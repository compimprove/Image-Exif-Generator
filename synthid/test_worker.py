"""Worker contract tests; run with the private runtime and unittest discovery."""
import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from PIL import Image

spec = importlib.util.spec_from_file_location("worker", Path(__file__).with_name("worker.py"))
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)


class WorkerTests(unittest.TestCase):
    def test_preserves_non_square_content_and_all_orientations(self):
        class IdentityEngine:
            def run(self, image, **kwargs):
                self.size = image.size
                return image
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for orientation in range(1, 9):
                image = Image.new("RGB", (48, 24))
                image.putdata([(x * 5, y * 10, x + y) for y in range(24) for x in range(48)])
                exif = Image.Exif()
                exif[274] = orientation
                source, output = root / "source.png", root / "output.png"
                image.save(source, exif=exif)
                instance = worker.Worker()
                instance.engine = IdentityEngine()
                instance.reduce(source, output)
                with Image.open(output) as actual:
                    self.assertEqual(image.size, actual.size)
                    self.assertEqual(image.tobytes(), actual.tobytes())
                self.assertEqual((512, 512), instance.engine.size)

    def test_rejects_corrupt_backend_archive(self):
        import io
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(worker.urllib.request, "urlopen", return_value=io.BytesIO(b"corrupt")):
                with self.assertRaisesRegex(RuntimeError, "integrity"):
                    worker.prepare_backend(Path(directory))
            self.assertEqual([], list(Path(directory).iterdir()))

    def test_rejects_blank_regeneration(self):
        class BlankEngine:
            def run(self, image, **kwargs):
                return Image.new("RGB", image.size)
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory) / "in.png", Path(directory) / "out.png"
            image = Image.new("RGB", (20, 20), "white")
            image.putpixel((0, 0), (0, 0, 0))
            image.save(source)
            instance = worker.Worker()
            instance.engine = BlankEngine()
            with self.assertRaisesRegex(RuntimeError, "blank"):
                instance.reduce(source, output)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
