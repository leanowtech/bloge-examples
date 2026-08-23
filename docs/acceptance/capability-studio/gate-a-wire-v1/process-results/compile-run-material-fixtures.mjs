#!/usr/env node
/**
 * compile-run-material-fixtures.mjs  –  S0 CHECK mode
 * Usage:  node compile-run-material-fixtures.mjs --check [--workspace-root <path>]
 * Exports: scanWorkspace, aggregateManifest, copyTree, mkdtempSync,
 *   spawnGenerator, runValidators, deriveOperations, buildPlan,
 *   verifyAuthorityHashes, validatePolicy, derivePaths,
 *   resolveWorkspaceRoot, mainCheck, POLICY_EXPECTED, reject, TX_NAMESPACES,
 *   runBoundedChild, assertCasefoldUnique
 */

import { createHash } from "node:crypto";
import { existsSync, lstatSync, mkdirSync, mkdtempSync as fsMkdtempSync,
  readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, relative, resolve, sep } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { spawn } from "node:child_process";
import { tmpdir } from "node:os";
import { canonicalize } from "../canonicalization/reference-fingerprint.mjs";

const POLICY_FILE = "fixture-generator-output-policy-v1.json";
const MAX_OUT = 65536; // 64 KiB bound per child stream

export const POLICY_EXPECTED = Object.freeze({
  messageVersion: "bloge.gate-a.fixture-output-policy.v1",
  mutableDirectoryRoots: [
    "process-results/challenge-input",
    "process-results/provider-fixture",
    "process-results/run-material"
  ],
  mutableFiles: [
    "process-results/invalid-abnormal-attempt-child-cancelled.json",
    "process-results/invalid-abnormal-attempt-child-crash.json",
    "process-results/invalid-abnormal-attempt-child-timeout.json",
    "process-results/invalid-abnormal-attempt-codesource-observation-unavailable.json",
    "process-results/invalid-abnormal-attempt-process-tree-residue.json",
    "process-results/invalid-abnormal-attempt-stdout-truncated.json",
    "process-results/invalid-candidate-replay-adapter-drift.json",
    "process-results/invalid-candidate-replay-obligation-drift.json",
    "process-results/invalid-independent-missing-process-observation-fingerprint.json",
    "process-results/invalid-independent-process-observation-fingerprint-reused.json",
    "process-results/invalid-independent-proof-envelope-harness-bootstrap.json",
    "process-results/invalid-independent-report-eleven-child-runs.json",
    "process-results/invalid-independent-review-guard-intrusion.json",
    "process-results/invalid-independent-wrong-exit.json",
    "process-results/invalid-independent-wrong-mechanism.json",
    "process-results/invalid-independent-wrong-terminal.json",
    "process-results/invalid-provider-guard-accepted.json",
    "process-results/invalid-provider-materialization-identity-replacement.json",
    "process-results/invalid-provider-materialization-missing-receipt.json",
    "process-results/invalid-provider-materialization-scratch-before.json",
    "process-results/invalid-replay-proof-envelope-missing-message-version.json",
    "process-results/invalid-replay-proof-envelope-parent-unavailable.json",
    "process-results/negative-fixture-expectations.json",
    "process-results/run-material-attack-vectors.json",
    "process-results/valid-abnormal-attempt-child-cancelled.json",
    "process-results/valid-abnormal-attempt-child-crash.json",
    "process-results/valid-abnormal-attempt-child-timeout.json",
    "process-results/valid-abnormal-attempt-codesource-observation-unavailable.json",
    "process-results/valid-abnormal-attempt-process-tree-residue.json",
    "process-results/valid-abnormal-attempt-stdout-truncated.json",
    "process-results/valid-candidate-challenge-request.json",
    "process-results/valid-candidate-replay-result.json",
    "process-results/valid-independent-proof-envelope.json",
    "process-results/valid-independent-verification-result.json",
    "process-results/valid-provider-materialization-observation.json",
    "process-results/valid-replay-proof-envelope-unavailable.json",
    "process-results/valid-replay-proof-envelope.json",
    "process-results/valid-replay-verification-result-invalid.json",
    "process-results/valid-replay-verification-result-unavailable.json",
    "process-results/valid-replay-verification-result.json",
    "trust-build/valid-challenge-trust-pin.json",
    "trust-build/valid-tck-provider-identity.json"
  ],
  frozenAuthority: {
    generator:        "decc8e3d55738dd2f88828080a05b7d87cdc4c174a98316404486e47bafcb1a9",
    processValidator: "47e8ed3b2b4447ce8fe67e38ef2e03bfa80ade4f31c2083e7decc0124c14d593",
    runValidator:     "d3b568e9081897270e45069335a102ff52c279edd699eadf50e029b89ba39dde",
    trustValidator:   "fddaf572d0d4a2e554f5a6b5702fcc9a2823e39e30bedf27e5e531be5ff462c6"
  }
});

