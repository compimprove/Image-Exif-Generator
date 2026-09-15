package org.compi.image_exif_reset

import org.compi.image_exif_reset.metadata.ExifToolMetadataEngine
import org.compi.image_exif_reset.model.TaskStatus
import org.compi.image_exif_reset.processing.ImageResetProcessor
import org.junit.Assume.assumeTrue
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/** Explicit opt-in: downloads several GB on a cold cache and needs substantial RAM. */
class RealSynthIdIntegrationTest {
    @Test fun runsRealWorkerThroughConversionAndFinalMetadata() {
        assumeTrue(System.getenv("IMAGE_EXIF_RESET_MODEL_TEST") == "1")
        val directory = createTempDirectory("synthid-real-pipeline-")
        val processor = ImageResetProcessor(ExifToolMetadataEngine())
        try {
            val input = directory.resolve("original.jpg")
            val image = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
            for (x in 0 until 64) for (y in 0 until 48) {
                image.setRGB(x, y, (x * 4 shl 16) or (y * 5 shl 8) or ((x + y) * 2))
            }
            ImageIO.write(image, "jpg", input.toFile())
            val original = Files.readAllBytes(input)
            val result = processor.process(input, true)
            assertEquals(TaskStatus.COMPLETED, result.status, result.message)
            assertTrue(result.message.contains("removal not verified"), result.message)
            val actual = ImageIO.read(Path.of(result.outputPath!!).toFile())
            assertEquals(64, actual.width)
            assertEquals(48, actual.height)
            assertContentEquals(original, Files.readAllBytes(input))
            Files.list(directory).use { files ->
                assertFalse(files.anyMatch { it.fileName.toString().startsWith(".") })
            }
        } finally {
            processor.close()
            directory.toFile().deleteRecursively()
        }
    }
}
