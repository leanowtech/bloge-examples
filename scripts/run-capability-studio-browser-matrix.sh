#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MVN="${MVN:-mvn}"
JAVA="${JAVA:-java}"
ALLOW_DIRTY=false
BUILD_CANDIDATE=true
OUTPUT="${ROOT_DIR}/resource-gateway-examples/target/acceptance/capability-studio-browser-matrix-result-v1.json"
ANOMALY_OUTPUT="${ROOT_DIR}/resource-gateway-examples/target/acceptance/capability-studio-browser-anomaly-matrix-result-v1.json"
ANOMALY_PROFILE=""
ANOMALY_GP=""
ANOMALY_LOCALE=""
ANOMALY_VIEWPORT=""
ANOMALY_FILTER_REQUESTED=false
ANOMALY_PROFILE_SET=false
ANOMALY_GP_SET=false
ANOMALY_LOCALE_SET=false
ANOMALY_VIEWPORT_SET=false

usage() {
    cat <<'EOF'
Usage: scripts/run-capability-studio-browser-matrix.sh [options]

Run the fixed Capability Studio browser acceptance gate:
  normal:  GP-01..GP-10 x zh-CN/en-US x 3 viewports = 60 cells
  anomaly: ERROR 60 + OFFLINE 60 + real stale-revision CONFLICT 6 = 126 obligations
  release total: 186 browser obligations
  development filters select execution only; the producer still emits 126 obligations.

Options:
  --allow-dirty  Development diagnosis only. Reuse an existing independently
                 verified COMPLETE normal base; never creates release evidence.
  --no-build     Reuse the existing candidate JAR and frontend node_modules.
  --output PATH  Write the normal Browser Matrix Result v1 artifact to PATH.
  --anomaly-output PATH
                 Write the Browser Anomaly Matrix Result v1 artifact to PATH.
  --anomaly-profile NAME
                 Development-only anomaly profile filter: ERROR, OFFLINE, or CONFLICT.
  --anomaly-gp GP-09
                 Development-only golden-path filter.
  --anomaly-locale LOCALE
                 Development-only locale filter: zh-CN or en-US.
  --anomaly-viewport SIZE
                 Development-only viewport filter: 1440x900, 1024x768, or 390x844.
  -h, --help     Show this help.

The default path fails before execution when the source tree is dirty. A successful
default run therefore builds one clean candidate, produces and independently
revalidates the COMPLETE normal and anomaly artifacts, and succeeds only after
all 186 obligations pass. ERROR uses HTTP 503, OFFLINE uses a transport failure,
and CONFLICT uses a real stale-revision HTTP 409.
EOF
}

invalid_filter() {
    echo "ERROR: $1" >&2
    exit 2
}

validate_anomaly_filters() {
    if [[ "${ANOMALY_PROFILE_SET}" == "true" ]]; then
        case "${ANOMALY_PROFILE}" in
            ERROR|OFFLINE|CONFLICT) ;;
            *) invalid_filter "--anomaly-profile must be ERROR, OFFLINE, or CONFLICT." ;;
        esac
    fi
    if [[ "${ANOMALY_GP_SET}" == "true" ]]; then
        case "${ANOMALY_GP}" in
            GP-01|GP-02|GP-03|GP-04|GP-05|GP-06|GP-07|GP-08|GP-09|GP-10) ;;
            *) invalid_filter "--anomaly-gp must be GP-01 through GP-10." ;;
        esac
    fi
    if [[ "${ANOMALY_LOCALE_SET}" == "true" ]]; then
        case "${ANOMALY_LOCALE}" in
            zh-CN|en-US) ;;
            *) invalid_filter "--anomaly-locale must be zh-CN or en-US." ;;
        esac
    fi
    if [[ "${ANOMALY_VIEWPORT_SET}" == "true" ]]; then
        case "${ANOMALY_VIEWPORT}" in
            1440x900|1024x768|390x844) ;;
            *) invalid_filter "--anomaly-viewport must be 1440x900, 1024x768, or 390x844." ;;
        esac
    fi
    if [[ "${ANOMALY_PROFILE}" == "CONFLICT" \
            && "${ANOMALY_GP_SET}" == "true" \
            && "${ANOMALY_GP}" != "GP-04" ]]; then
        invalid_filter "--anomaly-gp must be GP-04 when --anomaly-profile is CONFLICT."
    fi
}

validate_dirty_anomaly_complete() {
    jq -e '
        .resultStatus == "COMPLETE"
        and .summary.failed == 0
        and .summary.notRun == 0
        and ([.obligations[] | select(.status != "NOT_RUN") | .status] | all(. == "PASS"))
    ' -- "$1" >/dev/null
}

validate_dirty_anomaly_failed() {
    jq -e '
        .resultStatus == "FAILED"
        and .summary.failed == 0
        and .summary.passed > 0
        and .summary.notRun > 0
        and ([.diagnostics[] | .code] == ["CANDIDATE_SOURCE_TREE_DIRTY"])
        and ([.obligations[] | select(.status != "NOT_RUN") | .status] | all(. == "PASS"))
    ' -- "$1" >/dev/null
}