export const TX_NAMESPACES = Object.freeze(new Set([
  ".fixture-publish-active", ".fixture-publish-committed",
  ".fixture-publish-rolled-back", ".fixture-publish-history"
]));

export function reject(msg) {
  throw Object.assign(new Error(`REJECT: ${msg}`), { code: "EREJECT" });
}

export function resolveWorkspaceRoot(explicit) {
  if (explicit) { if (!existsSync(explicit)) reject(`--workspace-root not found: ${explicit}`); return explicit; }
  let dir = resolve(dirname(fileURLToPath(import.meta.url)));
  for (;;) {
    const test = join(dir, "docs", "acceptance", "capability-studio", "gate-a-wire-v1");
    if (existsSync(join(test, "process-results"))) return dir;
    const parent = resolve(dir, "..");
    if (parent === dir) break;
    dir = parent;
  }
  reject("Cannot find workspace root");
}

export function derivePaths(root) {
  const wireRoot    = join(root, "docs", "acceptance", "capability-studio", "gate-a-wire-v1");
  const schemasRoot = join(root, "docs", "schemas", "resource-gateway-capability-studio");
  for (const [label, dir] of [["wireRoot", wireRoot], ["schemasRoot", schemasRoot]]) {
    let lst;
    try { lst = lstatSync(dir, { throwIfNoEntry: false }); } catch (e) { reject(`${label} lstat: ${e.code}`); }
    if (!lst) reject(`${label} not found: ${dir}`);
    if (lst.isSymbolicLink()) reject(`${label} is symlink: ${dir}`);
    if (!lst.isDirectory()) reject(`${label} not a directory: ${dir}`);
  }
  return { wireRoot, schemasRoot };
}

function normalizePosix(p) {
  return p.split("\\").join("/");
}

export function mkdtempSync(prefix) {
  return fsMkdtempSync(prefix);
}

export function assertCasefoldUnique(paths) {
  const seen = new Map();
  for (const p of paths) {
    const lower = p.toLowerCase();
    if (seen.has(lower)) reject(`casefold conflict: "${p}" vs "${seen.get(lower)}"`);
    seen.set(lower, p);
  }
}

