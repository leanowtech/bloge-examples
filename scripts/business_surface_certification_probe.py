#!/usr/bin/env python3
"""Capture payload-free server-side BUSINESS_SOLUTION surface evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any, Callable
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from business_solution_codex_trace_certificate import AUTHORING_TOOLS, READ_TOOLS


class SurfaceProbeFailure(RuntimeError):
    """Raised when the server does not enforce both list and call visibility."""


BUSINESS_SURFACE_TOOLS = AUTHORING_TOOLS | READ_TOOLS


def _exchange(endpoint: str, token: str, purpose: str, request_id: str, method: str,
              params: dict[str, Any], opener: Callable[..., Any]) -> dict[str, Any]:
    body = json.dumps({"jsonrpc": "2.0", "id": request_id, "method": method,
                       "params": params}, separators=(",", ":")).encode()
    request = Request(endpoint, data=body, method="POST", headers={
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
        "X-Purpose": purpose,
        "X-RG-Surface": "BUSINESS_SOLUTION",
    })
    try:
        with opener(request, timeout=15) as response:
            payload = response.read()
    except HTTPError as failure:
        try:
            payload = failure.read()
        finally:
            failure.close()
    value = json.loads(payload.decode())
    if not isinstance(value, dict):
        raise SurfaceProbeFailure("MCP response is not an object")
    return value


def certify_surface(endpoint: str, token: str, runtime_nonce: str,
                    opener: Callable[..., Any] = urlopen) -> dict[str, Any]:
    """Verify READ and AUTHORING list/call filtering on one owned runtime."""
    proofs: dict[str, dict[str, Any]] = {}
    configurations = (
        ("read", "AGENT_TDD_READ", {"rg.library.overview.get", "rg.capability.search"},
         "rg.dsl.reference.get", {"libraryRefs": []}),
        ("authoring", "AGENT_TDD_AUTHORING", {"rg.journey.start", "rg.feature.define"},
         "rg.library.upsert", {"libraryYaml": "library: hidden-surface-probe",
                               "idempotencyKey": "surface-hidden-probe"}),
    )
    for label, purpose, required, hidden_tool, arguments in configurations:
        listed = _exchange(endpoint, token, purpose, f"surface-{label}-list", "tools/list", {}, opener)
        tools = listed.get("result", {}).get("tools", [])
        names = [item.get("name") for item in tools if isinstance(item, dict)]
        if not required.issubset(set(names)):
            raise SurfaceProbeFailure(f"business {label} tools are absent from tools/list")
        if any(not isinstance(name, str) or name not in BUSINESS_SURFACE_TOOLS for name in names):
            raise SurfaceProbeFailure(f"{label} tools/list exposed a non-business tool")
        rejected = _exchange(endpoint, token, purpose, f"surface-{label}-call", "tools/call", {
            "name": hidden_tool, "arguments": arguments}, opener)
        error = rejected.get("error", {})
        if error.get("code") != -32031 or error.get("message") != "TOOL_NOT_VISIBLE_IN_SURFACE":
            raise SurfaceProbeFailure(
                f"{label} direct call did not fail with TOOL_NOT_VISIBLE_IN_SURFACE")
        proofs[label] = {"purpose": purpose, "visibleToolNames": names,
                         "hiddenTool": hidden_tool, "rejectionCode": -32031,
                         "rejectionReason": "TOOL_NOT_VISIBLE_IN_SURFACE"}

    material = {
        "runtimeInstanceNonce": runtime_nonce,
        "purposeProofs": proofs,
    }
    digest = hashlib.sha256(json.dumps(material, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    return {"schemaVersion": "rg.businessSurfaceProof.v1", **material,
            "proofFingerprint": f"sha256:{digest}"}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoint", required=True)
    parser.add_argument("--token", required=True)
    parser.add_argument("--runtime-instance-nonce", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = certify_surface(args.endpoint, args.token, args.runtime_instance_nonce)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
