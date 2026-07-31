package org.compi.image_exif_reset.model

enum class TaskStatus {
    QUEUED,
    PROCESSING,
    COMPLETED,
    SKIPPED,
    FAILED,
}

data class ImageTask(
    val id: String,
    val inputPath: String,
    val inputName: String,
    val status: TaskStatus = TaskStatus.QUEUED,
    val detail: String = "Queued",
)

enum class SupportedImageFormat(val mimeType: String, val extensions: Set<String>) {
    JPEG("image/jpeg", setOf("jpg", "jpeg", "jpe")),
    PNG("image/png", setOf("png")),
    WEBP("image/webp", setOf("webp")),
}

data class MetadataSnapshot(
    val format: SupportedImageFormat,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val orientation: Int?,
    val iccProfile: ByteArray?,
    val iccDescription: String?,
    val colorMode: Int,
    val imageDataHash: String,
)

data class GeneratedMetadata(
    val documentId: String,
    val instanceId: String,
    val processingInstant: String,
)

data class ResetResult(
    val outputPath: String?,
    val status: TaskStatus,
    val message: String,
)

object PhotoshopMetadataProfile {
    const val SOFTWARE = "Adobe Photoshop 25.6"
    const val CREATOR_TOOL = "Adobe Photoshop 25.6"
    const val HISTORY = "Created with Adobe Photoshop 25.6"
}
