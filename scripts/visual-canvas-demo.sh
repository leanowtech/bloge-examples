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
STOP_TIMEOUT="${BLOGE_VISUAL_CANVAS_STOP_TIMEOUT:-40}"
BUILD_FRONTEND="${BLOGE_VISUAL_CANVAS_BUILD_FRONTEND:-1}"
SKIP_BUILD="${BLOGE_VISUAL_CANVAS_SKIP_BUILD:-0}"
RUN_TESTS="${BLOGE_VISUAL_CANVAS_RUN_TESTS:-0}"
OPEN_BROWSER="${BLOGE_VISUAL_CANVAS_OPEN:-0}"
JAVA_BIN="${JAVA_BIN:-java}"
SPRING_PROFILE="${BLOGE_VISUAL_CANVAS_PROFILE:-test}"
STATEFUL_MIRROR="${BLOGE_VISUAL_CANVAS_STATEFUL:-${RG_MIRROR_STATEFUL_ENABLED:-0}}"
SCENARIO_BATCH="${BLOGE_VISUAL_CANVAS_SCENARIO_BATCH:-${RG_MIRROR_SCENARIO_BATCH_SCHEDULER_ENABLED:-0}}"
SHADOW_JOBS="${BLOGE_VISUAL_CANVAS_SHADOW_JOBS:-0}"
SHADOW_SCHEDULER="${BLOGE_VISUAL_CANVAS_SHADOW_SCHEDULER:-${RG_MIRROR_SHADOW_JOB_SCHEDULER_ENABLED:-0}}"
SHADOW_DETACHED_DATA_PLANE="${BLOGE_VISUAL_CANVAS_SHADOW_DETACHED_DATA_PLANE:-0}"
OUTCOME_CONTINUOUS_ASSESSMENT="${BLOGE_VISUAL_CANVAS_OUTCOME_CONTINUOUS_ASSESSMENT:-${RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_SCHEDULER_ENABLED:-0}}"
CORRECTNESS_DEMO="${BLOGE_VISUAL_CANVAS_CORRECTNESS_DEMO:-1}"
CAPABILITY_STUDIO_DEMO="${BLOGE_VISUAL_CANVAS_CAPABILITY_STUDIO_DEMO:-1}"
CAPABILITY_STUDIO_REHEARSAL_TOKEN="${RG_INTEGRATION_DEMO_TOKEN:-bloge-aneke-demo-token}"
STATEFUL_KEY_FILE="${BLOGE_VISUAL_CANVAS_STATEFUL_KEY_FILE:-${ROOT_DIR}/target/example-state/mirror-aes256.key}"
RG_API_RESOURCE_AUTHORING_ENABLED="${RG_API_RESOURCE_AUTHORING_ENABLED:-true}"
RG_REUSABLE_FLOW_AUTHORING_ENABLED="${RG_REUSABLE_FLOW_AUTHORING_ENABLED:-true}"
RG_AUTHORING_LOCAL_SCHEMA_BOOTSTRAP_ENABLED="${RG_AUTHORING_LOCAL_SCHEMA_BOOTSTRAP_ENABLED:-true}"

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
  --no-build        Reuse an existing jar after validating the requested frontend mode.
  --api-only        Build without the React frontend profile.
  --run-tests       Run Maven tests during the package step.
  --stateful        Enable the encrypted stateful-mirror Session API for test/staging.
  --scenario-batch  Enable regional Scenario workers and isolated evidence finalizers.
  --shadow-jobs     Enable the durable read-only Shadow submit/read/lifecycle API.
  --shadow-scheduler  Also enable bounded Shadow polling; the default data plane remains unavailable.
  --shadow-detached-data-plane  Install exact detached source connectors and verifier; authorities still fail closed.
  --outcome-continuous-assessment  Enable selected-population freshness workers; customer authorities are still required.
  --correctness     Explicitly enable the read-only Correctness Studio sample (enabled by default).
  --no-correctness  Disable the Correctness Studio sample; required for production-profile demos.
  --capability-studio     Enable the Capability Studio golden pack (enabled by default).
  --no-capability-studio  Disable the Capability Studio golden pack; required for production.
  --open            Open Capability Studio when enabled; otherwise open the legacy workspace.
  -h, --help        Show this help.

Environment:
  BLOGE_VISUAL_CANVAS_PORT             default: 8080
  BLOGE_VISUAL_CANVAS_STARTUP_TIMEOUT  default: 180
  BLOGE_VISUAL_CANVAS_STOP_TIMEOUT     default: 40; bounded graceful shutdown wait (1..300 seconds)
  BLOGE_VISUAL_CANVAS_SKIP_BUILD       default: 0
  BLOGE_VISUAL_CANVAS_BUILD_FRONTEND   default: 1
  BLOGE_VISUAL_CANVAS_RUN_TESTS        default: 0
  BLOGE_VISUAL_CANVAS_OPEN             default: 0
  BLOGE_VISUAL_CANVAS_PROFILE          default: test
  BLOGE_VISUAL_CANVAS_STATEFUL         default: 0; same effect as --stateful
  BLOGE_VISUAL_CANVAS_SCENARIO_BATCH   default: 0; same effect as --scenario-batch
  BLOGE_VISUAL_CANVAS_SHADOW_JOBS      default: 0; same effect as --shadow-jobs
  BLOGE_VISUAL_CANVAS_SHADOW_SCHEDULER default: 0; same effect as --shadow-scheduler
  BLOGE_VISUAL_CANVAS_SHADOW_DETACHED_DATA_PLANE default: 0; same effect as --shadow-detached-data-plane
  BLOGE_VISUAL_CANVAS_OUTCOME_CONTINUOUS_ASSESSMENT default: 0; same effect as --outcome-continuous-assessment
  BLOGE_VISUAL_CANVAS_CORRECTNESS_DEMO default: 1; set to 0 or use --no-correctness to disable
  BLOGE_VISUAL_CANVAS_CAPABILITY_STUDIO_DEMO default: 1; use --no-capability-studio to disable
  BLOGE_VISUAL_CANVAS_STATEFUL_KEY_FILE  local demo AES-256 key file; never printed
  RG_API_RESOURCE_AUTHORING_ENABLED default: true
  RG_REUSABLE_FLOW_AUTHORING_ENABLED default: true
  RG_AUTHORING_LOCAL_SCHEMA_BOOTSTRAP_ENABLED default: true for the embedded H2 database
  Set either variable to false to disable that authoring surface.
  RG_MIRROR_SHADOW_JOB_INSTANCE_ID     stable local Shadow scheduler replica id
  RG_MIRROR_SHADOW_JOB_REGION          exact regional queue partition
  RG_MIRROR_SHADOW_JOB_ENVIRONMENT     exact enterprise non-production partition
  RG_MIRROR_SHADOW_JOB_MAXIMUM_POLLERS local bounded worker lanes (1..64)
  RG_MIRROR_SCENARIO_BATCH_INSTANCE_ID  stable local batch-worker replica id
  RG_MIRROR_SCENARIO_BATCH_REGION       exact regional queue partition
  RG_MIRROR_SCENARIO_BATCH_ENVIRONMENT  exact test or staging queue partition
  RG_MIRROR_SCENARIO_BATCH_MAXIMUM_POLLERS  local bounded worker lanes (1..256)
  RG_MIRROR_SCENARIO_BATCH_FINALIZATION_INSTANCE_ID  stable evidence-finalizer replica id
  RG_MIRROR_SCENARIO_BATCH_FINALIZATION_MAXIMUM_POLLERS  isolated KMS lanes (1..32)
  RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_SCHEDULER_ENABLED  opt-in selected-population freshness workers
  RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_INSTANCE_ID  stable continuous-assessment replica id
  RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_REGION  exact regional projection partition
  RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_ENVIRONMENT  exact test or staging partition
  RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_MAXIMUM_POLLERS  local bounded lanes (1..64)
  RG_MIRROR_STATEFUL_JDBC_URL          optional dedicated state-plane JDBC URL
  RG_MIRROR_STATEFUL_INSTANCE_ID       optional stable replica id
  RG_MIRROR_STATEFUL_ACTIVE_KEY_ID     optional active AES key id
  RG_MIRROR_STATEFUL_KEY_RING          optional keyId=base64AES256[,oldKeyId=...]
  RG_MIRROR_STATEFUL_MAXIMUM_ACTIVE_SESSIONS  global active-session limit
  RG_MIRROR_STATEFUL_MAXIMUM_SCOPE_ACTIVE_SESSIONS  exact-scope active-session limit
  RG_MIRROR_STATEFUL_MAXIMUM_RETAINED_PAYLOAD_BYTES  global canonical-payload-byte limit
  RG_MIRROR_STATEFUL_MAXIMUM_SCOPE_RETAINED_PAYLOAD_BYTES  scope canonical-byte limit
  RG_MIRROR_STATEFUL_MAXIMUM_CONCURRENT_COMMANDS  replica-local in-flight limit
  RG_MIRROR_STATEFUL_EXPIRY_BATCH_SIZE  oldest-first erasure page size (1..1000)
  RG_MIRROR_STATEFUL_EXPIRY_SWEEP_INTERVAL_MILLIS  erasure sweep delay
  RG_MIRROR_STATEFUL_WRITE_ATTEMPT_RECONCILIATION_BATCH_SIZE  stale-intent page size (1..1000)
  RG_MIRROR_STATEFUL_WRITE_ATTEMPT_RECONCILIATION_SWEEP_INTERVAL_MILLIS  stale-intent sweep delay
  RG_TEST_WORKER_QUARANTINE_TOKEN_ACTIVE_KEY_ID  required for staging
  RG_TEST_WORKER_QUARANTINE_TOKEN_KEY_RING       required for staging; keyId=base64AES256[,..]
  RG_TEST_WORKER_QUARANTINE_REQUEST_KEY_ACTIVE_KEY_ID  required for staging
  RG_TEST_WORKER_QUARANTINE_REQUEST_KEY_RING       required for staging; keyId=base64Key[,..]
  RG_TEST_WORKER_QUARANTINE_REQUEST_INDEX_WRITE_MODE  required for staging; staged rollout mode
  RG_RESOURCE_GATEWAY_INSTANCE_ID                  required for staging; exact serving replica id
  RG_RESOURCE_GATEWAY_ARTIFACT_FINGERPRINT         required for staging; sha256 image/JAR identity
  RG_RESOURCE_GATEWAY_SOURCE_COMMIT                optional immutable source commit; defaults to local Git HEAD
  RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_ENABLED  optional signed local TLS rotation preview
  RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_SCOPE_ID  required exact deployment scope
  RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_TRUST_DOMAIN  required external PKI governance domain
  RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_ACCEPTED_POLICIES  required sha256 policy list
  RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_SIGNATURE_THRESHOLD  required; 1..32
  RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_AUTHORITY_KEYS_JSON  required public Ed25519 keys
  RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_INITIAL_GENERATIONS_JSON  required target baseline generations/material ids
  RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_MATERIAL_CATALOG_JSON  required public material catalog
  RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_CONVERGENCE_ENABLED  optional all-replica activation/serving fence
  RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_FLEET_ID  required immutable rollout generation
  RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_INSTANCE_ID  required stable serving slot
  RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EXPECTED_INSTANCE_IDS  required exact comma-separated inventory
  RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_ENABLED  optional signed CA status admission
  RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_ENDPOINT_URI  required HTTPS normalized-status endpoint
  RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_BASELINE_PUBLICATION_FINGERPRINT  required pinned status cursor
  RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_TRANSPORT_CLIENT_KEY_STORE_PATH  required independent client identity
  RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SLO_*  optional bounded alert thresholds
  RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_ENABLED  optional authenticated CA event delivery
  RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_ENDPOINT_URI  required HTTPS page endpoint
  RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_BASELINE_PAGE_FINGERPRINT  required pinned chain head
  RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_TRANSPORT_CLIENT_KEY_STORE_PATH  required independent client identity
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
  Every enabled staging control-plane transport also requires these suffixes:
    _EXPECTED_CLIENT_SUBJECT_DN  exact RFC 2253 client Subject DN
    _EXPECTED_CLIENT_URI_SAN  exact absolute client workload URI SAN
    _CLIENT_ISSUER_SPKI_PINS  canonical sha256 pins for admitted client issuers
    _EXPECTED_SERVER_URI_SAN  exact absolute server workload URI SAN
    _SERVER_ISSUER_SPKI_PINS  canonical sha256 pins for admitted server issuers
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
  <EXTERNAL_ANCHOR_PREFIX>_TRANSPORT_ENABLED  required true for staging notary calls
  <EXTERNAL_ANCHOR_PREFIX>_TRUST_TRANSPORT_ENABLED  required true for managed trust reads
  <EXTERNAL_ANCHOR_PREFIX>_BOOTSTRAP_ROOT_TRANSPORT_ENABLED  required true for root-bundle reads
  Each external-anchor transport also requires REQUIRED=true, a dedicated absolute client
  PKCS#12 path, an env:VARIABLE password reference, canonical sha256 SPKI pins, and an optional
  complete private trust-store path/password-reference pair. Client identities must not be reused
  across publisher, inventory, trust-root, notary, managed-trust, or root-bundle sources.
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
  scripts/start-visual-canvas-demo.sh --stateful
  scripts/start-visual-canvas-demo.sh --scenario-batch
  scripts/start-visual-canvas-demo.sh --shadow-jobs
  scripts/start-visual-canvas-demo.sh --shadow-scheduler
  scripts/start-visual-canvas-demo.sh --shadow-detached-data-plane
  scripts/start-visual-canvas-demo.sh --outcome-continuous-assessment
  scripts/start-visual-canvas-demo.sh --no-capability-studio --no-correctness --open
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

