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
  RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_AUTHORITY_KEYS_JSON  must be [] in staging
  RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_ENDPOINTS_JSON  required HTTPS notary endpoints
  RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_TRUST_URI  required HTTPS managed-trust publication
  RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_TRUST_ROOT_SET_ID  required stable trust-root set id
  RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_BOOTSTRAP_TRUST_DOMAIN  required independent bootstrap domain
  RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_TRUST_POLICY_FINGERPRINTS  required accepted policies
  RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_BOOTSTRAP_ROOTS_ENABLED  required true
  RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_BOOTSTRAP_ROOT_GENESIS_JSON  required public-only pinned genesis
  RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_BOOTSTRAP_ROOT_POLICY_FINGERPRINTS  required accepted root policies
  RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_BOOTSTRAP_ROOT_BUNDLE_URI  required HTTPS complete-chain bundle
  RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_BOOTSTRAP_THRESHOLD  legacy; must be 0
  RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_BOOTSTRAP_KEYS_JSON  legacy; must be []
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
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_AUTHORITY_KEYS_JSON  must be [] in staging
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_ENDPOINTS_JSON  required HTTPS notary endpoints
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_TRUST_URI  required HTTPS managed-trust publication
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_TRUST_ROOT_SET_ID  required stable trust-root set id
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_BOOTSTRAP_TRUST_DOMAIN  required independent bootstrap domain
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_TRUST_POLICY_FINGERPRINTS  required accepted policies
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_BOOTSTRAP_ROOTS_ENABLED  required true
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_BOOTSTRAP_ROOT_GENESIS_JSON  required public-only pinned genesis
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_BOOTSTRAP_ROOT_POLICY_FINGERPRINTS  required accepted root policies
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_BOOTSTRAP_ROOT_BUNDLE_URI  required HTTPS complete-chain bundle
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_BOOTSTRAP_THRESHOLD  legacy; must be 0
  RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_BOOTSTRAP_KEYS_JSON  legacy; must be []
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
  RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_ENABLED  optional; enables durable root-chain publication
  RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_ENDPOINT  required strict HTTPS publisher endpoint
  RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_TRANSPORT_ENABLED  required true for staging publication
  RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_TRANSPORT_REQUIRED  required true for staging publication
  RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_TRANSPORT_TRUST_STORE_PATH  optional absolute PKCS#12 private roots
  RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_TRANSPORT_TRUST_STORE_PASSWORD_REF  required env:VARIABLE with private roots
  RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_TRANSPORT_CLIENT_KEY_STORE_PATH  required dedicated PKCS#12 client identity
  RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_TRANSPORT_CLIENT_KEY_STORE_PASSWORD_REF  required env:VARIABLE reference
  RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_TRANSPORT_SERVER_SPKI_PINS  required canonical sha256 pins
  RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_ENABLED  optional; enables durable recovery fleet
  RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_ENABLED  required true for staging fleet
  RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_PUBLICATION_URI  required HTTPS inventory source
  RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOTS_ENABLED  required true for staging fleet
  RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_URI  required distinct HTTPS dual-root source
  RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_SET_ID  required stable root-set identity
  RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_DEPLOYMENT_ROOT_DOMAIN  required bootstrap domain
  RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_WITNESS_ROOT_DOMAIN  required independent bootstrap domain
  RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRANSPORT_ENABLED  required true for staging fleet
  RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRANSPORT_REQUIRED  required true for staging fleet
  RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRANSPORT_TRUST_STORE_PATH  optional absolute PKCS#12 private roots
  RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRANSPORT_TRUST_STORE_PASSWORD_REF  required env:VARIABLE with private roots
  RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRANSPORT_CLIENT_KEY_STORE_PATH  required readable PKCS#12 client identity
  RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRANSPORT_CLIENT_KEY_STORE_PASSWORD_REF  required env:VARIABLE reference
  RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRANSPORT_SERVER_SPKI_PINS  required canonical sha256 pins
  RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_TRANSPORT_ENABLED  required independent pinned mTLS source
  RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_TRANSPORT_REQUIRED  required true for staging fleet
  RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_TRANSPORT_TRUST_STORE_PATH  optional absolute PKCS#12 private roots
  RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_TRANSPORT_TRUST_STORE_PASSWORD_REF  required env:VARIABLE with private roots
  RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_TRANSPORT_CLIENT_KEY_STORE_PATH  required distinct PKCS#12 client identity
  RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_TRANSPORT_CLIENT_KEY_STORE_PASSWORD_REF  required independent env:VARIABLE reference
  RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_TRANSPORT_SERVER_SPKI_PINS  required canonical sha256 pins
  RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_EXTERNAL_ANCHOR_ENABLED  required true for staging fleet
  RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_EXTERNAL_ANCHOR_TRUST_DOMAIN  required independent notary domain
  RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_EXTERNAL_ANCHOR_SET_ID  required stable notary-set identity
  RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_EXTERNAL_ANCHOR_ENDPOINTS_JSON  required non-empty notary array
  RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_EXTERNAL_ANCHOR_MANAGED_TRUST_ENABLED  required true for staging fleet
  RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_EXTERNAL_ANCHOR_BOOTSTRAP_ROOTS_ENABLED  required true for staging fleet
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

