package dev.ftycam.transport.pppp

/**
 * Wire format for the CS2 Network PPPP/PPCS family.
 *
 * Deliberately pure — no sockets, no Android, no coroutines — so the framing can
 * be unit-tested against bytes captured from the real device. This is the Kotlin
 * counterpart of `tools/poc_client.py`; the two must agree, because the Python
 * one is where the protocol gets figured out and this one is where it ships.
 *
 * Packet layout:
 *
 *     +--------+--------+-----------------+------------------+
 *     |  0xF1  |  type  |  length (be16)  |  payload         |
 *     +--------+--------+-----------------+------------------+
 *
 * Status: the framing is well-established for this SDK family. The DID packing in
 * [encodeUid] and the DRW sub-header offsets in [DrwHeader] vary between builds
 * and are marked where they need confirming against a capture.
 */
object PpppProtocol {

    const val MAGIC: Byte = 0xF1.toByte()
    const val HEADER_SIZE: Int = 4
    const val DEFAULT_PORT: Int = 32108

    /** Largest datagram worth attempting to parse. */
    const val MAX_PACKET_SIZE: Int = 2048

    object MessageType {
        const val HELLO = 0x00
        const val HELLO_ACK = 0x01
        const val QUERY_DID = 0x08
        const val QUERY_DID_ACK = 0x09
        const val DEV_LGN = 0x10
        const val DEV_LGN_ACK = 0x11
        const val P2P_REQ = 0x20
        const val LAN_SEARCH = 0x30
        const val LAN_NOTIFY = 0x31
        const val LAN_NOTIFY_ACK = 0x32
        const val PUNCH_TO = 0x40
        const val PUNCH_PKT = 0x41
        const val PUNCH_READY = 0x42
        const val P2P_RDY = 0x50
        const val DRW = 0x70
        const val DRW_ACK = 0x71
        const val ALIVE = 0xF0
        const val ALIVE_ACK = 0xF1
        const val CLOSE = 0xF8

        fun name(type: Int): String = when (type) {
            HELLO -> "HELLO"
            HELLO_ACK -> "HELLO_ACK"
            QUERY_DID -> "QUERY_DID"
            QUERY_DID_ACK -> "QUERY_DID_ACK"
            DEV_LGN -> "DEV_LGN"
            DEV_LGN_ACK -> "DEV_LGN_ACK"
            P2P_REQ -> "P2P_REQ"
            LAN_SEARCH -> "LAN_SEARCH"
            LAN_NOTIFY -> "LAN_NOTIFY"
            LAN_NOTIFY_ACK -> "LAN_NOTIFY_ACK"
            PUNCH_TO -> "PUNCH_TO"
            PUNCH_PKT -> "PUNCH_PKT"
            PUNCH_READY -> "PUNCH_READY"
            P2P_RDY -> "P2P_RDY"
            DRW -> "DRW"
            DRW_ACK -> "DRW_ACK"
            ALIVE -> "ALIVE"
            ALIVE_ACK -> "ALIVE_ACK"
            CLOSE -> "CLOSE"
            else -> "UNKNOWN_0x%02X".format(type)
        }
    }

    /** Channel numbering inside DRW. Confirm against a capture before relying on it. */
    object Channel {
        const val CONTROL = 0
        const val VIDEO = 1
        const val AUDIO = 2
    }

    data class Packet(val type: Int, val payload: ByteArray = ByteArray(0)) {

        fun encode(): ByteArray {
            val out = ByteArray(HEADER_SIZE + payload.size)
            out[0] = MAGIC
            out[1] = type.toByte()
            out[2] = ((payload.size shr 8) and 0xFF).toByte()
            out[3] = (payload.size and 0xFF).toByte()
            payload.copyInto(out, HEADER_SIZE)
            return out
        }

        val typeName: String get() = MessageType.name(type)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Packet) return false
            return type == other.type && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int = 31 * type + payload.contentHashCode()

