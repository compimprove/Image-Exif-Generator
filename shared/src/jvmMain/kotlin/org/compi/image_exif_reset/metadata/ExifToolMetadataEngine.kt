package org.compi.image_exif_reset.metadata

import org.compi.image_exif_reset.model.GeneratedMetadata
import org.compi.image_exif_reset.model.MetadataSnapshot
import org.compi.image_exif_reset.model.PhotoshopMetadataProfile
import org.compi.image_exif_reset.model.SupportedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

class ExifToolMetadataEngine(
    commandPrefix: List<String> = ExifToolLocator().commandPrefix(),
) : MetadataEngine {
    private val runner = ExifToolRunner(commandPrefix)

    override fun inspect(input: Path): MetadataSnapshot {
        if (!Files.isRegularFile(input)) throw MetadataException("Not a file")

        val values = readColumns(
            input,
            "-FileType",
            "-MIMEType",
            "-ImageWidth",
            "-ImageHeight",
            "-Orientation#",
            "-ICC_Profile:ProfileDescription",
            "-ColorComponents",
            "-ColorType",
            "-ImageDataHash",
            extraArguments = listOf("-api", "ImageHashType=SHA256"),
        )
        val fileType = values.required(0, "file type").uppercase()
        val format = when {
            fileType == "JPEG" -> SupportedImageFormat.JPEG
            fileType == "PNG" -> SupportedImageFormat.PNG
            fileType.endsWith("WEBP") -> SupportedImageFormat.WEBP
            else -> throw MetadataException("Unsupported image format")
        }
        val colorComponents = values.optional(6)?.toIntOrNull()
        val colorType = values.optional(7).orEmpty()
        val colorMode = when {
            colorType.contains("palette", ignoreCase = true) -> 2
            colorType.contains("gray", ignoreCase = true) || colorComponents == 1 -> 1
            colorComponents == 4 -> 4
            else -> 3
        }
        val icc = extractBinary(input, "-ICC_Profile")

        return MetadataSnapshot(
            format = format,
            mimeType = values.optional(1) ?: format.mimeType,
            width = values.required(2, "width").toIntOrNull()
                ?: throw MetadataException("Invalid image width"),
            height = values.required(3, "height").toIntOrNull()
                ?: throw MetadataException("Invalid image height"),
            orientation = values.optional(4)?.toIntOrNull(),
            iccProfile = icc,
            iccDescription = values.optional(5),
            colorMode = colorMode,
            imageDataHash = values.required(8, "image data hash"),
        )
    }

    override fun reset(
        input: Path,
        temporaryOutput: Path,
        source: MetadataSnapshot,
    ): GeneratedMetadata {
        Files.copy(input, temporaryOutput)

        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val generated = GeneratedMetadata(
            documentId = "xmp.did:${UUID.randomUUID()}",
            instanceId = "xmp.iid:${UUID.randomUUID()}",
            processingInstant = now.toString(),
        )
        val xmpTime = DateTimeFormatter.ISO_INSTANT.format(now)
        val exifTime = EXIF_DATE_FORMAT.format(now)
        var iccFile: Path? = null

        try {
            val arguments = mutableListOf(
                "-overwrite_original",
                "-all=",
                "-JUMBF:all=",
                "-EXIF:Software=${PhotoshopMetadataProfile.SOFTWARE}",
                "-XMP-xmp:CreatorTool=${PhotoshopMetadataProfile.CREATOR_TOOL}",
                "-XMP-photoshop:History=${PhotoshopMetadataProfile.HISTORY}",
                "-XMP-xmp:CreateDate=$xmpTime",
                "-XMP-xmp:ModifyDate=$xmpTime",
                "-XMP-xmp:MetadataDate=$xmpTime",
                "-EXIF:ModifyDate=$exifTime",
                "-XMP-xmpMM:DocumentID=${generated.documentId}",
                "-XMP-xmpMM:InstanceID=${generated.instanceId}",
                "-XMP-dc:Format=${source.mimeType}",
                "-EXIF:ExifImageWidth=${source.width}",
                "-EXIF:ExifImageHeight=${source.height}",
                "-XMP-exif:ExifImageWidth=${source.width}",
                "-XMP-exif:ExifImageHeight=${source.height}",
                "-XMP-photoshop:ColorMode#=${source.colorMode}",
            )
            source.orientation?.let { arguments += "-EXIF:Orientation#=$it" }
            source.iccDescription?.let { arguments += "-XMP-photoshop:ICCProfileName=$it" }
            source.iccProfile?.let { profile ->
                iccFile = Files.createTempFile(temporaryOutput.parent, ".icc-", ".icc")
                Files.write(iccFile, profile)
                arguments += "-ICC_Profile<=${iccFile!!.toAbsolutePath()}"
            }
            arguments += temporaryOutput.toAbsolutePath().toString()
            runner.run(arguments, temporaryOutput.parent)
        } catch (error: Exception) {
            Files.deleteIfExists(temporaryOutput)
            throw error
        } finally {
            iccFile?.let { Files.deleteIfExists(it) }
        }
        return generated
    }

    override fun verify(input: MetadataSnapshot, output: Path, generated: GeneratedMetadata) {
        val actual = inspect(output)
        val mismatches = mutableListOf<String>()
        if (actual.format != input.format) mismatches += "format changed"
        if (actual.width != input.width || actual.height != input.height) mismatches += "dimensions changed"
        if (actual.orientation != input.orientation) mismatches += "orientation changed"
        if (!byteArraysEqual(actual.iccProfile, input.iccProfile)) mismatches += "ICC profile changed"
        if (!actual.imageDataHash.equals(input.imageDataHash, ignoreCase = true)) mismatches += "image data changed"

        val fixed = readColumns(
            output,
            "-EXIF:Software",
            "-XMP-xmp:CreatorTool",
            "-XMP-photoshop:History",
            "-XMP-xmpMM:DocumentID",
            "-XMP-xmpMM:InstanceID",
            "-XMP-dc:Format",
            "-XMP-xmp:CreateDate",
            "-XMP-xmp:ModifyDate",
            "-XMP-xmp:MetadataDate",
            "-EXIF:ModifyDate",
            "-EXIF:ExifImageWidth",
            "-EXIF:ExifImageHeight",
            "-XMP-exif:ExifImageWidth",
            "-XMP-exif:ExifImageHeight",
            "-XMP-photoshop:ColorMode#",
            "-XMP-photoshop:ICCProfileName",
            extraArguments = listOf("-d", "%Y-%m-%dT%H:%M:%SZ"),
        )
        val expected = listOf(
            PhotoshopMetadataProfile.SOFTWARE,
            PhotoshopMetadataProfile.CREATOR_TOOL,
            PhotoshopMetadataProfile.HISTORY,
            generated.documentId,
            generated.instanceId,
            input.mimeType,
            generated.processingInstant,
            generated.processingInstant,
            generated.processingInstant,
            generated.processingInstant,
            input.width.toString(),
            input.height.toString(),
            input.width.toString(),
            input.height.toString(),
            input.colorMode.toString(),
            input.iccDescription,
        )
        val fieldNames = listOf(
            "Software", "CreatorTool", "History", "DocumentID", "InstanceID", "Format",
            "CreateDate", "ModifyDate", "MetadataDate", "EXIF ModifyDate", "EXIF width", "EXIF height",
            "XMP width", "XMP height", "ColorMode", "ICC description",
        )
        expected.forEachIndexed { index, value ->
            if (fixed.optional(index) != value) mismatches += "${fieldNames[index]} is incomplete"
        }
        if (generated.documentId == generated.instanceId || !isValidXmpUuid(generated.documentId, "xmp.did:") ||
            !isValidXmpUuid(generated.instanceId, "xmp.iid:")) {
            mismatches += "generated identifiers are invalid"
        }
        if (hasForbiddenMetadata(output)) mismatches += "old sensitive metadata or C2PA/JUMBF remains"

        if (mismatches.isNotEmpty()) {
            throw MetadataException("Verification failed: ${mismatches.distinct().joinToString()}")
        }
    }

    private fun hasForbiddenMetadata(path: Path): Boolean {
        val result = runner.run(
            listOf(
                "-s3",
                "-JUMBF:all",
                "-GPS:all",
                "-MakerNotes:all",
                "-IPTC:all",
                "-EXIF:Make",
                "-EXIF:Model",
                "-EXIF:CameraSerialNumber",
                "-EXIF:LensSerialNumber",
                "-EXIF:Artist",
                "-EXIF:Copyright",
                "-XMP-dc:Description",
                "-XMP-dc:Subject",
                "-XMP-dc:Title",
                "-Comment",
                "-ThumbnailImage",
                "-PreviewImage",
                path.toAbsolutePath().toString(),
            ),
            path.parent,
        )
        return result.stdout.isNotEmpty()
    }

    private fun extractBinary(path: Path, tag: String): ByteArray? {
        val bytes = runner.run(listOf("-b", tag, path.toAbsolutePath().toString()), path.parent).stdout
        return bytes.takeIf { it.isNotEmpty() }
    }

    private fun readColumns(
        path: Path,
        vararg tags: String,
        extraArguments: List<String> = emptyList(),
    ): List<String> {
        val args = buildList {
            add("-T")
            add("-s3")
            addAll(extraArguments)
            addAll(tags)
            add(path.toAbsolutePath().toString())
        }
        val line = runner.run(args, path.parent).stdoutText().trimEnd('\r', '\n')
        return line.split('\t')
    }

    private fun List<String>.optional(index: Int): String? =
        getOrNull(index)?.trim()?.takeUnless { it.isEmpty() || it == "-" }

    private fun List<String>.required(index: Int, label: String): String =
        optional(index) ?: throw MetadataException("Could not read image $label")

    private fun byteArraysEqual(first: ByteArray?, second: ByteArray?): Boolean = when {
        first == null && second == null -> true
        first == null || second == null -> false
        else -> MessageDigest.isEqual(first, second)
    }

    private fun isValidXmpUuid(value: String, prefix: String): Boolean = runCatching {
        UUID.fromString(value.removePrefix(prefix))
        value.startsWith(prefix)
    }.getOrDefault(false)

    companion object {
        private val EXIF_DATE_FORMAT = DateTimeFormatter
            .ofPattern("yyyy:MM:dd HH:mm:ss")
            .withZone(ZoneOffset.UTC)
    }
}
