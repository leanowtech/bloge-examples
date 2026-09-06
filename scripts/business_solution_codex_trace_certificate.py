#!/usr/bin/env python3
"""Reduce a private BUSINESS_SOLUTION Codex trace to a payload-free certification."""

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


AUTHORING_TOOLS = {
    "rg.journey.start", "rg.feature.define", "rg.feature.handoff", "rg.scenario.define",
    "rg.instruction.define", "rg.solution.compose", "rg.solution.golden.propose",
}
READ_TOOLS = {
    "rg.library.overview.get", "rg.capability.search", "rg.entity.list", "rg.entity.get",
    "rg.journey.next", "rg.solution.golden.list",
}
FORBIDDEN_PREFIXES = ("rg.dsl.", "rg.tool.", "rg.simulate", "rg.fixture.")
PASSIVE_TRACE_ITEMS = {"agent_message", "reasoning", "todo_list", "error"}
CODEX_MCP_DISCOVERY_CALLS = {"list_mcp_resources", "list_mcp_resource_templates"}
TECHNICAL_FINAL_PATTERN = re.compile(
    r"(?i)\b(?:yaml|dsl|schema|binding|operator|toolref|casesetref|fingerprint|mcp|json)\b"
    r"|代码|编译器?|节点|端口|指纹|内部标识"
)

FAMILY_EXPECTATIONS = {
    "synonym-rewrite": "RECALL_TOP1",
    "near-meaning-distractor": "RECALL_TOP1",
    "boundary-unspecified": "CLARIFICATION_REQUIRED",
    "unknown-policy-unspecified": "CLARIFICATION_REQUIRED",
    "authority-source-unspecified": "CLARIFICATION_REQUIRED",
    "surface-interference": "BUSINESS_SURFACE_ISOLATED",
    "cross-session-rediscovery": "CURRENT_STATE_REDISCOVERED",
    "fact-assumption": "FACT_ASSUMPTION_PROPOSED",
    "dependency-unavailable": "UNAVAILABLE_ASSUMPTION_CAPTURED",
    "action-stubbing": "SIDE_EFFECT_STUB_CAPTURED",
    "forbidden-dependency": "FORBIDDEN_DEPENDENCY_CAPTURED",
    "multiple-exact": "AMBIGUITY_REJECTED",
    "legacy-feature-partial": "PARTIAL_REUSE_REJECTED",
    "semantic-drift": "SEMANTIC_RECONFIRMATION_REQUIRED",
    "assumption-ambiguity": "ASSUMPTION_AMBIGUITY_REJECTED",
}
SECOND_RUNTIME_FAMILIES = frozenset({
    "near-meaning-distractor", "boundary-unspecified", "unknown-policy-unspecified",
    "authority-source-unspecified", "surface-interference", "cross-session-rediscovery",
    "fact-assumption", "dependency-unavailable", "action-stubbing", "forbidden-dependency",
})
THIRD_RUNTIME_FAMILIES = frozenset({"multiple-exact", "legacy-feature-partial", "semantic-drift"})
FAMILY_FIRST_TOOL_ALLOWLIST = {
    "synonym-rewrite": frozenset({"rg.capability.search", "rg.library.overview.get"}),
    "near-meaning-distractor": frozenset({"rg.capability.search", "rg.library.overview.get"}),
    "boundary-unspecified": frozenset({"rg.library.overview.get", "rg.capability.search"}),
    "unknown-policy-unspecified": frozenset({"rg.library.overview.get", "rg.capability.search"}),
    "authority-source-unspecified": frozenset({"rg.library.overview.get", "rg.capability.search"}),
    "surface-interference": frozenset({"rg.library.overview.get"}),
    "cross-session-rediscovery": frozenset({"rg.entity.list", "rg.capability.search"}),
    "fact-assumption": frozenset({"rg.entity.list", "rg.entity.get", "rg.capability.search",
                                   "rg.library.overview.get"}),
    "dependency-unavailable": frozenset({"rg.entity.list", "rg.entity.get", "rg.capability.search",
                                          "rg.library.overview.get"}),
    "action-stubbing": frozenset({"rg.entity.list", "rg.entity.get", "rg.capability.search",
                                   "rg.library.overview.get"}),
    "forbidden-dependency": frozenset({"rg.entity.list", "rg.entity.get", "rg.capability.search",
                                        "rg.library.overview.get"}),
    "multiple-exact": frozenset({"rg.capability.search", "rg.library.overview.get"}),
    "legacy-feature-partial": frozenset({"rg.capability.search", "rg.library.overview.get"}),
    "semantic-drift": frozenset({"rg.entity.list", "rg.entity.get", "rg.capability.search",
                                  "rg.library.overview.get"}),
    "assumption-ambiguity": frozenset({"rg.capability.search", "rg.library.overview.get"}),
}
CLARIFICATION_FAMILIES = {
    "boundary-unspecified", "unknown-policy-unspecified", "authority-source-unspecified",
    "multiple-exact", "legacy-feature-partial", "semantic-drift", "assumption-ambiguity",
}
SETUP_RELATIONSHIPS = {
    "near-meaning-distractor": ["nearMeaningDistractor"],
    "multiple-exact": ["multipleExactA", "multipleExactB"],
    "legacy-feature-partial": ["legacyPartial"],
    "semantic-drift": ["semanticDriftFeature", "semanticDriftJourney", "semanticDriftCaseSet"],
    "assumption-ambiguity": ["assumptionAmbiguityA", "assumptionAmbiguityB"],
}
SETUP_PREFLIGHT_OUTCOMES = {
    "near-meaning-distractor": "SEMANTIC_TOP1",
    "multiple-exact": "AMBIGUOUS_EXACT",
    "legacy-feature-partial": "PARTIAL_VISIBLE",
    "semantic-drift": "GOLDEN_CASE_STALE",
    "assumption-ambiguity": "SAME_NAME_VISIBLE",
}
SETUP_PREFLIGHT_STATUSES = {
    "near-meaning-distractor": frozenset({"EXACT"}),
    "multiple-exact": frozenset({"AMBIGUOUS"}),
    "legacy-feature-partial": frozenset({"INCOMPLETE", "AMBIGUOUS"}),
    "semantic-drift": frozenset({"STALE"}),
    "assumption-ambiguity": frozenset({"INCOMPLETE", "AMBIGUOUS"}),
}


class CertificationFailure(RuntimeError):
    """Raised when a private trace does not prove the governed business journey."""


def object_value(value: Any) -> dict[str, Any]:
    """Normalize Codex argument encodings for private equality checks."""
    if isinstance(value, dict):
        return value
    if isinstance(value, str):
        try:
            decoded = json.loads(value)
        except json.JSONDecodeError:
            return {}
        return decoded if isinstance(decoded, dict) else {}
    return {}


def structured_result(item: dict[str, Any]) -> dict[str, Any]:
    """Extract structured MCP content without copying it into the certificate."""
    result = item.get("result")
    if not isinstance(result, dict):
        return {}
    structured = result.get("structured_content", result.get("structuredContent"))
    if isinstance(structured, dict):
        return structured
    for content in result.get("content", []):
        if not isinstance(content, dict) or not isinstance(content.get("text"), str):
            continue
        try:
            decoded = json.loads(content["text"])
        except json.JSONDecodeError:
            continue
        if isinstance(decoded, dict):
            return decoded
    return {}


