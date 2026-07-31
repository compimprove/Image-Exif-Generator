package org.compi.image_exif_reset

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.compi.image_exif_reset.metadata.ExifToolMetadataEngine
import org.compi.image_exif_reset.processing.ImageResetController
import org.compi.image_exif_reset.processing.ImageResetProcessor

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Image EXIF Reset",
    ) {
        val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
        val controller = remember {
            ImageResetController(
                processor = ImageResetProcessor(ExifToolMetadataEngine()),
                scope = scope,
            )
        }
        DisposableEffect(controller) {
            onDispose {
                controller.close()
                scope.cancel()
            }
        }
        App(
            tasks = controller.tasks,
            onFilesDropped = controller::enqueue,
        )
    }
}
