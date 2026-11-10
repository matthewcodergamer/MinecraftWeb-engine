#!/usr/bin/env python3
"""Offline tests for the Mojang server bundle extractor."""
import hashlib
import io
import os
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(__file__))
import fetch_server


class FetchServerTests(unittest.TestCase):
    def test_extracts_and_verifies_runtime_and_libraries(self):
        runtime_stream = io.BytesIO()
        with zipfile.ZipFile(runtime_stream, "w") as runtime_archive:
            runtime_archive.writestr("net/minecraft/server/Main.class", b"main")
            runtime_archive.writestr("net/minecraft/server/level/ServerLevel.class", b"level")
        runtime_bytes = runtime_stream.getvalue()
        library_bytes = b"synthetic bundled library"
        runtime_hash = hashlib.sha256(runtime_bytes).hexdigest()
        library_hash = hashlib.sha256(library_bytes).hexdigest()

        with tempfile.TemporaryDirectory() as temporary:
            bundle = os.path.join(temporary, "server-bundled.jar")
            with zipfile.ZipFile(bundle, "w") as archive:
                archive.writestr("META-INF/versions.list",
                                 f"{runtime_hash}\t26.3\t26.3/server-26.3.jar\n")
                archive.writestr("META-INF/libraries.list",
                                 f"{library_hash}\torg.example:demo:1.0\torg/example/demo/1.0/demo-1.0.jar\n")
                archive.writestr("META-INF/versions/26.3/server-26.3.jar", runtime_bytes)
                archive.writestr("META-INF/libraries/org/example/demo/1.0/demo-1.0.jar", library_bytes)

            runtime, count = fetch_server.extract_bundle(
                bundle, "26.3", os.path.join(temporary, "versions", "26.3"),
                os.path.join(temporary, "libraries")
            )
            self.assertEqual(open(runtime, "rb").read(), runtime_bytes)
            self.assertEqual(count, 1)
            extracted_library = os.path.join(
                temporary, "libraries", "org", "example", "demo", "1.0", "demo-1.0.jar"
            )
            self.assertEqual(open(extracted_library, "rb").read(), library_bytes)

    def test_rejects_path_traversal(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaises(RuntimeError):
                fetch_server.safe_output(temporary, "../outside.jar")

    def test_rejects_wrong_release_entry(self):
        runtime_stream = io.BytesIO()
        with zipfile.ZipFile(runtime_stream, "w") as runtime_archive:
            runtime_archive.writestr("net/minecraft/server/Main.class", b"main")
            runtime_archive.writestr("net/minecraft/server/level/ServerLevel.class", b"level")
        runtime_bytes = runtime_stream.getvalue()
        runtime_hash = hashlib.sha256(runtime_bytes).hexdigest()
        with tempfile.TemporaryDirectory() as temporary:
            bundle = os.path.join(temporary, "server-bundled.jar")
            with zipfile.ZipFile(bundle, "w") as archive:
                archive.writestr("META-INF/versions.list",
                                 f"{runtime_hash}\t26.2\t26.2/server-26.2.jar\n")
                archive.writestr("META-INF/libraries.list", "")
                archive.writestr("META-INF/versions/26.2/server-26.2.jar", runtime_bytes)
            with self.assertRaises(RuntimeError):
                fetch_server.extract_bundle(
                    bundle, "26.3", os.path.join(temporary, "versions"),
                    os.path.join(temporary, "libraries")
                )


if __name__ == "__main__":
    unittest.main()