export function scanWorkspace(absRoot, prefix, txNs, ns) {
  const entries = [];
  const seenLower = new Map();

  // Verify root is not a symlink directory
  {
    let lst;
    try { lst = lstatSync(absRoot, { throwIfNoEntry: false }); } catch (e) { reject(`absRoot lstat: ${e.code}`); }
    if (!lst) reject(`absRoot not found: ${absRoot}`);
    if (lst.isSymbolicLink()) reject(`absRoot is symlink: ${absRoot}`);
    if (!lst.isDirectory()) reject(`absRoot not a directory: ${absRoot}`);
  }

  function scan(abs, dirRel) {
    let names;
    try { names = readdirSync(abs); } catch (e) { reject(`readdir ${abs}: ${e.code}`); }
    for (const name of names) {
      // Reject and skip dot segments; TX namespace dirs skipped only at root
      if (name === "." || name === "..") continue;
      if (dirRel === "" && txNs.has(name)) continue;

      for (let i = 0; i < name.length; i++) {
        const c = name.charCodeAt(i);
        if (c < 0x20) reject(`ctrl char in name: "${name}"`);
        if (c > 0x7e) reject(`non-ASCII char in name: "${name}"`);
      }
      if (name.includes("/") || name.includes("\\")) reject(`path sep in name: "${name}"`);

      const abs2 = join(abs, name);
      let lst;
      try { lst = lstatSync(abs2, { throwIfNoEntry: false }); } catch (e) { reject(`lstat ${abs2}`); }
      if (!lst) reject(`lstat null: ${abs2}`);

      if (lst.isSymbolicLink()) {
        reject(`symlink at ${abs2}`);
      } else if (!lst.isFile() && !lst.isDirectory()) {
        reject(`special file at ${abs2}`);
      } else if (lst.isFile()) {
        let st;
        try { st = statSync(abs2); } catch (e) { reject(`stat ${abs2}: ${e.code}`); }
        if (st.nlink > 1) reject(`hard link at ${abs2}`);

        let raw;
        try { raw = readFileSync(abs2); } catch (e) { reject(`read ${abs2}: ${e.message}`); }
        const sha256 = createHash("sha256").update(raw).digest("hex");

        const relPath = dirRel ? `${ns}/${dirRel}/${name}` : `${ns}/${name}`;
        const lowerRel = relPath.toLowerCase();
        if (seenLower.has(lowerRel)) {
          const first = seenLower.get(lowerRel);
          reject(`casefold conflict: "${relPath}" vs "${first}"`);
        }
        seenLower.set(lowerRel, relPath);

        if (relPath.length === 0) reject("empty relativePath");
        if (relPath.startsWith("/")) reject(`absolute relativePath: ${relPath}`);
        // Validate relative does not escape prefix
        const segs = relPath.split("/");
        if (segs.some(s => s === "..")) reject(`'..' segment in relativePath: ${relPath}`);
        const resolvedRel = segs.filter(s => s !== "." && s !== "").join("/");
        const resolvedPrefix = `${ns}/${dirRel ? dirRel : ""}`;
        if (!resolvedRel.startsWith(resolvedPrefix) && resolvedRel !== ns)
          reject(`relativePath escapes prefix: ${relPath}`);

        entries.push({ relativePath: relPath, kind: "file", sha256, size: raw.length });
      } else if (lst.isDirectory()) {
        const childRel = dirRel ? `${dirRel}/${name}` : name;
        const relPath = `${ns}/${childRel}`;
        const lowerRel = relPath.toLowerCase();
        if (seenLower.has(lowerRel)) {
          const first = seenLower.get(lowerRel);
          reject(`casefold conflict: "${relPath}" vs "${first}"`);
        }
        seenLower.set(lowerRel, relPath);
        entries.push({ relativePath: relPath, kind: "dir", sha256: null, size: 0 });
        scan(abs2, childRel);
      }
    }
  }

  // Root entry includes ${ns}/ prefix
  entries.push({ relativePath: `${ns}/`, kind: "dir", sha256: null, size: 0 });
  scan(absRoot, "");
  entries.sort((a, b) => (a.relativePath < b.relativePath ? -1 : 1));
  return { entries };
}

export function aggregateManifest(entries) {
  const canon = entries.map(e => ({
    relativePath: e.relativePath, kind: e.kind, sha256: e.sha256, size: e.size
  }));
  const json = canonicalize(canon);
  return createHash("sha256").update(Buffer.from(json, "utf8")).digest("hex");
}

