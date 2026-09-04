#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CODEX_BIN="${CODEX_BIN:-codex}"
MVN_BIN="${MVN:-mvn}"
RG_CERT_PORT="${RG_CERT_PORT:-18081}"
RG_MCP_ENDPOINT="http://127.0.0.1:${RG_CERT_PORT}/mcp"
RG_INSTANCE_ENDPOINT="${RG_MCP_ENDPOINT%/mcp}/internal/agent-tdd/certification-instance"
OUTPUT_FILE="${1:-${ROOT_DIR}/resource-gateway-examples/target/agent-tdd-codex-certification.json}"
JAVA_BIN="${JAVA_BIN:-java}"

usage() {
    cat <<'EOF'
Usage: scripts/certify-agent-tdd-codex.sh [certificate.json]

Builds and starts Resource Gateway from the current clean commit, then certifies the first
business journey through a repository-blind Codex process. RG_CERT_PORT defaults to 18081.
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
for command in "${CODEX_BIN}" "${MVN_BIN}" "${JAVA_BIN}" awk curl git lsof python3 openssl sandbox-exec; do
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
if lsof -nP -iTCP:"${RG_CERT_PORT}" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "Certification port is already occupied; stop its listener or choose another RG_CERT_PORT." >&2
    exit 1
fi

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
SERVICE_LOG="${PRIVATE_DIR}/resource-gateway.log"
SANDBOX_PROFILE="${PRIVATE_DIR}/codex-certification.sb"
touch "${TRACE_FILE}"
chmod 600 "${TRACE_FILE}"
mkdir -p "$(dirname "${OUTPUT_FILE}")"
chmod 500 "${WORKSPACE_DIR}"
umask 077
TEMP_OUTPUT=""
SERVICE_PID=""

SOURCE_CODEX_DIR="${CODEX_HOME:-${HOME}/.codex}"
SOURCE_AUTH_FILE="${SOURCE_CODEX_DIR}/auth.json"
if [ ! -f "${SOURCE_AUTH_FILE}" ] || [ -L "${SOURCE_AUTH_FILE}" ]; then
    echo "Codex authentication must be a regular auth.json file for isolated certification." >&2
    exit 1
fi
cp "${SOURCE_AUTH_FILE}" "${ISOLATED_CODEX_DIR}/auth.json"
chmod 600 "${ISOLATED_CODEX_DIR}/auth.json"

AGENT_TOKEN="$(openssl rand -hex 32)"
REVIEW_TOKEN="$(openssl rand -hex 32)"
if [ "${AGENT_TOKEN}" = "${REVIEW_TOKEN}" ]; then
    echo "Generated demo identities unexpectedly collided." >&2
    exit 1
fi

cleanup() {
    if [ -n "${SERVICE_PID}" ] && kill -0 "${SERVICE_PID}" 2>/dev/null; then
        kill "${SERVICE_PID}" 2>/dev/null || true
        wait "${SERVICE_PID}" 2>/dev/null || true
    fi
    unset RG_MCP_TOKEN AGENT_TOKEN REVIEW_TOKEN
    if [ -n "${TEMP_OUTPUT}" ]; then
        rm -f "${TEMP_OUTPUT}"
    fi
    if [ "${KEEP_RAW_CODEX_TRACE:-false}" = "true" ]; then
        echo "Private trace retained at ${TRACE_FILE}" >&2
    else
        rm -rf "${PRIVATE_DIR}"
    fi
    chmod 700 "${WORKSPACE_DIR}" 2>/dev/null || true
    rm -rf "${WORKSPACE_DIR}"
    rm -rf "${ISOLATED_CODEX_DIR}" "${CODEX_RUNTIME_DIR}"
}
trap cleanup EXIT

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
if [[ "${ROOT_DIR}${PRIVATE_DIR}${COMMON_REPOSITORY}${CODEX_DATA_ROOT:-}${SOURCE_CODEX_DIR}" == *'"'* ]]; then
    echo "Repository paths containing a quote cannot be represented in the macOS sandbox profile." >&2
    exit 1
fi
cat > "${SANDBOX_PROFILE}" <<EOF
(version 1)
(allow default)
(deny process-exec)
(allow process-exec (literal "${CODEX_EXECUTABLE}"))
(deny process-fork)
(deny file-write*)
(allow file-write* (literal "${TRACE_FILE}"))
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
"${MVN_BIN}" -f "${ROOT_DIR}/resource-gateway-examples/pom.xml" clean package -DskipTests
if [ "$(git -C "${ROOT_DIR}" rev-parse HEAD)" != "${REPOSITORY_COMMIT}" ] \
        || ! repository_is_clean; then
    echo "Repository changed while the certification JAR was being built." >&2
    exit 1
fi
JAR_FILE="${ROOT_DIR}/resource-gateway-examples/target/bloge-examples-resource-gateway-1.0.0.jar"
if [ ! -f "${JAR_FILE}" ] || [ -L "${JAR_FILE}" ]; then
    echo "Clean package did not create the expected regular Resource Gateway JAR." >&2
    exit 1
fi
JAR_SHA256="sha256:$(openssl dgst -sha256 "${JAR_FILE}" | awk '{print $2}')"
INSTANCE_NONCE="$(openssl rand -hex 32)"
if lsof -nP -iTCP:"${RG_CERT_PORT}" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "Certification port became occupied while packaging; refusing to reuse its listener." >&2
    exit 1
fi

(
    cd "${ROOT_DIR}/resource-gateway-examples"
    exec env \
        RG_API_RESOURCE_AUTHORING_ENABLED=true \
        RG_REUSABLE_FLOW_AUTHORING_ENABLED=true \
        RG_AUTHORING_LOCAL_SCHEMA_BOOTSTRAP_ENABLED=true \
        RG_INTEGRATION_DEMO_TOKEN="${AGENT_TOKEN}" \
        RG_INTEGRATION_DEMO_REVIEW_TOKEN="${REVIEW_TOKEN}" \
        RG_INTEGRATION_ENVIRONMENT_ID=local \
        "${JAVA_BIN}" --enable-preview -jar "${JAR_FILE}" \
        "--server.address=127.0.0.1" "--server.port=${RG_CERT_PORT}" \
        "--gateway.agent-tdd.certification-instance.enabled=true" \
        "--gateway.agent-tdd.certification-instance.instance-nonce=${INSTANCE_NONCE}" \
        "--gateway.agent-tdd.certification-instance.repository-commit=${REPOSITORY_COMMIT}" \
        "--gateway.agent-tdd.certification-instance.jar-sha256=${JAR_SHA256}"
) > "${SERVICE_LOG}" 2>&1 &
SERVICE_PID=$!

verify_runtime_identity() {
    kill -0 "${SERVICE_PID}" 2>/dev/null \
        && curl --fail --silent --show-error --max-time 3 "${RG_INSTANCE_ENDPOINT}" \
        | python3 -c '
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
' "${INSTANCE_NONCE}" "${REPOSITORY_COMMIT}" "${JAR_SHA256}"
}

SERVICE_READY=false
for ((attempt = 0; attempt < 120; attempt++)); do
    if verify_runtime_identity; then
        SERVICE_READY=true
        break
    fi
    if ! kill -0 "${SERVICE_PID}" 2>/dev/null; then
        echo "Owned Resource Gateway exited before exposing its certification identity." >&2
        tail -40 "${SERVICE_LOG}" >&2 || true
        exit 1
    fi
    sleep 1
done
if [ "${SERVICE_READY}" != "true" ]; then
    echo "Owned Resource Gateway did not expose the exact certification identity in time." >&2
    tail -40 "${SERVICE_LOG}" >&2 || true
    exit 1
fi
export RG_MCP_TOKEN="${AGENT_TOKEN}"
curl --fail --silent --show-error "${RG_MCP_ENDPOINT%/mcp}/examples/gateway" >/dev/null

BATCH_LABEL="$(date -u +%Y%m%dT%H%M%SZ)-$$"
cat > "${PROMPT_FILE}" <<EOF
请把“按用户编号查询用户姓名和会员等级”做成客服助手可用的业务能力。这次演示批次是 ${BATCH_LABEL}，请创建一份独立草稿，不要复用旧草稿。

用户资料由公司现有的用户资料服务负责。请在已经连接的平台中自行查找可用的只读来源；如果找不到，只告诉我应该找哪类系统负责人，不要让我填写地址或数据结构。

确定资料来源后，请先单独查看并核对它当前公开的输入信息和返回信息说明，再开始设计能力；不能只根据来源名称猜测。

这项能力接收一个用户编号，返回用户姓名和会员等级。请同时为客服助手写清楚什么时候使用、需要用户提供什么、会返回什么，以及资料暂时不可用时应该如何向业务人员说明。

典型案例是：查询用户 u-100 时，资料来源返回 Alice，会员等级为 premium；能力也应返回 Alice 和 premium。请把它整理成待我确认的标准案例，并确认标准案例确实归属于刚创建的能力。

请自行完成平台需要的工作和检查，不要向我展示过程或真实用户资料。完成后只用业务语言告诉我：资料来源是否匹配、能力草稿是否有效、标准案例是否已提交，以及我接下来需要在看板完成什么。不要替我确认标准案例，也不要开始验证或发布。
EOF

CODEX_VERSION="$(CODEX_HOME="${ISOLATED_CODEX_DIR}" TMPDIR="${CODEX_RUNTIME_DIR}" \
    sandbox-exec -f "${SANDBOX_PROFILE}" "${CODEX_EXECUTABLE}" --version | head -1)"
CERTIFIED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

CODEX_ARGS=(
    exec
    --disable apps --disable in_app_browser --disable multi_agent --disable multi_agent_v2
    --disable plugins --disable remote_plugin --disable skill_search
    --disable browser_use --disable browser_use_external --disable computer_use
    --disable code_mode_host --disable hooks --disable image_generation --disable memories
    --disable shell_snapshot --disable shell_tool --disable unified_exec
    --disable view_image --disable workspace_dependencies --disable standalone_web_search
    --disable tool_suggest --disable skill_mcp_dependency_install --disable enable_mcp_apps
)
if [ -n "${CODEX_MODEL:-}" ]; then
    CODEX_ARGS+=(-m "${CODEX_MODEL}")
fi
CODEX_ARGS+=(
    --ephemeral --json --ignore-user-config --ignore-rules --skip-git-repo-check
    --sandbox read-only -C "${WORKSPACE_DIR}"
    -c "mcp_servers.rg_read.url=\"${RG_MCP_ENDPOINT}\""
    -c 'mcp_servers.rg_read.bearer_token_env_var="RG_MCP_TOKEN"'
    -c 'mcp_servers.rg_read.http_headers={"X-Purpose"="AGENT_TDD_READ"}'
    -c 'mcp_servers.rg_read.enabled_tools=["rg.capability.list","rg.contract.get","rg.scenario.listCases","rg.dsl.reference.get","rg.dsl.preview","rg.gate.check"]'
    -c 'mcp_servers.rg_read.required=true'
    -c "mcp_servers.rg_author.url=\"${RG_MCP_ENDPOINT}\""
    -c 'mcp_servers.rg_author.bearer_token_env_var="RG_MCP_TOKEN"'
    -c 'mcp_servers.rg_author.http_headers={"X-Purpose"="AGENT_TDD_AUTHORING"}'
    -c 'mcp_servers.rg_author.enabled_tools=["rg.tool.compose","rg.tool.setInstruction","rg.scenario.upsertCases","rg.scenario.setDependencyBehavior","rg.oracle.propose"]'
    -c 'mcp_servers.rg_author.required=true'
    -c "mcp_servers.rg_execute.url=\"${RG_MCP_ENDPOINT}\""
    -c 'mcp_servers.rg_execute.bearer_token_env_var="RG_MCP_TOKEN"'
    -c 'mcp_servers.rg_execute.http_headers={"X-Purpose"="AGENT_TDD_EXECUTION"}'
    -c 'mcp_servers.rg_execute.enabled_tools=["rg.simulate","rg.feature.rehearse","rg.tool.baseline"]'
    -c 'mcp_servers.rg_execute.required=true'
    -c "mcp_servers.rg_govern.url=\"${RG_MCP_ENDPOINT}\""
    -c 'mcp_servers.rg_govern.bearer_token_env_var="RG_MCP_TOKEN"'
    -c 'mcp_servers.rg_govern.http_headers={"X-Purpose"="AGENT_TDD_GOVERNANCE"}'
    -c 'mcp_servers.rg_govern.enabled_tools=["rg.fixture.promote","rg.fixture.provide","rg.tool.publish"]'
    -c 'mcp_servers.rg_govern.required=true'
    -
)

set +e
(
    cd "${WORKSPACE_DIR}"
    CODEX_HOME="${ISOLATED_CODEX_DIR}" TMPDIR="${CODEX_RUNTIME_DIR}" \
        sandbox-exec -f "${SANDBOX_PROFILE}" \
        "${CODEX_EXECUTABLE}" "${CODEX_ARGS[@]}"
) < "${PROMPT_FILE}" > "${TRACE_FILE}"
CODEX_EXIT=$?
set -e

if ! verify_runtime_identity; then
    echo "The owned Resource Gateway identity changed or disappeared during the Codex turn." >&2
    exit 1
fi
if [ "$(git -C "${ROOT_DIR}" rev-parse HEAD)" != "${REPOSITORY_COMMIT}" ] \
        || ! repository_is_clean; then
    echo "Repository commit, tracked files or untracked sources changed during certification." >&2
    exit 1
fi

TEMP_OUTPUT="${OUTPUT_FILE}.tmp.$$"
python3 "${ROOT_DIR}/scripts/agent_tdd_codex_trace_certificate.py" "${TRACE_FILE}" \
    --repository-commit "${REPOSITORY_COMMIT}" \
    --codex-version "${CODEX_VERSION}" \
    --certified-at "${CERTIFIED_AT}" \
    --runtime-instance-nonce "${INSTANCE_NONCE}" \
    --runtime-jar-sha256 "${JAR_SHA256}" \
    --exit-code "${CODEX_EXIT}" > "${TEMP_OUTPUT}"
chmod 600 "${TEMP_OUTPUT}"
mv "${TEMP_OUTPUT}" "${OUTPUT_FILE}"
TEMP_OUTPUT=""
echo "Agent TDD Codex certification passed: ${OUTPUT_FILE}"
