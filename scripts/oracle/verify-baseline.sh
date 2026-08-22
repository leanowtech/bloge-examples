#!/usr/bin/env bash
set -u -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ $# -gt 1 ]]; then
  echo "BASELINE_FAIL: expected at most one argument" >&2
  exit 2
fi
case "${1:-}" in
  "") mode=--baseline-only ;;
  --capture-check) mode=--capture-check ;;
  *) echo "BASELINE_FAIL: unknown argument: $1" >&2; exit 2 ;;
esac

output="$(python3 "$SCRIPT_DIR/verify-step0.py" "$mode" 2>&1)"
status=$?
if [[ $status -ne 0 ]]; then
  [[ -n "$output" ]] && printf '%s\n' "$output" >&2
  exit "$status"
fi
if [[ -z "$output" ]]; then
  echo "BASELINE_FAIL: verifier returned empty output" >&2
  exit 1
fi
if [[ "$output" != "BASELINE_PASS" ]]; then
  printf 'BASELINE_FAIL: unknown verifier output: %s\n' "$output" >&2
  exit 1
fi

printf '%s\n' "$output"
