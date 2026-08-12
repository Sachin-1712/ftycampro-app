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

    /**
     * Message types **as used by this camera's firmware** (FTYA, 2.2.2.45).
     *
     * This build does not use the documented CS2/PPPP numbering for the session,
     * keepalive and data messages. Confirmed from a capture of the vendor app
     * (finding 05):
     *
     * | Role         | Documented | This firmware |
     * |--------------|-----------|---------------|
     * | session open | 0x20      | **0x42**      |
     * | keepalive    | 0xF0/0xF1 | **0xE0/0xE1** |
     * | data         | 0x70/0x71 | **0xD0/0xD1** |
     *
     * Sending the documented values gets silence, which is exactly what stalled
     * this project — see finding 02.
     */
    object MessageType {
        const val LAN_SEARCH = 0x30
        const val LAN_NOTIFY = 0x31
        const val PUNCH_PKT = 0x41

        /** Session open, and the camera's acceptance. Payload is the 20-byte DID. */
        const val PUNCH_READY = 0x42

        const val DATA = 0xD0
        const val DATA_ACK = 0xD1
        const val ALIVE = 0xE0
        const val ALIVE_ACK = 0xE1
        const val CLOSE = 0xF8

        // Documented values, kept so a trace can name them if another build
        // (or another device) ever uses them.
        const val LEGACY_P2P_REQ = 0x20
        const val LEGACY_P2P_RDY = 0x50
        const val LEGACY_DRW = 0x70

        fun name(type: Int): String = when (type) {
            LAN_SEARCH -> "LAN_SEARCH"
            LAN_NOTIFY -> "LAN_NOTIFY"
            PUNCH_PKT -> "PUNCH_PKT"
            PUNCH_READY -> "PUNCH_READY"
            DATA -> "DATA"
            DATA_ACK -> "DATA_ACK"
            ALIVE -> "ALIVE"
            ALIVE_ACK -> "ALIVE_ACK"
            CLOSE -> "CLOSE"
            LEGACY_P2P_REQ -> "P2P_REQ(legacy)"
            LEGACY_P2P_RDY -> "P2P_RDY(legacy)"
            LEGACY_DRW -> "DRW(legacy)"
            else -> "UNKNOWN_0x%02X".format(type)
        }
    }

    /** Channel numbering inside the DATA sub-header. Confirmed in finding 05. */
    object Channel {
        const val COMMAND = 0
        const val VIDEO = 1

        /** Not seen in the capture; audio may share the command channel. */
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

    fun alive(): ByteArray = Packet(MessageType.ALIVE).encode()

    fun aliveAck(): ByteArray = Packet(MessageType.ALIVE_ACK).encode()

    fun close(): ByteArray = Packet(MessageType.CLOSE).encode()

    /** Session opener: PUNCH_READY carrying the 20-byte DID. */
    fun punchReady(uid: String): ByteArray =
        Packet(MessageType.PUNCH_READY, encodeUid(uid)).encode()

    /**
     * Wrap a body for the data channel.
     *
     * Sub-header is `D1 <channel> <seq:u16be>`, then the body.
     */
    fun data(channel: Int, sequence: Int, body: ByteArray): ByteArray {
        val payload = ByteArray(DrwHeader.SIZE + body.size)
        payload[0] = DATA_SUBHEADER_MARKER
        payload[1] = channel.toByte()
        payload[2] = ((sequence shr 8) and 0xFF).toByte()
        payload[3] = (sequence and 0xFF).toByte()
        body.copyInto(payload, DrwHeader.SIZE)
        return Packet(MessageType.DATA, payload).encode()
    }

    /**
     * Acknowledge received data packets.
     *
     * Format is `D2 00 <count:u16be> <seq:u16be>...` — one ack can cover several
     * sequence numbers, which is what the vendor app does.
     */
    fun dataAck(channel: Int, sequences: List<Int>): ByteArray {
        val payload = ByteArray(4 + sequences.size * 2)
        payload[0] = ACK_SUBHEADER_MARKER
        payload[1] = channel.toByte()
        payload[2] = ((sequences.size shr 8) and 0xFF).toByte()
        payload[3] = (sequences.size and 0xFF).toByte()
        sequences.forEachIndexed { index, seq ->
            payload[4 + index * 2] = ((seq shr 8) and 0xFF).toByte()
            payload[5 + index * 2] = (seq and 0xFF).toByte()
        }
        return Packet(MessageType.DATA_ACK, payload).encode()
    }

    const val DATA_SUBHEADER_MARKER: Byte = 0xD1.toByte()
    const val ACK_SUBHEADER_MARKER: Byte = 0xD2.toByte()

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
     * Sub-header at the front of every DATA payload: `D1 <channel> <seq:u16be>`.
     *
     * Confirmed against the vendor-app capture (finding 05).
     */
    data class DrwHeader(val channel: Int, val sequence: Int, val bodyOffset: Int) {
        companion object {
            const val SIZE = 4

            fun parse(payload: ByteArray): DrwHeader? {
                if (payload.size <= SIZE) return null
                if (payload[0] != DATA_SUBHEADER_MARKER) return null
                return DrwHeader(
                    channel = payload[1].toInt() and 0xFF,
                    sequence = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF),
                    bodyOffset = SIZE,
                )
            }
        }
    }
}
