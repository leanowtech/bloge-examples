#!/usr/bin/env python3
"""Unit tests for run-a1-3-development-gate.py (D7-T1)."""

from __future__ import annotations

import contextlib
import errno
import hashlib
import importlib.util
import json
import os
import pathlib
import stat
import subprocess
import sys
import tempfile
import unittest
import unittest.mock

_MOD_PATH = pathlib.Path(__file__).parent / "run-a1-3-development-gate.py"
_SPEC = importlib.util.spec_from_file_location("prod", _MOD_PATH)
prod = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(prod)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def minimal_authority(delivery_slices=None):
    if delivery_slices is None:
        delivery_slices = [
            {
                "sliceId": "A1.2",
                "owner": "PROVIDER",
                "handoffArtifacts": [
                    {
                        "role": "TCK_PROVIDER",
                        "coordinate": {
                            "groupId": "com.leanowtech.bloge",
                            "artifactId": "resource-gateway-gate-a-tck-provider",
                            "version": "1.0.0",
                        },
                        "path": "resource-gateway-gate-a-tck-provider/target/provider.jar",
                    }
                ],
                "outputArtifacts": [
                    {
                        "role": "TCK_PROVIDER",
                        "coordinate": {
                            "groupId": "com.leanowtech.bloge",
                            "artifactId": "resource-gateway-gate-a-tck-provider",
                            "version": "1.0.0",
                        },
                        "path": "resource-gateway-gate-a-tck-provider/target/provider.jar",
                    }
                ],
            }
        ]
    return json.dumps({"deliverySlices": delivery_slices}, ensure_ascii=True).encode()


# ---------------------------------------------------------------------------
# Canonical JSON
# ---------------------------------------------------------------------------

class TestCanonicalJSON(unittest.TestCase):

    def test_keys_sorted_recursive(self):
        data = {"z": {"b_key": 1, "a_key": 2}, "a": [3, 2, 1]}
        raw = prod.canonical_json(data)
        a_key_pos = raw.find(b'"a_key"')
        b_key_pos = raw.find(b'"b_key"')
        z_pos = raw.find(b'"z"')
        a_pos = raw.find(b'"a"')
        self.assertLess(a_key_pos, b_key_pos)
        self.assertLess(a_pos, z_pos)
        self.assertNotIn(b" ", raw)

    def test_ensure_ascii(self):
        data = {"msg": "hello world"}
        raw = prod.canonical_json(data)
        self.assertIsInstance(raw, bytes)
        raw.decode("ascii")

    def test_sha256_fingerprint_format(self):
        fp = prod.sha256_fingerprint(b"hello")
        self.assertTrue(fp.startswith("sha256:"))
        self.assertEqual(len(fp), 7 + 64)

    def test_binding_fingerprint_excludes_itself(self):
        binding_base = {
            "messageVersion": "1.0.0",
            "authorityRawFingerprint": "sha256:" + "a" * 64,
            "sourceSliceId": "A1.2",
            "targetSliceId": "A1.3",
            "providerArtifact": {
                "coordinate": "g:a:v",
                "path": "p.jar",
                "byteLength": 100,
                "rawFingerprint": "sha256:" + "b" * 64,
            },
            "bindingFingerprint": "sha256:" + "c" * 64,
        }
        binding_different_fp = dict(binding_base, bindingFingerprint="sha256:" + "d" * 64)
        self.assertEqual(prod.binding_fingerprint(binding_base),
                         prod.binding_fingerprint(binding_different_fp))

    def test_binding_fingerprint_deterministic(self):
        b = {
            "messageVersion": "1.0.0",
            "authorityRawFingerprint": "sha256:" + "a" * 64,
            "sourceSliceId": "A1.2",
            "targetSliceId": "A1.3",
            "providerArtifact": {
                "coordinate": "g:a:v",
                "path": "p.jar",
                "byteLength": 100,
                "rawFingerprint": "sha256:" + "b" * 64,
            },
            "bindingFingerprint": "",
        }
        self.assertEqual(prod.binding_fingerprint(b), prod.binding_fingerprint(b))

    def test_binding_fingerprint_changes_on_field_change(self):
        b1 = {
            "messageVersion": "1.0.0",
            "authorityRawFingerprint": "sha256:" + "a" * 64,
            "sourceSliceId": "A1.2",
            "targetSliceId": "A1.3",
            "providerArtifact": {
                "coordinate": "g:a:v", "path": "p.jar",
                "byteLength": 100, "rawFingerprint": "sha256:" + "b" * 64,
            },
            "bindingFingerprint": "",
        }
        b2 = {**b1, "providerArtifact": {**b1["providerArtifact"], "byteLength": 999}}
        self.assertNotEqual(prod.binding_fingerprint(b1), prod.binding_fingerprint(b2))


# ---------------------------------------------------------------------------
# Derive A1.2 artifact — exactly 1 TCK_PROVIDER in both lists, tuples match
# ---------------------------------------------------------------------------

