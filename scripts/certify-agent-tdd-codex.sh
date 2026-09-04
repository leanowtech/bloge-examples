#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CODEX_BIN="${CODEX_BIN:-codex}"
RG_MCP_ENDPOINT="${RG_MCP_ENDPOINT:-http://127.0.0.1:8081/mcp}"
OUTPUT_FILE="${1:-${ROOT_DIR}/resource-gateway-examples/target/agent-tdd-codex-certification.json}"

usage() {
    cat <<'EOF'
Usage: RG_MCP_TOKEN=<agent-token> scripts/certify-agent-tdd-codex.sh [certificate.json]

Certifies the first business journey against an already-running local Resource Gateway.
The private raw Codex trace is removed by default. Set KEEP_RAW_CODEX_TRACE=true only for
an approved local investigation; the script then prints its mode-0600 temporary path.
EOF
}

if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then
    usage
    exit 0
fi
if [ -z "${RG_MCP_TOKEN:-}" ]; then
    echo "RG_MCP_TOKEN must contain the WORKLOAD Agent token." >&2
    exit 1
fi
if [[ ! "${RG_MCP_ENDPOINT}" =~ ^http://(127\.0\.0\.1|localhost):[0-9]+/mcp$ ]]; then
    echo "Certification accepts a loopback HTTP MCP endpoint only." >&2
    exit 1
fi
for command in "${CODEX_BIN}" curl git python3; do
    if ! command -v "${command}" >/dev/null 2>&1; then
        echo "Required command is unavailable: ${command}" >&2
        exit 1
    fi
done

curl --fail --silent --show-error "${RG_MCP_ENDPOINT%/mcp}/examples/gateway" >/dev/null

PRIVATE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/rg-codex-cert.XXXXXX")"
chmod 700 "${PRIVATE_DIR}"
TRACE_FILE="${PRIVATE_DIR}/trace.jsonl"
FINAL_FILE="${PRIVATE_DIR}/final.txt"
PROMPT_FILE="${PRIVATE_DIR}/prompt.txt"
WORKSPACE_DIR="${PRIVATE_DIR}/workspace"
mkdir -p "${WORKSPACE_DIR}" "$(dirname "${OUTPUT_FILE}")"
chmod 500 "${WORKSPACE_DIR}"
umask 077
TEMP_OUTPUT=""

cleanup() {
    if [ -n "${TEMP_OUTPUT}" ]; then
        rm -f "${TEMP_OUTPUT}"
    fi
    if [ "${KEEP_RAW_CODEX_TRACE:-false}" = "true" ]; then
        echo "Private trace retained at ${TRACE_FILE}" >&2
    else
        rm -rf "${PRIVATE_DIR}"
    fi
}
trap cleanup EXIT

BATCH_LABEL="$(date -u +%Y%m%dT%H%M%SZ)-$$"
cat > "${PROMPT_FILE}" <<EOF
请把“按用户编号查询用户姓名和会员等级”做成客服助手可用的业务能力。这次演示批次是 ${BATCH_LABEL}，请创建一份独立草稿，不要复用旧草稿。

用户资料由公司现有的用户资料服务负责。请在已经连接的平台中自行查找可用的只读来源；如果找不到，只告诉我应该找哪类系统负责人，不要让我填写地址或数据结构。

这项能力接收一个用户编号，返回用户姓名和会员等级。请同时为客服助手写清楚什么时候使用、需要用户提供什么、会返回什么，以及资料暂时不可用时应该如何向业务人员说明。

典型案例是：查询用户 u-100 时，资料来源返回 Alice，会员等级为 premium；能力也应返回 Alice 和 premium。请把它整理成待我确认的标准案例，并确认标准案例确实归属于刚创建的能力。

请自行完成平台需要的工作和检查，不要向我展示过程或真实用户资料。完成后只用业务语言告诉我：资料来源是否匹配、能力草稿是否有效、标准案例是否已提交，以及我接下来需要在看板完成什么。不要替我确认标准案例，也不要开始验证或发布。
EOF

CODEX_VERSION="$(${CODEX_BIN} --version | head -1)"
REPOSITORY_COMMIT="$(git -C "${ROOT_DIR}" rev-parse HEAD)"
CERTIFIED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

CODEX_ARGS=(
    exec
)
if [ -n "${CODEX_MODEL:-}" ]; then
    CODEX_ARGS+=(-m "${CODEX_MODEL}")
fi
CODEX_ARGS+=(
    --ephemeral --json --ignore-user-config --ignore-rules --skip-git-repo-check
    --sandbox read-only -C "${WORKSPACE_DIR}" -o "${FINAL_FILE}"
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
"${CODEX_BIN}" "${CODEX_ARGS[@]}" < "${PROMPT_FILE}" > "${TRACE_FILE}"
CODEX_EXIT=$?
set -e

TEMP_OUTPUT="${OUTPUT_FILE}.tmp.$$"
python3 "${ROOT_DIR}/scripts/agent_tdd_codex_trace_certificate.py" "${TRACE_FILE}" \
    --repository-commit "${REPOSITORY_COMMIT}" \
    --codex-version "${CODEX_VERSION}" \
    --certified-at "${CERTIFIED_AT}" \
    --exit-code "${CODEX_EXIT}" > "${TEMP_OUTPUT}"
chmod 600 "${TEMP_OUTPUT}"
mv "${TEMP_OUTPUT}" "${OUTPUT_FILE}"
TEMP_OUTPUT=""
echo "Agent TDD Codex certification passed: ${OUTPUT_FILE}"
