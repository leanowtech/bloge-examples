#!/usr/bin/env python3
"""Behavior tests for the payload-free Codex trace certificate reducer."""

from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("agent_tdd_codex_trace_certificate.py")
SPEC = importlib.util.spec_from_file_location("agent_tdd_codex_trace_certificate", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def completed_call(server: str, tool: str, arguments: dict | None = None,
                   data: dict | None = None) -> dict:
    return {
        "type": "item.completed",
        "item": {
            "type": "mcp_tool_call",
            "server": server,
            "tool": tool,
            "status": "completed",
            "arguments": arguments or {"secret": "must-not-survive"},
            "result": {"structured_content": {"ok": True, "data": data or {}}},
        },
    }


def failed_call(server: str, tool: str) -> dict:
    return {
        "type": "item.completed",
        "item": {"type": "mcp_tool_call", "server": server, "tool": tool,
                 "status": "failed", "arguments": {"secret": "must-not-survive"}},
    }


class TraceCertificateTest(unittest.TestCase):

    def write_trace(self, events: list[dict]) -> Path:
        directory = Path(tempfile.mkdtemp(prefix="rg-codex-trace-test-"))
        trace = directory / "trace.jsonl"
        trace.write_text("".join(json.dumps(event) + "\n" for event in events), encoding="utf-8")
        self.addCleanup(lambda: __import__("shutil").rmtree(directory))
        return trace

    def happy_events(self) -> list[dict]:
        source = "graph private { transform result { value = 'Alice-secret' } }"
        context = "sha256:" + "a" * 64
        receipt = "sha256:" + "b" * 64
        tool = "private-tool"
        case_set = "private-case-set"
        case = "private-case"
        events = [
            completed_call("rg_read", "rg.capability.list", {}, {"capabilities": []}),
            completed_call("rg_read", "rg.contract.get", {"assetRef": "private-source"},
                           {"assetRef": "private-source", "kind": "API"}),
            failed_call("rg_read", "rg.dsl.reference.get"),
            completed_call("rg_read", "rg.dsl.reference.get", {"libraryRefs": []},
                           {"authoringContextFingerprint": context}),
            completed_call("rg_read", "rg.dsl.preview", {
                "source": source, "libraryRefs": [], "authoringContextFingerprint": context,
            }, {"accepted": True, "authoringReceiptFingerprint": receipt}),
            completed_call("rg_read", "rg.gate.check", {
                "source": source, "libraryRefs": [], "authoringContextFingerprint": context,
            }, {"accepted": True, "rewriteGate": {"allowed": True},
                "authoringReceiptFingerprint": receipt}),
            completed_call("rg_author", "rg.tool.compose", {
                "toolRef": tool, "graph": {"dsl": source}, "libraryRefs": [],
                "authoringContextFingerprint": context, "authoringReceiptFingerprint": receipt,
            }, {"assetRef": tool, "authoringContextFingerprint": context,
                "authoringReceiptFingerprint": receipt}),
            completed_call("rg_author", "rg.tool.setInstruction", {"toolRef": tool},
                           {"toolRef": tool}),
            completed_call("rg_author", "rg.scenario.upsertCases", {
                "toolRef": tool, "caseSetRef": case_set,
            }, {"caseSetRef": case_set, "rows": [{"caseId": case}]}),
            completed_call("rg_author", "rg.scenario.setDependencyBehavior", {
                "caseSetRef": case_set, "caseId": case, "nodeId": "private-node",
            }, {"caseSetRef": case_set, "caseId": case, "nodeId": "private-node"}),
            completed_call("rg_read", "rg.scenario.listCases", {"caseSetRef": case_set}, {
                "caseSetRef": case_set, "toolRef": tool, "rows": [{
                    "caseId": case,
                    "stubs": {"private-node": {"behavior": "RETURN"}},
                    "proposedOracle": {"status": "PENDING", "value": "Alice-secret"},
                }],
            }),
        ]
        events.extend([
            {"type": "item.completed", "item": {"type": "agent_message",
             "text": "资料来源匹配，能力草稿有效，标准案例待人工确认。"}},
            {"type": "turn.completed"},
        ])
        return events

    def test_emits_only_safe_structure_for_a_complete_human_bounded_journey(self) -> None:
        certificate = MODULE.certify(self.write_trace(self.happy_events()), {
            "repositoryCommit": "abc123",
            "codexVersion": "codex-cli test",
            "certifiedAt": "2026-09-04T00:00:00Z",
            "exitCode": 0,
        })

        serialized = json.dumps(certificate, ensure_ascii=False)
        self.assertEqual("CERTIFIED", certificate["result"])
        self.assertTrue(certificate["assertions"]["caseSetBoundToTool"])
        self.assertTrue(certificate["assertions"]["dependencyBehaviorDefined"])
        self.assertTrue(certificate["assertions"]["businessOracleProposed"])
        self.assertTrue(certificate["assertions"]["stoppedBeforeExecutionGovernanceAndPublication"])
        self.assertTrue(certificate["assertions"]["sameCandidateReceiptAndAssets"])
        self.assertEqual("EPHEMERAL_HMAC_SHA256", certificate["correlation"]["method"])
        self.assertTrue(certificate["correlation"]["cases"])
        self.assertNotIn("Alice-secret", serialized)
        self.assertNotIn("must-not-survive", serialized)
        self.assertNotIn("private-tool", serialized)

    def test_rejects_a_case_set_bound_to_a_different_tool(self) -> None:
        events = self.happy_events()
        for event in events:
            item = event.get("item", {})
            if item.get("tool") == "rg.scenario.upsertCases":
                item["arguments"]["toolRef"] = "other-tool"

        with self.assertRaisesRegex(MODULE.CertificationFailure, "not bound to one Tool"):
            MODULE.certify(self.write_trace(events), {
                "repositoryCommit": "abc123",
                "codexVersion": "codex-cli test",
                "certifiedAt": "2026-09-04T00:00:00Z",
                "exitCode": 0,
            })

    def test_rejects_execution_before_human_approval(self) -> None:
        events = self.happy_events()
        events.insert(-2, completed_call("rg_execute", "rg.tool.baseline"))

        with self.assertRaisesRegex(MODULE.CertificationFailure, "human approval"):
            MODULE.certify(self.write_trace(events), {
                "repositoryCommit": "abc123",
                "codexVersion": "codex-cli test",
                "certifiedAt": "2026-09-04T00:00:00Z",
                "exitCode": 0,
            })

    def test_rejects_a_success_envelope_when_preview_was_not_accepted(self) -> None:
        events = self.happy_events()
        for event in events:
            item = event.get("item", {})
            if item.get("tool") == "rg.dsl.preview":
                item["result"]["structured_content"]["data"]["accepted"] = False
                item["result"]["structured_content"]["data"]["authoringDiagnostics"] = [{
                    "level": "ERROR", "diagnosticFingerprint": "sha256:" + "c" * 64,
                }]

        with self.assertRaisesRegex(MODULE.CertificationFailure, "required successful tool call"):
            MODULE.certify(self.write_trace(events), {
                "repositoryCommit": "abc123",
                "codexVersion": "codex-cli test",
                "certifiedAt": "2026-09-04T00:00:00Z",
                "exitCode": 0,
            })

    def test_rejects_a_different_candidate_at_compose(self) -> None:
        events = self.happy_events()
        for event in events:
            item = event.get("item", {})
            if item.get("tool") == "rg.tool.compose":
                item["arguments"]["graph"]["dsl"] = "graph different {}"

        with self.assertRaisesRegex(MODULE.CertificationFailure, "same DSL candidate"):
            MODULE.certify(self.write_trace(events), {
                "repositoryCommit": "abc123",
                "codexVersion": "codex-cli test",
                "certifiedAt": "2026-09-04T00:00:00Z",
                "exitCode": 0,
            })

    def test_rejects_a_third_attempt_after_the_same_blocker_repeats(self) -> None:
        events = self.happy_events()
        accepted_index = next(index for index, event in enumerate(events)
                              if event.get("item", {}).get("tool") == "rg.dsl.preview")
        context = events[accepted_index]["item"]["arguments"]["authoringContextFingerprint"]
        diagnostic = {"level": "ERROR", "diagnosticFingerprint": "sha256:" + "c" * 64}
        rejected = completed_call("rg_read", "rg.dsl.preview", {
            "source": "graph broken {}", "libraryRefs": [], "authoringContextFingerprint": context,
        }, {"accepted": False, "authoringDiagnostics": [diagnostic]})
        events[accepted_index:accepted_index] = [rejected, rejected]

        with self.assertRaisesRegex(MODULE.CertificationFailure, "continued after"):
            MODULE.certify(self.write_trace(events), {
                "repositoryCommit": "abc123",
                "codexVersion": "codex-cli test",
                "certifiedAt": "2026-09-04T00:00:00Z",
                "exitCode": 0,
            })

    def test_rejects_a_case_read_back_from_another_case_set(self) -> None:
        events = self.happy_events()
        for event in events:
            item = event.get("item", {})
            if item.get("tool") == "rg.scenario.listCases":
                item["arguments"]["caseSetRef"] = "other-case-set"
                item["result"]["structured_content"]["data"]["caseSetRef"] = "other-case-set"

        with self.assertRaisesRegex(MODULE.CertificationFailure, "not read back"):
            MODULE.certify(self.write_trace(events), {
                "repositoryCommit": "abc123",
                "codexVersion": "codex-cli test",
                "certifiedAt": "2026-09-04T00:00:00Z",
                "exitCode": 0,
            })


if __name__ == "__main__":
    unittest.main()
