#!/bin/bash
# Tests scripts/ci-install-ffmpeg.sh against stub sudo, apt-get, timeout and ffprobe on PATH: no network,
# no apt, no real waiting. The stubs work on macOS too (no GNU timeout there, which is why it is stubbed).
#   apt-get  logs its args; exits with $STUB_DIR/rc.<subcommand> (default 0); a download writes one .deb into
#            the Dir::Cache::archives dir it was given, as a download would
#   timeout  logs its limit; exits 124 without running when $STUB_DIR/hang.<subcommand> exists (every call) or
#            hang.<subcommand>.<n> exists (only the n-th call); a hung download first leaves one .deb behind.
#   <subcommand> is update, download (install --download-only), install or autoclean

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
        update|install|autoclean) sub="$a" ;;
    esac
done
for a in "$@"; do
    if [ "$a" = "--download-only" ]; then sub=download; fi
done
echo "$*" >> "$STUB_DIR/apt-calls"
echo "$sub" >> "$STUB_DIR/apt-subs"
if [ "$sub" = download ] && [ ! -e "$STUB_DIR/nodownload" ]; then
    for a in "$@"; do
        case "$a" in
            Dir::Cache::archives=*) touch "${a#Dir::Cache::archives=}/downloaded-$(wc -l < "$STUB_DIR/apt-subs" | tr -d ' ').deb" ;;
        esac
    done
fi
if [ "$sub" = autoclean ] && [ -e "$STUB_DIR/autoclean-removes" ]; then rm -f "$FFMPEG_DEB_DIR/stale.deb"; fi
exit "$(cat "$STUB_DIR/rc.$sub" 2>/dev/null || echo 0)"
STUB
    cat > "$STUB_DIR/bin/timeout" <<'STUB'
#!/bin/bash
echo "$1" >> "$STUB_DIR/timeout-kill-args"
shift
limit="$1"
shift
echo "$limit" >> "$STUB_DIR/timeout-limits"
kind=""
for a in "$@"; do
    case "$a" in
        update|install|autoclean) [ -n "$kind" ] || kind="$a" ;;
        --download-only) kind=download ;;
    esac
done
for a in "$kind"; do
    case "$a" in
        update|install|download|autoclean)
            echo "$a" >> "$STUB_DIR/timeout-subs"
            n=$(grep -cFx "$a" "$STUB_DIR/timeout-subs")
            for f in "$STUB_DIR/hang.$a" "$STUB_DIR/hang.$a.$n"; do
                if [ -e "$f" ]; then
                    if [ "$a" = download ]; then touch "$FFMPEG_DEB_DIR/stalled-$n.deb"; fi
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
assert_eq "update, download, install, autoclean" "update download install autoclean" "$SUBS"
assert_file_has "passes the retry option" "$STUB_DIR/apt-calls" "Acquire::Retries=3"
assert_file_has "passes the http timeout" "$STUB_DIR/apt-calls" "Acquire::http::Timeout=20"
assert_file_has "passes the https timeout" "$STUB_DIR/apt-calls" "Acquire::https::Timeout=20"
assert_file_has "waits for the dpkg lock" "$STUB_DIR/apt-calls" "DPkg::Lock::Timeout=60"
assert_file_has "downloads ffmpeg without recommends" "$STUB_DIR/apt-calls" "install --download-only -y --no-install-recommends ffmpeg"
assert_file_has "installs ffmpeg without recommends" "$STUB_DIR/apt-calls" "install -y --no-install-recommends ffmpeg"
assert_eq "every apt-get gets the cache dir" 4 "$(grep -cF -- "Dir::Cache::archives=$STUB_DIR/debs" "$STUB_DIR/apt-calls")"
assert_file_exists "creates the cache dir's partial/ without sudo" "$STUB_DIR/debs/partial"
LIMIT_OK=$(awk '$1 ~ /^[0-9]+$/ && $1 > 0 && $1 <= 720 { n++ } END { print n + 0 }' "$STUB_DIR/timeout-limits")
assert_eq "all four apt-gets run under a positive timeout within the overall limit" 4 "$LIMIT_OK"
assert_eq "update is capped at 300" 300 "$(sed -n 1p "$STUB_DIR/timeout-limits")"
assert_eq "the download is capped at 240" 240 "$(sed -n 2p "$STUB_DIR/timeout-limits")"
INSTALL_BUDGET=$(sed -n 3p "$STUB_DIR/timeout-limits")
assert_eq "the install gets the rest of the overall budget, not the download cap" 1 "$(awk -v b="$INSTALL_BUDGET" 'BEGIN { print (b >= 715 && b <= 720) ? 1 : 0 }')"
assert_eq "autoclean is capped at 60" 60 "$(sed -n 4p "$STUB_DIR/timeout-limits")"

