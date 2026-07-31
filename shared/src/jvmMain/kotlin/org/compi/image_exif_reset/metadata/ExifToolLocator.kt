package org.compi.image_exif_reset.metadata

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString

class ExifToolLocator(
    private val osName: String = System.getProperty("os.name"),
    private val environment: Map<String, String> = System.getenv(),
    private val properties: Map<String, String> = System.getProperties().entries.associate { it.key.toString() to it.value.toString() },
) {
    fun commandPrefix(): List<String> {
        environment[OVERRIDE_ENV]?.takeIf { it.isNotBlank() }?.let { override ->
            val path = Path.of(override).toAbsolutePath().normalize()
            if (!Files.isRegularFile(path)) throw MetadataException("ExifTool override does not exist: $path")
            return if (path.fileName.toString().endsWith(".pl", ignoreCase = true)) {
                listOf(perlExecutable(), path.absolutePathString())
            } else {
                listOf(path.absolutePathString())
            }
        }

        val resourceRoot = resolveResourceRoot()
        return if (isWindows()) {
            val executable = resourceRoot.resolve("exiftool/windows-x64/exiftool.exe")
            requireFile(executable, "Bundled Windows ExifTool is missing")
            listOf(executable.absolutePathString())
        } else if (isMac()) {
            val script = resourceRoot.resolve("exiftool/macos-arm64/exiftool")
            requireFile(script, "Bundled macOS ExifTool is missing")
            listOf(perlExecutable(), script.absolutePathString())
        } else {
            throw MetadataException("This build supports macOS and Windows only")
        }
    }

    private fun resolveResourceRoot(): Path {
        val candidates = buildList {
            properties[COMPOSE_RESOURCES_PROPERTY]?.takeIf { it.isNotBlank() }?.let { add(Path.of(it)) }
            properties["user.dir"]?.let { workingDirectory ->
                var current: Path? = Path.of(workingDirectory).toAbsolutePath().normalize()
                repeat(4) {
                    current?.let { directory ->
                        add(directory.resolve("desktopApp/src/main/appResources"))
                        add(directory.resolve("src/main/appResources"))
                    }
                    current = current?.parent
                }
            }
        }
        val platformDirectory = if (isWindows()) "windows" else "macos"
        return candidates
            .flatMap { root -> listOf(root, root.resolve(platformDirectory), root.resolve("common")) }
            .firstOrNull { Files.isDirectory(it.resolve("exiftool")) }
            ?: throw MetadataException("Bundled ExifTool resources could not be located")
    }

    private fun requireFile(path: Path, message: String) {
        if (!Files.isRegularFile(path)) throw MetadataException("$message: $path")
    }

    private fun perlExecutable(): String {
        val perl = Path.of("/usr/bin/perl")
        if (!Files.isExecutable(perl)) throw MetadataException("macOS Perl runtime is unavailable at /usr/bin/perl")
        return perl.absolutePathString()
    }

    private fun isWindows() = osName.startsWith("Windows", ignoreCase = true)
    private fun isMac() = osName.startsWith("Mac", ignoreCase = true)

    companion object {
        const val OVERRIDE_ENV = "IMAGE_EXIF_RESET_EXIFTOOL"
        const val COMPOSE_RESOURCES_PROPERTY = "compose.application.resources.dir"
    }
}
