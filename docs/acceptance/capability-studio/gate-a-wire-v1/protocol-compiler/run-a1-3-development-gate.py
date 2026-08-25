#!/usr/bin/env python3
"""Fail-closed A1.3 DEVELOPMENT predecessor fingerprint binding producer."""

from __future__ import annotations

import argparse
import errno
import hashlib
import json as _json
import os
import pathlib
import stat
import subprocess
import sys
import tempfile
from typing import Any

_AUTHORITY_MAX_BYTES: int = 4 * 1024 * 1024
_PROVIDER_MAX_BYTES: int = 16 * 1024 * 1024
_BINDING_DOMAIN: str = "RG-CS-GATE-A-A1-3-DEVELOPMENT-PREDECESSOR-BINDING-v1"
_BINDING_MSG_VERSION: str = "1.0.0"
_SOURCE_SLICE_ID: str = "A1.2"
_TARGET_SLICE_ID: str = "A1.3"
_BINDING_SEPARATORS = (",", ":")


class FailClosed(Exception):
    def __init__(self, code: str, *detail: Any):
        super().__init__(code, *detail)

    def stderr_line(self) -> str:
        parts = [self.args[0]] + [str(a) for a in self.args[1:]]
        return ":".join(parts)


def _canonical_dict(obj: Any) -> Any:
    if isinstance(obj, dict):
        return {k: _canonical_dict(v) for k, v in sorted(obj.items())}
    if isinstance(obj, list):
        return [_canonical_dict(item) for item in obj]
    return obj


def canonical_json(data: dict) -> bytes:
    return _json.dumps(
        _canonical_dict(data), ensure_ascii=True, separators=_BINDING_SEPARATORS
    ).encode("ascii")


def sha256_fingerprint(data: bytes) -> str:
    return f"sha256:{hashlib.sha256(data).hexdigest()}"


def binding_fingerprint(binding: dict) -> str:
    without_fp = {k: v for k, v in binding.items() if k != "bindingFingerprint"}
    payload = _BINDING_DOMAIN.encode("ascii") + b"\x00" + canonical_json(without_fp)
    return f"sha256:{hashlib.sha256(payload).hexdigest()}"


# ---------------------------------------------------------------------------
# Stable read — O_NOFOLLOW, fstat, bounded, pre/post check
# ---------------------------------------------------------------------------

def stable_read(path: str, max_bytes: int) -> tuple[bytes, int, int, int, int]:
    """Open O_RDONLY|O_NOFOLLOW, fstat pre/post, bounded read."""
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    except OSError as e:
        raise FailClosed("DG-READ-UNREADABLE", path) from e

    try:
        pre = os.fstat(fd)
        if not stat.S_ISREG(pre.st_mode):
            raise FailClosed("DG-READ-UNREADABLE", path)
        if pre.st_size > max_bytes:
            raise FailClosed("DG-READ-OVERSIZE", path, pre.st_size)

        dev, ino = pre.st_dev, pre.st_ino
        pre_size, pre_mtime_ns = pre.st_size, pre.st_mtime_ns

        buf = bytearray(pre_size)
        offset = 0
        while offset < len(buf):
            chunk = os.read(fd, len(buf) - offset)
            if not chunk:
                break
            buf[offset:offset + len(chunk)] = chunk
            offset += len(chunk)

        # Incomplete read → stale (not short-return; fd closed in finally)
        if offset != pre_size:
            try:
                os.close(fd)
            except OSError:
                pass
            raise FailClosed("DG-READ-STALE", path)

        post = os.fstat(fd)
        if post.st_dev != dev or post.st_ino != ino:
            raise FailClosed("DG-READ-STALE", path)
        if post.st_size != pre_size or post.st_mtime_ns != pre_mtime_ns:
            raise FailClosed("DG-READ-STALE", path)

        return bytes(buf), dev, ino, pre_size, pre_mtime_ns

    finally:
        try:
            os.close(fd)
        except OSError:
            pass


# ---------------------------------------------------------------------------
# stable_read_repo_relative — directory-FD traversal, no lstat/open race
# ---------------------------------------------------------------------------