validate_managed_external_anchor_trust() {
    local prefix="$1"
    local label="$2"
    local managed_var="${prefix}_MANAGED_TRUST_ENABLED"
    local managed_required_var="${prefix}_MANAGED_TRUST_REQUIRED"
    local managed_roots_var="${prefix}_BOOTSTRAP_ROOTS_ENABLED"
    local managed_roots_required_var="${prefix}_BOOTSTRAP_ROOTS_REQUIRED"
    local static_keys_var="${prefix}_AUTHORITY_KEYS_JSON"
    local notary_domain_var="${prefix}_TRUST_DOMAIN"
    local trust_uri_var="${prefix}_TRUST_URI"
    local root_set_var="${prefix}_TRUST_ROOT_SET_ID"
    local bootstrap_domain_var="${prefix}_BOOTSTRAP_TRUST_DOMAIN"
    local policy_var="${prefix}_TRUST_POLICY_FINGERPRINTS"
    local legacy_threshold_var="${prefix}_BOOTSTRAP_THRESHOLD"
    local bootstrap_keys_var="${prefix}_BOOTSTRAP_KEYS_JSON"
    local allow_loopback_var="${prefix}_TRUST_ALLOW_INSECURE_LOOPBACK"
    local root_genesis_var="${prefix}_BOOTSTRAP_ROOT_GENESIS_JSON"
    local root_policy_var="${prefix}_BOOTSTRAP_ROOT_POLICY_FINGERPRINTS"
    local root_bundle_var="${prefix}_BOOTSTRAP_ROOT_BUNDLE_URI"
    local root_allow_loopback_var="${prefix}_BOOTSTRAP_ROOT_ALLOW_INSECURE_LOOPBACK"

    if ! truthy "${!managed_var:-true}" ||
        ! truthy "${!managed_required_var:-true}"; then
        echo "Staging ${label} external anchor requires managed notary trust." >&2
        return 1
    fi
    if ! truthy "${!managed_roots_var:-true}" ||
        ! truthy "${!managed_roots_required_var:-true}"; then
        echo "Staging ${label} external anchor requires managed bootstrap-root trust." >&2
        return 1
    fi
    if ! printf '%s' "${!static_keys_var:-[]}" |
        grep -Eq '^\[[[:space:]]*\]$'; then
        echo "Managed ${label} external anchor forbids static notary keys." >&2
        return 1
    fi
    if [ -z "${!trust_uri_var:-}" ] || [ -z "${!root_set_var:-}" ] ||
        [ -z "${!bootstrap_domain_var:-}" ] || [ -z "${!policy_var:-}" ] ||
        [ -z "${!root_genesis_var:-}" ] || [ -z "${!root_policy_var:-}" ] ||
        [ -z "${!root_bundle_var:-}" ]; then
        echo "Managed ${label} external anchor requires notary trust plus a pinned genesis and root bundle." >&2
        return 1
    fi
    case "${!trust_uri_var}" in
        https://*) ;;
        *)
            echo "Managed ${label} external-anchor trust publication must use HTTPS." >&2
            return 1
            ;;
    esac
    if truthy "${!allow_loopback_var:-false}"; then
        echo "Staging ${label} external-anchor trust publication must not allow insecure loopback." >&2
        return 1
    fi
    case "${!root_bundle_var}" in
        https://*) ;;
        *)
            echo "Managed ${label} bootstrap-root bundle must use HTTPS." >&2
            return 1
            ;;
    esac
    if truthy "${!root_allow_loopback_var:-false}"; then
        echo "Staging ${label} bootstrap-root bundle must not allow insecure loopback." >&2
        return 1
    fi
    if ! printf '%s\n%s\n' "${!root_set_var}" "${!bootstrap_domain_var}" |
        grep -Eq '^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}$' ||
        [ "${!bootstrap_domain_var}" = "${!notary_domain_var:-}" ]; then
        echo "Managed ${label} external-anchor root set or independent bootstrap domain is invalid." >&2
        return 1
    fi
    if ! printf '%s' "${!policy_var}" |
        grep -Eq '^sha256:[a-f0-9]{64}(,sha256:[a-f0-9]{64}){0,31}$'; then
        echo "Managed ${label} external-anchor trust policy fingerprints are invalid." >&2
        return 1
    fi
    case "${!root_genesis_var}" in
        \{*\}) ;;
        *)
            echo "Managed ${label} bootstrap-root genesis must be a JSON object." >&2
            return 1
            ;;
    esac
    if printf '%s' "${!root_genesis_var}" |
        grep -Eiq '"(privateKey|privateKeyBase64|secret|credential)"[[:space:]]*:'; then
        echo "Managed ${label} bootstrap-root genesis must contain public material only." >&2
        return 1
    fi
    if ! printf '%s' "${!root_policy_var}" |
        grep -Eq '^sha256:[a-f0-9]{64}(,sha256:[a-f0-9]{64}){0,31}$'; then
        echo "Managed ${label} bootstrap-root policy fingerprints are invalid." >&2
        return 1
    fi
    if ! printf '%s' "${!bootstrap_keys_var:-[]}" |
        grep -Eq '^\[[[:space:]]*\]$'; then
        echo "Managed ${label} bootstrap-root chain forbids legacy static bootstrap keys." >&2
        return 1
    fi
    if [ -n "${!legacy_threshold_var:-}" ] && [ "${!legacy_threshold_var}" != "0" ]; then
        echo "Managed ${label} bootstrap-root chain forbids legacy static bootstrap keys." >&2
        return 1
    fi

    local refresh_var="${prefix}_TRUST_REFRESH_SECONDS"
    local timeout_var="${prefix}_TRUST_TIMEOUT_MS"
    local unknown_var="${prefix}_TRUST_UNKNOWN_KEY_REFRESH_SECONDS"
    local age_var="${prefix}_TRUST_MAXIMUM_AGE_SECONDS"
    local lifetime_var="${prefix}_TRUST_MAXIMUM_PUBLICATION_LIFETIME_SECONDS"
    local skew_var="${prefix}_TRUST_CLOCK_SKEW_SECONDS"
    local remaining_var="${prefix}_TRUST_MINIMUM_REMAINING_VALIDITY_SECONDS"
    local refresh_seconds="${!refresh_var:-30}"
    local timeout_ms="${!timeout_var:-3000}"
    local unknown_seconds="${!unknown_var:-5}"
    local maximum_age_seconds="${!age_var:-60}"
    local lifetime_seconds="${!lifetime_var:-86400}"
    local skew_seconds="${!skew_var:-5}"
    local remaining_seconds="${!remaining_var:-30}"
    if printf '%s\n%s\n%s\n%s\n%s\n%s\n%s\n' \
        "${refresh_seconds}" "${timeout_ms}" \
        "${unknown_seconds}" "${maximum_age_seconds}" "${lifetime_seconds}" \
        "${skew_seconds}" "${remaining_seconds}" | grep -Eqv '^(0|[1-9][0-9]*)$'; then
        echo "Managed ${label} external-anchor trust timing values must be canonical integers." >&2
        return 1
    fi
    if [ "${refresh_seconds}" -lt 1 ] || [ "${refresh_seconds}" -gt 3600 ] ||
        [ "${timeout_ms}" -lt 100 ] || [ "${timeout_ms}" -gt 30000 ] ||
        [ "${unknown_seconds}" -lt 1 ] || [ "${unknown_seconds}" -gt 600 ] ||
        [ "${maximum_age_seconds}" -lt "${refresh_seconds}" ] ||
        [ "${maximum_age_seconds}" -gt 86400 ] ||
        [ "${lifetime_seconds}" -lt 60 ] || [ "${lifetime_seconds}" -gt 604800 ] ||
        [ "${skew_seconds}" -gt 30 ] || [ "${remaining_seconds}" -gt 3600 ] ||
        [ "${remaining_seconds}" -ge "${lifetime_seconds}" ]; then
        echo "Managed ${label} external-anchor trust timing bounds are invalid." >&2
        return 1
    fi

    local root_refresh_var="${prefix}_BOOTSTRAP_ROOT_REFRESH_SECONDS"
    local root_timeout_var="${prefix}_BOOTSTRAP_ROOT_TIMEOUT_MS"
    local root_unknown_var="${prefix}_BOOTSTRAP_ROOT_UNKNOWN_KEY_REFRESH_SECONDS"
    local root_age_var="${prefix}_BOOTSTRAP_ROOT_MAXIMUM_AGE_SECONDS"
    local root_lifetime_var="${prefix}_BOOTSTRAP_ROOT_MAXIMUM_LIFETIME_SECONDS"
    local root_skew_var="${prefix}_BOOTSTRAP_ROOT_CLOCK_SKEW_SECONDS"
    local root_remaining_var="${prefix}_BOOTSTRAP_ROOT_MINIMUM_REMAINING_VALIDITY_SECONDS"
    local root_transitions_var="${prefix}_BOOTSTRAP_ROOT_MAXIMUM_TRANSITIONS"
    local root_refresh="${!root_refresh_var:-30}"
    local root_timeout="${!root_timeout_var:-3000}"
    local root_unknown="${!root_unknown_var:-5}"
    local root_age="${!root_age_var:-60}"
    local root_lifetime="${!root_lifetime_var:-2592000}"
    local root_skew="${!root_skew_var:-5}"
    local root_remaining="${!root_remaining_var:-30}"
    local root_transitions="${!root_transitions_var:-128}"
    if printf '%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n' \
        "${root_refresh}" "${root_timeout}" "${root_unknown}" "${root_age}" \
        "${root_lifetime}" "${root_skew}" "${root_remaining}" \
        "${root_transitions}" | grep -Eqv '^(0|[1-9][0-9]*)$'; then
        echo "Managed ${label} bootstrap-root timing values must be canonical integers." >&2
        return 1
    fi
    if [ "${root_refresh}" -lt 1 ] || [ "${root_refresh}" -gt 3600 ] ||
        [ "${root_timeout}" -lt 100 ] || [ "${root_timeout}" -gt 30000 ] ||
        [ "${root_unknown}" -lt 1 ] || [ "${root_unknown}" -gt 600 ] ||
        [ "${root_age}" -lt "${root_refresh}" ] || [ "${root_age}" -gt 86400 ] ||
        [ "${root_lifetime}" -lt 3600 ] || [ "${root_lifetime}" -gt 31622400 ] ||
        [ "${root_skew}" -gt 30 ] || [ "${root_remaining}" -gt 604800 ] ||
        [ "${root_remaining}" -ge "${root_lifetime}" ] ||
        [ "${root_transitions}" -lt 1 ] || [ "${root_transitions}" -gt 128 ]; then
        echo "Managed ${label} bootstrap-root timing bounds are invalid." >&2
        return 1
    fi
}

