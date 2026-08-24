#!/usr/bin/env python3
"""Fail-closed A1 release admission over one externally pinned Bundle snapshot.

This boundary deliberately does not provide a local-subprocess fallback. Until
the Dependency Authority, toolchain image and hermetic launcher are externally
pinned, a release attempt is unavailable rather than partially successful.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys
from typing import Any


HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from release_authority_bundle import (  # noqa: E402
    BundleError,
    _bundle_contract,
    _target_slice_and_closure,
    strict_json,
    verify_bundle,
)


class ReleaseUnavailable(RuntimeError):
    """A stable fail-closed release precondition failure."""


def require(condition: bool, code: str, detail: str = "") -> None:
    if not condition:
        suffix = f":{detail}" if detail else ""
        raise ReleaseUnavailable(f"{code}{suffix}")


def load_authority(snapshot: Any) -> dict[str, Any]:
    authority = strict_json(
        snapshot.bytes_for("authority/protocol-authority.json"),
        "bundle-authority",
    )
    require(isinstance(authority, dict), "A1_RELEASE_BUNDLE_AUTHORITY_NOT_OBJECT")
    return authority


def verify_release_preconditions(authority: dict[str, Any]) -> None:
    dependency = authority["dependencyAuthority"]
    status = dependency["status"]
    require(status != "REVOKED", "A1_RELEASE_DEPENDENCY_AUTHORITY_REVOKED")
    require(
        status == "ACTIVE",
        "A1_RELEASE_DEPENDENCY_AUTHORITY_NOT_ACTIVE",
        status,
    )


def verify_release_bundle_relation(authority: dict[str, Any], manifest: Any) -> None:
    contract = _bundle_contract(authority)
    require(
        manifest.get("targetSliceId") == contract["releaseTargetSliceId"],
        "A1_RELEASE_TARGET_SLICE_MISMATCH",
    )
    target, _, closure_roles, implementation_roles = _target_slice_and_closure(
        authority,
        manifest.get("targetSliceId"),
    )
    require(target["sliceId"] == "A1.7", "A1_RELEASE_TARGET_SLICE_NOT_A1_7")
    require(
        list(manifest.get("artifactClosureRoles", ())) == closure_roles,
        "A1_RELEASE_ARTIFACT_CLOSURE_RELATION_MISMATCH",
    )
    require(
        list(manifest.get("implementationRoles", ())) == implementation_roles,
        "A1_RELEASE_IMPLEMENTATION_ROLE_RELATION_MISMATCH",
    )

def main() -> int:
    parser = argparse.ArgumentParser(
        description="Admit an A1 release from one externally pinned Release Authority Bundle"
    )
    parser.add_argument("--bundle-root", required=True)
    parser.add_argument("--expected-bundle-root-fingerprint", required=True)
    parser.add_argument("--launcher-observation")
    arguments = parser.parse_args()

    try:
        snapshot = verify_bundle(
            arguments.bundle_root,
            arguments.expected_bundle_root_fingerprint,
        )
        authority = load_authority(snapshot)
        verify_release_bundle_relation(authority, snapshot.manifest)
        verify_release_preconditions(authority)
        require(
            arguments.launcher_observation is not None,
            "A1_RELEASE_HERMETIC_LAUNCHER_OBSERVATION_REQUIRED",
        )
        # A1.0 freezes the admission boundary. A later executable slice may
        # replace this state only with a caller-controlled hermetic launcher.
        raise ReleaseUnavailable("A1_RELEASE_HERMETIC_EXECUTION_ENVELOPE_NOT_AVAILABLE")
    except (BundleError, ReleaseUnavailable, KeyError, TypeError, json.JSONDecodeError) as error:
        print(f"A1_RELEASE_UNAVAILABLE:{error}", file=sys.stderr)
        return 4


if __name__ == "__main__":
    raise SystemExit(main())
