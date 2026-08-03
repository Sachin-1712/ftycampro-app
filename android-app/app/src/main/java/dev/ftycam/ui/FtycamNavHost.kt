package dev.ftycam.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.ftycam.ui.addcamera.AddCameraScreen
import dev.ftycam.ui.cameras.CameraListScreen
import dev.ftycam.ui.live.LiveScreen
import dev.ftycam.ui.settings.SettingsScreen

object Routes {
    const val CAMERAS = "cameras"
    const val ADD_CAMERA = "add_camera"
    const val SETTINGS = "settings"
    const val LIVE = "live/{cameraId}"

    fun live(cameraId: String) = "live/$cameraId"
}

@Composable
fun FtycamNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.CAMERAS) {

        composable(Routes.CAMERAS) {
            CameraListScreen(
                onCameraClick = { navController.navigate(Routes.live(it.id)) },
                onAddClick = { navController.navigate(Routes.ADD_CAMERA) },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.ADD_CAMERA) {
            AddCameraScreen(onDone = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.LIVE,
            arguments = listOf(navArgument("cameraId") { type = NavType.StringType }),
        ) { entry ->
            LiveScreen(
                cameraId = entry.arguments?.getString("cameraId").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }
    }
}
