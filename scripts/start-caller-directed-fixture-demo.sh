#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESOURCE_GATEWAY_PORT="${RESOURCE_GATEWAY_PORT:-8081}"
RG_BASE_URL="${RG_BASE_URL:-http://localhost:${RESOURCE_GATEWAY_PORT}}"
RG_TOKEN="${RG_TOKEN:-bloge-aneke-demo-token}"
RG_PURPOSE="${RG_PURPOSE:-API_RESOURCE_AUTHORING}"

for dependency in curl jq; do
    if ! command -v "${dependency}" >/dev/null 2>&1; then
        echo "Missing required command: ${dependency}" >&2
        exit 1
    fi
done

export RESOURCE_GATEWAY_PORT
export RG_API_RESOURCE_AUTHORING_ENABLED="${RG_API_RESOURCE_AUTHORING_ENABLED:-true}"
export RG_REUSABLE_FLOW_AUTHORING_ENABLED="${RG_REUSABLE_FLOW_AUTHORING_ENABLED:-true}"
export RG_AUTHORING_LOCAL_SCHEMA_BOOTSTRAP_ENABLED="${RG_AUTHORING_LOCAL_SCHEMA_BOOTSTRAP_ENABLED:-true}"

allowed_purposes="${RG_INTEGRATION_ALLOWED_PURPOSES:-}"
case ",${allowed_purposes}," in
    *,API_RESOURCE_AUTHORING,*) ;;
    *) allowed_purposes="${allowed_purposes:+${allowed_purposes},}API_RESOURCE_AUTHORING" ;;
esac
export RG_INTEGRATION_ALLOWED_PURPOSES="${allowed_purposes}"

"${SCRIPT_DIR}/example-services.sh" start resource-gateway

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/rg-fixture-demo-start.XXXXXX")"
trap 'rm -rf "${work_dir}"' EXIT

curl --fail-with-body --silent --show-error \
    "${RG_BASE_URL}/api/authoring/availability" > "${work_dir}/availability.json"
if ! jq --exit-status '.apiResource == true and .reusableFlow == true' \
    "${work_dir}/availability.json" >/dev/null; then
    echo "Resource Gateway started without both required authoring capabilities." >&2
    jq . "${work_dir}/availability.json" >&2
    exit 1
fi

if ! curl --fail-with-body --silent --show-error \
    --header "Authorization: Bearer ${RG_TOKEN}" \
    --header "X-Purpose: ${RG_PURPOSE}" \
    "${RG_BASE_URL}/api/authoring/catalog?limit=1" \
    --output "${work_dir}/catalog.json"; then
    echo "Resource Gateway is running, but the demo identity or purpose is not authorized." >&2
    jq . "${work_dir}/catalog.json" >&2 2>/dev/null || sed -n '1,80p' "${work_dir}/catalog.json" >&2
    exit 1
fi

echo "Fixture demo prerequisites are ready: ${RG_BASE_URL}"
echo "Run: RG_BASE_URL=${RG_BASE_URL} ${SCRIPT_DIR}/curl-caller-directed-fixture-demo.sh"
echo "Stop: ${SCRIPT_DIR}/stop-caller-directed-fixture-demo.sh"