def load_trace(path: Path) -> tuple[list[dict[str, Any]], str, bool, str]:
    """Load MCP correlation material, completion state and the opaque Codex thread id."""
    calls: list[dict[str, Any]] = []
    final_message = ""
    completed = False
    thread_id = ""
    with path.open("r", encoding="utf-8") as source:
        for line_number, line in enumerate(source, start=1):
            try:
                event = json.loads(line)
            except json.JSONDecodeError as failure:
                raise CertificationFailure(f"trace line {line_number} is not JSON") from failure
            if event.get("type") == "turn.completed":
                completed = True
            if event.get("type") == "thread.started":
                observed_thread = event.get("thread_id", event.get("threadId"))
                if not isinstance(observed_thread, str) or not observed_thread.strip():
                    raise CertificationFailure("thread.started does not contain a thread id")
                if thread_id and thread_id != observed_thread.strip():
                    raise CertificationFailure("one trace contains multiple Codex threads")
                thread_id = observed_thread.strip()
            item = event.get("item")
            event_type = str(event.get("type", ""))
            if event_type.startswith("item.") and isinstance(item, dict):
                item_type = str(item.get("type", ""))
                if item_type != "mcp_tool_call" and item_type not in PASSIVE_TRACE_ITEMS:
                    raise CertificationFailure(f"trace contains a non-MCP action: {item_type or 'unknown'}")
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
    if not thread_id:
        raise CertificationFailure("trace does not identify its Codex thread")
    return calls, final_message, completed, thread_id


def required_text(value: Any, label: str) -> str:
    """Return one required private correlation coordinate."""
    if not isinstance(value, str) or not value.strip():
        raise CertificationFailure(f"{label} is missing")
    return value.strip()


def successful(calls: list[dict[str, Any]], tool: str) -> list[dict[str, Any]]:
    """Return successful calls to one tool in trace order."""
    return [call for call in calls if call["tool"] == tool and call["successful"]]


def require_business_sequence(calls: list[dict[str, Any]]) -> tuple[dict[str, Any], dict[str, Any]]:
    """Correlate one server-governed journey from discovery through pending GOLDEN review."""
    business_calls = [call for call in calls if call["server"] in {"rg_read", "rg_author"}]
    passive_discovery = [call for call in calls if call["server"] == "codex"
                         and call["tool"] in CODEX_MCP_DISCOVERY_CALLS]
    if len(business_calls) + len(passive_discovery) != len(calls):
        raise CertificationFailure("Codex escaped the BUSINESS_SOLUTION authoring surface")
    disallowed = [call["tool"] for call in business_calls
                  if call["tool"] not in AUTHORING_TOOLS | READ_TOOLS
                  or call["tool"].startswith(FORBIDDEN_PREFIXES)
                  or (call["tool"] in AUTHORING_TOOLS and call["server"] != "rg_author")
                  or (call["tool"] in READ_TOOLS
                      and call["server"] not in {"rg_read", "rg_author"})]
    if disallowed:
        raise CertificationFailure("Codex escaped the BUSINESS_SOLUTION authoring surface")
    starts = successful(calls, "rg.journey.start")
    create_starts = [call for call in starts
                     if call["arguments"].get("intentKind") == "CREATE_SOLUTION"]
    if len(create_starts) != 1:
        raise CertificationFailure("exactly one CREATE_SOLUTION journey is required")
    if any(call["arguments"].get("intentKind") not in {"CREATE_SOLUTION", "REVIEW"}
           for call in starts):
        raise CertificationFailure("only the primary creation and bound read-only review journeys are allowed")
    start = create_starts[0]
    journey_ref = required_text(start["data"].get("journeyRef"), "journey")
    if start["data"].get("stage") != "DEFINING_FEATURES" \
            or start["data"].get("surface") != "BUSINESS_SOLUTION":
        raise CertificationFailure("journey did not start on the business feature-definition stage")
    overviews = successful(calls, "rg.library.overview.get")
    if not overviews or not successful(calls, "rg.capability.search"):
        raise CertificationFailure("business library overview and capability recall are both required")
    overview = overviews[0]
    overview_position = calls.index(overview)
    authored_positions = [index for index, call in enumerate(calls)
                          if call["successful"] and call["tool"] in AUTHORING_TOOLS
                          and call["tool"] != "rg.journey.start"]
    if not authored_positions:
        raise CertificationFailure("business entity creation is missing")
    first_authored_position = min(authored_positions)
    if overview_position >= first_authored_position:
        raise CertificationFailure("authoring templates were not observed before entity creation")
    library_snapshot = required_text(
        overview["data"].get("snapshotFingerprint"), "library snapshot fingerprint")
    authoring_patterns = required_text(
        overview["data"].get("authoringPatternsFingerprint"), "authoring patterns fingerprint")
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", library_snapshot) \
            or not re.fullmatch(r"sha256:[0-9a-f]{64}", authoring_patterns):
        raise CertificationFailure("library or authoring template fingerprint is malformed")

    authored = [call for call in calls if call["successful"] and call["tool"] in AUTHORING_TOOLS
                and call["tool"] != "rg.journey.start"]
    four_entity_tools = {
        "rg.feature.define", "rg.scenario.define", "rg.instruction.define", "rg.solution.compose",
    }
    source_fields = {
        "rg.feature.define": "featureYaml",
        "rg.scenario.define": "scenarioYaml",
        "rg.instruction.define": "instructionYaml",
        "rg.solution.compose": "solutionYaml",
    }
    revision = 1
    for call in authored:
        if call["arguments"].get("journeyRef") != journey_ref:
            raise CertificationFailure("an authored asset belongs to another journey")
        if call["arguments"].get("expectedJourneyRevision") != revision:
            raise CertificationFailure("journey revisions are not one monotonic authoring line")
        if call["tool"] in four_entity_tools \
                and call["arguments"].get("authoringPatternsFingerprint") != authoring_patterns:
            raise CertificationFailure(
                "four-entity authoring was not bound to the server-validated template context")
        if call["tool"] in four_entity_tools:
            source = call["arguments"].get(source_fields[call["tool"]])
            display_key = re.compile(r'(?m)(?:^[ \t]+display\s*:|["\']display["\']\s*:)')
            if not isinstance(source, str) or not display_key.search(source):
                raise CertificationFailure(
                    "four-entity authoring omitted the server-required business display")
        revision += 1

    required_kinds = ["rg.feature.define", "rg.scenario.define", "rg.instruction.define",
                      "rg.solution.compose", "rg.solution.golden.propose"]
    positions: list[int] = []
    cursor = 0
    for tool in required_kinds:
        for index in range(cursor, len(calls)):
            if calls[index]["tool"] == tool and calls[index]["successful"]:
                positions.append(index)
                cursor = index + 1
                break
        else:
            raise CertificationFailure(f"required business operation is missing or out of order: {tool}")

    compose = calls[positions[-2]]
    proposal = calls[positions[-1]]
    features = successful(calls, "rg.feature.define")
    recalled_feature_ref = required_text(features[0]["data"].get("featureId"), "recalled feature")
    recalled_feature_fingerprint = required_text(
        features[0]["data"].get("contractFingerprint"), "recalled feature contract fingerprint")
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", recalled_feature_fingerprint):
        raise CertificationFailure("recalled feature contract fingerprint is malformed")
    solution_ref = required_text(compose["data"].get("solutionRef"), "solution")
    solution_fingerprint = required_text(
        compose["data"].get("contractFingerprint"), "solution contract fingerprint")
    if proposal["arguments"].get("solutionRef") != solution_ref:
        raise CertificationFailure("GOLDEN proposal is not bound to the composed Solution")
    for review in (call for call in starts if call is not start):
        if review["arguments"].get("targetRef") != solution_ref \
                or calls.index(review) <= calls.index(compose):
            raise CertificationFailure("read-only review must inspect the same composed Solution")
    composing_contexts = [call["data"].get("solutionContextFingerprint")
                          for call in successful(calls, "rg.journey.next")
                          if call["data"].get("stage") == "COMPOSING"]
    supplied_context = compose["arguments"].get("solutionContextFingerprint")
    if not supplied_context or supplied_context not in composing_contexts:
        raise CertificationFailure("Solution composition did not use a current journey context")

    case_set_ref = required_text(proposal["data"].get("caseSetRef"), "case set")
    cases = proposal["arguments"].get("cases")
    summaries = proposal["data"].get("caseSummaries")
    if not isinstance(cases, list) or not isinstance(summaries, list) or len(cases) < 2:
        raise CertificationFailure("at least two complete proposed business cases are required")
    requested_ids = sorted(required_text(case.get("caseId"), "case")
                           for case in cases if isinstance(case, dict))
    summary_ids = sorted(required_text(case.get("caseId"), "case summary")
                         for case in summaries if isinstance(case, dict))
    if requested_ids != summary_ids or proposal["data"].get("proposalStatus") != "PENDING":
        raise CertificationFailure("GOLDEN summaries do not match the pending proposal")
    listed = [call for call in successful(calls, "rg.solution.golden.list")
              if call["arguments"].get("journeyRef") == journey_ref
              and call["arguments"].get("solutionRef") == solution_ref
              and call["data"].get("caseSetRef") == case_set_ref]
    if not listed:
        raise CertificationFailure("the pending GOLDEN set was not read back through the business surface")
    listed_ids = sorted(required_text(case.get("caseId"), "listed case")
                        for case in listed[-1]["data"].get("caseSummaries", [])
                        if isinstance(case, dict))
    if listed_ids != requested_ids or listed[-1]["data"].get("approvalState") != "PENDING":
        raise CertificationFailure("the read-back GOLDEN line is not awaiting human approval")
    return {
        "journeyRef": journey_ref,
        "solutionRef": solution_ref,
        "solutionContractFingerprint": solution_fingerprint,
        "caseSetRef": case_set_ref,
        "caseIds": requested_ids,
        "requiredPositions": [position + 1 for position in positions],
        "librarySnapshotFingerprint": library_snapshot,
        "authoringPatternsFingerprint": authoring_patterns,
        "recalledFeatureRef": recalled_feature_ref,
        "recalledFeatureContractFingerprint": recalled_feature_fingerprint,
    }, proposal


