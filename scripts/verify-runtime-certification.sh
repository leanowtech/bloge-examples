#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MVN="${MVN:-mvn}"
MODE="${1:-protocol}"

usage() {
    cat <<'EOF'
Usage: scripts/verify-runtime-certification.sh [protocol|postgres|all]

  protocol  Verify server protocol/harness/journal tests and the independent Test Kit.
  postgres  Run the native PostgreSQL two-replica journal certification test.
  all       Run protocol and PostgreSQL verification.

This script never installs an environment Adapter and never injects faults. The postgres
mode requires the repository's embedded-PostgreSQL binary/runtime prerequisites.
EOF
}

verify_protocol() {
    "${MVN}" -f "${ROOT_DIR}/resource-gateway-examples/pom.xml" \
        -Dtest='RuntimeCertification*Test,DatabaseRuntimeCertificationExecutionJournalTest' test
    "${MVN}" -f "${ROOT_DIR}/resource-gateway-test-kit/pom.xml" \
        -Dtest=RuntimeCertificationVerifierTest test
}

verify_postgres() {
    "${MVN}" -f "${ROOT_DIR}/resource-gateway-examples/pom.xml" \
        -Dtest='DatabaseReadOnlyShadowPostgresCertificationTest#runtimeCertificationMigrationConsumesOneAuthorizationAcrossReplicas' test
}

case "${MODE}" in
    protocol)
        verify_protocol
        ;;
    postgres)
        verify_postgres
        ;;
    all)
        verify_protocol
        verify_postgres
        ;;
    -h|--help)
        usage
        ;;
    *)
        usage >&2
        exit 2
        ;;
esac