export function copyTree(src, dst, prefix, txNs) {
  mkdirSync(dst, { recursive: true });
  function cpDir(s, d) {
    mkdirSync(d, { recursive: true });
    let names;
    try { names = readdirSync(s); } catch (e) { reject(`readdir ${s}: ${e.code}`); }
    for (const name of names) {
      if (s === src && txNs.has(name)) continue;

      for (let i = 0; i < name.length; i++) {
        const c = name.charCodeAt(i);
        if (c < 0x20 || c > 0x7e) reject(`non-ASCII/ctrl in copy: "${name}"`);
      }
      if (name === "..") reject(`segment '..' in copy: "${name}"`);
      if (name === ".")  reject(`segment '.' in copy: "${name}"`);
      if (name.includes("/") || name.includes("\\")) reject(`path separator in copy: "${name}"`);
      const sa = join(s, name), da = join(d, name);
      let lst;
      try { lst = lstatSync(sa, { throwIfNoEntry: false }); } catch (e) { reject(`lstat ${sa}`); }
      if (!lst) reject(`lstat null: ${sa}`);
      if (lst.isSymbolicLink()) reject(`symlink in copy: ${sa}`);
      if (lst.isFile()) {
        if (lst.nlink > 1) reject(`hard link in copy: ${sa}`);
        try { writeFileSync(da, readFileSync(sa)); } catch (e) { reject(`write ${da}: ${e.message}`); }
      } else if (lst.isDirectory()) {
        cpDir(sa, da);
      } else {
        reject(`special file in copy: ${sa}`);
      }
    }
  }
  cpDir(src, dst);
}

export function verifyAuthorityHashes(isolateRoot) {
  const files = {
    generator:        join(isolateRoot, "docs/acceptance/capability-studio/gate-a-wire-v1", "process-results", "generate-run-material-fixtures.mjs"),
    processValidator: join(isolateRoot, "docs/acceptance/capability-studio/gate-a-wire-v1", "process-results", "validate-fixtures.py"),
    runValidator:     join(isolateRoot, "docs/acceptance/capability-studio/gate-a-wire-v1", "process-results", "validate_run_material.py"),
    trustValidator:   join(isolateRoot, "docs/acceptance/capability-studio/gate-a-wire-v1", "trust-build", "validate-fixtures.py")
  };
  for (const [k, p] of Object.entries(files)) {
    if (!existsSync(p)) reject(`authority file missing: ${p}`);
    const sha = createHash("sha256").update(readFileSync(p)).digest("hex");
    const exp = POLICY_EXPECTED.frozenAuthority[k];
    if (sha !== exp) reject(`authority[${k}] mismatch: exp=${exp} act=${sha}`);
  }
}

/**
 * Shared bounded child runner.
 * Returns { done } promise.
 * Throws Error with code "EOUTOVERFLOW" if either stream exceeds the bound.
 */
