#!/usr/bin/env bash

set -euo pipefail

BASE_URL="${BLOGE_VISUAL_CANVAS_BASE_URL:-http://localhost:${BLOGE_VISUAL_CANVAS_PORT:-8080}}"
ACTION=""
COMMIT_POLICY="${BLOGE_DSL_IMPORT_COMMIT_POLICY:-renderable}"
FAIL_ON=""
INCLUDE_DRAFTS=0
DRY_RUN=0
REQUEST_FILE=""
OUT_FILE=""
ACTOR="${BLOGE_DSL_IMPORT_ACTOR:-dsl-batch-import-cli}"
CHANGE_SOURCE="${BLOGE_DSL_IMPORT_CHANGE_SOURCE:-dsl-batch-import-cli}"
CHANGE_SUMMARY="${BLOGE_DSL_IMPORT_CHANGE_SUMMARY:-}"
REASON="${BLOGE_DSL_IMPORT_REASON:-}"

OPERATOR_LIBRARY_IDS=()
INLINE_LIBRARY_FILES=()
DSL_FILES=()
DSL_DIRS=()

usage() {
    cat <<'EOF'
Usage:
  scripts/bloge-dsl-batch-import.sh report [options]
  scripts/bloge-dsl-batch-import.sh commit [options]

Modes:
  report   Call POST /api/visual/dsl-imports/batch-report.
  commit   Call POST /api/visual/dsl-imports/batch-commit.

Input options:
  --dsl FILE                  Add one .bloge source file.
  --dsl-dir DIR               Add every *.bloge file under DIR, sorted by path.
  --operator-library ID       Use an already imported visual operator library id. Repeatable.
  --inline-library-json FILE  Inline one bloge.visualOperatorLibrary.v1 JSON file. Repeatable.
  --request FILE              Use a complete JSON request body instead of building one from files.
  --include-drafts            Ask batch report items to include projected GraphDraft payloads.

Commit options:
  --commit-policy POLICY      renderable, fully-projected, or rewrite-allowed (default: renderable).
  --actor VALUE               Audit actor for batch-commit (default: dsl-batch-import-cli).
  --change-source VALUE       Audit source for batch-commit (default: dsl-batch-import-cli).
  --change-summary VALUE      Audit summary for batch-commit.
  --reason VALUE              Operator-facing reason for batch-commit.

Execution options:
  --base-url URL              Gateway base URL (default: http://localhost:${BLOGE_VISUAL_CANVAS_PORT:-8080}).
  --out FILE                  Write response JSON to FILE. In --dry-run, write request JSON to FILE.
  --fail-on POLICY            CI gate. report: none|blocked|repair|rewrite-blocked|not-fully-projected.
                              commit: none|failed|skipped-or-failed|not-all-committed.
                              Defaults: report=blocked, commit=skipped-or-failed.
  --dry-run                   Print the generated request JSON without calling the server.
  -h, --help                  Show this help.

Examples:
  scripts/bloge-dsl-batch-import.sh report \
    --operator-library risk-policy \
    --dsl-dir resource-gateway-examples/src/main/resources/bloge \
    --out target/dsl-batch-report.json

  scripts/bloge-dsl-batch-import.sh commit \
    --operator-library risk-policy \
    --dsl-dir resource-gateway-examples/src/main/resources/bloge \
    --commit-policy rewrite-allowed \
    --out target/dsl-batch-commit.json

  scripts/bloge-dsl-batch-import.sh report --dry-run \
    --inline-library-json risk-policy.visual-library.json \
    --dsl loan-approval.bloge
EOF
}

fail() {
    echo "bloge-dsl-batch-import: $*" >&2
    exit 1
}

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || fail "missing required command: $1"
}

if [ "$#" -eq 0 ]; then
    usage
    exit 1
fi

case "$1" in
    report|commit)
        ACTION="$1"
        shift
        ;;
    -h|--help)
        usage
        exit 0
        ;;
    *)
        fail "first argument must be report or commit"
        ;;
esac

while [ "$#" -gt 0 ]; do
    case "$1" in
        --base-url)
            BASE_URL="${2:-}"
            shift 2
            ;;
        --dsl)
            DSL_FILES+=("${2:-}")
            shift 2
            ;;
        --dsl-dir)
            DSL_DIRS+=("${2:-}")
            shift 2
            ;;
        --operator-library|--operator-library-id|--catalog-id)
            OPERATOR_LIBRARY_IDS+=("${2:-}")
            shift 2
            ;;
        --inline-library-json)
            INLINE_LIBRARY_FILES+=("${2:-}")
            shift 2
            ;;
        --request)
            REQUEST_FILE="${2:-}"
            shift 2
            ;;
        --out)
            OUT_FILE="${2:-}"
            shift 2
            ;;
        --commit-policy)
            COMMIT_POLICY="${2:-}"
            shift 2
            ;;
        --fail-on)
            FAIL_ON="${2:-}"
            shift 2
            ;;
        --actor)
            ACTOR="${2:-}"
            shift 2
            ;;
        --change-source)
            CHANGE_SOURCE="${2:-}"
            shift 2
            ;;
        --change-summary)
            CHANGE_SUMMARY="${2:-}"
            shift 2
            ;;
        --reason)
            REASON="${2:-}"
            shift 2
            ;;
        --include-drafts)
            INCLUDE_DRAFTS=1
            shift
            ;;
        --dry-run)
            DRY_RUN=1
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            fail "unknown option: $1"
            ;;
    esac