class TestDeriveA12(unittest.TestCase):

    def test_valid_derivation(self):
        coord, path = prod.derive_a12_artifact(json.loads(minimal_authority()))
        self.assertEqual(coord, "com.leanowtech.bloge:resource-gateway-gate-a-tck-provider:1.0.0")
        self.assertEqual(path, "resource-gateway-gate-a-tck-provider/target/provider.jar")

    def test_missing_a12_slice(self):
        auth = json.dumps({"deliverySlices": [{"sliceId": "A1.3"}]}).encode()
        with self.assertRaises(prod.FailClosed) as ctx:
            prod.derive_a12_artifact(json.loads(auth))
        self.assertEqual(ctx.exception.args[0], "DG-A12-MISSING")

    def test_duplicate_a12_slice(self):
        auth = json.dumps({"deliverySlices": [
            {"sliceId": "A1.2", "handoffArtifacts": [], "outputArtifacts": []},
            {"sliceId": "A1.2", "handoffArtifacts": [], "outputArtifacts": []},
        ]}).encode()
        with self.assertRaises(prod.FailClosed) as ctx:
            prod.derive_a12_artifact(json.loads(auth))
        self.assertEqual(ctx.exception.args[0], "DG-A12-AMBIGUOUS")

    def test_no_tck_provider_handoff(self):
        auth = json.dumps({"deliverySlices": [{
            "sliceId": "A1.2",
            "handoffArtifacts": [{"role": "OTHER", "coordinate": {"groupId": "g", "artifactId": "a", "version": "v"}, "path": "x"}],
            "outputArtifacts": [{"role": "TCK_PROVIDER", "coordinate": {"groupId": "g", "artifactId": "a", "version": "v"}, "path": "x"}],
        }]}).encode()
        with self.assertRaises(prod.FailClosed) as ctx:
            prod.derive_a12_artifact(json.loads(auth))
        self.assertEqual(ctx.exception.args[0], "DG-A12-AMBIGUOUS")

    def test_no_tck_provider_output(self):
        auth = json.dumps({"deliverySlices": [{
            "sliceId": "A1.2",
            "handoffArtifacts": [{"role": "TCK_PROVIDER", "coordinate": {"groupId": "g", "artifactId": "a", "version": "v"}, "path": "x"}],
            "outputArtifacts": [{"role": "OTHER", "coordinate": {"groupId": "g", "artifactId": "a", "version": "v"}, "path": "x"}],
        }]}).encode()
        with self.assertRaises(prod.FailClosed) as ctx:
            prod.derive_a12_artifact(json.loads(auth))
        self.assertEqual(ctx.exception.args[0], "DG-A12-AMBIGUOUS")

    def test_multiple_tck_handoffs(self):
        auth = json.dumps({"deliverySlices": [{
            "sliceId": "A1.2",
            "handoffArtifacts": [
                {"role": "TCK_PROVIDER", "coordinate": {"groupId": "g", "artifactId": "a", "version": "v"}, "path": "x"},
                {"role": "TCK_PROVIDER", "coordinate": {"groupId": "g", "artifactId": "a", "version": "v"}, "path": "y"},
            ],
            "outputArtifacts": [{"role": "TCK_PROVIDER", "coordinate": {"groupId": "g", "artifactId": "a", "version": "v"}, "path": "x"}],
        }]}).encode()
        with self.assertRaises(prod.FailClosed) as ctx:
            prod.derive_a12_artifact(json.loads(auth))
        self.assertEqual(ctx.exception.args[0], "DG-A12-AMBIGUOUS")

    def test_multiple_tck_outputs(self):
        auth = json.dumps({"deliverySlices": [{
            "sliceId": "A1.2",
            "handoffArtifacts": [{"role": "TCK_PROVIDER", "coordinate": {"groupId": "g", "artifactId": "a", "version": "v"}, "path": "x"}],
            "outputArtifacts": [
                {"role": "TCK_PROVIDER", "coordinate": {"groupId": "g", "artifactId": "a", "version": "v"}, "path": "x"},
                {"role": "TCK_PROVIDER", "coordinate": {"groupId": "g", "artifactId": "a", "version": "v"}, "path": "y"},
            ],
        }]}).encode()
        with self.assertRaises(prod.FailClosed) as ctx:
            prod.derive_a12_artifact(json.loads(auth))
        self.assertEqual(ctx.exception.args[0], "DG-A12-AMBIGUOUS")

    def test_handoff_output_inconsistent(self):
        auth = json.dumps({"deliverySlices": [{
            "sliceId": "A1.2",
            "handoffArtifacts": [{"role": "TCK_PROVIDER", "coordinate": {"groupId": "g", "artifactId": "a", "version": "v"}, "path": "x"}],
            "outputArtifacts": [{"role": "TCK_PROVIDER", "coordinate": {"groupId": "g", "artifactId": "a", "version": "v"}, "path": "y"}],
        }]}).encode()
        with self.assertRaises(prod.FailClosed) as ctx:
            prod.derive_a12_artifact(json.loads(auth))
        self.assertEqual(ctx.exception.args[0], "DG-A12-AMBIGUOUS")

    def test_handoff_output_coord_mismatch(self):
        auth = json.dumps({"deliverySlices": [{
            "sliceId": "A1.2",
            "handoffArtifacts": [{"role": "TCK_PROVIDER", "coordinate": {"groupId": "g1", "artifactId": "a", "version": "v"}, "path": "x"}],
            "outputArtifacts": [{"role": "TCK_PROVIDER", "coordinate": {"groupId": "g2", "artifactId": "a", "version": "v"}, "path": "x"}],
        }]}).encode()
        with self.assertRaises(prod.FailClosed) as ctx:
            prod.derive_a12_artifact(json.loads(auth))
        self.assertEqual(ctx.exception.args[0], "DG-A12-AMBIGUOUS")

    def test_coordinate_missing_field(self):
        auth = json.dumps({"deliverySlices": [{
            "sliceId": "A1.2",
            "handoffArtifacts": [{"role": "TCK_PROVIDER", "coordinate": {"groupId": "g"}, "path": "x"}],
            "outputArtifacts": [{"role": "TCK_PROVIDER", "coordinate": {"groupId": "g"}, "path": "x"}],
        }]}).encode()
        with self.assertRaises(prod.FailClosed) as ctx:
            prod.derive_a12_artifact(json.loads(auth))
        self.assertEqual(ctx.exception.args[0], "DG-A12-AMBIGUOUS")

    def test_handoff_missing_path(self):
        auth = json.dumps({"deliverySlices": [{
            "sliceId": "A1.2",
            "handoffArtifacts": [{"role": "TCK_PROVIDER", "coordinate": {"groupId": "g", "artifactId": "a", "version": "v"}}],
            "outputArtifacts": [{"role": "TCK_PROVIDER", "coordinate": {"groupId": "g", "artifactId": "a", "version": "v"}, "path": "x"}],
        }]}).encode()
        with self.assertRaises(prod.FailClosed) as ctx:
            prod.derive_a12_artifact(json.loads(auth))
        self.assertEqual(ctx.exception.args[0], "DG-A12-AMBIGUOUS")


