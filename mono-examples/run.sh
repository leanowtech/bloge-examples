#!/usr/bin/env bash

set -uo pipefail

EXAMPLES_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_LOG="${EXAMPLES_DIR}/output.log"

declare -a SKIPPED_EXAMPLES=()
declare -a FAILED_EXAMPLES=()

prepare_examples() {
    echo "Building mono-examples..."
    mvn -q -f "${EXAMPLES_DIR}/pom.xml" -DskipTests -Dcheckstyle.skip=true compile dependency:build-classpath -Dmdep.outputFile="${EXAMPLES_DIR}/target/classpath.txt" || return 1
}

compute_classpath() {
    local classpath_file="${EXAMPLES_DIR}/target/classpath.txt"
    if [ ! -f "${classpath_file}" ]; then
        echo "Error: dependency classpath file was not generated." >&2
        return 1
    fi

    EXAMPLE_CLASSPATH="$(cat "${classpath_file}"):${EXAMPLES_DIR}/target/classes"
    if [ -z "${EXAMPLE_CLASSPATH}" ] || [ "${EXAMPLE_CLASSPATH}" = ":${EXAMPLES_DIR}/target/classes" ]; then
        echo "Error: computed classpath is empty." >&2
        return 1
    fi
}

skip_reason() {
    local file="$1"
    case "$file" in
        */examples/longrunning/*)
            echo "requires suspend/resume infrastructure or manual event replay"
            ;;
        */examples/integration/maven/*)
            echo "requires the bloge-plugin-example profile to generate plugin metadata first"
            ;;
        */examples/integration/spring/*)
            echo "starts a Spring integration app that is intended for manual lifecycle testing"
            ;;
        *)
            echo ""
            ;;
    esac
}

should_skip_example() {
    local file="$1"
    case "$file" in
        */examples/longrunning/*|*/examples/integration/maven/*|*/examples/integration/spring/*)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

echo > "${OUTPUT_LOG}"

cd "${EXAMPLES_DIR}"

prepare_examples || exit 1
compute_classpath || exit 1

while IFS= read -r -d '' file; do
    if ! grep -q "public static void main" "$file"; then
        continue
    fi
    if should_skip_example "$file"; then
        reason=$(skip_reason "$file")
        rel_file=${file#"${EXAMPLES_DIR}/"}
        echo "Skipping ${rel_file} (${reason})" | tee -a "${OUTPUT_LOG}"
        SKIPPED_EXAMPLES+=("${rel_file}")
        continue
    fi
    pkg=$(grep -E '^package ' "$file" | head -1 | sed 's/^package //;s/;//' | tr -d '\r')
    cls=$(grep -E '^public (final |abstract )?class ' "$file" | head -1 | sed 's/^public \(final \|abstract \)\?class //;s/ .*//' | tr -d '\r')
    if [ -n "$pkg" ] && [ -n "$cls" ]; then
        main_class="${pkg}.${cls}"
        echo main_class: "$main_class" | tee -a "${OUTPUT_LOG}"
        java --enable-preview -cp "$EXAMPLE_CLASSPATH" "$main_class" 2>&1 | tee -a "${OUTPUT_LOG}"
        status=${PIPESTATUS[0]}
        if [ "${status}" -ne 0 ]; then
            FAILED_EXAMPLES+=("${main_class}")
        fi
    fi
done < <(find "${EXAMPLES_DIR}/src/main/java/com/leanowtech/bloge/examples" -name '*.java' ! -name '*ReplExample.java' -print0)

if [ "${#SKIPPED_EXAMPLES[@]}" -gt 0 ]; then
    {
        echo
        echo "Skipped examples that require manual or profile-specific setup:"
        printf '  - %s\n' "${SKIPPED_EXAMPLES[@]}"
    } | tee -a "${OUTPUT_LOG}"
fi

if [ "${#FAILED_EXAMPLES[@]}" -gt 0 ]; then
    {
        echo
        echo "Examples failed:"
        printf '  - %s\n' "${FAILED_EXAMPLES[@]}"
    } | tee -a "${OUTPUT_LOG}"
    exit 1
fi

echo "All self-contained examples completed successfully." | tee -a "${OUTPUT_LOG}"
