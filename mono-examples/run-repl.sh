#!/usr/bin/env bash

set -euo pipefail

EXAMPLES_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

prepare_examples() {
  echo "Building mono-examples..."
  mvn -q -f "${EXAMPLES_DIR}/pom.xml" -DskipTests -Dcheckstyle.skip=true compile dependency:build-classpath -Dmdep.outputFile="${EXAMPLES_DIR}/target/classpath.txt"
}

cd "${EXAMPLES_DIR}"

prepare_examples

mapfile -t REPL_CLASSES < <(
  find "${EXAMPLES_DIR}/src/main/java/com/leanowtech/bloge/examples" -name '*ReplExample.java' \
    | while read -r file; do
        if ! grep -q "public static void main" "$file"; then
          continue
        fi
        pkg=$(grep -E '^package ' "$file" | head -1 | sed 's/^package //;s/;//' | tr -d '\r')
        cls=$(grep -E '^public (final |abstract )?class ' "$file" | head -1 | sed 's/^public \(final \|abstract \)\?class //;s/ .*//' | tr -d '\r')
        if [ -n "$pkg" ] && [ -n "$cls" ]; then
          echo "${pkg}.${cls}"
        fi
      done | sort
)

if [ "${#REPL_CLASSES[@]}" -eq 0 ]; then
  echo "No REPL examples found."
  exit 1
fi

# Compute classpath once
echo "Computing classpath..."
CLASSPATH="$(cat "${EXAMPLES_DIR}/target/classpath.txt"):${EXAMPLES_DIR}/target/classes"

if [ -z "$CLASSPATH" ] || [[ "$CLASSPATH" == ":" ]]; then
  echo "Error: Could not determine classpath"
  exit 1
fi

echo
echo "Select a REPL class to run:"
select main_class in "${REPL_CLASSES[@]}"; do
  if [ -n "${main_class:-}" ]; then
    echo "Running ${main_class}..."
    # Run Java directly (not through Maven) for proper stdin handling
    exec java --enable-preview -cp "$CLASSPATH" "$main_class"
  fi
  echo "Invalid selection."
done
