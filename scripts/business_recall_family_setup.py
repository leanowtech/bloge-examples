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
PHASE_NEAR = "near-meaning"
PHASE_REMAINING = "remaining"
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


def candidates_for(api: HttpApi, query: dict[str, Any]) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """Search one business query and return only structurally valid candidates."""
    data = api.mcp("rg.capability.search", {"query": query, "limit": 100},
                   purpose="AGENT_TDD_READ", surface="BUSINESS_SOLUTION")
    candidates = [item for item in data.get("candidates", []) if isinstance(item, dict)]
    return data, candidates


def matches_asset(candidate: dict[str, Any], seeded: dict[str, Any]) -> bool:
    """Compare an observed candidate with one actual seed coordinate."""
    return (candidate.get("assetRef"), candidate.get("contractFingerprint")) == (
        seeded["assetRef"], seeded["contractFingerprint"])


def entity_business_query(api: HttpApi, asset_ref: str) -> dict[str, Any]:
    """Read the server-stored business definition used for an exact-match preflight."""
    data = api.mcp("rg.entity.get", {"assetRef": asset_ref},
                   purpose="AGENT_TDD_READ", surface="BUSINESS_SOLUTION")
    contract = data.get("businessContract")
    if not isinstance(contract, dict):
        raise SetupFailure("preflight entity has no business contract")
    definition = contract.get("businessDefinition", contract)
    if not isinstance(definition, dict) or not definition:
        raise SetupFailure("preflight entity has no business definition")
    return definition


def preflight_near(api: HttpApi, by_role: dict[str, dict[str, Any]],
                   primary: dict[str, Any]) -> dict[str, Any]:
    """Prove semantic match rank beats the near-domain distractor before Codex sees it."""
    seeded = by_role["nearMeaningDistractor"]
    target_ref = required_text(primary.get("assetRef"), "near preflight target")
    target_fingerprint = required_text(
        primary.get("contractFingerprint"), "near preflight target fingerprint")
    entity = api.mcp("rg.entity.get", {"assetRef": target_ref},
                     purpose="AGENT_TDD_READ", surface="BUSINESS_SOLUTION")
    if entity.get("contractFingerprint") != target_fingerprint:
        raise SetupFailure("near preflight target changed after the primary Codex trace")
    data, exact = candidates_for(api, entity_business_query(api, target_ref))
    seeded_candidate = next((item for item in exact if matches_asset(item, seeded)), None)
    if data.get("status") != "EXACT" \
            or not exact or exact[0].get("assetRef") != target_ref \
            or exact[0].get("matchType") != "EXACT" \
            or exact[0].get("contractFingerprint") != target_fingerprint \
            or seeded_candidate is None or seeded_candidate.get("matchType") == "EXACT":
        target = next((item for item in exact if item.get("assetRef") == target_ref), {})
        raise SetupFailure(
            "near preflight is not ranked by semantic match class: "
            f"status={data.get('status', 'NONE')},"
            f"targetRank={next((index + 1 for index, item in enumerate(exact) if item.get('assetRef') == target_ref), 0)},"
            f"targetMatch={target.get('matchType', 'NONE')},"
            f"distractorSeen={seeded_candidate is not None},"
            f"distractorMatch={(seeded_candidate or {}).get('matchType', 'NONE')}"
        )
    return {
        "familyId": "near-meaning-distractor", "status": data.get("status"),
        "observedRoles": ["nearMeaningDistractor"], "outcome": "SEMANTIC_TOP1",
        "target": {"assetRef": target_ref,
                   "contractFingerprint": target_fingerprint,
                   "matchType": "EXACT"},
    }