done

[ -n "${BASE_URL}" ] || fail "--base-url must not be empty"
[ -z "${FAIL_ON}" ] && {
    if [ "${ACTION}" = "report" ]; then
        FAIL_ON="blocked"
    else
        FAIL_ON="skipped-or-failed"
    fi
}

require_cmd python3
if [ "${DRY_RUN}" -eq 0 ]; then
    require_cmd curl
fi

for dir in ${DSL_DIRS+"${DSL_DIRS[@]}"}; do
    [ -d "${dir}" ] || fail "DSL directory does not exist: ${dir}"
    while IFS= read -r file; do
        DSL_FILES+=("${file}")
    done < <(find "${dir}" -type f -name '*.bloge' | sort)
done

if [ -n "${REQUEST_FILE}" ]; then
    [ -f "${REQUEST_FILE}" ] || fail "request file does not exist: ${REQUEST_FILE}"
else
    [ "${#DSL_FILES[@]}" -gt 0 ] || fail "provide --dsl, --dsl-dir, or --request"
fi

for file in ${DSL_FILES+"${DSL_FILES[@]}"}; do
    [ -f "${file}" ] || fail "DSL file does not exist: ${file}"
done

for file in ${INLINE_LIBRARY_FILES+"${INLINE_LIBRARY_FILES[@]}"}; do
    [ -f "${file}" ] || fail "inline library JSON file does not exist: ${file}"
done

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/bloge-dsl-batch-import.XXXXXX")"
cleanup() {
    rm -rf "${TMP_DIR}"
}
trap cleanup EXIT

DSL_LIST="${TMP_DIR}/dsl-files.txt"
LIBRARY_ID_LIST="${TMP_DIR}/library-ids.txt"
INLINE_LIST="${TMP_DIR}/inline-library-files.txt"
REQUEST_BODY="${TMP_DIR}/request.json"
RESPONSE_BODY="${TMP_DIR}/response.json"

: > "${DSL_LIST}"
for file in ${DSL_FILES+"${DSL_FILES[@]}"}; do
    printf '%s\n' "${file}" >> "${DSL_LIST}"
done

: > "${LIBRARY_ID_LIST}"
for id in ${OPERATOR_LIBRARY_IDS+"${OPERATOR_LIBRARY_IDS[@]}"}; do
    printf '%s\n' "${id}" >> "${LIBRARY_ID_LIST}"
done

: > "${INLINE_LIST}"
for file in ${INLINE_LIBRARY_FILES+"${INLINE_LIBRARY_FILES[@]}"}; do
    printf '%s\n' "${file}" >> "${INLINE_LIST}"
done

python3 - "${ACTION}" "${INCLUDE_DRAFTS}" "${COMMIT_POLICY}" "${REQUEST_FILE}" \
    "${DSL_LIST}" "${LIBRARY_ID_LIST}" "${INLINE_LIST}" > "${REQUEST_BODY}" <<'PY'
import json
import pathlib
import sys

action, include_drafts, commit_policy, request_file, dsl_list, library_id_list, inline_list = sys.argv[1:8]
mode = "batch-report" if action == "report" else "batch-commit"

if request_file:
    with open(request_file, "r", encoding="utf-8") as handle:
        request = json.load(handle)
    request.setdefault("mode", mode)
    request.setdefault("includeDrafts", include_drafts == "1")
    if action == "commit":
        request.setdefault("commitPolicy", commit_policy)
else:
    sources = []
    for raw in pathlib.Path(dsl_list).read_text(encoding="utf-8").splitlines():
        path = pathlib.Path(raw)
        if not raw:
            continue
        sources.append({
            "sourceId": raw,
            "dsl": path.read_text(encoding="utf-8"),
        })

    operator_library_ids = [
        item.strip()
        for item in pathlib.Path(library_id_list).read_text(encoding="utf-8").splitlines()
        if item.strip()
    ]

    inline_libraries = []
    for raw in pathlib.Path(inline_list).read_text(encoding="utf-8").splitlines():
        if not raw:
            continue
        with open(raw, "r", encoding="utf-8") as handle:
            inline_libraries.append(json.load(handle))

    request = {
        "operatorLibraryIds": operator_library_ids,
        "inlineLibraries": inline_libraries,
        "mode": mode,
        "includeDrafts": include_drafts == "1",
        "sources": sources,
    }
    if action == "commit":
        request["commitPolicy"] = commit_policy