def stable_read_repo_relative(
    repo_root: pathlib.Path,
    rel_path: str,
    max_bytes: int,
) -> tuple[bytes, int, int, int, int]:
    """Traverse rel_path via dir-fd chain from repo_root (no symlink escape, no race).

    Opens repo_root with O_NOFOLLOW|O_DIRECTORY|O_RDONLY to get a rooted fd, then opens
    each path segment with dir_fd + O_NOFOLLOW|O_DIRECTORY (intermediates) or
    O_NOFOLLOW|O_RDONLY (final file).  Uses fstat on each opened fd to verify
    each segment — no separate lstat call, no window between lstat and open.
    Rejects: empty, absolute, NUL byte, backslash anywhere, empty/dot/dotdot
    segments, non-directory intermediates, non-regular final, size > max_bytes,
    incomplete read (offset != pre_size), pre/post fstat mismatch.
    """
    # Reject invalid path forms
    if not rel_path or rel_path.startswith("/") or "\x00" in rel_path:
        raise FailClosed("DG-READ-UNREADABLE", f"invalid rel_path: {rel_path!r}")
    if "\\" in rel_path:
        raise FailClosed("DG-READ-UNREADABLE", f"invalid rel_path: {rel_path!r}")

    segments_raw = rel_path.replace("\\", "/").split("/")
    if any(p == "" for p in segments_raw):
        raise FailClosed("DG-READ-UNREADABLE", f"path contains empty segment: {rel_path!r}")
    if any(p == "." for p in segments_raw):
        raise FailClosed("DG-READ-UNREADABLE", f"path contains dot segment: {rel_path!r}")
    if any(p == ".." for p in segments_raw):
        raise FailClosed("DG-READ-UNREADABLE", f"path contains .. segment: {rel_path!r}")

    parts = [p for p in segments_raw if p]

    # Open repo_root with O_DIRECTORY to verify it is a directory
    try:
        root_fd = os.open(str(repo_root), os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY)
    except OSError as e:
        raise FailClosed("DG-READ-UNREADABLE", str(repo_root)) from e
    try:
        root_stat = os.fstat(root_fd)
        if not stat.S_ISDIR(root_stat.st_mode):
            raise FailClosed("DG-READ-UNREADABLE", str(repo_root))
    except OSError as e:
        os.close(root_fd)
        raise FailClosed("DG-READ-UNREADABLE", str(repo_root)) from e

    parent_fd = root_fd
    try:
        # Traverse all but the last segment as directories
        for part in parts[:-1]:
            try:
                seg_fd = os.open(part, os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY, dir_fd=parent_fd)
            except OSError as e:
                raise FailClosed("DG-READ-UNREADABLE", f"{rel_path!r} at {part!r}") from e
            # Verify it is a directory via fstat on the opened fd
            s = os.fstat(seg_fd)
            if not stat.S_ISDIR(s.st_mode):
                os.close(seg_fd)
                raise FailClosed("DG-READ-UNREADABLE", f"{rel_path!r} at {part!r}")
            os.close(parent_fd)
            parent_fd = seg_fd

        # Open and read the final file
        final_name = parts[-1]
        try:
            final_fd = os.open(final_name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=parent_fd)
        except OSError as e:
            raise FailClosed("DG-READ-UNREADABLE", f"{rel_path!r} at {final_name!r}") from e

        try:
            pre = os.fstat(final_fd)
        except OSError as e:
            os.close(final_fd)
            raise FailClosed("DG-READ-UNREADABLE", f"{rel_path!r} at {final_name!r}") from e
        if not stat.S_ISREG(pre.st_mode):
            os.close(final_fd)
            raise FailClosed("DG-READ-UNREADABLE", f"{rel_path!r} at {final_name!r}")
        if pre.st_size > max_bytes:
            os.close(final_fd)
            raise FailClosed("DG-READ-OVERSIZE", f"{rel_path!r}", pre.st_size)

        dev, ino = pre.st_dev, pre.st_ino
        pre_size, pre_mtime_ns = pre.st_size, pre.st_mtime_ns

        buf = bytearray(pre_size)
        offset = 0
        try:
            while offset < len(buf):
                chunk = os.read(final_fd, len(buf) - offset)
                if not chunk:
                    break
                buf[offset:offset + len(chunk)] = chunk
                offset += len(chunk)
        except OSError as e:
            os.close(final_fd)
            raise FailClosed("DG-READ-UNREADABLE", f"{rel_path!r} at {final_name!r}") from e

        # Incomplete read → stale
        if offset != pre_size:
            os.close(final_fd)
            raise FailClosed("DG-READ-STALE", rel_path)

        try:
            post = os.fstat(final_fd)
        except OSError as e:
            os.close(final_fd)
            raise FailClosed("DG-READ-STALE", rel_path) from e
        if post.st_dev != dev or post.st_ino != ino:
            os.close(final_fd)
            raise FailClosed("DG-READ-STALE", rel_path)
        if post.st_size != pre_size or post.st_mtime_ns != pre_mtime_ns:
            os.close(final_fd)
            raise FailClosed("DG-READ-STALE", rel_path)

        os.close(final_fd)
        return bytes(buf), dev, ino, pre_size, pre_mtime_ns

    finally:
        os.close(parent_fd)


