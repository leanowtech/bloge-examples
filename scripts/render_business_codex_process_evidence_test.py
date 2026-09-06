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
