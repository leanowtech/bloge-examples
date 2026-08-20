#!/usr/bin/env bash

set -u
umask 077

readonly MAX_STAGE_RESULT_BYTES=4194304
readonly MAX_CONFORMANCE_OUTPUT_BYTES=131072
readonly TERMINATION_GRACE_ATTEMPTS=50
readonly TERMINATION_GRACE_DELAY=0.1
readonly CONFORMANCE_MAIN='com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceProviderConformanceCli'
readonly FORMAL_MAIN='com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceCli'

test_kit_jar=''
provider_classpath=''
stage_result=''
conformance_output=''
seen_test_kit_jar=0
seen_provider_classpath=0
seen_stage_result=0
seen_conformance_output=0

usage() {
    printf '%s\n' \
        'Usage: verify-capability-studio-stage-acceptance.sh --test-kit-jar PATH --provider-classpath CLASSPATH --stage-result PATH --conformance-output PATH'
}

fail() {
    printf 'ERROR code=%s\n' "$1"
    exit 2
}

if [[ "$#" -eq 1 && ( "$1" == '--help' || "$1" == '-h' ) ]]; then
    usage
    exit 0
fi

[[ "$#" -eq 8 ]] || fail USAGE

while [[ "$#" -gt 0 ]]; do
    case "$1" in
        --test-kit-jar)
            [[ "$seen_test_kit_jar" -eq 0 && "$#" -ge 2 ]] || fail USAGE
            test_kit_jar="$2"
            seen_test_kit_jar=1
            shift 2
            ;;
        --provider-classpath)
            [[ "$seen_provider_classpath" -eq 0 && "$#" -ge 2 ]] || fail USAGE
            provider_classpath="$2"
            seen_provider_classpath=1
            shift 2
            ;;
        --stage-result)
            [[ "$seen_stage_result" -eq 0 && "$#" -ge 2 ]] || fail USAGE
            stage_result="$2"
            seen_stage_result=1
            shift 2
            ;;
        --conformance-output)
            [[ "$seen_conformance_output" -eq 0 && "$#" -ge 2 ]] || fail USAGE
            conformance_output="$2"
            seen_conformance_output=1
            shift 2
            ;;
        *)
            fail USAGE
            ;;
    esac
done

[[ "$seen_test_kit_jar" -eq 1 && "$seen_provider_classpath" -eq 1 \
    && "$seen_stage_result" -eq 1 && "$seen_conformance_output" -eq 1 ]] || fail USAGE
[[ -n "$provider_classpath" && "$provider_classpath" != :* \
    && "$provider_classpath" != *: && "$provider_classpath" != *::* ]] || fail INPUT

regular_readable_file() {
    [[ -f "$1" && -r "$1" && ! -L "$1" ]]
}

regular_readable_file "$test_kit_jar" || fail INPUT
regular_readable_file "$stage_result" || fail INPUT

old_ifs="$IFS"
IFS=':'
read -r -a provider_entries <<< "$provider_classpath"
IFS="$old_ifs"
[[ "${#provider_entries[@]}" -gt 0 ]] || fail INPUT
for provider_entry in "${provider_entries[@]}"; do
    regular_readable_file "$provider_entry" || fail INPUT
done

stage_size=$(wc -c < "$stage_result" 2>/dev/null | tr -d '[:space:]') || fail INPUT
[[ "$stage_size" =~ ^[0-9]+$ && "$stage_size" -le "$MAX_STAGE_RESULT_BYTES" ]] || fail INPUT

output_parent=$(dirname -- "$conformance_output")
[[ -d "$output_parent" && -w "$output_parent" ]] || fail OUTPUT
[[ ! -e "$conformance_output" && ! -L "$conformance_output" ]] || fail OUTPUT

java_bin="${JAVA_BIN:-}"
if [[ -z "$java_bin" ]]; then
    java_bin=$(command -v java 2>/dev/null) || fail JAVA