configure_shadow_jobs() {
    if truthy "${SHADOW_SCHEDULER}" ||
        truthy "${SHADOW_DETACHED_DATA_PLANE}"; then
        SHADOW_JOBS=1
    fi
    if ! truthy "${SHADOW_JOBS}"; then
        return 0
    fi
    local default_environment
    case ",${SPRING_PROFILE}," in
        *,production,*)
            echo "Read-only Shadow jobs are physically unavailable in the production profile." >&2
            return 1
            ;;
        *,staging,*)
            default_environment="staging"
            ;;
        *,test,*)
            default_environment="test"
            ;;
        *)
            echo "Read-only Shadow jobs require the test or staging profile." >&2
            return 1
            ;;
    esac

    export RG_MIRROR_RUNTIME_ENABLED=true
    export RG_INTEGRATION_REGION="${RG_INTEGRATION_REGION:-sg}"
    export RG_INTEGRATION_ENVIRONMENT_ID="${RG_INTEGRATION_ENVIRONMENT_ID:-${default_environment}}"
    case "${RG_INTEGRATION_ENVIRONMENT_ID}" in
        prod|production|live)
            echo "Read-only Shadow jobs cannot use a reserved production identity environment." >&2
            return 1
            ;;
    esac
    if truthy "${SHADOW_DETACHED_DATA_PLANE}"; then
        APP_ARGS+=(
            "--gateway.testing.mirror.read-only-shadow.detached-data-plane.enabled=true")
    fi
    if ! truthy "${SHADOW_SCHEDULER}"; then
        return 0
    fi
    export RG_MIRROR_SHADOW_JOB_SCHEDULER_ENABLED=true
    export RG_MIRROR_SHADOW_JOB_INSTANCE_ID="${RG_MIRROR_SHADOW_JOB_INSTANCE_ID:-visual-canvas-shadow-$(configured_port)}"
    export RG_MIRROR_SHADOW_JOB_REGION="${RG_MIRROR_SHADOW_JOB_REGION:-${RG_INTEGRATION_REGION}}"
    export RG_MIRROR_SHADOW_JOB_ENVIRONMENT="${RG_MIRROR_SHADOW_JOB_ENVIRONMENT:-${RG_INTEGRATION_ENVIRONMENT_ID}}"
    if [ -n "${RG_INTEGRATION_REGION:-}" ] &&
        [ "${RG_INTEGRATION_REGION}" != "${RG_MIRROR_SHADOW_JOB_REGION}" ]; then
        echo "Shadow scheduler region must match the integration identity region." >&2
        return 1
    fi
    if [ -n "${RG_INTEGRATION_ENVIRONMENT_ID:-}" ] &&
        [ "${RG_INTEGRATION_ENVIRONMENT_ID}" != "${RG_MIRROR_SHADOW_JOB_ENVIRONMENT}" ]; then
        echo "Shadow scheduler environment must match the integration identity environment." >&2
        return 1
    fi
    export RG_INTEGRATION_REGION="${RG_MIRROR_SHADOW_JOB_REGION}"
    export RG_INTEGRATION_ENVIRONMENT_ID="${RG_MIRROR_SHADOW_JOB_ENVIRONMENT}"
}

validate_shadow_jobs() {
    if ! truthy "${SHADOW_JOBS}"; then
        return 0
    fi
    if ! truthy "${RG_MIRROR_RUNTIME_ENABLED:-false}"; then
        echo "Read-only Shadow jobs require RG_MIRROR_RUNTIME_ENABLED=true." >&2
        return 1
    fi
    if ! truthy "${RG_MIRROR_SHADOW_JOB_SCHEDULER_ENABLED:-false}"; then
        return 0
    fi
    if ! printf '%s' "${RG_MIRROR_SHADOW_JOB_INSTANCE_ID:-}" |
        grep -Eq '^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}$' ||
        ! printf '%s' "${RG_MIRROR_SHADOW_JOB_REGION:-}" |
        grep -Eq '^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,95}$' ||
        ! printf '%s' "${RG_MIRROR_SHADOW_JOB_ENVIRONMENT:-}" |
        grep -Eq '^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}$'; then
        echo "Shadow scheduler instance or partition identity is invalid." >&2
        return 1
    fi
    case "${RG_MIRROR_SHADOW_JOB_ENVIRONMENT}" in
        prod|production|live)
            echo "Shadow scheduler cannot target a reserved production environment." >&2
            return 1
            ;;
    esac
    if [ "${RG_INTEGRATION_REGION:-}" != "${RG_MIRROR_SHADOW_JOB_REGION}" ] ||
        [ "${RG_INTEGRATION_ENVIRONMENT_ID:-}" != "${RG_MIRROR_SHADOW_JOB_ENVIRONMENT}" ]; then
        echo "Shadow scheduler partition must match the integration identity scope." >&2
        return 1
    fi
    local pollers="${RG_MIRROR_SHADOW_JOB_MAXIMUM_POLLERS:-2}"
    if ! printf '%s' "${pollers}" | grep -Eq '^[1-9][0-9]*$' ||
        [ "${pollers}" -gt 64 ]; then
        echo "Shadow scheduler maximum pollers must be between 1 and 64." >&2
        return 1
    fi
}

configure_scenario_batch() {
    if ! truthy "${SCENARIO_BATCH}"; then
        return 0
    fi
    local default_environment
    case ",${SPRING_PROFILE}," in
        *,production,*)
            echo "Scenario batch scheduling is physically unavailable in the production profile." >&2
            return 1
            ;;
        *,staging,*)
            default_environment="staging"
            ;;
        *,test,*)
            default_environment="test"
            ;;
        *)
            echo "Scenario batch scheduling requires the test or staging profile." >&2
            return 1
            ;;
    esac

    export RG_MIRROR_RUNTIME_ENABLED=true
    export RG_MIRROR_SCENARIO_BATCH_SCHEDULER_ENABLED=true
    export RG_MIRROR_SCENARIO_BATCH_INSTANCE_ID="${RG_MIRROR_SCENARIO_BATCH_INSTANCE_ID:-visual-canvas-batch-$(configured_port)}"
    export RG_MIRROR_SCENARIO_BATCH_REGION="${RG_MIRROR_SCENARIO_BATCH_REGION:-${RG_INTEGRATION_REGION:-sg}}"
    export RG_MIRROR_SCENARIO_BATCH_ENVIRONMENT="${RG_MIRROR_SCENARIO_BATCH_ENVIRONMENT:-${default_environment}}"
    export RG_MIRROR_SCENARIO_BATCH_FINALIZATION_SCHEDULER_ENABLED=true
    export RG_MIRROR_SCENARIO_BATCH_FINALIZATION_INSTANCE_ID="${RG_MIRROR_SCENARIO_BATCH_FINALIZATION_INSTANCE_ID:-${RG_MIRROR_SCENARIO_BATCH_INSTANCE_ID}-finalizer}"
    export RG_MIRROR_SCENARIO_BATCH_FINALIZATION_REGION="${RG_MIRROR_SCENARIO_BATCH_FINALIZATION_REGION:-${RG_MIRROR_SCENARIO_BATCH_REGION}}"
    export RG_MIRROR_SCENARIO_BATCH_FINALIZATION_ENVIRONMENT="${RG_MIRROR_SCENARIO_BATCH_FINALIZATION_ENVIRONMENT:-${RG_MIRROR_SCENARIO_BATCH_ENVIRONMENT}}"
    if [ -n "${RG_INTEGRATION_REGION:-}" ] &&
        [ "${RG_INTEGRATION_REGION}" != "${RG_MIRROR_SCENARIO_BATCH_REGION}" ]; then
        echo "Scenario batch region must match the integration identity region." >&2
        return 1
    fi
    if [ -n "${RG_INTEGRATION_ENVIRONMENT_ID:-}" ] &&
        [ "${RG_INTEGRATION_ENVIRONMENT_ID}" != "${RG_MIRROR_SCENARIO_BATCH_ENVIRONMENT}" ]; then
        echo "Scenario batch environment must match the integration identity environment." >&2
        return 1
    fi
    export RG_INTEGRATION_REGION="${RG_MIRROR_SCENARIO_BATCH_REGION}"
    export RG_INTEGRATION_ENVIRONMENT_ID="${RG_MIRROR_SCENARIO_BATCH_ENVIRONMENT}"
}

validate_scenario_batch() {
    if ! truthy "${RG_MIRROR_SCENARIO_BATCH_SCHEDULER_ENABLED:-false}"; then
        return 0
    fi
    if ! truthy "${RG_MIRROR_RUNTIME_ENABLED:-false}"; then
        echo "Scenario batch scheduling requires RG_MIRROR_RUNTIME_ENABLED=true." >&2
        return 1
    fi
    if ! printf '%s' "${RG_MIRROR_SCENARIO_BATCH_INSTANCE_ID:-}" |
        grep -Eq '^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}$' ||
        ! printf '%s' "${RG_MIRROR_SCENARIO_BATCH_REGION:-}" |
        grep -Eq '^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,63}$'; then
        echo "Scenario batch instance or region identity is invalid." >&2
        return 1
    fi
    case "${RG_MIRROR_SCENARIO_BATCH_ENVIRONMENT:-}" in
        test|staging) ;;
        *)
            echo "Scenario batch environment must be test or staging." >&2
            return 1
            ;;
    esac
    if [ "${RG_INTEGRATION_REGION:-}" != "${RG_MIRROR_SCENARIO_BATCH_REGION}" ] ||
        [ "${RG_INTEGRATION_ENVIRONMENT_ID:-}" != "${RG_MIRROR_SCENARIO_BATCH_ENVIRONMENT}" ]; then
        echo "Scenario batch partition must match the integration identity scope." >&2
        return 1
    fi
    local pollers="${RG_MIRROR_SCENARIO_BATCH_MAXIMUM_POLLERS:-4}"
    if ! printf '%s' "${pollers}" | grep -Eq '^[1-9][0-9]*$' ||
        [ "${pollers}" -gt 256 ]; then
        echo "Scenario batch maximum pollers must be between 1 and 256." >&2
        return 1
    fi
    if ! truthy "${RG_MIRROR_SCENARIO_BATCH_FINALIZATION_SCHEDULER_ENABLED:-false}" ||
        ! printf '%s' "${RG_MIRROR_SCENARIO_BATCH_FINALIZATION_INSTANCE_ID:-}" |
        grep -Eq '^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}$' ||
        [ "${RG_MIRROR_SCENARIO_BATCH_FINALIZATION_REGION:-}" != "${RG_MIRROR_SCENARIO_BATCH_REGION}" ] ||
        [ "${RG_MIRROR_SCENARIO_BATCH_FINALIZATION_ENVIRONMENT:-}" != "${RG_MIRROR_SCENARIO_BATCH_ENVIRONMENT}" ]; then
        echo "Scenario batch finalizer must be enabled for the exact worker partition." >&2
        return 1
    fi
    local finalization_pollers="${RG_MIRROR_SCENARIO_BATCH_FINALIZATION_MAXIMUM_POLLERS:-1}"
    if ! printf '%s' "${finalization_pollers}" |
        grep -Eq '^[1-9][0-9]*$' ||
        [ "${finalization_pollers}" -gt 32 ]; then
        echo "Scenario batch finalization pollers must be between 1 and 32." >&2
        return 1
    fi
}