export function runBoundedChild(cmd, args, opts = {}) {
  const bound = opts.maxOutputBytes ?? MAX_OUT;
  const done = new Promise((resolve, reject) => {
    let settled = false;
    let overflowed = false;
    let killTimer = null;

    const child = spawn(cmd, args, {
      stdio: ["ignore", "pipe", "pipe"],
      cwd: opts.cwd,
      killSignal: "SIGTERM"
    });

    let stdoutSize = 0, stderrSize = 0;
    let stdoutChunks = [], stderrChunks = [];

    function checkOverflow(size) {
      if (size > bound) {
        if (!overflowed) {
          overflowed = true;
          if (killTimer) { clearTimeout(killTimer); killTimer = null; }
          child.kill("SIGTERM");
          killTimer = setTimeout(() => {
            child.kill("SIGKILL");
          }, 1000);
        }
      }
    }

    child.stdout.on("data", d => {
      if (settled || overflowed) return;
      const remaining = bound + 1 - stdoutSize;
      if (d.length > remaining) {
        stdoutChunks.push(d.subarray(0, remaining > 0 ? remaining : 0));
        stdoutSize = bound + 1;
      } else {
        stdoutChunks.push(d);
        stdoutSize += d.length;
      }
      if (stdoutSize > bound) {
        if (!overflowed) {
          overflowed = true;
          if (killTimer) { clearTimeout(killTimer); killTimer = null; }
          child.kill("SIGTERM");
          killTimer = setTimeout(() => { child.kill("SIGKILL"); }, 1000);
        }
      }
    });

    child.stderr.on("data", d => {
      if (settled || overflowed) return;
      const remaining = bound + 1 - stderrSize;
      if (d.length > remaining) {
        stderrChunks.push(d.subarray(0, remaining > 0 ? remaining : 0));
        stderrSize = bound + 1;
      } else {
        stderrChunks.push(d);
        stderrSize += d.length;
      }
      if (stderrSize > bound) {
        if (!overflowed) {
          overflowed = true;
          if (killTimer) { clearTimeout(killTimer); killTimer = null; }
          child.kill("SIGTERM");
          killTimer = setTimeout(() => { child.kill("SIGKILL"); }, 1000);
        }
      }
    });

    child.on("error", e => {
      if (settled) return;
      settled = true;
      if (killTimer) { clearTimeout(killTimer); killTimer = null; }
      const err = new Error("ESPAWN");
      err.code = "ESPAWN";
      reject(err);
    });

    child.on("close", code => {
      if (settled) return;
      settled = true;
      if (killTimer) { clearTimeout(killTimer); killTimer = null; }
      if (overflowed) {
        const err = new Error("EOUTOVERFLOW");
        err.code = "EOUTOVERFLOW";
        reject(err);
      } else {
        resolve({ exitCode: code, stdout: Buffer.concat(stdoutChunks), stderr: Buffer.concat(stderrChunks) });
      }
    });
  });
  return { done };
}

export async function spawnGenerator(script, cwd) {
  const runner = runBoundedChild(process.execPath, [script], { cwd });
  const r = await runner.done;
  if (r.exitCode !== 0) {
    const err = new Error(`Generator exited ${r.exitCode}`);
    err.code = "EGENFAIL";
    err.exitCode = r.exitCode;
    throw err;
  }
  return { exitCode: r.exitCode, stdout: r.stdout, stderr: r.stderr };
}

export async function runValidators(stageRepo) {
  const wire = join(stageRepo, "docs/acceptance/capability-studio/gate-a-wire-v1");
  const vals = [
    { n: "processValidator", s: "validate-fixtures.py",   c: join(wire, "process-results") },
    { n: "runValidator",    s: "validate_run_material.py", c: join(wire, "process-results") },
    { n: "trustValidator",  s: "validate-fixtures.py",    c: join(wire, "trust-build") }
  ];
  const outcomes = {};
  for (const { n, s, c } of vals) {
    const sp = join(c, s);
    if (!existsSync(sp)) reject(`validator missing: ${sp}`);
    const runner = runBoundedChild("python3", [s], { cwd: c });
    let r;
    try {
      r = await runner.done;
    } catch (e) {
      throw e;
    }
    outcomes[n] = { exitCode: r.exitCode };
    if (r.exitCode !== 0) {
      const err = new Error(`Validator ${n} exited ${r.exitCode}`);
      err.code = "EVALFAIL";
      err.validator = n;
      err.exitCode = r.exitCode;
      throw err;
    }
  }
  return outcomes;
}