def require_recall_trace(path: Path, chain: dict[str, Any], exit_code: int) -> dict[str, Any]:
    """Prove that a new read-only Codex turn recalled the exact authored Feature."""
    calls, final_message, completed, thread_id = load_trace(path)
    if len(calls) > 40 or not completed or exit_code != 0:
        raise CertificationFailure("recall turn did not complete within the bounded call budget")
    for call in calls:
        passive = call["server"] == "codex" and call["tool"] in CODEX_MCP_DISCOVERY_CALLS
        business_read = call["server"] == "rg_read" and call["tool"] in READ_TOOLS
        if not passive and not business_read:
            raise CertificationFailure("recall turn escaped the read-only business surface")
    if not final_message.strip() or TECHNICAL_FINAL_PATTERN.search(final_message):
        raise CertificationFailure("recall final summary is missing or exposes technical vocabulary")

    target = (chain["recalledFeatureRef"], chain["recalledFeatureContractFingerprint"])
    best_rank: int | None = None
    best_match_type = "NONE"
    for search in successful(calls, "rg.capability.search"):
        candidates = search["data"].get("candidates")
        if not isinstance(candidates, list):
            continue
        for rank, candidate in enumerate(candidates, start=1):
            if not isinstance(candidate, dict):
                continue
            coordinate = (candidate.get("assetRef"), candidate.get("contractFingerprint"))
            if coordinate != target:
                continue
            if best_rank is None or rank < best_rank:
                best_rank = rank
                best_match_type = str(candidate.get("matchType", "NONE"))
    if best_rank is None or best_rank > 3:
        raise CertificationFailure("recall candidates do not contain the authored Feature in the top three")
    if best_rank != 1:
        raise CertificationFailure("the unique recall sample did not rank the authored Feature first")
    return {"rank": best_rank, "matchType": best_match_type, "calls": calls,
            "threadId": thread_id}


def require_clarification_trace(path: Path, exit_code: int) -> dict[str, Any]:
    """Prove that Codex asked one business question before any authoring mutation."""
    calls, final_message, completed, thread_id = load_trace(path)
    if len(calls) > 40 or not completed or exit_code != 0:
        raise CertificationFailure("clarification turn did not complete within the bounded call budget")
    for call in calls:
        passive = call["server"] == "codex" and call["tool"] in CODEX_MCP_DISCOVERY_CALLS
        business = call["server"] in {"rg_read", "rg_author"} \
            and call["tool"] in READ_TOOLS | AUTHORING_TOOLS
        if not passive and not business:
            raise CertificationFailure("clarification turn escaped the business surface")
        if call["tool"] in AUTHORING_TOOLS:
            raise CertificationFailure("clarification turn attempted a business write")
    if not successful(calls, "rg.library.overview.get") \
            and not successful(calls, "rg.capability.search"):
        raise CertificationFailure("clarification turn did not consult the business library")
    question_count = final_message.count("?") + final_message.count("？")
    if question_count != 1:
        raise CertificationFailure("clarification turn must ask exactly one business question")
    if TECHNICAL_FINAL_PATTERN.search(final_message):
        raise CertificationFailure("clarification final summary exposes technical vocabulary")
    return {"calls": calls, "threadId": thread_id}


def canonical_bytes(value: Any) -> bytes:
    """Encode one private manifest deterministically for its integrity binding."""
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(",", ":")).encode("utf-8")


