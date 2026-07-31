package org.compi.image_exif_reset.processing

import org.compi.image_exif_reset.metadata.MetadataEngine
import org.compi.image_exif_reset.model.ResetResult
import org.compi.image_exif_reset.model.SupportedImageFormat
import org.compi.image_exif_reset.model.TaskStatus
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

class ImageResetProcessor(
    private val metadataEngine: MetadataEngine,
) {
    fun process(input: Path): ResetResult {
        val normalizedInput = input.toAbsolutePath().normalize()
        if (Files.isDirectory(normalizedInput)) {
            return ResetResult(null, TaskStatus.SKIPPED, "Folders are not supported")
        }
        if (!Files.isRegularFile(normalizedInput)) {
            return ResetResult(null, TaskStatus.SKIPPED, "File does not exist")
        }

        var temporaryOutput: Path? = null
        return try {
            val source = metadataEngine.inspect(normalizedInput)
            val output = outputPathFor(normalizedInput, source.format)
            temporaryOutput = normalizedInput.parent.resolve(
                ".${normalizedInput.nameWithoutExtension}.reset-${UUID.randomUUID()}.${source.format.fileExtension}",
            )
            val generated = metadataEngine.reset(normalizedInput, temporaryOutput, source)
            metadataEngine.verify(source, temporaryOutput, generated)
            replaceAtomically(temporaryOutput, output)
            ResetResult(output.toString(), TaskStatus.COMPLETED, output.fileName.toString())
        } catch (error: Exception) {
            temporaryOutput?.let { Files.deleteIfExists(it) }
            ResetResult(
                outputPath = null,
                status = if (error.message?.contains("unsupported", ignoreCase = true) == true) {
                    TaskStatus.SKIPPED
                } else {
                    TaskStatus.FAILED
                },
                message = friendlyMessage(error),
            )
        }
    }

    fun outputPathFor(input: Path, format: SupportedImageFormat): Path {
        val filename = input.fileName.toString()
        val dotIndex = filename.lastIndexOf('.')
        val base = if (dotIndex > 0) filename.substring(0, dotIndex) else filename
        return input.parent.resolve("${base}_new_images.${format.fileExtension}")
    }

    private fun replaceAtomically(temporaryOutput: Path, output: Path) {
        try {
            Files.move(
                temporaryOutput,
                output,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporaryOutput, output, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun friendlyMessage(error: Exception): String {
        val message = error.message.orEmpty().lineSequence().firstOrNull().orEmpty().trim()
        return message.ifBlank { "Could not reset image" }.take(220)
    }
}
