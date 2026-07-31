package org.compi.image_exif_reset.metadata

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal data class ProcessResult(
    val stdout: ByteArray,
    val stderr: String,
    val exitCode: Int,
) {
    fun stdoutText(): String = stdout.toString(StandardCharsets.UTF_8)
}

internal class ExifToolRunner(
    private val commandPrefix: List<String>,
    private val timeoutSeconds: Long = 120,
) {
    fun run(arguments: List<String>, workingDirectory: Path? = null): ProcessResult {
        val process = ProcessBuilder(commandPrefix + arguments)
            .apply { if (workingDirectory != null) directory(workingDirectory.toFile()) }
            .start()

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val stdoutThread = Thread { process.inputStream.use { it.copyTo(stdout) } }.apply { start() }
        val stderrThread = Thread { process.errorStream.use { it.copyTo(stderr) } }.apply { start() }

        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            stdoutThread.join()
            stderrThread.join()
            throw MetadataException("ExifTool timed out after $timeoutSeconds seconds")
        }
        stdoutThread.join()
        stderrThread.join()

        val result = ProcessResult(
            stdout = stdout.toByteArray(),
            stderr = stderr.toString(StandardCharsets.UTF_8).trim(),
            exitCode = process.exitValue(),
        )
        if (result.exitCode != 0) {
            val reason = result.stderr.ifBlank { result.stdoutText().trim() }.ifBlank { "unknown ExifTool error" }
            throw MetadataException(reason.lineSequence().first())
        }
        return result
    }
}
