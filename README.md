# Image EXIF Reset

A focused Compose Desktop utility that creates sanitized JPEG, PNG, and WebP
copies. Drop images into the window and the app writes `*_new_images` files
beside the originals. Independent images run concurrently through a small,
bounded worker pool for faster batches.

The encoded image type is detected from the file itself. If a filename has the
wrong extension, the verified output uses the correct `.jpg`, `.png`, or
`.webp` extension.

The reset removes existing writable metadata and embedded C2PA/JUMBF
credentials, retains Orientation and the ICC color profile, and adds the fixed
Photoshop 25.6 metadata profile defined by the application.

## Run locally

```shell
./gradlew :desktopApp:run
```

ExifTool 13.59 is bundled for Apple Silicon macOS and Windows x64, so a system
ExifTool installation is not required. macOS uses the system Perl runtime at
`/usr/bin/perl`. Developers can override the executable used by setting
`IMAGE_EXIF_RESET_EXIFTOOL`.

## Build and test

```shell
./gradlew build
./gradlew :desktopApp:packageDistributionForCurrentOS
```

Original files are never edited. A verified temporary file replaces only the
generated `*_new_images` target.

## Tagged desktop releases

Pushing a three-component version tag starts the GitHub Actions release build:

```shell
git tag v2.15.2
git push origin v2.15.2
```

The tag must match `v<major>.<minor>.<patch>`. The workflow removes the leading
`v`, uses the remaining value as the native package version, builds an Apple
Silicon DMG and Windows x64 MSI, and attaches both installers to the GitHub
Release for that tag.

The Windows MSI installs under Program Files by default, lets the user choose a
different destination, and creates both a desktop shortcut and an Image EXIF
Reset entry in the Start Menu. A stable upgrade identifier allows newer MSI
releases to upgrade the existing installation.