validate_dirty_anomaly_not_run() {
    jq -e '
        .resultStatus == "NOT_RUN"
        and .summary.failed == 0
        and .summary.notRun > 0
        and .summary.passed > 0
        and ([.obligations[] | select(.status != "NOT_RUN") | .status] | all(. == "PASS"))
    ' -- "$1" >/dev/null
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
        --anomaly-output)
            if (($# < 2)); then
                usage >&2
                exit 2
            fi
            ANOMALY_OUTPUT="$2"
            shift 2
            ;;
        --anomaly-profile)
            if (($# < 2)); then usage >&2; exit 2; fi
            ANOMALY_PROFILE="$2"
            ANOMALY_FILTER_REQUESTED=true
            ANOMALY_PROFILE_SET=true
            shift 2
            ;;
        --anomaly-gp)
            if (($# < 2)); then usage >&2; exit 2; fi
            ANOMALY_GP="$2"
            ANOMALY_FILTER_REQUESTED=true
            ANOMALY_GP_SET=true
            shift 2
            ;;
        --anomaly-locale)
            if (($# < 2)); then usage >&2; exit 2; fi
            ANOMALY_LOCALE="$2"
            ANOMALY_FILTER_REQUESTED=true
            ANOMALY_LOCALE_SET=true
            shift 2
            ;;
        --anomaly-viewport)
            if (($# < 2)); then usage >&2; exit 2; fi
            ANOMALY_VIEWPORT="$2"
            ANOMALY_FILTER_REQUESTED=true
            ANOMALY_VIEWPORT_SET=true
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

validate_anomaly_filters

if [[ "${ANOMALY_FILTER_REQUESTED}" == "true" && "${ALLOW_DIRTY}" != "true" ]]; then
    echo "ERROR: anomaly development filters require --allow-dirty." >&2
    exit 2
fi

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
mkdir -p "$(dirname "${ANOMALY_OUTPUT}")"

if [[ "${SOURCE_TREE_STATUS}" == "CLEAN" ]]; then
    MATRIX_ARGS=(
        -f "${ROOT_DIR}/resource-gateway-examples/pom.xml"
        -Dtest=CapabilityStudioBrowserMatrixProducerIT
        "-Dcapability.browser.matrix.source-commit=${SOURCE_COMMIT}"
        "-Dcapability.browser.matrix.source-tree-status=${SOURCE_TREE_STATUS}"
        "-Dcapability.browser.matrix.candidate-artifact=${CANDIDATE_JAR}"
        "-Dcapability.browser.matrix.build-ref=build:resource-gateway:${SOURCE_COMMIT:0:12}"
        "-Dcapability.browser.matrix.candidate-revision=${SOURCE_COMMIT}"
        "-Dcapability.browser.matrix.output=${OUTPUT}"
        -Dcapability.browser.matrix.require-complete=true
    )
    "${MVN}" "${MATRIX_ARGS[@]}" test
elif [[ ! -f "${OUTPUT}" ]]; then
    echo "ERROR: dirty development verification requires an existing COMPLETE normal base: ${OUTPUT}" >&2
    echo "Generate it from a clean checkout, then retry with --allow-dirty --no-build." >&2
    exit 6
fi

TEST_KIT_TARGET="${ROOT_DIR}/resource-gateway-test-kit/target"
RUNTIME_CLASSPATH="${TEST_KIT_TARGET}/browser-matrix-runtime-classpath.txt"
"${MVN}" -q -f "${ROOT_DIR}/resource-gateway-test-kit/pom.xml" \
    -DskipTests package dependency:build-classpath \
    "-Dmdep.outputFile=${RUNTIME_CLASSPATH}"

set +e
NORMAL_CLI_OUTPUT="$("${JAVA}" -cp "${TEST_KIT_TARGET}/classes:$(cat "${RUNTIME_CLASSPATH}")" \
    com.leanowtech.bloge.gateway.testkit.CapabilityStudioBrowserMatrixResultCli \
    "${OUTPUT}" 2>&1)"
NORMAL_CLI_EXIT=$?
set -e
printf '%s\n' "${NORMAL_CLI_OUTPUT}"

if [[ ${NORMAL_CLI_EXIT} -ne 0 || "${NORMAL_CLI_OUTPUT}" != "VALID status=COMPLETE" ]]; then
    echo "ERROR: independent Test Kit verification rejected the normal base (exit ${NORMAL_CLI_EXIT})." >&2
    [[ ${NORMAL_CLI_EXIT} -ne 0 ]] && exit "${NORMAL_CLI_EXIT}"
    exit 2
fi

ANOMALY_ARGS=(
    -f "${ROOT_DIR}/resource-gateway-examples/pom.xml"
    -Dtest=CapabilityStudioBrowserAnomalyMatrixProducerIT
    "-Dcapability.browser.anomaly.base=${OUTPUT}"
    "-Dcapability.browser.anomaly.output=${ANOMALY_OUTPUT}"
    "-Dcapability.browser.anomaly.candidate-artifact=${CANDIDATE_JAR}"
)
[[ -n "${ANOMALY_PROFILE}" ]] && ANOMALY_ARGS+=("-Dcapability.browser.anomaly.profile=${ANOMALY_PROFILE}")
[[ -n "${ANOMALY_GP}" ]] && ANOMALY_ARGS+=("-Dcapability.browser.anomaly.gp=${ANOMALY_GP}")
[[ -n "${ANOMALY_LOCALE}" ]] && ANOMALY_ARGS+=("-Dcapability.browser.anomaly.locale=${ANOMALY_LOCALE}")
[[ -n "${ANOMALY_VIEWPORT}" ]] && ANOMALY_ARGS+=("-Dcapability.browser.anomaly.viewport=${ANOMALY_VIEWPORT}")
if [[ "${SOURCE_TREE_STATUS}" == "CLEAN" ]]; then
    ANOMALY_ARGS+=("-Dcapability.browser.anomaly.require-complete=true")
fi
"${MVN}" "${ANOMALY_ARGS[@]}" test

set +e
ANOMALY_CLI_OUTPUT="$("${JAVA}" -cp "${TEST_KIT_TARGET}/classes:$(cat "${RUNTIME_CLASSPATH}")" \
    com.leanowtech.bloge.gateway.testkit.CapabilityStudioBrowserAnomalyMatrixResultCli \
    "${ANOMALY_OUTPUT}" "${OUTPUT}" 2>&1)"
ANOMALY_CLI_EXIT=$?
set -e
printf '%s\n' "${ANOMALY_CLI_OUTPUT}"

if [[ "${SOURCE_TREE_STATUS}" == "CLEAN" ]]; then
    if [[ ${ANOMALY_CLI_EXIT} -eq 0 && "${ANOMALY_CLI_OUTPUT}" == "VALID status=COMPLETE" ]]; then
        echo "COMPLETE: 186/186 browser obligations passed."
        echo "NORMAL_RESULT: ${OUTPUT}"
        echo "ANOMALY_RESULT: ${ANOMALY_OUTPUT}"
        exit 0
    fi
    echo "ERROR: independent Test Kit verification rejected the anomaly result (exit ${ANOMALY_CLI_EXIT})." >&2
    [[ ${ANOMALY_CLI_EXIT} -ne 0 ]] && exit "${ANOMALY_CLI_EXIT}"
    exit 2
fi

if [[ "${ALLOW_DIRTY}" == "true" ]]; then
    if [[ ${ANOMALY_CLI_EXIT} -eq 0 \
            && "${ANOMALY_CLI_OUTPUT}" == "VALID status=COMPLETE" ]]; then
        if validate_dirty_anomaly_complete "${ANOMALY_OUTPUT}"; then
            echo "DEVELOPMENT_VERIFIED: anomaly evidence is COMPLETE but cannot serve as release evidence from a dirty tree."
            echo "NORMAL_BASE: ${OUTPUT}"
            echo "ANOMALY_RESULT: ${ANOMALY_OUTPUT}"
            exit 0
        fi
    fi
    if [[ ${ANOMALY_CLI_EXIT} -eq 3 \
            && "${ANOMALY_CLI_OUTPUT}" == "VALID status=FAILED" ]]; then
        if validate_dirty_anomaly_failed "${ANOMALY_OUTPUT}"; then
            echo "DEVELOPMENT_VERIFIED: anomaly evidence passed executed obligations but is not release evidence."
            echo "NORMAL_BASE: ${OUTPUT}"
            echo "ANOMALY_RESULT: ${ANOMALY_OUTPUT}"
            exit 0
        fi
    fi
    if [[ ${ANOMALY_CLI_EXIT} -eq 3 \
            && "${ANOMALY_CLI_OUTPUT}" == "VALID status=NOT_RUN" \
            && "${ANOMALY_FILTER_REQUESTED}" == "true" ]]; then
        if validate_dirty_anomaly_not_run "${ANOMALY_OUTPUT}"; then
            echo "DEVELOPMENT_VERIFIED: filtered anomaly evidence is valid but cannot serve as release evidence."
            echo "NORMAL_BASE: ${OUTPUT}"
            echo "ANOMALY_RESULT: ${ANOMALY_OUTPUT}"
            exit 0
        fi
    fi
    echo "ERROR: dirty development anomaly evidence failed its local status policy." >&2
    exit 2
fi

echo "ERROR: independent Test Kit verification rejected dirty-tree anomaly evidence (exit ${ANOMALY_CLI_EXIT})." >&2
[[ ${ANOMALY_CLI_EXIT} -ne 0 ]] && exit "${ANOMALY_CLI_EXIT}"
exit 2