export function deriveOperations(before, after, policy) {
  for (const e of [...before, ...after]) {
    if (!e.relativePath.startsWith("wire/") && !e.relativePath.startsWith("schemas/"))
      reject(`Entry outside wire/ or schemas/ namespace: ${e.relativePath}`);
  }

  function checkUnique(entries, label) {
    const seen = new Set();
    for (const e of entries) {
      if (seen.has(e.relativePath)) reject(`Duplicate ${label} relativePath: ${e.relativePath}`);
      seen.add(e.relativePath);
    }
  }
  checkUnique(before, "before");
  checkUnique(after,  "after");

  const bw = before.filter(e => e.relativePath.startsWith("wire/"));
  const aw = after.filter(e  => e.relativePath.startsWith("wire/"));
  const bm = new Map(bw.map(e => [e.relativePath.slice(5), e]));
  const am = new Map(aw.map(e  => [e.relativePath.slice(5), e]));

  const bsch = before.filter(e => e.relativePath.startsWith("schemas/"));
  const asch = after.filter(e  => e.relativePath.startsWith("schemas/"));
  const bsm = new Map(bsch.map(e => [e.relativePath.slice(8), e]));
  const asm = new Map(asch.map(e  => [e.relativePath.slice(8), e]));

  const ops = [];

  const mutableSet = new Set(policy.mutableFiles);
  const mutableRootSet = new Set(policy.mutableDirectoryRoots);

  // k is already wire/-stripped (e.g. "process-results/file.json")
  function isMutable(k) {
    if (mutableSet.has(k)) return true;
    // root itself or descendant of a mutable directory root
    for (const root of mutableRootSet) {
      if (k === root || k.startsWith(root + "/")) return true;
    }
    return false;
  }

  for (const [k, v] of bm) {
    if (!am.has(k)) {
      if (!isMutable(k)) reject(`Wire file ${k} deleted outside mutable area`);
      ops.push({ relativePath: k, type: "DELETE", beforeSha256: v.sha256, afterSha256: null, kind: v.kind });
    } else {
      const a = am.get(k);
      if (a.sha256 !== v.sha256 || a.size !== v.size) {
        if (!isMutable(k)) reject(`Wire file ${k} changed without DELETE+CREATE`);
        ops.push({ relativePath: k, type: "REPLACE", beforeSha256: v.sha256, afterSha256: a.sha256, kind: a.kind });
      }
    }
  }

  for (const [k, v] of am) {
    if (!bm.has(k)) {
      if (!isMutable(k)) reject(`Wire file ${k} created outside mutable area`);
      ops.push({ relativePath: k, type: "CREATE", beforeSha256: null, afterSha256: v.sha256, kind: v.kind });
    }
  }

  for (const [k, v] of bsm) {
    if (!asm.has(k)) reject(`Schema file ${k} deleted`);
    const a = asm.get(k);
    if (a.kind !== v.kind || a.sha256 !== v.sha256 || a.size !== v.size)
      reject(`Schema file ${k} changed`);
  }
  for (const [k] of asm) {
    if (!bsm.has(k)) reject(`Schema file ${k} created`);
  }

  return ops;
}

export async function buildPlan(fields) {
  const body = {
    messageVersion: 1,
    policyFingerprint: fields.policyFingerprint,
    authorityHashes: fields.authorityHashes,
    beforeManifestSha256: fields.beforeManifestSha256,
    afterManifestSha256: fields.afterManifestSha256,
    operations: fields.operations,
    planFingerprint: null
  };
  const json = canonicalize(body);
  const sha = createHash("sha256").update(Buffer.from(json, "utf8")).digest("hex");
  return { ...body, planFingerprint: `sha256:${sha}` };
}

