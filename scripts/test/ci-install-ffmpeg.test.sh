#!/bin/bash
# Tests scripts/ci-install-ffmpeg.sh against stub sudo, apt-get, timeout and ffprobe on PATH: no network,
# no apt, no real waiting. The stubs work on macOS too (no GNU timeout there, which is why it is stubbed).
#   apt-get  logs its args; exits with $STUB_DIR/rc.<subcommand> (default 0)
#   timeout  logs its limit; exits 124 without running when $STUB_DIR/hang.<subcommand> exists

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=/dev/null
. "$HERE/helpers.sh"
SCRIPT="$HERE/../ci-install-ffmpeg.sh"

make_stubs() {
    STUB_DIR=$(mktemp -d)
    export STUB_DIR
    mkdir -p "$STUB_DIR/bin"
    cat > "$STUB_DIR/bin/sudo" <<'STUB'
#!/bin/bash
exec "$@"
STUB
    cat > "$STUB_DIR/bin/apt-get" <<'STUB'
#!/bin/bash
sub=""
for a in "$@"; do
    case "$a" in
        update|install) sub="$a" ;;
    esac
done
echo "$*" >> "$STUB_DIR/apt-calls"
echo "$sub" >> "$STUB_DIR/apt-subs"
exit "$(cat "$STUB_DIR/rc.$sub" 2>/dev/null || echo 0)"
STUB
    cat > "$STUB_DIR/bin/timeout" <<'STUB'
#!/bin/bash
echo "$1" >> "$STUB_DIR/timeout-kill-args"
shift
limit="$1"
shift
echo "$limit" >> "$STUB_DIR/timeout-limits"
for a in "$@"; do
    case "$a" in
        update|install)
            if [ -e "$STUB_DIR/hang.$a" ]; then
                code=$(cat "$STUB_DIR/hang.$a")
                exit "${code:-124}"
            fi
            break
            ;;
    esac
done
exec "$@"
STUB
    cat > "$STUB_DIR/bin/ffprobe" <<'STUB'
#!/bin/bash
echo "ffprobe version 6.1.1-stub"
echo "second line"
STUB
    chmod +x "$STUB_DIR"/bin/*
}

run_install() {
    OUTPUT=$(PATH="$STUB_DIR/bin:$PATH" bash "$SCRIPT" 2>&1)
    RC=$?
    SUBS=""
    if [ -f "$STUB_DIR/apt-subs" ]; then
        SUBS=$(tr '\n' ' ' < "$STUB_DIR/apt-subs" | sed 's/ $//')
    fi
}

echo "success"
make_stubs
run_install
assert_eq "exits 0" 0 "$RC"
assert_contains "prints the ffprobe version line" "$OUTPUT" "ffprobe version 6.1.1-stub"
assert_not_contains "prints only the first version line" "$OUTPUT" "second line"
assert_eq "update then install" "update install" "$SUBS"
assert_file_has "passes the retry option" "$STUB_DIR/apt-calls" "Acquire::Retries=3"
assert_file_has "passes the http timeout" "$STUB_DIR/apt-calls" "Acquire::http::Timeout=20"
assert_file_has "passes the https timeout" "$STUB_DIR/apt-calls" "Acquire::https::Timeout=20"
assert_file_has "installs ffmpeg without recommends" "$STUB_DIR/apt-calls" "install -y --no-install-recommends ffmpeg"
LIMIT_OK=$(awk '$1 > 0 && $1 <= 300 { n++ } END { print n + 0 }' "$STUB_DIR/timeout-limits")
assert_eq "both apt-gets run under a timeout of at most the default limit" 2 "$LIMIT_OK"

KILL_OK=$(grep -cFx -- "--kill-after=10" "$STUB_DIR/timeout-kill-args")
assert_eq "both apt-gets get --kill-after=10" 2 "$KILL_OK"

echo "update hangs"
make_stubs
touch "$STUB_DIR/hang.update"
run_install
assert_eq "exits 1" 1 "$RC"
assert_contains "names the timeout with the limit" "$OUTPUT" "::error::Installing ffmpeg from the Ubuntu apt mirrors took longer than 300s"
assert_file_missing "apt-get never ran" "$STUB_DIR/apt-calls"
assert_not_contains "never reaches ffprobe" "$OUTPUT" "ffprobe version"

echo "install hangs"
make_stubs
touch "$STUB_DIR/hang.install"
run_install
assert_eq "exits 1" 1 "$RC"
assert_contains "names the timeout with the limit" "$OUTPUT" "took longer than 300s"
assert_eq "update ran, install did not" "update" "$SUBS"

echo "update ignores TERM and is killed (exit 137)"
make_stubs
echo 137 > "$STUB_DIR/hang.update"
run_install
assert_eq "exits 1" 1 "$RC"
assert_contains "reported as the timeout" "$OUTPUT" "::error::Installing ffmpeg from the Ubuntu apt mirrors took longer than 300s"
assert_file_missing "install never ran" "$STUB_DIR/apt-calls"

echo "install fails"
make_stubs
echo 100 > "$STUB_DIR/rc.install"
run_install
assert_eq "exits 1" 1 "$RC"
assert_contains "reports the install exit code" "$OUTPUT" "::error::apt-get install failed with exit 100 while installing ffmpeg"
assert_not_contains "is not reported as a timeout" "$OUTPUT" "took longer than"

echo "update fails"
make_stubs
echo 100 > "$STUB_DIR/rc.update"
run_install
assert_eq "exits 1" 1 "$RC"
assert_contains "reports the update exit code" "$OUTPUT" "::error::apt-get update failed with exit 100 while installing ffmpeg"
assert_eq "install was not called" "update" "$SUBS"

echo "FFMPEG_INSTALL_LIMIT is honoured"
make_stubs
touch "$STUB_DIR/hang.update"
OUTPUT=$(FFMPEG_INSTALL_LIMIT=42 PATH="$STUB_DIR/bin:$PATH" bash "$SCRIPT" 2>&1)
RC=$?
assert_eq "exits 1" 1 "$RC"
assert_contains "message carries the custom limit" "$OUTPUT" "took longer than 42s"
assert_file_has "timeout was given the custom limit" "$STUB_DIR/timeout-limits" "42"

echo "an exhausted budget is a timeout without running apt"
make_stubs
OUTPUT=$(FFMPEG_INSTALL_LIMIT=0 PATH="$STUB_DIR/bin:$PATH" bash "$SCRIPT" 2>&1)
RC=$?
assert_eq "exits 1" 1 "$RC"
assert_contains "names the timeout" "$OUTPUT" "took longer than 0s"
assert_file_missing "apt-get never ran" "$STUB_DIR/apt-calls"

finish_tests