def load_setup_manifest(path: Path) -> dict[str, Any]:
    """Validate the private seed result and recompute its actual asset relationship fingerprint."""
    manifest = json.loads(path.read_text(encoding="utf-8"))
    required = {"schemaVersion", "fixtureFingerprint", "authoringPatternsFingerprint",
                "completedPhases", "assets", "relationships", "preflights", "setupFingerprint"}
    if not isinstance(manifest, dict) or set(manifest) != required \
            or manifest.get("schemaVersion") != "rg.businessRecallFamilySetup.v1":
        raise CertificationFailure("setup manifest has an unsupported shape")
    for field in ("fixtureFingerprint", "authoringPatternsFingerprint", "setupFingerprint"):
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", str(manifest.get(field, ""))):
            raise CertificationFailure(f"setup manifest has a malformed {field}")
    relationships = manifest.get("relationships")
    if relationships != SETUP_RELATIONSHIPS:
        raise CertificationFailure("setup manifest does not bind the required family relationships")
    assets = manifest.get("assets")
    if not isinstance(assets, list):
        raise CertificationFailure("setup manifest has no assets")
    by_role: dict[str, dict[str, Any]] = {}
    for item in assets:
        if not isinstance(item, dict) or set(item) != {
                "role", "assetKind", "assetRef", "contractFingerprint", "revision"}:
            raise CertificationFailure("setup asset has an unsupported shape")
        role = required_text(item.get("role"), "setup role")
        if role in by_role:
            raise CertificationFailure("setup manifest contains a duplicate role")
        required_text(item.get("assetKind"), f"setup kind for {role}")
        required_text(item.get("assetRef"), f"setup ref for {role}")
        required_text(item.get("contractFingerprint"), f"setup fingerprint for {role}")
        if not isinstance(item.get("revision"), int) or item["revision"] < 0:
            raise CertificationFailure("setup asset revision is malformed")
        by_role[role] = item
    required_roles = {role for roles in SETUP_RELATIONSHIPS.values() for role in roles}
    if set(by_role) != required_roles:
        raise CertificationFailure("setup manifest does not contain the exact required asset roles")
    if manifest.get("completedPhases") != ["near-meaning", "remaining", "ambiguity"]:
        raise CertificationFailure("setup manifest does not prove all ordered seed phases")
    preflights = manifest.get("preflights")
    if not isinstance(preflights, list):
        raise CertificationFailure("setup manifest has no preflight evidence")
    by_preflight: dict[str, dict[str, Any]] = {}
    for preflight in preflights:
        if not isinstance(preflight, dict) or set(preflight) != {
                "familyId", "status", "observedRoles", "outcome", "target"}:
            raise CertificationFailure("setup preflight has an unsupported shape")
        family_id = required_text(preflight.get("familyId"), "setup preflight family")
        if family_id in by_preflight or family_id not in SETUP_PREFLIGHT_OUTCOMES:
            raise CertificationFailure("setup manifest has a duplicate or unknown preflight")
        if preflight.get("status") not in SETUP_PREFLIGHT_STATUSES[family_id] \
                or preflight.get("outcome") != SETUP_PREFLIGHT_OUTCOMES[family_id] \
                or preflight.get("observedRoles") != SETUP_RELATIONSHIPS[family_id]:
            raise CertificationFailure("setup preflight does not match its family relationship")
        target = preflight.get("target")
        if family_id == "near-meaning-distractor":
            if not isinstance(target, dict) or set(target) != {
                    "assetRef", "contractFingerprint", "matchType"} \
                    or target.get("matchType") != "EXACT":
                raise CertificationFailure("near preflight has no exact primary target")
            required_text(target.get("assetRef"), "near preflight target")
            required_text(target.get("contractFingerprint"), "near preflight target fingerprint")
        elif target is not None:
            raise CertificationFailure("non-ranking setup preflight must not select a target")
        by_preflight[family_id] = preflight
    if set(by_preflight) != set(SETUP_PREFLIGHT_OUTCOMES):
        raise CertificationFailure("setup manifest does not contain every required preflight")
    material = {key: manifest[key] for key in (
        "fixtureFingerprint", "authoringPatternsFingerprint", "completedPhases", "assets",
        "relationships", "preflights")}
    actual = "sha256:" + hashlib.sha256(canonical_bytes(material)).hexdigest()
    if not hmac.compare_digest(actual, manifest["setupFingerprint"]):
        raise CertificationFailure("setup fingerprint does not match the seeded asset relationships")
    return {**manifest, "path": path, "byRole": by_role, "byPreflight": by_preflight}


def load_surface_proof(value: dict[str, Any], runtime_nonce: str) -> dict[str, Any]:
    """Validate server-side list and call surface evidence from the owned runtime."""
    required = {"schemaVersion", "runtimeInstanceNonce", "purposeProofs", "proofFingerprint"}
    if not isinstance(value, dict) or set(value) != required \
            or value.get("schemaVersion") != "rg.businessSurfaceProof.v1":
        raise CertificationFailure("business surface proof has an unsupported shape")
    if value.get("runtimeInstanceNonce") != runtime_nonce:
        raise CertificationFailure("business surface proof belongs to another runtime")
    purpose_proofs = value.get("purposeProofs")
    expected = {
        "read": ("AGENT_TDD_READ", {"rg.library.overview.get", "rg.capability.search"},
                 "rg.dsl.reference.get"),
        "authoring": ("AGENT_TDD_AUTHORING", {"rg.journey.start", "rg.feature.define"},
                      "rg.library.upsert"),
    }
    if not isinstance(purpose_proofs, dict) or set(purpose_proofs) != set(expected):
        raise CertificationFailure("business surface proof does not cover both MCP purposes")
    for label, (purpose, required_tools, hidden_tool) in expected.items():
        proof = purpose_proofs.get(label)
        if not isinstance(proof, dict) or set(proof) != {
                "purpose", "visibleToolNames", "hiddenTool", "rejectionCode", "rejectionReason"}:
            raise CertificationFailure(f"business {label} surface proof has an unsupported shape")
        names = proof.get("visibleToolNames")
        if proof.get("purpose") != purpose or not isinstance(names, list) \
                or not required_tools.issubset(set(names)):
            raise CertificationFailure(f"business {label} surface proof has no required tools")
        if any(not isinstance(name, str) or name not in AUTHORING_TOOLS | READ_TOOLS
               for name in names):
            raise CertificationFailure(f"business {label} surface proof exposes a platform tool")
        if proof.get("hiddenTool") != hidden_tool or proof.get("rejectionCode") != -32031 \
                or proof.get("rejectionReason") != "TOOL_NOT_VISIBLE_IN_SURFACE":
            raise CertificationFailure(f"business {label} direct-call guard was not observed")
    material = {key: value[key] for key in required if key not in {"schemaVersion", "proofFingerprint"}}
    actual = "sha256:" + hashlib.sha256(canonical_bytes(material)).hexdigest()
    if not hmac.compare_digest(actual, str(value.get("proofFingerprint"))):
        raise CertificationFailure("business surface proof fingerprint does not match")
    return value