fi
[[ "$java_bin" == */* && -f "$java_bin" && -x "$java_bin" ]] || fail JAVA

sha256_bin=''
sha256_mode=''
if command -v sha256sum >/dev/null 2>&1; then
    sha256_bin=$(command -v sha256sum 2>/dev/null) || fail HASH
    sha256_mode='sha256sum'
elif command -v shasum >/dev/null 2>&1; then
    sha256_bin=$(command -v shasum 2>/dev/null) || fail HASH
    sha256_mode='shasum'
else
    fail HASH
fi
[[ "$sha256_bin" == */* && -f "$sha256_bin" && -x "$sha256_bin" ]] || fail HASH

temporary_directory=$(mktemp -d "$output_parent/.capability-studio-acceptance.XXXXXX" 2>/dev/null) \
    || fail TEMP
child_pid=''
pending_termination=0
cleanup() {
    rm -rf -- "$temporary_directory" >/dev/null 2>&1 || :
}
mark_termination() {
    pending_termination=1
}
terminate() {
    if [[ -n "$child_pid" ]]; then
        kill -TERM "$child_pid" >/dev/null 2>&1 || :
        termination_attempt=0
        while kill -0 "$child_pid" >/dev/null 2>&1 \
            && [[ "$termination_attempt" -lt "$TERMINATION_GRACE_ATTEMPTS" ]]; do
            sleep "$TERMINATION_GRACE_DELAY" >/dev/null 2>&1 || :
            termination_attempt=$((termination_attempt + 1))
        done
        if kill -0 "$child_pid" >/dev/null 2>&1; then
            kill -KILL "$child_pid" >/dev/null 2>&1 || :
        fi
        wait "$child_pid" >/dev/null 2>&1 || :
        child_pid=''
    fi
    exit 2
}
trap cleanup EXIT
trap terminate HUP INT TERM

run_child() {
    local stdout_path="$1"
    local stderr_path="$2"
    local child_exit
    shift 2
    pending_termination=0
    trap mark_termination HUP INT TERM
    "$@" >"$stdout_path" 2>"$stderr_path" &
    child_pid=$!
    trap terminate HUP INT TERM
    if [[ "$pending_termination" -ne 0 ]]; then
        terminate
    fi
    wait "$child_pid"
    child_exit=$?
    trap mark_termination HUP INT TERM
    child_pid=''
    trap terminate HUP INT TERM
    if [[ "$pending_termination" -ne 0 ]]; then
        terminate
    fi
    return "$child_exit"
}

snapshot_file() {
    local source_file="$1"
    local snapshot_file="$2"
    cp -P -- "$source_file" "$snapshot_file" >/dev/null 2>&1 || return 1
    [[ -f "$snapshot_file" && ! -L "$snapshot_file" ]] || return 1
    chmod 400 "$snapshot_file" >/dev/null 2>&1 || return 1
}

sha256_file() {
    local hash_output
    local digest
    if [[ "$sha256_mode" == 'sha256sum' ]]; then
        hash_output=$("$sha256_bin" "$1" 2>/dev/null) || return 1
    else
        hash_output=$("$sha256_bin" -a 256 "$1" 2>/dev/null) || return 1
    fi
    digest="${hash_output%%[[:space:]]*}"
    [[ "$digest" =~ ^[0-9a-f]{64}$ ]] || return 1
    printf '%s' "$digest"
}

snapshots_unchanged() {
    local snapshot_index
    local current_digest
    snapshot_index=0
    while [[ "$snapshot_index" -lt "${#snapshot_files[@]}" ]]; do
        current_digest=$(sha256_file "${snapshot_files[$snapshot_index]}") || return 1
        [[ "$current_digest" == "${snapshot_digests[$snapshot_index]}" ]] || return 1
        snapshot_index=$((snapshot_index + 1))
    done
}

test_kit_snapshot="$temporary_directory/test-kit.jar"
stage_result_snapshot="$temporary_directory/stage-result.json"
snapshot_file "$test_kit_jar" "$test_kit_snapshot" || exit 2
snapshot_file "$stage_result" "$stage_result_snapshot" || exit 2
snapshot_stage_size=$(wc -c < "$stage_result_snapshot" 2>/dev/null \
    | tr -d '[:space:]') || exit 2
[[ "$snapshot_stage_size" =~ ^[0-9]+$ \
    && "$snapshot_stage_size" -le "$MAX_STAGE_RESULT_BYTES" ]] || exit 2

