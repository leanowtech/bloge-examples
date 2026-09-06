#!/usr/bin/env python3
"""Capture payload-free server-side BUSINESS_SOLUTION surface evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any, Callable
from urllib.request import Request, urlopen


class SurfaceProbeFailure(RuntimeError):
    """Raised when the server does not enforce both list and call visibility."""


def _exchange(endpoint: str, token: str, request_id: str, method: str,
              params: dict[str, Any], opener: Callable[..., Any]) -> dict[str, Any]:
    body = json.dumps({"jsonrpc": "2.0", "id": request_id, "method": method,
                       "params": params}, separators=(",", ":")).encode()
    request = Request(endpoint, data=body, method="POST", headers={
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
        "X-Purpose": "AGENT_TDD_READ",
        "X-RG-Surface": "BUSINESS_SOLUTION",
    })
    with opener(request, timeout=15) as response:
        value = json.loads(response.read().decode())
    if not isinstance(value, dict):
        raise SurfaceProbeFailure("MCP response is not an object")
    return value


def certify_surface(endpoint: str, token: str, runtime_nonce: str,
                    opener: Callable[..., Any] = urlopen) -> dict[str, Any]:
    """Verify server list filtering and direct-call rejection on one owned runtime."""
    listed = _exchange(endpoint, token, "surface-list", "tools/list", {}, opener)
    tools = listed.get("result", {}).get("tools", [])
    names = [item.get("name") for item in tools if isinstance(item, dict)]
    if "rg.library.overview.get" not in names or "rg.capability.search" not in names:
        raise SurfaceProbeFailure("business read tools are absent from tools/list")
    forbidden = [name for name in names if isinstance(name, str) and (
        name.startswith(("rg.dsl.", "rg.tool.", "rg.fixture."))
        or name in {"rg.feature.compose", "rg.scenario.upsertCases", "rg.simulate"})]
    if forbidden:
        raise SurfaceProbeFailure("tools/list exposed a platform-authoring tool")

    rejected = _exchange(endpoint, token, "surface-call", "tools/call", {
        "name": "rg.dsl.reference.get", "arguments": {"libraryRefs": []}}, opener)
    error = rejected.get("error", {})
    if error.get("code") != -32031 or error.get("message") != "TOOL_NOT_VISIBLE_IN_SURFACE":
        raise SurfaceProbeFailure("direct call did not fail with TOOL_NOT_VISIBLE_IN_SURFACE")

    material = {
        "runtimeInstanceNonce": runtime_nonce,
        "visibleToolNames": names,
        "hiddenTool": "rg.dsl.reference.get",
        "rejectionCode": -32031,
        "rejectionReason": "TOOL_NOT_VISIBLE_IN_SURFACE",
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
