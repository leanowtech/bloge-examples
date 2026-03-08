#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PARENT_DIR=$(dirname "$SCRIPT_DIR")

cd "${SCRIPT_DIR}"

echo "Preparing bloge-examples dependencies..."
mvn -q -am -DskipTests install

mapfile -t REPL_CLASSES < <(
  find "${PARENT_DIR}/bloge-examples/src/main/java/com/leanowtech/bloge/examples" -name '*ReplExample.java' \
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

echo
echo "Select a REPL class to run:"
select main_class in "${REPL_CLASSES[@]}"; do
  if [ -n "${main_class:-}" ]; then
    echo "Running ${main_class}..."
    mvn exec:java -Dexec.mainClass="$main_class"
    break
  fi
  echo "Invalid selection."
done
