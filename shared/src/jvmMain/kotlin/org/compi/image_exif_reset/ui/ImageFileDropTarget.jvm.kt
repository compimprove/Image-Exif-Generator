package org.compi.image_exif_reset.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTargetModifierNode
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo

actual fun Modifier.imageFileDropTarget(
    onDropped: (List<String>) -> Unit,
    onActiveChanged: (Boolean) -> Unit,
): Modifier = this then ImageFileDropElement(onDropped, onActiveChanged)

private data class ImageFileDropElement(
    val onDropped: (List<String>) -> Unit,
    val onActiveChanged: (Boolean) -> Unit,
) : ModifierNodeElement<ImageFileDropNode>() {
    override fun create() = ImageFileDropNode(onDropped, onActiveChanged)

    override fun update(node: ImageFileDropNode) {
        node.onDropped = onDropped
        node.onActiveChanged = onActiveChanged
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "imageFileDropTarget"
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private class ImageFileDropNode(
    var onDropped: (List<String>) -> Unit,
    var onActiveChanged: (Boolean) -> Unit,
) : DelegatingNode() {
    private val target = object : DragAndDropTarget {
        override fun onEntered(event: DragAndDropEvent) {
            onActiveChanged(true)
        }

        override fun onExited(event: DragAndDropEvent) {
            onActiveChanged(false)
        }

        override fun onEnded(event: DragAndDropEvent) {
            onActiveChanged(false)
        }

        override fun onDrop(event: DragAndDropEvent): Boolean {
            onActiveChanged(false)
            val files = (event.dragData() as? DragData.FilesList)?.readFiles().orEmpty()
            if (files.isEmpty()) return false
            onDropped(files)
            return true
        }
    }

    init {
        delegate(
            DragAndDropTargetModifierNode(
                shouldStartDragAndDrop = { event -> event.dragData() is DragData.FilesList },
                target = target,
            ),
        )
    }
}
