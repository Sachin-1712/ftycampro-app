package dev.ftycam.transport.pppp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Command-layer tests, anchored on bytes captured from the vendor app
 * (finding 05). These are ground-truth fixtures, not self-consistency checks.
 */
class PpppCommandsTest {

    @Test
    fun `xor 0x01 recovers the username from the captured login`() {
        val onWire = byteArrayOf(0x60, 0x65, 0x6C, 0x68, 0x6F)

        val plain = PpppCommands.deobfuscate(onWire).toString(Charsets.US_ASCII)

        assertEquals("admin", plain)
    }

    @Test
    fun `xor 0x01 recovers the session token as later commands carry it`() {
        val onWire = byteArrayOf(0x51, 0x38, 0x30, 0x44) // "Q80D"

        val plain = PpppCommands.deobfuscate(onWire).toString(Charsets.US_ASCII)

        assertEquals("P91E", plain)
    }

    @Test
    fun `obfuscation is its own inverse`() {
        val original = "admin".toByteArray(Charsets.US_ASCII)

        val round = PpppCommands.deobfuscate(PpppCommands.obfuscate(original))

        assertArrayEquals(original, round)
    }

    /** Header is `11 0A <cmd:u16be> <len:u16le> FF 00`. */
    @Test
    fun `frame writes the captured header shape`() {
        val frame = PpppCommands.frame(PpppCommands.Cmd.LOGIN, ByteArray(164))

        assertEquals(0x11.toByte(), frame[0])
        assertEquals(0x0A.toByte(), frame[1])
        assertEquals(0x20.toByte(), frame[2]) // cmd 0x2010, big-endian
        assertEquals(0x10.toByte(), frame[3])
        assertEquals(0xA4.toByte(), frame[4]) // length 164, little-endian
        assertEquals(0x00.toByte(), frame[5])
        assertEquals(0xFF.toByte(), frame[6])
        assertEquals(172, frame.size)
    }

    @Test
    fun `login reproduces the captured header exactly`() {
        val captured = byteArrayOf(0x11, 0x0A, 0x20, 0x10, 0xA4.toByte(), 0x00, 0xFF.toByte(), 0x00)

        val login = PpppCommands.login("admin", "admin")

        assertArrayEquals(captured, login.copyOfRange(0, 8))
    }

    @Test
    fun `login places the username at the captured offset`() {
        val login = PpppCommands.login("admin", "admin")
        val payload = PpppCommands.deobfuscate(login.copyOfRange(PpppCommands.HEADER_SIZE, login.size))

        assertEquals("admin", payload.copyOfRange(32, 37).toString(Charsets.US_ASCII))
    }

    /**
     * The camera's real `2011` reply. Result code 0 then the ASCII token, in clear.
     */
    @Test
    fun `session token is read from a real login reply`() {
        val replyBody = byteArrayOf(
            0x11, 0x0A, 0x20, 0x11, 0x0C, 0x00, 0xFF.toByte(), 0x00,
        ) + PpppCommands.obfuscate(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x00,             // result = success
                0x50, 0x39, 0x31, 0x45,             // "P91E"
                0xFF.toByte(), 0x00, 0x00, 0x00,
            )
        )

        val reply = PpppCommands.parse(replyBody)

        assertEquals(PpppCommands.Cmd.LOGIN_REPLY, reply?.cmd)
        assertEquals("P91E", PpppCommands.sessionToken(reply!!))
    }

    @Test
    fun `a non zero result code is treated as a rejected login`() {
        val reply = PpppCommands.Reply(
            PpppCommands.Cmd.LOGIN_REPLY,
            byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x50, 0x39, 0x31, 0x45),
        )

        assertNull(PpppCommands.sessionToken(reply))
    }

    @Test
    fun `a reply that is not a login reply yields no token`() {
        val reply = PpppCommands.Reply(
            PpppCommands.Cmd.DEVICE_INFO_REPLY,
            ByteArray(16),
        )

        assertNull(PpppCommands.sessionToken(reply))
    }

    @Test
    fun `parse rejects a body without the 11 0A magic`() {
        assertNull(PpppCommands.parse(byteArrayOf(0x00, 0x00, 0x20, 0x10, 0x00, 0x00, 0x00, 0x00)))
    }

    /** A truncated reply must clamp rather than overrun. */
    @Test
    fun `parse clamps an over declared length`() {
        val body = byteArrayOf(
            0x11, 0x0A, 0x08, 0x11, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00,
            0x01, 0x02,
        )

        assertEquals(2, PpppCommands.parse(body)?.payload?.size)
    }

    @Test
    fun `stream setup sends the observed command burst carrying the token`() {
        val sequence = PpppCommands.startStreamSequence("P91E")

        assertEquals(5, sequence.size)
        sequence.forEach { frame ->
            // Every command carries the token, obfuscated.
            val payload = PpppCommands.deobfuscate(frame.copyOfRange(PpppCommands.HEADER_SIZE, frame.size))
            assertEquals("P91E", payload.toString(Charsets.US_ASCII))
        }
    }
}
