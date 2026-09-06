import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location(
    "process_renderer", SCRIPT_DIR / "render_business_codex_process_evidence.py")
RENDERER = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(RENDERER)


class ProcessEvidenceRendererTest(unittest.TestCase):
    def test_refreshes_the_summary_report_and_screenshot_from_the_current_certificate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            certificate = root / "certificate.json"
            certificate.write_text(json.dumps({
                "repositoryCommit": "c" * 40,
                "certificateFingerprint": "sha256:" + "d" * 64,
                "certifiedAt": "2026-09-06T15:14:52Z",
                "codexVersion": "codex-cli 0.153.4",
                "journey": {"observedCalls": [{"ordinal": value} for value in range(7)]},
            }), encoding="utf-8")
            report = root / "report.html"
            report.write_text(
                '<span>真实 Codex CLI 0.1.0<br>'
                '<div class="metric"><b>1</b><span>主创作链 MCP 调用</span></div>'
                '<span>认证时间：2020-01-01 00:00:00 UTC · Commit '
                '<code>' + "a" * 40 + '</code></span>'
                '<span>Certificate <code>sha256:' + "b" * 16 + '…' + "b" * 8 + '</code></span>',
                encoding="utf-8")
            screenshot = root / "report.png"
            browser = root / "fake-browser.py"
            browser.write_text("#!/usr/bin/env python3\nimport pathlib,sys\n"
                               "p=next(x.split('=',1)[1] for x in sys.argv if x.startswith('--screenshot='))\n"
                               "pathlib.Path(p).write_bytes(b'fake-png')\n", encoding="utf-8")
            os.chmod(browser, 0o700)

            RENDERER.render_summary(certificate, report, screenshot, browser)

            rendered = report.read_text(encoding="utf-8")
            self.assertIn("真实 Codex CLI 0.153.4", rendered)
            self.assertIn('<b>7</b><span>主创作链 MCP 调用</span>', rendered)
            self.assertIn("2026-09-06 15:14:52 UTC", rendered)
            self.assertIn("c" * 40, rendered)
            self.assertIn("sha256:" + "d" * 16 + "…" + "d" * 8, rendered)
            self.assertEqual(b"fake-png", screenshot.read_bytes())

    def test_renders_six_trace_bound_redacted_images(self):
        events = []
        for tool in [item[2] for item in RENDERER.STAGES]:
            events.append({"type": "item.completed", "item": {
                "type": "mcp_tool_call", "server": "rg_read" if tool.endswith("get") else "rg_author",
                "tool": tool, "status": "completed", "arguments": {"secret": "must-not-render"},
                "result": {"structuredContent": {"ok": True, "data": {"secret": "must-not-render"}}},
            }})
        events.extend([{"type": "thread.started", "thread_id": "thread-private"},
                       {"type": "turn.completed"}])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            trace = root / "trace.jsonl"
            trace.write_text("".join(json.dumps(event) + "\n" for event in events), encoding="utf-8")
            certificate = root / "certificate.json"
            certificate.write_text(json.dumps({
                "repositoryCommit": "a" * 40,
                "certificateFingerprint": "sha256:" + "b" * 64,
            }), encoding="utf-8")
            browser = root / "fake-browser.py"
            browser.write_text("#!/usr/bin/env python3\nimport pathlib,sys\n"
                               "p=next(x.split('=',1)[1] for x in sys.argv if x.startswith('--screenshot='))\n"
                               "pathlib.Path(p).write_bytes(b'fake-png')\n", encoding="utf-8")
            os.chmod(browser, 0o700)
            output = root / "evidence"

            manifest = RENDERER.render(trace, certificate, output, browser)

            self.assertEqual("REDACTED_REAL_CODEX_TRACE_RENDER", manifest["evidenceKind"])
            self.assertEqual(6, len(manifest["images"]))
            self.assertTrue(all((output / item["file"]).is_file() for item in manifest["images"]))
            rendered = RENDERER._page("业务事实", "不含参数", "rg.feature.define", 2,
                                      "a" * 40, "sha256:" + "b" * 64)
            self.assertNotIn("must-not-render", rendered)
            self.assertNotIn("thread-private", json.dumps(manifest))


if __name__ == "__main__":
    unittest.main()
