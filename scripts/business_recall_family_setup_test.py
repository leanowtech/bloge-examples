#!/usr/bin/env python3
"""Unit tests for governed business-recall certification setup."""

from __future__ import annotations

import importlib.util
import json
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("business_recall_family_setup.py")
SPEC = importlib.util.spec_from_file_location("business_recall_setup", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)
FIXTURE = Path(__file__).with_name("business-recall-platform-fixture-v1.json")
SUITE = Path(__file__).with_name("business-solution-recall-family-suite-v1.json")


class FakeApi:
    """Deterministic public-API double that records every requested envelope."""

    def __init__(self) -> None:
        self.calls: list[tuple[str, dict, str, str]] = []
        self.journey_number = 0

    def mcp(self, tool: str, arguments: dict, *, purpose: str, surface: str) -> dict:
        self.calls.append((tool, arguments, purpose, surface))
        if tool == "rg.library.overview.get":
            return {"authoringPatternsFingerprint": "sha256:" + "a" * 64}
        if tool == "rg.journey.start":
            self.journey_number += 1
            return {"journeyRef": f"journey:{self.journey_number}", "revision": 1}
        if tool == "rg.feature.define":
            ref = arguments["featureYaml"].split(":\n", 1)[0]
            return {"featureId": ref, "contractFingerprint": "sha256:" + "b" * 64,
                    "revision": 2 if "feature-after" in arguments["idempotencyKey"] else 1}
        if tool == "rg.instruction.define":
            ref = arguments["instructionYaml"].split(":\n", 1)[0]
            return {"instructionId": ref, "contractFingerprint": "sha256:" + "c" * 64,
                    "revision": 1}
        if tool == "rg.dsl.reference.get":
            return {"authoringContextFingerprint": "sha256:" + "d" * 64}
        if tool == "rg.gate.check":
            return {"accepted": True, "authoringReceiptFingerprint": "sha256:" + "e" * 64}
        if tool == "rg.feature.compose":
            return {"assetRef": arguments["featureRef"],
                    "authoringReceiptFingerprint": "sha256:" + "e" * 64, "revision": 1}
        if tool == "rg.entity.get":
            if arguments["assetRef"] == "feature:primary":
                return {"contractFingerprint": "sha256:" + "0" * 64,
                        "businessContract": {"businessDefinition": {
                    "semanticKey": "primary.cancel.party", "intent": "判断取消责任方"}}}
            return {"contractFingerprint": "sha256:" + "b" * 64,
                    "businessContract": {"businessDefinition": {
                "semanticKey": "test.cancel.party.duplicate", "intent": "判断取消责任方"}}}
        if tool == "rg.capability.search":
            query = arguments["query"]
            if query.get("semanticKey") == "primary.cancel.party":
                return {"status": "EXACT", "candidates": [
                    {"assetRef": "feature:primary", "assetKind": "FEATURE",
                     "contractFingerprint": "sha256:" + "0" * 64, "matchType": "EXACT"},
                    {"assetRef": "feature:traffic-accident-liability-test", "assetKind": "FEATURE",
                     "contractFingerprint": "sha256:" + "b" * 64, "matchType": "CONFLICT"}]}
            if query.get("semanticKey") == "test.cancel.party.duplicate":
                return {"status": "AMBIGUOUS", "candidates": [
                    {"assetRef": "feature:cancel-party-exact-test-a", "assetKind": "FEATURE",
                     "contractFingerprint": "sha256:" + "b" * 64, "matchType": "EXACT"},
                    {"assetRef": "feature:cancel-party-exact-test-b", "assetKind": "FEATURE",
                     "contractFingerprint": "sha256:" + "b" * 64, "matchType": "EXACT"}]}
            if query.get("intent") == "取消责任":
                return {"status": "INCOMPLETE", "candidates": [
                    {"assetRef": "feature:primary", "assetKind": "FEATURE",
                     "contractFingerprint": "sha256:" + "0" * 64, "matchType": "PARTIAL"},
                    {"assetRef": "feature:traffic-accident-liability-test", "assetKind": "FEATURE",
                     "contractFingerprint": "sha256:" + "b" * 64, "matchType": "PARTIAL"}]}
            if query.get("intent") == "执行退款":
                return {"status": "INCOMPLETE", "candidates": [
                    {"assetRef": "ins:refund-execution-ride-test", "assetKind": "INSTRUCTION",
                     "businessName": "退款执行", "contractFingerprint": "sha256:" + "c" * 64,
                     "matchType": "PARTIAL"},
                    {"assetRef": "ins:refund-execution-food-test", "assetKind": "INSTRUCTION",
                     "businessName": "退款执行", "contractFingerprint": "sha256:" + "c" * 64,
                     "matchType": "PARTIAL"}]}
            return {"status": "INCOMPLETE", "candidates": [
                {"assetRef": "feature:旧版取消归责事实测试", "assetKind": "FEATURE",
                 "contractFingerprint": "sha256:" + "f" * 64,
                 "revision": 1, "matchType": "PARTIAL"}]}
        if tool == "rg.scenario.define":
            return {"scenarioId": "scenario:test", "contractFingerprint": "sha256:" + "1" * 64,
                    "revision": 1}
        if tool == "rg.journey.next" and arguments["expectedRevision"] == 4:
            return {"journeyRef": arguments["journeyRef"],
                    "solutionContextFingerprint": "sha256:" + "2" * 64, "revision": 4}
        if tool == "rg.solution.compose":
            return {"solutionRef": "sol:cancel-drift-test",
                    "contractFingerprint": "sha256:" + "3" * 64, "revision": 1}
        if tool == "rg.solution.golden.propose":
            return {"caseSetRef": "caseSet:cancel-drift-test", "revision": 1,
                    "proposalStatus": "PENDING"}
        if tool == "rg.journey.next" and arguments["expectedRevision"] == 6:
            return {"journeyRef": arguments["journeyRef"], "revision": 6,
                    "solutionContextFingerprint": "sha256:" + "4" * 64,
                    "blockingReasons": ["GOLDEN_CASE_STALE"]}
        raise AssertionError(f"unexpected setup call: {tool} {arguments}")

    def review_get(self, case_set_ref: str, case_id: str, revision: int) -> dict:
        return {"proposalFingerprint": "sha256:" + "5" * 64}

    def approve(self, case_set_ref: str, case_id: str, revision: int,
                proposal_fingerprint: str) -> dict:
        return {"approvalState": "APPROVED"}