# ---------------------------------------------------------------------------
# stable_read
# ---------------------------------------------------------------------------

class TestStableRead(unittest.TestCase):

    def test_returns_correct_bytes(self):
        with tempfile.NamedTemporaryFile(delete=False) as f:
            f.write(b"hello world 12345")
            path = f.name
        try:
            data, dev, ino, size, mtime = prod.stable_read(path, 1024)
            self.assertEqual(data, b"hello world 12345")
            self.assertEqual(size, len(data))
            self.assertGreater(ino, 0)
        finally:
            os.unlink(path)

    def test_oversize_authority_rejected(self):
        with tempfile.NamedTemporaryFile(delete=False) as f:
            f.write(b"x" * (prod._AUTHORITY_MAX_BYTES + 1))
            path = f.name
        try:
            with self.assertRaises(prod.FailClosed) as ctx:
                prod.stable_read(path, prod._AUTHORITY_MAX_BYTES)
            self.assertEqual(ctx.exception.args[0], "DG-READ-OVERSIZE")
        finally:
            os.unlink(path)

    def test_oversize_provider_rejected(self):
        with tempfile.NamedTemporaryFile(delete=False) as f:
            f.write(b"x" * (prod._PROVIDER_MAX_BYTES + 1))
            path = f.name
        try:
            with self.assertRaises(prod.FailClosed) as ctx:
                prod.stable_read(path, prod._PROVIDER_MAX_BYTES)
            self.assertEqual(ctx.exception.args[0], "DG-READ-OVERSIZE")
        finally:
            os.unlink(path)

    def test_nonexistent_rejected(self):
        with self.assertRaises(prod.FailClosed) as ctx:
            prod.stable_read("/nonexistent/xyz/file", 1024)
        self.assertEqual(ctx.exception.args[0], "DG-READ-UNREADABLE")

    def test_fingerprint_matches_content(self):
        content = b"test content for fingerprint"
        fp = prod.sha256_fingerprint(content)
        expected = "sha256:" + hashlib.sha256(content).hexdigest()
        self.assertEqual(fp, expected)


# ---------------------------------------------------------------------------
# stable_read_repo_relative — dir-fd traversal
# ---------------------------------------------------------------------------

