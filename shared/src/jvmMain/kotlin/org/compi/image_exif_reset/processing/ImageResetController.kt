package org.compi.image_exif_reset.processing

import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.compi.image_exif_reset.model.ImageTask
import org.compi.image_exif_reset.model.TaskStatus
import java.net.URI
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.nameWithoutExtension

class ImageResetController(
    private val processor: ImageResetProcessor,
    private val scope: CoroutineScope,
    workerCount: Int = defaultWorkerCount(),
) {
    val tasks = mutableStateListOf<ImageTask>()

    private val queuedPaths = mutableSetOf<String>()
    private val pathLocks = mutableMapOf<String, Mutex>()
    private val queue = Channel<QueueEntry>(Channel.UNLIMITED)

    init {
        require(workerCount > 0) { "workerCount must be positive" }
        repeat(workerCount) {
            scope.launch {
                for (entry in queue) process(entry)
            }
        }
    }

    fun enqueue(paths: List<String>, reduceSynthId: Boolean = false) {
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
                    reduceSynthId = reduceSynthId,
                )
                tasks += task
                queue.send(QueueEntry(task.id, path, key, reduceSynthId))
            }
        }
    }

    fun close() {
        queue.close()
        processor.close()
    }

    private suspend fun process(entry: QueueEntry) {
        val normalizedPath = entry.path.toAbsolutePath().normalize()
        val affectedPaths = listOf(
            normalizedPath.parent.resolve(normalizedPath.nameWithoutExtension),
            normalizedPath.parent.resolve("${normalizedPath.nameWithoutExtension}_new_images"),
        )
            .map(Path::toString)
            .distinct()
            .sorted()

        withPathLocks(affectedPaths) {
            update(entry.taskId) { it.copy(status = TaskStatus.PROCESSING, detail = "Converting and resetting metadata…") }
            val result = withContext(Dispatchers.IO) {
                processor.process(entry.path, entry.reduceSynthId) { message ->
                    scope.launch {
                        update(entry.taskId) {
                            if (it.status == TaskStatus.PROCESSING) it.copy(detail = message) else it
                        }
                    }
                }
            }
            update(entry.taskId) { it.copy(status = result.status, detail = result.message) }
            queuedPaths.remove(entry.pathKey)
            if (queuedPaths.isEmpty()) processor.releaseWorker()
        }
    }

    private suspend fun <T> withPathLocks(
        paths: List<String>,
        index: Int = 0,
        block: suspend () -> T,
    ): T {
        if (index == paths.size) return block()
        return pathLocks.getOrPut(paths[index]) { Mutex() }.withLock {
            withPathLocks(paths, index + 1, block)
        }
    }

    private fun update(id: String, transform: (ImageTask) -> ImageTask) {
        val index = tasks.indexOfFirst { it.id == id }
        if (index >= 0) tasks[index] = transform(tasks[index])
    }

    private data class QueueEntry(
        val taskId: String,
        val path: Path,
        val pathKey: String,
        val reduceSynthId: Boolean,
    )

    companion object {
        /**
         * ExifTool work is both CPU and disk intensive. Four workers provide good batch throughput
         * without creating an unbounded number of processes or making lower-end machines unusable.
         */
        internal fun defaultWorkerCount(): Int =
            Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    }
}