provider_snapshot_classpath=''
provider_index=0
snapshot_files=("$test_kit_snapshot" "$stage_result_snapshot")
for provider_entry in "${provider_entries[@]}"; do
    provider_snapshot="$temporary_directory/provider-$provider_index.entry"
    snapshot_file "$provider_entry" "$provider_snapshot" || exit 2
    if [[ -z "$provider_snapshot_classpath" ]]; then
        provider_snapshot_classpath="$provider_snapshot"
    else
        provider_snapshot_classpath="$provider_snapshot_classpath:$provider_snapshot"
    fi
    snapshot_files+=("$provider_snapshot")
    provider_index=$((provider_index + 1))
done

snapshot_digests=()
for snapshot_file_path in "${snapshot_files[@]}"; do
    snapshot_digest=$(sha256_file "$snapshot_file_path") || exit 2
    snapshot_digests+=("$snapshot_digest")
done

classpath="$test_kit_snapshot:$provider_snapshot_classpath"
conformance_stdout="$temporary_directory/conformance.stdout"
conformance_stderr="$temporary_directory/conformance.stderr"
formal_stdout="$temporary_directory/formal.stdout"
formal_stderr="$temporary_directory/formal.stderr"

run_child "$conformance_stdout" "$conformance_stderr" \
    "$java_bin" -cp "$classpath" "$CONFORMANCE_MAIN" \
    --result "$stage_result_snapshot" --output "$conformance_output"
conformance_exit=$?
snapshots_unchanged || exit 2

if [[ "$conformance_exit" -ne 0 ]]; then
    [[ "$conformance_exit" -eq 3 ]] && exit 3
    exit 2
fi

conformance_line=$(sed -n '1p' "$conformance_stdout" 2>/dev/null) || exit 2
conformance_lines=$(wc -l < "$conformance_stdout" 2>/dev/null) || exit 2
conformance_last_byte=$(tail -c 1 "$conformance_stdout" 2>/dev/null | od -An -t x1 | tr -d '[:space:]') || exit 2
[[ "$conformance_lines" -eq 1 && "$conformance_last_byte" == '0a' ]] || exit 2
if [[ "$conformance_line" =~ ^CONFORMANT[[:space:]]verdict=CONFORMANT[[:space:]]checkCount=6[[:space:]]challengeCount=([1-9][0-9]*)[[:space:]]reportFingerprint=(sha256:[0-9a-f]{64})$ ]]; then
    provider_conformance_fingerprint="${BASH_REMATCH[2]}"
else
    exit 2
fi

regular_readable_file "$conformance_output" || exit 2
conformance_output_size=$(wc -c < "$conformance_output" 2>/dev/null | tr -d '[:space:]') || exit 2
[[ "$conformance_output_size" =~ ^[0-9]+$ \
    && "$conformance_output_size" -gt 0 \
    && "$conformance_output_size" -le "$MAX_CONFORMANCE_OUTPUT_BYTES" ]] || exit 2

run_child "$formal_stdout" "$formal_stderr" \
    "$java_bin" -cp "$classpath" "$FORMAL_MAIN" "$stage_result_snapshot"
formal_exit=$?
snapshots_unchanged || exit 2

if [[ "$formal_exit" -ne 0 ]]; then
    [[ "$formal_exit" -eq 3 ]] && exit 3
    exit 2
fi

formal_line=$(sed -n '1p' "$formal_stdout" 2>/dev/null) || exit 2
formal_lines=$(wc -l < "$formal_stdout" 2>/dev/null) || exit 2
formal_last_byte=$(tail -c 1 "$formal_stdout" 2>/dev/null | od -An -t x1 | tr -d '[:space:]') || exit 2
[[ "$formal_lines" -eq 1 && "$formal_last_byte" == '0a' ]] || exit 2
[[ "$formal_line" =~ ^ACCEPTED[[:space:]]outcome=ACCEPTED[[:space:]]reasonCode=RG\.CAPABILITY_STUDIO\.STAGE_ACCEPTANCE_AUTHORITY\.[A-Z0-9][A-Z0-9_.-]{0,254}$ ]] || exit 2

printf 'ACCEPTED status=ACCEPTED providerConformanceFingerprint=%s\n' \
    "$provider_conformance_fingerprint"
exit 0
