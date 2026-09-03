#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_DIR="${ROOT_DIR}/target/example-pids"
LOG_DIR="${ROOT_DIR}/target/example-logs"

GRAPH_ENGINE_PORT="${GRAPH_ENGINE_PORT:-8080}"
RESOURCE_GATEWAY_PORT="${RESOURCE_GATEWAY_PORT:-8081}"
RESOURCE_GATEWAY_ADDRESS="${RESOURCE_GATEWAY_ADDRESS:-127.0.0.1}"
STARTUP_TIMEOUT="${STARTUP_TIMEOUT:-120}"
JAVA_BIN="${JAVA_BIN:-java}"
RG_API_RESOURCE_AUTHORING_ENABLED="${RG_API_RESOURCE_AUTHORING_ENABLED:-true}"
RG_REUSABLE_FLOW_AUTHORING_ENABLED="${RG_REUSABLE_FLOW_AUTHORING_ENABLED:-true}"
RG_AUTHORING_LOCAL_SCHEMA_BOOTSTRAP_ENABLED="${RG_AUTHORING_LOCAL_SCHEMA_BOOTSTRAP_ENABLED:-true}"
RG_INTEGRATION_ENVIRONMENT_ID="${RG_INTEGRATION_ENVIRONMENT_ID:-local}"

if [ -z "${MVN:-}" ]; then
    if [ -x "/opt/apache-maven-3.9.16/bin/mvn" ]; then
        MVN="/opt/apache-maven-3.9.16/bin/mvn"
    else
        MVN="mvn"
    fi
fi

usage() {
    cat <<'EOF'
Usage:
  scripts/start-examples.sh [all|graph-engine|resource-gateway]
  scripts/stop-examples.sh [all|graph-engine|resource-gateway]
  scripts/example-services.sh start|stop|status [all|graph-engine|resource-gateway]

Environment:
  GRAPH_ENGINE_PORT       default: 8080
  RESOURCE_GATEWAY_PORT   default: 8081
  RESOURCE_GATEWAY_ADDRESS default: 127.0.0.1 (loopback-only demo safety)
  STARTUP_TIMEOUT         default: 120
  JAVA_BIN                default: java
  MVN                     default: /opt/apache-maven-3.9.16/bin/mvn when present, otherwise mvn
  RG_API_RESOURCE_AUTHORING_ENABLED default: true
  RG_REUSABLE_FLOW_AUTHORING_ENABLED default: true
  RG_AUTHORING_LOCAL_SCHEMA_BOOTSTRAP_ENABLED default: true for the embedded H2 database
  RG_INTEGRATION_ENVIRONMENT_ID default: local for zero-egress Agent TDD execution
  Set either variable to false to disable that authoring surface.
EOF
}

service_title() {
    case "$1" in
        graph-engine) echo "Graph Engine" ;;
        resource-gateway) echo "Resource Gateway" ;;
        *) echo "$1" ;;
    esac
}

configured_port() {
    case "$1" in
        graph-engine) echo "${GRAPH_ENGINE_PORT}" ;;
        resource-gateway) echo "${RESOURCE_GATEWAY_PORT}" ;;
        *) return 1 ;;
    esac
}

pid_file() {
    echo "${PID_DIR}/$1.pid"
}

port_file() {
    echo "${PID_DIR}/$1.port"
}

log_file() {
    echo "${LOG_DIR}/$1.log"
}

stored_or_configured_port() {
    local file
    file="$(port_file "$1")"
    if [ -f "${file}" ]; then
        tr -d '[:space:]' < "${file}"
    else
        configured_port "$1"
    fi
}

service_url() {
    local port
    port="$(stored_or_configured_port "$1")"
    case "$1" in
        graph-engine) echo "http://localhost:${port}/console" ;;
        resource-gateway) echo "http://localhost:${port}/examples/gateway" ;;
        *) return 1 ;;
    esac
}

service_workdir() {
    case "$1" in
        graph-engine) echo "${ROOT_DIR}/graph-engine-examples/server" ;;
        resource-gateway) echo "${ROOT_DIR}/resource-gateway-examples" ;;
        *) return 1 ;;
    esac
}

service_jar() {
    case "$1" in
        graph-engine) echo "target/bloge-graph-engine-server-1.0.0.jar" ;;
        resource-gateway) echo "target/bloge-examples-resource-gateway-1.0.0.jar" ;;
        *) return 1 ;;
    esac
}

