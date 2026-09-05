#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CODEX_BIN="${CODEX_BIN:-codex}"
MVN_BIN="${MVN:-mvn}"
RG_CERT_PORT="${RG_CERT_PORT:-18081}"
RG_MCP_ENDPOINT="http://127.0.0.1:${RG_CERT_PORT}/mcp"
RG_INSTANCE_ENDPOINT="${RG_MCP_ENDPOINT%/mcp}/internal/agent-tdd/certification-instance"
OUTPUT_FILE="${1:-${ROOT_DIR}/resource-gateway-examples/target/business-solution-codex-certification.json}"
JAVA_BIN="${JAVA_BIN:-java}"

usage() {
    cat <<'EOF'
Usage: scripts/certify-agent-tdd-codex.sh [certificate.json]

Builds and starts Resource Gateway from the current clean commit, then certifies one business-language
Solution journey and all 15 semantic recall families through repository-blind Codex processes.
RG_CERT_PORT defaults to 18081.
The private raw Codex trace is removed by default. Set KEEP_RAW_CODEX_TRACE=true only for
an approved local investigation; the script then prints its mode-0600 temporary path.
EOF
}

if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then
    usage
    exit 0
fi
if ! [[ "${RG_CERT_PORT}" =~ ^[0-9]+$ ]] || [ "${RG_CERT_PORT}" -lt 1024 ] \
        || [ "${RG_CERT_PORT}" -gt 65535 ]; then
    echo "RG_CERT_PORT must be an unprivileged TCP port." >&2
    exit 1
fi
repository_is_clean() {
    git -C "${ROOT_DIR}" diff --quiet \
        && git -C "${ROOT_DIR}" diff --cached --quiet \
        && [ -z "$(git -C "${ROOT_DIR}" ls-files --others --exclude-standard)" ]
}

if ! repository_is_clean; then
    echo "Certification requires a clean committed worktree." >&2
    exit 1
fi
for command in "${CODEX_BIN}" "${MVN_BIN}" "${JAVA_BIN}" awk chflags curl git lsof python3 openssl sandbox-exec tr; do
    if ! command -v "${command}" >/dev/null 2>&1; then
        echo "Required command is unavailable: ${command}" >&2
        exit 1
    fi
