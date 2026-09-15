"""Build a relocatable private CPython engine for the current desktop platform.

Run with uv available: python3 synthid/bundle.py
Generated runtimes and wheels stay under desktopApp/build, outside source control.
"""
import argparse
from pathlib import Path
import platform
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cuda", action="store_true", help="Include CUDA 12.4 PyTorch wheels on Windows")
    args = parser.parse_args()
    if (platform.system(), platform.machine().lower()) not in (("Darwin", "arm64"), ("Windows", "amd64")):
        raise SystemExit("Bundle on macOS ARM64 or Windows x64")
    here = Path(__file__).resolve().parent
    target = here.parent / "desktopApp/build/synthid"
    python_root = target / "python"
    uv = shutil.which("uv")
    if uv is None:
        raise SystemExit("Install uv to build the private Python runtime")
    subprocess.run([uv, "python", "install", "3.11.13"], check=True)
    executable = Path(subprocess.check_output(
        [uv, "python", "find", "--managed-python", "3.11.13"], text=True).strip())
    windows = platform.system() == "Windows"
    source = executable.parent if windows else executable.parent.parent
    if python_root.exists():
        shutil.rmtree(python_root)
    shutil.copytree(source, python_root, symlinks=True)
    python = python_root / ("python.exe" if windows else "bin/python3")
    command = [uv, "pip", "install", "--python", str(python), "--break-system-packages",
               "-r", str(here / "requirements.txt")]
    if windows:
        index = "cu124" if args.cuda else "cpu"
        subprocess.run([uv, "pip", "install", "--python", str(python), "--break-system-packages",
                        "torch==2.6.0", "torchvision==0.21.0", "--index-url",
                        "https://download.pytorch.org/whl/" + index], check=True)
    subprocess.run(command, check=True)
    for name in ("worker.py", "models.json", "THIRD_PARTY.md"):
        shutil.copy2(here / name, target / name)
    subprocess.run([str(python), "-I", "-c",
                    "import torch, torchvision, diffusers, transformers, controlnet_aux, color_matcher; "
                    "print('SynthID runtime imports OK')"], check=True)
    print("Bundled SynthID engine:", target)


if __name__ == "__main__":
    main()