def preflight_remaining(api: HttpApi, fixture: dict[str, Any],
                        by_role: dict[str, dict[str, Any]]) -> list[dict[str, Any]]:
    """Prove every remaining adversarial seed is visible with its intended server outcome."""
    exact_a = by_role["multipleExactA"]
    exact_b = by_role["multipleExactB"]
    exact_data, exact_candidates = candidates_for(
        api, entity_business_query(api, exact_a["assetRef"]))
    if exact_data.get("status") != "AMBIGUOUS" or not all(
            any(matches_asset(candidate, seed) and candidate.get("matchType") == "EXACT"
                for candidate in exact_candidates) for seed in (exact_a, exact_b)):
        raise SetupFailure("multiple-exact preflight did not observe both exact seeds")

    legacy = by_role["legacyPartial"]
    legacy_data, legacy_candidates = candidates_for(
        api, fixture["preflightQueries"]["legacy-feature-partial"])
    if legacy_data.get("status") not in {"INCOMPLETE", "AMBIGUOUS"} \
            or not any(matches_asset(candidate, legacy) and candidate.get("matchType") == "PARTIAL"
                       for candidate in legacy_candidates):
        raise SetupFailure("legacy preflight did not observe its PARTIAL seed")

    assumption_seeds = [by_role["assumptionAmbiguityA"], by_role["assumptionAmbiguityB"]]
    assumption_data, assumption_candidates = candidates_for(
        api, fixture["preflightQueries"]["assumption-ambiguity"])
    observed_assumptions = [candidate for candidate in assumption_candidates
                            if any(matches_asset(candidate, seed) for seed in assumption_seeds)]
    if assumption_data.get("status") != "INCOMPLETE" \
            or len(observed_assumptions) != 2 \
            or len({candidate.get("businessName") for candidate in observed_assumptions}) != 1:
        raise SetupFailure("assumption preflight did not observe two same-name seeded actions")

    drift_journey = by_role["semanticDriftJourney"]
    drift_data = api.mcp("rg.journey.next", {
        "journeyRef": drift_journey["assetRef"], "expectedRevision": drift_journey["revision"],
    }, purpose="AGENT_TDD_READ", surface="BUSINESS_SOLUTION")
    if "GOLDEN_CASE_STALE" not in drift_data.get("blockingReasons", []):
        raise SetupFailure("drift preflight did not observe GOLDEN_CASE_STALE")

    return [
        {"familyId": "multiple-exact", "status": exact_data.get("status"),
         "observedRoles": ["multipleExactA", "multipleExactB"],
         "outcome": "AMBIGUOUS_EXACT", "target": None},
        {"familyId": "legacy-feature-partial", "status": legacy_data.get("status"),
         "observedRoles": ["legacyPartial"], "outcome": "PARTIAL_VISIBLE", "target": None},
        {"familyId": "assumption-ambiguity", "status": assumption_data.get("status"),
         "observedRoles": ["assumptionAmbiguityA", "assumptionAmbiguityB"],
         "outcome": "SAME_NAME_VISIBLE", "target": None},
        {"familyId": "semantic-drift", "status": "STALE",
         "observedRoles": ["semanticDriftFeature", "semanticDriftJourney",
                           "semanticDriftCaseSet"],
         "outcome": "GOLDEN_CASE_STALE", "target": None},
    ]


def validate_near_manifest(existing: dict[str, Any], fixture_fingerprint: str) -> None:
    """Reject a changed or replay-shaped near manifest before the remaining seed begins."""
    required = {"schemaVersion", "fixtureFingerprint", "authoringPatternsFingerprint",
                "completedPhases", "assets", "relationships", "preflights", "setupFingerprint"}
    if not isinstance(existing, dict) or set(existing) != required \
            or existing.get("schemaVersion") != SCHEMA_VERSION \
            or existing.get("fixtureFingerprint") != fixture_fingerprint \
            or existing.get("completedPhases") != [PHASE_NEAR] \
            or existing.get("relationships") != {
                "near-meaning-distractor": ["nearMeaningDistractor"]}:
        raise SetupFailure("existing setup manifest is not the isolated near phase")
    assets = existing.get("assets")
    preflights = existing.get("preflights")
    if not isinstance(assets, list) or len(assets) != 1 \
            or assets[0].get("role") != "nearMeaningDistractor" \
            or not isinstance(preflights, list) or len(preflights) != 1 \
            or preflights[0].get("familyId") != "near-meaning-distractor" \
            or preflights[0].get("outcome") != "SEMANTIC_TOP1":
        raise SetupFailure("existing setup manifest does not contain only the near seed")
    material = {key: existing[key] for key in (
        "fixtureFingerprint", "authoringPatternsFingerprint", "completedPhases", "assets",
        "relationships", "preflights")}
    if existing.get("setupFingerprint") != sha256(material):
        raise SetupFailure("existing near setup fingerprint is invalid")