def _coord(coord: dict) -> str:
    try:
        role = coord.get("role")
        if role is not None:
            raise FailClosed("DG-A12-AMBIGUOUS")
        gid = coord["groupId"]
        aid = coord["artifactId"]
        ver = coord["version"]
        if not isinstance(gid, str) or not gid.strip():
            raise FailClosed("DG-A12-AMBIGUOUS")
        if not isinstance(aid, str) or not aid.strip():
            raise FailClosed("DG-A12-AMBIGUOUS")
        if not isinstance(ver, str) or not ver.strip():
            raise FailClosed("DG-A12-AMBIGUOUS")
        return f"{gid}:{aid}:{ver}"
    except (KeyError, TypeError, IndexError):
        raise FailClosed("DG-A12-AMBIGUOUS")


def _artifact_tuple(art: dict) -> tuple[str, str, str]:
    try:
        path_val = art["path"]
        if not isinstance(path_val, str) or not path_val.strip():
            raise FailClosed("DG-A12-AMBIGUOUS")
        # classifier must be absent/null — reject if present with non-null value
        classifier = art.get("classifier")
        if classifier is not None:
            raise FailClosed("DG-A12-AMBIGUOUS")
        return (art["role"], _coord(art["coordinate"]), path_val)
    except (KeyError, TypeError, IndexError):
        raise FailClosed("DG-A12-AMBIGUOUS")


def derive_a12_artifact(authority_data: dict) -> tuple[str, str]:
    slices = authority_data.get("deliverySlices", [])
    a12_slices = [s for s in slices if s.get("sliceId") == "A1.2"]
    if not a12_slices:
        raise FailClosed("DG-A12-MISSING")
    if len(a12_slices) != 1:
        raise FailClosed("DG-A12-AMBIGUOUS")
    record = a12_slices[0]

    handoff_artifacts = record.get("handoffArtifacts", [])
    output_artifacts = record.get("outputArtifacts", [])

    handoff_tcks = [a for a in handoff_artifacts if a.get("role") == "TCK_PROVIDER"]
    output_tcks = [a for a in output_artifacts if a.get("role") == "TCK_PROVIDER"]

    if len(handoff_tcks) != 1 or len(output_tcks) != 1:
        raise FailClosed("DG-A12-AMBIGUOUS")

    h_tup = _artifact_tuple(handoff_tcks[0])
    o_tup = _artifact_tuple(output_tcks[0])

    if h_tup != o_tup:
        raise FailClosed("DG-A12-AMBIGUOUS")

    return h_tup[1], h_tup[2]


# ---------------------------------------------------------------------------
# Authority JSON parsing — object_pairs_hook rejects duplicate keys
# ---------------------------------------------------------------------------

def parse_authority_json(raw: bytes, source_path: str) -> dict:
    """Parse authority JSON; rejects duplicate keys as DG-AUTHORITY-INVALID."""
    try:
        # object_pairs_hook raises on duplicate keys
        return _json.loads(
            raw.decode("utf-8"),
            object_pairs_hook=_json_object_pairs_hook,
        )
    except _json.JSONDecodeError as e:
        raise FailClosed("DG-AUTHORITY-INVALID", source_path, str(e)) from e
    except (UnicodeDecodeError, DuplicateKeyError) as e:
        raise FailClosed("DG-AUTHORITY-INVALID", source_path, str(e)) from e


class DuplicateKeyError(ValueError):
    pass


def _json_object_pairs_hook(pairs):
    seen = {}
    for k, v in pairs:
        if k in seen:
            raise DuplicateKeyError(f"duplicate key: {k!r}")
        seen[k] = v
    return seen


# ---------------------------------------------------------------------------
# Write binding — O_CREAT|O_EXCL|O_WRONLY 0600, partial-write cleanup
# ---------------------------------------------------------------------------

