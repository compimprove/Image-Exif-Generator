package org.compi.image_exif_reset.processing

import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.compi.image_exif_reset.model.ImageTask
import org.compi.image_exif_reset.model.TaskStatus
import java.net.URI
import java.nio.file.Path
import java.util.UUID

class ImageResetController(
    private val processor: ImageResetProcessor,
    private val scope: CoroutineScope,
) {
    val tasks = mutableStateListOf<ImageTask>()

    private val queuedPaths = mutableSetOf<String>()
    private val queue = Channel<QueueEntry>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (entry in queue) process(entry)
        }
    }

    fun enqueue(paths: List<String>) {
        scope.launch {
            paths.forEach { rawPath ->
                val path = runCatching {
                    if (rawPath.startsWith("file:", ignoreCase = true)) Path.of(URI(rawPath)) else Path.of(rawPath)
                }.getOrNull()?.toAbsolutePath()?.normalize()
                    ?: return@forEach
                val key = path.toString()
                if (!queuedPaths.add(key)) return@forEach

                val task = ImageTask(
                    id = UUID.randomUUID().toString(),
                    inputPath = key,
                    inputName = path.fileName?.toString() ?: key,
                )
                tasks += task
                queue.send(QueueEntry(task.id, path, key))
            }
        }
    }

    fun close() {
        queue.close()
    }

    private suspend fun process(entry: QueueEntry) {
        update(entry.taskId) { it.copy(status = TaskStatus.PROCESSING, detail = "Resetting metadata…") }
        val result = withContext(Dispatchers.IO) { processor.process(entry.path) }
        update(entry.taskId) { it.copy(status = result.status, detail = result.message) }
        queuedPaths.remove(entry.pathKey)
    }

    private fun update(id: String, transform: (ImageTask) -> ImageTask) {
        val index = tasks.indexOfFirst { it.id == id }
        if (index >= 0) tasks[index] = transform(tasks[index])
    }

    private data class QueueEntry(
        val taskId: String,
        val path: Path,
        val pathKey: String,
    )
}
