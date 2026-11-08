#!/bin/bash
# Tests scripts/npm-audit.sh against stub npx and sleep on PATH: no network, no npm, no waiting.
# The stub npx answers `--version`, and for `audit` replays response N (out.N, rc.N) from $STUB_DIR.

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=/dev/null
. "$HERE/helpers.sh"
SCRIPT="$HERE/../npm-audit.sh"

make_stubs() {
    STUB_DIR=$(mktemp -d)
    export STUB_DIR
    mkdir -p "$STUB_DIR/bin" "$STUB_DIR/frontend"
    cat > "$STUB_DIR/bin/npx" <<'STUB'
#!/bin/bash
for a in "$@"; do
    if [ "$a" = "--version" ]; then echo "11.0.0"; exit 0; fi
done
echo "$*" >> "$STUB_DIR/npx-calls"
n=$(( $(cat "$STUB_DIR/count" 2>/dev/null || echo 0) + 1 ))
echo "$n" > "$STUB_DIR/count"
[ -f "$STUB_DIR/out.$n" ] || n=$(ls "$STUB_DIR"/out.* | sed 's/.*out\.//' | sort -n | tail -1)
cat "$STUB_DIR/out.$n"
exit "$(cat "$STUB_DIR/rc.$n")"
STUB
    cat > "$STUB_DIR/bin/npm" <<'STUB'
#!/bin/bash
echo "10.0.0"
STUB
    cat > "$STUB_DIR/bin/sleep" <<'STUB'
#!/bin/bash
echo "$1" >> "$STUB_DIR/sleeps"
STUB
    chmod +x "$STUB_DIR"/bin/*
}

# respond N RC TEXT: what the Nth audit call prints and exits with.
respond() {
    printf '%s\n' "$3" > "$STUB_DIR/out.$1"
    echo "$2" > "$STUB_DIR/rc.$1"
}

run_audit() {
    OUTPUT=$(cd "$STUB_DIR" && PATH="$STUB_DIR/bin:$PATH" bash "$SCRIPT" frontend high 2>&1)
    RC=$?
    CALLS=$(cat "$STUB_DIR/count" 2>/dev/null || echo 0)
}

sleeps() {
    tr '\n' ' ' < "$STUB_DIR/sleeps" | sed 's/ $//'
}

echo "clean audit"
make_stubs
respond 1 0 "found 0 vulnerabilities"
run_audit
assert_eq "exits 0" 0 "$RC"
assert_eq "audits once" 1 "$CALLS"
assert_file_has "passes the requested level" "$STUB_DIR/npx-calls" "audit --audit-level=high"
assert_file_missing "never sleeps" "$STUB_DIR/sleeps"
rm -rf "$STUB_DIR"

echo "vulnerability findings fail without retry"
make_stubs
respond 1 1 "lodash  <4.17.21  Severity: high
1 high severity vulnerability"
run_audit
assert_eq "exits 1" 1 "$RC"
assert_eq "audits once only" 1 "$CALLS"
assert_file_missing "never sleeps" "$STUB_DIR/sleeps"
assert_contains "says it failed on findings" "$OUTPUT" "failing without retry"
rm -rf "$STUB_DIR"

echo "transient transport errors retry with backoff"
make_stubs
respond 1 1 "npm error audit endpoint returned an error"
respond 2 1 "npm error code ETIMEDOUT"
respond 3 0 "found 0 vulnerabilities"
run_audit
assert_eq "exits 0 once the registry answers" 0 "$RC"
assert_eq "audits three times" 3 "$CALLS"
assert_eq "backs off 15s then 30s" "15 30" "$(sleeps)"
assert_contains "warns about the retry" "$OUTPUT" "retrying in 15s"
rm -rf "$STUB_DIR"

echo "a transport error followed by a real finding stops at the finding"
make_stubs
respond 1 1 "npm error ECONNRESET"
respond 2 1 "1 high severity vulnerability"
run_audit
assert_eq "exits 1" 1 "$RC"
assert_eq "audits twice" 2 "$CALLS"
assert_eq "slept once" "15" "$(sleeps)"
rm -rf "$STUB_DIR"

echo "exhausted retries fail"
make_stubs
respond 1 1 "npm error 503 Service Unavailable"
run_audit
assert_eq "exits 1" 1 "$RC"
assert_eq "tries exactly three times" 3 "$CALLS"
assert_eq "sleeps only between attempts" "15 30" "$(sleeps)"
assert_contains "says it is not a clean audit" "$OUTPUT" "NOT a clean audit"
rm -rf "$STUB_DIR"

echo "missing directory"
make_stubs
OUTPUT=$(cd "$STUB_DIR" && PATH="$STUB_DIR/bin:$PATH" bash "$SCRIPT" nope high 2>&1)
assert_eq "exits 2" 2 "$?"
rm -rf "$STUB_DIR"

finish_tests
