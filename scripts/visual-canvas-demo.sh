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
  RG_TEST_WORKER_QUARANTINE_REQUEST_INDEX_WRITE_MODE  required for staging; staged rollout mode
  RG_RESOURCE_GATEWAY_INSTANCE_ID                  required for staging; exact serving replica id
  RG_RESOURCE_GATEWAY_ARTIFACT_FINGERPRINT         required for staging; sha256 image/JAR identity
  RG_TEST_SECRET_AUTHORITY_COHORT_ENABLED  optional; exact dynamic-JWKS test-secret replica gate
  RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_ENABLED  required true for staging cohort
  RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_TRUST_DOMAIN  required external trust domain
  RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_SET_ID  required stable notary-set id
  RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_SIGNATURE_THRESHOLD  required 2f+1 quorum
  RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_MAXIMUM_FAULTS  required; 1..10
  RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_MINIMUM_FAULTS  default: 1
  RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_AUTHORITY_KEYS_JSON  required public Ed25519 keys
  RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_ENDPOINTS_JSON  required HTTPS notary endpoints
  RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_TIMEOUT_MS  default: 3000; 100..30000
  RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_CLOCK_SKEW_SECONDS  default: 5; 0..30
  RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_MAXIMUM_RECEIPT_LIFETIME_SECONDS  default: 15; 1..60
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_ENABLED   optional; exact dynamic-JWKS replica gate
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_SCOPE_ID  required when cohort is enabled; stable fleet scope
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_ID        required when cohort is enabled; deployment generation
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_EXPECTED_INSTANCE_IDS  optional signed-inventory equality assertion
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_SIGNED_INVENTORY_ENABLED  required true for staging cohort
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_POLICY_FINGERPRINTS  required accepted sha256 policies
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_REMOTE_ENABLED  required true for staging cohort
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_REMOTE_URI  required HTTPS publication endpoint
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_REMOTE_REFRESH_SECONDS  default: 30; 1..3600
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_REMOTE_TIMEOUT_MS  default: 3000; 100..30000
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_REMOTE_MAXIMUM_AGE_SECONDS  default: 60; 2..86400
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_ENABLED  required true for staging cohort
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_TRUST_DOMAIN  required external trust domain
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_SET_ID  required stable notary-set id
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_SIGNATURE_THRESHOLD  required 2f+1 quorum
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_MAXIMUM_FAULTS  required; 1..10
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_MINIMUM_FAULTS  default: 1
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_AUTHORITY_KEYS_JSON  required public Ed25519 keys
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_ENDPOINTS_JSON  required HTTPS notary endpoints
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_TIMEOUT_MS  default: 3000; 100..30000
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_CLOCK_SKEW_SECONDS  default: 5; 0..30
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_MAXIMUM_RECEIPT_LIFETIME_SECONDS  default: 15; 1..60
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_TRUST_ROOTS_ENABLED  required true for staging cohort
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_TRUST_ROOTS_URI  required HTTPS dual-root endpoint
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_TRUST_ROOT_SET_ID  required stable root-set identity
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_TRUST_ROOT_POLICY_FINGERPRINTS  required accepted policies
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_DEPLOYMENT_ROOT_DOMAIN  required bootstrap domain
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_DEPLOYMENT_ROOT_SIGNATURE_THRESHOLD  required; 1..32
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_DEPLOYMENT_ROOT_AUTHORITY_KEYS_JSON  required public roots
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_WITNESS_ROOT_DOMAIN  required independent bootstrap domain
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_WITNESS_ROOT_SIGNATURE_THRESHOLD  required; 1..32
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_WITNESS_ROOT_AUTHORITY_KEYS_JSON  required public roots
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_TRUST_ROOTS_REFRESH_SECONDS  default: 30; 1..3600
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_TRUST_ROOTS_TIMEOUT_MS  default: 3000; 100..30000
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_TRUST_ROOTS_UNKNOWN_KEY_REFRESH_SECONDS  default: 5; 1..3600
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_TRUST_ROOTS_MAXIMUM_AGE_SECONDS  default: 60; 2..86400
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_HEARTBEAT_SECONDS  default: 10; 1..300
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_LEASE_SECONDS      default: 30; 3..900 and >= 3x heartbeat
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_RETENTION_SECONDS  default: 86400; 3600..2592000
  RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_TRUST_DOMAIN  required for staging
  RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_POLICY_FINGERPRINTS  required; comma-separated sha256 values
  RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_SIGNATURE_THRESHOLD  required; 1..32 authorities
  RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_AUTHORITY_KEYS_JSON  required; public Ed25519 keys only
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