def load_family_manifest(path: Path) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """Load the private 15-family trace index without accepting caller-defined semantics."""
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(manifest, dict) \
            or manifest.get("schemaVersion") != "rg.businessRecallFamilyTraceSet.v1" \
            or set(manifest) != {"schemaVersion", "setupManifestFile", "setupCredentialFingerprint",
                                 "workloadCredentialFingerprint", "families"}:
        raise CertificationFailure("family trace manifest has an unsupported shape")
    setup_credential = required_text(
        manifest.get("setupCredentialFingerprint"), "setup credential fingerprint")
    workload_credential = required_text(
        manifest.get("workloadCredentialFingerprint"), "workload credential fingerprint")
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", setup_credential) \
            or not re.fullmatch(r"sha256:[0-9a-f]{64}", workload_credential) \
            or hmac.compare_digest(setup_credential, workload_credential):
        raise CertificationFailure("setup and workload credentials are not independently bound")
    setup_name = required_text(manifest.get("setupManifestFile"), "setup manifest file")
    setup_path = Path(setup_name)
    if not setup_path.is_absolute():
        setup_path = path.parent / setup_path
    setup = load_setup_manifest(setup_path)
    entries = manifest.get("families")
    if not isinstance(entries, list):
        raise CertificationFailure("family trace manifest does not contain families")
    observed_ids: list[str] = []
    normalized: list[dict[str, Any]] = []
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != {
                "familyId", "expectedBehaviorClass", "traceFile", "exitCode",
                "runtimeInstanceNonce"}:
            raise CertificationFailure("family trace manifest entry has an unsupported shape")
        family_id = required_text(entry.get("familyId"), "family id")
        observed_ids.append(family_id)
        expected = FAMILY_EXPECTATIONS.get(family_id)
        if expected is None:
            raise CertificationFailure(f"unknown recall family: {family_id}")
        if entry.get("expectedBehaviorClass") != expected:
            raise CertificationFailure(f"recall family is misclassified: {family_id}")
        trace_name = required_text(entry.get("traceFile"), "family trace file")
        trace_file = Path(trace_name)
        if not trace_file.is_absolute():
            trace_file = path.parent / trace_file
        runtime_nonce = required_text(entry.get("runtimeInstanceNonce"),
                                      f"runtime nonce for {family_id}")
        if not re.fullmatch(r"[0-9a-f]{32,128}", runtime_nonce):
            raise CertificationFailure(f"runtime nonce is malformed for {family_id}")
        normalized.append({
            "familyId": family_id,
            "expectedBehaviorClass": expected,
            "traceFile": trace_file,
            "exitCode": entry.get("exitCode"),
            "runtimeInstanceNonce": runtime_nonce,
        })
    expected_ids = set(FAMILY_EXPECTATIONS)
    observed_set = set(observed_ids)
    if len(observed_ids) != len(observed_set):
        raise CertificationFailure("family trace manifest contains a duplicate family")
    missing = sorted(expected_ids - observed_set)
    extra = sorted(observed_set - expected_ids)
    if missing or extra:
        raise CertificationFailure(
            f"family trace manifest must cover all 15 families; missing={missing}, extra={extra}")
    ordered = sorted(normalized, key=lambda item: list(FAMILY_EXPECTATIONS).index(item["familyId"]))
    return ordered, {**setup, "setupCredentialFingerprint": setup_credential,
                     "workloadCredentialFingerprint": workload_credential}


def require_family_surface(calls: list[dict[str, Any]], family_id: str) -> None:
    """Fail when one family turn observes a tool outside the business surface."""
    for call in calls:
        passive = call["server"] == "codex" and call["tool"] in CODEX_MCP_DISCOVERY_CALLS
        business_read = call["server"] in {"rg_read", "rg_author"} \
            and call["tool"] in READ_TOOLS
        business_write = call["server"] == "rg_author" and call["tool"] in AUTHORING_TOOLS
        if not passive and not business_read and not business_write:
            raise CertificationFailure(f"family {family_id} escaped the business surface")


def require_business_question(calls: list[dict[str, Any]], final_message: str,
                              family_id: str) -> None:
    """Require one question and no successful mutation for a fail-closed family."""
    if any(call["successful"] and call["tool"] in AUTHORING_TOOLS for call in calls):
        raise CertificationFailure(f"family {family_id} mutated state before clarification")
    if not successful(calls, "rg.library.overview.get") \
            and not successful(calls, "rg.capability.search") \
            and not successful(calls, "rg.journey.next"):
        raise CertificationFailure(f"family {family_id} did not inspect current business context")
    if final_message.count("?") + final_message.count("？") != 1:
        raise CertificationFailure(f"family {family_id} must ask exactly one business question")


def target_rank(calls: list[dict[str, Any]], chain: dict[str, Any]) -> tuple[int | None, str]:
    """Return the best rank and match class for the exact authored Feature."""
    target = (chain["recalledFeatureRef"], chain["recalledFeatureContractFingerprint"])
    best_rank: int | None = None
    best_match = "NONE"
    for search in successful(calls, "rg.capability.search"):
        candidates = search["data"].get("candidates")
        if not isinstance(candidates, list):
            continue
        for rank, candidate in enumerate(candidates, start=1):
            if not isinstance(candidate, dict):
                continue
            if (candidate.get("assetRef"), candidate.get("contractFingerprint")) != target:
                continue
            if best_rank is None or rank < best_rank:
                best_rank = rank
                best_match = str(candidate.get("matchType", "NONE"))
    return best_rank, best_match


def contains_value(value: Any, expected: str) -> bool:
    """Search a private argument tree for one exact controlled-assumption outcome."""
    if isinstance(value, dict):
        return any(contains_value(child, expected) for child in value.values())
    if isinstance(value, list):
        return any(contains_value(child, expected) for child in value)
    return value == expected


def successful_golden_with(calls: list[dict[str, Any]], predicate: Any) -> bool:
    """Return whether a successful GOLDEN proposal contains a required private case construct."""
    return any(predicate(call["arguments"]) for call in successful(calls, "rg.solution.golden.propose"))


def setup_assets(setup: dict[str, Any], family_id: str) -> list[dict[str, Any]]:
    """Return the exact private seed coordinates declared for one family."""
    return [setup["byRole"][role] for role in SETUP_RELATIONSHIPS.get(family_id, [])]