normalize_target() {
    case "${1:-all}" in
        all) printf '%s\n' graph-engine resource-gateway ;;
        graph|graph-engine|ge) echo "graph-engine" ;;
        gateway|resource-gateway|rg) echo "resource-gateway" ;;
        *)
            echo "Unknown service: $1" >&2
            usage >&2
            return 1
            ;;
    esac
}

expand_targets() {
    if [ "$#" -eq 0 ]; then
        normalize_target all
        return
    fi

    local target
    for target in "$@"; do
        normalize_target "$target"
    done
}

read_pid() {
    local file
    file="$(pid_file "$1")"
    if [ ! -f "${file}" ]; then
        return 1
    fi
    tr -d '[:space:]' < "${file}"
}

process_command() {
    ps -p "$1" -o command= 2>/dev/null || true
}

process_matches_service() {
    local service="$1"
    local pid="$2"
    local command
    command="$(process_command "${pid}")"
    case "${service}" in
        graph-engine)
            [[ "${command}" == *"bloge-graph-engine-server-1.0.0.jar"* ]] ||
                [[ "${command}" == *"graph-engine-examples/pom.xml"* ]] ||
                [[ "${command}" == *"GraphEngineServerApplication"* ]]
            ;;
        resource-gateway)
            [[ "${command}" == *"bloge-examples-resource-gateway-1.0.0.jar"* ]] ||
                [[ "${command}" == *"resource-gateway-examples/pom.xml"* ]] ||
                [[ "${command}" == *"ResourceGatewayApplication"* ]]
            ;;
        *)
            return 1
            ;;
    esac
}

listener_pid() {
    local service="$1"
    local port
    port="$(stored_or_configured_port "${service}")"
    if ! command -v lsof >/dev/null 2>&1; then
        return 1
    fi
    lsof -tiTCP:"${port}" -sTCP:LISTEN 2>/dev/null | head -1
}

running_pid() {
    local service="$1"
    local pid
    pid="$(read_pid "${service}" 2>/dev/null || true)"
    if [ -n "${pid}" ] && kill -0 "${pid}" 2>/dev/null && process_matches_service "${service}" "${pid}"; then
        echo "${pid}"
        return 0
    fi

    pid="$(listener_pid "${service}" 2>/dev/null || true)"
    if [ -n "${pid}" ] && kill -0 "${pid}" 2>/dev/null && process_matches_service "${service}" "${pid}"; then
        echo "${pid}"
        return 0
    fi

    return 1
}

is_running() {
    running_pid "$1" >/dev/null 2>&1
}

remove_stale_files() {
    local service="$1"
    if ! is_running "${service}"; then
        rm -f "$(pid_file "${service}")"
    fi
}

assert_port_available() {
    local service="$1"
    local pid
    pid="$(listener_pid "${service}" 2>/dev/null || true)"
    if [ -z "${pid}" ]; then
        return 0
    fi
    if process_matches_service "${service}" "${pid}"; then
        return 0
    fi

    echo "$(service_title "${service}") port $(stored_or_configured_port "${service}") is already used by pid ${pid}." >&2
    echo "Command: $(process_command "${pid}")" >&2
    return 1
}

build_service() {
    local service="$1"
    local title
    title="$(service_title "${service}")"
    case "${service}" in
        graph-engine)
            echo "Packaging ${title}..."
            (
                cd "${ROOT_DIR}"
                "${MVN}" -f graph-engine-examples/pom.xml -pl server -am -DskipTests package
            )
            ;;
        resource-gateway)
            echo "Packaging ${title}..."
            (
                cd "${ROOT_DIR}"
                "${MVN}" -f resource-gateway-examples/pom.xml -DskipTests package
            )
            ;;
        *)
            echo "Unknown service: ${service}" >&2
            return 1
            ;;
    esac
}

wait_for_url() {
    local service="$1"
    local url="$2"
    local title
    title="$(service_title "${service}")"

    if ! command -v curl >/dev/null 2>&1; then
        echo "${title} started. curl is unavailable, so readiness was not checked."
        return 0
    fi

    local deadline
    deadline=$((SECONDS + STARTUP_TIMEOUT))
    while [ "${SECONDS}" -lt "${deadline}" ]; do
        if ! is_running "${service}"; then
            echo "${title} exited before becoming ready. See $(log_file "${service}")." >&2
            return 1
        fi
        if curl -fsS "${url}" >/dev/null 2>&1; then
            echo "${title} ready: ${url}"
            return 0
        fi
        sleep 2
    done

    echo "${title} did not become ready within ${STARTUP_TIMEOUT}s." >&2
    echo "Log: $(log_file "${service}")" >&2
    tail -40 "$(log_file "${service}")" >&2 || true
    return 1
}