class TestStableReadRepoRelative(unittest.TestCase):

    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp())
        self.repo_root = self.tmp / "repo"
        self.repo_root.mkdir()
        (self.repo_root / "sub").mkdir()
        (self.repo_root / "sub" / "deep").mkdir()
        (self.repo_root / "sub" / "deep" / "file.txt").write_bytes(b"hello dir fd")
        (self.repo_root / "file2.txt").write_bytes(b"root level")

    def tearDown(self):
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_reads_file_via_dir_fd(self):
        content = b"hello dir fd"
        (self.repo_root / "sub" / "deep" / "file.txt").write_bytes(content)
        data, dev, ino, size, mtime_ns = prod.stable_read_repo_relative(
            self.repo_root, "sub/deep/file.txt", 1024
        )
        self.assertEqual(data, content)
        self.assertEqual(size, len(content))

    def test_reads_from_repo_root(self):
        content = b"root level"
        (self.repo_root / "file2.txt").write_bytes(content)
        data, *_ = prod.stable_read_repo_relative(self.repo_root, "file2.txt", 1024)
        self.assertEqual(data, content)

    def test_rejects_nul_in_rel_path(self):
        with self.assertRaises(prod.FailClosed) as ctx:
            prod.stable_read_repo_relative(self.repo_root, "a\x00b/file.txt", 1024)
        self.assertEqual(ctx.exception.args[0], "DG-READ-UNREADABLE")

    def test_rejects_backslash_in_rel_path(self):
        with self.assertRaises(prod.FailClosed) as ctx:
            prod.stable_read_repo_relative(self.repo_root, "a\\b/file.txt", 1024)
        self.assertEqual(ctx.exception.args[0], "DG-READ-UNREADABLE")

    def test_rejects_absolute_in_rel_path(self):
        with self.assertRaises(prod.FailClosed) as ctx:
            prod.stable_read_repo_relative(self.repo_root, "/etc/passwd", 1024)
        self.assertEqual(ctx.exception.args[0], "DG-READ-UNREADABLE")

    def test_rejects_dotdot_escape_in_rel_path(self):
        with self.assertRaises(prod.FailClosed) as ctx:
            prod.stable_read_repo_relative(self.repo_root, "sub/../../etc/passwd", 1024)
        self.assertEqual(ctx.exception.args[0], "DG-READ-UNREADABLE")

    def test_rejects_dot_segment_in_rel_path(self):
        with self.assertRaises(prod.FailClosed) as ctx:
            prod.stable_read_repo_relative(self.repo_root, "sub/./deep/file.txt", 1024)
        self.assertEqual(ctx.exception.args[0], "DG-READ-UNREADABLE")

    def test_rejects_parent_symlink_race(self):
        """Intermediate directory replaced with symlink: O_DIRECTORY on that segment raises ELOOP."""
        parent = self.repo_root / "parent"
        parent.mkdir()
        (parent / "child.txt").write_bytes(b"secret")
        parent_symlink = self.repo_root / "parent_link"
        parent_symlink.symlink_to(parent)

        orig_open = os.open

        def fake_open(path, flags, *, dir_fd=None):
            path_str = str(path)
            if path_str == str(self.repo_root):
                return orig_open(path_str, flags, dir_fd=dir_fd)
            if path_str == "parent_link" and dir_fd is not None:
                # Segment is actually a symlink → O_DIRECTORY raises ELOOP
                raise OSError(errno.ELOOP, "symbolic link in component", path_str)
            return orig_open(path_str, flags, dir_fd=dir_fd)

        with unittest.mock.patch('os.open', fake_open):
            with self.assertRaises(prod.FailClosed) as ctx:
                prod.stable_read_repo_relative(self.repo_root, "parent_link/child.txt", 1024)
            self.assertEqual(ctx.exception.args[0], "DG-READ-UNREADABLE")


    def test_rejects_intermediate_symlink(self):
        # Create actual target under sub/ so we can symlink to it
        target = self.repo_root / "sub" / "deep" / "file.txt"
        target.write_bytes(b"secret")
        link = self.repo_root / "link"
        link.symlink_to(self.repo_root / "sub")
        with self.assertRaises(prod.FailClosed) as ctx:
            prod.stable_read_repo_relative(self.repo_root, "link/deep/file.txt", 1024)
        self.assertEqual(ctx.exception.args[0], "DG-READ-UNREADABLE")

    def test_repo_root_open_eloop_rejected(self):
        """repo_root itself is a symlink: O_DIRECTORY on it raises ELOOP."""
        real = pathlib.Path(tempfile.mkdtemp()) / "real_repo"
        real.mkdir()
        (real / "file.txt").write_bytes(b"data")
        link = pathlib.Path(tempfile.mkdtemp()) / "repo_link"
        link.symlink_to(real)
        with self.assertRaises(prod.FailClosed) as ctx:
            prod.stable_read_repo_relative(link, "file.txt", 1024)
        self.assertEqual(ctx.exception.args[0], "DG-READ-UNREADABLE")

    def test_repo_root_not_directory_rejected(self):
        """repo_root is a regular file: O_DIRECTORY fails with ENOTDIR."""
        with tempfile.NamedTemporaryFile(delete=False) as f:
            f.write(b"not a dir")
            fpath = f.name
        try:
            with self.assertRaises(prod.FailClosed) as ctx:
                prod.stable_read_repo_relative(pathlib.Path(fpath), "file.txt", 1024)
            self.assertEqual(ctx.exception.args[0], "DG-READ-UNREADABLE")
        finally:
            os.unlink(fpath)

    def test_oversize_rejected(self):
        with self.assertRaises(prod.FailClosed) as ctx:
            prod.stable_read_repo_relative(self.repo_root, "file2.txt", 1)
        self.assertEqual(ctx.exception.args[0], "DG-READ-OVERSIZE")

    def test_nonexistent_file_rejected(self):
        with self.assertRaises(prod.FailClosed) as ctx:
            prod.stable_read_repo_relative(self.repo_root, "nonexistent.txt", 1024)
        self.assertEqual(ctx.exception.args[0], "DG-READ-UNREADABLE")


# ---------------------------------------------------------------------------
# stable_read: stale detection
# ---------------------------------------------------------------------------

class TestStableReadStale(unittest.TestCase):

    def test_stale_file_rejected(self):
        with tempfile.NamedTemporaryFile(delete=False) as f:
            f.write(b"original")
            path = f.name
        try:
            original_fstat = os.fstat
            call_count = [0]

            def fake_fstat(fd):
                call_count[0] += 1
                result = original_fstat(fd)
                if call_count[0] == 2:
                    result = os.stat_result((
                        result.st_mode, result.st_ino, result.st_dev,
                        result.st_nlink, result.st_uid, result.st_gid,
                        result.st_size, result.st_atime,
                        result.st_mtime + 1e-9,
                        result.st_ctime, result.st_ino,
                        result.st_blocks, result.st_blksize,
                        result.st_flags, result.st_gen,
                        result.st_birthtime,
                        result.st_mtime_ns + 1
                    ))
                return result

            with unittest.mock.patch('os.fstat', fake_fstat):
                with self.assertRaises(prod.FailClosed) as ctx:
                    prod.stable_read(path, 1024)
                self.assertEqual(ctx.exception.args[0], "DG-READ-STALE")
        finally:
            os.unlink(path)


# ---------------------------------------------------------------------------
# Authority JSON — duplicate key rejection
# ---------------------------------------------------------------------------

class TestAuthorityJSON(unittest.TestCase):

    def test_duplicate_key_rejected(self):
        raw = b'{"a":1,"a":2}'
        with self.assertRaises(prod.FailClosed) as ctx:
            prod.parse_authority_json(raw, "/fake")
        self.assertEqual(ctx.exception.args[0], "DG-AUTHORITY-INVALID")

    def test_valid_authority_passthrough(self):
        auth = json.loads(minimal_authority().decode())
        result = prod.parse_authority_json(minimal_authority(), "/fake")
        self.assertEqual(result["deliverySlices"][0]["sliceId"], "A1.2")

    def test_invalid_utf8_rejected(self):
        raw = b'\xff\xfe'
        with self.assertRaises(prod.FailClosed) as ctx:
            prod.parse_authority_json(raw, "/fake")
        self.assertEqual(ctx.exception.args[0], "DG-AUTHORITY-INVALID")


# ---------------------------------------------------------------------------
# Maven invocation — bounded sink, not PIPE
# ---------------------------------------------------------------------------

