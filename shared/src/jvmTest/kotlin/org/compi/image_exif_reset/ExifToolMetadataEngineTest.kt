package org.compi.image_exif_reset

import org.compi.image_exif_reset.metadata.ExifToolLocator
import org.compi.image_exif_reset.metadata.ExifToolMetadataEngine
import org.compi.image_exif_reset.model.TaskStatus
import org.compi.image_exif_reset.processing.ImageResetProcessor
import java.awt.color.ColorSpace
import java.awt.color.ICC_Profile
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExifToolMetadataEngineTest {
    private val commandPrefix = ExifToolLocator().commandPrefix()
    private val engine = ExifToolMetadataEngine(commandPrefix)

    @Test
    fun resetsJpegPngAndWebpWithoutChangingImageData() {
        val directory = createTempDirectory("image-reset-formats-")
        val fixtures = listOf(
            createImageIoFixture(directory.resolve("sample.jpg"), "jpeg"),
            createImageIoFixture(directory.resolve("sample.png"), "png"),
            createWebpFixture(directory.resolve("sample.webp")),
        )
        val icc = ICC_Profile.getInstance(ColorSpace.CS_sRGB).data

        fixtures.forEach { input ->
            seedPrivateMetadata(input, icc)
            val before = engine.inspect(input)
            val output = directory.resolve(".${input.fileName}.temporary.${input.fileName}")
            val generated = engine.reset(input, output)

            engine.verify(before, output, generated)
            val after = engine.inspect(output)

            assertEquals(before.format, after.format)
            assertEquals(before.imageDataHash, after.imageDataHash)
            assertEquals(6, after.orientation)
            assertTrue(after.iccProfile!!.contentEquals(icc))
            assertTrue(input.exists(), "The source must remain in place")
        }
    }

    @Test
    fun processorReplacesExistingOutputAndAllowsRepeatedSuffixes() {
        val directory = createTempDirectory("image-reset-output-")
        val input = createImageIoFixture(directory.resolve("photo.jpg"), "jpeg")
        val processor = ImageResetProcessor(engine)

        val first = processor.process(input)
        assertEquals(TaskStatus.COMPLETED, first.status, first.message)
        val firstOutput = Path.of(first.outputPath!!)
        val initialModifiedTime = Files.getLastModifiedTime(firstOutput)

        Thread.sleep(20)
        val replacement = processor.process(input)
        assertEquals(TaskStatus.COMPLETED, replacement.status, replacement.message)
        assertEquals(firstOutput, Path.of(replacement.outputPath!!))
        assertTrue(Files.getLastModifiedTime(firstOutput) >= initialModifiedTime)

        val repeated = processor.process(firstOutput)
        assertEquals(TaskStatus.COMPLETED, repeated.status, repeated.message)
        assertEquals("photo_new_images_new_images.jpg", Path.of(repeated.outputPath!!).name)
        assertTrue(input.exists())
    }

    @Test
    fun rejectsUnsupportedFilesAndCorrectsFalseExtensions() {
        val directory = createTempDirectory("image-reset-invalid-")
        val unsupported = directory.resolve("notes.txt")
        Files.writeString(unsupported, "not an image")
        val falseExtension = createImageIoFixture(directory.resolve("wrong.jpg"), "png")
        val processor = ImageResetProcessor(engine)

        assertEquals(TaskStatus.SKIPPED, processor.process(unsupported).status)
        val corrected = processor.process(falseExtension)
        assertEquals(TaskStatus.COMPLETED, corrected.status, corrected.message)
        val correctedOutput = Path.of(requireNotNull(corrected.outputPath))
        assertEquals("wrong_new_images.png", correctedOutput.name)
        assertEquals("PNG", engine.inspect(correctedOutput).format.name)
        assertFalse(directory.resolve("notes_new_images.txt").exists())
        assertFalse(directory.resolve("wrong_new_images.jpg").exists())
    }

    private fun createImageIoFixture(path: Path, format: String): Path {
        val image = BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                image.setRGB(x, y, ((x * 31) shl 16) or ((y * 41) shl 8) or 0x55)
            }
        }
        assertTrue(ImageIO.write(image, format, path.toFile()))
        return path
    }

    private fun createWebpFixture(path: Path): Path {
        Files.write(path, Base64.getDecoder().decode(WEBP_1X1))
        return path
    }

    private fun seedPrivateMetadata(path: Path, icc: ByteArray) {
        val iccFile = Files.createTempFile(path.parent, "profile-", ".icc")
        Files.write(iccFile, icc)
        try {
            runExifTool(
                "-overwrite_original",
                "-EXIF:Orientation#=6",
                "-EXIF:Make=Private Camera",
                "-EXIF:Model=Private Model",
                "-GPSLatitude=10.5",
                "-GPSLongitude=106.7",
                "-XMP-dc:Description=old private description",
                "-ICC_Profile<=${iccFile.toAbsolutePath()}",
                path.toAbsolutePath().toString(),
            )
        } finally {
            Files.deleteIfExists(iccFile)
        }
    }

    private fun runExifTool(vararg arguments: String) {
        val process = ProcessBuilder(commandPrefix + arguments).start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), stderr.ifBlank { stdout })
    }

    companion object {
        private const val WEBP_1X1 =
            "UklGRiwAAABXRUJQVlA4ICAAAABQAQCdASoCAAIAAgA0JYgABAAAAP7xlQ/+6HlDsUQgAA=="
    }
}
