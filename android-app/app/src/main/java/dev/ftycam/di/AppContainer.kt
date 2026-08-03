package dev.ftycam.di

import android.content.Context
import dev.ftycam.data.CameraRepository
import dev.ftycam.data.SecureCameraStore
import dev.ftycam.data.SettingsRepository
import dev.ftycam.stream.MediaWriter
import dev.ftycam.transport.TransportFactory

/**
 * Manual dependency container.
 *
 * Hilt would do this with less typing, at the cost of a compiler plugin, KSP, and
 * a build that breaks whenever those disagree with the Kotlin version. This app
 * has six dependencies and a transport layer that is going to be rewritten once
 * the protocol is known; the graph is not the hard part, and keeping the build
 * boring is worth more than the annotations.
 *
 * Held by [dev.ftycam.FtycamApplication] and reached through it.
 */
class AppContainer(context: Context) {

    private val applicationContext: Context = context.applicationContext

    val secureStore: SecureCameraStore by lazy { SecureCameraStore(applicationContext) }

    val cameraRepository: CameraRepository by lazy { CameraRepository(secureStore) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(applicationContext) }

    val transportFactory: TransportFactory by lazy { TransportFactory() }

    val mediaWriter: MediaWriter by lazy { MediaWriter(applicationContext) }
}