def write_binding(binding_line: bytes, binding_path: pathlib.Path) -> None:
    parent = binding_path.parent
    name = binding_path.name

    # Validate name: no slash, not empty, not ., not ..
    if not name or "/" in name or name == "." or name == "..":
        raise FailClosed("DG-BINDING-WRITE", str(binding_path), "invalid binding filename")

    # Open parent with O_DIRECTORY|O_NOFOLLOW to get a rooted fd
    try:
        parent_fd = os.open(str(parent), os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY)
    except OSError as e:
        raise FailClosed("DG-BINDING-WRITE", str(binding_path), str(e)) from e

    fd = None
    try:
        # Verify parent is a directory via fstat on the same fd
        parent_stat = os.fstat(parent_fd)
        if not stat.S_ISDIR(parent_stat.st_mode):
            raise FailClosed("DG-BINDING-WRITE", str(binding_path), "parent not a directory")

        # Open the binding file via parent dir_fd; O_EXCL ensures no existing file
        try:
            fd = os.open(name, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600, dir_fd=parent_fd)
        except OSError as e:
            if e.errno == errno.EEXIST:
                raise FailClosed("DG-BINDING-DUPLICATE", str(binding_path)) from e
            raise FailClosed("DG-BINDING-WRITE", str(binding_path), str(e)) from e

        written = 0
        while written < len(binding_line):
            got = os.write(fd, binding_line[written:])
            if got == 0:
                raise FailClosed("DG-BINDING-WRITE", str(binding_path), "zero write")
            written += got
        os.fsync(fd)
    except FailClosed:
        if fd is not None:
            try:
                os.close(fd)
            except OSError:
                pass
            try:
                os.unlink(name, dir_fd=parent_fd)
            except OSError:
                pass
        raise
    except OSError as e:
        if fd is not None:
            try:
                os.close(fd)
            except OSError:
                pass
            try:
                os.unlink(name, dir_fd=parent_fd)
            except OSError:
                pass
        raise FailClosed("DG-BINDING-WRITE", str(binding_path), str(e)) from e
    else:
        os.close(fd)
    finally:
        try:
            os.close(parent_fd)
        except OSError:
            pass


# ---------------------------------------------------------------------------
# Argument validation helpers
# ---------------------------------------------------------------------------

def validate_repo_root_arg(raw_path: str) -> pathlib.Path:
    p = pathlib.Path(raw_path)
    if not p.is_absolute():
        raise FailClosed("DG-ARG-MISSING", f"repo-root not absolute: {raw_path}")
    try:
        fd = os.open(str(p), os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY)
    except OSError as e:
        raise FailClosed("DG-ARG-MISSING", f"repo-root unreadable: {raw_path} {e}")
    try:
        st = os.fstat(fd)
        if not stat.S_ISDIR(st.st_mode):
            raise FailClosed("DG-ARG-MISSING", f"repo-root not a directory: {raw_path}")
    finally:
        try:
            os.close(fd)
        except OSError:
            pass
    return p


def validate_binding_path_arg(raw_path: str) -> pathlib.Path:
    p = pathlib.Path(raw_path)
    if not p.is_absolute():
        raise FailClosed("DG-ARG-MISSING", f"binding-path not absolute: {raw_path}")
    parent = p.parent
    try:
        parent_fd = os.open(str(parent), os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY)
    except OSError as e:
        raise FailClosed("DG-ARG-MISSING", f"binding-path parent unreadable: {parent} {e}")
    try:
        parent_st = os.fstat(parent_fd)
        if not stat.S_ISDIR(parent_st.st_mode):
            raise FailClosed("DG-ARG-MISSING", f"binding-path parent not a directory: {parent}")
    finally:
        try:
            os.close(parent_fd)
        except OSError:
            pass
    return p


# ---------------------------------------------------------------------------
# Build binding
# ---------------------------------------------------------------------------

