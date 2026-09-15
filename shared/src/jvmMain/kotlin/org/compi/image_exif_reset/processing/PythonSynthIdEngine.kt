package org.compi.image_exif_reset.processing

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/** One private stdio worker; paths/messages are UTF-8 base64, never shell commands. */
class PythonSynthIdEngine(
    private val command: () -> List<String> = { bundledCommand() },
    private val timeoutSeconds: Long = 3600,
) : SynthIdEngine {
    private val lock = ReentrantLock()
    @Volatile private var worker: Process? = null
    @Volatile private var closed = false
    private var events = LinkedBlockingQueue<String>()

    override fun reduce(input: Path, output: Path, onProgress: (String) -> Unit) = lock.withLock {
        check(!closed) { "SynthID worker was closed" }
        try {
            val process = worker?.takeIf { it.isAlive } ?: startWorker()
            check(!closed) { "SynthID worker was closed" }
            val request = "reduce\t${encode(input.toAbsolutePath().toString())}\t${encode(output.toAbsolutePath().toString())}\n"
            process.outputStream.write(request.toByteArray(Charsets.UTF_8))
            process.outputStream.flush()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            while (true) {
                check(!closed) { "SynthID worker was closed" }
                check(System.nanoTime() < deadline) { "SynthID processing timed out" }
                val event = events.poll(250, TimeUnit.MILLISECONDS)
                if (event == null) {
                    check(process.isAlive) { "SynthID worker exited unexpectedly" }
                    continue
                }
                when {
                    event == "done" -> {
                        check(Files.isRegularFile(output) && Files.size(output) > 0) { "SynthID worker produced no image" }
                        return@withLock
                    }
                    event.startsWith("progress\t") -> onProgress(decode(event.substringAfter('\t')).take(240))
                    event.startsWith("error\t") -> error(decode(event.substringAfter('\t')).take(240))
                    event == "eof" -> error("SynthID worker exited unexpectedly")
                    else -> error("Invalid response from SynthID worker")
                }
            }
        } catch (error: Exception) {
            stopWorker()
            throw error
        }
    }

    private fun startWorker(): Process {
        val builder = ProcessBuilder(command())
        builder.environment().apply {
            put("PYTHONUNBUFFERED", "1")
            put("PYTHONNOUSERSITE", "1")
            remove("PYTHONPATH")
            remove("PYTHONHOME")
        }
        val process = builder.start()
        worker = process
        val currentEvents = LinkedBlockingQueue<String>()
        events = currentEvents
        thread(name = "synthid-protocol", isDaemon = true) {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { currentEvents.put(it) }
                }
            } finally {
                currentEvents.offer("eof")
            }
        }
        // Always drain diagnostics so large model-loader logs cannot deadlock the worker.
        thread(name = "synthid-diagnostics", isDaemon = true) {
            runCatching { process.errorStream.use { stream ->
                val buffer = ByteArray(8192)
                while (stream.read(buffer) != -1) { /* no paths/tokens in the UI */ }
            } }
        }
        if (closed) stopWorker()
        return process
    }

    override fun release() = lock.withLock { stopWorker() }

    override fun close() {
        closed = true
        stopWorker()
    }

    @Synchronized private fun stopWorker() {
        val process = worker ?: return
        worker = null
        val descendants = process.descendants().toList()
        descendants.forEach { it.destroyForcibly() }
        process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
        descendants.forEach { child -> runCatching { child.onExit().get(5, TimeUnit.SECONDS) } }
    }

    companion object {
        private fun encode(value: String) = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        private fun decode(value: String) = String(Base64.getDecoder().decode(value), Charsets.UTF_8)

        private fun bundledCommand(): List<String> {
            val windows = System.getProperty("os.name").startsWith("Windows")
            val candidates = buildList {
                System.getenv("IMAGE_EXIF_RESET_SYNTHID_ENGINE")?.let { add(Path.of(it)) }
                System.getProperty("compose.application.resources.dir")?.let { add(Path.of(it).resolve("synthid")) }
                var directory: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
                repeat(4) {
                    directory?.let {
                        add(it.resolve("desktopApp/build/synthid"))
                        add(it.resolve("build/synthid"))
                    }
                    directory = directory?.parent
                }
            }
            val relativePython = if (windows) "python/python.exe" else "python/bin/python3"
            val root = candidates.firstOrNull {
                Files.isRegularFile(it.resolve(relativePython)) && Files.isRegularFile(it.resolve("worker.py"))
            } ?: error("SynthID engine is not installed in this build")
            return listOf(root.resolve(relativePython).toString(), "-I", root.resolve("worker.py").toString())
        }
    }
}