print(json.dumps(request, ensure_ascii=False, indent=2))
PY

if [ "${DRY_RUN}" -eq 1 ]; then
    if [ -n "${OUT_FILE}" ]; then
        mkdir -p "$(dirname "${OUT_FILE}")"
        cp "${REQUEST_BODY}" "${OUT_FILE}"
        echo "Dry-run request written to ${OUT_FILE}" >&2
    else
        cat "${REQUEST_BODY}"
        printf '\n'
    fi
    exit 0
fi

BASE_URL="${BASE_URL%/}"
ENDPOINT="${BASE_URL}/api/visual/dsl-imports/batch-${ACTION}"

if [ "${ACTION}" = "commit" ]; then
    QUERY="$(python3 - "${ACTOR}" "${CHANGE_SOURCE}" "${CHANGE_SUMMARY}" "${REASON}" <<'PY'
from urllib.parse import urlencode
import sys

actor, change_source, change_summary, reason = sys.argv[1:5]
print(urlencode({
    "actor": actor,
    "changeSource": change_source,
    "changeSummary": change_summary,
    "reason": reason,
}))
PY
)"
    ENDPOINT="${ENDPOINT}?${QUERY}"
fi

HTTP_CODE="$(
    curl -sS -o "${RESPONSE_BODY}" -w '%{http_code}' \
        -X POST "${ENDPOINT}" \
        -H 'Content-Type: application/json' \
        --data @"${REQUEST_BODY}" || true
)"

case "${HTTP_CODE}" in
    2??)
        ;;
    *)
        echo "Request failed with HTTP ${HTTP_CODE}: ${ENDPOINT}" >&2
        cat "${RESPONSE_BODY}" >&2 || true
        exit 1
        ;;
esac

if [ -n "${OUT_FILE}" ]; then
    mkdir -p "$(dirname "${OUT_FILE}")"
    cp "${RESPONSE_BODY}" "${OUT_FILE}"
else
    cat "${RESPONSE_BODY}"
    printf '\n'
fi

python3 - "${ACTION}" "${FAIL_ON}" "${RESPONSE_BODY}" <<'PY'
import json
import sys

action, fail_on, response_file = sys.argv[1:4]
with open(response_file, "r", encoding="utf-8") as handle:
    data = json.load(handle)
summary = data.get("summary") or {}

def count(name):
    value = summary.get(name)
    return int(value or 0)

failed = False

if action == "report":
    source_count = count("sourceCount")
    renderable = count("renderableSourceCount")
    fully_projected = count("fullyProjectedSourceCount")
    repairable = count("repairableSourceCount")
    blocked = count("blockedSourceCount")
    rewrite_allowed = count("rewriteAllowedSourceCount")
    rewrite_blocked = count("rewriteBlockedSourceCount")
    print(
        "DSL batch report: "
        f"sources={source_count}, renderable={renderable}, fullyProjected={fully_projected}, "
        f"repairable={repairable}, blocked={blocked}, rewriteAllowed={rewrite_allowed}, "
        f"rewriteBlocked={rewrite_blocked}",
        file=sys.stderr,
    )
    if fail_on == "blocked":
        failed = blocked > 0
    elif fail_on == "repair":
        failed = blocked > 0 or repairable > 0
    elif fail_on == "rewrite-blocked":
        failed = blocked > 0 or rewrite_blocked > 0
    elif fail_on == "not-fully-projected":
        failed = source_count != fully_projected
    elif fail_on == "none":
        failed = False
    else:
        print(f"Unknown report --fail-on policy: {fail_on}", file=sys.stderr)
        sys.exit(1)
else:
    source_count = count("sourceCount")
    committed = count("committedSourceCount")
    skipped = count("skippedSourceCount")
    failed_count = count("failedSourceCount")
    print(
        "DSL batch commit: "
        f"sources={source_count}, committed={committed}, skipped={skipped}, failed={failed_count}",
        file=sys.stderr,
    )
    if fail_on == "failed":
        failed = failed_count > 0
    elif fail_on == "skipped-or-failed":
        failed = skipped > 0 or failed_count > 0
    elif fail_on == "not-all-committed":
        failed = source_count != committed
    elif fail_on == "none":
        failed = False
    else:
        print(f"Unknown commit --fail-on policy: {fail_on}", file=sys.stderr)
        sys.exit(1)

if failed:
    print(f"CI gate failed by --fail-on={fail_on}", file=sys.stderr)
    sys.exit(2)
PY
