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
    private val synthIdEngine: SynthIdEngine = PythonSynthIdEngine(),
) {
    fun close() = synthIdEngine.close()

    fun releaseWorker() = synthIdEngine.release()

    fun process(input: Path, reduceSynthId: Boolean = false, onProgress: (String) -> Unit = {}): ResetResult {
        val normalizedInput = input.toAbsolutePath().normalize()
        if (Files.isDirectory(normalizedInput)) {
            return ResetResult(null, TaskStatus.SKIPPED, "Folders are not supported")
        }
        if (!Files.isRegularFile(normalizedInput)) {
            return ResetResult(null, TaskStatus.SKIPPED, "File does not exist")
        }

        var temporaryOutput: Path? = null
        var convertedInput: Path? = null
        var regeneratedOutput: Path? = null
        var finalizedOutput: Path? = null
        return try {
            val source = metadataEngine.inspect(normalizedInput)
            val targetFormat = if (source.format == SupportedImageFormat.JPEG) {
                SupportedImageFormat.PNG
            } else {
                SupportedImageFormat.JPEG
            }
            val output = outputPathFor(normalizedInput, targetFormat)
            convertedInput = Files.createTempFile(normalizedInput.parent, ".convert-", ".${targetFormat.fileExtension}")
            ImageConverter.convert(normalizedInput, convertedInput, targetFormat)
            val converted = metadataEngine.inspect(convertedInput).copy(orientation = source.orientation)
            check(converted.format == targetFormat && converted.width == source.width && converted.height == source.height) {
                "Conversion changed image dimensions or produced the wrong format"
            }
            temporaryOutput = normalizedInput.parent.resolve(
                ".${normalizedInput.nameWithoutExtension}.reset-${UUID.randomUUID()}.${targetFormat.fileExtension}",
            )
            val generated = metadataEngine.reset(convertedInput, temporaryOutput, converted)
            metadataEngine.verify(converted, temporaryOutput, generated)
            var selectedOutput = temporaryOutput
            var message = output.fileName.toString()
            if (reduceSynthId) {
                try {
                    onProgress("Waiting for SynthID worker…")
                    regeneratedOutput = Files.createTempFile(normalizedInput.parent, ".synthid-", ".${targetFormat.fileExtension}")
                    synthIdEngine.reduce(temporaryOutput, regeneratedOutput, onProgress)
                    val regenerated = metadataEngine.inspect(regeneratedOutput).copy(orientation = converted.orientation)
                    check(regenerated.format == targetFormat && regenerated.width == converted.width && regenerated.height == converted.height) {
                        "SynthID output has incorrect dimensions or format"
                    }
                    check(!regenerated.imageDataHash.equals(converted.imageDataHash, ignoreCase = true)) {
                        "SynthID worker returned unchanged image data"
                    }
                    onProgress("Finalizing metadata and verifying SynthID output…")
                    finalizedOutput = Files.createTempFile(normalizedInput.parent, ".finalize-", ".${targetFormat.fileExtension}")
                    Files.delete(finalizedOutput)
                    val finalMetadata = metadataEngine.reset(regeneratedOutput, finalizedOutput, regenerated)
                    metadataEngine.verify(regenerated, finalizedOutput, finalMetadata)
                    selectedOutput = finalizedOutput
                    message = "${output.fileName} · SynthID reduction processed; removal not verified"
                } catch (error: Exception) {
                    message = "${output.fileName} · Saved converted metadata-reset copy; SynthID reduction failed: ${friendlyMessage(error)}"
                }
            }
            replaceAtomically(selectedOutput, output)
            ResetResult(output.toString(), TaskStatus.COMPLETED, message)
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
        } finally {
            convertedInput?.let { Files.deleteIfExists(it) }
            temporaryOutput?.let { Files.deleteIfExists(it) }
            regeneratedOutput?.let { Files.deleteIfExists(it) }
            finalizedOutput?.let { Files.deleteIfExists(it) }
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