export async function validatePolicy(path) {
  let raw;
  try { raw = readFileSync(path, "utf8"); } catch (e) { reject(`read policy: ${e.message}`); }
  let pol;
  try { pol = JSON.parse(raw); } catch (e) { reject(`parse policy: ${e.message}`); }

  const polKeys = Object.keys(pol).sort();
  const expKeys = ["frozenAuthority", "messageVersion", "mutableDirectoryRoots", "mutableFiles", "policyFingerprint"];
  if (polKeys.length !== expKeys.length || polKeys.some((k, i) => k !== expKeys[i]))
    reject(`policy keys: ${polKeys}`);

  if (pol.messageVersion !== POLICY_EXPECTED.messageVersion)
    reject(`messageVersion: ${pol.messageVersion}`);

  const dirs = pol.mutableDirectoryRoots;
  if (!Array.isArray(dirs) || dirs.length !== 3) reject(`mutableDirectoryRoots: ${dirs}`);
  const sortedDirs = [...dirs].sort();
  const expDirs = [...POLICY_EXPECTED.mutableDirectoryRoots].sort();
  for (let i = 0; i < 3; i++) {
    if (sortedDirs[i] !== expDirs[i]) reject(`mutableDirectoryRoots[${i}]: exp=${expDirs[i]} act=${sortedDirs[i]}`);
  }

  const files = pol.mutableFiles;
  if (!Array.isArray(files) || files.length !== 42) reject(`mutableFiles: ${files.length}`);
  const sortedFiles = [...files].sort();
  const expFiles = [...POLICY_EXPECTED.mutableFiles].sort();
  for (let i = 0; i < 42; i++) {
    if (sortedFiles[i] !== expFiles[i]) reject(`mutableFiles[${i}]: exp=${expFiles[i]} act=${sortedFiles[i]}`);
  }

  for (const f of files) {
    if (f.startsWith("/")) reject(`mutableFile absolute path: ${f}`);
    if (f.includes("\\")) reject(`mutableFile backslash: ${f}`);
    if (f.includes("/..") || f.endsWith("..")) reject(`mutableFile traversal: ${f}`);
    for (let i = 0; i < f.length; i++) {
      const c = f.charCodeAt(i);
      if (c < 0x20) reject(`mutableFile ctrl char at pos ${i}: ${f}`);
    }
  }

  const dup = new Set();
  for (const d of dirs) { if (dup.has(d)) reject(`dup dir: ${d}`); dup.add(d); }
  dup.clear();
  for (const f of files) { if (dup.has(f)) reject(`dup file: ${f}`); dup.add(f); }

  const auth = pol.frozenAuthority || {};
  for (const [k, exp] of Object.entries(POLICY_EXPECTED.frozenAuthority))
    if (auth[k] !== exp) reject(`authority[${k}]: exp=${exp} act=${auth[k]}`);

  const json2 = canonicalize({ ...pol, policyFingerprint: null });
  const computed = `sha256:${createHash("sha256").update(Buffer.from(json2, "utf8")).digest("hex")}`;
  if (pol.policyFingerprint !== computed) reject(`fingerprint drift: exp=${computed} act=${pol.policyFingerprint}`);

  return pol;
}