class BusinessRecallSetupTest(unittest.TestCase):

    def test_assumption_ambiguity_prompt_requires_discovery_without_revealing_the_fixture(self) -> None:
        suite = json.loads(SUITE.read_text(encoding="utf-8"))
        prompt = next(item["prompt"] for item in suite["families"]
                      if item["familyId"] == "assumption-ambiguity")

        self.assertIn("请先查找", prompt)
        self.assertNotIn("存在两个", prompt)
        self.assertNotIn("MCP", prompt)
        self.assertNotIn("Instruction", prompt)
        self.assertEqual("assumption-ambiguity", suite["families"][-1]["familyId"])
        self.assertLess(
            next(index for index, item in enumerate(suite["families"])
                 if item["familyId"] == "forbidden-dependency"),
            len(suite["families"]) - 1)

    def test_certification_authors_primary_solution_before_seeding_distractors(self):
        script = Path(__file__).with_name("certify-agent-tdd-codex.sh").read_text(
            encoding="utf-8")

        authoring = script.index('run_codex_turn "${PROMPT_FILE}" "${TRACE_FILE}"')
        board = script.index('curl --config "${BOARD_CURL_CONFIG}"')
        preflight = script.index("module.require_business_sequence(calls)")
        setup = script.index('python3 "${ROOT_DIR}/scripts/business_recall_family_setup.py"')
        families = script.index("while IFS=$'\\t' read -r FAMILY_ID FAMILY_EXPECTED")

        self.assertLess(authoring, board)
        self.assertLess(board, preflight)
        self.assertLess(preflight, families)
        self.assertLess(families, setup)
        self.assertIn('if [ "${FAMILY_ID}" = "near-meaning-distractor" ]', script)
        self.assertIn('if [ "${FAMILY_ID}" = "boundary-unspecified" ]', script)
        self.assertIn('if [ "${FAMILY_ID}" = "assumption-ambiguity" ]', script)
        self.assertIn('--phase near-meaning', script)
        self.assertIn('--phase remaining', script)
        self.assertIn('--phase ambiguity', script)
        self.assertIn('--primary-context "${PRIMARY_CONTEXT_FILE}"', script)
        self.assertIn('"runtimeInstanceNonce": runtime_nonce', script)
        self.assertIn('"${FAMILY_EXIT}" "${INSTANCE_NONCE}" >> "${FAMILY_RUN_INDEX}"', script)
        self.assertIn('"familyId": "synonym-rewrite"', script)

    def setUp(self) -> None:
        self.fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
        self.primary = {"assetRef": "feature:primary",
                        "contractFingerprint": "sha256:" + "0" * 64}

    def test_every_structured_seed_declares_business_display(self) -> None:
        structured_sources = []
        for feature in self.fixture["features"]:
            structured_sources.append(feature["featureYaml"])
        for instruction in self.fixture["instructions"]:
            structured_sources.extend([
                instruction["prerequisiteFeatureYaml"],
                instruction["prerequisiteScenarioYaml"],
                instruction["instructionYaml"],
            ])
        drift = self.fixture["semanticDrift"]
        structured_sources.extend(drift[name] for name in (
            "featureBeforeYaml", "featureAfterYaml", "scenarioYaml",
            "instructionYaml", "solutionYaml"))

        self.assertEqual(14, len(structured_sources))
        for source in structured_sources:
            self.assertIn("\n  display: {", source)
            self.assertIn("businessName:", source)
            self.assertIn("description:", source)

    def test_builds_fingerprint_from_actual_seed_relationships(self) -> None:
        api = FakeApi()
        near = MODULE.manifest(api, self.fixture, MODULE.PHASE_NEAR, primary=self.primary)
        remaining = MODULE.manifest(api, self.fixture, MODULE.PHASE_REMAINING, near)
        manifest = MODULE.manifest(api, self.fixture, MODULE.PHASE_AMBIGUITY, remaining)

        self.assertEqual(MODULE.SCHEMA_VERSION, manifest["schemaVersion"])
        self.assertEqual(MODULE.REQUIRED_ROLES, {asset["role"] for asset in manifest["assets"]})
        material = {key: manifest[key] for key in (
            "fixtureFingerprint", "authoringPatternsFingerprint", "completedPhases", "assets",
            "relationships", "preflights")}
        self.assertEqual(MODULE.sha256(material), manifest["setupFingerprint"])
        self.assertEqual([MODULE.PHASE_NEAR, MODULE.PHASE_REMAINING,
                          MODULE.PHASE_AMBIGUITY],
                         manifest["completedPhases"])
        self.assertEqual(5, len(manifest["preflights"]))

    def test_remaining_phase_does_not_seed_assumption_ambiguity_actions(self) -> None:
        api = FakeApi()
        near = MODULE.manifest(api, self.fixture, MODULE.PHASE_NEAR, primary=self.primary)
        remaining = MODULE.manifest(api, self.fixture, MODULE.PHASE_REMAINING, near)

        roles = {item["role"] for item in remaining["assets"]}
        self.assertNotIn("assumptionAmbiguityA", roles)
        self.assertNotIn("assumptionAmbiguityB", roles)
        self.assertNotIn("assumption-ambiguity",
                         {item["familyId"] for item in remaining["preflights"]})
        instruction_keys = [arguments["idempotencyKey"]
                            for tool, arguments, _purpose, _surface in api.calls
                            if tool == "rg.instruction.define"]
        self.assertFalse(any(key.startswith("recall-seed-refund-")
                             for key in instruction_keys))

    def test_legacy_preflight_accepts_its_partial_candidate_among_ambiguous_results(self) -> None:
        class AmbiguousLegacyApi(FakeApi):
            def mcp(self, tool: str, arguments: dict, *, purpose: str, surface: str) -> dict:
                result = super().mcp(tool, arguments, purpose=purpose, surface=surface)
                if tool == "rg.capability.search" and arguments["query"].get("intent") == "取消归责":
                    return {**result, "status": "AMBIGUOUS"}
                return result

        api = AmbiguousLegacyApi()
        near = MODULE.manifest(api, self.fixture, MODULE.PHASE_NEAR, primary=self.primary)
        manifest = MODULE.manifest(api, self.fixture, MODULE.PHASE_REMAINING, near)
        legacy = next(item for item in manifest["preflights"]
                      if item["familyId"] == "legacy-feature-partial")

        self.assertEqual("AMBIGUOUS", legacy["status"])
        self.assertEqual("PARTIAL_VISIBLE", legacy["outcome"])

    def test_legacy_preflight_rejects_a_seed_that_is_not_partial(self) -> None:
        class ExactLegacyApi(FakeApi):
            def mcp(self, tool: str, arguments: dict, *, purpose: str, surface: str) -> dict:
                result = super().mcp(tool, arguments, purpose=purpose, surface=surface)
                if tool == "rg.capability.search" and arguments["query"].get("intent") == "取消归责":
                    candidates = [{**candidate, "matchType": "EXACT"}
                                  for candidate in result["candidates"]]
                    return {**result, "status": "AMBIGUOUS", "candidates": candidates}
                return result

        with self.assertRaisesRegex(MODULE.SetupFailure, "did not observe its PARTIAL seed"):
            api = ExactLegacyApi()
            near = MODULE.manifest(api, self.fixture, MODULE.PHASE_NEAR, primary=self.primary)
            MODULE.manifest(api, self.fixture, MODULE.PHASE_REMAINING, near)

    def test_assumption_preflight_accepts_two_same_name_actions_in_ambiguous_results(self) -> None:
        class AmbiguousAssumptionApi(FakeApi):
            def mcp(self, tool: str, arguments: dict, *, purpose: str, surface: str) -> dict:
                result = super().mcp(tool, arguments, purpose=purpose, surface=surface)
                if tool == "rg.capability.search" and arguments["query"].get("intent") == "执行退款":
                    return {**result, "status": "AMBIGUOUS"}
                return result

        api = AmbiguousAssumptionApi()
        near = MODULE.manifest(api, self.fixture, MODULE.PHASE_NEAR, primary=self.primary)
        remaining = MODULE.manifest(api, self.fixture, MODULE.PHASE_REMAINING, near)
        manifest = MODULE.manifest(api, self.fixture, MODULE.PHASE_AMBIGUITY, remaining)
        assumption = next(item for item in manifest["preflights"]
                          if item["familyId"] == "assumption-ambiguity")

        self.assertEqual("AMBIGUOUS", assumption["status"])
        self.assertEqual("SAME_NAME_VISIBLE", assumption["outcome"])

    def test_near_phase_seeds_only_its_distractor_and_runs_preflight(self) -> None:
        api = FakeApi()
        near = MODULE.manifest(api, self.fixture, MODULE.PHASE_NEAR, primary=self.primary)

        self.assertEqual({"nearMeaningDistractor"}, {item["role"] for item in near["assets"]})
        self.assertEqual([MODULE.PHASE_NEAR], near["completedPhases"])
        self.assertEqual("SEMANTIC_TOP1", near["preflights"][0]["outcome"])
        seeded_feature_keys = [arguments["idempotencyKey"] for tool, arguments, _purpose, _surface
                               in api.calls if tool == "rg.feature.define"]
        self.assertEqual(["recall-seed-traffic-liability-v1"], seeded_feature_keys)

    def test_all_business_entity_writes_use_a_server_navigated_journey(self) -> None:
        api = FakeApi()
        near = MODULE.manifest(api, self.fixture, MODULE.PHASE_NEAR, primary=self.primary)
        remaining = MODULE.manifest(api, self.fixture, MODULE.PHASE_REMAINING, near)
        MODULE.manifest(api, self.fixture, MODULE.PHASE_AMBIGUITY, remaining)

        for tool, arguments, _purpose, surface in api.calls:
            if tool not in {"rg.feature.define", "rg.scenario.define", "rg.instruction.define",
                            "rg.solution.compose", "rg.solution.golden.propose"}:
                continue
            self.assertEqual("BUSINESS_SOLUTION", surface)
            self.assertRegex(arguments.get("journeyRef", ""), r"^journey:")
            self.assertIsInstance(arguments.get("expectedJourneyRevision"), int)
        preflight_kinds = [arguments.get("assetKinds") for tool, arguments, _purpose, _surface
                           in api.calls if tool == "rg.capability.search"
                           and arguments.get("query", {}).get("intent") in {"取消归责", "执行退款"}
                           and arguments.get("assetKinds")]
        self.assertEqual([["FEATURE"], ["INSTRUCTION"]], preflight_kinds)
        revised = [arguments for tool, arguments, _purpose, _surface in api.calls
                   if tool == "rg.feature.define"
                   and arguments["idempotencyKey"].endswith("feature-after")]
        self.assertEqual(1, len(revised))
        self.assertNotEqual("journey:6", revised[0]["journeyRef"])

    def test_legacy_seed_uses_compiler_gate_and_platform_surface(self) -> None:
        api = FakeApi()
        near = MODULE.manifest(api, self.fixture, MODULE.PHASE_NEAR, primary=self.primary)
        MODULE.manifest(api, self.fixture, MODULE.PHASE_REMAINING, near)

        relevant = [(tool, purpose, surface) for tool, _arguments, purpose, surface in api.calls
                    if tool in {"rg.dsl.reference.get", "rg.gate.check", "rg.feature.compose"}]
        self.assertEqual([
            ("rg.dsl.reference.get", "AGENT_TDD_READ", "PLATFORM_AUTHORING"),
            ("rg.gate.check", "AGENT_TDD_READ", "PLATFORM_AUTHORING"),
            ("rg.feature.compose", "AGENT_TDD_AUTHORING", "PLATFORM_AUTHORING"),
        ], relevant)

    def test_rejects_drift_setup_without_stale_golden_result(self) -> None:
        class NotStale(FakeApi):
            def mcp(self, tool: str, arguments: dict, *, purpose: str, surface: str) -> dict:
                result = super().mcp(tool, arguments, purpose=purpose, surface=surface)
                if tool == "rg.journey.next" and arguments.get("expectedRevision") == 6:
                    return {**result, "blockingReasons": []}
                return result

        with self.assertRaisesRegex(MODULE.SetupFailure, "did not invalidate"):
            api = NotStale()
            near = MODULE.manifest(api, self.fixture, MODULE.PHASE_NEAR, primary=self.primary)
            MODULE.manifest(api, self.fixture, MODULE.PHASE_REMAINING, near)

    def test_remaining_phase_rejects_repeated_seed_roles(self) -> None:
        api = FakeApi()
        near = MODULE.manifest(api, self.fixture, MODULE.PHASE_NEAR, primary=self.primary)
        near["assets"].append(dict(near["assets"][0]))

        with self.assertRaisesRegex(MODULE.SetupFailure, "only the near seed"):
            MODULE.manifest(api, self.fixture, MODULE.PHASE_REMAINING, near)

    def test_remaining_phase_rejects_a_tampered_near_manifest(self) -> None:
        api = FakeApi()
        near = MODULE.manifest(api, self.fixture, MODULE.PHASE_NEAR, primary=self.primary)
        near["setupFingerprint"] = "sha256:" + "9" * 64

        with self.assertRaisesRegex(MODULE.SetupFailure, "near setup fingerprint"):
            MODULE.manifest(api, self.fixture, MODULE.PHASE_REMAINING, near)

    def test_ambiguity_phase_requires_the_completed_remaining_manifest(self) -> None:
        api = FakeApi()
        near = MODULE.manifest(api, self.fixture, MODULE.PHASE_NEAR, primary=self.primary)

        with self.assertRaisesRegex(MODULE.SetupFailure, "ambiguity setup requires"):
            MODULE.manifest(api, self.fixture, MODULE.PHASE_AMBIGUITY, near)

    def test_near_phase_rejects_a_primary_coordinate_not_bound_to_main_trace(self) -> None:
        with self.assertRaisesRegex(MODULE.SetupFailure, "changed after the primary"):
            MODULE.manifest(FakeApi(), self.fixture, MODULE.PHASE_NEAR, primary={
                "assetRef": "feature:primary", "contractFingerprint": "sha256:" + "9" * 64,
            })


if __name__ == "__main__":
    unittest.main()
