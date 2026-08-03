package dev.ftycam.transport.pppp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Framing tests.
 *
 * These are the tests that matter most right now, because the framing is the one
 * part of the protocol that is understood well enough to be asserted on. When a
 * capture yields real device bytes, add them here as fixtures — a regression in
 * the parser should fail on real data, not just on data this code generated
 * itself.
 */
class PpppProtocolTest {

    @Test
    fun `lan search is the documented four byte magic`() {
        assertArrayEquals(
            byteArrayOf(0xF1.toByte(), 0x30, 0x00, 0x00),
            PpppProtocol.lanSearch(),
        )
    }

    @Test
    fun `encode writes a big endian length`() {
        val payload = ByteArray(300) { 0x41 }
        val encoded = PpppProtocol.Packet(PpppProtocol.MessageType.DRW, payload).encode()

        assertEquals(0xF1.toByte(), encoded[0])
        assertEquals(0x70.toByte(), encoded[1])
        assertEquals(0x01.toByte(), encoded[2]) // 300 = 0x012C
        assertEquals(0x2C.toByte(), encoded[3])
        assertEquals(304, encoded.size)
    }

    @Test
    fun `decode round trips an encoded packet`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val original = PpppProtocol.Packet(PpppProtocol.MessageType.P2P_REQ, payload)

        val decoded = PpppProtocol.decode(original.encode())

        assertEquals(original, decoded)
    }

    @Test
    fun `decode rejects a packet without the magic byte`() {
        assertNull(PpppProtocol.decode(byteArrayOf(0x00, 0x30, 0x00, 0x00)))
    }

    @Test
    fun `decode rejects a runt`() {
        assertNull(PpppProtocol.decode(byteArrayOf(0xF1.toByte(), 0x30)))
    }

    /**
     * A device that declares more payload than it sent, or a datagram truncated in
     * flight, must not take the parser out. Discovery broadcasts to the whole
     * subnet, so the parser sees whatever else is on the network too.
     */
    @Test
    fun `decode clamps an over declared length instead of overrunning`() {
        val malformed = byteArrayOf(0xF1.toByte(), 0x70, 0xFF.toByte(), 0xFF.toByte(), 1, 2, 3)

        val decoded = PpppProtocol.decode(malformed)

        assertEquals(3, decoded?.payload?.size)
    }

    @Test
    fun `decode honours the datagram length over the buffer size`() {
        val buffer = ByteArray(2048)
        PpppProtocol.Packet(PpppProtocol.MessageType.ALIVE, byteArrayOf(9, 9))
            .encode()
            .copyInto(buffer)

        val decoded = PpppProtocol.decode(buffer, length = 6)

        assertEquals(PpppProtocol.MessageType.ALIVE, decoded?.type)
        assertArrayEquals(byteArrayOf(9, 9), decoded?.payload)
    }

    @Test
    fun `uid encodes to twenty bytes with a big endian serial`() {
        val did = PpppProtocol.encodeUid("ABCD-123456-EFGHI")

        assertEquals(20, did.size)
        assertEquals("ABCD", did.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals(0x0001E240, // 123456
            ((did[8].toInt() and 0xFF) shl 24) or
                ((did[9].toInt() and 0xFF) shl 16) or
                ((did[10].toInt() and 0xFF) shl 8) or
                (did[11].toInt() and 0xFF))
        assertEquals("EFGHI", did.copyOfRange(12, 17).toString(Charsets.US_ASCII))
    }

    @Test
    fun `uid round trips through encode and decode`() {
        val decoded = PpppProtocol.decodeUid(PpppProtocol.encodeUid("WXYZ-004242-ABCDE"))

        assertEquals("WXYZ-004242-ABCDE", decoded)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `uid without three parts is rejected`() {
        PpppProtocol.encodeUid("NOTAUID")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `uid with a non numeric serial is rejected`() {
        PpppProtocol.encodeUid("ABCD-XXXXXX-EFGHI")
    }

    @Test
    fun `decode uid returns null for a blob that is not one`() {
        assertNull(PpppProtocol.decodeUid(ByteArray(20) { 0xFF.toByte() }))
    }

    @Test
    fun `message type names cover the handshake`() {
        assertEquals("LAN_SEARCH", PpppProtocol.MessageType.name(0x30))
        assertEquals("P2P_RDY", PpppProtocol.MessageType.name(0x50))
        assertTrue(PpppProtocol.MessageType.name(0xAB).startsWith("UNKNOWN"))
    }

    @Test
    fun `drw header parse rejects a payload with no body`() {
        assertNull(PpppProtocol.DrwHeader.parse(byteArrayOf(1, 0, 0, 0)))
    }

    /**
     * Real bytes captured from the device on 2026-08-03 (finding 01). This is the
     * fixture that turns the encode/decode tests from self-consistency checks into
     * regression tests against ground truth: a change that breaks parsing of an
     * actual PUNCH_PKT fails here.
     */
    @Test
    fun `decodes a real captured PUNCH_PKT`() {
        val onWire = byteArrayOf(
            0xF1.toByte(), 0x41, 0x00, 0x14,
            0x58, 0x4D, 0x53, 0x59, 0x49, 0x4E, 0x41, 0x00, // "XMSYINA\0"
            0x00, 0x0B, 0xC9.toByte(), 0x6B,                 // serial 772459 BE
            0x56, 0x4E, 0x59, 0x55, 0x4B, 0x00, 0x00, 0x00,  // "VNYUK\0\0\0"
        )

        val packet = PpppProtocol.decode(onWire)

        assertEquals(PpppProtocol.MessageType.PUNCH_PKT, packet?.type)
        assertEquals("XMSYINA-772459-VNYUK", PpppProtocol.decodeUid(packet!!.payload))
    }

    /** Re-encoding the captured UID must reproduce the wire payload byte for byte. */
    @Test
    fun `encodes the captured uid back to the wire bytes`() {
        val expectedPayload = byteArrayOf(
            0x58, 0x4D, 0x53, 0x59, 0x49, 0x4E, 0x41, 0x00,
            0x00, 0x0B, 0xC9.toByte(), 0x6B,
            0x56, 0x4E, 0x59, 0x55, 0x4B, 0x00, 0x00, 0x00,
        )

        assertArrayEquals(expectedPayload, PpppProtocol.encodeUid("XMSYINA-772459-VNYUK"))
    }

    @Test
    fun `drw header reads channel and sequence`() {
        val header = PpppProtocol.DrwHeader.parse(byteArrayOf(0x01, 0x00, 0x12, 0x34, 0xAA.toByte()))

        assertEquals(PpppProtocol.Channel.VIDEO, header?.channel)
        assertEquals(0x1234, header?.sequence)
        assertEquals(4, header?.bodyOffset)
    }
}
