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
        if tool == "rg.capability.search":
            return {"candidates": [{"assetRef": "feature:旧版取消归责事实测试",
                                     "assetKind": "FEATURE",
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
        self.assertLess(preflight, setup)
        self.assertLess(setup, families)
    def setUp(self) -> None:
        self.fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))

    def test_builds_fingerprint_from_actual_seed_relationships(self) -> None:
        api = FakeApi()
        manifest = MODULE.build_manifest(api, self.fixture)

        self.assertEqual(MODULE.SCHEMA_VERSION, manifest["schemaVersion"])
        self.assertEqual(MODULE.REQUIRED_ROLES, {asset["role"] for asset in manifest["assets"]})
        material = {key: manifest[key] for key in (
            "fixtureFingerprint", "authoringPatternsFingerprint", "assets", "relationships")}
        self.assertEqual(MODULE.sha256(material), manifest["setupFingerprint"])

    def test_all_business_entity_writes_use_a_server_navigated_journey(self) -> None:
        api = FakeApi()
        MODULE.build_manifest(api, self.fixture)

        for tool, arguments, _purpose, surface in api.calls:
            if tool not in {"rg.feature.define", "rg.scenario.define", "rg.instruction.define",
                            "rg.solution.compose", "rg.solution.golden.propose"}:
                continue
            self.assertEqual("BUSINESS_SOLUTION", surface)
            self.assertRegex(arguments.get("journeyRef", ""), r"^journey:")
            self.assertIsInstance(arguments.get("expectedJourneyRevision"), int)
        revised = [arguments for tool, arguments, _purpose, _surface in api.calls
                   if tool == "rg.feature.define"
                   and arguments["idempotencyKey"].endswith("feature-after")]
        self.assertEqual(1, len(revised))
        self.assertNotEqual("journey:6", revised[0]["journeyRef"])

    def test_legacy_seed_uses_compiler_gate_and_platform_surface(self) -> None:
        api = FakeApi()
        MODULE.build_manifest(api, self.fixture)

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
            MODULE.build_manifest(NotStale(), self.fixture)


if __name__ == "__main__":
    unittest.main()
