#!/bin/bash
# Tests scripts/ci-install-ffmpeg.sh against stub sudo, apt-get, timeout and ffprobe on PATH: no network,
# no apt, no real waiting. The stubs work on macOS too (no GNU timeout there, which is why it is stubbed).
#   apt-get  logs its args; exits with $STUB_DIR/rc.<subcommand> (default 0); an install writes one .deb into
#            the Dir::Cache::archives dir it was given, as a download would
#   timeout  logs its limit; exits 124 without running when $STUB_DIR/hang.<subcommand> exists (every call) or
#            hang.<subcommand>.<n> exists (only the n-th call); a hung install first leaves one .deb behind

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=/dev/null
. "$HERE/helpers.sh"
SCRIPT="$HERE/../ci-install-ffmpeg.sh"

make_stubs() {
    STUB_DIR=$(mktemp -d)
    export STUB_DIR
    mkdir -p "$STUB_DIR/bin"
    FFMPEG_DEB_DIR="$STUB_DIR/debs"
    GITHUB_OUTPUT="$STUB_DIR/step-output"
    export FFMPEG_DEB_DIR GITHUB_OUTPUT
    unset FFMPEG_INSTALL_LIMIT FFMPEG_UPDATE_LIMIT FFMPEG_ATTEMPT_LIMIT FFMPEG_CACHE_MATCHED
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
if [ "$sub" = install ]; then
    for a in "$@"; do
        case "$a" in
            Dir::Cache::archives=*) touch "${a#Dir::Cache::archives=}/downloaded-$(wc -l < "$STUB_DIR/apt-subs" | tr -d ' ').deb" ;;
        esac
    done
fi
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
            echo "$a" >> "$STUB_DIR/timeout-subs"
            n=$(grep -cFx "$a" "$STUB_DIR/timeout-subs")
            for f in "$STUB_DIR/hang.$a" "$STUB_DIR/hang.$a.$n"; do
                if [ -e "$f" ]; then
                    if [ "$a" = install ]; then touch "$FFMPEG_DEB_DIR/stalled-$n.deb"; fi
                    code=$(cat "$f")
                    exit "${code:-124}"
                fi
            done
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
assert_file_has "points apt at the cache dir" "$STUB_DIR/apt-calls" "Dir::Cache::archives=$STUB_DIR/debs"
assert_file_exists "creates the cache dir's partial/ without sudo" "$STUB_DIR/debs/partial"
LIMIT_OK=$(awk '$1 ~ /^[0-9]+$/ && $1 > 0 && $1 <= 300 { n++ } END { print n + 0 }' "$STUB_DIR/timeout-limits")
assert_eq "both apt-gets run under a timeout of at most the default caps" 2 "$LIMIT_OK"

KILL_OK=$(grep -cFx -- "--kill-after=10" "$STUB_DIR/timeout-kill-args")
assert_eq "both apt-gets get --kill-after=10" 2 "$KILL_OK"
assert_contains "notices the cache miss" "$OUTPUT" "::notice::ffmpeg deb cache miss: 0 .deb files already present"
assert_contains "notices the download" "$OUTPUT" "1 new .deb files downloaded"
assert_not_contains "no retry warning" "$OUTPUT" "::warning::"
assert_file_has "reports new debs for the cache save" "$STUB_DIR/step-output" "new_debs=1"

echo "cache hit"
make_stubs
mkdir -p "$STUB_DIR/debs"
touch "$STUB_DIR/debs/a.deb" "$STUB_DIR/debs/b.deb"
FFMPEG_CACHE_MATCHED="ffmpeg-debs-Linux-X64-123"
export FFMPEG_CACHE_MATCHED
run_install
assert_eq "exits 0" 0 "$RC"
assert_contains "notices the hit with its key and the count" "$OUTPUT" "::notice::ffmpeg deb cache hit (ffmpeg-debs-Linux-X64-123): 2 .deb files already present"
assert_file_has "counts only what is new" "$STUB_DIR/step-output" "new_debs=1"

echo "update hangs"
make_stubs
touch "$STUB_DIR/hang.update"
run_install
assert_eq "exits 1" 1 "$RC"
assert_contains "names the timeout with the update cap" "$OUTPUT" "::error::Installing ffmpeg from the Ubuntu apt mirrors took longer than 300s"
assert_file_missing "apt-get never ran" "$STUB_DIR/apt-calls"
assert_not_contains "never reaches ffprobe" "$OUTPUT" "ffprobe version"
assert_eq "update is not retried" 1 "$(grep -c . "$STUB_DIR/timeout-limits")"

