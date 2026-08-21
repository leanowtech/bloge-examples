#!/usr/bin/env bash

set -u
set -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CLASSPATH_FILE="$MODULE_DIR/target/formal-evidence-runtime-classpath.txt"
MAIN_CLASS="com.leanowtech.bloge.gateway.testkit.CapabilityStudioFormalEvidenceRunVerifyCli"
MVN_BIN="${MVN_BIN:-mvn}"
JAVA_BIN="${JAVA_BIN:-java}"

if [[ "$#" -ne 4 || "$1" != "--manifest" || "$3" != "--bundle-root" ]]; then
  printf '%s\n' "usage: verify-formal-evidence-run.sh --manifest <absolute-path> --bundle-root <absolute-path>" >&2
  exit 2
fi

if ! "$MVN_BIN" -q -f "$MODULE_DIR/pom.xml" -Dmaven.test.skip=true package \
    dependency:build-classpath -Dmdep.outputFile="$CLASSPATH_FILE" \
    >/dev/null 2>&1; then
  printf '%s\n' 'NOT_VERIFIED outcome=UNAVAILABLE reasonCode=RG.CAPABILITY_STUDIO.FORMAL_EVIDENCE_RUN_VERIFY.BUILD_UNAVAILABLE' >&2
  exit 3
fi

if [[ ! -d "$MODULE_DIR/target/classes" || ! -r "$CLASSPATH_FILE" ]]; then
  exit 3
fi

RUNTIME_CLASSPATH="$MODULE_DIR/target/classes:$(<"$CLASSPATH_FILE")"
set +e
"$JAVA_BIN" --enable-preview -cp "$RUNTIME_CLASSPATH" "$MAIN_CLASS" "$@" 2>/dev/null
JAVA_STATUS=$?
set -e

case "$JAVA_STATUS" in
  2|3|4) exit "$JAVA_STATUS" ;;
  *)
    printf '%s\n' 'NOT_VERIFIED outcome=UNAVAILABLE reasonCode=RG.CAPABILITY_STUDIO.FORMAL_EVIDENCE_RUN_VERIFY.RUNTIME_UNAVAILABLE' >&2
    exit 3
    ;;
esac
