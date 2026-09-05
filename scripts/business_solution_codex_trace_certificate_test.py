#!/usr/bin/env python3
"""Behavior tests for the payload-free business Solution certification reducer."""

from __future__ import annotations

import importlib.util
import hashlib
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("business_solution_codex_trace_certificate.py")
SPEC = importlib.util.spec_from_file_location("business_certificate", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)
MAIN_RUNTIME_NONCE = "1" * 64
NEAR_RUNTIME_NONCE = "2" * 64
REMAINING_RUNTIME_NONCE = "3" * 64


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
            {"journeyRef": journey, "revision": 1, "stage": "DEFINING_FEATURES",
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
              "featureYaml": "feature:test:\n  display: { businessName: test, description: test }",
              "idempotencyKey": "feature"},
             {"featureId": "feature:test", "contractFingerprint": "sha256:" + "e" * 64}),
        call("rg_read", "rg.journey.next", {"journeyRef": journey, "expectedRevision": 2},
             {"journeyRef": journey, "revision": 2, "stage": "DEFINING_RULES",
              "surface": "BUSINESS_SOLUTION", "solutionContextFingerprint": ""}),
        call("rg_author", "rg.scenario.define",
             {"journeyRef": journey, "expectedJourneyRevision": 2,
              "authoringPatternsFingerprint": patterns,
              "scenarioYaml": "scenario:test:\n  display: { businessName: test, description: test }",
              "libraryRefs": [], "idempotencyKey": "scenario"},
             {"scenarioId": "scenario:test"}),
        call("rg_author", "rg.instruction.define",
             {"journeyRef": journey, "expectedJourneyRevision": 3,
              "authoringPatternsFingerprint": patterns,
              "instructionYaml": "instruction:test:\n  display: { businessName: test, description: test }",
              "idempotencyKey": "instruction"},
             {"instructionId": "instruction:test"}),
        call("rg_read", "rg.journey.next", {"journeyRef": journey, "expectedRevision": 4},
             {"journeyRef": journey, "revision": 4, "stage": "COMPOSING",
              "surface": "BUSINESS_SOLUTION", "solutionContextFingerprint": context}),
        call("rg_author", "rg.solution.compose",
             {"journeyRef": journey, "expectedJourneyRevision": 4,
              "authoringPatternsFingerprint": patterns,
              "solutionContextFingerprint": context,
              "solutionYaml": "solution:test:\n  display: { businessName: test, description: test }",
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
        {"type": "thread.started", "thread_id": "thread-authoring"},
        {"type": "turn.completed"},
    ]
    return events


def metadata() -> dict:
    return {
        "repositoryCommit": "a" * 40,
        "codexVersion": "codex 1.0",
        "certifiedAt": "2026-09-05T00:00:00Z",
        "exitCode": 0,
        "runtimeInstanceNonce": MAIN_RUNTIME_NONCE,
        "runtimeJarSha256": "sha256:" + "c" * 64,
        "productionTreeFingerprint": "sha256:" + "d" * 64,
        "boardProjection": {"payloadPolicy": "STRUCTURE_ONLY", "pendingReviews": [
            {"kind": "ORACLE", "assetRef": "case-set:test", "caseId": "case-a"},
            {"kind": "ORACLE", "assetRef": "case-set:test", "caseId": "case-b"},
        ]},
    }


def valid_recall_events() -> list[dict]:
    return [
        call("rg_read", "rg.capability.search", {"query": {"intent": "private"}}, {
            "status": "INCOMPLETE",
            "candidates": [{
                "assetRef": "feature:test",
                "contractFingerprint": "sha256:" + "e" * 64,
                "matchType": "PARTIAL",
            }],
        }),
        {"type": "item.completed", "item": {
            "type": "agent_message", "text": "已经找到现有的取消归责事实，可以继续核对业务含义。"}},
        {"type": "thread.started", "thread_id": "thread-recall"},
        {"type": "turn.completed"},
    ]


def valid_clarification_events() -> list[dict]:
    return [
        call("rg_read", "rg.library.overview.get", {"includeSamples": False}, {
            "buildingBlocks": [], "worldModel": {}, "authoringPatterns": {}, "samples": [],
            "snapshotFingerprint": "sha256:" + "a" * 64,
            "authoringPatternsFingerprint": "sha256:" + "b" * 64,
        }),
        {"type": "item.completed", "item": {
            "type": "agent_message", "text": "无法判断取消责任时，应当转人工复核还是采用其他处理方式？"}},
        {"type": "thread.started", "thread_id": "thread-clarification"},
        {"type": "turn.completed"},
    ]


def family_events(family_id: str) -> list[dict]:
    overview = call("rg_read", "rg.library.overview.get", {"includeSamples": False}, {
        "snapshotFingerprint": "sha256:" + "a" * 64,
        "authoringPatternsFingerprint": "sha256:" + "b" * 64,
    })
    question = {"type": "item.completed", "item": {
        "type": "agent_message", "text": "还需要由业务负责人确认一个关键业务条件？"}}
    summary = {"type": "item.completed", "item": {
        "type": "agent_message", "text": "已按当前业务含义完成核对。"}}
    search = lambda data: call("rg_read", "rg.capability.search",  # noqa: E731
                               {"query": {"intent": "private"}}, data)
    exact = search({"status": "EXACT", "candidates": [{
        "assetRef": "feature:test", "contractFingerprint": "sha256:" + "e" * 64,
        "matchType": "EXACT",
    }]})
    if family_id == "synonym-rewrite":
        body = [exact, summary]
    elif family_id == "near-meaning-distractor":
        exact["item"]["result"]["structuredContent"]["data"]["candidates"].append({
            "assetRef": "feature:traffic-accident",
            "contractFingerprint": "sha256:" + "f" * 64,
            "matchType": "CONFLICT",
        })
        body = [exact, summary]
    elif family_id in {
            "boundary-unspecified", "unknown-policy-unspecified", "authority-source-unspecified"}:
        body = [overview, question]
    elif family_id == "multiple-exact":
        body = [search({"status": "AMBIGUOUS", "candidates": [
            {"assetRef": "feature:a", "contractFingerprint": "sha256:" + "1" * 64,
             "matchType": "EXACT"},
            {"assetRef": "feature:b", "contractFingerprint": "sha256:" + "2" * 64,
             "matchType": "EXACT"},
        ]}), question]
    elif family_id == "legacy-feature-partial":
        body = [search({"status": "INCOMPLETE", "candidates": [
            {"assetRef": "feature:legacy", "contractFingerprint": "sha256:" + "3" * 64,
             "matchType": "PARTIAL"},
        ]}), question]
    elif family_id == "surface-interference":
        body = [overview, summary]
    elif family_id == "cross-session-rediscovery":
        body = [call("rg_read", "rg.entity.list", {"kind": "SOLUTION"}, {"items": []}),
                call("rg_read", "rg.journey.next", {"journeyRef": "journey:test"},
                     {"journeyRef": "journey:test", "stage": "TESTING"}), summary]
    elif family_id == "semantic-drift":
        body = [call("rg_read", "rg.journey.next", {"journeyRef": "journey:drift"}, {
            "journeyRef": "journey:drift",
            "blockingReasons": ["GOLDEN_CASE_STALE"],
        }), question]
    elif family_id == "fact-assumption":
        body = [call("rg_author", "rg.solution.golden.propose", {}, {
            "caseSetRef": "case-set:fact", "proposalStatus": "PENDING",
        }), summary]
        body[0]["item"]["arguments"] = {"cases": [{"givenFacts": {"责任方": "乘客"}}]}
    elif family_id in {"dependency-unavailable", "action-stubbing", "forbidden-dependency"}:
        outcome = {
            "dependency-unavailable": "UNAVAILABLE",
            "action-stubbing": "SUCCEEDS_WITHOUT_EFFECT",
            "forbidden-dependency": "MUST_NOT_BE_USED",
        }[family_id]
        body = [call("rg_author", "rg.solution.golden.propose", {
            "cases": [{"dependencyAssumptions": [{"outcome": outcome}]}],
        }, {"caseSetRef": f"case-set:{family_id}", "proposalStatus": "PENDING"}), summary]
    elif family_id == "assumption-ambiguity":
        body = [search({"status": "AMBIGUOUS", "candidates": [
            {"assetRef": "instruction:a", "contractFingerprint": "sha256:" + "4" * 64,
             "businessName": "退款执行", "matchType": "EXACT"},
            {"assetRef": "instruction:b", "contractFingerprint": "sha256:" + "5" * 64,
             "businessName": "退款执行", "matchType": "EXACT"},
        ]}), question]
    else:
        raise AssertionError(f"test fixture missing for {family_id}")
    return body + [
        {"type": "thread.started", "thread_id": f"thread-{family_id}"},
        {"type": "turn.completed"},
    ]


class BusinessSolutionCertificateTest(unittest.TestCase):
    @staticmethod
    def setup_manifest() -> dict:
        assets = [
            {"role": "nearMeaningDistractor", "assetKind": "FEATURE",
             "assetRef": "feature:traffic-accident", "contractFingerprint": "sha256:" + "f" * 64,
             "revision": 1},
            {"role": "multipleExactA", "assetKind": "FEATURE", "assetRef": "feature:a",
             "contractFingerprint": "sha256:" + "1" * 64, "revision": 1},
            {"role": "multipleExactB", "assetKind": "FEATURE", "assetRef": "feature:b",
             "contractFingerprint": "sha256:" + "2" * 64, "revision": 1},
            {"role": "legacyPartial", "assetKind": "FEATURE", "assetRef": "feature:legacy",
             "contractFingerprint": "sha256:" + "3" * 64, "revision": 1},
            {"role": "assumptionAmbiguityA", "assetKind": "INSTRUCTION",
             "assetRef": "instruction:a", "contractFingerprint": "sha256:" + "4" * 64,
             "revision": 1},
            {"role": "assumptionAmbiguityB", "assetKind": "INSTRUCTION",
             "assetRef": "instruction:b", "contractFingerprint": "sha256:" + "5" * 64,
             "revision": 1},
            {"role": "semanticDriftFeature", "assetKind": "FEATURE",
             "assetRef": "feature:drift", "contractFingerprint": "sha256:" + "6" * 64,
             "revision": 2},
            {"role": "semanticDriftJourney", "assetKind": "BUSINESS_JOURNEY",
             "assetRef": "journey:drift", "contractFingerprint": "sha256:" + "7" * 64,
             "revision": 6},
            {"role": "semanticDriftCaseSet", "assetKind": "GOLDEN_CASE_SET",
             "assetRef": "case-set:drift", "contractFingerprint": "sha256:" + "8" * 64,
             "revision": 1},
        ]
        material = {
            "fixtureFingerprint": "sha256:" + "9" * 64,
            "authoringPatternsFingerprint": "sha256:" + "a" * 64,
            "completedPhases": ["near-meaning", "remaining"],
            "assets": assets,
            "relationships": MODULE.SETUP_RELATIONSHIPS,
            "preflights": [
                {"familyId": "near-meaning-distractor", "status": "EXACT",
                 "observedRoles": ["nearMeaningDistractor"], "outcome": "SEMANTIC_TOP1",
                 "target": {"assetRef": "feature:test",
                            "contractFingerprint": "sha256:" + "e" * 64,
                            "matchType": "EXACT"}},
                {"familyId": "multiple-exact", "status": "AMBIGUOUS",
                 "observedRoles": ["multipleExactA", "multipleExactB"],
                 "outcome": "AMBIGUOUS_EXACT", "target": None},
                {"familyId": "legacy-feature-partial", "status": "INCOMPLETE",
                 "observedRoles": ["legacyPartial"], "outcome": "PARTIAL_VISIBLE",
                 "target": None},
                {"familyId": "assumption-ambiguity", "status": "INCOMPLETE",
                 "observedRoles": ["assumptionAmbiguityA", "assumptionAmbiguityB"],
                 "outcome": "SAME_NAME_VISIBLE", "target": None},
                {"familyId": "semantic-drift", "status": "STALE",
                 "observedRoles": ["semanticDriftFeature", "semanticDriftJourney",
                                   "semanticDriftCaseSet"],
                 "outcome": "GOLDEN_CASE_STALE", "target": None},
            ],
        }
        return {"schemaVersion": "rg.businessRecallFamilySetup.v1", **material,
                "setupFingerprint": "sha256:" + hashlib.sha256(
                    MODULE.canonical_bytes(material)).hexdigest()}

    def certify(self, events: list[dict] | None = None) -> dict:
        return self.certify_aux(authoring_events=events)

    def certify_aux(self, recall_events: list[dict] | None = None,
                    clarification_events: list[dict] | None = None,
                    family_overrides: dict[str, list[dict]] | None = None,
                    manifest_mutator=None,
                    setup_mutator=None,
                    authoring_events: list[dict] | None = None) -> dict:
        with tempfile.TemporaryDirectory() as directory:
            trace = Path(directory, "trace.jsonl")
            trace.write_text("".join(json.dumps(event, ensure_ascii=False) + "\n"
                                     for event in (authoring_events or valid_events())), encoding="utf-8")
            overrides = dict(family_overrides or {})
            if recall_events is not None:
                overrides["synonym-rewrite"] = recall_events
            if clarification_events is not None:
                overrides["unknown-policy-unspecified"] = clarification_events
            families = []
            for family_id, expected in MODULE.FAMILY_EXPECTATIONS.items():
                family_trace = Path(directory, f"{family_id}.jsonl")
                events = overrides.get(family_id, family_events(family_id))
                family_trace.write_text("".join(json.dumps(event, ensure_ascii=False) + "\n"
                                                 for event in events), encoding="utf-8")
                families.append({
                    "familyId": family_id,
                    "expectedBehaviorClass": expected,
                    "traceFile": family_trace.name,
                    "exitCode": 0,
                    "runtimeInstanceNonce": (MAIN_RUNTIME_NONCE if family_id == "synonym-rewrite"
                                             else NEAR_RUNTIME_NONCE
                                             if family_id == "near-meaning-distractor"
                                             else REMAINING_RUNTIME_NONCE),
                })
            setup = self.setup_manifest()
            if setup_mutator is not None:
                setup_mutator(setup)
            setup_path = Path(directory, "setup.json")
            setup_path.write_text(json.dumps(setup), encoding="utf-8")
            manifest = {"schemaVersion": "rg.businessRecallFamilyTraceSet.v1",
                        "setupManifestFile": setup_path.name,
                        "families": families}
            if manifest_mutator is not None:
                manifest_mutator(manifest)
            manifest_path = Path(directory, "families.json")
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            safe_metadata = metadata()
            return MODULE.certify(trace, safe_metadata, manifest_path)

    def test_certifies_one_correlated_business_journey_without_payload(self) -> None:
        certificate = self.certify_aux()
        self.assertEqual("CERTIFIED", certificate["result"])
        self.assertEqual("rg.businessRecallCertification.v1", certificate["schemaVersion"])
        self.assertEqual(2, len(certificate["correlation"]["cases"]))
        self.assertRegex(certificate["correlation"]["authoringPatterns"], r"^hmac-sha256:")
        self.assertTrue(certificate["assertions"]
                        ["compilerValidatedAuthoringPatternsObservedBeforeCreation"])
        self.assertTrue(certificate["assertions"]["fourEntityWritesBoundToAuthoringPatterns"])
        self.assertTrue(certificate["assertions"]["fourEntityBusinessDisplaysDeclared"])
        self.assertRegex(certificate["setupIdentity"]["seedManifestFingerprint"],
                         r"^hmac-sha256:")
        self.assertRegex(certificate["setupIdentity"]["setupFingerprint"], r"^sha256:")
        self.assertEqual(3, certificate["runtimeIdentity"]["codexPhaseCount"])
        self.assertEqual(3, len(certificate["runtimeIdentity"]["instanceNonceFingerprints"]))
        self.assertNotIn("private", json.dumps(certificate))
        self.assertNotIn("journey:drift", json.dumps(certificate))
        self.assertNotIn("feature:traffic-accident", json.dumps(certificate))
        self.assertEqual(1.0, certificate["metrics"]["recallAt3"])

    def test_certifies_correlated_recall_and_single_business_clarification(self) -> None:
        certificate = self.certify_aux()
        self.assertEqual(3, len(certificate["cases"]))
        self.assertEqual(1.0, certificate["metrics"]["recallAt3"])
        self.assertEqual(1.0, certificate["metrics"]["top1"])
        self.assertEqual(1.0, certificate["metrics"]["clarificationRate"])
        self.assertTrue(certificate["assertions"]["crossSessionFeatureRecallCorrelated"])
        self.assertTrue(certificate["assertions"]["clarificationStoppedBeforeAuthoring"])
        self.assertEqual(16, len(set(certificate["correlation"]["sessions"])))
        self.assertEqual(15, len(certificate["familyEvidence"]))
        self.assertEqual(set(MODULE.FAMILY_EXPECTATIONS),
                         {entry["familyId"] for entry in certificate["familyEvidence"]})
        self.assertNotIn("feature:test", json.dumps(certificate))

    def test_rejects_reused_or_missing_codex_thread_identity(self) -> None:
        events = valid_clarification_events()
        events[-2]["thread_id"] = "thread-synonym-rewrite"
        with self.assertRaisesRegex(MODULE.CertificationFailure, "16 independent"):
            self.certify_aux(clarification_events=events)

        events = valid_recall_events()
        events.pop(-2)
        with self.assertRaisesRegex(MODULE.CertificationFailure, "identify its Codex thread"):
            self.certify_aux(recall_events=events)

    def test_rejects_missing_recall_family(self) -> None:
        def remove_family(manifest: dict) -> None:
            manifest["families"] = [entry for entry in manifest["families"]
                                    if entry["familyId"] != "forbidden-dependency"]

        with self.assertRaisesRegex(MODULE.CertificationFailure, "cover all 15 families"):
            self.certify_aux(manifest_mutator=remove_family)

    def test_rejects_misclassified_recall_family(self) -> None:
        def misclassify(manifest: dict) -> None:
            target = next(entry for entry in manifest["families"]
                          if entry["familyId"] == "action-stubbing")
            target["expectedBehaviorClass"] = "RECALL_TOP1"

        with self.assertRaisesRegex(MODULE.CertificationFailure, "misclassified"):
            self.certify_aux(manifest_mutator=misclassify)

    def test_rejects_family_whose_observed_outcome_does_not_match(self) -> None:
        events = family_events("multiple-exact")
        data = events[0]["item"]["result"]["structuredContent"]["data"]
        data["status"] = "EXACT"
        with self.assertRaisesRegex(MODULE.CertificationFailure, "did not observe AMBIGUOUS"):
            self.certify_aux(family_overrides={"multiple-exact": events})

    def test_rejects_distractor_family_without_a_real_distractor(self) -> None:
        events = family_events("near-meaning-distractor")
        candidates = events[0]["item"]["result"]["structuredContent"]["data"]["candidates"]
        del candidates[1:]
        with self.assertRaisesRegex(MODULE.CertificationFailure, "seeded distractor"):
            self.certify_aux(family_overrides={"near-meaning-distractor": events})

    def test_rejects_tampered_setup_fingerprint(self) -> None:
        def tamper(setup: dict) -> None:
            setup["assets"][0]["assetRef"] = "feature:tampered"

        with self.assertRaisesRegex(MODULE.CertificationFailure, "setup fingerprint"):
            self.certify_aux(setup_mutator=tamper)

    def test_rejects_setup_preflight_for_a_different_primary_feature(self) -> None:
        def replace_target(setup: dict) -> None:
            setup["preflights"][0]["target"]["assetRef"] = "feature:other"
            material = {key: setup[key] for key in (
                "fixtureFingerprint", "authoringPatternsFingerprint", "completedPhases", "assets",
                "relationships", "preflights")}
            setup["setupFingerprint"] = "sha256:" + hashlib.sha256(
                MODULE.canonical_bytes(material)).hexdigest()

        with self.assertRaisesRegex(MODULE.CertificationFailure, "primary authored Feature"):
            self.certify_aux(setup_mutator=replace_target)

    def test_rejects_a_misclassified_setup_preflight_status(self) -> None:
        def replace_status(setup: dict) -> None:
            setup["preflights"][0]["status"] = "INCOMPLETE"
            material = {key: setup[key] for key in (
                "fixtureFingerprint", "authoringPatternsFingerprint", "completedPhases", "assets",
                "relationships", "preflights")}
            setup["setupFingerprint"] = "sha256:" + hashlib.sha256(
                MODULE.canonical_bytes(material)).hexdigest()

        with self.assertRaisesRegex(MODULE.CertificationFailure, "family relationship"):
            self.certify_aux(setup_mutator=replace_status)

    def test_rejects_family_runtime_phase_reuse(self) -> None:
        def reuse_runtime(manifest: dict) -> None:
            target = next(item for item in manifest["families"]
                          if item["familyId"] == "near-meaning-distractor")
            target["runtimeInstanceNonce"] = MAIN_RUNTIME_NONCE

        with self.assertRaisesRegex(MODULE.CertificationFailure, "three isolated"):
            self.certify_aux(manifest_mutator=reuse_runtime)

    def test_rejects_remaining_families_split_across_runtime_phases(self) -> None:
        def split_runtime(manifest: dict) -> None:
            target = next(item for item in manifest["families"]
                          if item["familyId"] == "semantic-drift")
            target["runtimeInstanceNonce"] = "4" * 64

        with self.assertRaisesRegex(MODULE.CertificationFailure, "three isolated"):
            self.certify_aux(manifest_mutator=split_runtime)

    def test_rejects_family_trace_that_does_not_observe_its_seeded_assets(self) -> None:
        events = family_events("multiple-exact")
        events[0]["item"]["result"]["structuredContent"]["data"]["candidates"].pop()
        with self.assertRaisesRegex(MODULE.CertificationFailure, "both seeded candidates"):
            self.certify_aux(family_overrides={"multiple-exact": events})

    def test_accepts_golden_case_stale_as_semantic_drift_evidence(self) -> None:
        certificate = self.certify_aux()
        evidence = next(item for item in certificate["familyEvidence"]
                        if item["familyId"] == "semantic-drift")
        self.assertEqual("RECONFIRMATION_STOP", evidence["observedOutcome"])

    def test_rejects_semantic_drift_from_an_unseeded_journey(self) -> None:
        events = family_events("semantic-drift")
        events[0]["item"]["arguments"]["journeyRef"] = "journey:other"
        events[0]["item"]["result"]["structuredContent"]["data"]["journeyRef"] = "journey:other"
        with self.assertRaisesRegex(MODULE.CertificationFailure, "re-confirmation"):
            self.certify_aux(family_overrides={"semantic-drift": events})

    def test_accepts_same_name_seeded_actions_as_business_assumption_ambiguity(self) -> None:
        events = family_events("assumption-ambiguity")
        events[0]["item"]["result"]["structuredContent"]["data"]["status"] = "INCOMPLETE"
        certificate = self.certify_aux(family_overrides={"assumption-ambiguity": events})
        evidence = next(item for item in certificate["familyEvidence"]
                        if item["familyId"] == "assumption-ambiguity")
        self.assertEqual("ASSUMPTION_AMBIGUOUS_STOP", evidence["observedOutcome"])

    def test_rejects_recall_of_a_different_feature(self) -> None:
        events = valid_recall_events()
        candidate = events[0]["item"]["result"]["structuredContent"]["data"]["candidates"][0]
        candidate["assetRef"] = "feature:other"
        with self.assertRaisesRegex(MODULE.CertificationFailure, "rank the exact Feature first"):
            self.certify_aux(recall_events=events)

    def test_rejects_recalled_feature_outside_top_one_for_the_single_sample(self) -> None:
        events = valid_recall_events()
        candidates = events[0]["item"]["result"]["structuredContent"]["data"]["candidates"]
        candidates.insert(0, {
            "assetRef": "feature:other",
            "contractFingerprint": "sha256:" + "f" * 64,
            "matchType": "PARTIAL",
        })
        with self.assertRaisesRegex(MODULE.CertificationFailure, "rank.*first"):
            self.certify_aux(recall_events=events)

    def test_rejects_authoring_attempt_during_clarification(self) -> None:
        events = valid_clarification_events()
        events.insert(1, call("rg_author", "rg.journey.start", {}, {}))
        with self.assertRaisesRegex(MODULE.CertificationFailure, "mutated state"):
            self.certify_aux(clarification_events=events)

    def test_rejects_zero_or_multiple_clarification_questions(self) -> None:
        for text in ("请补充无法判断时的处理方式。", "无法判断时怎么办？由谁提供这个事实？"):
            with self.subTest(text=text):
                events = valid_clarification_events()
                events[-3]["item"]["text"] = text
                with self.assertRaisesRegex(MODULE.CertificationFailure, "exactly one"):
                    self.certify_aux(clarification_events=events)

    def test_rejects_technical_clarification_question(self) -> None:
        events = valid_clarification_events()
        events[-3]["item"]["text"] = "请提供 schema 字段？"
        with self.assertRaisesRegex(MODULE.CertificationFailure, "technical"):
            self.certify_aux(clarification_events=events)

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

    def test_rejects_entity_write_without_business_display(self) -> None:
        events = valid_events()
        events[6]["item"]["arguments"]["instructionYaml"] = "instruction:test:\n  effect: READ"
        with self.assertRaisesRegex(MODULE.CertificationFailure, "business display"):
            self.certify(events)

    def test_accepts_json_style_business_display(self) -> None:
        events = valid_events()
        events[6]["item"]["arguments"]["instructionYaml"] = (
            '{"instruction:test":{"display":{"businessName":"test",'
            '"description":"test"}}}')
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
        events[-3]["item"]["text"] = "YAML 和 schema 已经提交。"
        with self.assertRaisesRegex(MODULE.CertificationFailure, "technical"):
            self.certify(events)

    def test_rejects_non_mcp_external_action(self) -> None:
        events = valid_events()
        events.insert(-2, {"type": "item.completed", "item": {"type": "command_execution"}})
        with self.assertRaisesRegex(MODULE.CertificationFailure, "non-MCP"):
            self.certify(events)


if __name__ == "__main__":
    unittest.main()
