#!/usr/bin/env python3
"""Seed the isolated business-recall certification scope through normal HTTP APIs."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Callable


SCHEMA_VERSION = "rg.businessRecallFamilySetup.v1"
REQUIRED_ROLES = {
    "nearMeaningDistractor",
    "multipleExactA",
    "multipleExactB",
    "legacyPartial",
    "assumptionAmbiguityA",
    "assumptionAmbiguityB",
    "semanticDriftFeature",
    "semanticDriftJourney",
    "semanticDriftCaseSet",
}


class SetupFailure(RuntimeError):
    """Raised when one governed setup operation cannot establish the declared fixture."""


def canonical_bytes(value: Any) -> bytes:
    """Encode stable JSON for fingerprints without leaking values to logs."""
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(",", ":")).encode("utf-8")


def sha256(value: Any) -> str:
    """Return a prefixed SHA-256 over one canonical value."""
    return "sha256:" + hashlib.sha256(canonical_bytes(value)).hexdigest()


class HttpApi:
    """Small HTTP client for public MCP calls and the existing human review API."""

    def __init__(self, endpoint: str, author_token: str, review_token: str,
                 opener: Callable[..., Any] = urllib.request.urlopen) -> None:
        self.endpoint = endpoint.rstrip("/")
        self.base_url = self.endpoint.removesuffix("/mcp")
        self.author_token = author_token
        self.review_token = review_token
        self.opener = opener
        self.request_id = 0

    def _json(self, request: urllib.request.Request) -> dict[str, Any]:
        try:
            with self.opener(request, timeout=15) as response:
                value = json.loads(response.read().decode("utf-8"))
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as failure:
            raise SetupFailure("certification setup HTTP exchange failed") from failure
        if not isinstance(value, dict):
            raise SetupFailure("certification setup received a non-object response")
        return value

    def mcp(self, tool: str, arguments: dict[str, Any], *, purpose: str,
            surface: str) -> dict[str, Any]:
        """Call one catalogued MCP tool and return its successful data envelope."""
        self.request_id += 1
        body = canonical_bytes({
            "jsonrpc": "2.0", "id": self.request_id, "method": "tools/call",
            "params": {"name": tool, "arguments": arguments},
        })
        request = urllib.request.Request(self.endpoint, data=body, method="POST", headers={
            "Authorization": f"Bearer {self.author_token}",
            "Content-Type": "application/json",
            "MCP-Protocol-Version": "2025-06-18",
            "X-Purpose": purpose,
            "X-RG-Surface": surface,
        })
        payload = self._json(request)
        result = payload.get("result")
        if not isinstance(result, dict):
            raise SetupFailure(f"setup tool failed: {tool}")
        structured = result.get("structuredContent", result.get("structured_content"))
        if not isinstance(structured, dict) or structured.get("ok") is not True:
            code = structured.get("error", {}).get("code") if isinstance(structured, dict) else None
            raise SetupFailure(f"setup tool failed: {tool} ({code or 'UNKNOWN'})")
        data = structured.get("data")
        if not isinstance(data, dict):
            raise SetupFailure(f"setup tool returned no data: {tool}")
        return data

    def review_get(self, case_set_ref: str, case_id: str, revision: int) -> dict[str, Any]:
        """Read one proposal through the existing independent reviewer endpoint."""
        path = "/api/agent-tdd/reviews/oracles/{}/{}?expectedRevision={}".format(
            urllib.parse.quote(case_set_ref, safe=""), urllib.parse.quote(case_id, safe=""), revision)
        request = urllib.request.Request(self.base_url + path, method="GET", headers={
            "Authorization": f"Bearer {self.review_token}",
            "X-Purpose": "AGENT_TDD_GOVERNANCE",
        })
        return self._json(request)

    def approve(self, case_set_ref: str, case_id: str, revision: int,
                proposal_fingerprint: str) -> dict[str, Any]:
        """Approve one controlled setup case through the existing reviewer endpoint."""
        path = "/api/agent-tdd/reviews/oracles/{}/{}/approve".format(
            urllib.parse.quote(case_set_ref, safe=""), urllib.parse.quote(case_id, safe=""))
        body = canonical_bytes({
            "expectedRevision": revision, "proposalFingerprint": proposal_fingerprint,
        })
        request = urllib.request.Request(self.base_url + path, data=body, method="POST", headers={
            "Authorization": f"Bearer {self.review_token}",
            "Content-Type": "application/json",
            "X-Purpose": "AGENT_TDD_GOVERNANCE",
        })
        return self._json(request)


def required_text(value: Any, label: str) -> str:
    """Return one non-blank setup coordinate."""
    if not isinstance(value, str) or not value.strip():
        raise SetupFailure(f"setup response is missing {label}")
    return value.strip()


def asset(role: str, kind: str, data: dict[str, Any], ref_field: str) -> dict[str, Any]:
    """Project one safe seeded asset coordinate from a normal tool result."""
    return {
        "role": role,
        "assetKind": kind,
        "assetRef": required_text(data.get(ref_field), f"{role} ref"),
        "contractFingerprint": required_text(
            data.get("contractFingerprint", data.get("authoringReceiptFingerprint")),
            f"{role} fingerprint"),
        "revision": int(data.get("revision", 0)),
    }


def search_asset(api: HttpApi, role: str, asset_ref: str, intent: str) -> dict[str, Any]:
    """Read the index-assigned fingerprint for a graph draft seeded through compose."""
    data = api.mcp("rg.capability.search", {"query": {"intent": intent}, "limit": 20},
                   purpose="AGENT_TDD_READ", surface="BUSINESS_SOLUTION")
    candidates = data.get("candidates")
    if not isinstance(candidates, list):
        raise SetupFailure(f"setup search has no candidates for {role}")
    candidate = next((item for item in candidates
                      if isinstance(item, dict) and item.get("assetRef") == asset_ref), None)
    if candidate is None:
        raise SetupFailure(f"seeded asset is not recallable: {role}")
    return {
        "role": role,
        "assetKind": required_text(candidate.get("assetKind"), f"{role} kind"),
        "assetRef": asset_ref,
        "contractFingerprint": required_text(candidate.get("contractFingerprint"),
                                              f"{role} fingerprint"),
        "revision": int(candidate.get("revision", 0)),
    }


def start_journey(api: HttpApi, intent: str, goal: str, idempotency_key: str,
                  target_ref: str = "") -> str:
    """Start one normal server-navigated setup journey."""
    arguments = {"intentKind": intent, "businessGoal": goal,
                 "idempotencyKey": idempotency_key}
    if target_ref:
        arguments["targetRef"] = target_ref
    started = api.mcp("rg.journey.start", arguments,
                      purpose="AGENT_TDD_AUTHORING", surface="BUSINESS_SOLUTION")
    return required_text(started.get("journeyRef"), "setup journey")


def seed_feature(api: HttpApi, spec: dict[str, Any], patterns: str) -> dict[str, Any]:
    """Define one target Feature as the first action of its own governed journey."""
    journey_ref = start_journey(api, "CREATE_SOLUTION", spec["businessGoal"],
                                spec["idempotencyKey"] + "-journey")
    data = api.mcp("rg.feature.define", {
        "journeyRef": journey_ref, "expectedJourneyRevision": 1,
        "authoringPatternsFingerprint": patterns, "featureYaml": spec["featureYaml"],
        "idempotencyKey": spec["idempotencyKey"],
    }, purpose="AGENT_TDD_AUTHORING", surface="BUSINESS_SOLUTION")
    return asset(spec["role"], "FEATURE", data, "featureId")


def seed_instruction(api: HttpApi, spec: dict[str, Any], patterns: str) -> dict[str, Any]:
    """Reach the action-definition stage before defining one target Instruction."""
    journey_ref = start_journey(api, "CREATE_SOLUTION", spec["businessGoal"],
                                spec["idempotencyKey"] + "-journey")
    api.mcp("rg.feature.define", {
        "journeyRef": journey_ref, "expectedJourneyRevision": 1,
        "authoringPatternsFingerprint": patterns,
        "featureYaml": spec["prerequisiteFeatureYaml"],
        "idempotencyKey": spec["idempotencyKey"] + "-feature",
    }, purpose="AGENT_TDD_AUTHORING", surface="BUSINESS_SOLUTION")
    api.mcp("rg.scenario.define", {
        "journeyRef": journey_ref, "expectedJourneyRevision": 2,
        "authoringPatternsFingerprint": patterns,
        "scenarioYaml": spec["prerequisiteScenarioYaml"], "libraryRefs": [],
        "idempotencyKey": spec["idempotencyKey"] + "-scenario",
    }, purpose="AGENT_TDD_AUTHORING", surface="BUSINESS_SOLUTION")
    data = api.mcp("rg.instruction.define", {
        "journeyRef": journey_ref, "expectedJourneyRevision": 3,
        "authoringPatternsFingerprint": patterns,
        "instructionYaml": spec["instructionYaml"],
        "idempotencyKey": spec["idempotencyKey"],
    }, purpose="AGENT_TDD_AUTHORING", surface="BUSINESS_SOLUTION")
    return asset(spec["role"], "INSTRUCTION", data, "instructionId")


def seed_legacy(api: HttpApi, spec: dict[str, Any]) -> dict[str, Any]:
    """Create one pre-v2 Feature graph through reference, gate and compose."""
    references: list[str] = []
    reference = api.mcp("rg.dsl.reference.get", {"libraryRefs": references},
                        purpose="AGENT_TDD_READ", surface="PLATFORM_AUTHORING")
    context = required_text(reference.get("authoringContextFingerprint"), "DSL context")
    source = {"sourceId": spec["sourceId"], "dsl": spec["dsl"]}
    gate = api.mcp("rg.gate.check", {
        "source": source, "libraryRefs": references,
        "authoringContextFingerprint": context,
    }, purpose="AGENT_TDD_READ", surface="PLATFORM_AUTHORING")
    if gate.get("accepted") is not True:
        raise SetupFailure("legacy Feature setup was not accepted by the compiler gate")
    receipt = required_text(gate.get("authoringReceiptFingerprint"), "DSL receipt")
    api.mcp("rg.feature.compose", {
        "featureRef": spec["featureRef"], "graph": source, "libraryRefs": references,
        "authoringContextFingerprint": context,
        "authoringReceiptFingerprint": receipt,
        "idempotencyKey": spec["idempotencyKey"],
    }, purpose="AGENT_TDD_AUTHORING", surface="PLATFORM_AUTHORING")
    return search_asset(api, spec["role"], spec["featureRef"], spec["searchIntent"])


def seed_drift(api: HttpApi, spec: dict[str, Any], patterns: str) -> list[dict[str, Any]]:
    """Create, approve and then invalidate one independent business journey."""
    started = api.mcp("rg.journey.start", {
        "intentKind": "CREATE_SOLUTION", "businessGoal": spec["businessGoal"],
        "idempotencyKey": spec["idempotencyKey"] + "-journey",
    }, purpose="AGENT_TDD_AUTHORING", surface="BUSINESS_SOLUTION")
    journey_ref = required_text(started.get("journeyRef"), "drift journey")
    feature = api.mcp("rg.feature.define", {
        "journeyRef": journey_ref, "expectedJourneyRevision": 1,
        "authoringPatternsFingerprint": patterns, "featureYaml": spec["featureBeforeYaml"],
        "idempotencyKey": spec["idempotencyKey"] + "-feature-before",
    }, purpose="AGENT_TDD_AUTHORING", surface="BUSINESS_SOLUTION")
    api.mcp("rg.scenario.define", {
        "journeyRef": journey_ref, "expectedJourneyRevision": 2,
        "authoringPatternsFingerprint": patterns, "scenarioYaml": spec["scenarioYaml"],
        "libraryRefs": [], "idempotencyKey": spec["idempotencyKey"] + "-scenario",
    }, purpose="AGENT_TDD_AUTHORING", surface="BUSINESS_SOLUTION")
    api.mcp("rg.instruction.define", {
        "journeyRef": journey_ref, "expectedJourneyRevision": 3,
        "authoringPatternsFingerprint": patterns, "instructionYaml": spec["instructionYaml"],
        "idempotencyKey": spec["idempotencyKey"] + "-instruction",
    }, purpose="AGENT_TDD_AUTHORING", surface="BUSINESS_SOLUTION")
    composing = api.mcp("rg.journey.next", {
        "journeyRef": journey_ref, "expectedRevision": 4,
    }, purpose="AGENT_TDD_READ", surface="BUSINESS_SOLUTION")
    solution = api.mcp("rg.solution.compose", {
        "journeyRef": journey_ref, "expectedJourneyRevision": 4,
        "authoringPatternsFingerprint": patterns,
        "solutionContextFingerprint": required_text(
            composing.get("solutionContextFingerprint"), "drift solution context"),
        "solutionYaml": spec["solutionYaml"],
        "idempotencyKey": spec["idempotencyKey"] + "-solution",
    }, purpose="AGENT_TDD_AUTHORING", surface="BUSINESS_SOLUTION")
    proposed = api.mcp("rg.solution.golden.propose", {
        "journeyRef": journey_ref, "expectedJourneyRevision": 5,
        "solutionRef": required_text(solution.get("solutionRef"), "drift solution"),
        "cases": spec["cases"], "idempotencyKey": spec["idempotencyKey"] + "-golden",
    }, purpose="AGENT_TDD_AUTHORING", surface="BUSINESS_SOLUTION")
    case_set_ref = required_text(proposed.get("caseSetRef"), "drift case set")
    revision = int(proposed.get("revision", 1))
    for case in spec["cases"]:
        case_id = required_text(case.get("caseId"), "drift case")
        review = api.review_get(case_set_ref, case_id, revision)
        api.approve(case_set_ref, case_id, revision,
                    required_text(review.get("proposalFingerprint"), "proposal fingerprint"))
        revision += 1
    feature_ref = required_text(feature.get("featureId"), "drift feature")
    revise_journey = start_journey(
        api, "REVISE_SOLUTION", spec["revisionBusinessGoal"],
        spec["idempotencyKey"] + "-revision-journey", feature_ref)
    changed = api.mcp("rg.feature.define", {
        "journeyRef": revise_journey, "expectedJourneyRevision": 1,
        "authoringPatternsFingerprint": patterns, "featureYaml": spec["featureAfterYaml"],
        "idempotencyKey": spec["idempotencyKey"] + "-feature-after",
    }, purpose="AGENT_TDD_AUTHORING", surface="BUSINESS_SOLUTION")
    stale = api.mcp("rg.journey.next", {
        "journeyRef": journey_ref, "expectedRevision": 6,
    }, purpose="AGENT_TDD_READ", surface="BUSINESS_SOLUTION")
    if "GOLDEN_CASE_STALE" not in stale.get("blockingReasons", []):
        raise SetupFailure("semantic drift setup did not invalidate the approved GOLDEN case")
    return [
        asset(spec["featureRole"], "FEATURE", changed, "featureId"),
        {"role": spec["journeyRole"], "assetKind": "BUSINESS_JOURNEY",
         "assetRef": journey_ref,
         "contractFingerprint": required_text(
             stale.get("solutionContextFingerprint"), "drift journey fingerprint"),
         "revision": int(stale.get("revision", 6))},
        {"role": spec["caseSetRole"], "assetKind": "GOLDEN_CASE_SET",
         "assetRef": case_set_ref,
         "contractFingerprint": sha256({"caseSetRef": case_set_ref, "revision": revision,
                                        "caseIds": [case["caseId"] for case in spec["cases"]]}),
         "revision": revision},
    ]


def build_manifest(api: HttpApi, fixture: dict[str, Any]) -> dict[str, Any]:
    """Execute every seed through governed public APIs and bind actual relationships."""
    if fixture.get("schemaVersion") != "rg.businessRecallPlatformFixture.v1":
        raise SetupFailure("setup fixture has an unsupported schemaVersion")
    overview = api.mcp("rg.library.overview.get", {"includeSamples": False},
                       purpose="AGENT_TDD_READ", surface="BUSINESS_SOLUTION")
    patterns = required_text(overview.get("authoringPatternsFingerprint"), "authoring patterns")
    assets: list[dict[str, Any]] = []
    for spec in fixture.get("features", []):
        assets.append(seed_feature(api, spec, patterns))
    for spec in fixture.get("instructions", []):
        assets.append(seed_instruction(api, spec, patterns))
    assets.append(seed_legacy(api, fixture["legacyFeature"]))
    assets.extend(seed_drift(api, fixture["semanticDrift"], patterns))
    roles = [item["role"] for item in assets]
    if len(roles) != len(set(roles)) or set(roles) != REQUIRED_ROLES:
        raise SetupFailure("setup did not create the exact required asset roles")
    relationships = fixture.get("relationships")
    if not isinstance(relationships, dict):
        raise SetupFailure("setup fixture has no family relationships")
    used_roles = {role for family_roles in relationships.values()
                  if isinstance(family_roles, list) for role in family_roles}
    if not used_roles.issubset(REQUIRED_ROLES):
        raise SetupFailure("setup fixture relationship names an unknown asset role")
    material = {
        "fixtureFingerprint": sha256(fixture),
        "authoringPatternsFingerprint": patterns,
        "assets": sorted(assets, key=lambda item: item["role"]),
        "relationships": relationships,
    }
    return {"schemaVersion": SCHEMA_VERSION, **material,
            "setupFingerprint": sha256(material)}


def main() -> int:
    """CLI entry point; the output remains private and is reduced to HMAC evidence later."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoint", required=True)
    parser.add_argument("--author-token", required=True)
    parser.add_argument("--review-token", required=True)
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    fixture = json.loads(args.fixture.read_text(encoding="utf-8"))
    manifest = build_manifest(HttpApi(args.endpoint, args.author_token, args.review_token), fixture)
    args.output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (SetupFailure, OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as failure:
        print(f"Recall certification setup failed: {failure}", file=sys.stderr)
        raise SystemExit(1) from failure
