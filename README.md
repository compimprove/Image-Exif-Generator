# Image EXIF Reset

A focused Compose Desktop utility that creates sanitized, converted image
copies. JPEG inputs automatically become PNG; PNG and WebP inputs become JPEG. Drop images into the window and the app writes `*_new_images` files
beside the originals. Independent images run concurrently through a small,
bounded worker pool for faster batches.

The encoded image type is detected from the file itself. Conversion follows the detected type, even if the filename has the wrong
extension. Outputs use `.png` or `.jpg` to match the converted content.
JPEG output uses quality 95 and a white background for transparency. Animated
images are skipped.

The reset removes existing writable metadata and embedded C2PA/JUMBF
credentials, retains Orientation, converts colors to sRGB, and adds the fixed
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
Silicon DMG and Windows x64 MSI with external cabinet files, and attaches the
installers and all cabinet files to the GitHub Release for that tag.

**Windows installation:** download the `.msi` and every `image-exif-data*.cab`
asset from the same release into one folder, then open the MSI. Keep the files
together; the MSI requires the cabinet files to install the bundled CUDA runtime.

The Windows MSI installs under Program Files by default, lets the user choose a
different destination, and creates both a desktop shortcut and an Image EXIF
Reset entry in the Start Menu. A stable upgrade identifier allows newer MSI
releases to upgrade the existing installation.

## Optional SynthID reduction

The unchecked **Reduce SynthID (experimental)** checkbox is captured when each
image is queued. Conversion and metadata reset always happen first. Their
verified result is retained while a private Python worker attempts CtrlRegen
regeneration. If download, inference, or output validation fails, that result is
saved with a warning. Successful regeneration gets a final metadata pass before
publication. Originals are never edited.

Regeneration can alter fine detail and text. A successful processing result does
not certify watermark removal: no proprietary or surrogate SynthID detector is
bundled. The worker handles one image at a time and stays loaded for the batch;
it exits when the batch finishes or the application closes.

### Build the optional engine

Install `uv` on the build machine, then run:

```shell
python3 synthid/bundle.py
./gradlew :desktopApp:run
```

On Windows use `python synthid/bundle.py --cuda` to include CUDA 12.4 PyTorch;
omit `--cuda` for a smaller CPU-only runtime. CUDA acceleration requires a
compatible NVIDIA driver. macOS builds target Apple Silicon and select MPS
when available. CPU processing is supported but slow. Release workflows build
the private runtime before packaging. Local builds without it still perform
conversion/reset and explicitly fall back when reduction is requested.

The app bundles CPython and dependencies from `synthid/requirements.txt`; users
need no system Python. The first checked run downloads the pinned external
backend and model snapshots (~10 GB, hardware/model-dependent). Downloads go to
`~/Library/Application Support/Image Exif Reset/synthid` on macOS or
`%LOCALAPPDATA%/Image Exif Reset/synthid` on Windows. Completed downloads work
offline. No image is uploaded. Model files stay outside installers and Git.

`IMAGE_EXIF_RESET_SYNTHID_ENGINE` can point to a development engine directory
containing `worker.py`, `models.json`, and `python/`. The default job timeout is
one hour, including first-use downloads; failures retain the first-stage copy.

The externally downloaded backend has no repository license. Resolve its usage
and distribution terms before a public product release; runtime downloading is
not a license grant. See [engine dependency notices](synthid/THIRD_PARTY.md).

Worker tests can be run with the bundled interpreter:

```shell
desktopApp/build/synthid/python/bin/python3 -B -m unittest discover -s synthid -p 'test_*.py'
./gradlew :shared:jvmTest
```

The heavyweight `RealSynthIdIntegrationTest` is skipped by default. To test a
packaged engine end-to-end, set `IMAGE_EXIF_RESET_MODEL_TEST=1` and
`IMAGE_EXIF_RESET_SYNTHID_ENGINE` to the package's `resources/synthid` directory,
then run `:shared:jvmTest --tests '*RealSynthIdIntegrationTest'`. This exercises
real regeneration and metadata verification, not SynthID detection accuracy.

Installer packaging also runs on pull requests, without publishing a release.
Windows CI installs the MSI, checks the installed Python/CUDA runtime, and
uninstalls it. Failed packaging logs are retained as workflow artifacts.

Windows MSI builds use a short, task-owned jpackage working directory to keep
bundled Python paths within WiX 3's path limits. Local builds can override its
parent with `-PwindowsJpackageTempDir=C:/temp` if the user temp path is too long.

The CUDA runtime is split into external cabinets using WiX MediaTemplate with a
512 MiB target for uncompressed content per cabinet. This avoids the single-CAB
size failure while retaining NVIDIA GPU support. A single large file may exceed
the target size, but release assets are checked to stay below 2 GiB each.