echo "install hangs on the first attempt only"
make_stubs
touch "$STUB_DIR/hang.install.1"
run_install
assert_eq "exits 0 after the retry" 0 "$RC"
assert_contains "warns about the retry" "$OUTPUT" "::warning::apt-get install did not finish (exit 124) with 1 .deb files downloaded; retrying once"
assert_eq "update, then the install that ran on the retry" "update install" "$SUBS"
assert_eq "the first attempt got the 240 s cap" 240 "$(sed -n 2p "$STUB_DIR/timeout-limits")"
RETRY_BUDGET=$(sed -n 3p "$STUB_DIR/timeout-limits")
assert_eq "the retry got the rest of the 720 s budget" 1 "$(awk -v b="$RETRY_BUDGET" 'BEGIN { print (b > 240 && b <= 720) ? 1 : 0 }')"
assert_file_has "the debs kept from the first attempt count as new" "$STUB_DIR/step-output" "new_debs=2"

echo "install hangs on both attempts"
make_stubs
touch "$STUB_DIR/hang.install"
run_install
assert_eq "exits 1" 1 "$RC"
assert_contains "names the timeout with the overall limit" "$OUTPUT" "::error::Installing ffmpeg from the Ubuntu apt mirrors took longer than 720s"
assert_eq "update and exactly two install attempts ran under a timeout" 3 "$(grep -c . "$STUB_DIR/timeout-limits")"
assert_file_has "still reports the partial download for the cache save" "$STUB_DIR/step-output" "new_debs=2"

echo "update ignores TERM and is killed (exit 137)"
make_stubs
echo 137 > "$STUB_DIR/hang.update"
run_install
assert_eq "exits 1" 1 "$RC"
assert_contains "reported as the timeout" "$OUTPUT" "::error::Installing ffmpeg from the Ubuntu apt mirrors took longer than 300s"
assert_file_missing "install never ran" "$STUB_DIR/apt-calls"

echo "install is killed on its first attempt (exit 137)"
make_stubs
echo 137 > "$STUB_DIR/hang.install.1"
run_install
assert_eq "retried like a timeout, exits 0" 0 "$RC"
assert_contains "warns about the retry as a timeout" "$OUTPUT" "(exit 124)"

echo "install fails to fetch (apt exit 100) on both attempts"
make_stubs
echo 100 > "$STUB_DIR/rc.install"
run_install
assert_eq "exits 1" 1 "$RC"
assert_contains "reports the install exit code" "$OUTPUT" "::error::apt-get install failed with exit 100 while installing ffmpeg"
assert_not_contains "is not reported as a timeout" "$OUTPUT" "took longer than"
assert_eq "update once, install twice" "update install install" "$SUBS"

echo "install fails with another code"
make_stubs
echo 1 > "$STUB_DIR/rc.install"
run_install
assert_eq "exits 1" 1 "$RC"
assert_contains "reports the install exit code" "$OUTPUT" "::error::apt-get install failed with exit 1 while installing ffmpeg"
assert_eq "no retry" "update install" "$SUBS"
assert_not_contains "no retry warning" "$OUTPUT" "::warning::"

echo "update fails"
make_stubs
echo 100 > "$STUB_DIR/rc.update"
run_install
assert_eq "exits 1" 1 "$RC"
assert_contains "reports the update exit code" "$OUTPUT" "::error::apt-get update failed with exit 100 while installing ffmpeg"
assert_eq "install was not called" "update" "$SUBS"

echo "the budget variables are honoured"
make_stubs
touch "$STUB_DIR/hang.update"
FFMPEG_INSTALL_LIMIT=42
export FFMPEG_INSTALL_LIMIT
run_install
assert_eq "exits 1" 1 "$RC"
assert_contains "message carries the custom limit" "$OUTPUT" "took longer than 42s"
CUSTOM_OK=$(awk '$1 ~ /^[0-9]+$/ && $1 > 0 && $1 <= 42 { n++ } END { print n + 0 }' "$STUB_DIR/timeout-limits")
assert_eq "timeout was given a positive limit of at most the custom budget" 1 "$CUSTOM_OK"

make_stubs
FFMPEG_ATTEMPT_LIMIT=50
export FFMPEG_ATTEMPT_LIMIT
run_install
assert_eq "the install attempt cap is configurable" 50 "$(sed -n 2p "$STUB_DIR/timeout-limits")"

echo "a retry is skipped when the budget is nearly spent"
make_stubs
touch "$STUB_DIR/hang.install"
FFMPEG_INSTALL_LIMIT=20
export FFMPEG_INSTALL_LIMIT
run_install
assert_eq "exits 1" 1 "$RC"
assert_contains "names the timeout" "$OUTPUT" "took longer than 20s"
assert_eq "only update and one install ran under a timeout" 2 "$(grep -c . "$STUB_DIR/timeout-limits")"
assert_not_contains "no retry warning" "$OUTPUT" "::warning::"

echo "an exhausted budget is a timeout without running apt"
make_stubs
FFMPEG_INSTALL_LIMIT=0
export FFMPEG_INSTALL_LIMIT
run_install
assert_eq "exits 1" 1 "$RC"
assert_contains "names the timeout" "$OUTPUT" "took longer than 0s"
assert_file_missing "apt-get never ran" "$STUB_DIR/apt-calls"

finish_tests
