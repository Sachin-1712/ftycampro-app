package dev.ftycam.transport.pppp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Asserts the complete login datagram is byte-identical to the one the vendor app
 * sent and the camera accepted.
 *
 * This is the strongest possible check on the command layer: if these 180 bytes
 * match, nothing about the packet's *content* can explain a failure to connect,
 * and the investigation moves to session state, ports or timing.
 */
class LoginPacketByteTest {

    private val vendorLoginPacket: ByteArray = (
        "f1d000b0" +                       // DATA, len 176
        "d1000000" +                       // sub-header: marker, channel 0, seq 0
        "110a2010a400ff00" +               // cmd 0x2010, len 164 LE, FF 00
        "00000000" +                       // payload[0..3]
        "6f" + "01".repeat(27) +           // payload[4] then padding to 32
        "60656c686f" + "01".repeat(123) +  // "admin" at 32, padding to 160
        "60656c68"                         // "admi" at 160
        ).let { hex -> ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }

    @Test
    fun `our login datagram matches the vendor app byte for byte`() {
        val ours = PpppProtocol.data(
            channel = PpppProtocol.Channel.COMMAND,
            sequence = 0,
            body = PpppCommands.login("admin", "admin"),
        )

        assertEquals(
            "length differs",
            vendorLoginPacket.size,
            ours.size,
        )
        for (i in vendorLoginPacket.indices) {
            assertEquals(
                "byte $i differs (expected 0x%02X, got 0x%02X)".format(vendorLoginPacket[i], ours[i]),
                vendorLoginPacket[i],
                ours[i],
            )
        }
    }
}