KILL_OK=$(grep -cFx -- "--kill-after=10" "$STUB_DIR/timeout-kill-args")
assert_eq "every apt-get gets --kill-after=10" 4 "$KILL_OK"
assert_contains "notices the cache miss" "$OUTPUT" "::notice::ffmpeg deb cache miss: 0 .deb files already present"
assert_contains "notices the download" "$OUTPUT" "1 new .deb files downloaded"
assert_not_contains "no retry warning" "$OUTPUT" "::warning::"
assert_file_has "reports the change for the cache save" "$STUB_DIR/step-output" "debs_changed=true"

echo "cache hit"
make_stubs
mkdir -p "$STUB_DIR/debs"
touch "$STUB_DIR/debs/a.deb" "$STUB_DIR/debs/b.deb"
FFMPEG_CACHE_MATCHED="ffmpeg-debs-Linux-X64-123-1"
export FFMPEG_CACHE_MATCHED
run_install
assert_eq "exits 0" 0 "$RC"
assert_contains "notices the hit with its key and the count" "$OUTPUT" "::notice::ffmpeg deb cache hit (ffmpeg-debs-Linux-X64-123-1): 2 .deb files already present"
assert_file_has "a new deb is a change" "$STUB_DIR/step-output" "debs_changed=true"

echo "a full cache changes nothing"
make_stubs
mkdir -p "$STUB_DIR/debs"
touch "$STUB_DIR/debs/a.deb" "$STUB_DIR/nodownload"
run_install
assert_eq "exits 0" 0 "$RC"
assert_contains "no new debs" "$OUTPUT" "0 new .deb files downloaded, 1 in the cache dir"
assert_file_has "nothing to save" "$STUB_DIR/step-output" "debs_changed=false"

echo "autoclean swaps a stale version out"
make_stubs
mkdir -p "$STUB_DIR/debs"
touch "$STUB_DIR/debs/stale.deb" "$STUB_DIR/nodownload" "$STUB_DIR/autoclean-removes"
run_install
assert_eq "exits 0" 0 "$RC"
assert_file_missing "the stale deb is gone" "$STUB_DIR/debs/stale.deb"
assert_file_has "a removal is a change" "$STUB_DIR/step-output" "debs_changed=true"

echo "autoclean fails"
make_stubs
echo 1 > "$STUB_DIR/rc.autoclean"
run_install
assert_eq "still exits 0" 0 "$RC"
assert_contains "warns only" "$OUTPUT" "::warning::apt-get autoclean failed with exit 1"
assert_contains "still reaches ffprobe" "$OUTPUT" "ffprobe version"

echo "update hangs"
make_stubs
touch "$STUB_DIR/hang.update"
run_install
assert_eq "exits 1" 1 "$RC"
assert_contains "names the timeout with the update cap" "$OUTPUT" "::error::Installing ffmpeg from the Ubuntu apt mirrors took longer than 300s"
assert_file_missing "apt-get never ran" "$STUB_DIR/apt-calls"
assert_not_contains "never reaches ffprobe" "$OUTPUT" "ffprobe version"
assert_eq "update is not retried" 1 "$(grep -c . "$STUB_DIR/timeout-limits")"

echo "download hangs on the first attempt only"
make_stubs
touch "$STUB_DIR/hang.download.1"
run_install
assert_eq "exits 0 after the retry" 0 "$RC"
assert_contains "warns about the retry" "$OUTPUT" "::warning::apt-get download did not finish (exit 124) with 1 .deb files downloaded; retrying once"
assert_eq "update, the retried download, install, autoclean" "update download install autoclean" "$SUBS"
assert_eq "the first attempt got the 240 s cap" 240 "$(sed -n 2p "$STUB_DIR/timeout-limits")"
RETRY_BUDGET=$(sed -n 3p "$STUB_DIR/timeout-limits")
assert_eq "the retry ends the install reserve (120 s) before the 720 s limit" 1 "$(awk -v b="$RETRY_BUDGET" 'BEGIN { print (b >= 595 && b <= 600) ? 1 : 0 }')"
INSTALL_AFTER_RETRY=$(sed -n 4p "$STUB_DIR/timeout-limits")
assert_eq "the install after a hung first download keeps at least the reserve" 1 "$(awk -v b="$INSTALL_AFTER_RETRY" 'BEGIN { print (b >= 120 && b <= 720) ? 1 : 0 }')"
assert_eq "five apt-gets ran under a timeout" 5 "$(grep -c . "$STUB_DIR/timeout-limits")"
assert_file_has "the debs kept from the first attempt count as a change" "$STUB_DIR/step-output" "debs_changed=true"

