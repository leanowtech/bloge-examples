#!/usr/bin/env python3
"""Behavior tests for the payload-free business Solution certification reducer."""

from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("business_solution_codex_trace_certificate.py")
SPEC = importlib.util.spec_from_file_location("business_certificate", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def call(server: str, tool: str, arguments: dict, data: dict, status: str = "completed") -> dict:
    return {"type": "item.completed", "item": {
        "type": "mcp_tool_call", "server": server, "tool": tool,
        "status": status, "arguments": arguments,
        "result": {"structuredContent": {"ok": status == "completed", "data": data}},
    }}


def valid_events() -> list[dict]:
    journey = "journey:test"
    context = "sha256:" + "a" * 64
    patterns = "sha256:" + "b" * 64
    events = [
        call("rg_author", "rg.journey.start",
             {"intentKind": "CREATE_SOLUTION", "businessGoal": "private", "idempotencyKey": "start"},
             {"journeyRef": journey, "revision": 1, "stage": "DISCOVERING",
              "surface": "BUSINESS_SOLUTION"}),
        call("rg_read", "rg.library.overview.get", {"includeSamples": False},
             {"buildingBlocks": [], "worldModel": {}, "authoringPatterns": {}, "samples": [],
              "snapshotFingerprint": context,
              "authoringPatternsFingerprint": patterns}),
        call("rg_read", "rg.capability.search", {"query": {"intent": "private"}},
             {"status": "NONE", "snapshotFingerprint": context, "candidates": [],
              "clarification": {"required": False, "dimension": "", "question": ""}}),
        call("rg_author", "rg.feature.define",
             {"journeyRef": journey, "expectedJourneyRevision": 1,
              "authoringPatternsFingerprint": patterns,
              "featureYaml": "private", "idempotencyKey": "feature"},
             {"featureId": "feature:test"}),
        call("rg_read", "rg.journey.next", {"journeyRef": journey, "expectedRevision": 2},
             {"journeyRef": journey, "revision": 2, "stage": "DEFINING_RULES",
              "surface": "BUSINESS_SOLUTION", "solutionContextFingerprint": ""}),
        call("rg_author", "rg.scenario.define",
             {"journeyRef": journey, "expectedJourneyRevision": 2,
              "authoringPatternsFingerprint": patterns,
              "scenarioYaml": "private", "libraryRefs": [], "idempotencyKey": "scenario"},
             {"scenarioId": "scenario:test"}),
        call("rg_author", "rg.instruction.define",
             {"journeyRef": journey, "expectedJourneyRevision": 3,
              "authoringPatternsFingerprint": patterns,
              "instructionYaml": "private", "idempotencyKey": "instruction"},
             {"instructionId": "instruction:test"}),
        call("rg_read", "rg.journey.next", {"journeyRef": journey, "expectedRevision": 4},
             {"journeyRef": journey, "revision": 4, "stage": "COMPOSING",
              "surface": "BUSINESS_SOLUTION", "solutionContextFingerprint": context}),
        call("rg_author", "rg.solution.compose",
             {"journeyRef": journey, "expectedJourneyRevision": 4,
              "authoringPatternsFingerprint": patterns,
              "solutionContextFingerprint": context, "solutionYaml": "private",
              "idempotencyKey": "solution"},
             {"solutionRef": "solution:test", "contractFingerprint": "sha256:" + "d" * 64}),
        call("rg_author", "rg.solution.golden.propose",
             {"journeyRef": journey, "expectedJourneyRevision": 5,
              "solutionRef": "solution:test", "idempotencyKey": "golden", "cases": [
                  {"caseId": "case-a", "private": "payload"},
                  {"caseId": "case-b", "private": "payload"},
              ]},
             {"caseSetRef": "case-set:test", "proposalStatus": "PENDING", "caseSummaries": [
                 {"caseId": "case-a"}, {"caseId": "case-b"},
             ]}),
        call("rg_read", "rg.solution.golden.list",
             {"journeyRef": journey, "solutionRef": "solution:test"},
             {"caseSetRef": "case-set:test", "approvalState": "PENDING", "caseSummaries": [
                 {"caseId": "case-a"}, {"caseId": "case-b"},
             ]}),
        {"type": "item.completed", "item": {
            "type": "agent_message", "text": "业务事实、规则和两条标准案例已经提交，请业务负责人确认。"}},
        {"type": "turn.completed"},
    ]
    return events


def metadata() -> dict:
    return {
        "repositoryCommit": "a" * 40,
        "codexVersion": "codex 1.0",
        "certifiedAt": "2026-09-05T00:00:00Z",
        "exitCode": 0,
        "runtimeInstanceNonce": "b" * 64,
        "runtimeJarSha256": "sha256:" + "c" * 64,
        "boardProjection": {"payloadPolicy": "STRUCTURE_ONLY", "pendingReviews": [
            {"kind": "ORACLE", "assetRef": "case-set:test", "caseId": "case-a"},
            {"kind": "ORACLE", "assetRef": "case-set:test", "caseId": "case-b"},
        ]},
    }


class BusinessSolutionCertificateTest(unittest.TestCase):
    def certify(self, events: list[dict] | None = None) -> dict:
        with tempfile.TemporaryDirectory() as directory:
            trace = Path(directory, "trace.jsonl")
            trace.write_text("".join(json.dumps(event, ensure_ascii=False) + "\n"
                                     for event in (events or valid_events())), encoding="utf-8")
            return MODULE.certify(trace, metadata())

    def test_certifies_one_correlated_business_journey_without_payload(self) -> None:
        certificate = self.certify()
        self.assertEqual("CERTIFIED", certificate["result"])
        self.assertEqual("rg.businessRecallCertification.v1", certificate["schemaVersion"])
        self.assertEqual(2, len(certificate["correlation"]["cases"]))
        self.assertRegex(certificate["correlation"]["authoringPatterns"], r"^hmac-sha256:")
        self.assertTrue(certificate["assertions"]
                        ["compilerValidatedAuthoringPatternsObservedBeforeCreation"])
        self.assertTrue(certificate["assertions"]["fourEntityWritesBoundToAuthoringPatterns"])
        self.assertNotIn("private", json.dumps(certificate))
        self.assertIsNone(certificate["metrics"]["recallAt3"])

    def test_rejects_stale_journey_revision(self) -> None:
        events = valid_events()
        events[5]["item"]["arguments"]["expectedJourneyRevision"] = 1
        with self.assertRaisesRegex(MODULE.CertificationFailure, "monotonic"):
            self.certify(events)

    def test_rejects_missing_authoring_template_fingerprint(self) -> None:
        events = valid_events()
        events[1]["item"]["result"]["structuredContent"]["data"].pop(
            "authoringPatternsFingerprint")
        with self.assertRaisesRegex(MODULE.CertificationFailure, "authoring patterns"):
            self.certify(events)

    def test_rejects_templates_observed_after_entity_creation(self) -> None:
        events = valid_events()
        overview = events.pop(1)
        events.insert(4, overview)
        with self.assertRaisesRegex(MODULE.CertificationFailure, "before entity creation"):
            self.certify(events)

    def test_rejects_entity_write_without_the_observed_template_context(self) -> None:
        events = valid_events()
        events[5]["item"]["arguments"].pop("authoringPatternsFingerprint")
        with self.assertRaisesRegex(MODULE.CertificationFailure, "not bound"):
            self.certify(events)

    def test_rejects_entity_write_with_a_different_template_context(self) -> None:
        events = valid_events()
        events[6]["item"]["arguments"]["authoringPatternsFingerprint"] = "sha256:" + "c" * 64
        with self.assertRaisesRegex(MODULE.CertificationFailure, "not bound"):
            self.certify(events)

    def test_rejects_cross_journey_asset(self) -> None:
        events = valid_events()
        events[6]["item"]["arguments"]["journeyRef"] = "journey:other"
        with self.assertRaisesRegex(MODULE.CertificationFailure, "another journey"):
            self.certify(events)

    def test_rejects_platform_authoring_escape(self) -> None:
        events = valid_events()
        events.insert(-2, call("rg_author", "rg.dsl.preview", {}, {}))
        with self.assertRaisesRegex(MODULE.CertificationFailure, "escaped"):
            self.certify(events)

    def test_allows_codex_resource_discovery_as_passive_protocol_work(self) -> None:
        events = valid_events()
        events.insert(-2, call("codex", "list_mcp_resources", {}, {}, status="completed"))
        certificate = self.certify(events)
        self.assertEqual(0, certificate["metrics"]["unsafeEscapeCount"])

    def test_rejects_business_tool_on_the_wrong_server(self) -> None:
        events = valid_events()
        feature = next(event for event in events
                       if event.get("item", {}).get("tool") == "rg.feature.define")
        feature["item"]["server"] = "rg_read"
        with self.assertRaisesRegex(MODULE.CertificationFailure, "escaped"):
            self.certify(events)

    def test_rejects_solution_case_set_mismatch(self) -> None:
        events = valid_events()
        events[9]["item"]["arguments"]["solutionRef"] = "solution:other"
        with self.assertRaisesRegex(MODULE.CertificationFailure, "not bound"):
            self.certify(events)

    def test_rejects_missing_business_read_back(self) -> None:
        events = [event for event in valid_events()
                  if event.get("item", {}).get("tool") != "rg.solution.golden.list"]
        with self.assertRaisesRegex(MODULE.CertificationFailure, "not read back"):
            self.certify(events)

    def test_rejects_technical_final_summary(self) -> None:
        events = valid_events()
        events[-2]["item"]["text"] = "YAML 和 schema 已经提交。"
        with self.assertRaisesRegex(MODULE.CertificationFailure, "technical"):
            self.certify(events)

    def test_rejects_non_mcp_external_action(self) -> None:
        events = valid_events()
        events.insert(-2, {"type": "item.completed", "item": {"type": "command_execution"}})
        with self.assertRaisesRegex(MODULE.CertificationFailure, "non-MCP"):
            self.certify(events)


if __name__ == "__main__":
    unittest.main()
