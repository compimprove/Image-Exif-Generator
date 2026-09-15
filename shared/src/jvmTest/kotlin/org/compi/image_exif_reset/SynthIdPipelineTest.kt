package org.compi.image_exif_reset

import org.compi.image_exif_reset.metadata.ExifToolMetadataEngine
import org.compi.image_exif_reset.metadata.MetadataEngine
import org.compi.image_exif_reset.model.GeneratedMetadata
import org.compi.image_exif_reset.model.MetadataSnapshot
import org.compi.image_exif_reset.model.TaskStatus
import org.compi.image_exif_reset.processing.ImageResetProcessor
import org.compi.image_exif_reset.processing.SynthIdEngine
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class SynthIdPipelineTest {
    private val metadata = ExifToolMetadataEngine()

    private fun fixture(directory: Path): Path {
        val path = directory.resolve("source.jpg")
        val image = BufferedImage(16, 8, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 16) for (y in 0 until 8) image.setRGB(x, y, (x * 15 shl 16) or (y * 30 shl 8))
        ImageIO.write(image, "jpg", path.toFile())
        return path
    }

    private fun withFixture(block: (Path, Path) -> Unit) {
        val directory = createTempDirectory("synthid-pipeline-")
        try {
            val input = fixture(directory)
            val original = Files.readAllBytes(input)
            block(directory, input)
            assertContentEquals(original, Files.readAllBytes(input), "Original must survive")
            Files.list(directory).use { files ->
                assertFalse(files.anyMatch { it.fileName.toString().startsWith(".") }, "Temporary files must be removed")
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun uncheckedNeverCallsWorker() = withFixture { _, input ->
        val engine = object : SynthIdEngine {
            override fun reduce(input: Path, output: Path, onProgress: (String) -> Unit) = error("Must not run")
        }
        val result = ImageResetProcessor(metadata, engine).process(input)
        assertEquals(TaskStatus.COMPLETED, result.status)
        assertFalse(result.message.contains("SynthID"))
    }

    @Test fun workerFailureRetainsVerifiedConvertedPixels() = withFixture { _, input ->
        var fallbackHash = ""
        val engine = object : SynthIdEngine {
            override fun reduce(input: Path, output: Path, onProgress: (String) -> Unit) {
                fallbackHash = metadata.inspect(input).imageDataHash
                Files.writeString(output, "partial output")
                error("Model download failed")
            }
        }
        val result = ImageResetProcessor(metadata, engine).process(input, true)
        assertEquals(TaskStatus.COMPLETED, result.status, result.message)
        assertTrue(result.message.contains("SynthID reduction failed"))
        val actual = metadata.inspect(Path.of(result.outputPath!!))
        assertEquals("PNG", actual.format.name)
        assertEquals(fallbackHash, actual.imageDataHash)
    }

    @Test fun invalidAndUnchangedWorkerOutputsFallBack() {
        for (kind in listOf("garbage", "dimensions", "unchanged")) withFixture { _, input ->
            var fallbackHash = ""
            val engine = object : SynthIdEngine {
                override fun reduce(input: Path, output: Path, onProgress: (String) -> Unit) {
                    fallbackHash = metadata.inspect(input).imageDataHash
                    when (kind) {
                        "garbage" -> Files.writeString(output, "invalid")
                        "dimensions" -> ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output.toFile())
                        else -> Files.copy(input, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
            val result = ImageResetProcessor(metadata, engine).process(input, true)
            assertEquals(TaskStatus.COMPLETED, result.status, result.message)
            assertTrue(result.message.contains("SynthID reduction failed"), kind)
            assertEquals(fallbackHash, metadata.inspect(Path.of(result.outputPath!!)).imageDataHash)
        }
    }

    @Test fun successfulRegenerationReceivesFinalMetadata() = withFixture { _, input ->
        var regeneratedHash = ""
        val stages = mutableListOf<String>()
        val engine = object : SynthIdEngine {
            override fun reduce(input: Path, output: Path, onProgress: (String) -> Unit) {
                assertEquals("PNG", metadata.inspect(input).format.name, "Conversion precedes worker")
                val image = ImageIO.read(input.toFile())
                image.setRGB(0, 0, 0xFFFFFF)
                ImageIO.write(image, "png", output.toFile())
                regeneratedHash = metadata.inspect(output).imageDataHash
                onProgress("Processing test image")
            }
        }
        val result = ImageResetProcessor(metadata, engine).process(input, true, stages::add)
        assertEquals(TaskStatus.COMPLETED, result.status, result.message)
        assertTrue(result.message.contains("removal not verified"), result.message)
        assertEquals(regeneratedHash, metadata.inspect(Path.of(result.outputPath!!)).imageDataHash)
        assertTrue(stages.any { it.contains("Finalizing") })
    }

    @Test fun finalMetadataFailureAlsoRetainsFallback() = withFixture { _, input ->
        var fallbackHash = ""
        var verificationCount = 0
        val failingMetadata = object : MetadataEngine by metadata {
            override fun verify(input: MetadataSnapshot, output: Path, generated: GeneratedMetadata) {
                if (++verificationCount == 2) error("Final metadata verification failed")
                metadata.verify(input, output, generated)
            }
        }
        val engine = object : SynthIdEngine {
            override fun reduce(input: Path, output: Path, onProgress: (String) -> Unit) {
                fallbackHash = metadata.inspect(input).imageDataHash
                val image = ImageIO.read(input.toFile())
                image.setRGB(0, 0, 0xFFFFFF)
                ImageIO.write(image, "png", output.toFile())
            }
        }
        val result = ImageResetProcessor(failingMetadata, engine).process(input, true)
        assertEquals(TaskStatus.COMPLETED, result.status, result.message)
        assertTrue(result.message.contains("Final metadata verification failed"), result.message)
        assertEquals(fallbackHash, metadata.inspect(Path.of(result.outputPath!!)).imageDataHash)
    }
}
