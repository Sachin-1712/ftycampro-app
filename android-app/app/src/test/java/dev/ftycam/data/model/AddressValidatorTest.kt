package dev.ftycam.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressValidatorTest {

    @Test
    fun `uid with hyphens is accepted and normalised to upper case`() {
        val result = AddressValidator.validateUid("abcd-123456-efghi")

        assertEquals(Address.Uid("ABCD-123456-EFGHI"), result.getOrNull())
    }

    @Test
    fun `uid without hyphens is accepted`() {
        assertTrue(AddressValidator.validateUid("ABCD123456EFGHI").isSuccess)
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        // Users paste UIDs out of the vendor app, and it is easy to catch a space.
        val result = AddressValidator.validateUid("  ABCD-123456-EFGHI  ")

        assertEquals(Address.Uid("ABCD-123456-EFGHI"), result.getOrNull())
    }

    @Test
    fun `empty uid is rejected with a usable message`() {
        val result = AddressValidator.validateUid("")

        assertTrue(result.isFailure)
        assertEquals("UID is required", result.exceptionOrNull()?.message)
    }

    @Test
    fun `malformed uid is rejected`() {
        assertTrue(AddressValidator.validateUid("not-a-uid").isFailure)
        assertTrue(AddressValidator.validateUid("12345").isFailure)
    }

    @Test
    fun `valid ipv4 with explicit port is accepted`() {
        val result = AddressValidator.validateNetwork("192.168.29.214", "32108")

        assertEquals(Address.Network("192.168.29.214", 32108), result.getOrNull())
    }

    @Test
    fun `empty port falls back to the protocol default`() {
        val result = AddressValidator.validateNetwork("192.168.29.214", "")

        assertEquals(Address.DEFAULT_PORT, (result.getOrNull())?.port)
    }

    @Test
    fun `hostnames are accepted`() {
        assertTrue(AddressValidator.validateNetwork("camera.local", "8554").isSuccess)
    }

    @Test
    fun `octets above 255 are rejected`() {
        assertTrue(AddressValidator.validateNetwork("192.168.29.999", "80").isFailure)
    }

    @Test
    fun `non numeric port is rejected`() {
        val result = AddressValidator.validateNetwork("192.168.29.214", "abc")

        assertTrue(result.isFailure)
        assertEquals("Port must be a number", result.exceptionOrNull()?.message)
    }

    @Test
    fun `out of range port is rejected`() {
        assertTrue(AddressValidator.validateNetwork("192.168.29.214", "0").isFailure)
        assertTrue(AddressValidator.validateNetwork("192.168.29.214", "70000").isFailure)
    }

    @Test
    fun `empty host is rejected`() {
        assertTrue(AddressValidator.validateNetwork("", "80").isFailure)
    }
}
