#!/usr/bin/env bash
set -u -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ $# -gt 1 ]]; then
  echo "STEP0_FAIL: expected at most one argument" >&2
  exit 2
fi
case "${1:-}" in
  "") baseline_mode=steady ;;
  --capture-check) baseline_mode=capture ;;
  --index-check) baseline_mode=index ;;
  *) echo "STEP0_FAIL: unknown argument: $1" >&2; exit 2 ;;
esac

if [[ "$baseline_mode" == index ]]; then
  index_output="$(python3 "$SCRIPT_DIR/verify-step0.py" --index-check 2>&1)"
  index_status=$?
  if [[ $index_status -ne 0 ]]; then
    [[ -n "$index_output" ]] && printf '%s\n' "$index_output" >&2
    exit "$index_status"
  fi
  if [[ "$index_output" != "STEP0_INDEX_PASS" ]]; then
    if [[ -z "$index_output" ]]; then
      echo "STEP0_FAIL: index verifier returned empty output" >&2
    else
      printf 'STEP0_FAIL: unknown index verifier output: %s\n' "$index_output" >&2
    fi
    exit 1
  fi
  printf '%s\n' "$index_output"
  exit 0
fi

if [[ "$baseline_mode" == capture ]]; then
  baseline_output="$("$SCRIPT_DIR/verify-baseline.sh" --capture-check 2>&1)"
else
  baseline_output="$("$SCRIPT_DIR/verify-baseline.sh" 2>&1)"
fi
baseline_status=$?
if [[ $baseline_status -ne 0 ]]; then
  [[ -n "$baseline_output" ]] && printf '%s\n' "$baseline_output" >&2
  exit "$baseline_status"
fi
if [[ "$baseline_output" != "BASELINE_PASS" ]]; then
  if [[ -z "$baseline_output" ]]; then
    echo "STEP0_FAIL: baseline returned empty output" >&2
  else
    printf 'STEP0_FAIL: unknown baseline output: %s\n' "$baseline_output" >&2
  fi
  exit 1
fi

static_output="$(python3 "$SCRIPT_DIR/verify-step0.py" --static-only 2>&1)"
static_status=$?
if [[ $static_status -ne 0 ]]; then
  [[ -n "$static_output" ]] && printf '%s\n' "$static_output" >&2
  exit "$static_status"
fi
if [[ -z "$static_output" ]]; then
  echo "STEP0_FAIL: static verifier returned empty output" >&2
  exit 1
fi
if [[ "$static_output" != "STEP0_STATIC_PASS" ]]; then
  printf 'STEP0_FAIL: unknown static verifier output: %s\n' "$static_output" >&2
  exit 1
fi

echo "STEP0_PASS"
