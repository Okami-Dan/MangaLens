"""Network and integrity regression tests using small temporary files only."""
import contextlib
import functools
import hashlib
import http.server
import importlib.util
import io
from pathlib import Path
import socket
import tempfile
import threading
import unittest

SPEC = importlib.util.spec_from_file_location("setup_assets", Path(__file__).resolve().parents[1] / "setup_assets.py")
setup = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(setup)


class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


class InterruptedHandler(QuietHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Length", "100")
        self.end_headers()
        self.wfile.write(b"short")
        self.wfile.flush()
        self.connection.shutdown(socket.SHUT_WR)


class SetupAssetsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / "repo"
        self.source = Path(self.tmp.name) / "source"
        self.source.mkdir()
        self.data = b"verified model bytes"
        (self.source / "model.bin").write_bytes(self.data)
        self.asset = {"path": "var/models/model.bin", "file": "model.bin", "bytes": len(self.data),
                      "sha256": hashlib.sha256(self.data).hexdigest()}

    def install(self, **kwargs):
        with contextlib.redirect_stdout(io.StringIO()):
            setup.install_asset(self.root, self.asset, local_dir=kwargs.pop("local_dir", self.source),
                                base_url=kwargs.pop("base_url", "https://example.invalid"), **kwargs)

    def server(self, handler=QuietHandler):
        factory = functools.partial(handler, directory=str(self.source))
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), factory)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        return f"http://127.0.0.1:{server.server_port}"

    def test_local_import_and_offline_reuse(self):
        self.install()
        (self.source / "model.bin").unlink()
        self.install(local_dir=None, check=True)
        self.assertTrue(setup.verified(self.root / self.asset["path"], self.asset))

    def test_missing_local_file(self):
        (self.source / "model.bin").unlink()
        with self.assertRaises(FileNotFoundError):
            self.install()

    def test_check_missing_does_not_create_directories(self):
        with self.assertRaises(ValueError):
            self.install(check=True)
        self.assertFalse(self.root.exists())

    def test_corruption_does_not_replace_existing_file(self):
        target = self.root / self.asset["path"]
        target.parent.mkdir(parents=True)
        target.write_bytes(b"old file")
        (self.source / "model.bin").write_bytes(b"x" * len(self.data))
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            self.install()
        self.assertEqual(target.read_bytes(), b"old file")

    def test_http_download(self):
        self.install(local_dir=None, base_url=self.server())
        self.assertTrue(setup.verified(self.root / self.asset["path"], self.asset))

    def test_http_same_length_corruption_rejected(self):
        (self.source / "model.bin").write_bytes(b"x" * len(self.data))
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            self.install(local_dir=None, base_url=self.server())
        self.assertFalse((self.root / self.asset["path"]).exists())
        self.assertFalse(list(self.root.rglob("*.part")))

    def test_interrupted_download_and_stale_partial_retry(self):
        with self.assertRaises((OSError, ValueError)):
            self.install(local_dir=None, base_url=self.server(InterruptedHandler))
        target = self.root / self.asset["path"]
        self.assertFalse(target.exists())
        self.assertFalse(list(self.root.rglob("*.part")))
        target.with_name(target.name + ".part").write_bytes(b"stale")
        self.install(local_dir=None, base_url=self.server())
        self.assertEqual(target.read_bytes(), self.data)

    def test_oversize_download_rejected(self):
        (self.source / "model.bin").write_bytes(self.data * 3)
        with self.assertRaisesRegex(ValueError, "exceeds"):
            self.install(local_dir=None, base_url=self.server())
        self.assertFalse((self.root / self.asset["path"]).exists())

    def test_unsafe_destination_rejected(self):
        for path in ["", ".", "../escape", "var/../../escape", "/var/file", "C:/secret", "var\\file"]:
            with self.subTest(path=path), self.assertRaises(ValueError):
                setup.destination(self.root, {**self.asset, "path": path})


if __name__ == "__main__":
    unittest.main()
