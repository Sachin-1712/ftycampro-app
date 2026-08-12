package dev.ftycam.data.model

import org.json.JSONObject
import java.util.UUID

/**
 * A camera the user has added.
 *
 * Credentials are deliberately *not* in this class. [Camera] is passed around the
 * UI layer freely — into Compose state, into logs, into `toString()` on a crash —
 * and a password that lives here will eventually end up somewhere it shouldn't.
 * They are held separately by `SecureCameraStore` and fetched only at the moment a
 * transport actually needs them.
 */
data class Camera(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val address: Address,
    val transport: TransportKind = TransportKind.AUTO,
    val streamQuality: StreamQuality = StreamQuality.HIGH,
    val audioEnabled: Boolean = true,
    /**
     * Where this camera was last seen. **Metadata only — never used to connect.**
     *
     * A UID camera is always relocated by fresh discovery, because DHCP moves it
     * and because its reply port is ephemeral. This field exists so the UI can say
     * "last seen at 192.168.29.24" without that address ever becoming an endpoint.
     */
    val lastKnownHost: String? = null,
    val lastSeenAtMillis: Long? = null,
) {
    val displayAddress: String
        get() = when (address) {
            is Address.Uid -> address.uid
            is Address.Network -> "${address.host}:${address.port}"
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("transport", transport.name)
        put("quality", streamQuality.name)
        put("audio", audioEnabled)
        lastKnownHost?.let { put("lastKnownHost", it) }
        lastSeenAtMillis?.let { put("lastSeenAt", it) }
        // The discovery reply port is deliberately absent: it is ephemeral, and
        // persisting it produced endpoints that were dead on arrival.
        when (address) {
            is Address.Uid -> {
                put("addressKind", "uid")
                put("uid", address.uid)
            }
            is Address.Network -> {
                put("addressKind", "network")
                put("host", address.host)
                put("port", address.port)
            }
        }
    }

    companion object {
        fun fromJson(json: JSONObject): Camera = Camera(
            id = json.getString("id"),
            name = json.getString("name"),
            address = when (json.optString("addressKind")) {
                "uid" -> Address.Uid(json.getString("uid"))
                else -> Address.Network(
                    host = json.getString("host"),
                    port = json.optInt("port", Address.DEFAULT_PORT),
                )
            },
            transport = runCatching { TransportKind.valueOf(json.optString("transport")) }
                .getOrDefault(TransportKind.AUTO),
            streamQuality = runCatching { StreamQuality.valueOf(json.optString("quality")) }
                .getOrDefault(StreamQuality.HIGH),
            audioEnabled = json.optBoolean("audio", true),
            lastKnownHost = json.optString("lastKnownHost").takeIf { it.isNotEmpty() },
            lastSeenAtMillis = json.optLong("lastSeenAt").takeIf { it > 0L },
        )
    }
}

/**
 * How to reach the camera.
 *
 * Both forms are supported because it isn't yet known which one works. A UID is
 * what the vendor app uses and is the only option if the device turns out to
 * require cloud rendezvous; a direct host/port is what a reproduced local
 * protocol would use, and is the outcome this project is aiming for.
 */
sealed interface Address {
    data class Uid(val uid: String) : Address
    data class Network(val host: String, val port: Int = DEFAULT_PORT) : Address

    companion object {
        /** PPPP/PPCS LAN discovery and session port. */
        const val DEFAULT_PORT: Int = 32108
    }
}

enum class TransportKind {
    /** Try each known transport in turn and keep whichever answers. */
    AUTO,

    /** CS2 Network PPPP/PPCS. The leading hypothesis for this hardware. */
    PPPP,

    /** Standard RTSP, via Media3's own client. Only if the camera turns out to speak it. */
    RTSP,
}

enum class StreamQuality { LOW, HIGH }

/** Validation for user-entered addresses, so the UI can explain what's wrong. */
object AddressValidator {

    // PREFIX-SERIAL-CHECK, hyphens optional, which covers the formats seen across
    // this SDK family.
    private val UID_PATTERN = Regex("^[A-Za-z]{3,8}-?\\d{4,8}-?[A-Za-z]{4,6}$")

    private val IPV4_PATTERN = Regex(
        "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$"
    )

    fun validateUid(raw: String): Result<Address.Uid> {
        val uid = raw.trim().uppercase()
        return when {
            uid.isEmpty() -> Result.failure(IllegalArgumentException("UID is required"))
            !UID_PATTERN.matches(uid) -> Result.failure(
                IllegalArgumentException("Expected a UID like ABCD-123456-EFGHI")
            )
            else -> Result.success(Address.Uid(uid))
        }
    }

    fun validateNetwork(host: String, port: String): Result<Address.Network> {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Address is required"))
        }
        // A string of only digits and dots is an attempt at an IPv4 address, so it
        // must be a *valid* one — otherwise "192.168.29.999" would sail through the
        // hostname branch below just because it contains a dot.
        val looksNumeric = trimmed.all { it.isDigit() || it == '.' }
        if (looksNumeric) {
            if (!IPV4_PATTERN.matches(trimmed)) {
                return Result.failure(IllegalArgumentException("Not a valid IP address"))
            }
        } else if (!trimmed.contains('.')) {
            return Result.failure(IllegalArgumentException("Not a valid address or hostname"))
        }
        val parsedPort = port.trim().ifEmpty { Address.DEFAULT_PORT.toString() }.toIntOrNull()
            ?: return Result.failure(IllegalArgumentException("Port must be a number"))
        if (parsedPort !in 1..65535) {
            return Result.failure(IllegalArgumentException("Port must be between 1 and 65535"))
        }
        return Result.success(Address.Network(trimmed, parsedPort))
    }
}
