"""Private base64/TSV stdio worker. No HTTP listener and no image uploads.

The external CtrlRegen implementation is fetched into user app data, never
redistributed in the installer. All source and model revisions are fixed.
"""
from __future__ import annotations

import base64
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import time
import urllib.request
import zipfile

PROTOCOL = sys.stdout
EVENT_LOCK = threading.Lock()
LAST_PROGRESS = (None, 0.0)
BACKEND_REVISION = "b642ae45d20eded52c96d570985eb4e3e427aac8"
BACKEND_SHA256 = "8cc924e699f1e46d158927d85750d82fe8fb66fa61c7886f73b8de6ef7a8eb5b"
MODEL_PINS = json.loads(Path(__file__).with_name("models.json").read_text())


def emit(kind, message=None):
    global LAST_PROGRESS
    with EVENT_LOCK:
        if kind == "progress":
            now = time.monotonic()
            if message == LAST_PROGRESS[0] and now - LAST_PROGRESS[1] < 2:
                return
            LAST_PROGRESS = (message, now)
        suffix = "" if message is None else "\t" + base64.b64encode(str(message).encode()).decode()
        print(kind + suffix, file=PROTOCOL, flush=True)


def data_directory():
    if sys.platform == "win32":
        base = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData/Local"))
    elif sys.platform == "darwin":
        base = Path.home() / "Library/Application Support"
    else:
        base = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local/share"))
    return base / "Image Exif Reset" / "synthid"


def prepare_backend(cache):
    destination = cache / ("backend-" + BACKEND_REVISION)
    if (destination / "src/ctrlregen/engine.py").is_file():
        return destination / "src"
    emit("progress", "Downloading pinned SynthID engine…")
    url = "https://codeload.github.com/mertizci/noai-watermark/zip/" + BACKEND_REVISION
    with urllib.request.urlopen(url, timeout=60) as response:
        archive = response.read(32 * 1024 * 1024 + 1)
    if hashlib.sha256(archive).hexdigest() != BACKEND_SHA256:
        raise RuntimeError("SynthID engine download failed integrity verification")
    with tempfile.TemporaryDirectory(dir=cache, prefix="backend-download-") as temporary:
        stage = Path(temporary)
        with zipfile.ZipFile(io.BytesIO(archive)) as bundle:
            for member in bundle.infolist():
                parts = Path(member.filename).parts
                # Copy only Python source. Reject traversal and never extract archive symlinks.
                if len(parts) < 3 or parts[1] != "src" or ".." in parts or member.is_dir():
                    continue
                if not member.filename.endswith(".py"):
                    continue
                target = stage.joinpath(*parts[1:])
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(bundle.read(member))
        stage.rename(destination)
    return destination / "src"


def prepare_models(cache):
    from huggingface_hub import snapshot_download
    from tqdm.auto import tqdm

    class DownloadProgress(tqdm):
        def display(self, *args, **kwargs):
            if self.total:
                emit("progress", f"Downloading models: {self.n}/{self.total} files")

    result = {}
    for repo, config in MODEL_PINS.items():
        arguments = dict(repo_id=repo, revision=config["revision"],
                         allow_patterns=config["patterns"], cache_dir=str(cache / "models"))
        # Complete snapshots must contain every selected file, not merely exist.
        marker = cache / (repo.replace("/", "--") + "-" + config["revision"] + ".json")
        if marker.exists():
            record = json.loads(marker.read_text())
            root = Path(record["root"])
            if all((root / name).is_file() and (root / name).stat().st_size == size
                   for name, size in record["files"].items()) and record["files"]:
                result[repo] = str(root)
                continue
        emit("progress", "Downloading model: " + repo + " (first use may take several minutes)")
        root = Path(snapshot_download(**arguments, tqdm_class=DownloadProgress, max_workers=2))
        record = {"root": str(root), "files": {str(p.relative_to(root)): p.stat().st_size
                                                for p in root.rglob("*") if p.is_file()}}
        temporary = marker.with_suffix(".tmp")
        temporary.write_text(json.dumps(record))
        temporary.replace(marker)
        result[repo] = str(root)
    return result


