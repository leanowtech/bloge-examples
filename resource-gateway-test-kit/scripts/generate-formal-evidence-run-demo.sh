#!/usr/bin/env bash

set -u
set -o pipefail
umask 077

usage() {
  printf '%s\n' "usage: generate-formal-evidence-run-demo.sh <absolute-output-directory>" >&2
}

if [[ "$#" -ne 1 || "$1" != /* ]]; then
  usage
  exit 2
fi

OUTPUT_DIR="$1"
NODE_BIN="${NODE_BIN:-node}"

if ! command -v "$NODE_BIN" >/dev/null 2>&1; then
  printf '%s\n' "node is unavailable; install Node.js to generate the demo" >&2
  exit 3
fi

if [[ -e "$OUTPUT_DIR" ]]; then
  if [[ ! -d "$OUTPUT_DIR" ]] || find "$OUTPUT_DIR" -mindepth 1 -maxdepth 1 \
      -print -quit 2>/dev/null | grep -q .; then
    printf '%s\n' "output directory must be absent or empty" >&2
    exit 2
  fi
else
  if ! mkdir -p "$OUTPUT_DIR" 2>/dev/null; then
    exit 3
  fi
fi

if ! chmod 700 "$OUTPUT_DIR" 2>/dev/null; then
  exit 3
fi

if ! REAL_OUTPUT_DIR="$(cd "$OUTPUT_DIR" 2>/dev/null && pwd -P)"; then
  exit 3
fi

if ! "$NODE_BIN" - "$REAL_OUTPUT_DIR" <<'NODE'
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const outputDirectory = process.argv[2];
const evidenceRoot = path.join(outputDirectory, 'evidence-root');
const manifestPath = path.join(outputDirectory, 'manifest.json');

function canonical(value) {
  if (Array.isArray(value)) {
    return `[${value.map(canonical).join(',')}]`;
  }
  if (value !== null && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((key) =>
      `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

function sha256(bytes) {
  return `sha256:${crypto.createHash('sha256').update(bytes).digest('hex')}`;
}

function canonicalFingerprint(value) {
  return sha256(Buffer.from(canonical(value), 'utf8'));
}

function seededFingerprint(character) {
  return `sha256:${character.repeat(64)}`;
}

const obligations = Array.from({length: 14}, (_, index) => ({
  id: `FELT-${String(index + 1).padStart(2, '0')}`,
  status: 'NOT_RUN',
  evidencePaths: [],
}));

const manifest = {
  contractId: 'RG-CS-FELT-v1',
  runId: seededFingerprint('0'),
  candidatePinFingerprint: seededFingerprint('1'),
  inputPinFingerprint: seededFingerprint('2'),
  environmentPinFingerprint: seededFingerprint('3'),
  executionWindow: {
    startedAt: '2026-01-01T00:00:00Z',
    endedAt: '2026-01-01T00:01:00Z',
  },
  independentReview: {
    reviewerFingerprint: seededFingerprint('4'),
    reviewedAt: '2026-01-01T00:02:00Z',
    reviewFingerprint: seededFingerprint('5'),
  },
  obligations,
  openP0: 1,
  openP1: 1,
  passed: 0,
  failed: 0,
  blocked: 0,
  notRun: 14,
  verificationLevel: 'INCOMPLETE',
  formalPassCount: 0,
  formalExpectedCount: 27,
  evidenceCount: 0,
  evidenceByteSize: 0,
  evidenceInventory: [],
  inventoryClosureFingerprint: canonicalFingerprint([]),
  typedEvidenceReplays: [],
  manifestFingerprint: null,
};

manifest.manifestFingerprint = canonicalFingerprint(manifest);
const manifestBytes = Buffer.from(canonical(manifest), 'utf8');

try {
  fs.mkdirSync(evidenceRoot, {mode: 0o700});
  fs.chmodSync(evidenceRoot, 0o700);
  const temporaryManifest = path.join(outputDirectory, `.manifest.${process.pid}.tmp`);
  fs.writeFileSync(temporaryManifest, manifestBytes, {flag: 'wx', mode: 0o600});
  fs.chmodSync(temporaryManifest, 0o600);
  fs.renameSync(temporaryManifest, manifestPath);
  fs.chmodSync(manifestPath, 0o600);
} catch (error) {
  process.stderr.write('demo generation failed\n');
  process.exitCode = 3;
}
NODE
then
  exit 3
fi

printf '%s\n' 'generated status=INCOMPLETE'