        override fun toString(): String =
            "$typeName(len=${payload.size}) ${payload.take(16).joinToString(" ") { "%02x".format(it) }}"
    }

    /**
     * Parse one datagram.
     *
     * Returns null rather than throwing for anything that isn't a PPPP packet:
     * during discovery the app broadcasts to the whole subnet and will receive
     * unrelated replies, and those are normal rather than exceptional.
     */
    fun decode(data: ByteArray, length: Int = data.size): Packet? {
        if (length < HEADER_SIZE || data[0] != MAGIC) return null
        val declared = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        // Trust the datagram over the length field: a truncated or over-declared
        // packet is still worth surfacing, and clamping avoids a crash on hostile
        // or corrupt input.
        val available = (length - HEADER_SIZE).coerceAtLeast(0)
        val size = minOf(declared, available)
        return Packet(
            type = data[1].toInt() and 0xFF,
            payload = data.copyOfRange(HEADER_SIZE, HEADER_SIZE + size),
        )
    }

    fun lanSearch(): ByteArray = Packet(MessageType.LAN_SEARCH).encode()

    fun queryDid(): ByteArray = Packet(MessageType.QUERY_DID).encode()

    fun alive(): ByteArray = Packet(MessageType.ALIVE).encode()

    fun aliveAck(): ByteArray = Packet(MessageType.ALIVE_ACK).encode()

    fun close(): ByteArray = Packet(MessageType.CLOSE).encode()

    fun drwAck(payload: ByteArray): ByteArray =
        Packet(MessageType.DRW_ACK, payload.copyOfRange(0, minOf(4, payload.size))).encode()

    /**
     * Pack a UID into the 20-byte DID structure: 8-byte prefix, 4-byte big-endian
     * serial, 8-byte check block.
     *
     * CONFIRMED for this device (finding 01): re-encoding the UID from a captured
     * PUNCH_PKT reproduces the wire bytes exactly. Other builds of this SDK may
     * pack the DID differently, so treat this as verified for the XMSYINA-prefix
     * family and re-check if a device with a different prefix behaves oddly.
     */
    fun encodeUid(uid: String): ByteArray {
        val parts = uid.trim().uppercase().replace('_', '-').split('-')
        require(parts.size == 3) { "Expected PREFIX-SERIAL-CHECK, got '$uid'" }
        val (prefix, serial, check) = parts
        val serialValue = serial.toLongOrNull()
            ?: throw IllegalArgumentException("Serial '$serial' is not numeric")

        return ByteArray(20).also { out ->
            prefix.toByteArray(Charsets.US_ASCII).copyInto(out, 0, 0, minOf(8, prefix.length))
            out[8] = ((serialValue shr 24) and 0xFF).toByte()
            out[9] = ((serialValue shr 16) and 0xFF).toByte()
            out[10] = ((serialValue shr 8) and 0xFF).toByte()
            out[11] = (serialValue and 0xFF).toByte()
            check.toByteArray(Charsets.US_ASCII).copyInto(out, 12, 0, minOf(8, check.length))
        }
    }

    /** Recover a printable UID from a DID blob in a LAN_NOTIFY / PUNCH_PKT reply. */
    fun decodeUid(payload: ByteArray): String? {
        if (payload.size < 12) return null
        val prefix = payload.copyOfRange(0, 8).takeWhile { it != 0.toByte() }
            .toByteArray().toString(Charsets.US_ASCII)
        if (prefix.isEmpty() || !prefix.all { it.isLetterOrDigit() }) return null
        val serial = ((payload[8].toLong() and 0xFF) shl 24) or
            ((payload[9].toLong() and 0xFF) shl 16) or
            ((payload[10].toLong() and 0xFF) shl 8) or
            (payload[11].toLong() and 0xFF)
        val check = if (payload.size >= 20) {
            payload.copyOfRange(12, 20).takeWhile { it != 0.toByte() }
                .toByteArray().toString(Charsets.US_ASCII)
        } else {
            ""
        }
        return if (check.isEmpty()) {
            "%s-%06d".format(prefix, serial)
        } else {
            "%s-%06d-%s".format(prefix, serial, check)
        }
    }

    /**
     * Sub-header carried at the front of every DRW payload.
     *
     * UNCONFIRMED offsets. `tools/pcap_triage.py --dump-flow N` prints the byte
     * positions that stay constant across packets, which is how these get pinned
     * down. Until then the defaults are the common case for this SDK family.
     */
    data class DrwHeader(val channel: Int, val sequence: Int, val bodyOffset: Int) {
        companion object {
            const val SIZE = 4

            fun parse(payload: ByteArray): DrwHeader? {
                if (payload.size <= SIZE) return null
                return DrwHeader(
                    channel = payload[0].toInt() and 0xFF,
                    sequence = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF),
                    bodyOffset = SIZE,
                )
            }
        }
    }
}