configure_outcome_continuous_assessment() {
    if ! truthy "${OUTCOME_CONTINUOUS_ASSESSMENT}"; then
        return 0
    fi
    local default_environment
    case ",${SPRING_PROFILE}," in
        *,production,*)
            echo "Continuous outcome assessment is physically unavailable in the production profile." >&2
            return 1
            ;;
        *,staging,*)
            default_environment="staging"
            ;;
        *,test,*)
            default_environment="test"
            ;;
        *)
            echo "Continuous outcome assessment requires the test or staging profile." >&2
            return 1
            ;;
    esac

    export RG_MIRROR_RUNTIME_ENABLED=true
    export RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_SCHEDULER_ENABLED=true
    export RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_INSTANCE_ID="${RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_INSTANCE_ID:-visual-canvas-outcome-$(configured_port)}"
    export RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_REGION="${RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_REGION:-${RG_INTEGRATION_REGION:-sg}}"
    export RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_ENVIRONMENT="${RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_ENVIRONMENT:-${RG_INTEGRATION_ENVIRONMENT_ID:-${default_environment}}}"
    if [ -n "${RG_INTEGRATION_REGION:-}" ] &&
        [ "${RG_INTEGRATION_REGION}" != "${RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_REGION}" ]; then
        echo "Continuous outcome assessment region must match the integration identity region." >&2
        return 1
    fi
    if [ -n "${RG_INTEGRATION_ENVIRONMENT_ID:-}" ] &&
        [ "${RG_INTEGRATION_ENVIRONMENT_ID}" != "${RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_ENVIRONMENT}" ]; then
        echo "Continuous outcome assessment environment must match the integration identity environment." >&2
        return 1
    fi
    export RG_INTEGRATION_REGION="${RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_REGION}"
    export RG_INTEGRATION_ENVIRONMENT_ID="${RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_ENVIRONMENT}"
}

validate_outcome_continuous_assessment() {
    if ! truthy "${RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_SCHEDULER_ENABLED:-false}"; then
        return 0
    fi
    if ! truthy "${RG_MIRROR_RUNTIME_ENABLED:-false}"; then
        echo "Continuous outcome assessment requires RG_MIRROR_RUNTIME_ENABLED=true." >&2
        return 1
    fi
    if ! printf '%s' "${RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_INSTANCE_ID:-}" |
        grep -Eq '^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}$' ||
        ! printf '%s' "${RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_REGION:-}" |
        grep -Eq '^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,95}$' ||
        ! printf '%s' "${RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_ENVIRONMENT:-}" |
        grep -Eq '^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}$'; then
        echo "Continuous outcome assessment instance or partition identity is invalid." >&2
        return 1
    fi
    case "${RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_ENVIRONMENT}" in
        test|staging) ;;
        *)
            echo "Continuous outcome assessment environment must be test or staging." >&2
            return 1
            ;;
    esac
    if [ "${RG_INTEGRATION_REGION:-}" != "${RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_REGION}" ] ||
        [ "${RG_INTEGRATION_ENVIRONMENT_ID:-}" != "${RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_ENVIRONMENT}" ]; then
        echo "Continuous outcome assessment partition must match the integration identity scope." >&2
        return 1
    fi
    local pollers="${RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_MAXIMUM_POLLERS:-2}"
    if ! printf '%s' "${pollers}" | grep -Eq '^[1-9][0-9]*$' ||
        [ "${pollers}" -gt 64 ]; then
        echo "Continuous outcome assessment maximum pollers must be between 1 and 64." >&2
        return 1
    fi
}

configure_stateful_mirror() {
    if ! truthy "${STATEFUL_MIRROR}"; then
        return 0
    fi
    case ",${SPRING_PROFILE}," in
        *,production,*)
            echo "Stateful mirror is physically unavailable in the production profile." >&2
            return 1
            ;;
        *,test,*|*,staging,*) ;;
        *)
            echo "Stateful mirror requires the test or staging profile." >&2
            return 1
            ;;
    esac

    export RG_MIRROR_RUNTIME_ENABLED=true
    export RG_MIRROR_STATEFUL_ENABLED=true
    export RG_MIRROR_STATEFUL_INSTANCE_ID="${RG_MIRROR_STATEFUL_INSTANCE_ID:-visual-canvas-$(configured_port)}"
    export RG_MIRROR_STATEFUL_ACTIVE_KEY_ID="${RG_MIRROR_STATEFUL_ACTIVE_KEY_ID:-demo-v1}"

    if [ -z "${RG_MIRROR_STATEFUL_KEY_RING:-}" ]; then
        local key_file="${STATEFUL_KEY_FILE}"
        local key_material
        if [ -L "${key_file}" ]; then
            echo "Refusing to use a symbolic link as the stateful demo key file." >&2
            return 1
        fi
        if [ ! -f "${key_file}" ]; then
            local key_directory
            local temporary_key
            key_directory="$(dirname "${key_file}")"
            mkdir -p "${key_directory}"
            temporary_key="$(mktemp "${key_file}.tmp.XXXXXX")"
            chmod 600 "${temporary_key}"
            if command -v openssl >/dev/null 2>&1; then
                openssl rand -base64 32 | tr -d '\r\n' > "${temporary_key}"
            else
                dd if=/dev/urandom bs=32 count=1 2>/dev/null |
                    base64 | tr -d '\r\n' > "${temporary_key}"
            fi
            mv "${temporary_key}" "${key_file}"
            chmod 600 "${key_file}"
            echo "Created persistent local stateful-mirror demo key: ${key_file}"
        fi
        if [ ! -r "${key_file}" ]; then
            echo "Stateful mirror demo key is not readable: ${key_file}" >&2
            return 1
        fi
        key_material="$(tr -d '\r\n' < "${key_file}")"
        if [ -z "${key_material}" ]; then
            echo "Stateful mirror demo key is empty: ${key_file}" >&2
            return 1
        fi
        export RG_MIRROR_STATEFUL_KEY_RING="${RG_MIRROR_STATEFUL_ACTIVE_KEY_ID}=${key_material}"
    fi
}

