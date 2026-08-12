package dev.ftycam.transport.pppp

/**
 * The command layer that rides on channel 0 of the data channel.
 *
 * Decoded from a capture of the vendor app (finding 05).
 *
 * ## Wire format
 *
 * ```
 * 11 0A <cmd:u16be> <payloadLen:u16le> FF 00 <payload...>
 * ```
 *
 * Requests use an even command id, the reply is the next value up: `2010` → `2011`,
 * `0810` → `0811`, `1830` → `1831`.
 *
 * ## Obfuscation
 *
 * Payloads are **XOR'd with 0x01**. That is all — no key exchange, no negotiation,
 * no real cryptography. Plaintext `00` padding appears on the wire as `01`, and
 * `"admin"` appears as `60 65 6C 68 6F`. Calling it encryption would overstate it;
 * it deters casual inspection and nothing more.
 */
object PpppCommands {

    const val HEADER_SIZE = 8
    private const val MAGIC_0: Byte = 0x11
    private const val MAGIC_1: Byte = 0x0A
    private const val OBFUSCATION_KEY: Byte = 0x01

    object Cmd {
        const val LOGIN = 0x2010
        const val LOGIN_REPLY = 0x2011

        /** Device info / status. Answered with a 128-byte block. */
        const val DEVICE_INFO = 0x0810
        const val DEVICE_INFO_REPLY = 0x0811

        // Sent by the vendor app in the burst immediately before video begins.
        // Which one is literally "start streaming" is not isolated yet — see
        // startStreamSequence().
        const val STREAM_SETUP_A = 0x1830
        const val STREAM_SETUP_B = 0x1030
        const val STREAM_SETUP_C = 0xFF50
        const val STREAM_SETUP_D = 0x1930
        const val STREAM_SETUP_E = 0x0530
        const val STREAM_SETUP_F = 0x3210
    }

    /** XOR a buffer with the obfuscation key. Self-inverse. */
    fun deobfuscate(data: ByteArray): ByteArray =
        ByteArray(data.size) { (data[it].toInt() xor OBFUSCATION_KEY.toInt()).toByte() }

    /** Same operation; named separately so call sites read correctly. */
    fun obfuscate(data: ByteArray): ByteArray = deobfuscate(data)

    /** Build a command frame. [payload] is given in clear and obfuscated here. */
    fun frame(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(HEADER_SIZE + payload.size)
        out[0] = MAGIC_0
        out[1] = MAGIC_1
        out[2] = ((cmd shr 8) and 0xFF).toByte()
        out[3] = (cmd and 0xFF).toByte()
        out[4] = (payload.size and 0xFF).toByte()          // length is little-endian
        out[5] = ((payload.size shr 8) and 0xFF).toByte()
        out[6] = 0xFF.toByte()
        out[7] = 0x00
        obfuscate(payload).copyInto(out, HEADER_SIZE)
        return out
    }

    data class Reply(val cmd: Int, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Reply && cmd == other.cmd && payload.contentEquals(other.payload)

        override fun hashCode(): Int = 31 * cmd + payload.contentHashCode()
    }

    /** Parse a command frame from channel 0. Payload is returned deobfuscated. */
    fun parse(body: ByteArray): Reply? {
        if (body.size < HEADER_SIZE) return null
        if (body[0] != MAGIC_0 || body[1] != MAGIC_1) return null
        val cmd = ((body[2].toInt() and 0xFF) shl 8) or (body[3].toInt() and 0xFF)
        val declared = ((body[5].toInt() and 0xFF) shl 8) or (body[4].toInt() and 0xFF)
        val available = body.size - HEADER_SIZE
        val length = minOf(declared, available).coerceAtLeast(0)
        return Reply(cmd, deobfuscate(body.copyOfRange(HEADER_SIZE, HEADER_SIZE + length)))
    }

    /**
     * The 164-byte login payload.
     *
     * Offsets 32 (username) and 160 (password) are confirmed from the capture. The
     * 32-byte prefix is **replayed verbatim** from the observed login: its fields
     * are not identified, and reproducing bytes that are known to work beats
     * guessing at a structure. Revisit if a second capture shows it varying.
     *
     * Note the password field starts at 160 in a 164-byte payload, so only four
     * bytes of it fit. That is what the vendor app sent, and the camera accepted
     * it — reproduced rather than "corrected".
     */
    fun loginPayload(username: String, password: String): ByteArray {
        val plain = ByteArray(LOGIN_PAYLOAD_SIZE)
        OBSERVED_LOGIN_PREFIX.copyInto(plain, 0)

        val user = username.toByteArray(Charsets.US_ASCII)
        user.copyInto(plain, USERNAME_OFFSET, 0, minOf(user.size, USERNAME_MAX))

        val pass = password.toByteArray(Charsets.US_ASCII)
        val room = LOGIN_PAYLOAD_SIZE - PASSWORD_OFFSET
        pass.copyInto(plain, PASSWORD_OFFSET, 0, minOf(pass.size, room))

        return plain
    }

    fun login(username: String, password: String): ByteArray =
        frame(Cmd.LOGIN, loginPayload(username, password))

    /**
     * Extract the session token from a `2011` login reply.
     *
     * Reply payload is `<result:u32> <token:4 bytes> ...`; the token is ASCII and
     * arrives in the clear. Every later command carries it obfuscated.
     */
    fun sessionToken(reply: Reply): String? {
        if (reply.cmd != Cmd.LOGIN_REPLY || reply.payload.size < TOKEN_OFFSET + TOKEN_SIZE) return null
        val result = reply.payload.copyOfRange(0, 4)
        if (result.any { it != 0.toByte() }) return null // non-zero result = rejected
        return reply.payload
            .copyOfRange(TOKEN_OFFSET, TOKEN_OFFSET + TOKEN_SIZE)
            .toString(Charsets.US_ASCII)
    }

    /** A short command that carries only the session token. */
    fun tokenCommand(cmd: Int, token: String): ByteArray =
        frame(cmd, token.toByteArray(Charsets.US_ASCII))

    /**
     * The command burst the vendor app sends immediately before video starts.
     *
     * Replayed as a sequence because the capture does not isolate which single
     * command is "start streaming" — all six are sent within the same millisecond
     * and video follows. Sending the observed sequence is the honest reproduction;
     * narrowing it to one command needs a capture that varies them.
     */
    fun startStreamSequence(token: String): List<ByteArray> = listOf(
        tokenCommand(Cmd.DEVICE_INFO, token),
        tokenCommand(Cmd.STREAM_SETUP_C, token),
        tokenCommand(Cmd.STREAM_SETUP_D, token),
        tokenCommand(Cmd.STREAM_SETUP_E, token),
        tokenCommand(Cmd.STREAM_SETUP_F, token),
    )

    private const val LOGIN_PAYLOAD_SIZE = 164
    private const val USERNAME_OFFSET = 32
    private const val USERNAME_MAX = 32
    private const val PASSWORD_OFFSET = 160
    private const val TOKEN_OFFSET = 4
    private const val TOKEN_SIZE = 4

    /** Bytes 0..31 of the observed login payload, in clear. */
    private val OBSERVED_LOGIN_PREFIX = byteArrayOf(
        0x01, 0x01, 0x01, 0x01, 0x6E, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    )
}