validate_test_secret_external_anchor() {
    if ! truthy "${RG_TEST_SECRET_AUTHORITY_COHORT_ENABLED:-false}"; then
        return 0
    fi
    if ! truthy "${RG_TEST_SECRET_AUTHORITY_HTTP_ENABLED:-false}" ||
        ! truthy "${RG_TEST_SECRET_AUTHORITY_JWKS_ENABLED:-false}" ||
        ! truthy "${RG_TEST_SECRET_AUTHORITY_COHORT_SIGNED_INVENTORY_ENABLED:-false}" ||
        ! truthy "${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_REMOTE_ENABLED:-false}" ||
        ! truthy "${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_TRUST_ROOTS_ENABLED:-false}" ||
        ! truthy "${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_ENABLED:-false}";
        then
        echo "Staging test-secret cohort requires HTTP, dynamic JWKS, remote signed inventory, managed roots, and external anchoring." >&2
        return 1
    fi
    if [ -z "${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_TRUST_DOMAIN:-}" ] ||
        [ -z "${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_SET_ID:-}" ] ||
        [ -z "${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_SIGNATURE_THRESHOLD:-}" ] ||
        [ -z "${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_MAXIMUM_FAULTS:-}" ] ||
        [ -z "${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_AUTHORITY_KEYS_JSON:-}" ] ||
        [ -z "${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_ENDPOINTS_JSON:-}" ]; then
        echo "Test-secret external anchoring requires trust, quorum, public keys, and endpoints." >&2
        return 1
    fi
    if truthy "${RG_TEST_SECRET_AUTHORITY_ALLOW_INSECURE_LOOPBACK:-false}" ||
        truthy "${RG_TEST_SECRET_AUTHORITY_JWKS_ALLOW_INSECURE_LOOPBACK:-false}" ||
        truthy "${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_REMOTE_ALLOW_INSECURE_LOOPBACK:-false}" ||
        truthy "${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_TRUST_ROOTS_ALLOW_INSECURE_LOOPBACK:-false}" ||
        truthy "${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_ALLOW_INSECURE_LOOPBACK:-false}";
        then
        echo "Staging test-secret authority, inventory, roots, and notaries must use HTTPS." >&2
        return 1
    fi
    if printf '%s\n%s\n' \
        "${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_TRUST_DOMAIN}" \
        "${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_SET_ID}" |
        grep -Eqv '^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}$'; then
        echo "Invalid test-secret external anchor trust domain or set id." >&2
        return 1
    fi
    case "${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_AUTHORITY_KEYS_JSON}" in
        \[*\]) ;;
        *)
            echo "Test-secret external anchor keys must be a JSON array." >&2
            return 1
            ;;
    esac
    case "${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_ENDPOINTS_JSON}" in
        \[*\]) ;;
        *)
            echo "Test-secret external anchor endpoints must be a JSON array." >&2
            return 1
            ;;
    esac
    if printf '%s' \
        "${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_AUTHORITY_KEYS_JSON}" |
        grep -Eq '^\[[[:space:]]*\]$' ||
        printf '%s' \
        "${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_ENDPOINTS_JSON}" |
        grep -Eq '^\[[[:space:]]*\]$'; then
        echo "Test-secret external anchor keys and endpoints must be non-empty." >&2
        return 1
    fi

    local threshold="${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_SIGNATURE_THRESHOLD}"
    local maximum_faults="${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_MAXIMUM_FAULTS}"
    local minimum_faults="${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_MINIMUM_FAULTS:-1}"
    local timeout_ms="${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_TIMEOUT_MS:-3000}"
    local clock_skew_seconds="${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_CLOCK_SKEW_SECONDS:-5}"
    local lifetime_seconds="${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_MAXIMUM_RECEIPT_LIFETIME_SECONDS:-15}"
    if printf '%s\n%s\n%s\n%s\n%s\n' "${threshold}" "${maximum_faults}" \
        "${minimum_faults}" "${timeout_ms}" "${lifetime_seconds}" |
        grep -Eqv '^[1-9][0-9]*$' ||
        ! printf '%s' "${clock_skew_seconds}" | grep -Eq '^(0|[1-9][0-9]*)$'; then
        echo "Test-secret external anchor policy values must be canonical integers." >&2
        return 1
    fi
    if [ "${threshold}" -gt 32 ] ||
        [ "${maximum_faults}" -lt 1 ] || [ "${maximum_faults}" -gt 10 ] ||
        [ "${minimum_faults}" -lt 1 ] || [ "${minimum_faults}" -gt 10 ] ||
        [ "${maximum_faults}" -lt "${minimum_faults}" ] ||
        [ "${threshold}" -lt $((maximum_faults * 2 + 1)) ]; then
        echo "Test-secret external anchor quorum must satisfy f>=1 and threshold>=2f+1." >&2
        return 1
    fi
    if [ "${timeout_ms}" -lt 100 ] || [ "${timeout_ms}" -gt 30000 ] ||
        [ "${clock_skew_seconds}" -gt 30 ] ||
        [ "${lifetime_seconds}" -gt 60 ] ||
        [ "${timeout_ms}" -ge $((lifetime_seconds * 1000)) ]; then
        echo "Test-secret external anchor timing bounds are invalid." >&2
        return 1
    fi
}