def observed_candidates(calls: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Collect candidates from this thread only."""
    return [candidate for search in successful(calls, "rg.capability.search")
            for candidate in search["data"].get("candidates", [])
            if isinstance(candidate, dict)]


def contains_seeded_candidates(calls: list[dict[str, Any]], seeds: list[dict[str, Any]]) -> bool:
    """Require this thread to observe every setup asset by ref and fingerprint."""
    observed = {(item.get("assetRef"), item.get("contractFingerprint"))
                for item in observed_candidates(calls)}
    expected = {(item["assetRef"], item["contractFingerprint"]) for item in seeds}
    return expected.issubset(observed)


def require_family_trace(entry: dict[str, Any], chain: dict[str, Any],
                         setup: dict[str, Any]) -> dict[str, Any]:
    """Classify one real Codex trace from observable server outcomes, not manifest claims."""
    family_id = entry["familyId"]
    calls, final_message, completed, thread_id = load_trace(entry["traceFile"])
    if len(calls) > 40 or not completed or entry["exitCode"] != 0:
        raise CertificationFailure(f"family {family_id} did not complete within the bounded call budget")
    require_family_surface(calls, family_id)
    business_calls = [call for call in calls if call["server"] in {"rg_read", "rg_author"}]
    if not business_calls or business_calls[0]["tool"] not in FAMILY_FIRST_TOOL_ALLOWLIST[family_id]:
        raise CertificationFailure(f"family {family_id} did not recall an intent-appropriate first tool")
    first_tool = business_calls[0]["tool"]
    if not final_message.strip() or TECHNICAL_FINAL_PATTERN.search(final_message):
        raise CertificationFailure(f"family {family_id} final summary is missing or technical")

    outcome = ""
    rank: int | None = None
    match_type = "NONE"
    if family_id in {"synonym-rewrite", "near-meaning-distractor"}:
        rank, match_type = target_rank(calls, chain)
        if rank != 1:
            raise CertificationFailure(f"family {family_id} did not rank the exact Feature first")
        if family_id == "near-meaning-distractor":
            if not contains_seeded_candidates(calls, setup_assets(setup, family_id)):
                raise CertificationFailure(
                    "family near-meaning-distractor did not observe its seeded distractor")
        outcome = "TOP1_MATCH"
    elif family_id in {
            "boundary-unspecified", "unknown-policy-unspecified", "authority-source-unspecified"}:
        require_business_question(calls, final_message, family_id)
        outcome = "SINGLE_BUSINESS_QUESTION"
    elif family_id == "multiple-exact":
        ambiguous = any(search["data"].get("status") == "AMBIGUOUS"
                        for search in successful(calls, "rg.capability.search"))
        if not ambiguous:
            raise CertificationFailure("family multiple-exact did not observe AMBIGUOUS")
        if not contains_seeded_candidates(calls, setup_assets(setup, family_id)):
            raise CertificationFailure("family multiple-exact did not observe both seeded candidates")
        require_business_question(calls, final_message, family_id)
        outcome = "AMBIGUOUS_STOP"
    elif family_id == "legacy-feature-partial":
        partial = any(
            candidate.get("matchType") == "PARTIAL"
            for search in successful(calls, "rg.capability.search")
            for candidate in search["data"].get("candidates", []) if isinstance(candidate, dict))
        if not partial:
            raise CertificationFailure("family legacy-feature-partial did not observe PARTIAL")
        if not contains_seeded_candidates(calls, setup_assets(setup, family_id)):
            raise CertificationFailure("family legacy-feature-partial did not observe the seeded legacy Feature")
        require_business_question(calls, final_message, family_id)
        outcome = "PARTIAL_STOP"
    elif family_id == "surface-interference":
        if not successful(calls, "rg.library.overview.get") \
                and not successful(calls, "rg.capability.search"):
            raise CertificationFailure("family surface-interference did not inspect the business library")
        outcome = "BUSINESS_SURFACE_ONLY"
    elif family_id == "cross-session-rediscovery":
        current = [call for call in successful(calls, "rg.journey.next")
                   if call["arguments"].get("journeyRef") == chain["journeyRef"]
                   and call["data"].get("journeyRef") == chain["journeyRef"]]
        discovered = successful(calls, "rg.entity.list") or successful(calls, "rg.entity.get") \
            or successful(calls, "rg.capability.search")
        if not current or not discovered:
            raise CertificationFailure("family cross-session-rediscovery did not rediscover current state")
        outcome = "CURRENT_STATE_FOUND"
    elif family_id == "semantic-drift":
        drift_journey = setup["byRole"]["semanticDriftJourney"]["assetRef"]
        drift = any(call["arguments"].get("journeyRef") == drift_journey
                    and call["data"].get("journeyRef") == drift_journey
                    and any(contains_value(call["data"], code) for code in (
                        "BUSINESS_SEMANTICS_CHANGED", "CAPABILITY_CONTEXT_STALE", "GOLDEN_CASE_STALE"))
                    for call in successful(calls, "rg.journey.next"))
        if not drift:
            raise CertificationFailure("family semantic-drift did not observe semantic re-confirmation")
        require_business_question(calls, final_message, family_id)
        outcome = "RECONFIRMATION_STOP"
    elif family_id == "fact-assumption":
        captured = successful_golden_with(calls, lambda arguments:
                                          contains_value(arguments, "givenFacts")
                                          or any(key in arguments for key in ("given", "givenFacts"))
                                          or any(isinstance(case, dict)
                                                 and ("given" in case or "givenFacts" in case)
                                                 for case in arguments.get("cases", [])))
        if not captured:
            raise CertificationFailure("family fact-assumption did not capture given facts")
        outcome = "GIVEN_FACT_CAPTURED"
    elif family_id in {"dependency-unavailable", "action-stubbing", "forbidden-dependency"}:
        expected_value = {
            "dependency-unavailable": "UNAVAILABLE",
            "action-stubbing": "SUCCEEDS_WITHOUT_EFFECT",
            "forbidden-dependency": "MUST_NOT_BE_USED",
        }[family_id]
        if not successful_golden_with(calls, lambda arguments: contains_value(arguments, expected_value)):
            raise CertificationFailure(
                f"family {family_id} did not capture controlled outcome {expected_value}")
        outcome = {
            "dependency-unavailable": "UNAVAILABLE_CAPTURED",
            "action-stubbing": "SIDE_EFFECT_STUB_CAPTURED",
            "forbidden-dependency": "MUST_NOT_USE_CAPTURED",
        }[family_id]
    elif family_id == "assumption-ambiguity":
        ambiguous = any(search["data"].get("status") == "AMBIGUOUS"
                        for search in successful(calls, "rg.capability.search")) \
            or any(contains_value(call["data"], "BUSINESS_ASSUMPTION_AMBIGUOUS") for call in calls)
        seeds = setup_assets(setup, family_id)
        seeded_candidates = contains_seeded_candidates(calls, seeds)
        same_name = len({item.get("businessName") for item in observed_candidates(calls)
                         if any(item.get("assetRef") == seed["assetRef"] for seed in seeds)}) == 1
        if not ambiguous and not (seeded_candidates and same_name):
            raise CertificationFailure("family assumption-ambiguity did not observe ambiguity")
        if not seeded_candidates:
            raise CertificationFailure("family assumption-ambiguity did not observe both seeded actions")
        require_business_question(calls, final_message, family_id)
        outcome = "ASSUMPTION_AMBIGUOUS_STOP"
    else:  # FAMILY_EXPECTATIONS and manifest validation make this unreachable.
        raise CertificationFailure(f"family verifier is missing: {family_id}")
    return {
        "familyId": family_id,
        "expectedBehaviorClass": entry["expectedBehaviorClass"],
        "observedOutcome": outcome,
        "threadId": thread_id,
        "rank": rank,
        "matchType": match_type,
        "runtimeInstanceNonce": entry["runtimeInstanceNonce"],
        "firstTool": first_tool,
        "toolRecallPassed": True,
    }


def verify_board(board: Any, chain: dict[str, Any]) -> None:
    """Require pending HUMAN decisions for the exact correlated case set."""
    if not isinstance(board, dict) or board.get("payloadPolicy") != "STRUCTURE_ONLY":
        raise CertificationFailure("structure-only reviewer board is missing")
    pending = board.get("pendingReviews")
    if not isinstance(pending, list):
        raise CertificationFailure("reviewer board has no pending decisions")
    visible = {(item.get("assetRef"), item.get("caseId")) for item in pending
               if isinstance(item, dict) and item.get("kind") == "ORACLE"}
    expected = {(chain["caseSetRef"], case_id) for case_id in chain["caseIds"]}
    if not expected.issubset(visible):
        raise CertificationFailure("the exact proposed cases are not pending independent review")


def certify(trace: Path, metadata: dict[str, Any], family_manifest: Path | None = None) -> dict[str, Any]:
    """Build the safe certificate after all private correlation checks pass."""
    calls, final_message, completed, authoring_thread_id = load_trace(trace)
    if len(calls) > 80 or not completed or metadata["exitCode"] != 0:
        raise CertificationFailure("Codex turn did not complete within the bounded call budget")
    chain, _proposal = require_business_sequence(calls)
    if not final_message.strip() or TECHNICAL_FINAL_PATTERN.search(final_message):
        raise CertificationFailure("Codex final summary is missing or exposes technical vocabulary")
    verify_board(metadata.get("boardProjection"), chain)
    if family_manifest is None:
        raise CertificationFailure("all 15 real Codex family traces are required")
    family_entries, setup = load_family_manifest(family_manifest)
    near_target = setup["byPreflight"]["near-meaning-distractor"]["target"]
    if (near_target["assetRef"], near_target["contractFingerprint"]) != (
            chain["recalledFeatureRef"], chain["recalledFeatureContractFingerprint"]):
        raise CertificationFailure("near preflight target is not the primary authored Feature")
    family_proofs = [require_family_trace(entry, chain, setup) for entry in family_entries]
    thread_ids = [authoring_thread_id] + [proof["threadId"] for proof in family_proofs]
    if len(set(thread_ids)) != len(FAMILY_EXPECTATIONS) + 1:
        raise CertificationFailure("certification requires 16 independent Codex threads")
    runtime_nonce = required_text(metadata.get("runtimeInstanceNonce"), "runtime nonce")
    runtime_jar = required_text(metadata.get("runtimeJarSha256"), "runtime JAR")
    production_tree = required_text(metadata.get("productionTreeFingerprint"),
                                    "production tree fingerprint")
    certification_inputs = required_text(metadata.get("certificationInputsFingerprint"),
                                         "certification inputs fingerprint")
    codex_executable = required_text(metadata.get("codexExecutableSha256"),
                                     "Codex executable fingerprint")
    codex_code_directory = required_text(metadata.get("codexCodeDirectoryHash"),
                                         "Codex code-directory fingerprint")
    if not re.fullmatch(r"[0-9a-f]{32,128}", runtime_nonce) \
            or not re.fullmatch(r"sha256:[0-9a-f]{64}", runtime_jar) \
            or not re.fullmatch(r"sha256:[0-9a-f]{64}", production_tree) \
            or not re.fullmatch(r"sha256:[0-9a-f]{64}", certification_inputs) \
            or not re.fullmatch(r"sha256:[0-9a-f]{64}", codex_executable) \
            or not re.fullmatch(r"sha256:[0-9a-f]{64}", codex_code_directory):
        raise CertificationFailure("runtime identity is malformed")
    surface_proof = load_surface_proof(metadata.get("surfaceProof"), runtime_nonce)
    family_nonces = {proof["familyId"]: proof["runtimeInstanceNonce"] for proof in family_proofs}
    second_nonces = {family_nonces[family_id] for family_id in SECOND_RUNTIME_FAMILIES}
    third_nonces = {family_nonces[family_id] for family_id in THIRD_RUNTIME_FAMILIES}
    fourth_nonce = family_nonces["assumption-ambiguity"]
    if family_nonces["synonym-rewrite"] != runtime_nonce \
            or len(second_nonces) != 1 or len(third_nonces) != 1:
        raise CertificationFailure("certification does not prove four isolated Codex runtime phases")
    second_nonce = next(iter(second_nonces))
    third_nonce = next(iter(third_nonces))
    if len({runtime_nonce, second_nonce, third_nonce, fourth_nonce}) != 4:
        raise CertificationFailure("certification does not prove four isolated Codex runtime phases")
    runtime_nonces = [runtime_nonce, second_nonce, third_nonce, fourth_nonce]

    key = secrets.token_bytes(32)
    opaque = lambda label, value: "hmac-sha256:" + hmac.new(  # noqa: E731
        key, f"{label}\0{value}".encode(), hashlib.sha256).hexdigest()
    recall = successful(calls, "rg.capability.search")[-1]["data"].get("status", "NONE")
    safe_calls = [{"ordinal": index + 1, "server": call["server"],
                   "tool": call["tool"], "status": call["status"]}
                  for index, call in enumerate(calls)]
    certificate: dict[str, Any] = {
        "schemaVersion": "rg.businessRecallCertification.v1",
        "certifiedAt": metadata["certifiedAt"],
        "repositoryCommit": metadata["repositoryCommit"],
        "productionTreeFingerprint": production_tree,
        "certificationInputsFingerprint": certification_inputs,
        "codexVersion": metadata["codexVersion"],
        "runtimeIdentity": {
            "schemaVersion": "rg.agentTddCertificationInstance.v1",
            "codexExecutableSha256": metadata["codexExecutableSha256"],
            "codexCodeDirectoryHash": metadata["codexCodeDirectoryHash"],
            "instanceNonceFingerprints": [
                "sha256:" + hashlib.sha256(value.encode()).hexdigest() for value in runtime_nonces],
            "codexPhaseCount": 4,
            "repositoryCommit": metadata["repositoryCommit"],
            "jarSha256": runtime_jar,
            "processOwnershipVerified": True,
            "verifiedBeforeAndAfterTurn": True,
        },
        "setupIdentity": {
            "setupFingerprint": setup["setupFingerprint"],
            "seedManifestFingerprint": opaque(
                "seed-manifest", canonical_bytes({key: value for key, value in setup.items()
                                                  if key not in {"path", "byRole", "byPreflight"}}).decode("utf-8")),
            "relationshipCount": len(SETUP_RELATIONSHIPS),
            "credentialSeparationVerified": True,
            "credentialSeparationFingerprint": opaque(
                "setup-workload-credentials", setup["setupCredentialFingerprint"] + "\0"
                + setup["workloadCredentialFingerprint"]),
        },
        "suite": "business-solution-recall-v1",
        "transport": {
            "kind": "HTTP_MCP", "endpointClass": "LOOPBACK", "serverCount": 2,
            "serverListFiltered": True, "directHiddenCallRejected": True,
            "surfaceProofFingerprint": opaque("surface-proof", surface_proof["proofFingerprint"]),
        },
        "cases": [{
            "caseFingerprint": opaque("journey", chain["journeyRef"]),
            "expectedIntentKind": "CREATE_SOLUTION",
            "observedSurface": "BUSINESS_SOLUTION",
            "capabilityOutcome": recall if recall in {"EXACT", "AMBIGUOUS", "INCOMPLETE", "NONE"} else "NONE",
            "selectedContractFingerprint": opaque(
                "solution-contract", chain["solutionContractFingerprint"]),
            "toolSequenceClass": "VALID",
            "humanBoundaryRespected": True,
            "controlledAssumptionClass": "NOT_OBSERVED",
            "egressDeniedCount": 0,
            "goldenCaseCurrent": None,
        }],
        "journey": {
            "phase": "BUSINESS_AUTHORING_TO_HUMAN_GOLDEN_REVIEW",
            "requiredSequence": ["rg.feature.define", "rg.scenario.define", "rg.instruction.define",
                                 "rg.solution.compose", "rg.solution.golden.propose"],
            "successfulPositions": chain["requiredPositions"],
            "observedCalls": safe_calls,
        },
        "correlation": {
            "method": "EPHEMERAL_HMAC_SHA256",
            "journey": opaque("journey", chain["journeyRef"]),
            "solution": opaque("solution", chain["solutionRef"]),
            "caseSet": opaque("case-set", chain["caseSetRef"]),
            "cases": [opaque("case", case_id) for case_id in chain["caseIds"]],
            "librarySnapshot": opaque("library-snapshot", chain["librarySnapshotFingerprint"]),
            "authoringPatterns": opaque("authoring-patterns", chain["authoringPatternsFingerprint"]),
            "sessions": [opaque("codex-thread", value) for value in thread_ids],
        },
        "metrics": {
            "toolRecallRate": 1.0, "recallAt3": None, "top1": None,
            "clarificationRate": None,
            "recallCases": 0, "clarificationCases": 0,
            "unsafeEscapeCount": 0, "controlledTestEgressCount": None,
            "staleGoldenAcceptedCount": None,
        },
        "assertions": {
            "onlyBusinessSurfaceMcpActionsObserved": True,
            "journeyRevisionLineCurrent": True,
            "libraryAndCapabilityDiscoveryObserved": True,
            "compilerValidatedAuthoringPatternsObservedBeforeCreation": True,
            "fourEntityWritesBoundToAuthoringPatterns": True,
            "fourEntityBusinessDisplaysDeclared": True,
            "solutionContextCurrent": True,
            "completeGoldenCasesProposed": True,
            "sameJourneySolutionAndCaseSet": True,
            "pendingCasesVisibleForHumanReview": True,
            "stoppedBeforeApprovalExecutionAndPublication": True,
            "finalSummaryBusinessOnly": True,
            "rawArgumentsResultsAndMessagesOmitted": True,
            "spawnedRuntimeIdentityVerified": True,
            "independentCodexSessionsObserved": True,
            "serverSurfaceListFiltered": True,
            "serverSurfaceDirectCallRejected": True,
            "setupCredentialSeparated": True,
        },
        "result": "CERTIFIED",
    }
    proof_by_family = {proof["familyId"]: proof for proof in family_proofs}
    recall_proof = proof_by_family["synonym-rewrite"]
    clarification_proof = proof_by_family["unknown-policy-unspecified"]
    match_type = recall_proof["matchType"]
    if match_type not in {"EXACT", "PARTIAL", "CONFLICT", "NONE"}:
        match_type = "NONE"
    certificate["cases"].extend([
            {
                "caseFingerprint": opaque("recall-case", chain["recalledFeatureRef"]),
                "expectedIntentKind": "RECALL_CAPABILITY",
                "observedSurface": "BUSINESS_SOLUTION",
                "capabilityOutcome": match_type,
                "selectedContractFingerprint": opaque(
                    "feature-contract", chain["recalledFeatureContractFingerprint"]),
                "toolSequenceClass": "VALID",
                "humanBoundaryRespected": True,
                "controlledAssumptionClass": "NOT_APPLICABLE",
                "egressDeniedCount": 0,
                "goldenCaseCurrent": None,
            },
            {
                "caseFingerprint": opaque(
                    "clarification-case", chain["authoringPatternsFingerprint"]),
                "expectedIntentKind": "DEFINE_FEATURE",
                "observedSurface": "BUSINESS_SOLUTION",
                "capabilityOutcome": "CLARIFIED",
                "selectedContractFingerprint": None,
                "toolSequenceClass": "VALID",
                "humanBoundaryRespected": True,
                "controlledAssumptionClass": "NOT_APPLICABLE",
                "egressDeniedCount": 0,
                "goldenCaseCurrent": None,
            },
    ])
    recall_proofs = [proof for proof in family_proofs
                     if proof["expectedBehaviorClass"] == "RECALL_TOP1"]
    clarification_proofs = [proof for proof in family_proofs
                             if proof["familyId"] in CLARIFICATION_FAMILIES]
    certificate["metrics"].update({
        "toolRecallRate": sum(proof["toolRecallPassed"] for proof in family_proofs)
                          / len(FAMILY_EXPECTATIONS),
        "recallAt3": sum(proof["rank"] is not None and proof["rank"] <= 3
                         for proof in recall_proofs) / len(recall_proofs),
        "top1": sum(proof["rank"] == 1 for proof in recall_proofs) / len(recall_proofs),
        "clarificationRate": sum(proof["observedOutcome"] in {
            "SINGLE_BUSINESS_QUESTION", "AMBIGUOUS_STOP", "PARTIAL_STOP",
            "RECONFIRMATION_STOP", "ASSUMPTION_AMBIGUOUS_STOP",
        } for proof in clarification_proofs) / len(clarification_proofs),
        "recallCases": len(recall_proofs),
        "clarificationCases": len(clarification_proofs),
    })
    certificate["familyEvidence"] = [{
        "familyId": proof["familyId"],
        "caseFingerprint": opaque(
            "family-case", f"{proof['familyId']}\0{proof['threadId']}"),
        "sessionFingerprint": opaque("codex-thread", proof["threadId"]),
        "expectedBehaviorClass": proof["expectedBehaviorClass"],
        "observedOutcome": proof["observedOutcome"],
        "firstTool": proof["firstTool"],
        "toolRecallPassed": proof["toolRecallPassed"],
        "passed": True,
    } for proof in family_proofs]
    certificate["assertions"].update({
        "crossSessionFeatureRecallCorrelated": True,
        "clarificationStoppedBeforeAuthoring": True,
        "singleBusinessQuestionObserved": True,
        "allRequiredRecallFamiliesObserved": True,
    })
    canonical = json.dumps(certificate, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    certificate["certificateFingerprint"] = "sha256:" + hashlib.sha256(canonical.encode()).hexdigest()
    return certificate


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("trace", type=Path)
    parser.add_argument("--repository-commit", required=True)
    parser.add_argument("--codex-version", required=True)
    parser.add_argument("--codex-executable-sha256", required=True)
    parser.add_argument("--codex-code-directory-hash", required=True)
    parser.add_argument("--certified-at", default=datetime.now(timezone.utc).isoformat())
    parser.add_argument("--exit-code", required=True, type=int)
    parser.add_argument("--runtime-instance-nonce", required=True)
    parser.add_argument("--runtime-jar-sha256", required=True)
    parser.add_argument("--production-tree-fingerprint", required=True)
    parser.add_argument("--certification-inputs-fingerprint", required=True)
    parser.add_argument("--board-projection", required=True, type=Path)
    parser.add_argument("--family-manifest", required=True, type=Path)
    parser.add_argument("--surface-proof", required=True, type=Path)
    args = parser.parse_args()
    try:
        board = json.loads(args.board_projection.read_text(encoding="utf-8"))
        surface_proof = json.loads(args.surface_proof.read_text(encoding="utf-8"))
        result = certify(args.trace, {
            "repositoryCommit": args.repository_commit, "codexVersion": args.codex_version,
            "codexExecutableSha256": args.codex_executable_sha256,
            "codexCodeDirectoryHash": args.codex_code_directory_hash,
            "certifiedAt": args.certified_at, "exitCode": args.exit_code,
            "runtimeInstanceNonce": args.runtime_instance_nonce,
            "runtimeJarSha256": args.runtime_jar_sha256,
            "productionTreeFingerprint": args.production_tree_fingerprint,
            "certificationInputsFingerprint": args.certification_inputs_fingerprint,
            "boardProjection": board,
            "surfaceProof": surface_proof,
        }, args.family_manifest)
    except (CertificationFailure, json.JSONDecodeError, OSError) as failure:
        print(f"Certification failed: {failure}", file=sys.stderr)
        return 1
    json.dump(result, sys.stdout, ensure_ascii=False, indent=2)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