validate_stateful_mirror() {
    if ! truthy "${RG_MIRROR_STATEFUL_ENABLED:-false}"; then
        return 0
    fi
    if ! truthy "${RG_MIRROR_RUNTIME_ENABLED:-false}"; then
        echo "Stateful mirror requires RG_MIRROR_RUNTIME_ENABLED=true." >&2
        return 1
    fi
    if [ -z "${RG_MIRROR_STATEFUL_ACTIVE_KEY_ID:-}" ] ||
        [ -z "${RG_MIRROR_STATEFUL_KEY_RING:-}" ]; then
        echo "Stateful mirror requires an active AES-256 key id and key ring." >&2
        return 1
    fi
    if ! printf '%s' "${RG_MIRROR_STATEFUL_ACTIVE_KEY_ID}" |
        grep -Eq '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'; then
        echo "Stateful mirror active key id is invalid." >&2
        return 1
    fi
    local active_entry_found=0
    local entry
    local -a key_entries
    IFS=',' read -r -a key_entries <<< "${RG_MIRROR_STATEFUL_KEY_RING}"
    for entry in "${key_entries[@]}"; do
        if [[ "${entry}" == "${RG_MIRROR_STATEFUL_ACTIVE_KEY_ID}="* ]] &&
            [ "${#entry}" -gt $((${#RG_MIRROR_STATEFUL_ACTIVE_KEY_ID} + 1)) ]; then
            active_entry_found=1
            break
        fi
    done
    if [ "${active_entry_found}" -ne 1 ]; then
        echo "Stateful mirror key ring does not contain the active key id." >&2
        return 1
    fi
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
    validate_control_plane_transport "${prefix}_TRANSPORT" \
        "${label} external notary" || return 1
    validate_control_plane_transport "${prefix}_TRUST_TRANSPORT" \
        "${label} managed-trust source" || return 1
    validate_control_plane_transport "${prefix}_BOOTSTRAP_ROOT_TRANSPORT" \
        "${label} bootstrap-root source" || return 1
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
    local client_subject_var="${prefix}_EXPECTED_CLIENT_SUBJECT_DN"
    local client_uri_var="${prefix}_EXPECTED_CLIENT_URI_SAN"
    local client_issuer_pins_var="${prefix}_CLIENT_ISSUER_SPKI_PINS"
    local server_uri_var="${prefix}_EXPECTED_SERVER_URI_SAN"
    local server_issuer_pins_var="${prefix}_SERVER_ISSUER_SPKI_PINS"

    if ! truthy "${!enabled_var:-false}" || ! truthy "${!required_var:-true}"; then
        echo "Staging ${label} requires pinned mutual TLS." >&2
        return 1
    fi
    if [ -z "${!client_subject_var:-}" ] || [ -z "${!client_uri_var:-}" ] ||
        [ -z "${!client_issuer_pins_var:-}" ] || [ -z "${!server_uri_var:-}" ] ||
        [ -z "${!server_issuer_pins_var:-}" ]; then
        echo "${label} transport requires exact client and server certificate workload identities." >&2
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
        *) echo "${label} client key store must use an absolute path." >&2; return 1 ;;
    esac
    if [ ! -f "${!client_path_var}" ] || [ ! -r "${!client_path_var}" ]; then
        echo "${label} client key store is not readable." >&2
        return 1
    fi
    if [ -n "${!trust_path_var:-}" ]; then
        case "${!trust_path_var}" in
            /*) ;;
            *) echo "${label} trust store must use an absolute path." >&2; return 1 ;;
        esac
        if [ ! -f "${!trust_path_var}" ] || [ ! -r "${!trust_path_var}" ]; then
            echo "${label} trust store is not readable." >&2
            return 1
        fi
    fi
    local client_ref="${!client_ref_var}"
    if ! printf '%s' "${client_ref}" | grep -Eq '^env:[A-Z][A-Z0-9_]{0,127}$'; then
        echo "${label} client credential must be an env:VARIABLE reference." >&2
        return 1
    fi
    local client_secret="${client_ref#env:}"
    if [ -z "${!client_secret:-}" ]; then
        echo "${label} client credential is unavailable." >&2
        return 1
    fi
    if [ -n "${!trust_ref_var:-}" ]; then
        local trust_ref="${!trust_ref_var}"
        if ! printf '%s' "${trust_ref}" | grep -Eq '^env:[A-Z][A-Z0-9_]{0,127}$'; then
            echo "${label} trust-store credential must be an env:VARIABLE reference." >&2
            return 1
        fi
        local trust_secret="${trust_ref#env:}"
        if [ -z "${!trust_secret:-}" ]; then
            echo "${label} trust-store credential is unavailable." >&2
            return 1
        fi
    fi
    if ! printf '%s' "${!pins_var}" |
        grep -Eq '^sha256:[a-f0-9]{64}(,sha256:[a-f0-9]{64}){0,15}$'; then
        echo "${label} SPKI pins are invalid." >&2
        return 1
    fi
    if printf '%s\n%s\n' "${!client_uri_var}" "${!server_uri_var}" |
        grep -Eqv '^[a-z][a-z0-9+.-]{1,31}:[^[:space:]]+$'; then
        echo "${label} workload URI SANs are invalid." >&2
        return 1
    fi
    if printf '%s\n%s\n' "${!client_issuer_pins_var}" \
        "${!server_issuer_pins_var}" |
        grep -Eqv '^sha256:[a-f0-9]{64}(,sha256:[a-f0-9]{64}){0,15}$'; then
        echo "${label} issuer SPKI pins are invalid." >&2
        return 1
    fi
}

validate_control_plane_certificate_rotation() {
    if ! truthy "${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_ENABLED:-false}"; then
        return 0
    fi
    if ! truthy "${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_REQUIRED:-true}" ||
        [ -z "${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_SCOPE_ID:-}" ] ||
        [ -z "${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_TRUST_DOMAIN:-}" ] ||
        [ -z "${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_ACCEPTED_POLICIES:-}" ] ||
        [ -z "${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_SIGNATURE_THRESHOLD:-}" ] ||
        [ -z "${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_AUTHORITY_KEYS_JSON:-}" ] ||
        [ -z "${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_INITIAL_GENERATIONS_JSON:-}" ] ||
        [ -z "${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_MATERIAL_CATALOG_JSON:-}" ]; then
        echo "Signed certificate rotation requires complete scope, trust, generation, and material configuration." >&2
        return 1
    fi
    if printf '%s\n%s\n' \
        "${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_SCOPE_ID}" \
        "${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_TRUST_DOMAIN}" |
        grep -Eqv '^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}$' ||
        ! printf '%s' "${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_ACCEPTED_POLICIES}" |
        grep -Eq '^sha256:[a-f0-9]{64}(,sha256:[a-f0-9]{64}){0,31}$'; then
        echo "Signed certificate rotation scope, trust domain, or policy fingerprints are invalid." >&2
        return 1
    fi
    local threshold="${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_SIGNATURE_THRESHOLD}"
    local overlap="${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_MINIMUM_OVERLAP_SECONDS:-300}"
    local lead="${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_MAXIMUM_LEAD_TIME_SECONDS:-86400}"
    if printf '%s\n%s\n%s\n' "${threshold}" "${overlap}" "${lead}" |
        grep -Eqv '^(0|[1-9][0-9]*)$' ||
        [ "${threshold}" -lt 1 ] || [ "${threshold}" -gt 32 ] ||
        [ "${overlap}" -gt 2592000 ] ||
        [ "${lead}" -lt 1 ] || [ "${lead}" -gt 2592000 ]; then
        echo "Signed certificate rotation quorum or timing bounds are invalid." >&2
        return 1
    fi
    local authorities="${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_AUTHORITY_KEYS_JSON}"
    local generations="${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_INITIAL_GENERATIONS_JSON}"
    local catalog="${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_MATERIAL_CATALOG_JSON}"
    if ! printf '%s' "${authorities}" | grep -Eq '^\[[[:space:]]*\{.*\}[[:space:]]*\]$' ||
        ! printf '%s' "${generations}" | grep -Eq '^\{[[:space:]]*".*":[[:space:]]*([1-9][0-9]*|\{[[:space:]]*"generation"[[:space:]]*:[[:space:]]*[1-9][0-9]*[[:space:]]*,[[:space:]]*"materialId"[[:space:]]*:[[:space:]]*"[A-Za-z0-9][A-Za-z0-9._-]{0,254}"[[:space:]]*\}).*\}$' ||
        ! printf '%s' "${catalog}" | grep -Eq '^\[[[:space:]]*\{.*\}[[:space:]]*\]$'; then
        echo "Signed certificate rotation authority, generation, or material JSON is invalid." >&2
        return 1
    fi
    if printf '%s\n%s\n' "${authorities}" "${catalog}" |
        grep -Eqi '"(privateKey|privateKeyBase64|password)"[[:space:]]*:'; then
        echo "Signed certificate rotation configuration must not contain private keys or resolved passwords." >&2
        return 1
    fi
}

validate_control_plane_certificate_rotation_convergence() {
    if ! truthy "${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_CONVERGENCE_ENABLED:-false}"; then
        if truthy "${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_CONVERGENCE_REQUIRED:-false}"; then
            echo "Required certificate rotation convergence cannot be disabled." >&2
            return 1
        fi
        return 0
    fi
    if ! truthy "${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_ENABLED:-false}"; then
        echo "Certificate rotation convergence requires the signed rotation runtime." >&2
        return 1
    fi
    local fleet_id="${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_FLEET_ID:-}"
    local instance_id="${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_INSTANCE_ID:-}"
    local startup_id="${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_STARTUP_ID:-}"
    local artifact="${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_ARTIFACT_FINGERPRINT:-}"
    local instances="${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EXPECTED_INSTANCE_IDS:-}"
    local mode="${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_ACTIVATION_MODE:-ALL_REPLICAS}"
    local threshold="${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_REQUIRED_STAGED_REPLICAS:-0}"
    if printf '%s\n%s\n' "${fleet_id}" "${instance_id}" |
        grep -Eqv '^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}$' ||
        ! printf '%s' "${startup_id}" |
        grep -Eq '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' ||
        ! printf '%s' "${artifact}" | grep -Eq '^sha256:[a-f0-9]{64}$' ||
        ! printf '%s' "${instances}" |
        grep -Eq '^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}(,[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}){0,63}$' ||
        [ "${mode}" != "ALL_REPLICAS" ] ||
        ! printf '%s' "${threshold}" | grep -Eq '^[1-9][0-9]*$'; then
        echo "Certificate rotation convergence identity, inventory, or activation mode is invalid." >&2
        return 1
    fi
    local count=0
    local seen=","
    local member
    local local_present=false
    local -a members
    IFS=',' read -r -a members <<< "${instances}"
    for member in "${members[@]}"; do
        if [[ "${seen}" == *",${member},"* ]]; then
            echo "Certificate rotation convergence inventory contains a duplicate slot." >&2
            return 1
        fi
        seen="${seen}${member},"
        count=$((count + 1))
        if [ "${member}" = "${instance_id}" ]; then
            local_present=true
        fi
    done
    if [ "${local_present}" != true ] || [ "${threshold}" -ne "${count}" ]; then
        echo "Certificate rotation convergence requires the local slot and an all-replica threshold." >&2
        return 1
    fi
    local heartbeat="${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_HEARTBEAT_SECONDS:-5}"
    local lease="${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_LEASE_SECONDS:-15}"
    local retention="${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_RECORD_RETENTION_SECONDS:-3600}"
    if printf '%s\n%s\n%s\n' "${heartbeat}" "${lease}" "${retention}" |
        grep -Eqv '^[1-9][0-9]*$' ||
        [ "${heartbeat}" -gt 300 ] || [ "${lease}" -lt 3 ] || [ "${lease}" -gt 900 ] ||
        [ "${lease}" -lt $((heartbeat * 3)) ] || [ "${retention}" -lt 3600 ] ||
        [ "${retention}" -gt 2592000 ] || [ "${retention}" -lt "${lease}" ]; then
        echo "Certificate rotation convergence heartbeat, lease, or retention bounds are invalid." >&2
        return 1
    fi
    local inventory_type="${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_INVENTORY_SOURCE_TYPE:-LOCAL_CONFIGURED}"
    if [ "${inventory_type}" = "LOCAL_CONFIGURED" ]; then
        if [ "${count}" -ne 1 ]; then
            echo "Multi-replica certificate rotation convergence requires external inventory attestation." >&2
            return 1
        fi
        return 0
    fi
    local revision="${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_INVENTORY_REVISION:-0}"
    local material="${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_INVENTORY_MATERIAL_FINGERPRINT:-}"
    local policy="${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_INVENTORY_POLICY_FINGERPRINT:-}"
    local expiry="${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_INVENTORY_EXPIRES_AT:-}"
    if ! printf '%s' "${inventory_type}" |
        grep -Eq '^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}$' ||
        ! printf '%s' "${revision}" | grep -Eq '^[1-9][0-9]*$' ||
        printf '%s\n%s\n' "${material}" "${policy}" |
        grep -Eqv '^sha256:[a-f0-9]{64}$' ||
        ! printf '%s' "${expiry}" |
        grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.+-]+Z$'; then
        echo "External certificate rotation inventory attestation is incomplete." >&2
        return 1
    fi
}

validate_control_plane_certificate_rotation_event_source() {
    if ! truthy "${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_ENABLED:-false}"; then
        if truthy "${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_REQUIRED:-false}"; then
            echo "Required certificate rotation event source cannot be disabled." >&2
            return 1
        fi
        return 0
    fi
    if ! truthy "${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_REQUIRED:-true}" ||
        ! truthy "${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_ENABLED:-false}" ||
        ! truthy "${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_REQUIRED:-false}" ||
        ! truthy "${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_CONVERGENCE_ENABLED:-false}" ||
        ! truthy "${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_CONVERGENCE_REQUIRED:-false}"; then
        echo "Certificate rotation event delivery requires required signed rotation and all-replica convergence." >&2
        return 1
    fi
    local endpoint="${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_ENDPOINT_URI:-}"
    local baseline_sequence="${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_BASELINE_SEQUENCE:-0}"
    local baseline_fingerprint="${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_BASELINE_PAGE_FINGERPRINT:-}"
    if ! printf '%s' "${endpoint}" |
        grep -Eq '^https://[^/?#[:space:]]+(/[^?#[:space:]]*)?$' ||
        truthy "${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_ALLOW_INSECURE_LOOPBACK:-false}" ||
        ! printf '%s' "${baseline_sequence}" | grep -Eq '^(0|[1-9][0-9]*)$' ||
        ! printf '%s' "${baseline_fingerprint}" |
        grep -Eq '^sha256:[a-f0-9]{64}$'; then
        echo "Staging certificate rotation event source requires HTTPS and an exact page-chain baseline." >&2
        return 1
    fi
    local poll="${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_POLL_INTERVAL_SECONDS:-5}"
    local pages="${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_MAXIMUM_PAGES_PER_POLL:-4}"
    local timeout="${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_REQUEST_TIMEOUT_MILLIS:-3000}"
    local bytes="${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_MAXIMUM_PAGE_BYTES:-262144}"
    local skew="${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_CLOCK_SKEW_SECONDS:-30}"
    local lifetime="${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_MAXIMUM_PAGE_LIFETIME_SECONDS:-300}"
    if printf '%s\n%s\n%s\n%s\n%s\n%s\n' \
        "${poll}" "${pages}" "${timeout}" "${bytes}" "${skew}" "${lifetime}" |
        grep -Eqv '^(0|[1-9][0-9]*)$' ||
        [ "${poll}" -lt 1 ] || [ "${poll}" -gt 3600 ] ||
        [ "${pages}" -lt 1 ] || [ "${pages}" -gt 32 ] ||
        [ "${timeout}" -lt 100 ] || [ "${timeout}" -gt 30000 ] ||
        [ "${bytes}" -lt 1024 ] || [ "${bytes}" -gt 524288 ] ||
        [ "${skew}" -gt 300 ] ||
        [ "${lifetime}" -lt 1 ] || [ "${lifetime}" -gt 86400 ]; then
        echo "Certificate rotation event source polling and page bounds are invalid." >&2
        return 1
    fi
    if [ -z "${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_TRANSPORT_TRUST_STORE_PATH:-}" ] ||
        [ -z "${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_TRANSPORT_TRUST_STORE_PASSWORD_REF:-}" ]; then
        echo "Certificate rotation event source requires a private trust store." >&2
        return 1
    fi
    validate_control_plane_transport \
        "RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_TRANSPORT" \
        "certificate rotation event source"
}

validate_control_plane_certificate_status() {
    if ! truthy "${RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_ENABLED:-false}"; then
        if truthy "${RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_REQUIRED:-false}"; then
            echo "Required certificate status admission cannot be disabled." >&2
            return 1
        fi
        return 0
    fi
    local status_scope="${RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SCOPE_ID:-}"
    if ! truthy "${RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_REQUIRED:-true}" ||
        ! truthy "${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_ENABLED:-false}" ||
        ! truthy "${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_REQUIRED:-false}" ||
        [ -z "${status_scope}" ] ||
        [ "${status_scope}" != "${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_SCOPE_ID:-}" ]; then
        echo "Certificate status admission requires required signed rotation with the exact deployment scope." >&2
        return 1
    fi
    local trust_domain="${RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_TRUST_DOMAIN:-}"
    local policies="${RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_ACCEPTED_POLICIES:-}"
    local threshold="${RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SIGNATURE_THRESHOLD:-0}"
    local authorities="${RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_AUTHORITY_KEYS_JSON:-[]}"
    local baseline_sequence="${RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_BASELINE_SEQUENCE:-0}"
    local baseline_fingerprint="${RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_BASELINE_PUBLICATION_FINGERPRINT:-}"
    local endpoint="${RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_ENDPOINT_URI:-}"
    if printf '%s\n%s\n' "${status_scope}" "${trust_domain}" |
        grep -Eqv '^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}$' ||
        ! printf '%s' "${policies}" |
        grep -Eq '^sha256:[a-f0-9]{64}(,sha256:[a-f0-9]{64}){0,31}$' ||
        ! printf '%s' "${threshold}" | grep -Eq '^[1-9][0-9]*$' ||
        [ "${threshold}" -gt 32 ] ||
        ! printf '%s' "${authorities}" |
        grep -Eq '^\[[[:space:]]*\{.*\}[[:space:]]*\]$' ||
        printf '%s' "${authorities}" |
        grep -Eqi '"(privateKey|privateKeyBase64|password)"[[:space:]]*:' ||
        ! printf '%s' "${baseline_sequence}" | grep -Eq '^(0|[1-9][0-9]*)$' ||
        ! printf '%s' "${baseline_fingerprint}" |
        grep -Eq '^sha256:[a-f0-9]{64}$' ||
        ! printf '%s' "${endpoint}" |
        grep -Eq '^https://[^/?#[:space:]]+(/[^?#[:space:]]*)?$'; then
        echo "Certificate status trust, baseline, or HTTPS source configuration is invalid." >&2
        return 1
    fi
    local timeout="${RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_REQUEST_TIMEOUT_MILLIS:-5000}"
    local bytes="${RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_MAXIMUM_PUBLICATION_BYTES:-524288}"
    local skew="${RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_CLOCK_SKEW_SECONDS:-60}"
    local lifetime="${RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_MAXIMUM_PUBLICATION_LIFETIME_SECONDS:-3600}"
    local refresh="${RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_REFRESH_DELAY_MILLIS:-30000}"
    local initial="${RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_INITIAL_DELAY_MILLIS:-1000}"
    local batch="${RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_MAXIMUM_BATCH:-8}"
    if printf '%s\n%s\n%s\n%s\n%s\n%s\n%s\n' \
        "${timeout}" "${bytes}" "${skew}" "${lifetime}" "${refresh}" \
        "${initial}" "${batch}" | grep -Eqv '^(0|[1-9][0-9]*)$' ||
        [ "${timeout}" -lt 100 ] || [ "${timeout}" -gt 30000 ] ||
        [ "${bytes}" -lt 1024 ] || [ "${bytes}" -gt 2097152 ] ||
        [ "${skew}" -gt 300 ] || [ "${lifetime}" -lt 1 ] ||
        [ "${lifetime}" -gt 86400 ] || [ "${refresh}" -lt 100 ] ||
        [ "${refresh}" -gt 300000 ] || [ "${initial}" -gt 300000 ] ||
        [ "${batch}" -lt 1 ] || [ "${batch}" -gt 32 ]; then
        echo "Certificate status source, scheduler, or batch bounds are invalid." >&2
        return 1
    fi
    local startup_grace="${RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SLO_STARTUP_GRACE_SECONDS:-60}"
    local maximum_success_age="${RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SLO_MAXIMUM_REFRESH_SUCCESS_AGE_SECONDS:-120}"
    local minimum_headroom="${RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SLO_MINIMUM_EXPIRY_HEADROOM_SECONDS:-60}"
    local minimum_refresh_samples="${RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SLO_MINIMUM_REFRESH_SAMPLES:-20}"
    local maximum_refresh_failure="${RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SLO_MAXIMUM_REFRESH_FAILURE_BASIS_POINTS:-500}"
    local minimum_admission_samples="${RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SLO_MINIMUM_ADMISSION_SAMPLES:-100}"
    local maximum_admission_denial="${RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SLO_MAXIMUM_ADMISSION_DENIAL_BASIS_POINTS:-1000}"
    local maximum_source_head_lag="${RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SLO_MAXIMUM_SOURCE_HEAD_LAG:-0}"
    if printf '%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n' \
        "${startup_grace}" "${maximum_success_age}" "${minimum_headroom}" \
        "${minimum_refresh_samples}" "${maximum_refresh_failure}" \
        "${minimum_admission_samples}" "${maximum_admission_denial}" \
        "${maximum_source_head_lag}" | grep -Eqv '^(0|[1-9][0-9]*)$' ||
        [ "${startup_grace}" -gt 3600 ] ||
        [ "$((startup_grace * 1000))" -lt "$((initial + timeout))" ] ||
        [ "${maximum_success_age}" -lt 1 ] ||
        [ "${maximum_success_age}" -gt 86400 ] ||
        [ "$((maximum_success_age * 1000))" -lt "$((refresh + timeout))" ] ||
        [ "${minimum_headroom}" -gt 86400 ] ||
        [ "${minimum_headroom}" -ge "${lifetime}" ] ||
        [ "${minimum_refresh_samples}" -lt 1 ] ||
        [ "${minimum_refresh_samples}" -gt 1000000 ] ||
        [ "${maximum_refresh_failure}" -gt 10000 ] ||
        [ "${minimum_admission_samples}" -lt 1 ] ||
        [ "${minimum_admission_samples}" -gt 1000000 ] ||
        [ "${maximum_admission_denial}" -gt 10000 ] ||
        [ "${maximum_source_head_lag}" -gt 1000000 ]; then
        echo "Certificate status SLO bounds are invalid." >&2
        return 1
    fi
    if [ -z "${RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_TRANSPORT_TRUST_STORE_PATH:-}" ] ||
        [ -z "${RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_TRANSPORT_TRUST_STORE_PASSWORD_REF:-}" ]; then
        echo "Certificate status source requires a private trust store." >&2
        return 1
    fi
    validate_control_plane_transport \
        "RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_TRANSPORT" \
        "certificate status source"
}

validate_control_plane_identity_isolation() {
    local -a prefixes=()
    if truthy "${RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_ENABLED:-false}"; then
        prefixes+=("RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_TRANSPORT")
    fi
    if truthy "${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_ENABLED:-false}"; then
        prefixes+=("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_TRANSPORT")
    fi
    if truthy "${RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_ENABLED:-false}"; then
        prefixes+=("RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_TRANSPORT")
    fi
    if truthy "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_ENABLED:-false}" &&
        truthy "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_ENABLED:-false}"; then
        prefixes+=("RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRANSPORT")
        if truthy "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOTS_ENABLED:-false}"; then
            prefixes+=("RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_TRANSPORT")
        fi
        if truthy "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_EXTERNAL_ANCHOR_ENABLED:-false}"; then
            prefixes+=("RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_EXTERNAL_ANCHOR_TRANSPORT")
            if truthy "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_EXTERNAL_ANCHOR_MANAGED_TRUST_ENABLED:-false}"; then
                prefixes+=("RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_EXTERNAL_ANCHOR_TRUST_TRANSPORT")
                if truthy "${RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_EXTERNAL_ANCHOR_BOOTSTRAP_ROOTS_ENABLED:-false}"; then
                    prefixes+=("RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_EXTERNAL_ANCHOR_BOOTSTRAP_ROOT_TRANSPORT")
                fi
            fi
        fi
    fi

    local anchor_prefix
    for anchor_prefix in \
        "RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR" \
        "RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR"; do
        local anchor_enabled_var="${anchor_prefix}_ENABLED"
        if truthy "${!anchor_enabled_var:-false}"; then
            prefixes+=("${anchor_prefix}_TRANSPORT")
            local managed_enabled_var="${anchor_prefix}_MANAGED_TRUST_ENABLED"
            if truthy "${!managed_enabled_var:-false}"; then
                prefixes+=("${anchor_prefix}_TRUST_TRANSPORT")
                local roots_enabled_var="${anchor_prefix}_BOOTSTRAP_ROOTS_ENABLED"
                if truthy "${!roots_enabled_var:-false}"; then
                    prefixes+=("${anchor_prefix}_BOOTSTRAP_ROOT_TRANSPORT")
                fi
            fi
        fi
    done

    local i j
    for ((i = 0; i < ${#prefixes[@]}; i++)); do
        local first_path_var="${prefixes[i]}_CLIENT_KEY_STORE_PATH"
        local first_ref_var="${prefixes[i]}_CLIENT_KEY_STORE_PASSWORD_REF"
        for ((j = i + 1; j < ${#prefixes[@]}; j++)); do
            local second_path_var="${prefixes[j]}_CLIENT_KEY_STORE_PATH"
            local second_ref_var="${prefixes[j]}_CLIENT_KEY_STORE_PASSWORD_REF"
            if [ -n "${!first_path_var:-}" ] &&
                [ "${!first_path_var}" = "${!second_path_var:-}" ] &&
                [ "${!first_ref_var:-}" = "${!second_ref_var:-}" ]; then
                echo "Control-plane sources require independent client identities." >&2
                return 1
            fi
        done
    done
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
    validate_control_plane_identity_isolation
    validate_control_plane_certificate_rotation
    validate_control_plane_certificate_rotation_convergence
    validate_control_plane_certificate_status
    validate_control_plane_certificate_rotation_event_source
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

capability_studio_url() {
    echo "http://localhost:$(configured_port)/capabilities/?lang=zh-CN"
}

capability_studio_quality_url() {
    echo "http://localhost:$(configured_port)/capabilities/?lang=zh-CN&task=quality"
}

business_mirror_url() {
    echo "http://localhost:$(configured_port)/business-mirror/"
}

correctness_url() {
    echo "http://localhost:$(configured_port)/correctness/?lang=zh-CN"
}

correctness_workspace_api_url() {
    echo "http://localhost:$(configured_port)/api/visual/correctness-workspaces/GRAPH/loan-decision-with-fallback?targetFingerprint=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa&definitionId=loan-correctness-demo&caseLimit=100"
}

libraries_url() {
    echo "http://localhost:$(configured_port)/libraries/"
}

rehearsals_url() {
    echo "http://localhost:$(configured_port)/rehearsals/"
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

capability_studio_demo_pack_url() {
    echo "http://localhost:$(configured_port)/api/capability-studio/demo-pack"
}

capability_studio_acceptance_url() {
    echo "http://localhost:$(configured_port)/api/capability-studio/acceptance-baseline"
}

capability_studio_dataset_url() {
    echo "http://localhost:$(configured_port)/api/capability-studio/scenario-dataset"
}

capability_studio_quality_impact_url() {
    echo "http://localhost:$(configured_port)/api/capability-studio/scenario-dataset/quality-impact"
}

capability_studio_tutorial_branch_url() {
    echo "http://localhost:$(configured_port)/api/capability-studio/tutorial-branch"
}

capability_studio_tutorial_preflight_url() {
    echo "http://localhost:$(configured_port)/api/capability-studio/tutorial-branch/preflight"
}

capability_studio_feature_rehearsal_url() {
    echo "http://localhost:$(configured_port)/api/capability-studio/feature-rehearsal?caseId=case-compensation-history-timeout&permission=STRUCTURE_ONLY"
}

capability_studio_feature_baseline_url() {
    echo "http://localhost:$(configured_port)/api/capability-studio/feature-rehearsal-baseline"
}

capability_studio_governed_baseline_url() {
    echo "http://localhost:$(configured_port)/api/capability-studio/governed-baseline"
}

jar_path() {
    echo "${PROJECT_DIR}/target/${JAR_NAME}"
}

packaged_artifact_fingerprint() {
    local artifact
    local digest
    artifact="$(jar_path)"
    if command -v shasum >/dev/null 2>&1; then
        digest="$(shasum -a 256 "${artifact}" | awk '{print $1}')"
    elif command -v sha256sum >/dev/null 2>&1; then
        digest="$(sha256sum "${artifact}" | awk '{print $1}')"
    else
        echo "A SHA-256 command is required to bind the packaged candidate." >&2
        return 1
    fi
    if ! printf '%s' "${digest}" | grep -Eq '^[a-f0-9]{64}$'; then
        echo "The packaged candidate SHA-256 digest is invalid." >&2
        return 1
    fi
    printf 'sha256:%s\n' "${digest}"
}

candidate_source_commit() {
    local commit="${RG_RESOURCE_GATEWAY_SOURCE_COMMIT:-}"
    if [ -z "${commit}" ] && command -v git >/dev/null 2>&1; then
        commit="$(git -C "${ROOT_DIR}" rev-parse HEAD 2>/dev/null || true)"
    fi
    printf '%s\n' "${commit}"
}

candidate_source_tree_status() {
    if ! command -v git >/dev/null 2>&1 ||
        ! git -C "${ROOT_DIR}" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
        printf 'UNKNOWN\n'
        return 0
    fi
    if [ -z "$(git -C "${ROOT_DIR}" status --porcelain 2>/dev/null)" ]; then
        printf 'CLEAN\n'
    else
        printf 'DIRTY\n'
    fi
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

artifact_has_visual_frontend() {
    local artifact
    artifact="$(jar_path)"
    if [ ! -f "${artifact}" ] || ! command -v jar >/dev/null 2>&1; then
        return 1
    fi

    jar tf "${artifact}" 2>/dev/null | awk '
        $0 == "BOOT-INF/classes/static/capabilities/index.html" ||
        $0 == "BOOT-INF/classes/static/business-mirror/index.html" ||
        $0 == "BOOT-INF/classes/static/author/index.html" ||
        $0 == "BOOT-INF/classes/static/correctness/index.html" ||
        $0 == "BOOT-INF/classes/static/libraries/index.html" ||
        $0 == "BOOT-INF/classes/static/rehearsals/index.html" ||
        $0 == "BOOT-INF/classes/static/showcase/index.html" {
            if (!seen[$0]++) {
                found++
            }
        }
        END { exit found == 7 ? 0 : 1 }
    '
}

validate_packaged_artifact() {
    local artifact
    artifact="$(jar_path)"
    if [ ! -f "${artifact}" ]; then
        echo "Resource Gateway jar does not exist: ${artifact}" >&2
        return 1
    fi
    if truthy "${BUILD_FRONTEND}" && ! artifact_has_visual_frontend; then
        echo "Resource Gateway jar does not contain the complete visual frontend." >&2
        echo "Run again without --no-build, or pass --api-only for an API-only service." >&2
        return 1
    fi
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
    echo "Demo URLs:"
    if truthy "${BUILD_FRONTEND}" && artifact_has_visual_frontend; then
        cat <<EOF
  Capability Studio: $(capability_studio_url)
  Quality & impact:  $(capability_studio_quality_url)
  Business Mirror: $(business_mirror_url)
$(truthy "${CORRECTNESS_DEMO}" && printf '  Correctness:     %s\n' "$(correctness_url)")
  Author canvas:   $(author_url)
  Library author:  $(libraries_url)
  Rehearsals:      $(rehearsals_url)
  Showcase:        $(showcase_url)
  Legacy composer: $(legacy_url)
EOF
    else
        echo "  Visual routes:   not advertised (--api-only or not packaged)"
    fi
    cat <<EOF
  Capability probe: $(capabilities_url)
$(truthy "${CAPABILITY_STUDIO_DEMO}" && printf '  Golden demo pack: %s\n' "$(capability_studio_demo_pack_url)")
$(truthy "${CAPABILITY_STUDIO_DEMO}" && printf '  Acceptance base:  %s\n' "$(capability_studio_acceptance_url)")
$(truthy "${CAPABILITY_STUDIO_DEMO}" && printf '  Scenario dataset: %s\n' "$(capability_studio_dataset_url)")
$(truthy "${CAPABILITY_STUDIO_DEMO}" && printf '  Quality & impact:  GET  %s\n' "$(capability_studio_quality_impact_url)")
$(truthy "${CAPABILITY_STUDIO_DEMO}" && printf '  Tutorial branch:  %s\n' "$(capability_studio_tutorial_branch_url)")
$(truthy "${CAPABILITY_STUDIO_DEMO}" && printf '  Tutorial check:   POST %s\n' "$(capability_studio_tutorial_preflight_url)")
$(truthy "${CAPABILITY_STUDIO_DEMO}" && printf '  Feature trace:    GET  %s\n' "$(capability_studio_feature_rehearsal_url)")
$(truthy "${CAPABILITY_STUDIO_DEMO}" && printf '  Feature baseline: GET  %s\n' "$(capability_studio_feature_baseline_url)")
$(truthy "${CAPABILITY_STUDIO_DEMO}" && printf '  Governed 9x3:    POST %s (run from Tool page)\n' "$(capability_studio_governed_baseline_url)")
  Active profile:   ${SPRING_PROFILE}

Integration API templates:
  Draft workbook:    GET  /api/integration/drafts/{draftId}/correctness-workbook?revision={revision}
  Semantic workbook: GET  /api/integration/test-suites/{suiteId}/revisions/{revision}/semantic-correctness-workbook
  Gate feedback:     POST /api/integration/gate-results
  Correctness sample: GET  /api/visual/correctness-workspaces/GRAPH/loan-decision-with-fallback (enabled by default; X-Purpose: CORRECTNESS_READ)
  Test execution:    POST /api/testing/executions  (Bearer token + X-Purpose: TEST_EXECUTION)
  Fixture registry: PUT /api/testing/fixture-bundles/{id} (X-Purpose: TEST_FIXTURE_WRITE)
  Stateful session: POST /api/mirror/sessions (--stateful; Bearer token + X-Purpose: MIRROR_REHEARSAL)
  Session command:  POST /api/mirror/sessions/{sessionId}/commands
  Scenario batches: POST /api/mirror/rehearsal-jobs (--scenario-batch; Bearer token + X-Purpose: MIRROR_REHEARSAL)
  Batch workbench:  GET  /api/mirror/rehearsal-jobs (exact-scope keyset page)
  Batch workbook:   GET  /api/mirror/rehearsal-jobs/{jobId}/workbook-seed
  Finalization:     GET  /api/mirror/rehearsal-jobs/{jobId}/finalization
  Finalizer health: GET  /api/mirror/rehearsal-jobs/finalization-health
  Shadow admission: POST /api/mirror/shadow-jobs (--shadow-jobs; Bearer token + X-Purpose: MIRROR_SHADOW)
  Shadow lifecycle: GET  /api/mirror/shadow-jobs/{jobId}/lifecycle
  Source proof:     GET  /api/mirror/shadow/source-resolutions/{attestationId}/revisions/{revision}?fingerprint=...
  Continuous outcome: POST /api/mirror/outcome-continuous-assessments (--outcome-continuous-assessment; customer authorities required)
EOF
}

build_app() {
    if truthy "${SKIP_BUILD}"; then
        validate_packaged_artifact
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
    if ! (
        cd "${ROOT_DIR}"
        "${command[@]}"
    ); then
        return 1
    fi
    validate_packaged_artifact
}

visual_routes_ready() {
    curl -fsS "$(capability_studio_url)" >/dev/null 2>&1 &&
        curl -fsS "$(business_mirror_url)" >/dev/null 2>&1 &&
        curl -fsS "$(author_url)" >/dev/null 2>&1 &&
        curl -fsS "$(correctness_url)" >/dev/null 2>&1 &&
        curl -fsS "$(libraries_url)" >/dev/null 2>&1 &&
        curl -fsS "$(rehearsals_url)" >/dev/null 2>&1 &&
        curl -fsS "$(showcase_url)" >/dev/null 2>&1
}

wait_for_ready() {
    if ! command -v curl >/dev/null 2>&1; then
        echo "Started. curl is unavailable, so readiness was not checked."
        return 0
    fi

    local deadline
    local url
    local response
    local visual_readiness
    deadline=$((SECONDS + STARTUP_TIMEOUT))
    url="$(capabilities_url)"
    while [ "${SECONDS}" -lt "${deadline}" ]; do
        if ! running_pid >/dev/null 2>&1; then
            echo "Demo service exited before becoming ready. See $(log_file)." >&2
            return 1
        fi
        if response="$(curl -fsS "${url}" 2>/dev/null)"; then
            visual_readiness=""
            if truthy "${BUILD_FRONTEND}" && ! visual_routes_ready; then
                sleep 2
                continue
            fi
            if truthy "${BUILD_FRONTEND}"; then
                visual_readiness=", and visual route probes passed"
            fi
            if truthy "${CAPABILITY_STUDIO_DEMO}"; then
                local capability_studio_pack
                local capability_studio_acceptance
                local capability_studio_dataset
                local capability_studio_quality_impact
                local capability_studio_branch
                local capability_studio_preflight
                local capability_studio_feature_rehearsal
                local capability_studio_feature_baseline
                if ! capability_studio_pack="$(curl -fsS \
                    "$(capability_studio_demo_pack_url)" 2>/dev/null)" ||
                    ! capability_studio_acceptance="$(curl -fsS \
                    "$(capability_studio_acceptance_url)" 2>/dev/null)" ||
                    ! capability_studio_dataset="$(curl -fsS \
                    "$(capability_studio_dataset_url)" 2>/dev/null)" ||
                    ! capability_studio_quality_impact="$(curl -fsS \
                    -H "Authorization: Bearer ${CAPABILITY_STUDIO_REHEARSAL_TOKEN}" \
                    -H 'X-Purpose: CAPABILITY_STUDIO_REHEARSAL' \
                    "$(capability_studio_quality_impact_url)" 2>/dev/null)" ||
                    ! capability_studio_branch="$(curl -fsS \
                    "$(capability_studio_tutorial_branch_url)" 2>/dev/null)" ||
                    ! capability_studio_preflight="$(curl -fsS -X POST \
                    "$(capability_studio_tutorial_preflight_url)" 2>/dev/null)" ||
                    ! capability_studio_feature_rehearsal="$(curl -fsS \
                    -H "Authorization: Bearer ${CAPABILITY_STUDIO_REHEARSAL_TOKEN}" \
                    -H 'X-Purpose: CAPABILITY_STUDIO_REHEARSAL' \
                    "$(capability_studio_feature_rehearsal_url)" 2>/dev/null)" ||
                    ! capability_studio_feature_baseline="$(curl -fsS \
                    "$(capability_studio_feature_baseline_url)" 2>/dev/null)"; then
                    sleep 2
                    continue
                fi
                if command -v jq >/dev/null 2>&1; then
                    if ! printf '%s' "${capability_studio_pack}" | jq -e '
                        .cardinality.api == 4
                        and .cardinality.feature == 1
                        and .cardinality.tool == 1
                        and .cardinality.scenarios == 9
                    ' >/dev/null 2>&1 ||
                        ! printf '%s' "${capability_studio_acceptance}" | jq -e '
                        (.status == "NO_GO")
                        and ((.gates | length) == 10)
                        and ([.gates[].status] | all(. == "NOT_RUN"))
                    ' >/dev/null 2>&1 ||
                        ! printf '%s' "${capability_studio_dataset}" | jq -e '
                        (.schemaVersion == "resource-gateway.capability-studio.scenario-dataset.v1")
                        and (.datasetRef.kind == "DATASET")
                        and (.datasetRef.fingerprint | test("^sha256:[0-9a-f]{64}$"))
                        and (.lifecycle == "REVIEW_READY")
                        and (.quality.status == "BLOCKED")
                        and (.quality.totalCaseCount == 9)
                        and ((.cases | length) == 9)
                        and ([.cases[].caseRef.kind] | all(. == "DATA_CASE"))
                        and ([.cases[].owner] | all(. != null))
                        and ([.cases[].sourceRef] | all(. != null))
                        and ([.cases[].oracleRef] | all(. != null))
                        and ([.cases[].applicableContractRefs | length] | all(. > 0))
                    ' >/dev/null 2>&1 ||
                        ! printf '%s' "${capability_studio_quality_impact}" | jq -e '
                        (.schemaVersion == "resource-gateway.capability-studio.scenario-quality-impact.v1")
                        and (.targetRef.kind == "TOOL")
                        and (.targetRef.id == "tool-cancellation-fee-dispute-handling")
                        and (.targetRef.fingerprint | test("^sha256:[0-9a-f]{64}$"))
                        and (.admission.status == "BLOCKED")
                        and (.admission.activeCaseCount == 0)
                        and (.admission.draftCaseCount == 9)
                        and (.admission.staleCaseCount == 0)
                        and ([.admission.blockers[].code] == ["FRESHNESS_EVIDENCE_MISSING", "NO_ACTIVE_CASES"])
                        and (.quality.ownerCoveragePercent == 100)
                        and (.quality.sourceCoveragePercent == 100)
                        and (.quality.oracleCoveragePercent == 100)
                        and (.quality.contractCoveragePercent == 100)
                        and (.quality.behaviorClosurePercent == 100)
                        and (.quality.freshnessStatus == "UNVERIFIED")
                        and (.quality.payloadExposure == "NONE")
                        and (.quality.maskingStatus == "PAYLOAD_NOT_EXPORTED")
                        and (.summary.caseCount == 9)
                        and (.summary.impactedAssetCount == 9)
                        and (.summary.orphanCaseCount == 0)
                        and ((.cases | length) == 9)
                        and ([.cases[].impactedAssetCount] | all(. == 6))
                        and ((.impactGraph.nodes | length) == 37)
                        and ((.impactGraph.edges | length) == 81)
                    ' >/dev/null 2>&1 ||
                        ! jq -n -e \
                        --argjson branch "${capability_studio_branch}" \
                        --argjson preflight "${capability_studio_preflight}" '
                        ($branch.revision >= 1)
                        and ($branch.fingerprint | test("^sha256:[0-9a-f]{64}$"))
                        and ($branch.canonicalBaselineFingerprint | test("^sha256:[0-9a-f]{64}$"))
                        and ($branch.behavior.dependencyId == "api-compensation-history")
                        and ($branch.behavior.behavior == "TIMEOUT")
                        and ($branch.behavior.durationMs >= 100 and $branch.behavior.durationMs <= 30000)
                        and ($preflight.mode == "ISOLATED")
                        and ($preflight.unresolvedDependencies == 0)
                        and ($preflight.realExternalCallCount == 0)
                        and ($preflight.fallbackToReal == false)
                        and ($preflight.branchId == $branch.branchId)
                        and ($preflight.revision == $branch.revision)
                        and ($preflight.fingerprint == $branch.fingerprint)
                    ' >/dev/null 2>&1 ||
                        ! printf '%s' "${capability_studio_feature_rehearsal}" | jq -e '
                        (.schemaVersion == "resource-gateway.capability-studio.feature-rehearsal.v1")
                        and (.scenario.id == "case-compensation-history-timeout")
                        and (.run.status == "PASSED")
                        and (.run.bindingMode == "FIXTURE_CONTROLLED_NON_PRODUCTION")
                        and (.run.realExternalCallCount == 0)
                        and (.dataLens.permissionMode == "STRUCTURE_ONLY")
                        and ((.dataLens.nodes | length) == 6)
                        and ((.dataLens.edges | length) == 5)
                        and ([.dataLens.nodes[] | select(.input != null or .output != null)] | length == 0)
                        and ([.dataLens.edges[] | select(.value != null)] | length == 0)
                    ' >/dev/null 2>&1 ||
                        ! printf '%s' "${capability_studio_feature_baseline}" | jq -e '
                        (.schemaVersion == "resource-gateway.capability-studio.feature-rehearsal-baseline.v1")
                        and (.evidenceKind == "DEVELOPMENT_TEST_OWNED")
                        and (.status == "PASSED")
                        and (.caseCount == 9 and .roundCount == 3 and .runCount == 27)
                        and (.realExternalCallCount == 0)
                        and ((.cases | length) == 9)
                        and ([.cases[].rounds | length] | all(. == 3))
                        and ([.cases[].oracle.status] | all(. == "PASS"))
                        and ([.cases[].rounds[].realExternalCallCount] | all(. == 0))
                        and (([.cases[].rounds[].runId] | length) == 27)
                        and (([.cases[].rounds[].runId] | unique | length) == 27)
                        and ([.cases[] | [.rounds[].semanticFingerprint] | unique | length] | all(. == 1))
                        and ([.cases[].rounds[].status] | all(. == "PASSED"))
                        and ((.operators | length) == 6)
                        and ([.operators[].sideEffectType] | all(. != "WRITE" and . != "MIXED"))
                        and ((.diagnostics | length) == 0)
                    ' >/dev/null 2>&1; then
                        sleep 2
                        continue
                    fi
                elif ! printf '%s' "${capability_studio_pack}" |
                    grep -Eq '"api"[[:space:]]*:[[:space:]]*4' ||
                    ! printf '%s' "${capability_studio_pack}" |
                    grep -Eq '"feature"[[:space:]]*:[[:space:]]*1' ||
                    ! printf '%s' "${capability_studio_pack}" |
                    grep -Eq '"tool"[[:space:]]*:[[:space:]]*1' ||
                    ! printf '%s' "${capability_studio_pack}" |
                    grep -Eq '"scenarios"[[:space:]]*:[[:space:]]*9' ||
                    ! printf '%s' "${capability_studio_acceptance}" |
                    grep -Eq '"status"[[:space:]]*:[[:space:]]*"NO_GO"' ||
                    ! printf '%s' "${capability_studio_dataset}" |
                    grep -Eq '"schemaVersion"[[:space:]]*:[[:space:]]*"resource-gateway.capability-studio.scenario-dataset.v1"' ||
                    ! printf '%s' "${capability_studio_dataset}" |
                    grep -Eq '"totalCaseCount"[[:space:]]*:[[:space:]]*9' ||
                    ! printf '%s' "${capability_studio_quality_impact}" |
                    grep -Eq '"schemaVersion"[[:space:]]*:[[:space:]]*"resource-gateway.capability-studio.scenario-quality-impact.v1"' ||
                    ! printf '%s' "${capability_studio_quality_impact}" |
                    grep -Eq '"draftCaseCount"[[:space:]]*:[[:space:]]*9' ||
                    ! printf '%s' "${capability_studio_quality_impact}" |
                    grep -Eq '"activeCaseCount"[[:space:]]*:[[:space:]]*0' ||
                    ! printf '%s' "${capability_studio_quality_impact}" |
                    grep -Eq '"orphanCaseCount"[[:space:]]*:[[:space:]]*0' ||
                    ! printf '%s' "${capability_studio_quality_impact}" |
                    grep -Eq '"maskingStatus"[[:space:]]*:[[:space:]]*"PAYLOAD_NOT_EXPORTED"' ||
                    ! printf '%s' "${capability_studio_preflight}" |
                    grep -Eq '"mode"[[:space:]]*:[[:space:]]*"ISOLATED"' ||
                    ! printf '%s' "${capability_studio_preflight}" |
                    grep -Eq '"realExternalCallCount"[[:space:]]*:[[:space:]]*0' ||
                    ! printf '%s' "${capability_studio_preflight}" |
                    grep -Eq '"fallbackToReal"[[:space:]]*:[[:space:]]*false' ||
                    ! printf '%s' "${capability_studio_feature_rehearsal}" |
                    grep -Eq '"schemaVersion"[[:space:]]*:[[:space:]]*"resource-gateway.capability-studio.feature-rehearsal.v1"' ||
                    ! printf '%s' "${capability_studio_feature_rehearsal}" |
                    grep -Eq '"status"[[:space:]]*:[[:space:]]*"PASSED"' ||
                    ! printf '%s' "${capability_studio_feature_rehearsal}" |
                    grep -Eq '"permissionMode"[[:space:]]*:[[:space:]]*"STRUCTURE_ONLY"' ||
                    ! printf '%s' "${capability_studio_feature_rehearsal}" |
                    grep -Eq '"realExternalCallCount"[[:space:]]*:[[:space:]]*0' ||
                    ! printf '%s' "${capability_studio_feature_baseline}" |
                    grep -Eq '"schemaVersion"[[:space:]]*:[[:space:]]*"resource-gateway.capability-studio.feature-rehearsal-baseline.v1"' ||
                    ! printf '%s' "${capability_studio_feature_baseline}" |
                    grep -Eq '"evidenceKind"[[:space:]]*:[[:space:]]*"DEVELOPMENT_TEST_OWNED"' ||
                    ! printf '%s' "${capability_studio_feature_baseline}" |
                    grep -Eq '"runCount"[[:space:]]*:[[:space:]]*27' ||
                    ! printf '%s' "${capability_studio_feature_baseline}" |
                    grep -Eq '"realExternalCallCount"[[:space:]]*:[[:space:]]*0'; then
                    sleep 2
                    continue
                fi
            fi
            if truthy "${CORRECTNESS_DEMO}"; then
                if command -v jq >/dev/null 2>&1; then
                    if ! printf '%s' "${response}" | jq -e '
                        .payload.features.correctnessWorkspaceApi == true
                        and .payload.features.correctnessRunApi == false
                    ' >/dev/null 2>&1; then
                        sleep 2
                        continue
                    fi
                elif ! printf '%s' "${response}" |
                    grep -Eq '"correctnessWorkspaceApi"[[:space:]]*:[[:space:]]*true' ||
                    ! printf '%s' "${response}" |
                    grep -Eq '"correctnessRunApi"[[:space:]]*:[[:space:]]*false'; then
                    sleep 2
                    continue
                fi
                local correctness_response
                if ! correctness_response="$(curl -fsS \
                    -H 'Authorization: Bearer bloge-aneke-demo-token' \
                    -H 'X-Purpose: CORRECTNESS_READ' \
                    "$(correctness_workspace_api_url)" 2>/dev/null)" ||
                    ! printf '%s' "${correctness_response}" |
                    grep -Fq '"title":"Loan decision correctness"'; then
                    sleep 2
                    continue
                fi
            fi
            if truthy "${SHADOW_JOBS}"; then
                if command -v jq >/dev/null 2>&1; then
                    if ! printf '%s' "${response}" | jq -e \
                        --argjson scheduler "$(truthy "${RG_MIRROR_SHADOW_JOB_SCHEDULER_ENABLED:-false}" && printf true || printf false)" '
                        .payload.features.mirrorReadOnlyShadowJobApi == true
                        and .payload.features.mirrorReadOnlyShadowLifecycleAudit == true
                        and .payload.features.mirrorReadOnlyShadowSourceResolutionApi == true
                        and ($scheduler == false or .payload.features.mirrorReadOnlyShadowScheduling == true)
                    ' >/dev/null 2>&1; then
                        sleep 2
                        continue
                    fi
                elif ! printf '%s' "${response}" |
                    grep -Eq '"mirrorReadOnlyShadowJobApi"[[:space:]]*:[[:space:]]*true' ||
                    ! printf '%s' "${response}" |
                    grep -Eq '"mirrorReadOnlyShadowLifecycleAudit"[[:space:]]*:[[:space:]]*true' ||
                    ! printf '%s' "${response}" |
                    grep -Eq '"mirrorReadOnlyShadowSourceResolutionApi"[[:space:]]*:[[:space:]]*true'; then
                    sleep 2
                    continue
                elif truthy "${RG_MIRROR_SHADOW_JOB_SCHEDULER_ENABLED:-false}" &&
                    ! printf '%s' "${response}" |
                    grep -Eq '"mirrorReadOnlyShadowScheduling"[[:space:]]*:[[:space:]]*true'; then
                    sleep 2
                    continue
                fi
            fi
            if truthy "${RG_MIRROR_SCENARIO_BATCH_SCHEDULER_ENABLED:-false}"; then
                if command -v jq >/dev/null 2>&1; then
                    if ! printf '%s' "${response}" | jq -e '
                        .payload.features.mirrorScenarioRehearsalBatchApi == true
                        and .payload.features.mirrorScenarioRehearsalBatchJobListing == true
                        and .payload.features.mirrorScenarioRehearsalBatchCooperativeControl == true
                        and .payload.features.mirrorScenarioRehearsalBatchEvidence == true
                        and .payload.features.mirrorScenarioRehearsalBatchWorkbookSeed == true
                        and .payload.features.mirrorScenarioRehearsalBatchEvidenceFinalizationApi == true
                        and .payload.features.mirrorScenarioRehearsalBatchEvidenceFinalizationScheduling == true
                        and .payload.features.mirrorScenarioRehearsalBatchFinalizationHealthApi == true
                        and .payload.features.mirrorScenarioRehearsalBatchFinalizationSloIntegrated == true
                        and .payload.features.mirrorScenarioRehearsalBatchFinalizationSloReady == true
                        and .payload.features.mirrorScenarioRehearsalBatchRetentionApi == true
                        and .payload.features.mirrorScenarioRehearsalBatchLegalHold == true
                        and .payload.features.mirrorScenarioRehearsalBatchDeletionProof == true
                        and .payload.features.mirrorScenarioRehearsalBatchScheduling == true
                    ' >/dev/null 2>&1; then
                        sleep 2
                        continue
                    fi
                elif ! printf '%s' "${response}" |
                    grep -Eq '"mirrorScenarioRehearsalBatchApi"[[:space:]]*:[[:space:]]*true' ||
                    ! printf '%s' "${response}" |
                    grep -Eq '"mirrorScenarioRehearsalBatchJobListing"[[:space:]]*:[[:space:]]*true' ||
                    ! printf '%s' "${response}" |
                    grep -Eq '"mirrorScenarioRehearsalBatchCooperativeControl"[[:space:]]*:[[:space:]]*true' ||
                    ! printf '%s' "${response}" |
                    grep -Eq '"mirrorScenarioRehearsalBatchEvidence"[[:space:]]*:[[:space:]]*true' ||
                    ! printf '%s' "${response}" |
                    grep -Eq '"mirrorScenarioRehearsalBatchWorkbookSeed"[[:space:]]*:[[:space:]]*true' ||
                    ! printf '%s' "${response}" |
                    grep -Eq '"mirrorScenarioRehearsalBatchEvidenceFinalizationApi"[[:space:]]*:[[:space:]]*true' ||
                    ! printf '%s' "${response}" |
                    grep -Eq '"mirrorScenarioRehearsalBatchEvidenceFinalizationScheduling"[[:space:]]*:[[:space:]]*true' ||
                    ! printf '%s' "${response}" |
                    grep -Eq '"mirrorScenarioRehearsalBatchFinalizationHealthApi"[[:space:]]*:[[:space:]]*true' ||
                    ! printf '%s' "${response}" |
                    grep -Eq '"mirrorScenarioRehearsalBatchFinalizationSloIntegrated"[[:space:]]*:[[:space:]]*true' ||
                    ! printf '%s' "${response}" |
                    grep -Eq '"mirrorScenarioRehearsalBatchFinalizationSloReady"[[:space:]]*:[[:space:]]*true' ||
                    ! printf '%s' "${response}" |
                    grep -Eq '"mirrorScenarioRehearsalBatchRetentionApi"[[:space:]]*:[[:space:]]*true' ||
                    ! printf '%s' "${response}" |
                    grep -Eq '"mirrorScenarioRehearsalBatchLegalHold"[[:space:]]*:[[:space:]]*true' ||
                    ! printf '%s' "${response}" |
                    grep -Eq '"mirrorScenarioRehearsalBatchDeletionProof"[[:space:]]*:[[:space:]]*true' ||
                    ! printf '%s' "${response}" |
                    grep -Eq '"mirrorScenarioRehearsalBatchScheduling"[[:space:]]*:[[:space:]]*true'; then
                    sleep 2
                    continue
                fi
            fi
            if truthy "${RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_SCHEDULER_ENABLED:-false}"; then
                if command -v jq >/dev/null 2>&1; then
                    if ! printf '%s' "${response}" | jq -e '
                        .payload.features.mirrorAuthoritativeOutcomeContinuousAssessmentApi == true
                        and .payload.features.mirrorAuthoritativeOutcomeContinuousAssessmentDurable == true
                        and .payload.features.mirrorAuthoritativeOutcomeContinuousAssessmentWorkerReady == true
                        and .payload.features.mirrorAuthoritativeOutcomeContinuousAssessmentScheduling == true
                        and .payload.features.mirrorAuthoritativeOutcomeSelectedPopulationReady == true
                    ' >/dev/null 2>&1; then
                        sleep 2
                        continue
                    fi
                elif ! printf '%s' "${response}" |
                    grep -Eq '"mirrorAuthoritativeOutcomeContinuousAssessmentApi"[[:space:]]*:[[:space:]]*true' ||
                    ! printf '%s' "${response}" |
                    grep -Eq '"mirrorAuthoritativeOutcomeContinuousAssessmentDurable"[[:space:]]*:[[:space:]]*true' ||
                    ! printf '%s' "${response}" |
                    grep -Eq '"mirrorAuthoritativeOutcomeContinuousAssessmentWorkerReady"[[:space:]]*:[[:space:]]*true' ||
                    ! printf '%s' "${response}" |
                    grep -Eq '"mirrorAuthoritativeOutcomeContinuousAssessmentScheduling"[[:space:]]*:[[:space:]]*true' ||
                    ! printf '%s' "${response}" |
                    grep -Eq '"mirrorAuthoritativeOutcomeSelectedPopulationReady"[[:space:]]*:[[:space:]]*true'; then
                    sleep 2
                    continue
                fi
            fi
            if truthy "${RG_MIRROR_STATEFUL_ENABLED:-false}"; then
                if command -v jq >/dev/null 2>&1; then
                    if ! printf '%s' "${response}" | jq -e '
                        .payload.features.mirrorStatefulSessionApi == true
                        and .payload.features.mirrorStatefulStateStoreReady == true
                        and .payload.features.mirrorStateWriteAttemptDurableReconciliationReady == true
                    ' >/dev/null 2>&1; then
                        sleep 2
                        continue
                    fi
                elif ! printf '%s' "${response}" |
                    grep -Eq '"mirrorStatefulSessionApi"[[:space:]]*:[[:space:]]*true' ||
                    ! printf '%s' "${response}" |
                    grep -Eq '"mirrorStatefulStateStoreReady"[[:space:]]*:[[:space:]]*true' ||
                    ! printf '%s' "${response}" |
                    grep -Eq '"mirrorStateWriteAttemptDurableReconciliationReady"[[:space:]]*:[[:space:]]*true'; then
                    sleep 2
                    continue
                fi
                if truthy "${RG_MIRROR_SCENARIO_BATCH_SCHEDULER_ENABLED:-false}"; then
                    echo "Demo service ready; stateful and Scenario batch scheduler probes passed${visual_readiness}: ${url}"
                else
                    echo "Demo service ready; stateful Session, state store, and write-attempt reconciliation probes passed${visual_readiness}: ${url}"
                fi
                return 0
            fi
            if truthy "${RG_MIRROR_SCENARIO_BATCH_SCHEDULER_ENABLED:-false}"; then
                echo "Demo service ready; Scenario batch API and scheduler probes passed${visual_readiness}: ${url}"
                return 0
            fi
            if truthy "${RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_SCHEDULER_ENABLED:-false}"; then
                echo "Demo service ready; continuous outcome assessment API, worker, authority, and scheduler probes passed${visual_readiness}: ${url}"
                return 0
            fi
            if truthy "${SHADOW_JOBS}"; then
                echo "Demo service ready; Shadow job, lifecycle, and source-resolution API probes passed${visual_readiness}: ${url}"
                return 0
            fi
            if truthy "${CAPABILITY_STUDIO_DEMO}"; then
                echo "Demo service ready; Capability Studio 4/1/1/9 pack, Scenario Dataset, GP09 quality/admission/impact closure, isolated tutorial preflight, 9x3 development baseline, and visual probes passed${visual_readiness}: ${url}"
                return 0
            fi
            if truthy "${CORRECTNESS_DEMO}"; then
                echo "Demo service ready; Correctness capability and exact Workspace probes passed${visual_readiness}: ${url}"
                return 0
            fi
            echo "Demo service ready; integration capability probe passed${visual_readiness}: ${url}"
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
    if ! truthy "${BUILD_FRONTEND}" || ! artifact_has_visual_frontend; then
        echo "Browser open skipped because visual routes are not available in this mode."
        return 0
    fi
    if command -v open >/dev/null 2>&1; then
        if truthy "${CAPABILITY_STUDIO_DEMO}"; then
            open "$(capability_studio_url)" >/dev/null 2>&1 || true
        elif truthy "${CORRECTNESS_DEMO}"; then
            open "$(correctness_url)" >/dev/null 2>&1 || true
        else
            open "$(business_mirror_url)" >/dev/null 2>&1 || true
        fi
    else
        echo "Browser open requested, but the 'open' command is unavailable."
    fi
}

start_service() {
    configure_shadow_jobs
    validate_shadow_jobs
    configure_scenario_batch
    validate_scenario_batch
    configure_outcome_continuous_assessment
    validate_outcome_continuous_assessment
    configure_stateful_mirror
    validate_stateful_mirror
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
    if truthy "${CAPABILITY_STUDIO_DEMO}"; then
        case ",${SPRING_PROFILE}," in
            *,production,*)
                echo "The Capability Studio golden pack is physically unavailable in production." >&2
                return 1
                ;;
        esac
        args+=("--gateway.capability-studio.demo.enabled=true")
        local candidate_fingerprint
        local candidate_commit
        local candidate_source_status
        candidate_fingerprint="$(packaged_artifact_fingerprint)"
        candidate_commit="$(candidate_source_commit)"
        candidate_source_status="$(candidate_source_tree_status)"
        if [ "${SPRING_PROFILE}" = "staging" ] &&
            [ "${RG_RESOURCE_GATEWAY_ARTIFACT_FINGERPRINT}" != "${candidate_fingerprint}" ]; then
            echo "The staging Resource Gateway artifact fingerprint does not match the packaged JAR." >&2
            return 1
        fi
        if [ "${candidate_source_status}" = "CLEAN" ] &&
            printf '%s' "${candidate_commit}" | grep -Eq '^[A-Fa-f0-9]{7,64}$'; then
            args+=(
                "--gateway.capability-studio.acceptance.candidate-build.authority=deployment-launcher"
                "--gateway.capability-studio.acceptance.candidate-build.instance-id=${RG_RESOURCE_GATEWAY_INSTANCE_ID:-local-resource-gateway}"
                "--gateway.capability-studio.acceptance.candidate-build.build-ref=resource-gateway-examples"
                "--gateway.capability-studio.acceptance.candidate-build.revision=1.0.0"
                "--gateway.capability-studio.acceptance.candidate-build.source-commit=${candidate_commit}"
                "--gateway.capability-studio.acceptance.candidate-build.source-tree-status=CLEAN"
                "--gateway.capability-studio.acceptance.candidate-build.artifact-fingerprint=${candidate_fingerprint}"
            )
        elif [ "${SPRING_PROFILE}" = "staging" ]; then
            echo "Capability Studio staging requires a clean source tree and immutable source commit." >&2
            return 1
        else
            echo "Capability Studio candidate binding withheld: source tree is ${candidate_source_status}."
        fi
    fi
    if truthy "${CORRECTNESS_DEMO}"; then
        case ",${SPRING_PROFILE}," in
            *,production,*)
                echo "The Correctness sample is physically unavailable in production." >&2
                return 1
                ;;
        esac
        args+=("--gateway.testing.correctness.demo.enabled=true")
    fi
    if [ "${#APP_ARGS[@]}" -gt 0 ]; then
        args+=("${APP_ARGS[@]}")
    fi

    echo "Starting Visual Canvas demo..."
    (
        cd "${PROJECT_DIR}"
        export RG_API_RESOURCE_AUTHORING_ENABLED
        export RG_REUSABLE_FLOW_AUTHORING_ENABLED
        export RG_AUTHORING_LOCAL_SCHEMA_BOOTSTRAP_ENABLED
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
    if ! printf '%s' "${STOP_TIMEOUT}" | grep -Eq '^[1-9][0-9]*$' ||
        [ "${STOP_TIMEOUT}" -gt 300 ]; then
        echo "Visual canvas stop timeout must be between 1 and 300 seconds." >&2
        return 1
    fi

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
    for i in $(seq 1 "${STOP_TIMEOUT}"); do
        if ! kill -0 "${pid}" 2>/dev/null; then
            rm -f "$(pid_file)" "$(port_file)"
            echo "Visual canvas demo stopped."
            return 0
        fi
        sleep 1
    done

    echo "Visual canvas demo did not stop within ${STOP_TIMEOUT}s; forcing stop."
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
            --stateful)
                STATEFUL_MIRROR=1
                shift
                ;;
            --scenario-batch)
                SCENARIO_BATCH=1
                shift
                ;;
            --shadow-jobs)
                SHADOW_JOBS=1
                shift
                ;;
            --shadow-scheduler)
                SHADOW_JOBS=1
                SHADOW_SCHEDULER=1
                shift
                ;;
            --shadow-detached-data-plane)
                SHADOW_JOBS=1
                SHADOW_DETACHED_DATA_PLANE=1
                shift
                ;;
            --outcome-continuous-assessment)
                OUTCOME_CONTINUOUS_ASSESSMENT=1
                shift
                ;;
            --correctness)
                CORRECTNESS_DEMO=1
                shift
                ;;
            --no-correctness)
                CORRECTNESS_DEMO=0
                shift
                ;;
            --capability-studio)
                CAPABILITY_STUDIO_DEMO=1
                shift
                ;;
            --no-capability-studio)
                CAPABILITY_STUDIO_DEMO=0
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