validate_profile_secrets() {
    if [ "${SPRING_PROFILE}" != "staging" ]; then
        return 0
    fi
    if [ -z "${RG_TEST_WORKER_QUARANTINE_TOKEN_ACTIVE_KEY_ID:-}" ] ||
        [ -z "${RG_TEST_WORKER_QUARANTINE_TOKEN_KEY_RING:-}" ] ||
        [ -z "${RG_TEST_WORKER_QUARANTINE_REQUEST_KEY_ACTIVE_KEY_ID:-}" ] ||
        [ -z "${RG_TEST_WORKER_QUARANTINE_REQUEST_KEY_RING:-}" ] ||
        [ -z "${RG_TEST_WORKER_QUARANTINE_REQUEST_INDEX_WRITE_MODE:-}" ] ||
        [ -z "${RG_RESOURCE_GATEWAY_INSTANCE_ID:-}" ] ||
        [ -z "${RG_RESOURCE_GATEWAY_ARTIFACT_FINGERPRINT:-}" ] ||
        [ -z "${RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_TRUST_DOMAIN:-}" ] ||
        [ -z "${RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_POLICY_FINGERPRINTS:-}" ] ||
        [ -z "${RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_SIGNATURE_THRESHOLD:-}" ] ||
        [ -z "${RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_AUTHORITY_KEYS_JSON:-}" ]; then
        echo "Staging requires quarantine key rings, rollout identity, and external change-authorization trust." >&2
        echo "Inject all eleven deployment-owned values before startup." >&2
        return 1
    fi
    validate_test_secret_external_anchor
    case "${RG_TEST_WORKER_QUARANTINE_REQUEST_INDEX_WRITE_MODE}" in
        LEGACY_READ_WRITE|DUAL_READ_KEYED_WRITE|KEYED_ONLY) ;;
        *)
            echo "Invalid worker-quarantine request-index write mode." >&2
            echo "Use LEGACY_READ_WRITE, DUAL_READ_KEYED_WRITE, or KEYED_ONLY." >&2
            return 1
            ;;
    esac
    case "${RG_RESOURCE_GATEWAY_INSTANCE_ID}" in
        [A-Za-z0-9]*) ;;
        *)
            echo "Invalid Resource Gateway instance id." >&2
            return 1
            ;;
    esac
    if ! printf '%s' "${RG_RESOURCE_GATEWAY_INSTANCE_ID}" |
        grep -Eq '^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}$'; then
        echo "Invalid Resource Gateway instance id." >&2
        return 1
    fi
    if ! printf '%s' "${RG_RESOURCE_GATEWAY_ARTIFACT_FINGERPRINT}" |
        grep -Eq '^sha256:[a-f0-9]{64}$'; then
        echo "Invalid Resource Gateway artifact fingerprint; use canonical sha256:<lowercase-hex>." >&2
        return 1
    fi
    if ! printf '%s' "${RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_TRUST_DOMAIN}" |
        grep -Eq '^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}$'; then
        echo "Invalid external change-authorization trust domain." >&2
        return 1
    fi
    if ! printf '%s' "${RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_POLICY_FINGERPRINTS}" |
        grep -Eq '^sha256:[a-f0-9]{64}(,sha256:[a-f0-9]{64}){0,31}$'; then
        echo "Invalid external change-authorization policy fingerprints." >&2
        return 1
    fi
    case "${RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_SIGNATURE_THRESHOLD}" in
        ''|*[!0-9]*)
            echo "Invalid external change-authorization signature threshold." >&2
            return 1
            ;;
    esac
    if [ "${RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_SIGNATURE_THRESHOLD}" -lt 1 ] ||
        [ "${RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_SIGNATURE_THRESHOLD}" -gt 32 ]; then
        echo "External change-authorization signature threshold must be 1..32." >&2
        return 1
    fi
    case "${RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_AUTHORITY_KEYS_JSON}" in
        \[*\]) ;;
        *)
            echo "External change-authorization authority keys must be a JSON array." >&2
            return 1
            ;;
    esac

    if truthy "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_ENABLED:-false}"; then
        if ! truthy "${RG_TEST_STABILITY_JOB_AUTHORITY_HTTP_ENABLED:-false}" ||
            ! truthy "${RG_TEST_STABILITY_JOB_AUTHORITY_JWKS_ENABLED:-false}"; then
            echo "Authority trust cohort requires the HTTP authority and dynamic JWKS trust." >&2
            return 1
        fi
        if [ -z "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_SCOPE_ID:-}" ] ||
            [ -z "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_ID:-}" ]; then
            echo "Authority trust cohort requires scope and cohort ids." >&2
            return 1
        fi
        if ! truthy "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_SIGNED_INVENTORY_ENABLED:-false}";
            then
            echo "Staging authority cohort requires deployment-signed serving inventory." >&2
            return 1
        fi
        if ! truthy "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_REMOTE_ENABLED:-false}";
            then
            echo "Staging authority cohort requires dynamic witnessed inventory refresh." >&2
            return 1
        fi
        if ! truthy "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_TRUST_ROOTS_ENABLED:-false}";
            then
            echo "Staging dynamic inventory requires managed dual trust roots." >&2
            return 1
        fi
        if ! truthy "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_ENABLED:-false}";
            then
            echo "Staging dynamic inventory requires external non-equivocation anchoring." >&2
            return 1
        fi
        if [ -z "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_POLICY_FINGERPRINTS:-}" ] ||
            [ -z "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_REMOTE_URI:-}" ] ||
            [ -z "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_TRUST_ROOTS_URI:-}" ] ||
            [ -z "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_TRUST_ROOT_SET_ID:-}" ] ||
            [ -z "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_TRUST_ROOT_POLICY_FINGERPRINTS:-}" ] ||
            [ -z "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_DEPLOYMENT_ROOT_DOMAIN:-}" ] ||
            [ -z "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_DEPLOYMENT_ROOT_SIGNATURE_THRESHOLD:-}" ] ||
            [ -z "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_DEPLOYMENT_ROOT_AUTHORITY_KEYS_JSON:-}" ] ||
            [ -z "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_WITNESS_ROOT_DOMAIN:-}" ] ||
            [ -z "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_WITNESS_ROOT_SIGNATURE_THRESHOLD:-}" ] ||
            [ -z "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_WITNESS_ROOT_AUTHORITY_KEYS_JSON:-}" ]; then
            echo "Dynamic serving inventory requires HTTPS inventory/root sources and independent bootstrap roots." >&2
            return 1
        fi
        if [ -n "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_SIGNED_INVENTORY_JSON:-}" ]; then
            echo "Staging dynamic serving inventory must not also inject a static inventory document." >&2
            return 1
        fi
        if ! printf '%s' "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_SCOPE_ID}" |
            grep -Eq '^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}$'; then
            echo "Invalid authority trust cohort scope id." >&2
            return 1
        fi
        if ! printf '%s' "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_ID}" |
            grep -Eq '^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}$'; then
            echo "Invalid authority trust cohort id." >&2
            return 1
        fi

        if [ -n "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_EXPECTED_INSTANCE_IDS:-}" ]; then
            local -a cohort_instances
            local cohort_instance
            local cohort_seen="|"
            local local_instance_present=0
            case "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_EXPECTED_INSTANCE_IDS}" in
                ,*|*,|*,,*)
                    echo "Invalid authority trust cohort expected instance list." >&2
                    return 1
                    ;;
            esac
            IFS=',' read -r -a cohort_instances <<< \
                "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_EXPECTED_INSTANCE_IDS}"
            if [ "${#cohort_instances[@]}" -lt 1 ] || [ "${#cohort_instances[@]}" -gt 256 ]; then
                echo "Authority trust cohort expected instance count must be 1..256." >&2
                return 1
            fi
            for cohort_instance in "${cohort_instances[@]}"; do
                if ! printf '%s' "${cohort_instance}" |
                    grep -Eq '^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}$'; then
                    echo "Invalid authority trust cohort expected instance id." >&2
                    return 1
                fi
                case "${cohort_seen}" in
                    *"|${cohort_instance}|"*)
                        echo "Duplicate authority trust cohort expected instance id." >&2
                        return 1
                        ;;
                esac
                cohort_seen="${cohort_seen}${cohort_instance}|"
                if [ "${cohort_instance}" = "${RG_RESOURCE_GATEWAY_INSTANCE_ID}" ]; then
                    local_instance_present=1
                fi
            done
            if [ "${local_instance_present}" -ne 1 ]; then
                echo "Configured inventory assertion must include the local instance id." >&2
                return 1
            fi
        fi

        if [ -n "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_TRUST_DOMAIN:-}" ] ||
            [ "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_SIGNATURE_THRESHOLD:-0}" != "0" ] ||
            [ -n "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_WITNESS_DOMAIN:-}" ] ||
            [ "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_WITNESS_SIGNATURE_THRESHOLD:-0}" != "0" ]; then
            echo "Managed trust roots forbid static serving-inventory runtime trust settings." >&2
            return 1
        fi
        case "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_AUTHORITY_KEYS_JSON:-[]}" in
            \[\]) ;;
            *)
                echo "Managed trust roots forbid static serving-inventory runtime keys." >&2
                return 1
                ;;
        esac
        case "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_WITNESS_AUTHORITY_KEYS_JSON:-[]}" in
            \[\]) ;;
            *)
                echo "Managed trust roots forbid static serving-inventory witness keys." >&2
                return 1
                ;;
        esac
        if ! printf '%s' \
            "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_POLICY_FINGERPRINTS}" |
            grep -Eq '^sha256:[a-f0-9]{64}(,sha256:[a-f0-9]{64}){0,31}$'; then
            echo "Invalid signed serving-inventory policy fingerprints." >&2
            return 1
        fi
        case "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_REMOTE_URI}" in
            https://*) ;;
            *)
                echo "Serving-inventory publication endpoint must use HTTPS." >&2
                return 1
                ;;
        esac
        case "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_TRUST_ROOTS_URI}" in
            https://*) ;;
            *)
                echo "Serving-inventory trust-root endpoint must use HTTPS." >&2
                return 1
                ;;
        esac
        if ! printf '%s' "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_TRUST_ROOT_SET_ID}" |
            grep -Eq '^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}$'; then
            echo "Invalid serving-inventory trust-root set id." >&2
            return 1
        fi
        if ! printf '%s' \
            "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_TRUST_ROOT_POLICY_FINGERPRINTS}" |
            grep -Eq '^sha256:[a-f0-9]{64}(,sha256:[a-f0-9]{64}){0,31}$'; then
            echo "Invalid serving-inventory trust-root policy fingerprints." >&2
            return 1
        fi
        if ! printf '%s' "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_DEPLOYMENT_ROOT_DOMAIN}" |
            grep -Eq '^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}$' ||
            ! printf '%s' "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_WITNESS_ROOT_DOMAIN}" |
            grep -Eq '^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}$' ||
            [ "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_DEPLOYMENT_ROOT_DOMAIN}" =
            "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_WITNESS_ROOT_DOMAIN}" ]; then
            echo "Bootstrap root trust domains must be valid and independent." >&2
            return 1
        fi
        case "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_DEPLOYMENT_ROOT_SIGNATURE_THRESHOLD}" in
            ''|*[!0-9]*)
                echo "Invalid deployment bootstrap-root signature threshold." >&2
                return 1
                ;;
        esac
        case "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_WITNESS_ROOT_SIGNATURE_THRESHOLD}" in
            ''|*[!0-9]*)
                echo "Invalid witness bootstrap-root signature threshold." >&2
                return 1
                ;;
        esac
        if [ "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_DEPLOYMENT_ROOT_SIGNATURE_THRESHOLD}" -lt 1 ] ||
            [ "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_DEPLOYMENT_ROOT_SIGNATURE_THRESHOLD}" -gt 32 ] ||
            [ "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_WITNESS_ROOT_SIGNATURE_THRESHOLD}" -lt 1 ] ||
            [ "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_WITNESS_ROOT_SIGNATURE_THRESHOLD}" -gt 32 ]; then
            echo "Bootstrap-root signature thresholds must be 1..32." >&2
            return 1
        fi
        case "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_DEPLOYMENT_ROOT_AUTHORITY_KEYS_JSON}" in
            \[*\]) ;;
            *)
                echo "Deployment bootstrap-root keys must be a JSON array." >&2
                return 1
                ;;
        esac
        case "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_WITNESS_ROOT_AUTHORITY_KEYS_JSON}" in
            \[*\]) ;;
            *)
                echo "Witness bootstrap-root keys must be a JSON array." >&2
                return 1
                ;;
        esac

        if [ -z "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_TRUST_DOMAIN:-}" ] ||
            [ -z "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_SET_ID:-}" ] ||
            [ -z "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_SIGNATURE_THRESHOLD:-}" ] ||
            [ -z "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_MAXIMUM_FAULTS:-}" ] ||
            [ -z "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_AUTHORITY_KEYS_JSON:-}" ] ||
            [ -z "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_ENDPOINTS_JSON:-}" ]; then
            echo "External inventory anchoring requires trust, quorum, public keys, and endpoints." >&2
            return 1
        fi
        if truthy "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_ALLOW_INSECURE_LOOPBACK:-false}"; then
            echo "Staging external inventory anchors must use HTTPS." >&2
            return 1
        fi
        if printf '%s\n%s\n' \
            "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_TRUST_DOMAIN}" \
            "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_SET_ID}" |
            grep -Eqv '^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}$'; then
            echo "Invalid external inventory anchor trust domain or set id." >&2
            return 1
        fi
        case "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_AUTHORITY_KEYS_JSON}" in
            \[*\]) ;;
            *)
                echo "External inventory anchor keys must be a JSON array." >&2
                return 1
                ;;
        esac
        case "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_ENDPOINTS_JSON}" in
            \[*\]) ;;
            *)
                echo "External inventory anchor endpoints must be a JSON array." >&2
                return 1
                ;;
        esac
        if printf '%s' \
            "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_AUTHORITY_KEYS_JSON}" |
            grep -Eq '^\[[[:space:]]*\]$' ||
            printf '%s' \
            "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_ENDPOINTS_JSON}" |
            grep -Eq '^\[[[:space:]]*\]$'; then
            echo "External inventory anchor keys and endpoints must be non-empty." >&2
            return 1
        fi

        local anchor_threshold="${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_SIGNATURE_THRESHOLD}"
        local anchor_maximum_faults="${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_MAXIMUM_FAULTS}"
        local anchor_minimum_faults="${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_MINIMUM_FAULTS:-1}"
        local anchor_timeout_ms="${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_TIMEOUT_MS:-3000}"
        local anchor_clock_skew_seconds="${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_CLOCK_SKEW_SECONDS:-5}"
        local anchor_lifetime_seconds="${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_MAXIMUM_RECEIPT_LIFETIME_SECONDS:-15}"
        if printf '%s\n%s\n%s\n%s\n%s\n' "${anchor_threshold}" \
            "${anchor_maximum_faults}" "${anchor_minimum_faults}" \
            "${anchor_timeout_ms}" "${anchor_lifetime_seconds}" |
            grep -Eqv '^[1-9][0-9]*$' ||
            ! printf '%s' "${anchor_clock_skew_seconds}" |
            grep -Eq '^(0|[1-9][0-9]*)$'; then
            echo "External inventory anchor policy values must be canonical integers." >&2
            return 1
        fi
        if [ "${anchor_threshold}" -gt 32 ] ||
            [ "${anchor_maximum_faults}" -lt 1 ] ||
            [ "${anchor_maximum_faults}" -gt 10 ] ||
            [ "${anchor_minimum_faults}" -lt 1 ] ||
            [ "${anchor_minimum_faults}" -gt 10 ] ||
            [ "${anchor_maximum_faults}" -lt "${anchor_minimum_faults}" ] ||
            [ "${anchor_threshold}" -lt $((anchor_maximum_faults * 2 + 1)) ]; then
            echo "External inventory anchor quorum must satisfy f>=1 and threshold>=2f+1." >&2
            return 1
        fi
        if [ "${anchor_timeout_ms}" -lt 100 ] || [ "${anchor_timeout_ms}" -gt 30000 ] ||
            [ "${anchor_clock_skew_seconds}" -gt 30 ] ||
            [ "${anchor_lifetime_seconds}" -gt 60 ] ||
            [ "${anchor_timeout_ms}" -ge $((anchor_lifetime_seconds * 1000)) ]; then
            echo "External inventory anchor timing bounds are invalid." >&2
            return 1
        fi

        local inventory_refresh_seconds="${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_REMOTE_REFRESH_SECONDS:-30}"
        local inventory_timeout_ms="${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_REMOTE_TIMEOUT_MS:-3000}"
        local inventory_maximum_age_seconds="${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_REMOTE_MAXIMUM_AGE_SECONDS:-60}"
        if printf '%s\n%s\n%s\n' "${inventory_refresh_seconds}" \
            "${inventory_timeout_ms}" "${inventory_maximum_age_seconds}" |
            grep -Eqv '^[1-9][0-9]*$'; then
            echo "Serving-inventory refresh settings must be canonical positive integers." >&2
            return 1
        fi

        local root_refresh_seconds="${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_TRUST_ROOTS_REFRESH_SECONDS:-30}"
        local root_timeout_ms="${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_TRUST_ROOTS_TIMEOUT_MS:-3000}"
        local root_unknown_seconds="${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_TRUST_ROOTS_UNKNOWN_KEY_REFRESH_SECONDS:-5}"
        local root_maximum_age_seconds="${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_TRUST_ROOTS_MAXIMUM_AGE_SECONDS:-60}"
        if printf '%s\n%s\n%s\n%s\n' "${root_refresh_seconds}" "${root_timeout_ms}" \
            "${root_unknown_seconds}" "${root_maximum_age_seconds}" |
            grep -Eqv '^[1-9][0-9]*$'; then
            echo "Trust-root refresh settings must be canonical positive integers." >&2
            return 1
        fi
        if [ "${root_refresh_seconds}" -lt 1 ] || [ "${root_refresh_seconds}" -gt 3600 ] ||
            [ "${root_timeout_ms}" -lt 100 ] || [ "${root_timeout_ms}" -gt 30000 ] ||
            [ "${root_unknown_seconds}" -lt 1 ] || [ "${root_unknown_seconds}" -gt 3600 ] ||
            [ "${root_maximum_age_seconds}" -lt 2 ] ||
            [ "${root_maximum_age_seconds}" -gt 86400 ] ||
            [ $((root_maximum_age_seconds * 1000)) -lt \
            $((root_refresh_seconds * 1000 + root_timeout_ms)) ]; then
            echo "Trust-root maximum age must cover valid refresh and timeout bounds." >&2
            return 1
        fi
        if [ "${inventory_refresh_seconds}" -lt 1 ] ||
            [ "${inventory_refresh_seconds}" -gt 3600 ] ||
            [ "${inventory_timeout_ms}" -lt 100 ] ||
            [ "${inventory_timeout_ms}" -gt 30000 ] ||
            [ "${inventory_maximum_age_seconds}" -lt 2 ] ||
            [ "${inventory_maximum_age_seconds}" -gt 86400 ] ||
            [ $((inventory_maximum_age_seconds * 1000)) -lt \
            $((inventory_refresh_seconds * 1000 + inventory_timeout_ms)) ]; then
            echo "Serving-inventory maximum age must cover valid refresh and timeout bounds." >&2
            return 1
        fi

        local heartbeat_seconds="${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_HEARTBEAT_SECONDS:-10}"
        local lease_seconds="${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_LEASE_SECONDS:-30}"
        local retention_seconds="${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_RETENTION_SECONDS:-86400}"
        if printf '%s\n%s\n%s\n' \
            "${heartbeat_seconds}" "${lease_seconds}" "${retention_seconds}" |
            grep -Eqv '^[1-9][0-9]*$'; then
            echo "Authority trust cohort timing values must be canonical positive whole seconds." >&2
            return 1
        fi
        if [ "${heartbeat_seconds}" -lt 1 ] || [ "${heartbeat_seconds}" -gt 300 ]; then
            echo "Authority trust cohort heartbeat must be 1..300 seconds." >&2
            return 1
        fi
        if [ "${lease_seconds}" -lt 3 ] || [ "${lease_seconds}" -gt 900 ] ||
            [ "${lease_seconds}" -lt $((heartbeat_seconds * 3)) ]; then
            echo "Authority trust cohort lease must be 3..900 seconds and at least 3x heartbeat." >&2
            return 1
        fi
        if [ "${retention_seconds}" -lt 3600 ] || [ "${retention_seconds}" -gt 2592000 ] ||
            [ "${retention_seconds}" -lt "${lease_seconds}" ]; then
            echo "Authority trust cohort retention must be 3600..2592000 seconds and at least the lease." >&2
            return 1
        fi
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
