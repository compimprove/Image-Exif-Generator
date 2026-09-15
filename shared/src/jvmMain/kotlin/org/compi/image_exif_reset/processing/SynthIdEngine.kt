package org.compi.image_exif_reset.processing

import java.nio.file.Path

interface SynthIdEngine : AutoCloseable {
    fun reduce(input: Path, output: Path, onProgress: (String) -> Unit)
    /** Release a model after a batch, without disabling subsequent batches. */
    fun release() {}
    override fun close() {}
}
