#!/usr/bin/env python3
"""Reduce a private Codex JSONL trace to a payload-free Agent TDD certificate."""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import re
import secrets
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
AUTHORING_TOOLS = {"rg.dsl.preview", "rg.gate.check"}
MAX_PREVIEW_ATTEMPTS = 4
TECHNICAL_FINAL_PATTERN = re.compile(
    r"(?i)\b(?:dsl|schema|binding|operator|toolref|casesetref|fingerprint|mcp|json)\b"
    r"|代码|编译器?|节点|端口|指纹|内部标识"
)
PASSIVE_TRACE_ITEMS = {"agent_message", "reasoning", "todo_list", "error"}


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


def object_value(value: Any) -> dict[str, Any]:
    """Normalize Codex argument encodings without ever placing them in a certificate."""
    if isinstance(value, dict):
        return value
    if isinstance(value, str):
        try:
            decoded = json.loads(value)
        except json.JSONDecodeError:
            return {}
        return decoded if isinstance(decoded, dict) else {}
    return {}


def load_trace(path: Path) -> tuple[list[dict[str, Any]], str, bool]:
    """Extract private correlation material for in-process checks and the last final message."""
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
            event_type = str(event.get("type", ""))
            if event_type.startswith("item.") and isinstance(item, dict):
                item_type = str(item.get("type", ""))
                if item_type != "mcp_tool_call" and item_type not in PASSIVE_TRACE_ITEMS:
                    raise CertificationFailure(
                        f"trace contains a non-MCP action item: {item_type or 'unknown'}")
            if event_type != "item.completed" or not isinstance(item, dict):
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
                "arguments": object_value(item.get("arguments")),
            })
    return calls, final_message, completed


def stage_accepted(call: dict[str, Any], tool: str) -> bool:
    """Require semantic acceptance, not merely a successful JSON-RPC envelope."""
    if not call["successful"]:
        return False
    if tool == "rg.dsl.preview":
        return call["data"].get("accepted") is True
    if tool == "rg.gate.check":
        gate = call["data"].get("rewriteGate")
        return call["data"].get("accepted") is True and isinstance(gate, dict) and gate.get("allowed") is True
    return True


def in_order_successes(calls: list[dict[str, Any]]) -> list[int]:
    """Return positions of the required successful calls or fail at the first missing edge."""
    positions: list[int] = []
    cursor = 0
    for required in REQUIRED_SEQUENCE:
        for index in range(cursor, len(calls)):
            if calls[index]["tool"] == required and stage_accepted(calls[index], required):
                positions.append(index)
                cursor = index + 1
                break
        else:
            raise CertificationFailure(f"required successful tool call is missing or out of order: {required}")
    return positions


def required_text(value: Any, label: str) -> str:
    """Return a non-blank private identity or fail the proof closed."""
    if not isinstance(value, str) or not value.strip():
        raise CertificationFailure(f"{label} is missing")
    return value.strip()


def canonical(value: Any) -> str:
    """Canonicalize private request fragments only for equality checks."""
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def dsl_text(value: Any, label: str) -> str:
    """Normalize the MCP string/envelope alternatives to the candidate source itself."""
    if isinstance(value, str):
        return required_text(value, label)
    if isinstance(value, dict):
        return required_text(value.get("dsl"), label)
    raise CertificationFailure(f"{label} is missing")


def blocking_fingerprints(call: dict[str, Any]) -> tuple[str, ...]:
    """Extract the stable blocking set from a rejected authoring receipt."""
    if call["tool"] not in AUTHORING_TOOLS or not call["successful"] or call["data"].get("accepted") is True:
        return ()
    diagnostics = call["data"].get("authoringDiagnostics")
    if not isinstance(diagnostics, list):
        raise CertificationFailure("a rejected authoring receipt omitted diagnostics")
    fingerprints = sorted({
        diagnostic.get("diagnosticFingerprint")
        for diagnostic in diagnostics
        if isinstance(diagnostic, dict) and diagnostic.get("blocking") is True
        and isinstance(diagnostic.get("diagnosticFingerprint"), str)
        and diagnostic["diagnosticFingerprint"].startswith("sha256:")
    })
    if not fingerprints:
        raise CertificationFailure("a rejected authoring receipt omitted blocking fingerprints")
    return tuple(fingerprints)


def enforce_bounded_repair(calls: list[dict[str, Any]]) -> None:
    """Enforce the documented three-repair and repeated-blocker stop protocol."""
    previews = [index for index, call in enumerate(calls) if call["tool"] == "rg.dsl.preview"]
    if len(previews) > MAX_PREVIEW_ATTEMPTS:
        raise CertificationFailure("Codex exceeded the three-round DSL repair limit")
    previous: tuple[str, ...] = ()
    for index, call in enumerate(calls):
        current = blocking_fingerprints(call)
        if not current:
            if call["tool"] in AUTHORING_TOOLS and call["successful"] and call["data"].get("accepted") is True:
                previous = ()
            continue
        if previous == current:
            if any(later["tool"] in AUTHORING_TOOLS for later in calls[index + 1:]):
                raise CertificationFailure("Codex continued after the same blocking diagnostics repeated twice")
        previous = current


