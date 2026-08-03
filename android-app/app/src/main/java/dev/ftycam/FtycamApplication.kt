package dev.ftycam

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.ftycam.di.AppContainer
import dev.ftycam.ui.addcamera.AddCameraViewModel
import dev.ftycam.ui.cameras.CameraListViewModel
import dev.ftycam.ui.live.LiveViewModel
import dev.ftycam.ui.settings.SettingsViewModel
import dev.ftycam.util.Log

class FtycamApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Log.verbose = BuildConfig.DEBUG
        Log.i(TAG, "ftycam ${BuildConfig.VERSION_NAME} started")
    }

    private companion object {
        const val TAG = "FtycamApplication"
    }
}

/** Key used to reach the container from a [CreationExtras]-based factory. */
private val CreationExtras.container: AppContainer
    get() = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as FtycamApplication)
        .container

/**
 * View-model factories.
 *
 * Collected here rather than beside each view model so that the wiring is
 * visible in one place — with manual DI, a graph scattered across a dozen
 * companion objects is the thing that actually costs time later.
 */
object ViewModelFactories {

    val cameraList = viewModelFactory {
        initializer { CameraListViewModel(container.cameraRepository) }
    }

    val addCamera = viewModelFactory {
        initializer { AddCameraViewModel(container.cameraRepository) }
    }

    val settings = viewModelFactory {
        initializer { SettingsViewModel(container.settingsRepository) }
    }

    val live = viewModelFactory {
        initializer {
            LiveViewModel(
                cameraRepository = container.cameraRepository,
                transportFactory = container.transportFactory,
                mediaWriter = container.mediaWriter,
                settingsRepository = container.settingsRepository,
            )
        }
    }
}
