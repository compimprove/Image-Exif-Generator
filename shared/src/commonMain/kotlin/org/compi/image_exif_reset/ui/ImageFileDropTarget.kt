package org.compi.image_exif_reset.ui

import androidx.compose.ui.Modifier

expect fun Modifier.imageFileDropTarget(
    onDropped: (List<String>) -> Unit,
    onActiveChanged: (Boolean) -> Unit,
): Modifier