export async function mainCheck(ws, opts = {}) {
  const { wireRoot, schemasRoot } = derivePaths(ws);
  const policyPath = join(wireRoot, "process-results", POLICY_FILE);
  const policy = await validatePolicy(policyPath);

  const { entries: lw }  = scanWorkspace(wireRoot,    wireRoot,    TX_NAMESPACES, "wire");
  const { entries: ls }   = scanWorkspace(schemasRoot, schemasRoot, TX_NAMESPACES, "schemas");
  const livePreAgg = aggregateManifest([...lw, ...ls]);

  const tempParent = opts?.tempParent ?? tmpdir();
  {
    let lst;
    try { lst = lstatSync(tempParent, { throwIfNoEntry: false }); } catch (e) { reject(`tempParent lstat: ${e.code}`); }
    if (!lst) reject(`tempParent not found: ${tempParent}`);
    if (lst.isSymbolicLink()) reject(`tempParent is symlink: ${tempParent}`);
    if (!lst.isDirectory()) reject(`tempParent not a directory: ${tempParent}`);
  }
  const stageRepo = mkdtempSync(join(tempParent, "gate-a-check-"));
  const stageWire    = join(stageRepo, "docs/acceptance/capability-studio/gate-a-wire-v1");
  const stageSchemas = join(stageRepo, "docs/schemas/resource-gateway-capability-studio");
  let cleaned = false;
  let cleanupErr = null;

  function cleanup() {
    if (cleaned) return;
    cleaned = true;
    try { rmSync(stageRepo, { recursive:true, force:true }); }
    catch (e) { cleanupErr = e; }
  }

  let businessErr = null;
  try {
    copyTree(wireRoot,    stageWire,    wireRoot,    TX_NAMESPACES);
    copyTree(schemasRoot, stageSchemas, schemasRoot, TX_NAMESPACES);
    verifyAuthorityHashes(stageRepo);

    const { entries: bw } = scanWorkspace(stageWire,    stageWire,    TX_NAMESPACES, "wire");
    const { entries: bs } = scanWorkspace(stageSchemas, stageSchemas, TX_NAMESPACES, "schemas");
    const beforeAgg = aggregateManifest([...bw, ...bs]);

    const gs = join(stageWire, "process-results", "generate-run-material-fixtures.mjs");
    await spawnGenerator(gs, join(stageWire, "process-results"));

    const { entries: aw } = scanWorkspace(stageWire,    stageWire,    TX_NAMESPACES, "wire");
    const { entries: as2 } = scanWorkspace(stageSchemas, stageSchemas, TX_NAMESPACES, "schemas");
    const afterAgg = aggregateManifest([...aw, ...as2]);

    const ops = deriveOperations([...bw, ...bs], [...aw, ...as2], policy);
    const vo = await runValidators(stageRepo);
    const plan = await buildPlan({
      policyFingerprint: policy.policyFingerprint,
      authorityHashes: policy.frozenAuthority,
      beforeManifestSha256: beforeAgg,
      afterManifestSha256: afterAgg,
      operations: ops
    });

    const { entries: lw2 } = scanWorkspace(wireRoot,    wireRoot,    TX_NAMESPACES, "wire");
    const { entries: ls2 } = scanWorkspace(schemasRoot, schemasRoot, TX_NAMESPACES, "schemas");
    const livePostAgg = aggregateManifest([...lw2, ...ls2]);
    if (livePostAgg !== livePreAgg) reject(`Live changed: pre=${livePreAgg} post=${livePostAgg}`);

    return {
      messageVersion: 1,
      status: "CHECKED",
      planFingerprint: plan.planFingerprint,
      beforeManifestSha256: beforeAgg,
      afterManifestSha256: afterAgg,
      operationCount: ops.length,
      validatorOutcomes: vo
    };
  } catch (e) {
    businessErr = e;
    throw e;
  } finally {
    cleanup();
    if (cleanupErr && !businessErr) throw cleanupErr;
    if (cleanupErr && businessErr) {
      const agg = new AggregateError([businessErr, cleanupErr], "business error with cleanup failure");
      agg.cause = businessErr;
      throw agg;
    }
  }
}

const isMain = (() => {
  try {
    return import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
  } catch (_) {
    return false;
  }
})();
if (isMain) {
  const args = process.argv.slice(2);
  const modes = new Set();
  let ws = null;
  for (let i = 0; i < args.length; i++) {
    const a = args[i];
    if (a === "--check") modes.add("check");
    else if (a === "--workspace-root") { if (i + 1 >= args.length) { console.error("ERROR: --workspace-root needs arg"); process.exit(1); } ws = args[++i]; }
    else if (a === "--publish" || a === "--recover") { console.error(`ERROR: --${a.slice(2)} not supported (S0 CHECK only)`); process.exit(1); }
    else if (a.startsWith("--")) { console.error(`ERROR: unknown flag: ${a}`); process.exit(1); }
  }
  if (!modes.has("check")) { console.error("ERROR: --check required (S0 CHECK only)"); process.exit(1); }
  const root = resolveWorkspaceRoot(ws);
  mainCheck(root).then(r => { process.stdout.write(JSON.stringify(r) + "\n"); }).catch(err => {
    if (err.code === "EREJECT") { console.error(err.message); process.exit(1); }
    throw err;
  });
}
