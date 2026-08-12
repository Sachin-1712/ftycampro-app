package dev.ftycam.transport

import dev.ftycam.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only record of one connection attempt, for the diagnostics panel.
 *
 * Every line carries a timestamp, the UID under test, and the endpoint involved,
 * so a failed attempt can be read back without re-running it. This is the app-side
 * counterpart of the `-v` output from `tools/poc_client.py`.
 *
 * **Never pass credentials to any method here.** The trace is displayed on screen
 * and exportable from Settings; it is for protocol bytes and endpoints only.
 */
class ProtocolTrace(private val uid: String?) {

    private val lines = mutableListOf<String>()
    private val timestamps = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun sent(packetType: String, destination: String) =
        add("TX  $packetType -> $destination")

    @Synchronized
    fun received(packetType: String, source: String) =
        add("RX  $packetType <- $source")

    @Synchronized
    fun silence(afterPacket: String, destination: String, waitedMs: Long) =
        add("--  no reply to $afterPacket from $destination after ${waitedMs}ms")

    @Synchronized
    fun note(text: String) = add("..  $text")

    private fun add(body: String) {
        val stamp = timestamps.format(Date())
        val identity = uid ?: "(no uid)"
        val line = "$stamp  [$identity]  $body"
        lines += line
        Log.i(TAG, line)
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    private companion object {
        const val TAG = "PpppTrace"
    }
}