def manifest(api: HttpApi, fixture: dict[str, Any], phase: str,
             existing: dict[str, Any] | None = None,
             primary: dict[str, Any] | None = None) -> dict[str, Any]:
    """Seed one isolated phase and bind the accumulated actual asset relationships."""
    if fixture.get("schemaVersion") != "rg.businessRecallPlatformFixture.v1":
        raise SetupFailure("setup fixture has an unsupported schemaVersion")
    if phase not in {PHASE_NEAR, PHASE_REMAINING}:
        raise SetupFailure("setup phase is unsupported")
    fixture_fingerprint = sha256(fixture)
    existing_assets: list[dict[str, Any]] = []
    existing_preflights: list[dict[str, Any]] = []
    completed: list[str] = []
    if existing is not None:
        validate_near_manifest(existing, fixture_fingerprint)
        existing_assets = list(existing.get("assets", []))
        existing_preflights = list(existing.get("preflights", []))
        completed = list(existing.get("completedPhases", []))
    if phase == PHASE_NEAR and existing is not None:
        raise SetupFailure("near setup cannot merge an existing manifest")
    if phase == PHASE_REMAINING and completed != [PHASE_NEAR]:
        raise SetupFailure("remaining setup requires exactly one completed near phase")
    overview = api.mcp("rg.library.overview.get", {"includeSamples": False},
                       purpose="AGENT_TDD_READ", surface="BUSINESS_SOLUTION")
    patterns = required_text(overview.get("authoringPatternsFingerprint"), "authoring patterns")
    if existing is not None and existing.get("authoringPatternsFingerprint") != patterns:
        raise SetupFailure("authoring patterns changed between setup phases")
    assets = list(existing_assets)
    selected_features = [spec for spec in fixture.get("features", [])
                         if (phase == PHASE_NEAR) == (spec.get("role") == "nearMeaningDistractor")]
    for spec in selected_features:
        assets.append(seed_feature(api, spec, patterns))
    if phase == PHASE_REMAINING:
        for spec in fixture.get("instructions", []):
            assets.append(seed_instruction(api, spec, patterns))
        assets.append(seed_legacy(api, fixture["legacyFeature"]))
        assets.extend(seed_drift(api, fixture["semanticDrift"], patterns))
    roles = [item["role"] for item in assets]
    expected_roles = {"nearMeaningDistractor"} if phase == PHASE_NEAR else REQUIRED_ROLES
    if len(roles) != len(set(roles)) or set(roles) != expected_roles:
        raise SetupFailure("setup phase did not create the exact required asset roles")
    all_relationships = fixture.get("relationships")
    if not isinstance(all_relationships, dict):
        raise SetupFailure("setup fixture has no family relationships")
    relationships = ({"near-meaning-distractor": ["nearMeaningDistractor"]}
                     if phase == PHASE_NEAR else all_relationships)
    used_roles = {role for family_roles in relationships.values()
                  if isinstance(family_roles, list) for role in family_roles}
    if not used_roles.issubset(expected_roles):
        raise SetupFailure("setup fixture relationship names an unknown asset role")
    by_role = {item["role"]: item for item in assets}
    preflights = list(existing_preflights)
    if phase == PHASE_NEAR:
        if primary is None:
            raise SetupFailure("near setup requires the primary trace Feature coordinate")
        preflights.append(preflight_near(api, by_role, primary))
    else:
        preflights.extend(preflight_remaining(api, fixture, by_role))
    completed.append(phase)
    material = {
        "fixtureFingerprint": fixture_fingerprint,
        "authoringPatternsFingerprint": patterns,
        "completedPhases": completed,
        "assets": sorted(assets, key=lambda item: item["role"]),
        "relationships": relationships,
        "preflights": preflights,
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
    parser.add_argument("--phase", choices=[PHASE_NEAR, PHASE_REMAINING], required=True)
    parser.add_argument("--existing-manifest", type=Path)
    parser.add_argument("--primary-context", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    fixture = json.loads(args.fixture.read_text(encoding="utf-8"))
    existing = None
    if args.existing_manifest is not None:
        existing = json.loads(args.existing_manifest.read_text(encoding="utf-8"))
    primary = None
    if args.primary_context is not None:
        primary = json.loads(args.primary_context.read_text(encoding="utf-8"))
    result = manifest(HttpApi(args.endpoint, args.author_token, args.review_token),
                      fixture, args.phase, existing, primary)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (SetupFailure, OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as failure:
        print(f"Recall certification setup failed: {failure}", file=sys.stderr)
        raise SystemExit(1) from failure