start_service() {
    local service="$1"
    local title
    local log
    local pid
    local port
    local url
    local workdir
    local jar
    title="$(service_title "${service}")"
    log="$(log_file "${service}")"
    port="$(configured_port "${service}")"
    url="$(service_url "${service}")"

    mkdir -p "${PID_DIR}" "${LOG_DIR}"
    echo "${port}" > "$(port_file "${service}")"
    remove_stale_files "${service}"

    pid="$(running_pid "${service}" 2>/dev/null || true)"
    if [ -n "${pid}" ]; then
        echo "${title} already running (pid ${pid}): ${url}"
        echo "${pid}" > "$(pid_file "${service}")"
        return 0
    fi

    assert_port_available "${service}"
    build_service "${service}"

    echo "Starting ${title}..."
    case "${service}" in
        graph-engine)
            workdir="$(service_workdir "${service}")"
            jar="$(service_jar "${service}")"
            (
                cd "${workdir}"
                nohup "${JAVA_BIN}" -jar "${jar}" "--server.port=${port}" > "${log}" 2>&1 &
                echo $! > "$(pid_file "${service}")"
            )
            ;;
        resource-gateway)
            workdir="$(service_workdir "${service}")"
            jar="$(service_jar "${service}")"
            (
                cd "${workdir}"
                export RG_API_RESOURCE_AUTHORING_ENABLED
                export RG_REUSABLE_FLOW_AUTHORING_ENABLED
                export RG_AUTHORING_LOCAL_SCHEMA_BOOTSTRAP_ENABLED
                export RG_INTEGRATION_ENVIRONMENT_ID
                nohup "${JAVA_BIN}" --enable-preview -jar "${jar}" \
                    "--server.address=${RESOURCE_GATEWAY_ADDRESS}" "--server.port=${port}" > "${log}" 2>&1 &
                echo $! > "$(pid_file "${service}")"
            )
            ;;
        *)
            echo "Unknown service: ${service}" >&2
            return 1
            ;;
    esac

    pid="$(read_pid "${service}")"
    echo "${title} pid: ${pid}"
    echo "${title} log: ${log}"
    wait_for_url "${service}" "${url}"
}

stop_service() {
    local service="$1"
    local title
    local pid
    local i
    title="$(service_title "${service}")"
    pid="$(running_pid "${service}" 2>/dev/null || true)"

    if [ -z "${pid}" ]; then
        rm -f "$(pid_file "${service}")" "$(port_file "${service}")"
        echo "${title} is not running."
        return 0
    fi

    if ! process_matches_service "${service}" "${pid}"; then
        echo "Refusing to stop ${title}: pid ${pid} does not look like this example service." >&2
        return 1
    fi

    echo "Stopping ${title} (pid ${pid})..."
    kill "${pid}" 2>/dev/null || true
    for i in $(seq 1 20); do
        if ! kill -0 "${pid}" 2>/dev/null; then
            rm -f "$(pid_file "${service}")" "$(port_file "${service}")"
            echo "${title} stopped."
            return 0
        fi
        sleep 1
    done

    echo "${title} did not stop gracefully; forcing stop."
    kill -9 "${pid}" 2>/dev/null || true
    rm -f "$(pid_file "${service}")" "$(port_file "${service}")"
}

status_service() {
    local service="$1"
    local title
    local pid
    title="$(service_title "${service}")"
    pid="$(running_pid "${service}" 2>/dev/null || true)"
    if [ -n "${pid}" ]; then
        echo "${title}: running (pid ${pid})"
        echo "  URL: $(service_url "${service}")"
        echo "  Log: $(log_file "${service}")"
    else
        echo "${title}: stopped"
    fi
}

main() {
    local action="${1:-}"
    if [ -z "${action}" ] || [ "${action}" = "-h" ] || [ "${action}" = "--help" ]; then
        usage
        exit 0
    fi
    shift

    local services
    services="$(expand_targets "$@")"

    local service
    case "${action}" in
        start)
            while IFS= read -r service; do
                start_service "${service}"
            done <<< "${services}"
            ;;
        stop)
            while IFS= read -r service; do
                stop_service "${service}"
            done <<< "${services}"
            ;;
        status)
            while IFS= read -r service; do
                status_service "${service}"
            done <<< "${services}"
            ;;
        *)
            echo "Unknown action: ${action}" >&2
            usage >&2
            exit 1
            ;;
    esac
}

main "$@"
