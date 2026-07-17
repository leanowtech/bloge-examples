#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_DIR="${ROOT_DIR}/resource-gateway-examples"
PID_DIR="${ROOT_DIR}/target/example-pids"
LOG_DIR="${ROOT_DIR}/target/example-logs"
SERVICE_NAME="visual-canvas-demo"
JAR_NAME="bloge-examples-resource-gateway-1.0.0.jar"

PORT="${BLOGE_VISUAL_CANVAS_PORT:-8080}"
PORT_EXPLICIT=0
STARTUP_TIMEOUT="${BLOGE_VISUAL_CANVAS_STARTUP_TIMEOUT:-180}"
BUILD_FRONTEND="${BLOGE_VISUAL_CANVAS_BUILD_FRONTEND:-1}"
SKIP_BUILD="${BLOGE_VISUAL_CANVAS_SKIP_BUILD:-0}"
RUN_TESTS="${BLOGE_VISUAL_CANVAS_RUN_TESTS:-0}"
OPEN_BROWSER="${BLOGE_VISUAL_CANVAS_OPEN:-0}"
JAVA_BIN="${JAVA_BIN:-java}"
SPRING_PROFILE="${BLOGE_VISUAL_CANVAS_PROFILE:-test}"

if [ -z "${MVN:-}" ]; then
    if [ -x "/opt/apache-maven-3.9.16/bin/mvn" ]; then
        MVN="/opt/apache-maven-3.9.16/bin/mvn"
    else
        MVN="mvn"
    fi
fi

APP_ARGS=()

usage() {
    cat <<'EOF'
Usage:
  scripts/start-visual-canvas-demo.sh [options] [-- spring-boot-args...]
  scripts/stop-visual-canvas-demo.sh [options]
  scripts/visual-canvas-demo.sh start|stop|status|restart [options] [-- spring-boot-args...]

Options:
  --port PORT       Start or look for the demo service on PORT (default: 8080).
  --profile NAME    Spring profile for the demo (default: test).
  --no-build        Reuse the existing resource-gateway jar.
  --api-only        Build without the React frontend profile.
  --run-tests       Run Maven tests during the package step.
  --open            Open /author/ in the default browser after startup.
  -h, --help        Show this help.

Environment:
  BLOGE_VISUAL_CANVAS_PORT             default: 8080
  BLOGE_VISUAL_CANVAS_STARTUP_TIMEOUT  default: 180
  BLOGE_VISUAL_CANVAS_SKIP_BUILD       default: 0
  BLOGE_VISUAL_CANVAS_BUILD_FRONTEND   default: 1
  BLOGE_VISUAL_CANVAS_RUN_TESTS        default: 0
  BLOGE_VISUAL_CANVAS_OPEN             default: 0
  BLOGE_VISUAL_CANVAS_PROFILE          default: test
  RG_TEST_WORKER_QUARANTINE_TOKEN_ACTIVE_KEY_ID  required for staging
  RG_TEST_WORKER_QUARANTINE_TOKEN_KEY_RING       required for staging; keyId=base64AES256[,..]
  RG_TEST_WORKER_QUARANTINE_REQUEST_KEY_ACTIVE_KEY_ID  required for staging
  RG_TEST_WORKER_QUARANTINE_REQUEST_KEY_RING       required for staging; keyId=base64Key[,..]
  RG_TEST_WORKER_QUARANTINE_COMMAND_RETENTION_DAYS    default: 30
  RG_TEST_WORKER_QUARANTINE_HISTORY_RETENTION_DAYS    default: 365
  RG_TEST_WORKER_QUARANTINE_TOMBSTONE_RETENTION_DAYS  default: 365
  RG_TEST_WORKER_QUARANTINE_RETENTION_PAGE_SIZE       default: 100 per category
  RG_TEST_WORKER_QUARANTINE_RETENTION_INTERVAL_MS     default: 3600000
  JAVA_BIN                             default: java
  MVN                                  default: /opt/apache-maven-3.9.16/bin/mvn when present, otherwise mvn

Examples:
  scripts/start-visual-canvas-demo.sh
  scripts/start-visual-canvas-demo.sh --open
  scripts/start-visual-canvas-demo.sh --port 18080 -- --gateway.base-url=http://localhost:9091
  scripts/visual-canvas-demo.sh status
EOF
}

truthy() {
    case "${1:-}" in
        1|true|TRUE|yes|YES|on|ON) return 0 ;;
        *) return 1 ;;
    esac
}

validate_profile_secrets() {
    if [ "${SPRING_PROFILE}" != "staging" ]; then
        return 0
    fi
    if [ -z "${RG_TEST_WORKER_QUARANTINE_TOKEN_ACTIVE_KEY_ID:-}" ] ||
        [ -z "${RG_TEST_WORKER_QUARANTINE_TOKEN_KEY_RING:-}" ] ||
        [ -z "${RG_TEST_WORKER_QUARANTINE_REQUEST_KEY_ACTIVE_KEY_ID:-}" ] ||
        [ -z "${RG_TEST_WORKER_QUARANTINE_REQUEST_KEY_RING:-}" ]; then
        echo "Staging requires both worker-quarantine token and request-key protection key rings." >&2
        echo "Inject all four values from the deployment secret manager before startup." >&2
        return 1
    fi
}