def follows_blocking_authoring_rejection_with_acceptance(calls: list[dict[str, Any]]) -> bool:
    """Prove that compiler feedback for one authoring tool led to an accepted retry."""
    rejected_at: dict[str, int] = {}
    for index, call in enumerate(calls):
        blockers = blocking_fingerprints(call)
        if blockers:
            rejected_at.setdefault(call["tool"], index)
        elif call["tool"] in AUTHORING_TOOLS and stage_accepted(call, call["tool"]) \
                and call["tool"] in rejected_at and rejected_at[call["tool"]] < index:
            return True
    return False


def correlate_authoring_chain(calls: list[dict[str, Any]], positions: list[int]) -> dict[str, Any]:
    """Bind reference, accepted source, gate, Tool, CaseSet and cases into one candidate chain."""
    selected = {tool: calls[position] for tool, position in zip(REQUIRED_SEQUENCE, positions, strict=True)}
    reference = selected["rg.dsl.reference.get"]
    preview = selected["rg.dsl.preview"]
    gate = selected["rg.gate.check"]
    compose = selected["rg.tool.compose"]
    instruction = selected["rg.tool.setInstruction"]
    upsert = selected["rg.scenario.upsertCases"]
    upsert_position = positions[REQUIRED_SEQUENCE.index("rg.scenario.upsertCases")]

    context = required_text(reference["data"].get("authoringContextFingerprint"), "reference context")
    preview_context = required_text(preview["arguments"].get("authoringContextFingerprint"), "preview context")
    gate_context = required_text(gate["arguments"].get("authoringContextFingerprint"), "gate context")
    receipt = required_text(gate["data"].get("authoringReceiptFingerprint"), "gate receipt")
    preview_receipt = required_text(preview["data"].get("authoringReceiptFingerprint"), "preview receipt")
    source = dsl_text(preview["arguments"].get("source"), "preview DSL")
    gate_source = dsl_text(gate["arguments"].get("source"), "gate DSL")
    graph = compose["arguments"].get("graph")
    if not isinstance(graph, dict):
        raise CertificationFailure("the accepted DSL candidate is missing from a private correlation edge")
    compose_source = dsl_text(graph, "compose DSL")
    if not (context == preview_context == gate_context
            == required_text(compose["arguments"].get("authoringContextFingerprint"), "compose context")
            == required_text(compose["data"].get("authoringContextFingerprint"), "stored context")):
        raise CertificationFailure("reference, preview, gate and compose contexts do not match")
    if not (preview_receipt == receipt
            == required_text(compose["arguments"].get("authoringReceiptFingerprint"), "compose receipt")
            == required_text(compose["data"].get("authoringReceiptFingerprint"), "stored receipt")):
        raise CertificationFailure("preview, gate and compose receipts do not match")
    if source != gate_source or source != compose_source:
        raise CertificationFailure("preview, gate and compose did not use the same DSL candidate")
    if canonical(preview["arguments"].get("libraryRefs", [])) != canonical(gate["arguments"].get("libraryRefs", [])) \
            or canonical(gate["arguments"].get("libraryRefs", [])) != canonical(compose["arguments"].get("libraryRefs", [])):
        raise CertificationFailure("preview, gate and compose library refs do not match")

    tool_ref = required_text(compose["data"].get("assetRef"), "composed Tool")
    if not (tool_ref == required_text(compose["arguments"].get("toolRef"), "requested Tool")
            == required_text(instruction["arguments"].get("toolRef"), "instruction Tool")
            == required_text(instruction["data"].get("toolRef"), "stored instruction Tool")
            == required_text(upsert["arguments"].get("toolRef"), "case-set Tool")):
        raise CertificationFailure("compose, instruction and case set are not bound to one Tool")

    case_set_ref = required_text(upsert["arguments"].get("caseSetRef"), "requested CaseSet")
    if case_set_ref != required_text(upsert["data"].get("caseSetRef"), "stored CaseSet"):
        raise CertificationFailure("requested and stored CaseSet identities do not match")
    rows = upsert["data"].get("rows")
    if not isinstance(rows, list):
        raise CertificationFailure("stored CaseSet rows are missing")
    case_ids = sorted({required_text(row.get("caseId"), "stored case")
                       for row in rows if isinstance(row, dict)})
    if not case_ids:
        raise CertificationFailure("stored CaseSet has no cases")

    matching_lists = [call for index, call in enumerate(calls) if index > upsert_position
                      and call["tool"] == "rg.scenario.listCases" and call["successful"]
                      and call["arguments"].get("caseSetRef") == case_set_ref
                      and call["data"].get("caseSetRef") == case_set_ref
                      and call["data"].get("toolRef") == tool_ref]
    if not matching_lists:
        raise CertificationFailure("the stored CaseSet was not read back from the composed Tool")
    listed_rows = matching_lists[-1]["data"].get("rows")
    listed_by_id = {row.get("caseId"): row for row in listed_rows if isinstance(row, dict)} \
        if isinstance(listed_rows, list) else {}
    if not set(case_ids).issubset(listed_by_id):
        raise CertificationFailure("the stored cases were not read back from the same CaseSet")

    dependency_cases = {
        call["arguments"].get("caseId")
        for index, call in enumerate(calls)
        if index > upsert_position
        and call["tool"] == "rg.scenario.setDependencyBehavior" and call["successful"]
        and call["arguments"].get("caseSetRef") == case_set_ref
        and call["data"].get("caseSetRef") == case_set_ref
        and call["arguments"].get("caseId") == call["data"].get("caseId")
    }
    cases_with_stubs = {
        case_id for case_id, row in listed_by_id.items()
        if isinstance(row.get("stubs"), dict) and bool(row["stubs"])
    }
    if not (set(case_ids) & (dependency_cases | cases_with_stubs)):
        raise CertificationFailure("the correlated standard case has no governed dependency behavior")
    cases_with_oracle = {
        case_id for case_id, row in listed_by_id.items()
        if isinstance(row.get("proposedOracle"), dict) and bool(row["proposedOracle"])
    }
    cases_with_oracle.update({
        call["arguments"].get("caseId")
        for index, call in enumerate(calls)
        if index > upsert_position
        and call["tool"] == "rg.oracle.propose" and call["successful"]
        and call["arguments"].get("caseSetRef") == case_set_ref
        and call["data"].get("caseSetRef") == case_set_ref
        and call["arguments"].get("caseId") == call["data"].get("caseId")
    })
    correlated_cases = sorted(set(case_ids) & (dependency_cases | cases_with_stubs) & cases_with_oracle)
    if not correlated_cases:
        raise CertificationFailure("no single correlated standard case has both a stub and Oracle proposal")

    key = secrets.token_bytes(32)
    opaque = lambda label, value: "hmac-sha256:" + hmac.new(  # noqa: E731
        key, f"{label}\0{value}".encode("utf-8"), hashlib.sha256).hexdigest()
    return {
        "method": "EPHEMERAL_HMAC_SHA256",
        "candidate": opaque("candidate", source),
        "authoringContext": opaque("context", context),
        "authoringReceipt": opaque("receipt", receipt),
        "tool": opaque("tool", tool_ref),
        "caseSet": opaque("case-set", case_set_ref),
        "cases": [opaque("case", case_id) for case_id in correlated_cases],
    }


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
    enforce_bounded_repair(calls)
    forbidden = sorted({call["tool"] for call in calls if call["tool"] in FORBIDDEN_TOOLS})
    if forbidden or any(call["server"] in {"rg_execute", "rg_govern"} for call in calls):
        raise CertificationFailure("Codex crossed the required human approval boundary")
    correlation = correlate_authoring_chain(calls, positions)
    if not final_message.strip():
        raise CertificationFailure("Codex produced no final business summary")
    if TECHNICAL_FINAL_PATTERN.search(final_message):
        raise CertificationFailure("Codex final summary exposed technical implementation vocabulary")

    self_repair = follows_blocking_authoring_rejection_with_acceptance(calls)
    preview_calls = [call for call in calls if call["tool"] == "rg.dsl.preview"]
    first_pass = bool(preview_calls) and stage_accepted(preview_calls[0], "rg.dsl.preview")
    if not (self_repair or first_pass):
        raise CertificationFailure("Codex neither repaired a rejected preview nor passed its first preview")
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
        "correlation": correlation,
        "assertions": {
            "codexTurnCompleted": True,
            "onlyMcpExternalActionsObserved": True,
            "requiredAuthoringOrder": True,
            "caseSetBoundToTool": True,
            "dependencyBehaviorDefined": True,
            "businessOracleProposed": True,
            "stoppedBeforeExecutionGovernanceAndPublication": True,
            "finalSummaryBusinessOnly": True,
            "selfRepairObserved": self_repair,
            "firstPassAccepted": first_pass,
            "selfRepairOrFirstPassAccepted": True,
            "boundedRepairPolicyRespected": True,
            "sameCandidateReceiptAndAssets": True,
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