done
CODEX_EXECUTABLE="$(command -v "${CODEX_BIN}")"
if [[ "${CODEX_EXECUTABLE}" != /* ]]; then
    echo "Codex executable must resolve to an absolute path." >&2
    exit 1
fi
CODE_MODE_EXECUTABLE="$(dirname "${CODEX_EXECUTABLE}")/codex-code-mode-host"
if [ ! -x "${CODE_MODE_EXECUTABLE}" ] || [ -L "${CODE_MODE_EXECUTABLE}" ]; then
    echo "Codex MCP orchestration host must be a regular executable beside Codex." >&2
    exit 1
fi
if lsof -nP -iTCP:"${RG_CERT_PORT}" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "Certification port is already occupied; stop its listener or choose another RG_CERT_PORT." >&2
    exit 1
fi

PRIVATE_DIR=""
WORKSPACE_DIR=""
ISOLATED_CODEX_DIR=""
CODEX_RUNTIME_DIR=""
TRACE_FILE=""
FAMILY_TRACE_DIR=""
FAMILY_MANIFEST_FILE=""
SETUP_MANIFEST_FILE=""
PRIVATE_JAR=""
TEMP_OUTPUT=""
SERVICE_PID=""

remove_certification_temp_dir() {
    local directory="${1:-}"
    local expected_prefix="${2:-}"
    if [ -z "${directory}" ] || [ ! -d "${directory}" ]; then
        return
    fi
    case "$(basename "${directory}")" in
        "${expected_prefix}"*) rm -rf "${directory}" ;;
        *) echo "Refusing to remove unexpected certification directory: ${directory}" >&2 ;;
    esac
}

cleanup() {
    if [ -n "${SERVICE_PID:-}" ] && kill -0 "${SERVICE_PID}" 2>/dev/null; then
        kill "${SERVICE_PID}" 2>/dev/null || true
        wait "${SERVICE_PID}" 2>/dev/null || true
    fi
    unset RG_MCP_TOKEN AGENT_TOKEN SETUP_TOKEN REVIEW_TOKEN FIXTURE_KEY
    if [ -n "${TEMP_OUTPUT:-}" ]; then
        rm -f "${TEMP_OUTPUT}"
    fi
    if [ -n "${PRIVATE_JAR:-}" ] && [ -e "${PRIVATE_JAR}" ]; then
        chflags nouchg "${PRIVATE_JAR}" 2>/dev/null || true
    fi
    if [ "${KEEP_RAW_CODEX_TRACE:-false}" = "true" ] \
            && [ -n "${PRIVATE_DIR:-}" ] && [ -d "${PRIVATE_DIR}" ]; then
        echo "Private trace retained at ${TRACE_FILE:-${PRIVATE_DIR}}" >&2
    else
        remove_certification_temp_dir "${PRIVATE_DIR:-}" "rg-codex-cert."
    fi
    if [ -n "${WORKSPACE_DIR:-}" ] && [ -d "${WORKSPACE_DIR}" ]; then
        chmod 700 "${WORKSPACE_DIR}" 2>/dev/null || true
    fi
    remove_certification_temp_dir "${WORKSPACE_DIR:-}" "rg-codex-workspace."
    remove_certification_temp_dir "${ISOLATED_CODEX_DIR:-}" "rg-codex-home."
    remove_certification_temp_dir "${CODEX_RUNTIME_DIR:-}" "rg-codex-runtime."
}
trap cleanup EXIT

PRIVATE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/rg-codex-cert.XXXXXX")"
PRIVATE_DIR="$(cd "${PRIVATE_DIR}" && pwd -P)"
WORKSPACE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/rg-codex-workspace.XXXXXX")"
WORKSPACE_DIR="$(cd "${WORKSPACE_DIR}" && pwd -P)"
ISOLATED_CODEX_DIR="$(mktemp -d "${TMPDIR:-/tmp}/rg-codex-home.XXXXXX")"
ISOLATED_CODEX_DIR="$(cd "${ISOLATED_CODEX_DIR}" && pwd -P)"
CODEX_RUNTIME_DIR="$(mktemp -d "${TMPDIR:-/tmp}/rg-codex-runtime.XXXXXX")"
CODEX_RUNTIME_DIR="$(cd "${CODEX_RUNTIME_DIR}" && pwd -P)"
chmod 700 "${PRIVATE_DIR}"
chmod 700 "${ISOLATED_CODEX_DIR}" "${CODEX_RUNTIME_DIR}"
TRACE_FILE="${PRIVATE_DIR}/trace.jsonl"
PROMPT_FILE="${PRIVATE_DIR}/prompt.txt"
FAMILY_TRACE_DIR="${PRIVATE_DIR}/families"
FAMILY_MANIFEST_FILE="${PRIVATE_DIR}/family-manifest.json"
FAMILY_RUN_INDEX="${PRIVATE_DIR}/family-run-index.tsv"
FAMILY_SUITE_FILE="${ROOT_DIR}/scripts/business-solution-recall-family-suite-v1.json"
SETUP_FIXTURE_FILE="${ROOT_DIR}/scripts/business-recall-platform-fixture-v1.json"
SETUP_MANIFEST_FILE="${PRIVATE_DIR}/setup-manifest.json"
SERVICE_LOG="${PRIVATE_DIR}/resource-gateway.log"
SANDBOX_PROFILE="${PRIVATE_DIR}/codex-certification.sb"
BOARD_FILE="${PRIVATE_DIR}/board.json"
BOARD_CURL_CONFIG="${PRIVATE_DIR}/board-curl.conf"
mkdir -p "${FAMILY_TRACE_DIR}"
chmod 700 "${FAMILY_TRACE_DIR}"
touch "${TRACE_FILE}" "${FAMILY_RUN_INDEX}"
chmod 600 "${TRACE_FILE}" "${FAMILY_RUN_INDEX}"
mkdir -p "$(dirname "${OUTPUT_FILE}")"
chmod 500 "${WORKSPACE_DIR}"
umask 077

SOURCE_CODEX_DIR="${CODEX_HOME:-${HOME}/.codex}"
SOURCE_AUTH_FILE="${SOURCE_CODEX_DIR}/auth.json"
if [ ! -f "${SOURCE_AUTH_FILE}" ] || [ -L "${SOURCE_AUTH_FILE}" ]; then
    echo "Codex authentication must be a regular auth.json file for isolated certification." >&2
    exit 1
fi
cp "${SOURCE_AUTH_FILE}" "${ISOLATED_CODEX_DIR}/auth.json"
chmod 600 "${ISOLATED_CODEX_DIR}/auth.json"

AGENT_TOKEN="$(openssl rand -hex 32)"
SETUP_TOKEN="$(openssl rand -hex 32)"
REVIEW_TOKEN="$(openssl rand -hex 32)"
FIXTURE_KEY="$(openssl rand -base64 32 | tr -d '\n')"
if [ "${AGENT_TOKEN}" = "${REVIEW_TOKEN}" ] || [ "${SETUP_TOKEN}" = "${REVIEW_TOKEN}" ] \
        || [ "${SETUP_TOKEN}" = "${AGENT_TOKEN}" ]; then
    echo "Generated demo identities unexpectedly collided." >&2
    exit 1
fi

COMMON_GIT_DIR="$(git -C "${ROOT_DIR}" rev-parse --path-format=absolute --git-common-dir)"
COMMON_REPOSITORY="$(dirname "${COMMON_GIT_DIR}")"
EXTRA_DENY_RULES=""
if [[ "${ROOT_DIR}" == */worktrees/* ]]; then
    CODEX_DATA_ROOT="${ROOT_DIR%%/worktrees/*}"
    EXTRA_DENY_RULES="
(deny file-read* file-write* (subpath \"${CODEX_DATA_ROOT}/worktrees\"))
(deny file-read* file-write* (subpath \"${CODEX_DATA_ROOT}/memories\"))"
fi
if [ "${COMMON_REPOSITORY}" != "${ROOT_DIR}" ]; then
    EXTRA_DENY_RULES="${EXTRA_DENY_RULES}
(deny file-read* file-write* (subpath \"${COMMON_REPOSITORY}\"))"
fi
if [[ "${ROOT_DIR}${PRIVATE_DIR}${COMMON_REPOSITORY}${CODEX_DATA_ROOT:-}${SOURCE_CODEX_DIR}${CODE_MODE_EXECUTABLE}" == *'"'* ]]; then
    echo "Repository paths containing a quote cannot be represented in the macOS sandbox profile." >&2
    exit 1
fi
cat > "${SANDBOX_PROFILE}" <<EOF
(version 1)
(allow default)
(deny process-exec)
(allow process-exec (literal "${CODEX_EXECUTABLE}"))
(allow process-exec (literal "${CODE_MODE_EXECUTABLE}"))
(deny file-write*)
(allow file-write* (literal "${TRACE_FILE}"))
(allow file-write* (subpath "${FAMILY_TRACE_DIR}"))
(allow file-write* (subpath "${ISOLATED_CODEX_DIR}"))
(allow file-write* (subpath "${CODEX_RUNTIME_DIR}"))
(deny file-read* (subpath "${ROOT_DIR}"))
(deny file-write* (subpath "${ROOT_DIR}"))
(deny file-read* file-write* (subpath "${PRIVATE_DIR}"))
(deny file-read* file-write* (subpath "${SOURCE_CODEX_DIR}"))
${EXTRA_DENY_RULES}
EOF
if ! CODEX_HOME="${ISOLATED_CODEX_DIR}" TMPDIR="${CODEX_RUNTIME_DIR}" \
        sandbox-exec -f "${SANDBOX_PROFILE}" "${CODEX_EXECUTABLE}" --version >/dev/null; then
    echo "macOS certification sandbox cannot start the exact Codex executable." >&2
    exit 1
fi
if sandbox-exec -f "${SANDBOX_PROFILE}" /usr/bin/true >/dev/null 2>&1; then
    echo "macOS sandbox did not deny non-Codex process execution." >&2
    exit 1
fi

REPOSITORY_COMMIT="$(git -C "${ROOT_DIR}" rev-parse HEAD)"
PRODUCTION_TREE_OBJECT="$(git -C "${ROOT_DIR}" rev-parse \
    "${REPOSITORY_COMMIT}:resource-gateway-examples/src/main")"
PRODUCTION_TREE_FINGERPRINT="sha256:$(printf '%s' "${PRODUCTION_TREE_OBJECT}" \
    | openssl dgst -sha256 | awk '{print $2}')"
"${MVN_BIN}" -f "${ROOT_DIR}/resource-gateway-examples/pom.xml" clean package -DskipTests
if [ "$(git -C "${ROOT_DIR}" rev-parse HEAD)" != "${REPOSITORY_COMMIT}" ] \
        || ! repository_is_clean; then
    echo "Repository changed while the certification JAR was being built." >&2
    exit 1
fi
BUILT_JAR="${ROOT_DIR}/resource-gateway-examples/target/bloge-examples-resource-gateway-1.0.0.jar"
if [ ! -f "${BUILT_JAR}" ] || [ -L "${BUILT_JAR}" ]; then
    echo "Clean package did not create the expected regular Resource Gateway JAR." >&2
    exit 1
fi
PRIVATE_JAR="${PRIVATE_DIR}/resource-gateway-certification.jar"
cp "${BUILT_JAR}" "${PRIVATE_JAR}.copying"
chmod 400 "${PRIVATE_JAR}.copying"
mv "${PRIVATE_JAR}.copying" "${PRIVATE_JAR}"
chflags uchg "${PRIVATE_JAR}"
JAR_SHA256="sha256:$(openssl dgst -sha256 "${PRIVATE_JAR}" | awk '{print $2}')"
INSTANCE_NONCE=""
if lsof -nP -iTCP:"${RG_CERT_PORT}" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "Certification port became occupied while packaging; refusing to reuse its listener." >&2
    exit 1
fi

start_owned_service() {
    local workload_token="$1"
    INSTANCE_NONCE="$(openssl rand -hex 32)"
    (
        cd "${ROOT_DIR}/resource-gateway-examples"
        if [ "sha256:$(openssl dgst -sha256 "${PRIVATE_JAR}" | awk '{print $2}')" != "${JAR_SHA256}" ]; then
            echo "Private certification JAR changed before process launch." >&2
            exit 1
        fi
        exec env \
            RG_API_RESOURCE_AUTHORING_ENABLED=true \
            RG_REUSABLE_FLOW_AUTHORING_ENABLED=true \
            RG_AUTHORING_LOCAL_SCHEMA_BOOTSTRAP_ENABLED=true \
            RG_CORRECTNESS_AUTHORING_ENABLED=true \
            RG_CORRECTNESS_FIXTURE_MATERIAL_ENABLED=true \
            RG_CORRECTNESS_FIXTURE_MATERIAL_ACTIVE_KEY_ID=business-cert-v1 \
            RG_CORRECTNESS_FIXTURE_MATERIAL_KEY_RING="business-cert-v1=${FIXTURE_KEY}" \
            RG_INTEGRATION_DEMO_TOKEN="${workload_token}" \
            RG_INTEGRATION_DEMO_REVIEW_TOKEN="${REVIEW_TOKEN}" \
            RG_INTEGRATION_ENVIRONMENT_ID=local \
            "${JAVA_BIN}" --enable-preview -jar "${PRIVATE_JAR}" \
            "--server.address=127.0.0.1" "--server.port=${RG_CERT_PORT}" \
            "--spring.datasource.url=jdbc:h2:file:${PRIVATE_DIR}/recall-certification;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false" \
            "--gateway.agent-tdd.certification-instance.enabled=true" \
            "--gateway.agent-tdd.certification-instance.instance-nonce=${INSTANCE_NONCE}" \
            "--gateway.agent-tdd.certification-instance.repository-commit=${REPOSITORY_COMMIT}" \
            "--gateway.agent-tdd.certification-instance.expected-jar-sha256=${JAR_SHA256}"
    ) >> "${SERVICE_LOG}" 2>&1 &
    SERVICE_PID=$!
}

stop_owned_service() {
    if [ -n "${SERVICE_PID:-}" ] && kill -0 "${SERVICE_PID}" 2>/dev/null; then
        kill "${SERVICE_PID}"
        wait "${SERVICE_PID}" 2>/dev/null || true
    fi
    SERVICE_PID=""
}

start_owned_service "${AGENT_TOKEN}"

verify_runtime_identity() {
    local actual_identity
    kill -0 "${SERVICE_PID}" 2>/dev/null || return 1
    actual_identity="$(curl --fail --silent --max-time 3 "${RG_INSTANCE_ENDPOINT}")" || return 1
    python3 -c '
import json
import sys
actual = json.load(sys.stdin)
expected = {
    "schemaVersion": "rg.agentTddCertificationInstance.v1",
    "instanceNonce": sys.argv[1],
    "repositoryCommit": sys.argv[2],
    "jarSha256": sys.argv[3],
}
raise SystemExit(0 if actual == expected else 1)
' "${INSTANCE_NONCE}" "${REPOSITORY_COMMIT}" "${JAR_SHA256}" <<< "${actual_identity}"
}

wait_owned_service() {
    local service_ready=false
    for ((attempt = 0; attempt < 120; attempt++)); do
        if verify_runtime_identity; then
            service_ready=true
            break
        fi
        if ! kill -0 "${SERVICE_PID}" 2>/dev/null; then
            echo "Owned Resource Gateway exited before exposing its certification identity." >&2
            tail -40 "${SERVICE_LOG}" >&2 || true
            exit 1
        fi
        sleep 1
    done
    if [ "${service_ready}" != "true" ]; then
        echo "Owned Resource Gateway did not expose the exact certification identity in time." >&2
        tail -40 "${SERVICE_LOG}" >&2 || true
        exit 1
    fi
}

wait_owned_service
export RG_MCP_TOKEN="${AGENT_TOKEN}"
curl --fail --silent --show-error "${RG_MCP_ENDPOINT%/mcp}/examples/gateway" >/dev/null

BATCH_LABEL="$(date -u +%Y%m%dT%H%M%SZ)-$$"
cat > "${PROMPT_FILE}" <<EOF
请为取消费争议建立一项新的业务解法。这次演示批次是 ${BATCH_LABEL}，请创建独立版本，不要复用名称相近但业务含义不同的旧能力。

先了解平台已经具备哪些业务积木，并查找“取消责任方”是否已有含义完全一致、当前可用的业务事实。如果没有，就把它定义为由客服人员在当前争议订单中确认的事实，可能值只有“乘客”和“司机”；无法确认时必须转人工复核。

业务规则是：乘客在免费取消时限之后主动取消，由乘客承担，维持取消费；司机导致取消，由司机承担，退还取消费；其他情况转人工复核。每个处置都要返回结果和业务解释。

请组合这项解法，并提交两条等待我确认的标准案例：一条是乘客超时取消，假设“维持取消费”返回成功，预期维持且解释为乘客责任；另一条是司机导致取消，假设“退还取消费”返回成功，预期退还且解释为司机责任。标准答案负责人是取消争议业务负责人。提交后请重新查看待确认案例清单，确认这两条都在且仍等待我确认。

请自行使用平台提供的创作说明完成结构化定义，不要让我提供格式、字段或技术引用。不要替我批准案例，不要开始验证、签署或发布。完成后只用业务语言告诉我：复用了还是新建了哪些业务能力、规则是否完整、两条案例是否已提交，以及我下一步需要确认什么。
EOF

CODEX_VERSION="$(CODEX_HOME="${ISOLATED_CODEX_DIR}" TMPDIR="${CODEX_RUNTIME_DIR}" \
    sandbox-exec -f "${SANDBOX_PROFILE}" "${CODEX_EXECUTABLE}" --version | head -1)"
CERTIFIED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

BASE_CODEX_ARGS=(
    exec
    --disable apps --disable in_app_browser --disable multi_agent --disable multi_agent_v2
    --disable plugins --disable remote_plugin --disable skill_search
    --disable browser_use --disable browser_use_external --disable computer_use
    --disable hooks --disable image_generation --disable memories
    --disable shell_snapshot --disable shell_tool --disable unified_exec
    --disable view_image --disable workspace_dependencies --disable standalone_web_search
    --disable tool_suggest --disable skill_mcp_dependency_install --disable enable_mcp_apps
)
if [ -n "${CODEX_MODEL:-}" ]; then
    BASE_CODEX_ARGS+=(-m "${CODEX_MODEL}")
fi
BASE_CODEX_ARGS+=(
    --ephemeral --json --ignore-user-config --ignore-rules --skip-git-repo-check
    --sandbox read-only -C "${WORKSPACE_DIR}"
)
READ_MCP_ARGS=(
    -c "mcp_servers.rg_read.url=\"${RG_MCP_ENDPOINT}\""
    -c 'mcp_servers.rg_read.bearer_token_env_var="RG_MCP_TOKEN"'
    -c 'mcp_servers.rg_read.http_headers={"X-Purpose"="AGENT_TDD_READ","X-RG-Surface"="BUSINESS_SOLUTION"}'
    -c 'mcp_servers.rg_read.enabled_tools=["rg.library.overview.get","rg.capability.search","rg.entity.list","rg.entity.get","rg.journey.next","rg.solution.golden.list"]'
    -c 'mcp_servers.rg_read.required=true'
)
AUTHOR_MCP_ARGS=(
    -c "mcp_servers.rg_author.url=\"${RG_MCP_ENDPOINT}\""
    -c 'mcp_servers.rg_author.bearer_token_env_var="RG_MCP_TOKEN"'
    -c 'mcp_servers.rg_author.http_headers={"X-Purpose"="AGENT_TDD_AUTHORING","X-RG-Surface"="BUSINESS_SOLUTION"}'
    -c 'mcp_servers.rg_author.enabled_tools=["rg.journey.start","rg.feature.define","rg.feature.handoff","rg.scenario.define","rg.instruction.define","rg.solution.compose","rg.solution.golden.propose"]'
    -c 'mcp_servers.rg_author.required=true'
)

run_codex_turn() {
    local prompt_file="$1"
    local trace_file="$2"
    shift 2
    (
        cd "${WORKSPACE_DIR}"
        CODEX_HOME="${ISOLATED_CODEX_DIR}" TMPDIR="${CODEX_RUNTIME_DIR}" \
            sandbox-exec -f "${SANDBOX_PROFILE}" \
            "${CODEX_EXECUTABLE}" "${BASE_CODEX_ARGS[@]}" "$@" -
    ) < "${prompt_file}" > "${trace_file}"
}

set +e
run_codex_turn "${PROMPT_FILE}" "${TRACE_FILE}" \
    "${READ_MCP_ARGS[@]}" "${AUTHOR_MCP_ARGS[@]}"
CODEX_EXIT=$?
set -e
if ! verify_runtime_identity; then
    echo "The owned Resource Gateway identity changed or disappeared during the authoring turn." >&2
    exit 1
fi
cat > "${BOARD_CURL_CONFIG}" <<EOF
url = "${RG_MCP_ENDPOINT%/mcp}/api/agent-tdd/board"
header = "Authorization: Bearer ${AGENT_TOKEN}"
header = "X-Purpose: AGENT_TDD_READ"
fail
silent
show-error
output = "${BOARD_FILE}"
EOF
chmod 600 "${BOARD_CURL_CONFIG}"
curl --config "${BOARD_CURL_CONFIG}"
rm -f "${BOARD_CURL_CONFIG}"
chmod 600 "${BOARD_FILE}"
python3 - "${ROOT_DIR}" "${TRACE_FILE}" "${CODEX_EXIT}" "${BOARD_FILE}" <<'PY'
import importlib.util
import json
import pathlib
import sys

module_path = pathlib.Path(sys.argv[1]) / "scripts/business_solution_codex_trace_certificate.py"
spec = importlib.util.spec_from_file_location("business_solution_certificate", module_path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
calls, final_message, completed, _thread_id = module.load_trace(pathlib.Path(sys.argv[2]))
if int(sys.argv[3]) != 0 or not completed:
    raise SystemExit("Primary Codex authoring turn did not complete.")
chain, _proposal = module.require_business_sequence(calls)
if not final_message.strip() or module.TECHNICAL_FINAL_PATTERN.search(final_message):
    raise SystemExit("Primary Codex authoring summary is missing or exposes technical vocabulary.")
module.verify_board(json.loads(pathlib.Path(sys.argv[4]).read_text(encoding="utf-8")), chain)
PY

python3 - "${FAMILY_SUITE_FILE}" <<'PY'
import json
import sys

suite = json.load(open(sys.argv[1], encoding="utf-8"))
families = suite.get("families")
if suite.get("schemaVersion") != "rg.businessRecallFamilySuite.v1" or not isinstance(families, list):
    raise SystemExit("Recall family suite has an unsupported shape.")
if len(families) != 15 or len({entry.get("familyId") for entry in families}) != 15:
    raise SystemExit("Recall family suite must define 15 unique families.")
PY

while IFS=$'\t' read -r FAMILY_ID FAMILY_EXPECTED; do
    if [ "${FAMILY_ID}" = "near-meaning-distractor" ]; then
        # The first synonym turn must recall the primary Feature without certification-only
        # distractors. Seed the remaining adversarial families only after that proof exists.
        SYNONYM_TRACE="${FAMILY_TRACE_DIR}/synonym-rewrite.trace.jsonl"
        SYNONYM_EXIT="$(awk -F '\t' '$1 == "synonym-rewrite" { print $4 }' "${FAMILY_RUN_INDEX}")"
        python3 - "${ROOT_DIR}" "${TRACE_FILE}" "${SYNONYM_TRACE}" "${SYNONYM_EXIT}" <<'PY'
import importlib.util
import pathlib
import sys

module_path = pathlib.Path(sys.argv[1]) / "scripts/business_solution_codex_trace_certificate.py"
spec = importlib.util.spec_from_file_location("business_solution_certificate", module_path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
calls, _message, _completed, _thread_id = module.load_trace(pathlib.Path(sys.argv[2]))
chain, _proposal = module.require_business_sequence(calls)
module.require_family_trace({
    "familyId": "synonym-rewrite",
    "expectedBehaviorClass": "RECALL_TOP1",
    "traceFile": pathlib.Path(sys.argv[3]),
    "exitCode": int(sys.argv[4]),
}, chain, {"byRole": {}})
PY
        stop_owned_service
        start_owned_service "${SETUP_TOKEN}"
        wait_owned_service
        python3 "${ROOT_DIR}/scripts/business_recall_family_setup.py" \
            --endpoint "${RG_MCP_ENDPOINT}" \
            --author-token "${SETUP_TOKEN}" \
            --review-token "${REVIEW_TOKEN}" \
            --fixture "${SETUP_FIXTURE_FILE}" \
            --output "${SETUP_MANIFEST_FILE}"
        chmod 600 "${SETUP_MANIFEST_FILE}"
        stop_owned_service
        start_owned_service "${AGENT_TOKEN}"
        wait_owned_service
        export RG_MCP_TOKEN="${AGENT_TOKEN}"
        curl --fail --silent --show-error "${RG_MCP_ENDPOINT%/mcp}/examples/gateway" >/dev/null
    fi
    FAMILY_PROMPT_FILE="${FAMILY_TRACE_DIR}/${FAMILY_ID}.prompt.txt"
    FAMILY_TRACE_FILE="${FAMILY_TRACE_DIR}/${FAMILY_ID}.trace.jsonl"
    python3 - "${FAMILY_SUITE_FILE}" "${FAMILY_ID}" > "${FAMILY_PROMPT_FILE}" <<'PY'
import json
import sys

suite = json.load(open(sys.argv[1], encoding="utf-8"))
entry = next(item for item in suite["families"] if item["familyId"] == sys.argv[2])
print(entry["prompt"])
PY
    chmod 600 "${FAMILY_PROMPT_FILE}"
    touch "${FAMILY_TRACE_FILE}"
    chmod 600 "${FAMILY_TRACE_FILE}"
    set +e
    run_codex_turn "${FAMILY_PROMPT_FILE}" "${FAMILY_TRACE_FILE}" \
        "${READ_MCP_ARGS[@]}" "${AUTHOR_MCP_ARGS[@]}"
    FAMILY_EXIT=$?
    set -e
    printf '%s\t%s\t%s\t%s\n' "${FAMILY_ID}" "${FAMILY_EXPECTED}" \
        "${FAMILY_TRACE_FILE}" "${FAMILY_EXIT}" >> "${FAMILY_RUN_INDEX}"
    if ! verify_runtime_identity; then
        echo "The owned Resource Gateway identity changed during family ${FAMILY_ID}." >&2
        exit 1
    fi
done < <(python3 - "${FAMILY_SUITE_FILE}" <<'PY'
import json
import sys

for entry in json.load(open(sys.argv[1], encoding="utf-8"))["families"]:
    print(f'{entry["familyId"]}\t{entry["expectedBehaviorClass"]}')
PY
)

python3 - "${FAMILY_RUN_INDEX}" "${FAMILY_MANIFEST_FILE}" "${SETUP_MANIFEST_FILE}" <<'PY'
import json
import sys

families = []
with open(sys.argv[1], encoding="utf-8") as source:
    for line in source:
        family_id, expected, trace_file, exit_code = line.rstrip("\n").split("\t")
        families.append({
            "familyId": family_id,
            "expectedBehaviorClass": expected,
            "traceFile": trace_file,
            "exitCode": int(exit_code),
        })
with open(sys.argv[2], "w", encoding="utf-8") as target:
    json.dump({"schemaVersion": "rg.businessRecallFamilyTraceSet.v1",
               "setupManifestFile": sys.argv[3], "families": families},
              target, ensure_ascii=False, indent=2)
    target.write("\n")
PY
chmod 600 "${FAMILY_MANIFEST_FILE}"

if [ "$(git -C "${ROOT_DIR}" rev-parse HEAD)" != "${REPOSITORY_COMMIT}" ] \
        || ! repository_is_clean; then
    echo "Repository commit, tracked files or untracked sources changed during certification." >&2
    exit 1
fi

TEMP_OUTPUT="${OUTPUT_FILE}.tmp.$$"
python3 "${ROOT_DIR}/scripts/business_solution_codex_trace_certificate.py" "${TRACE_FILE}" \
    --family-manifest "${FAMILY_MANIFEST_FILE}" \
    --repository-commit "${REPOSITORY_COMMIT}" \
    --codex-version "${CODEX_VERSION}" \
    --certified-at "${CERTIFIED_AT}" \
    --runtime-instance-nonce "${INSTANCE_NONCE}" \
    --runtime-jar-sha256 "${JAR_SHA256}" \
    --production-tree-fingerprint "${PRODUCTION_TREE_FINGERPRINT}" \
    --board-projection "${BOARD_FILE}" \
    --exit-code "${CODEX_EXIT}" > "${TEMP_OUTPUT}"
chmod 600 "${TEMP_OUTPUT}"
mv "${TEMP_OUTPUT}" "${OUTPUT_FILE}"
TEMP_OUTPUT=""
echo "Agent TDD Codex certification passed: ${OUTPUT_FILE}"
