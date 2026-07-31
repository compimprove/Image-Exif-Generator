package org.compi.image_exif_reset

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform