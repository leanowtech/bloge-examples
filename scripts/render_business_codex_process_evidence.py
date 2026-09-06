#!/usr/bin/env python3
"""Render six redacted screenshots from one completed real Codex authoring trace."""

from __future__ import annotations

import argparse
import hashlib
import html
import importlib.util
import json
import re
import subprocess
import tempfile
from pathlib import Path


STAGES = [
    ("01", "先读业务积木", "rg.library.overview.get", "Codex 先读取服务端创作模板和当前业务能力。"),
    ("02", "定义业务事实", "rg.feature.define", "Codex 把业务事实、值域、时点和未知处理写成 Feature。"),
    ("03", "定义业务规则", "rg.scenario.define", "Codex 把处置条件和兜底出口写成 Scenario。"),
    ("04", "定义业务动作", "rg.instruction.define", "Codex 定义结果与业务解释完整的 Instruction。"),
    ("05", "组合业务解法", "rg.solution.compose", "Codex 将事实、规则和动作组合为 Solution。"),
    ("06", "提交标准案例", "rg.solution.golden.propose", "Codex 提交两条案例并停在业务负责人确认前。"),
]


def _replace_once(source: str, pattern: str, replacement: str, label: str) -> str:
    rendered, count = re.subn(pattern, replacement, source, count=1)
    if count != 1:
        raise RuntimeError(f"summary report does not contain exactly one {label}")
    return rendered


