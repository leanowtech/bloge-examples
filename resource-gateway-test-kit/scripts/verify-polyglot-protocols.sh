#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_KIT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
TYPESCRIPT_DIR="${TEST_KIT_DIR}/polyglot/typescript"
GO_DIR="${TEST_KIT_DIR}/polyglot/go"

for command in node go; do
  if ! command -v "${command}" >/dev/null 2>&1; then
    printf 'Required command not found: %s\n' "${command}" >&2
    exit 1
  fi
done

node -e '
const [major, minor] = process.versions.node.split(".").map(Number);
if (major < 22 || (major === 22 && minor < 18)) {
  console.error(`Node.js >= 22.18 is required; found ${process.versions.node}`);
  process.exit(1);
}
'

printf 'TypeScript protocol certification (Node.js %s)\n' "$(node --version)"
node --test "${TYPESCRIPT_DIR}/outcome-observation-verifier.test.ts"

printf 'Go protocol certification (%s)\n' "$(go version)"
(
  cd "${GO_DIR}"
  GOWORK=off go test ./...
)

printf 'Polyglot protocol certification passed.\n'