pid_file() {
    echo "${PID_DIR}/${SERVICE_NAME}.pid"
}

port_file() {
    echo "${PID_DIR}/${SERVICE_NAME}.port"
}

log_file() {
    echo "${LOG_DIR}/${SERVICE_NAME}.log"
}

configured_port() {
    if [ "${PORT_EXPLICIT}" -eq 1 ]; then
        echo "${PORT}"
        return 0
    fi
    if [ -f "$(port_file)" ]; then
        tr -d '[:space:]' < "$(port_file)"
        return 0
    fi
    echo "${PORT}"
}

author_url() {
    echo "http://localhost:$(configured_port)/author/"
}

showcase_url() {
    echo "http://localhost:$(configured_port)/showcase/"
}

legacy_url() {
    echo "http://localhost:$(configured_port)/examples/gateway"
}

capabilities_url() {
    echo "http://localhost:$(configured_port)/api/integration/capabilities"
}

jar_path() {
    echo "${PROJECT_DIR}/target/${JAR_NAME}"
}

read_pid() {
    if [ ! -f "$(pid_file)" ]; then
        return 1
    fi
    tr -d '[:space:]' < "$(pid_file)"
}

process_command() {
    ps -p "$1" -o command= 2>/dev/null || true
}

process_matches_service() {
    local pid="$1"
    local command
    command="$(process_command "${pid}")"
    [[ "${command}" == *"${JAR_NAME}"* ]] ||
        [[ "${command}" == *"ResourceGatewayApplication"* ]] ||
        [[ "${command}" == *"resource-gateway-examples/pom.xml"* ]]
}

listener_pid() {
    local port
    port="$(configured_port)"
    if ! command -v lsof >/dev/null 2>&1; then
        return 1
    fi
    lsof -tiTCP:"${port}" -sTCP:LISTEN 2>/dev/null | head -1
}

running_pid() {
    local pid
    pid="$(read_pid 2>/dev/null || true)"
    if [ -n "${pid}" ] && kill -0 "${pid}" 2>/dev/null && process_matches_service "${pid}"; then
        echo "${pid}"
        return 0
    fi

    pid="$(listener_pid 2>/dev/null || true)"
    if [ -n "${pid}" ] && kill -0 "${pid}" 2>/dev/null && process_matches_service "${pid}"; then
        echo "${pid}"
        return 0
    fi

    return 1
}

remove_stale_files() {
    if ! running_pid >/dev/null 2>&1; then
        rm -f "$(pid_file)" "$(port_file)"
    fi
}

assert_port_available() {
    local pid
    pid="$(listener_pid 2>/dev/null || true)"
    if [ -z "${pid}" ]; then
        return 0
    fi
    if process_matches_service "${pid}"; then
        return 0
    fi

    echo "Port $(configured_port) is already used by pid ${pid}." >&2
    echo "Command: $(process_command "${pid}")" >&2
    return 1
}

print_urls() {
    cat <<EOF
Demo URLs:
  Author canvas:   $(author_url)
  Showcase:        $(showcase_url)
  Legacy composer: $(legacy_url)
  Capability probe: $(capabilities_url)
  Active profile:   ${SPRING_PROFILE}

Integration API templates:
  Draft workbook:    GET  /api/integration/drafts/{draftId}/correctness-workbook?revision={revision}
  Semantic workbook: GET  /api/integration/test-suites/{suiteId}/revisions/{revision}/semantic-correctness-workbook
  Gate feedback:     POST /api/integration/gate-results
  Test execution:    POST /api/testing/executions  (Bearer token + X-Purpose: TEST_EXECUTION)
  Fixture registry: PUT /api/testing/fixture-bundles/{id} (X-Purpose: TEST_FIXTURE_WRITE)
EOF
}

build_app() {
    if truthy "${SKIP_BUILD}"; then
        if [ ! -f "$(jar_path)" ]; then
            echo "Cannot skip build: $(jar_path) does not exist." >&2
            return 1
        fi
        echo "Skipping build; reusing $(jar_path)."
        return 0
    fi

    local command=("${MVN}" -f "${PROJECT_DIR}/pom.xml")
    if truthy "${BUILD_FRONTEND}"; then
        command+=("-Pfrontend")
    fi
    command+=(package)
    if ! truthy "${RUN_TESTS}"; then
        command+=("-DskipTests")
    fi

    echo "Packaging Resource Gateway demo..."
    printf 'Command:'
    printf ' %q' "${command[@]}"
    printf '\n'
    (
        cd "${ROOT_DIR}"
        "${command[@]}"
    )
}

