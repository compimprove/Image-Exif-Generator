package org.compi.image_exif_reset.metadata

import org.compi.image_exif_reset.model.GeneratedMetadata
import org.compi.image_exif_reset.model.MetadataSnapshot
import java.nio.file.Path

interface MetadataEngine {
    fun inspect(input: Path): MetadataSnapshot

    fun reset(input: Path, temporaryOutput: Path): GeneratedMetadata =
        reset(input, temporaryOutput, inspect(input))

    fun reset(
        input: Path,
        temporaryOutput: Path,
        source: MetadataSnapshot,
    ): GeneratedMetadata

    fun verify(input: MetadataSnapshot, output: Path, generated: GeneratedMetadata)
}

class MetadataException(message: String, cause: Throwable? = null) : Exception(message, cause)
