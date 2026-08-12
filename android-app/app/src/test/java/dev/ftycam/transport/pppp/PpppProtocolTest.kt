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
        val encoded = PpppProtocol.Packet(PpppProtocol.MessageType.DATA, payload).encode()

        assertEquals(0xF1.toByte(), encoded[0])
        assertEquals(0xD0.toByte(), encoded[1])
        assertEquals(0x01.toByte(), encoded[2]) // 300 = 0x012C
        assertEquals(0x2C.toByte(), encoded[3])
        assertEquals(304, encoded.size)
    }

    @Test
    fun `decode round trips an encoded packet`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val original = PpppProtocol.Packet(PpppProtocol.MessageType.PUNCH_READY, payload)

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
    fun `message type names use this firmware's numbering`() {
        assertEquals("LAN_SEARCH", PpppProtocol.MessageType.name(0x30))
        assertEquals("PUNCH_READY", PpppProtocol.MessageType.name(0x42))
        assertEquals("DATA", PpppProtocol.MessageType.name(0xD0))
        assertEquals("ALIVE", PpppProtocol.MessageType.name(0xE0))
        assertTrue(PpppProtocol.MessageType.name(0xAB).startsWith("UNKNOWN"))
    }

    @Test
    fun `drw header rejects a payload without the D1 marker`() {
        // 0x01 is a plausible-looking channel byte but not the sub-header marker.
        assertNull(PpppProtocol.DrwHeader.parse(byteArrayOf(0x01, 0x00, 0x12, 0x34, 0xAA.toByte())))
    }

    @Test
    fun `drw header parse rejects a payload with no body`() {
        assertNull(PpppProtocol.DrwHeader.parse(byteArrayOf(0xD1.toByte(), 0x00, 0x00, 0x00)))
    }

    /**
     * Real bytes from a PPPP `PUNCH_PKT` observed on the LAN (finding 01). This
     * device is *not* the project's camera — see finding 04 — but the packet is a
     * genuine capture and remains a valid parser regression fixture.
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

    /**
     * The DID from **this project's camera**, exactly as the vendor app sent it in
     * its PUNCH_READY session opener (finding 05). If `encodeUid` ever stops
     * reproducing these bytes, the handshake breaks.
     */
    @Test
    fun `encodes the FTYA camera uid to the bytes the vendor app sent`() {
        val fromVendorApp = byteArrayOf(
            0x46, 0x54, 0x59, 0x41, 0x00, 0x00, 0x00, 0x00, // "FTYA"
            0x00, 0x0B, 0x67, 0x59,                          // serial 747353 BE
            0x53, 0x5A, 0x4E, 0x54, 0x4C, 0x00, 0x00, 0x00,  // "SZNTL"
        )

        assertArrayEquals(fromVendorApp, PpppProtocol.encodeUid("FTYA-747353-SZNTL"))
    }

    /** The session opener the camera actually accepts. */
    @Test
    fun `punch ready wraps the did in an 0x42 packet`() {
        val packet = PpppProtocol.punchReady("FTYA-747353-SZNTL")

        assertEquals(0xF1.toByte(), packet[0])
        assertEquals(0x42.toByte(), packet[1])
        assertEquals(0x00.toByte(), packet[2])
        assertEquals(0x14.toByte(), packet[3]) // 20-byte DID
        assertEquals(24, packet.size)
    }

    @Test
    fun `drw header reads channel and sequence`() {
        val header = PpppProtocol.DrwHeader.parse(
            byteArrayOf(0xD1.toByte(), 0x01, 0x12, 0x34, 0xAA.toByte())
        )

        assertEquals(PpppProtocol.Channel.VIDEO, header?.channel)
        assertEquals(0x1234, header?.sequence)
        assertEquals(4, header?.bodyOffset)
    }

    @Test
    fun `data wraps a body with the D1 sub-header`() {
        val encoded = PpppProtocol.data(PpppProtocol.Channel.COMMAND, 7, byteArrayOf(0xAA.toByte()))

        assertEquals(0xD0.toByte(), encoded[1])
        assertEquals(0xD1.toByte(), encoded[4]) // sub-header marker
        assertEquals(0x00.toByte(), encoded[5]) // channel 0
        assertEquals(0x07.toByte(), encoded[7]) // sequence
    }

    /** Acks carry a count then that many sequence numbers, as the vendor app does. */
    @Test
    fun `data ack carries a count and the sequence list`() {
        val ack = PpppProtocol.dataAck(0, listOf(1, 2))

        assertEquals(0xD1.toByte(), ack[1]) // DATA_ACK message type
        assertEquals(0xD2.toByte(), ack[4]) // ack sub-header marker
        assertEquals(0x02.toByte(), ack[7]) // count = 2
        assertEquals(0x01.toByte(), ack[9]) // seq 1
        assertEquals(0x02.toByte(), ack[11]) // seq 2
    }
}