validate_control_plane_transport() {
    local prefix="$1"
    local label="$2"
    local enabled_var="${prefix}_ENABLED"
    local required_var="${prefix}_REQUIRED"
    local trust_path_var="${prefix}_TRUST_STORE_PATH"
    local trust_ref_var="${prefix}_TRUST_STORE_PASSWORD_REF"
    local client_path_var="${prefix}_CLIENT_KEY_STORE_PATH"
    local client_ref_var="${prefix}_CLIENT_KEY_STORE_PASSWORD_REF"
    local pins_var="${prefix}_SERVER_SPKI_PINS"

    if ! truthy "${!enabled_var:-false}" || ! truthy "${!required_var:-true}"; then
        echo "Staging recovery-fleet ${label} requires pinned mutual TLS." >&2
        return 1
    fi
    if [ -z "${!client_path_var:-}" ] || [ -z "${!client_ref_var:-}" ] ||
        [ -z "${!pins_var:-}" ] ||
        { [ -z "${!trust_path_var:-}" ] && [ -n "${!trust_ref_var:-}" ]; } ||
        { [ -n "${!trust_path_var:-}" ] && [ -z "${!trust_ref_var:-}" ]; }; then
        echo "${label} transport requires a client identity, pins, and a complete optional trust-store pair." >&2
        return 1
    fi
    case "${!client_path_var}" in
        /*) ;;
        *) echo "Recovery-fleet ${label} client key store must use an absolute path." >&2; return 1 ;;
    esac
    if [ ! -f "${!client_path_var}" ] || [ ! -r "${!client_path_var}" ]; then
        echo "Recovery-fleet ${label} client key store is not readable." >&2
        return 1
    fi
    if [ -n "${!trust_path_var:-}" ]; then
        case "${!trust_path_var}" in
            /*) ;;
            *) echo "Recovery-fleet ${label} trust store must use an absolute path." >&2; return 1 ;;
        esac
        if [ ! -f "${!trust_path_var}" ] || [ ! -r "${!trust_path_var}" ]; then
            echo "Recovery-fleet ${label} trust store is not readable." >&2
            return 1
        fi
    fi
    local client_ref="${!client_ref_var}"
    if ! printf '%s' "${client_ref}" | grep -Eq '^env:[A-Z][A-Z0-9_]{0,127}$'; then
        echo "Recovery-fleet ${label} client credential must be an env:VARIABLE reference." >&2
        return 1
    fi
    local client_secret="${client_ref#env:}"
    if [ -z "${!client_secret:-}" ]; then
        echo "Recovery-fleet ${label} client credential is unavailable." >&2
        return 1
    fi
    if [ -n "${!trust_ref_var:-}" ]; then
        local trust_ref="${!trust_ref_var}"
        if ! printf '%s' "${trust_ref}" | grep -Eq '^env:[A-Z][A-Z0-9_]{0,127}$'; then
            echo "Recovery-fleet ${label} trust-store credential must be an env:VARIABLE reference." >&2
            return 1
        fi
        local trust_secret="${trust_ref#env:}"
        if [ -z "${!trust_secret:-}" ]; then
            echo "Recovery-fleet ${label} trust-store credential is unavailable." >&2
            return 1
        fi
    fi
    if ! printf '%s' "${!pins_var}" |
        grep -Eq '^sha256:[a-f0-9]{64}(,sha256:[a-f0-9]{64}){0,15}$'; then
        echo "Recovery-fleet ${label} SPKI pins are invalid." >&2
        return 1
    fi
}

validate_recovery_fleet_external_anchor() {
    local prefix="RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_EXTERNAL_ANCHOR"
    if ! truthy "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_EXTERNAL_ANCHOR_ENABLED:-false}" ||
        ! truthy "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_EXTERNAL_ANCHOR_REQUIRED:-true}"; then
        echo "Staging recovery fleet requires external Byzantine inventory non-equivocation." >&2
        return 1
    fi
    if [ -z "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_EXTERNAL_ANCHOR_TRUST_DOMAIN:-}" ] ||
        [ -z "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_EXTERNAL_ANCHOR_SET_ID:-}" ] ||
        [ -z "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_EXTERNAL_ANCHOR_SIGNATURE_THRESHOLD:-}" ] ||
        [ -z "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_EXTERNAL_ANCHOR_MAXIMUM_FAULTS:-}" ] ||
        [ -z "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_EXTERNAL_ANCHOR_ENDPOINTS_JSON:-}" ]; then
        echo "Recovery-fleet external anchoring requires trust, quorum, and endpoints." >&2
        return 1
    fi
    if truthy "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_EXTERNAL_ANCHOR_ALLOW_INSECURE_LOOPBACK:-false}"; then
        echo "Staging recovery-fleet external anchors must use HTTPS." >&2
        return 1
    fi
    if printf '%s\n%s\n' \
        "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_EXTERNAL_ANCHOR_TRUST_DOMAIN}" \
        "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_EXTERNAL_ANCHOR_SET_ID}" |
        grep -Eqv '^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}$'; then
        echo "Recovery-fleet external anchor trust domain or set id is invalid." >&2
        return 1
    fi
    if ! printf '%s' "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_EXTERNAL_ANCHOR_ENDPOINTS_JSON}" |
        grep -Eq '^\[.+\]$'; then
        echo "Recovery-fleet external anchor endpoints must be a non-empty JSON array." >&2
        return 1
    fi
    local threshold="${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_EXTERNAL_ANCHOR_SIGNATURE_THRESHOLD}"
    local maximum_faults="${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_EXTERNAL_ANCHOR_MAXIMUM_FAULTS}"
    local minimum_faults="${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_EXTERNAL_ANCHOR_MINIMUM_FAULTS:-1}"
    local timeout_ms="${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_EXTERNAL_ANCHOR_TIMEOUT_MS:-3000}"
    local skew="${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_EXTERNAL_ANCHOR_CLOCK_SKEW_SECONDS:-5}"
    local lifetime="${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_EXTERNAL_ANCHOR_MAXIMUM_RECEIPT_LIFETIME_SECONDS:-15}"
    if printf '%s\n%s\n%s\n%s\n%s\n' "${threshold}" "${maximum_faults}" \
        "${minimum_faults}" "${timeout_ms}" "${lifetime}" | grep -Eqv '^[1-9][0-9]*$' ||
        ! printf '%s' "${skew}" | grep -Eq '^(0|[1-9][0-9]*)$' ||
        [ "${maximum_faults}" -lt 1 ] || [ "${maximum_faults}" -gt 10 ] ||
        [ "${minimum_faults}" -lt 1 ] || [ "${minimum_faults}" -gt "${maximum_faults}" ] ||
        [ "${threshold}" -gt 32 ] || [ "${threshold}" -lt $((maximum_faults * 2 + 1)) ] ||
        [ "${timeout_ms}" -lt 100 ] || [ "${timeout_ms}" -gt 30000 ] ||
        [ "${skew}" -gt 30 ] || [ "${lifetime}" -gt 60 ] ||
        [ "${timeout_ms}" -ge $((lifetime * 1000)) ]; then
        echo "Recovery-fleet external anchor quorum or timing policy is invalid." >&2
        return 1
    fi
    validate_managed_external_anchor_trust "${prefix}" "recovery-fleet"
}

validate_bootstrap_root_publisher_transport() {
    if ! truthy "${RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_ENABLED:-false}"; then
        return 0
    fi
    case "${RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_ENDPOINT:-}" in
        https://*) ;;
        *) echo "Staging bootstrap-root publisher must use HTTPS." >&2; return 1 ;;
    esac
    if truthy "${RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_ALLOW_INSECURE_LOOPBACK:-false}"; then
        echo "Staging bootstrap-root publisher forbids insecure loopback." >&2
        return 1
    fi
    validate_control_plane_transport \
        "RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_TRANSPORT" \
        "bootstrap-root publisher" || return 1

    if truthy "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_ENABLED:-false}"; then
        local publisher_path="${RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_TRANSPORT_CLIENT_KEY_STORE_PATH}"
        local publisher_ref="${RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_TRANSPORT_CLIENT_KEY_STORE_PASSWORD_REF}"
        local inventory_path="${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRANSPORT_CLIENT_KEY_STORE_PATH:-}"
        local inventory_ref="${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRANSPORT_CLIENT_KEY_STORE_PASSWORD_REF:-}"
        local root_path="${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_TRANSPORT_CLIENT_KEY_STORE_PATH:-}"
        local root_ref="${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_TRANSPORT_CLIENT_KEY_STORE_PASSWORD_REF:-}"
        if { [ "${publisher_path}" = "${inventory_path}" ] &&
            [ "${publisher_ref}" = "${inventory_ref}" ]; } ||
            { [ "${publisher_path}" = "${root_path}" ] &&
            [ "${publisher_ref}" = "${root_ref}" ]; }; then
            echo "Bootstrap-root publisher requires a client identity independent from recovery-fleet sources." >&2
            return 1
        fi
    fi
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
    if [ -z "${RG_TEST_SECRET_AUTHORITY_COHORT_SCOPE_ID:-}" ] ||
        [ -z "${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_TRUST_DOMAIN:-}" ] ||
        [ -z "${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_SET_ID:-}" ] ||
        [ -z "${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_SIGNATURE_THRESHOLD:-}" ] ||
        [ -z "${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_MAXIMUM_FAULTS:-}" ] ||
        [ -z "${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_ENDPOINTS_JSON:-}" ]; then
        echo "Test-secret external anchoring requires trust, quorum, and endpoints." >&2
        return 1
    fi
    if truthy "${RG_TEST_SECRET_AUTHORITY_ALLOW_INSECURE_LOOPBACK:-false}" ||
        truthy "${RG_TEST_SECRET_AUTHORITY_JWKS_ALLOW_INSECURE_LOOPBACK:-false}" ||
        truthy "${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_REMOTE_ALLOW_INSECURE_LOOPBACK:-false}" ||
        truthy "${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_TRUST_ROOTS_ALLOW_INSECURE_LOOPBACK:-false}" ||
        truthy "${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_ALLOW_INSECURE_LOOPBACK:-false}" ||
        truthy "${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_TRUST_ALLOW_INSECURE_LOOPBACK:-false}";
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
    case "${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_ENDPOINTS_JSON}" in
        \[*\]) ;;
        *)
            echo "Test-secret external anchor endpoints must be a JSON array." >&2
            return 1
            ;;
    esac
    if printf '%s' \
        "${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_ENDPOINTS_JSON}" |
        grep -Eq '^\[[[:space:]]*\]$'; then
        echo "Test-secret external anchor endpoints must be non-empty." >&2
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
    validate_managed_external_anchor_trust \
        "RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR" "test-secret"
}

validate_external_anchor_domain_isolation() {
    if ! truthy "${RG_TEST_SECRET_AUTHORITY_COHORT_ENABLED:-false}" ||
        ! truthy "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_ENABLED:-false}"; then
        return 0
    fi
    local secret_scope="${RG_TEST_SECRET_AUTHORITY_COHORT_SCOPE_ID:-}"
    local suite_scope="${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_SCOPE_ID:-}"
    local secret_root_set="${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_TRUST_ROOT_SET_ID:-}"
    local suite_root_set="${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_TRUST_ROOT_SET_ID:-}"
    local secret_notary_domain="${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_TRUST_DOMAIN:-}"
    local suite_notary_domain="${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_TRUST_DOMAIN:-}"
    local secret_root_domain="${RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_BOOTSTRAP_TRUST_DOMAIN:-}"
    local suite_root_domain="${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_BOOTSTRAP_TRUST_DOMAIN:-}"
    if { [ "${secret_scope}" = "${suite_scope}" ] &&
        [ "${secret_root_set}" = "${suite_root_set}" ]; } ||
        [ "${secret_notary_domain}" = "${suite_notary_domain}" ] ||
        [ "${secret_notary_domain}" = "${suite_root_domain}" ] ||
        [ "${secret_root_domain}" = "${suite_notary_domain}" ] ||
        [ "${secret_root_domain}" = "${suite_root_domain}" ]; then
        echo "Test-secret and suite-stability external anchors must not share trust domains or root floors." >&2
        return 1
    fi
}

validate_recovery_fleet_managed_roots() {
    if ! truthy "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_ENABLED:-false}"; then
        return 0
    fi
    if ! truthy "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_ENABLED:-false}" ||
        ! truthy "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_REQUIRED:-true}" ||
        ! truthy "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOTS_ENABLED:-false}" ||
        ! truthy "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOTS_REQUIRED:-true}"; then
        echo "Staging recovery fleet requires dynamic witnessed inventory and managed dual trust roots." >&2
        return 1
    fi
    validate_control_plane_transport \
        "RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRANSPORT" \
        "inventory source" || return 1
    validate_control_plane_transport \
        "RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_TRANSPORT" \
        "trust-root source" || return 1
    local inventory_client_path="${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRANSPORT_CLIENT_KEY_STORE_PATH}"
    local inventory_client_ref="${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRANSPORT_CLIENT_KEY_STORE_PASSWORD_REF}"
    local root_client_path="${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_TRANSPORT_CLIENT_KEY_STORE_PATH}"
    local root_client_ref="${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_TRANSPORT_CLIENT_KEY_STORE_PASSWORD_REF}"
    if [ "${inventory_client_path}" = "${root_client_path}" ] &&
        [ "${inventory_client_ref}" = "${root_client_ref}" ]; then
        echo "Recovery-fleet inventory and trust-root sources require independent client identities." >&2
        return 1
    fi
    validate_recovery_fleet_external_anchor || return 1
    if [ -z "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_ID:-}" ] ||
        [ -z "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_WORKER_ID:-}" ] ||
        [ -z "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_DEPLOYMENT_SCOPE_ID:-}" ] ||
        [ -z "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_ARTIFACT_FINGERPRINT:-}" ] ||
        [ -z "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_ACCEPTED_POLICY_FINGERPRINTS:-}" ] ||
        [ -z "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_PUBLICATION_URI:-}" ] ||
        [ -z "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_URI:-}" ] ||
        [ -z "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_SET_ID:-}" ] ||
        [ -z "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_POLICY_FINGERPRINTS:-}" ] ||
        [ -z "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_DEPLOYMENT_ROOT_DOMAIN:-}" ] ||
        [ -z "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_DEPLOYMENT_ROOT_THRESHOLD:-}" ] ||
        [ -z "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_DEPLOYMENT_ROOT_KEYS_JSON:-}" ] ||
        [ -z "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_WITNESS_ROOT_DOMAIN:-}" ] ||
        [ -z "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_WITNESS_ROOT_THRESHOLD:-}" ] ||
        [ -z "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_WITNESS_ROOT_KEYS_JSON:-}" ]; then
        echo "Managed recovery-fleet inventory requires exact fleet binding, two sources, policies, and independent bootstrap roots." >&2
        return 1
    fi
    if [ "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_ARTIFACT_FINGERPRINT}" !=
        "${RG_RESOURCE_GATEWAY_ARTIFACT_FINGERPRINT}" ]; then
        echo "Recovery-fleet inventory artifact must match the local Resource Gateway artifact." >&2
        return 1
    fi
    if [ -n "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_DOMAIN:-}" ] ||
        [ "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_SIGNATURE_THRESHOLD:-0}" != "0" ] ||
        [ -n "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_WITNESS_DOMAIN:-}" ] ||
        [ "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_WITNESS_SIGNATURE_THRESHOLD:-0}" != "0" ] ||
        ! printf '%s' "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_AUTHORITY_KEYS_JSON:-[]}" |
        grep -Eq '^\[[[:space:]]*\]$' ||
        ! printf '%s' "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_WITNESS_AUTHORITY_KEYS_JSON:-[]}" |
        grep -Eq '^\[[[:space:]]*\]$'; then
        echo "Managed recovery-fleet trust roots forbid static runtime trust and keys." >&2
        return 1
    fi
    case "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_PUBLICATION_URI}" in
        https://*) ;;
        *) echo "Recovery-fleet inventory source must use HTTPS." >&2; return 1 ;;
    esac
    case "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_URI}" in
        https://*) ;;
        *) echo "Recovery-fleet trust-root source must use HTTPS." >&2; return 1 ;;
    esac
    if [ "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_PUBLICATION_URI}" =
        "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_URI}" ] ||
        truthy "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_ALLOW_INSECURE_LOOPBACK:-false}" ||
        truthy "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_ALLOW_INSECURE_LOOPBACK:-false}"; then
        echo "Staging recovery-fleet inventory and trust roots require distinct strict-HTTPS sources." >&2
        return 1
    fi
    if printf '%s\n%s\n%s\n%s\n' \
        "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_ID}" \
        "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_DEPLOYMENT_SCOPE_ID}" \
        "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_SET_ID}" \
        "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_DEPLOYMENT_ROOT_DOMAIN}" |
        grep -Eqv '^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}$' ||
        ! printf '%s' "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_WITNESS_ROOT_DOMAIN}" |
        grep -Eq '^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}$' ||
        [ "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_DEPLOYMENT_ROOT_DOMAIN}" =
        "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_WITNESS_ROOT_DOMAIN}" ]; then
        echo "Recovery-fleet identities and bootstrap-root domains must be valid and independent." >&2
        return 1
    fi
    if printf '%s\n%s\n' \
        "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_ACCEPTED_POLICY_FINGERPRINTS}" \
        "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_POLICY_FINGERPRINTS}" |
        grep -Eqv '^sha256:[a-f0-9]{64}(,sha256:[a-f0-9]{64}){0,31}$'; then
        echo "Recovery-fleet inventory and root policy fingerprints are invalid." >&2
        return 1
    fi
    local deployment_threshold="${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_DEPLOYMENT_ROOT_THRESHOLD}"
    local witness_threshold="${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_WITNESS_ROOT_THRESHOLD}"
    if printf '%s\n%s\n' "${deployment_threshold}" "${witness_threshold}" |
        grep -Eqv '^[1-9][0-9]*$' || [ "${deployment_threshold}" -gt 32 ] ||
        [ "${witness_threshold}" -gt 32 ]; then
        echo "Recovery-fleet bootstrap-root signature thresholds must be 1..32." >&2
        return 1
    fi
    local deployment_keys="${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_DEPLOYMENT_ROOT_KEYS_JSON}"
    local witness_keys="${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_WITNESS_ROOT_KEYS_JSON}"
    if printf '%s\n%s\n' "${deployment_keys}" "${witness_keys}" |
        grep -Eqv '^\[.+\]$' || printf '%s\n%s\n' "${deployment_keys}" "${witness_keys}" |
        grep -Eiq '"(privateKey|privateKeyBase64|secret|credential)"[[:space:]]*:'; then
        echo "Recovery-fleet bootstrap roots require non-empty public-only JSON key arrays." >&2
        return 1
    fi

    local inventory_refresh="${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_REFRESH_INTERVAL_SECONDS:-30}"
    local inventory_timeout="${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_REQUEST_TIMEOUT_MS:-3000}"
    local inventory_age="${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_MAXIMUM_SNAPSHOT_AGE_SECONDS:-60}"
    local root_refresh="${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_REFRESH_SECONDS:-30}"
    local root_timeout="${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_TIMEOUT_MS:-3000}"
    local root_unknown="${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_UNKNOWN_KEY_REFRESH_SECONDS:-5}"
    local root_age="${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_MAXIMUM_AGE_SECONDS:-60}"
    if printf '%s\n%s\n%s\n%s\n%s\n%s\n%s\n' \
        "${inventory_refresh}" "${inventory_timeout}" "${inventory_age}" \
        "${root_refresh}" "${root_timeout}" "${root_unknown}" "${root_age}" |
        grep -Eqv '^[1-9][0-9]*$' ||
        [ "${inventory_refresh}" -gt 3600 ] || [ "${inventory_timeout}" -lt 100 ] ||
        [ "${inventory_timeout}" -gt 30000 ] || [ "${inventory_age}" -gt 86400 ] ||
        [ $((inventory_age * 1000)) -lt $((inventory_refresh * 1000 + inventory_timeout)) ] ||
        [ "${root_refresh}" -gt 3600 ] || [ "${root_timeout}" -lt 100 ] ||
        [ "${root_timeout}" -gt 30000 ] || [ "${root_unknown}" -gt 3600 ] ||
        [ "${root_age}" -gt 86400 ] ||
        [ $((root_age * 1000)) -lt $((root_refresh * 1000 + root_timeout)) ]; then
        echo "Recovery-fleet inventory and root refresh timing bounds are invalid." >&2
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
    validate_bootstrap_root_publisher_transport
    validate_recovery_fleet_managed_roots
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
            [ -z "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_ENDPOINTS_JSON:-}" ]; then
            echo "External inventory anchoring requires trust, quorum, and endpoints." >&2
            return 1
        fi
        if truthy "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_ALLOW_INSECURE_LOOPBACK:-false}" ||
            truthy "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_TRUST_ALLOW_INSECURE_LOOPBACK:-false}"; then
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
        case "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_ENDPOINTS_JSON}" in
            \[*\]) ;;
            *)
                echo "External inventory anchor endpoints must be a JSON array." >&2
                return 1
                ;;
        esac
        if printf '%s' \
            "${RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_ENDPOINTS_JSON}" |
            grep -Eq '^\[[[:space:]]*\]$'; then
            echo "External inventory anchor endpoints must be non-empty." >&2
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
        validate_managed_external_anchor_trust \
            "RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR" \
            "suite-stability"

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
    validate_external_anchor_domain_isolation
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