echo "download hangs on both attempts"
make_stubs
touch "$STUB_DIR/hang.download"
run_install
assert_eq "exits 1" 1 "$RC"
assert_contains "names the timeout with the overall limit" "$OUTPUT" "::error::Installing ffmpeg from the Ubuntu apt mirrors took longer than 720s"
assert_eq "update and exactly two download attempts ran under a timeout" 3 "$(grep -c . "$STUB_DIR/timeout-limits")"
assert_eq "the install never ran" 0 "$(grep -cFx install "$STUB_DIR/timeout-subs")"
assert_file_has "still reports the partial download for the cache save" "$STUB_DIR/step-output" "debs_changed=true"

echo "update ignores TERM and is killed (exit 137)"
make_stubs
echo 137 > "$STUB_DIR/hang.update"
run_install
assert_eq "exits 1" 1 "$RC"
assert_contains "reported as the timeout" "$OUTPUT" "::error::Installing ffmpeg from the Ubuntu apt mirrors took longer than 300s"
assert_file_missing "download never ran" "$STUB_DIR/apt-calls"

echo "download is killed on its first attempt (exit 137)"
make_stubs
echo 137 > "$STUB_DIR/hang.download.1"
run_install
assert_eq "retried like a timeout, exits 0" 0 "$RC"
assert_contains "warns about the retry as a timeout" "$OUTPUT" "(exit 124)"

echo "download gets an apt error (exit 100) on both attempts"
make_stubs
echo 100 > "$STUB_DIR/rc.download"
run_install
assert_eq "exits 1" 1 "$RC"
assert_contains "reports the exit code" "$OUTPUT" "::error::apt-get install --download-only failed with exit 100 while installing ffmpeg"
assert_not_contains "is not reported as a timeout" "$OUTPUT" "took longer than"
assert_eq "update once, download twice, no install" "update download download" "$SUBS"

echo "download fails with another code"
make_stubs
echo 1 > "$STUB_DIR/rc.download"
run_install
assert_eq "exits 1" 1 "$RC"
assert_eq "no retry" "update download" "$SUBS"
assert_not_contains "no retry warning" "$OUTPUT" "::warning::"

echo "the install itself is never retried"
make_stubs
echo 100 > "$STUB_DIR/rc.install"
run_install
assert_eq "exits 1" 1 "$RC"
assert_contains "reports the install exit code" "$OUTPUT" "::error::apt-get install failed with exit 100 while installing ffmpeg"
assert_eq "one download, one install, no autoclean" "update download install" "$SUBS"
assert_not_contains "no retry warning" "$OUTPUT" "::warning::"

echo "the install hangs"
make_stubs
touch "$STUB_DIR/hang.install"
run_install
assert_eq "exits 1" 1 "$RC"
assert_contains "names the timeout with the overall limit" "$OUTPUT" "took longer than 720s"
assert_eq "the install ran under a timeout once" 1 "$(grep -cFx install "$STUB_DIR/timeout-subs")"

echo "update fails"
make_stubs
echo 100 > "$STUB_DIR/rc.update"
run_install
assert_eq "exits 1" 1 "$RC"
assert_contains "reports the update exit code" "$OUTPUT" "::error::apt-get update failed with exit 100 while installing ffmpeg"
assert_eq "nothing else was called" "update" "$SUBS"

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
assert_eq "the download cap is configurable" 50 "$(sed -n 2p "$STUB_DIR/timeout-limits")"

echo "FFMPEG_INSTALL_RESERVE is honoured"
make_stubs
touch "$STUB_DIR/hang.download.1"
FFMPEG_INSTALL_RESERVE=300
export FFMPEG_INSTALL_RESERVE
run_install
assert_eq "exits 0 after the retry" 0 "$RC"
assert_eq "the retry ends 300 s before the limit" 1 "$(awk -v b="$(sed -n 3p "$STUB_DIR/timeout-limits")" 'BEGIN { print (b >= 415 && b <= 420) ? 1 : 0 }')"
unset FFMPEG_INSTALL_RESERVE

make_stubs
touch "$STUB_DIR/hang.download.1"
FFMPEG_INSTALL_LIMIT=140
export FFMPEG_INSTALL_LIMIT
run_install
assert_eq "no room for a retry plus the reserve: fails after the first download" 1 "$RC"
assert_eq "update and one download only" "update" "$SUBS"
unset FFMPEG_INSTALL_LIMIT

echo "a retry is skipped when the budget is nearly spent"
make_stubs
touch "$STUB_DIR/hang.download"
FFMPEG_INSTALL_LIMIT=20
export FFMPEG_INSTALL_LIMIT
run_install
assert_eq "exits 1" 1 "$RC"
assert_contains "names the timeout" "$OUTPUT" "took longer than 20s"
assert_eq "only update and one download ran under a timeout" 2 "$(grep -c . "$STUB_DIR/timeout-limits")"
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