def render_summary(certificate_path: Path, report: Path, screenshot: Path,
                   browser: Path) -> None:
    """Refresh the checked-in summary and screenshot from one payload-free certificate."""
    certificate = json.loads(certificate_path.read_text(encoding="utf-8"))
    commit = str(certificate.get("repositoryCommit", ""))
    fingerprint = str(certificate.get("certificateFingerprint", ""))
    certified_at = str(certificate.get("certifiedAt", ""))
    version = str(certificate.get("codexVersion", ""))
    calls = certificate.get("journey", {}).get("observedCalls")
    if not re.fullmatch(r"[0-9a-f]{40}", commit) \
            or not re.fullmatch(r"sha256:[0-9a-f]{64}", fingerprint) \
            or not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", certified_at) \
            or not version.startswith("codex-cli ") or not isinstance(calls, list):
        raise RuntimeError("summary certificate metadata is incomplete")
    source = report.read_text(encoding="utf-8")
    source = _replace_once(source, r"真实 Codex CLI [^<]+<br>",
                           f"真实 Codex CLI {html.escape(version.removeprefix('codex-cli '))}<br>",
                           "Codex version")
    source = _replace_once(
        source,
        r'<div class="metric"><b>\d+</b><span>主创作链 MCP 调用</span></div>',
        f'<div class="metric"><b>{len(calls)}</b><span>主创作链 MCP 调用</span></div>',
        "observed-call metric")
    timestamp = certified_at.removesuffix("Z").replace("T", " ") + " UTC"
    source = _replace_once(
        source, r"认证时间：[^<]+ · Commit <code>[0-9a-f]{40}</code>",
        f"认证时间：{timestamp} · Commit <code>{commit}</code>", "certification footer")
    abbreviated = fingerprint[:23] + "…" + fingerprint[-8:]
    source = _replace_once(
        source, r"Certificate <code>sha256:[0-9a-f]{16}…[0-9a-f]{8}</code>",
        f"Certificate <code>{abbreviated}</code>", "certificate fingerprint")
    report.write_text(source, encoding="utf-8")
    command = [str(browser), "--headless=new", "--disable-gpu", "--hide-scrollbars",
               "--window-size=1440,1440", f"--screenshot={screenshot}", report.as_uri()]
    subprocess.run(command, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def _load_reducer(script_dir: Path):
    path = script_dir / "business_solution_codex_trace_certificate.py"
    spec = importlib.util.spec_from_file_location("business_certificate_renderer", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def _page(title: str, description: str, tool: str, ordinal: int,
          commit: str, certificate_fingerprint: str) -> str:
    return f"""<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\">
<style>
*{{box-sizing:border-box}}body{{margin:0;background:#f4f6f8;color:#17212b;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif}}
.frame{{width:1440px;height:900px;padding:54px 64px;background:linear-gradient(135deg,#f8fafc,#eef3f7)}}
.top{{display:flex;justify-content:space-between;align-items:center;margin-bottom:34px}}.brand{{font-size:26px;font-weight:750}}.badge{{padding:10px 18px;border-radius:24px;background:#dff6e8;color:#12613b;font-weight:700}}
.card{{height:650px;border:1px solid #dbe3ea;background:white;border-radius:22px;box-shadow:0 18px 60px #1b334a18;padding:42px 48px}}
.eyebrow{{color:#547084;font-weight:700;letter-spacing:.08em}}h1{{font-size:52px;margin:15px 0 18px}}.desc{{font-size:25px;line-height:1.55;color:#31485a;max-width:1040px}}
.event{{margin-top:42px;border-left:6px solid #2878d0;background:#f2f7fc;border-radius:12px;padding:26px 30px}}.event strong{{display:block;font-size:22px;margin-bottom:12px}}code{{font-size:25px;color:#135b9d}}
.result{{margin-top:28px;display:flex;gap:18px}}.pill{{padding:12px 20px;border-radius:10px;background:#eaf7ef;color:#17643d;font-weight:700}}
.foot{{margin-top:44px;color:#6d7f8c;font-size:16px;display:flex;justify-content:space-between}}.mono{{font-family:ui-monospace,SFMono-Regular,Menlo,monospace}}
</style><body><main class=\"frame\"><header class=\"top\"><div class=\"brand\">Codex × Resource Gateway</div><div class=\"badge\">真实 trace · 脱敏过程截图</div></header>
<section class=\"card\"><div class=\"eyebrow\">BUSINESS AUTHORING · STEP {ordinal}</div><h1>{html.escape(title)}</h1><p class=\"desc\">{html.escape(description)}</p>
<div class=\"event\"><strong>实际 Codex MCP 事件</strong><code>{html.escape(tool)}</code><div class=\"result\"><span class=\"pill\">status = completed</span><span class=\"pill\">server validated</span></div></div>
<div class=\"foot\"><span>仅展示工具名、顺序和完成状态；不保存参数、结果或内部引用。</span><span class=\"mono\">commit {html.escape(commit[:12])} · cert {html.escape(certificate_fingerprint[-12:])}</span></div></section></main></body></html>"""


def render(trace: Path, certificate_path: Path, output_dir: Path, browser: Path) -> dict:
    """Validate the real trace, render six screenshots and bind their digests to the certificate."""
    reducer = _load_reducer(Path(__file__).resolve().parent)
    calls, _message, completed, _thread = reducer.load_trace(trace)
    if not completed:
        raise RuntimeError("Codex trace did not complete")
    certificate = json.loads(certificate_path.read_text(encoding="utf-8"))
    output_dir.mkdir(parents=True, exist_ok=True)
    images = []
    with tempfile.TemporaryDirectory(prefix="rg-codex-process-render.") as directory:
        temporary = Path(directory)
        for number, title, tool, description in STAGES:
            matches = [(index + 1, call) for index, call in enumerate(calls)
                       if call["tool"] == tool and call["successful"]]
            if not matches:
                raise RuntimeError(f"required real Codex event is absent: {tool}")
            ordinal = matches[0][0]
            stem = f"business-solution-codex-process-{number}"
            page = temporary / f"{stem}.html"
            target = output_dir / f"{stem}.png"
            page.write_text(_page(title, description, tool, ordinal,
                                  certificate["repositoryCommit"],
                                  certificate["certificateFingerprint"]), encoding="utf-8")
            command = [str(browser), "--headless=new", "--disable-gpu", "--hide-scrollbars",
                       "--window-size=1440,900", f"--screenshot={target}", page.as_uri()]
            subprocess.run(command, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            digest = hashlib.sha256(target.read_bytes()).hexdigest()
            images.append({"stage": number, "title": title, "tool": tool,
                           "traceOrdinal": ordinal, "file": target.name,
                           "sha256": f"sha256:{digest}"})
    manifest = {
        "schemaVersion": "rg.businessCodexProcessEvidence.v1",
        "evidenceKind": "REDACTED_REAL_CODEX_TRACE_RENDER",
        "repositoryCommit": certificate["repositoryCommit"],
        "certificateFingerprint": certificate["certificateFingerprint"],
        "images": images,
    }
    manifest_path = output_dir / "business-solution-codex-process-v1.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--trace", type=Path, required=True)
    parser.add_argument("--certificate", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--browser", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--summary-screenshot", type=Path, required=True)
    args = parser.parse_args()
    render(args.trace, args.certificate, args.output_dir, args.browser)
    render_summary(args.certificate, args.report, args.summary_screenshot, args.browser)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
