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


def completed_call(server: str, tool: str, data: dict | None = None) -> dict:
    return {
        "type": "item.completed",
        "item": {
            "type": "mcp_tool_call",
            "server": server,
            "tool": tool,
            "status": "completed",
            "arguments": {"secret": "must-not-survive"},
            "result": {"structured_content": {"ok": True, "data": data or {}}},
        },
    }


class TraceCertificateTest(unittest.TestCase):

    def write_trace(self, events: list[dict]) -> Path:
        directory = Path(tempfile.mkdtemp(prefix="rg-codex-trace-test-"))
        trace = directory / "trace.jsonl"
        trace.write_text("".join(json.dumps(event) + "\n" for event in events), encoding="utf-8")
        self.addCleanup(lambda: __import__("shutil").rmtree(directory))
        return trace

    def happy_events(self) -> list[dict]:
        servers = ["rg_read"] * 5 + ["rg_author"] * 3
        events = [
            completed_call(server, tool, {"toolRef": "private-tool", "rows": [{
                "stubs": {"source": {"behavior": "RETURN"}},
                "proposedOracle": {"status": "PENDING"},
            }]}
                           if tool == "rg.scenario.upsertCases" else {"payload": "Alice-secret"})
            for server, tool in zip(servers, MODULE.REQUIRED_SEQUENCE, strict=True)
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
        self.assertNotIn("Alice-secret", serialized)
        self.assertNotIn("must-not-survive", serialized)
        self.assertNotIn("private-tool", serialized)

    def test_rejects_an_unbound_case_set(self) -> None:
        events = self.happy_events()
        for event in events:
            item = event.get("item", {})
            if item.get("tool") == "rg.scenario.upsertCases":
                item["result"]["structured_content"]["data"] = {"toolRef": ""}

        with self.assertRaisesRegex(MODULE.CertificationFailure, "not bound"):
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


if __name__ == "__main__":
    unittest.main()