wait_for_ready() {
    if ! command -v curl >/dev/null 2>&1; then
        echo "Started. curl is unavailable, so readiness was not checked."
        return 0
    fi

    local deadline
    local url
    deadline=$((SECONDS + STARTUP_TIMEOUT))
    url="$(capabilities_url)"
    while [ "${SECONDS}" -lt "${deadline}" ]; do
        if ! running_pid >/dev/null 2>&1; then
            echo "Demo service exited before becoming ready. See $(log_file)." >&2
            return 1
        fi
        if curl -fsS "${url}" >/dev/null 2>&1; then
            echo "Demo service ready; integration capability probe passed: ${url}"
            return 0
        fi
        sleep 2
    done

    echo "Demo service did not become ready within ${STARTUP_TIMEOUT}s." >&2
    echo "Log: $(log_file)" >&2
    tail -60 "$(log_file)" >&2 || true
    return 1
}

open_author_if_requested() {
    if ! truthy "${OPEN_BROWSER}"; then
        return 0
    fi
    if command -v open >/dev/null 2>&1; then
        open "$(author_url)" >/dev/null 2>&1 || true
    else
        echo "Browser open requested, but the 'open' command is unavailable."
    fi
}

start_service() {
    validate_profile_secrets
    mkdir -p "${PID_DIR}" "${LOG_DIR}"

    local pid
    pid="$(running_pid 2>/dev/null || true)"
    if [ -n "${pid}" ]; then
        echo "Visual canvas demo already running (pid ${pid})."
        echo "${pid}" > "$(pid_file)"
        print_urls
        return 0
    fi

    remove_stale_files
    echo "${PORT}" > "$(port_file)"

    assert_port_available
    build_app

    local log
    local -a args
    log="$(log_file)"
    args=("--server.port=$(configured_port)" "--spring.profiles.active=${SPRING_PROFILE}")
    if [ "${#APP_ARGS[@]}" -gt 0 ]; then
        args+=("${APP_ARGS[@]}")
    fi

    echo "Starting Visual Canvas demo..."
    (
        cd "${PROJECT_DIR}"
        nohup "${JAVA_BIN}" --enable-preview -jar "$(jar_path)" "${args[@]}" > "${log}" 2>&1 &
        echo $! > "$(pid_file)"
    )

    pid="$(read_pid)"
    echo "Demo pid: ${pid}"
    echo "Demo log: $(log_file)"
    wait_for_ready
    print_urls
    open_author_if_requested
}

stop_service() {
    local pid
    pid="$(running_pid 2>/dev/null || true)"

    if [ -z "${pid}" ]; then
        rm -f "$(pid_file)" "$(port_file)"
        echo "Visual canvas demo is not running."
        return 0
    fi

    if ! process_matches_service "${pid}"; then
        echo "Refusing to stop pid ${pid}; it does not look like the visual canvas demo." >&2
        return 1
    fi

    echo "Stopping Visual Canvas demo (pid ${pid})..."
    kill "${pid}" 2>/dev/null || true

    local i
    for i in $(seq 1 20); do
        if ! kill -0 "${pid}" 2>/dev/null; then
            rm -f "$(pid_file)" "$(port_file)"
            echo "Visual canvas demo stopped."
            return 0
        fi
        sleep 1
    done

    echo "Visual canvas demo did not stop gracefully; forcing stop."
    kill -9 "${pid}" 2>/dev/null || true
    rm -f "$(pid_file)" "$(port_file)"
}

status_service() {
    local pid
    pid="$(running_pid 2>/dev/null || true)"
    if [ -n "${pid}" ]; then
        echo "Visual canvas demo: running (pid ${pid})"
        echo "Log: $(log_file)"
        print_urls
        if command -v curl >/dev/null 2>&1; then
            if curl -fsS "$(capabilities_url)" >/dev/null 2>&1; then
                echo "Capability probe: healthy"
            else
                echo "Capability probe: unavailable"
            fi
        fi
    else
        echo "Visual canvas demo: stopped"
    fi
}

parse_options() {
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --port)
                if [ "$#" -lt 2 ]; then
                    echo "--port requires a value." >&2
                    return 1
                fi
                PORT="$2"
                PORT_EXPLICIT=1
                shift 2
                ;;
            --profile)
                if [ "$#" -lt 2 ] || [ -z "$2" ]; then
                    echo "--profile requires a value." >&2
                    return 1
                fi
                SPRING_PROFILE="$2"
                shift 2
                ;;
            --no-build)
                SKIP_BUILD=1
                shift
                ;;
            --api-only)
                BUILD_FRONTEND=0
                shift
                ;;
            --run-tests)
                RUN_TESTS=1
                shift
                ;;
            --open)
                OPEN_BROWSER=1
                shift
                ;;
            -h|--help)
                usage
                exit 0
                ;;
            --)
                shift
                APP_ARGS+=("$@")
                break
                ;;
            *)
                APP_ARGS+=("$1")
                shift
                ;;
        esac
    done
}

main() {
    local action="${1:-}"
    if [ -z "${action}" ] || [ "${action}" = "-h" ] || [ "${action}" = "--help" ]; then
        usage
        exit 0
    fi
    shift

    parse_options "$@"

    case "${action}" in
        start)
            start_service
            ;;
        stop)
            stop_service
            ;;
        status)
            status_service
            ;;
        restart)
            stop_service
            start_service
            ;;
        *)
            echo "Unknown action: ${action}" >&2
            usage >&2
            exit 1
            ;;
    esac
}

main "$@"
