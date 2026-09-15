package org.compi.image_exif_reset.processing

import org.compi.image_exif_reset.model.SupportedImageFormat
import org.jetbrains.skia.Codec
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import java.nio.file.Files
import java.nio.file.Path

/** Re-encodes pixels in sRGB; orientation is retained separately by the metadata reset. */
internal object ImageConverter {
    fun convert(input: Path, output: Path, format: SupportedImageFormat) {
        require(format != SupportedImageFormat.WEBP)
        Data.makeFromBytes(Files.readAllBytes(input)).use { data ->
            Codec.makeFromData(data).use { codec ->
                require(codec.frameCount <= 1) { "Unsupported animated image" }
                // Codec decodes raw pixels without applying EXIF orientation.
                codec.readPixels().use { bitmap ->
                    Image.makeFromBitmap(bitmap).use { image ->
                        Surface.makeRaster(ImageInfo.makeS32(image.width, image.height, ColorAlphaType.OPAQUE)).use { surface ->
                            surface.canvas.clear(0xFFFFFFFF.toInt())
                            surface.canvas.drawImage(image, 0f, 0f)
                            surface.makeImageSnapshot().use { converted ->
                                val encoding = if (format == SupportedImageFormat.PNG) {
                                    EncodedImageFormat.PNG
                                } else {
                                    EncodedImageFormat.JPEG
                                }
                                checkNotNull(converted.encodeToData(encoding, 95)) { "Could not encode image" }.use {
                                    Files.write(output, it.bytes)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
