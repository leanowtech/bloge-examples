#!/usr/bin/env bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PARENT_DIR=$(dirname "$SCRIPT_DIR")

echo > "${SCRIPT_DIR}/output.log"

cd "${SCRIPT_DIR}"

echo "Preparing bloge-examples dependencies..."
mvn -q -am -DskipTests install || exit 1

for file in $(find "${PARENT_DIR}/bloge-examples/src/main/java/com/leanowtech/bloge/examples" -name '*.java' ! -name '*ReplExample.java'); do
    if ! grep -q "public static void main" "$file"; then
        continue
    fi
    pkg=$(grep -E '^package ' "$file" | head -1 | sed 's/^package //;s/;//' | tr -d '\r')
    cls=$(grep -E '^public (final |abstract )?class ' "$file" | head -1 | sed 's/^public \(final \|abstract \)\?class //;s/ .*//' | tr -d '\r')
    if [ -n "$pkg" ] && [ -n "$cls" ]; then
        main_class="${pkg}.${cls}"
        echo main_class: "$main_class"
        mvn exec:java -Dexec.mainClass="$main_class" 2>&1 | tee -a "${SCRIPT_DIR}/output.log"
    fi
done
