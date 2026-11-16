#!/bin/bash
# Runs the home replay rig's tests: every scripts/test/replay/*.test.mjs, each with `node --test` (docs/engineering/
# home-replay.md). `node --test` passes when nothing ran (no files, an empty file, a skipped or todo test) and never
# ends when a test file leaves a timer running, so this wrapper:
#   - names the files from a glob and fails when there are none;
#   - gives each file a time budget and kills it when it runs over, which is a failure;
#   - reads each file's TAP summary and fails unless at least one test ran and every one passed (none failed,
#     cancelled, skipped or left to do);
#   - fails unless at least EXPECTED_TESTS ran in all. Raise it when a test is added.
# It fails, and never skips, when Node is missing or older than 22. No browser and no network beyond this machine.

set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=/dev/null
. "${HERE}/helpers.sh"

EXPECTED_TESTS=169
MIN_NODE_MAJOR=22
TEST_TIMEOUT_MS=30000
FILE_BUDGET_S=120

TMP="$(mktemp -d)"
cleanup() {
    rm -rf "$TMP"
}
trap cleanup EXIT
trap 'exit 1' INT TERM HUP

# run_with_budget SECONDS OUTPUT_FILE COMMAND...: runs the command with its output in the file; kills it, and its
# children, and returns 124 when it is still running after SECONDS. Otherwise returns its exit code.
run_with_budget() {
    local budget="$1" out="$2" pid ticks=0
    shift 2
    "$@" >"$out" 2>&1 &
    pid=$!
    while kill -0 "$pid" 2>/dev/null; do
        if [ "$ticks" -ge $((budget * 10)) ]; then
            pkill -P "$pid" 2>/dev/null
            kill "$pid" 2>/dev/null
            wait "$pid" 2>/dev/null
            return 124
        fi
        sleep 0.1
        ticks=$((ticks + 1))
    done
    wait "$pid"
}

# summary NAME FILE: the figure the TAP summary gives for NAME (tests, pass, fail, cancelled, skipped, todo), 0 if none.
summary() {
    local value
    value=$(sed -n "s/^# $1 //p" "$2" | tail -n 1)
    echo "${value:-0}"
}

NODE_MAJOR=""
if command -v node >/dev/null 2>&1; then
    NODE_MAJOR=$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null)
fi
case "$NODE_MAJOR" in
    '' | *[!0-9]*)
        bad "node is installed (the replay rig's tests need Node ${MIN_NODE_MAJOR} or newer)"
        finish_tests
        exit
        ;;
esac
if [ "$NODE_MAJOR" -lt "$MIN_NODE_MAJOR" ]; then
    bad "node is ${MIN_NODE_MAJOR} or newer (this is ${NODE_MAJOR})"
    finish_tests
    exit
fi
ok "node ${NODE_MAJOR} is new enough"

FILES=("${HERE}"/replay/*.test.mjs)
if [ ! -e "${FILES[0]}" ]; then
    bad "scripts/test/replay holds test files"
    finish_tests
    exit
fi
ok "found ${#FILES[@]} test files"

TOTAL=0
for file in "${FILES[@]}"; do
    name=$(basename "$file")
    out="${TMP}/${name}.tap"
    run_with_budget "$FILE_BUDGET_S" "$out" node --test --test-reporter=tap --test-timeout="$TEST_TIMEOUT_MS" "$file"
    rc=$?
    if [ "$rc" -eq 124 ]; then
        bad "${name} ended within ${FILE_BUDGET_S}s (a test left a timer or a socket open?)"
        continue
    fi
    tests=$(summary tests "$out")
    passed=$(summary pass "$out")
    others="$(summary fail "$out") $(summary cancelled "$out") $(summary skipped "$out") $(summary todo "$out")"
    # A file with no test in it is itself reported as one passing test, named by its path: not a test of ours.
    bare=$(grep -c -E "^(not )?ok [0-9]+ - (.*/)?${name}\$" "$out")
    own=$((tests - bare))
    TOTAL=$((TOTAL + own))
    assert_eq "${name} exits 0" 0 "$rc"
    if [ "$own" -ge 1 ]; then ok "${name} ran ${own} tests"; else bad "${name} ran at least one test of its own"; fi
    assert_eq "${name}: every test passed" "$tests" "$passed"
    assert_eq "${name}: none failed, cancelled, skipped or left to do" "0 0 0 0" "$others"
    if [ "$rc" -ne 0 ] || [ "$tests" != "$passed" ]; then
        grep -E '^(not ok|# Subtest)|^ *not ok|error:|expected:|actual:' "$out" | head -n 40
    fi
done

if [ "$TOTAL" -ge "$EXPECTED_TESTS" ]; then
    ok "${TOTAL} tests ran (at least ${EXPECTED_TESTS} expected)"
else
    bad "at least ${EXPECTED_TESTS} tests ran (only ${TOTAL})"
fi

finish_tests