class Worker:
    def __init__(self):
        self.engine = None

    def load(self):
        if self.engine is not None:
            return
        cache = data_directory()
        cache.mkdir(parents=True, exist_ok=True)
        os.environ["HF_HOME"] = str(cache / "huggingface")
        os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
        os.environ["DO_NOT_TRACK"] = "1"
        # UniPC's small linear solve is unsupported on MPS in the pinned torch.
        # Permit that operation to run on CPU while model inference stays on MPS.
        os.environ.setdefault("PYTORCH_ENABLE_MPS_FALLBACK", "1")
        source = prepare_backend(cache)
        models = prepare_models(cache)
        # Adapt external source in the user's cache to local pinned snapshots.
        # Rebuild from the downloaded original on every worker start.
        runtime = cache / ("runtime-" + BACKEND_REVISION)
        runtime.mkdir(exist_ok=True)
        for original in source.rglob("*.py"):
            target = runtime / original.relative_to(source)
            target.parent.mkdir(parents=True, exist_ok=True)
            content = original.read_text()
            for repo, local in models.items():
                content = content.replace('"' + repo + '"', repr(local))
            target.write_text(content)
        sys.path.insert(0, str(runtime))
        emit("progress", "Loading SynthID models into memory…")
        import torch
        from ctrlregen.engine import CtrlRegenEngine, is_ctrlregen_available
        if not is_ctrlregen_available():
            raise RuntimeError("Bundled SynthID dependencies are incomplete")
        device = "cuda" if torch.cuda.is_available() else (
            "mps" if torch.backends.mps.is_available() else "cpu")
        # MPS uses float32 for numerical stability. CUDA uses float16.
        dtype = torch.float16 if device == "cuda" else torch.float32
        engine = CtrlRegenEngine(device=device, torch_dtype=dtype,
                                 progress_callback=lambda _: emit("progress", "Reducing SynthID on " + device + "…"))
        engine.load()
        self.engine = engine

    def reduce(self, source, destination):
        from PIL import Image, ImageOps, ImageStat
        self.load()
        with Image.open(source) as original:
            original.load()
            orientation = original.getexif().get(274, 1)
            image = ImageOps.exif_transpose(original).convert("RGB")
        width, height = image.size
        # Avoid the backend's center crop for small/non-square inputs and zero-size thin tiles.
        padded_size = (max(512, (width + 7) // 8 * 8), max(512, (height + 7) // 8 * 8))
        import numpy as np
        pixels = np.asarray(image)
        padded = Image.fromarray(np.pad(pixels, ((0, padded_size[1] - height),
                                                (0, padded_size[0] - width), (0, 0)), mode="edge"))
        result = self.engine.run(padded, strength=0.25, num_inference_steps=50, seed=0)
        result = result.crop((0, 0, width, height)).convert("RGB")
        if max(ImageStat.Stat(result).var) < 0.01 and max(ImageStat.Stat(image).var) > 1:
            raise RuntimeError("SynthID model produced a blank image")
        inverse = {2: Image.Transpose.FLIP_LEFT_RIGHT, 3: Image.Transpose.ROTATE_180,
                   4: Image.Transpose.FLIP_TOP_BOTTOM, 5: Image.Transpose.TRANSPOSE,
                   6: Image.Transpose.ROTATE_90, 7: Image.Transpose.TRANSVERSE,
                   8: Image.Transpose.ROTATE_270}
        if orientation in inverse:
            result = result.transpose(inverse[orientation])
        extension = destination.suffix.lower()
        if extension not in (".png", ".jpg", ".jpeg"):
            raise ValueError("Unsupported SynthID output format")
        result.save(destination, format="PNG" if extension == ".png" else "JPEG", quality=95)


def main():
    worker = Worker()
    for line in sys.stdin:
        try:
            operation, source, destination = line.rstrip("\n").split("\t")
            if operation != "reduce":
                raise ValueError("Unknown worker operation")
            source, destination = (Path(base64.b64decode(value, validate=True).decode())
                                   for value in (source, destination))
            if source.resolve() == destination.resolve():
                raise ValueError("Worker output must be separate from input")
            with contextlib.redirect_stdout(sys.stderr):
                worker.reduce(source, destination)
            emit("done")
        except Exception as error:
            print(type(error).__name__ + ": " + str(error), file=sys.stderr)
            emit("error", "SynthID engine could not complete processing (" + type(error).__name__ + ")")


if __name__ == "__main__":
    main()
