#!/usr/bin/env python3
"""Reduce a private Codex JSONL trace to a payload-free Agent TDD certificate."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


REQUIRED_SEQUENCE = (
    "rg.capability.list",
    "rg.contract.get",
    "rg.dsl.reference.get",
    "rg.dsl.preview",
    "rg.gate.check",
    "rg.tool.compose",
    "rg.tool.setInstruction",
    "rg.scenario.upsertCases",
)
FORBIDDEN_TOOLS = {
    "rg.simulate",
    "rg.feature.rehearse",
    "rg.tool.baseline",
    "rg.fixture.promote",
    "rg.fixture.provide",
    "rg.tool.publish",
}
TECHNICAL_FINAL_PATTERN = re.compile(
    r"(?i)\b(?:dsl|schema|binding|operator|toolref|casesetref|fingerprint|mcp|json)\b"
    r"|代码|编译器?|节点|端口|指纹|内部标识"
)


class CertificationFailure(RuntimeError):
    """Raised when the private trace does not prove the required product journey."""


def structured_result(item: dict[str, Any]) -> dict[str, Any]:
    """Read the structured MCP result without copying text content into the certificate."""
    result = item.get("result")
    if not isinstance(result, dict):
        return {}
    structured = result.get("structured_content", result.get("structuredContent"))
    if isinstance(structured, dict):
        return structured
    for content in result.get("content", []):
        if isinstance(content, dict) and isinstance(content.get("text"), str):
            try:
                parsed = json.loads(content["text"])
            except json.JSONDecodeError:
                continue
            if isinstance(parsed, dict):
                return parsed
    return {}


def load_trace(path: Path) -> tuple[list[dict[str, Any]], str, bool]:
    """Extract only tool identities/statuses and the last final message from JSONL."""
    calls: list[dict[str, Any]] = []
    final_message = ""
    completed = False
    with path.open("r", encoding="utf-8") as source:
        for line_number, line in enumerate(source, start=1):
            try:
                event = json.loads(line)
            except json.JSONDecodeError as failure:
                raise CertificationFailure(f"trace line {line_number} is not JSON") from failure
            if event.get("type") == "turn.completed":
                completed = True
            item = event.get("item")
            if event.get("type") != "item.completed" or not isinstance(item, dict):
                continue
            if item.get("type") == "agent_message" and isinstance(item.get("text"), str):
                final_message = item["text"]
            if item.get("type") != "mcp_tool_call":
                continue
            structured = structured_result(item)
            calls.append({
                "server": str(item.get("server", "")),
                "tool": str(item.get("tool", "")),
                "status": str(item.get("status", "unknown")),
                "successful": item.get("status") == "completed" and structured.get("ok") is True,
                "data": structured.get("data") if isinstance(structured.get("data"), dict) else {},
            })
    return calls, final_message, completed


def in_order_successes(calls: list[dict[str, Any]]) -> list[int]:
    """Return positions of the required successful calls or fail at the first missing edge."""
    positions: list[int] = []
    cursor = 0
    for required in REQUIRED_SEQUENCE:
        for index in range(cursor, len(calls)):
            if calls[index]["tool"] == required and calls[index]["successful"]:
                positions.append(index)
                cursor = index + 1
                break
        else:
            raise CertificationFailure(f"required successful tool call is missing or out of order: {required}")
    return positions


def certify(trace: Path, metadata: dict[str, Any]) -> dict[str, Any]:
    """Build a bounded certificate after proving authoring order and the human stop."""
    calls, final_message, turn_completed = load_trace(trace)
    if len(calls) > 64:
        raise CertificationFailure("Codex emitted more than 64 MCP calls")
    if not turn_completed:
        raise CertificationFailure("Codex turn did not complete")
    if metadata["exitCode"] != 0:
        raise CertificationFailure("Codex process did not exit successfully")
    positions = in_order_successes(calls)
    forbidden = sorted({call["tool"] for call in calls if call["tool"] in FORBIDDEN_TOOLS})
    if forbidden or any(call["server"] in {"rg_execute", "rg_govern"} for call in calls):
        raise CertificationFailure("Codex crossed the required human approval boundary")
    case_bound = any(
        call["successful"]
        and call["tool"] in {"rg.scenario.upsertCases", "rg.scenario.listCases"}
        and isinstance(call["data"].get("toolRef"), str)
        and bool(call["data"]["toolRef"].strip())
        for call in calls
    )
    if not case_bound:
        raise CertificationFailure("the proposed case set is not bound to a Tool")
    dependency_behavior = any(
        call["successful"] and (
            call["tool"] == "rg.scenario.setDependencyBehavior"
            or (
                call["tool"] in {"rg.scenario.upsertCases", "rg.scenario.listCases"}
                and isinstance(call["data"].get("rows"), list)
                and any(isinstance(row, dict) and isinstance(row.get("stubs"), dict) and bool(row["stubs"])
                        for row in call["data"]["rows"])
            )
        )
        for call in calls
    )
    if not dependency_behavior:
        raise CertificationFailure("the standard case has no governed dependency behavior")
    oracle_proposed = any(
        call["successful"] and (
            call["tool"] == "rg.oracle.propose"
            or (
                call["tool"] in {"rg.scenario.upsertCases", "rg.scenario.listCases"}
                and isinstance(call["data"].get("rows"), list)
                and any(isinstance(row, dict)
                        and isinstance(row.get("proposedOracle"), dict)
                        and bool(row["proposedOracle"])
                        for row in call["data"]["rows"])
            )
        )
        for call in calls
    )
    if not oracle_proposed:
        raise CertificationFailure("the standard case has no pending business Oracle proposal")
    if not final_message.strip():
        raise CertificationFailure("Codex produced no final business summary")
    if TECHNICAL_FINAL_PATTERN.search(final_message):
        raise CertificationFailure("Codex final summary exposed technical implementation vocabulary")

    observed_statuses: dict[str, set[str]] = {}
    for call in calls:
        observed_statuses.setdefault(call["tool"], set()).add(call["status"])
    self_repair = any("failed" in statuses and "completed" in statuses
                      for statuses in observed_statuses.values())
    safe_calls = [
        {"ordinal": index + 1, "server": call["server"], "tool": call["tool"], "status": call["status"]}
        for index, call in enumerate(calls)
    ]
    certificate: dict[str, Any] = {
        "schemaVersion": "rg.agentTddCodexCertification.v1",
        "certifiedAt": metadata["certifiedAt"],
        "repositoryCommit": metadata["repositoryCommit"],
        "codexVersion": metadata["codexVersion"],
        "transport": {
            "kind": "HTTP_MCP",
            "endpointClass": "LOOPBACK",
            "serverCount": 4,
        },
        "journey": {
            "phase": "AUTHORING_TO_HUMAN_GOLDEN_REVIEW",
            "requiredSequence": list(REQUIRED_SEQUENCE),
            "successfulPositions": [position + 1 for position in positions],
            "observedCalls": safe_calls,
        },
        "assertions": {
            "codexTurnCompleted": True,
            "requiredAuthoringOrder": True,
            "caseSetBoundToTool": True,
            "dependencyBehaviorDefined": True,
            "businessOracleProposed": True,
            "stoppedBeforeExecutionGovernanceAndPublication": True,
            "finalSummaryBusinessOnly": True,
            "selfRepairObserved": self_repair,
            "rawArgumentsResultsAndMessagesOmitted": True,
        },
        "result": "CERTIFIED",
    }
    canonical = json.dumps(certificate, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    certificate["certificateFingerprint"] = "sha256:" + hashlib.sha256(canonical.encode("utf-8")).hexdigest()
    return certificate


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("trace", type=Path)
    parser.add_argument("--repository-commit", required=True)
    parser.add_argument("--codex-version", required=True)
    parser.add_argument("--certified-at", default=datetime.now(timezone.utc).isoformat())
    parser.add_argument("--exit-code", required=True, type=int)
    arguments = parser.parse_args()
    try:
        certificate = certify(arguments.trace, {
            "repositoryCommit": arguments.repository_commit,
            "codexVersion": arguments.codex_version,
            "certifiedAt": arguments.certified_at,
            "exitCode": arguments.exit_code,
        })
    except (CertificationFailure, OSError) as failure:
        print(f"Certification failed: {failure}", file=sys.stderr)
        return 1
    json.dump(certificate, sys.stdout, ensure_ascii=False, indent=2)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
