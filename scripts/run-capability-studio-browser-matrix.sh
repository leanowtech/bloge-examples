#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MVN="${MVN:-mvn}"
JAVA="${JAVA:-java}"
ALLOW_DIRTY=false
BUILD_CANDIDATE=true
OUTPUT="${ROOT_DIR}/resource-gateway-examples/target/acceptance/capability-studio-browser-matrix-result-v1.json"

usage() {
    cat <<'EOF'
Usage: scripts/run-capability-studio-browser-matrix.sh [options]

Run the fixed Capability Studio acceptance matrix:
  GP-01..GP-10 x zh-CN/en-US x 1440x900/1024x768/390x844 = 60 cells

Options:
  --allow-dirty  Permit a dirty source tree for development diagnosis. The result
                 remains valid but cannot be COMPLETE.
  --no-build     Reuse the existing candidate JAR and frontend node_modules.
  --output PATH  Write the Browser Matrix Result v1 artifact to PATH.
  -h, --help     Show this help.

The default path fails before execution when the source tree is dirty. A successful
default run therefore binds a clean commit, candidate JAR fingerprint, browser
environment, 60 screenshots, and a COMPLETE result independently revalidated by
resource-gateway-test-kit.
EOF
}

while (($# > 0)); do
    case "$1" in
        --allow-dirty)
            ALLOW_DIRTY=true
            shift
            ;;
        --no-build)
            BUILD_CANDIDATE=false
            shift
            ;;
        --output)
            if (($# < 2)); then
                usage >&2
                exit 2
            fi
            OUTPUT="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            usage >&2
            exit 2
            ;;
    esac
done

SOURCE_COMMIT="$(git -C "${ROOT_DIR}" rev-parse HEAD)"
if [[ -n "$(git -C "${ROOT_DIR}" status --porcelain=v1)" ]]; then
    SOURCE_TREE_STATUS="DIRTY"
else
    SOURCE_TREE_STATUS="CLEAN"
fi

if [[ "${SOURCE_TREE_STATUS}" == "DIRTY" && "${ALLOW_DIRTY}" != "true" ]]; then
    echo "ERROR: source tree is dirty; commit the candidate or use --allow-dirty for development diagnosis." >&2
    exit 4
fi

CANDIDATE_JAR="${ROOT_DIR}/resource-gateway-examples/target/bloge-examples-resource-gateway-1.0.0.jar"
if [[ "${BUILD_CANDIDATE}" == "true" ]]; then
    "${MVN}" -f "${ROOT_DIR}/resource-gateway-examples/pom.xml" \
        -Pfrontend -DskipTests package
fi
if [[ ! -f "${CANDIDATE_JAR}" ]]; then
    echo "ERROR: candidate JAR is missing: ${CANDIDATE_JAR}" >&2
    exit 5
fi

mkdir -p "$(dirname "${OUTPUT}")"
MATRIX_ARGS=(
    -f "${ROOT_DIR}/resource-gateway-examples/pom.xml"
    -Dtest=CapabilityStudioBrowserMatrixProducerIT
    "-Dcapability.browser.matrix.source-commit=${SOURCE_COMMIT}"
    "-Dcapability.browser.matrix.source-tree-status=${SOURCE_TREE_STATUS}"
    "-Dcapability.browser.matrix.candidate-artifact=${CANDIDATE_JAR}"
    "-Dcapability.browser.matrix.build-ref=build:resource-gateway:${SOURCE_COMMIT:0:12}"
    "-Dcapability.browser.matrix.candidate-revision=${SOURCE_COMMIT}"
    "-Dcapability.browser.matrix.output=${OUTPUT}"
)
if [[ "${ALLOW_DIRTY}" != "true" ]]; then
    MATRIX_ARGS+=("-Dcapability.browser.matrix.require-complete=true")
fi
"${MVN}" "${MATRIX_ARGS[@]}" test

TEST_KIT_TARGET="${ROOT_DIR}/resource-gateway-test-kit/target"
RUNTIME_CLASSPATH="${TEST_KIT_TARGET}/browser-matrix-runtime-classpath.txt"
"${MVN}" -q -f "${ROOT_DIR}/resource-gateway-test-kit/pom.xml" \
    -DskipTests package dependency:build-classpath \
    "-Dmdep.outputFile=${RUNTIME_CLASSPATH}"

set +e
CLI_OUTPUT="$("${JAVA}" -cp "${TEST_KIT_TARGET}/classes:$(cat "${RUNTIME_CLASSPATH}")" \
    com.leanowtech.bloge.gateway.testkit.CapabilityStudioBrowserMatrixResultCli \
    "${OUTPUT}" 2>&1)"
CLI_EXIT=$?
set -e
printf '%s\n' "${CLI_OUTPUT}"

if [[ ${CLI_EXIT} -eq 0 ]]; then
    echo "COMPLETE: ${OUTPUT}"
    exit 0
fi
if [[ "${ALLOW_DIRTY}" == "true" && ${CLI_EXIT} -eq 3 && "${CLI_OUTPUT}" == "VALID status=FAILED" ]]; then
    echo "DEVELOPMENT_VERIFIED: all matrix cells passed, but the dirty candidate is not release evidence."
    echo "RESULT: ${OUTPUT}"
    exit 0
fi

echo "ERROR: independent Test Kit verification rejected the result (exit ${CLI_EXIT})." >&2
exit "${CLI_EXIT}"