def build_binding(
    authority_path: str,
    repo_root: pathlib.Path,
    binding_path: pathlib.Path,
    skip_maven: bool,
) -> None:
    # Read and parse authority
    try:
        auth_bytes, *_ = stable_read(authority_path, _AUTHORITY_MAX_BYTES)
    except FailClosed as e:
        if e.args[0].startswith("DG-READ-"):
            detail = ":".join(str(a) for a in e.args[1:])
            print(f"DG-AUTHORITY-INVALID:{e.args[0]}:{detail}", file=sys.stderr)
        else:
            print(e.stderr_line(), file=sys.stderr)
        raise SystemExit(2) from e

    try:
        authority_data = parse_authority_json(auth_bytes, authority_path)
    except FailClosed as e:
        print(e.stderr_line(), file=sys.stderr)
        raise SystemExit(2) from e

    if not isinstance(authority_data, dict):
        print(f"DG-AUTHORITY-INVALID:{authority_path}:not a JSON object", file=sys.stderr)
        raise SystemExit(2)
    if "deliverySlices" not in authority_data:
        print(f"DG-AUTHORITY-INVALID:{authority_path}:missing deliverySlices", file=sys.stderr)
        raise SystemExit(2)

    # Derive A1.2 artifact
    try:
        a12_coord, a12_rel_path = derive_a12_artifact(authority_data)
    except FailClosed as e:
        print(e.stderr_line(), file=sys.stderr)
        raise SystemExit(2) from e

    # Read provider via dir-fd traversal
    try:
        provider_bytes, *_ = stable_read_repo_relative(
            repo_root, a12_rel_path, _PROVIDER_MAX_BYTES
        )
    except FailClosed as e:
        print(e.stderr_line(), file=sys.stderr)
        raise SystemExit(2) from e

    auth_fp = sha256_fingerprint(auth_bytes)
    provider_fp = sha256_fingerprint(provider_bytes)

    binding: dict[str, Any] = {
        "messageVersion": _BINDING_MSG_VERSION,
        "authorityRawFingerprint": auth_fp,
        "sourceSliceId": _SOURCE_SLICE_ID,
        "targetSliceId": _TARGET_SLICE_ID,
        "providerArtifact": {
            "coordinate": a12_coord,
            "path": a12_rel_path,
            "byteLength": len(provider_bytes),
            "rawFingerprint": provider_fp,
        },
    }
    binding["bindingFingerprint"] = binding_fingerprint(binding)

    binding_line = canonical_json(binding) + b"\n"

    try:
        write_binding(binding_line, binding_path)
    except FailClosed as e:
        print(e.stderr_line(), file=sys.stderr)
        raise SystemExit(2) from e

    if skip_maven:
        raise SystemExit(0)

    # Maven invocation — bounded sink (no PIPE+communicate)
    maven_cmd = [
        "mvn",
        "-f", "resource-gateway-gate-a-verifier/pom.xml",
        "-Pgate-a-verifier,gate-a-a1-3-development-binding",
        "-Dgate.a.slice=A1.3",
        f"-Dgate.a.binding.path={binding_path}",
        f"-Dgate.a.repo.root={repo_root}",
        "-Dgate.a.testSet=A1_3_ROLE_PACKAGING",
        "clean", "verify",
    ]

    stdout_sink = stderr_sink = None
    proc = None
    try:
        stdout_sink = tempfile.TemporaryFile()
        stderr_sink = tempfile.TemporaryFile()
        try:
            proc = subprocess.Popen(
                maven_cmd,
                cwd=str(repo_root),
                stdout=stdout_sink,
                stderr=stderr_sink,
            )
        except OSError as e:
            raise FailClosed("DG-MAVEN-FAIL", f"cannot spawn Maven:{e}") from e
        proc.wait()
        BOUND = 65536
        stdout_sink.seek(0)
        sys.stdout.buffer.write(stdout_sink.read(BOUND))
        stderr_sink.seek(0)
        sys.stderr.buffer.write(stderr_sink.read(BOUND))
    except FailClosed as e:
        print(e.stderr_line(), file=sys.stderr)
        raise SystemExit(2) from e
    finally:
        if stdout_sink is not None:
            try:
                stdout_sink.close()
            except OSError:
                pass
        if stderr_sink is not None:
            try:
                stderr_sink.close()
            except OSError:
                pass

    if proc.returncode != 0:
        print(f"DG-MAVEN-FAIL exitCode={proc.returncode}", file=sys.stderr)
        raise SystemExit(2)

    raise SystemExit(0)


def main() -> None:
    parser = argparse.ArgumentParser(usage=argparse.SUPPRESS)
    parser.add_argument("--authority", default="")
    parser.add_argument("--repo-root", type=str, default="")
    parser.add_argument("--binding-path", default="")
    parser.add_argument(
        "--skip-maven", action="store_true",
        help="Write binding atomically then exit without invoking Maven.",
    )
    args = parser.parse_args()

    missing = []
    if not args.authority:
        missing.append("--authority")
    if not args.repo_root:
        missing.append("--repo-root")
    if not args.binding_path:
        missing.append("--binding-path")
    if missing:
        print(f"DG-ARG-MISSING:{','.join(missing)}", file=sys.stderr)
        raise SystemExit(2)

    try:
        validated_repo_root = validate_repo_root_arg(args.repo_root)
        validated_binding_path = validate_binding_path_arg(args.binding_path)
    except FailClosed as e:
        print(e.stderr_line(), file=sys.stderr)
        raise SystemExit(2)

    build_binding(
        args.authority,
        validated_repo_root,
        validated_binding_path,
        args.skip_maven,
    )


if __name__ == "__main__":
    main()