class TestMavenInvocation(unittest.TestCase):

    def test_maven_called_with_correct_flags(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            binding_path = pathlib.Path(tmpdir) / "binding.json"
            repo_root = pathlib.Path(tmpdir)
            pdir = repo_root / "resource-gateway-gate-a-tck-provider" / "target"
            pdir.mkdir(parents=True)
            (pdir / "provider.jar").write_bytes(b"PK\x03\x04JAR")

            with unittest.mock.patch.object(prod, "stable_read", autospec=True) as mock_sr:
                with unittest.mock.patch.object(prod, "stable_read_repo_relative", autospec=True) as mock_srrr:
                    auth_bytes = minimal_authority()
                    pbytes = b"PK\x03\x04JAR"
                    mock_sr.return_value = (auth_bytes, 1, 1, len(auth_bytes), 1)
                    mock_srrr.return_value = (pbytes, 1, 2, len(pbytes), 1)

                    with unittest.mock.patch.object(subprocess, "Popen") as mock_popen:
                        mock_proc = unittest.mock.MagicMock()
                        mock_proc.returncode = 0
                        mock_proc.wait.return_value = 0
                        mock_proc.communicate.return_value = (b"", b"")
                        mock_popen.return_value = mock_proc

                        with self.assertRaises(SystemExit) as ctx:
                            prod.build_binding("/fake/auth", repo_root, binding_path, skip_maven=False)
                        self.assertEqual(ctx.exception.code, 0)

                        # Verify sink is NOT PIPE and has file-like interface
                        call_kwargs = mock_popen.call_args.kwargs
                        stdout_val = call_kwargs.get("stdout")
                        stderr_val = call_kwargs.get("stderr")
                        self.assertIsNot(stdout_val, subprocess.PIPE)
                        self.assertIsNot(stderr_val, subprocess.PIPE)
                        self.assertTrue(hasattr(stdout_val, "seek"))
                        self.assertTrue(hasattr(stdout_val, "read"))
                        self.assertTrue(hasattr(stderr_val, "seek"))
                        self.assertTrue(hasattr(stderr_val, "read"))

    def test_maven_failure_exits_2(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            binding_path = pathlib.Path(tmpdir) / "binding.json"
            repo_root = pathlib.Path(tmpdir)

            with unittest.mock.patch.object(prod, "stable_read", autospec=True) as mock_sr:
                with unittest.mock.patch.object(prod, "stable_read_repo_relative", autospec=True) as mock_srrr:
                    mock_sr.return_value = (minimal_authority(), 1, 1, 100, 1)
                    mock_srrr.return_value = (b"PK", 1, 2, 2, 1)

                    with unittest.mock.patch.object(subprocess, "Popen") as mock_popen:
                        mock_proc = unittest.mock.MagicMock()
                        mock_proc.returncode = 1
                        mock_proc.wait.return_value = 1
                        mock_proc.communicate.return_value = (b"out", b"err")
                        mock_popen.return_value = mock_proc

                        with self.assertRaises(SystemExit) as ctx:
                            prod.build_binding("/fake/auth", repo_root, binding_path, skip_maven=False)
                        self.assertEqual(ctx.exception.code, 2)

    def test_missing_provider_jar_exits_2(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            binding_path = pathlib.Path(tmpdir) / "binding.json"
            repo_root = pathlib.Path(tmpdir)

            with unittest.mock.patch.object(prod, "stable_read", autospec=True) as mock_sr:
                with unittest.mock.patch.object(prod, "stable_read_repo_relative", autospec=True) as mock_srrr:
                    mock_sr.return_value = (minimal_authority(), 1, 1, 100, 1)
                    mock_srrr.side_effect = prod.FailClosed("DG-READ-UNREADABLE", "/nonexistent")

                    with self.assertRaises(SystemExit) as ctx:
                        prod.build_binding("/fake/auth", repo_root, binding_path, skip_maven=True)
                    self.assertEqual(ctx.exception.code, 2)
            self.assertFalse(binding_path.exists())


# ---------------------------------------------------------------------------
# Authority validation — stable_read failure maps to DG-AUTHORITY-INVALID
# ---------------------------------------------------------------------------

class TestAuthorityValidation(unittest.TestCase):

    def test_not_json_exits_2(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            binding_path = pathlib.Path(tmpdir) / "binding.json"
            auth_file = pathlib.Path(tmpdir) / "auth.json"
            auth_file.write_bytes(b"not json at all")

            with self.assertRaises(SystemExit) as ctx:
                prod.build_binding(str(auth_file), pathlib.Path(tmpdir), binding_path, skip_maven=True)
            self.assertEqual(ctx.exception.code, 2)
            self.assertFalse(binding_path.exists())

    def test_missing_delivery_slices_exits_2(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            binding_path = pathlib.Path(tmpdir) / "binding.json"
            auth_file = pathlib.Path(tmpdir) / "auth.json"
            auth_file.write_bytes(b'{"other": true}')

            with self.assertRaises(SystemExit) as ctx:
                prod.build_binding(str(auth_file), pathlib.Path(tmpdir), binding_path, skip_maven=True)
            self.assertEqual(ctx.exception.code, 2)
            self.assertFalse(binding_path.exists())

    def test_authority_stable_read_unreadable_maps_to_dg_authority_invalid(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            binding_path = pathlib.Path(tmpdir) / "binding.json"
            repo_root = pathlib.Path(tmpdir)

            import io
            buf = io.StringIO()
            with unittest.mock.patch.object(prod, "stable_read", autospec=True) as mock_sr:
                mock_sr.side_effect = prod.FailClosed("DG-READ-UNREADABLE", "/nonexistent/auth")
                with contextlib.redirect_stderr(buf):
                    with self.assertRaises(SystemExit) as ctx:
                        prod.build_binding("/nonexistent/auth", repo_root, binding_path, skip_maven=True)
                    self.assertEqual(ctx.exception.code, 2)
            buf.seek(0)
            stderr_output = buf.read()
            self.assertIn("DG-AUTHORITY-INVALID", stderr_output)
            self.assertIn("DG-READ-UNREADABLE", stderr_output)
            self.assertIn("/nonexistent/auth", stderr_output)


# ---------------------------------------------------------------------------
# No bytes leak
# ---------------------------------------------------------------------------

class TestNoBytesLeak(unittest.TestCase):

    def test_binding_contains_fingerprint_not_raw_bytes(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            binding_path = pathlib.Path(tmpdir) / "binding.json"
            repo_root = pathlib.Path(tmpdir)
            pdir = repo_root / "resource-gateway-gate-a-tck-provider" / "target"
            pdir.mkdir(parents=True)
            pjar = pdir / "provider.jar"
            pjar.write_bytes(b"raw secret \x00\xff\nbytes")

            auth_bytes = minimal_authority()

            with unittest.mock.patch.object(prod, "stable_read", autospec=True) as mock_sr:
                with unittest.mock.patch.object(prod, "stable_read_repo_relative", autospec=True) as mock_srrr:
                    pbytes = pjar.read_bytes()
                    mock_sr.return_value = (auth_bytes, 1, 1, len(auth_bytes), 1)
                    mock_srrr.return_value = (pbytes, 1, 2, len(pbytes), pjar.stat().st_mtime_ns)

                    with self.assertRaises(SystemExit) as ctx:
                        prod.build_binding("/fake/auth", repo_root, binding_path, skip_maven=True)
                    self.assertEqual(ctx.exception.code, 0)

            raw = binding_path.read_bytes()
            binding_json = json.loads(raw.decode("utf-8"))
            self.assertTrue(binding_json["providerArtifact"]["rawFingerprint"].startswith("sha256:"))
            self.assertNotIn(b"raw secret", raw)
            self.assertNotIn(b"\xff", raw)


# ---------------------------------------------------------------------------
# skip-maven writes binding then exits 0
# ---------------------------------------------------------------------------

class TestSkipMaven(unittest.TestCase):

    def test_skip_maven_writes_and_exits_0(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            binding_path = pathlib.Path(tmpdir) / "binding.json"
            repo_root = pathlib.Path(tmpdir)
            pdir = repo_root / "resource-gateway-gate-a-tck-provider" / "target"
            pdir.mkdir(parents=True)
            pjar = pdir / "provider.jar"
            pjar.write_bytes(b"PK\x03\x04JAR")

            auth_bytes = minimal_authority()

            with unittest.mock.patch.object(prod, "stable_read", autospec=True) as mock_sr:
                with unittest.mock.patch.object(prod, "stable_read_repo_relative", autospec=True) as mock_srrr:
                    pbytes = pjar.read_bytes()
                    mock_sr.return_value = (auth_bytes, 1, 1, len(auth_bytes), 1)
                    mock_srrr.return_value = (pbytes, 1, 2, len(pbytes), pjar.stat().st_mtime_ns)

                    with self.assertRaises(SystemExit) as ctx:
                        prod.build_binding("/fake/auth", repo_root, binding_path, skip_maven=True)
                    self.assertEqual(ctx.exception.code, 0)

            self.assertTrue(binding_path.exists())
            bj = json.loads(binding_path.read_bytes().decode("utf-8"))
            self.assertEqual(bj["messageVersion"], "1.0.0")
            self.assertEqual(bj["sourceSliceId"], "A1.2")
            self.assertEqual(bj["targetSliceId"], "A1.3")
            self.assertTrue(bj["bindingFingerprint"].startswith("sha256:"))


# ---------------------------------------------------------------------------
# Non-regular file
# ---------------------------------------------------------------------------

class TestNonRegularFile(unittest.TestCase):

    def test_directory_rejected(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            binding_path = pathlib.Path(tmpdir) / "binding.json"
            repo_root = pathlib.Path(tmpdir)
            auth_file = pathlib.Path(tmpdir) / "auth.json"
            auth_file.write_bytes(minimal_authority())

            pdir = repo_root / "resource-gateway-gate-a-tck-provider" / "target"
            pdir.mkdir(parents=True)

            auth_for_dir = json.dumps({
                "deliverySlices": [{
                    "sliceId": "A1.2",
                    "handoffArtifacts": [
                        {"role": "TCK_PROVIDER", "coordinate": {"groupId": "g", "artifactId": "a", "version": "v"},
                         "path": "resource-gateway-gate-a-tck-provider/target"}
                    ],
                    "outputArtifacts": [
                        {"role": "TCK_PROVIDER", "coordinate": {"groupId": "g", "artifactId": "a", "version": "v"},
                         "path": "resource-gateway-gate-a-tck-provider/target"}
                    ],
                }]
            }).encode()

            with unittest.mock.patch.object(prod, "stable_read", autospec=True) as mock_sr:
                with unittest.mock.patch.object(prod, "stable_read_repo_relative", autospec=True) as mock_srrr:
                    mock_sr.return_value = (auth_for_dir, 1, 1, len(auth_for_dir), 1)
                    mock_srrr.side_effect = prod.FailClosed("DG-READ-UNREADABLE", str(pdir))

                    with self.assertRaises(SystemExit) as ctx:
                        prod.build_binding(str(auth_file), repo_root, binding_path, skip_maven=True)
                    self.assertEqual(ctx.exception.code, 2)


# ---------------------------------------------------------------------------
# O_EXCL — existing binding file
# ---------------------------------------------------------------------------

class TestOEXCL(unittest.TestCase):

    def test_existing_binding_rejected(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            binding_path = pathlib.Path(tmpdir) / "binding.json"
            binding_path.write_bytes(b'{"x":1}\n')

            repo_root = pathlib.Path(tmpdir)
            auth_file = pathlib.Path(tmpdir) / "auth.json"
            auth_file.write_bytes(minimal_authority())
            pdir = repo_root / "resource-gateway-gate-a-tck-provider" / "target"
            pdir.mkdir(parents=True)
            (pdir / "provider.jar").write_bytes(b"PK\x03\x04JAR")

            with unittest.mock.patch.object(prod, "stable_read", autospec=True) as mock_sr:
                with unittest.mock.patch.object(prod, "stable_read_repo_relative", autospec=True) as mock_srrr:
                    mock_sr.return_value = (minimal_authority(), 1, 1, 100, 1)
                    mock_srrr.return_value = (b"PK", 1, 2, 2, 1)

                    with self.assertRaises(SystemExit) as ctx:
                        prod.build_binding(str(auth_file), repo_root, binding_path, skip_maven=True)
                    self.assertEqual(ctx.exception.code, 2)


# ---------------------------------------------------------------------------
# Written binding file mode
# ---------------------------------------------------------------------------

class TestBindingFileMode(unittest.TestCase):

    def test_binding_written_mode_0600(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            binding_path = pathlib.Path(tmpdir) / "binding.json"
            prod.write_binding(b'{"messageVersion":"1.0.0"}\n', binding_path)
            mode = binding_path.stat().st_mode & 0o777
            self.assertEqual(mode, 0o600)


# ---------------------------------------------------------------------------
# Write failure cleanup
# ---------------------------------------------------------------------------

class TestWriteFailureCleanup(unittest.TestCase):

    def test_write_failure_removes_partial_binding(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            binding_path = pathlib.Path(tmpdir) / "binding.json"
            big_line = b'{"x":"' + b'x' * 1000 + b'"}\n'

            original_write = os.write
            call_num = [0]

            def fake_write(fd, data):
                call_num[0] += 1
                if call_num[0] == 1:
                    return original_write(fd, data[:1])
                else:
                    raise OSError(errno.ENOSPC, "No space left on device")

            with unittest.mock.patch('os.write', fake_write):
                with self.assertRaises(prod.FailClosed) as ctx:
                    prod.write_binding(big_line, binding_path)
                self.assertEqual(ctx.exception.args[0], "DG-BINDING-WRITE")
            self.assertFalse(binding_path.exists())

    def test_zero_write_from_os_write_exits_failclosed(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            binding_path = pathlib.Path(tmpdir) / "binding.json"

            def fake_write(fd, data):
                return 0

            with unittest.mock.patch('os.write', fake_write):
                with self.assertRaises(prod.FailClosed) as ctx:
                    prod.write_binding(b'{"x":1}\n', binding_path)
                self.assertEqual(ctx.exception.args[0], "DG-BINDING-WRITE")
            self.assertFalse(binding_path.exists())


# ---------------------------------------------------------------------------
# CLI argument validation
# ---------------------------------------------------------------------------

class TestCLIArgs(unittest.TestCase):

    def test_missing_authority_arg_exits_2_with_exact_code(self):
        with tempfile.NamedTemporaryFile(delete=False) as f:
            f.write(b"{}")
            auth = f.name
        try:
            script = str(pathlib.Path(__file__).parent / "run-a1-3-development-gate.py")
            proc = subprocess.Popen(
                [sys.executable, script, "--repo-root", "/tmp", "--binding-path", "/tmp/binding.json"],
                stderr=subprocess.PIPE,
            )
            _, stderr = proc.communicate()
            self.assertIn(b"DG-ARG-MISSING", stderr)
            self.assertIn(b"--authority", stderr)
            self.assertNotIn(b"usage:", stderr.lower())
        finally:
            os.unlink(auth)

    def test_missing_all_args_exits_2_with_exact_code(self):
        script = str(pathlib.Path(__file__).parent / "run-a1-3-development-gate.py")
        proc = subprocess.Popen([sys.executable, script], stderr=subprocess.PIPE)
        _, stderr = proc.communicate()
        self.assertIn(b"DG-ARG-MISSING", stderr)
        self.assertNotIn(b"usage:", stderr.lower())

    def test_repo_root_not_absolute_rejected(self):
        script = str(pathlib.Path(__file__).parent / "run-a1-3-development-gate.py")
        with tempfile.TemporaryDirectory() as tdir:
            auth = pathlib.Path(tdir) / "auth.json"
            auth.write_bytes(minimal_authority())
            proc = subprocess.Popen(
                [sys.executable, script,
                 "--authority", str(auth),
                 "--repo-root", "relative/path",
                 "--binding-path", str(pathlib.Path(tdir) / "b.json")],
                stderr=subprocess.PIPE,
            )
            _, stderr = proc.communicate()
            self.assertIn(b"DG-ARG-MISSING", stderr)
            self.assertIn(b"repo-root not absolute", stderr)

    def test_repo_root_not_found_rejected(self):
        script = str(pathlib.Path(__file__).parent / "run-a1-3-development-gate.py")
        with tempfile.TemporaryDirectory() as tdir:
            auth = pathlib.Path(tdir) / "auth.json"
            auth.write_bytes(minimal_authority())
            proc = subprocess.Popen(
                [sys.executable, script,
                 "--authority", str(auth),
                 "--repo-root", "/nonexistent/repo/root",
                 "--binding-path", str(pathlib.Path(tdir) / "b.json")],
                stderr=subprocess.PIPE,
            )
            _, stderr = proc.communicate()
            self.assertIn(b"DG-ARG-MISSING", stderr)

    def test_binding_path_not_absolute_rejected(self):
        script = str(pathlib.Path(__file__).parent / "run-a1-3-development-gate.py")
        with tempfile.TemporaryDirectory() as tdir:
            auth = pathlib.Path(tdir) / "auth.json"
            auth.write_bytes(minimal_authority())
            proc = subprocess.Popen(
                [sys.executable, script,
                 "--authority", str(auth),
                 "--repo-root", tdir,
                 "--binding-path", "relative/binding.json"],
                stderr=subprocess.PIPE,
            )
            _, stderr = proc.communicate()
            self.assertIn(b"DG-ARG-MISSING", stderr)
            self.assertIn(b"binding-path not absolute", stderr)

    def test_binding_path_parent_not_found_rejected(self):
        script = str(pathlib.Path(__file__).parent / "run-a1-3-development-gate.py")
        with tempfile.TemporaryDirectory() as tdir:
            auth = pathlib.Path(tdir) / "auth.json"
            auth.write_bytes(minimal_authority())
            proc = subprocess.Popen(
                [sys.executable, script,
                 "--authority", str(auth),
                 "--repo-root", tdir,
                 "--binding-path", "/nonexistent/parent/binding.json"],
                stderr=subprocess.PIPE,
            )
            _, stderr = proc.communicate()
            self.assertIn(b"DG-ARG-MISSING", stderr)
            self.assertIn(b"binding-path parent", stderr)


# ---------------------------------------------------------------------------
# Authority final symlink
# ---------------------------------------------------------------------------

class TestAuthoritySymlinkFinal(unittest.TestCase):

    def test_final_symlink_in_authority_rejected(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            auth_link = pathlib.Path(tmpdir) / "auth.json"
            auth_target = pathlib.Path(tmpdir) / "auth_target.json"
            auth_target.write_bytes(minimal_authority())
            auth_link.symlink_to(auth_target)

            binding_path = pathlib.Path(tmpdir) / "binding.json"
            repo_root = pathlib.Path(tmpdir)
            pdir = repo_root / "resource-gateway-gate-a-tck-provider" / "target"
            pdir.mkdir(parents=True)
            (pdir / "provider.jar").write_bytes(b"PK\x03\x04JAR")

            import io
            buf = io.StringIO()
            with unittest.mock.patch.object(prod, "stable_read", autospec=True) as mock_sr:
                with unittest.mock.patch.object(prod, "stable_read_repo_relative", autospec=True) as mock_srrr:
                    mock_sr.side_effect = prod.FailClosed("DG-READ-UNREADABLE", str(auth_link))
                    mock_srrr.return_value = (b"PK", 1, 2, 2, 1)

                with contextlib.redirect_stderr(buf):
                        with self.assertRaises(SystemExit) as ctx:
                            prod.build_binding(str(auth_link), repo_root, binding_path, skip_maven=True)
                        self.assertEqual(ctx.exception.code, 2)
            buf.seek(0)
            stderr_output = buf.read()
            self.assertIn("DG-AUTHORITY-INVALID", stderr_output)
            self.assertIn("DG-READ-UNREADABLE", stderr_output)


# ---------------------------------------------------------------------------
# Real-code coverage tests (A1.3-R03)
# ---------------------------------------------------------------------------

class TestStableReadShortRead(unittest.TestCase):
    def test_short_read_rejected_as_stale(self):
        with tempfile.NamedTemporaryFile(delete=False) as f:
            f.write(b"short content")
            path = f.name
        try:
            orig_read = os.read
            call_count = [0]
            def fake_read(fd, n):
                if call_count[0] == 0:
                    call_count[0] += 1
                    return orig_read(fd, 3)
                return b""
            with unittest.mock.patch("os.read", fake_read):
                with self.assertRaises(prod.FailClosed) as ctx:
                    prod.stable_read(path, 1024)
                self.assertEqual(ctx.exception.args[0], "DG-READ-STALE")
        finally:
            os.unlink(path)


class TestDeriveA12NonBlankCoord(unittest.TestCase):
    def test_blank_groupId_rejected(self):
        auth = json.dumps({"deliverySlices": [{
            "sliceId": "A1.2",
            "handoffArtifacts": [{"role": "TCK_PROVIDER", "coordinate": {"groupId": "  ", "artifactId": "a", "version": "v"}, "path": "x"}],
            "outputArtifacts": [{"role": "TCK_PROVIDER", "coordinate": {"groupId": "  ", "artifactId": "a", "version": "v"}, "path": "x"}],
        }]}).encode()
        with self.assertRaises(prod.FailClosed) as ctx:
            prod.derive_a12_artifact(json.loads(auth))
        self.assertEqual(ctx.exception.args[0], "DG-A12-AMBIGUOUS")

    def test_classifier_present_rejected(self):
        auth = json.dumps({"deliverySlices": [{
            "sliceId": "A1.2",
            "handoffArtifacts": [{"role": "TCK_PROVIDER", "coordinate": {"groupId": "g", "artifactId": "a", "version": "v"}, "path": "x", "classifier": "sources"}],
            "outputArtifacts": [{"role": "TCK_PROVIDER", "coordinate": {"groupId": "g", "artifactId": "a", "version": "v"}, "path": "x", "classifier": "sources"}],
        }]}).encode()
        with self.assertRaises(prod.FailClosed) as ctx:
            prod.derive_a12_artifact(json.loads(auth))
        self.assertEqual(ctx.exception.args[0], "DG-A12-AMBIGUOUS")


class TestWriteBindingParentSymlink(unittest.TestCase):
    def test_parent_symlink_raises_dg_binding_write_target_not_created(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            real_parent = pathlib.Path(tmpdir) / "real_parent"
            real_parent.mkdir()
            link_parent = pathlib.Path(tmpdir) / "link_parent"
            link_parent.symlink_to(real_parent)
            binding_path = link_parent / "binding.json"
            with self.assertRaises(prod.FailClosed) as ctx:
                prod.write_binding(b'{"x":1}\n', binding_path)
            self.assertEqual(ctx.exception.args[0], "DG-BINDING-WRITE")
            self.assertFalse(binding_path.exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
