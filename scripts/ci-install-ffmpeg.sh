#!/bin/bash
# ============================================================================
# Installs ffmpeg on the CI runner for the backend job (RealToolsTest needs ffmpeg and ffprobe).
#
# Usage: ci-install-ffmpeg.sh        (FFMPEG_INSTALL_LIMIT=seconds, default 300)
#
# WHY THIS IS NOT JUST `apt-get update && apt-get install`
#
# On 2026-11-11 the runners could not resolve archive.ubuntu.com, azure.archive.ubuntu.com or
# security.ubuntu.com. A single `apt-get update` then took about six minutes, roughly 20 s of DNS wait
# per mirror entry, because Acquire::*::Timeout does not cover name resolution. Retrying cannot help
# (one attempt already costs more than the budget) and the bare step sat until the job's 20-minute
# timeout cancelled it, with no message.
#
# So there is ONE overall budget, tracked with $SECONDS and enforced with `timeout --kill-after=10` on
# each apt-get (TERM at the deadline, then KILL ten seconds later if apt or sudo ignores TERM).
# When it runs out the script prints a clear ::error:: and exits 1, before the workflow step's own
# timeout-minutes (a backstop set above this budget) can kill it silently.
# ============================================================================
set -uo pipefail

limit="${FFMPEG_INSTALL_LIMIT:-300}"
opts=(-o Acquire::Retries=3 -o Acquire::http::Timeout=20 -o Acquire::https::Timeout=20)

timeout_error() {
    echo "::error::Installing ffmpeg from the Ubuntu apt mirrors took longer than ${limit}s (mirror or DNS outage on the runner?). Re-run the job once archive.ubuntu.com resolves."
    exit 1
}

# run_apt SUBCOMMAND ARGS...: apt-get under the remaining budget; exits 1 with a clear error on failure.
run_apt() {
    local sub="$1" remaining rc
    remaining=$(( limit - SECONDS ))
    if [ "$remaining" -le 0 ]; then
        timeout_error
    fi
    timeout --kill-after=10 "$remaining" sudo apt-get "${opts[@]}" "$@"
    rc=$?
    # 124: timeout's TERM stopped it. 137 (128+9): it ignored TERM and --kill-after's KILL had to end it.
    if [ "$rc" -eq 124 ] || [ "$rc" -eq 137 ]; then
        timeout_error
    elif [ "$rc" -ne 0 ]; then
        echo "::error::apt-get ${sub} failed with exit ${rc} while installing ffmpeg. Re-run the job; if it persists, check the Ubuntu mirrors."
        exit 1
    fi
}

run_apt update
run_apt install -y --no-install-recommends ffmpeg

ffprobe -version | head -1
exit 0
