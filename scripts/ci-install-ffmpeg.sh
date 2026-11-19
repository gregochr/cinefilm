#!/bin/bash
# ============================================================================
# Installs ffmpeg on the CI runner for the backend job (RealToolsTest needs ffmpeg and ffprobe).
#
# Usage: ci-install-ffmpeg.sh
#   FFMPEG_INSTALL_LIMIT   overall budget in seconds, default 720
#   FFMPEG_UPDATE_LIMIT    cap for `apt-get update` alone, default 300
#   FFMPEG_ATTEMPT_LIMIT   cap for the first `apt-get install`, default 240 (the retry gets what is left)
#   FFMPEG_DEB_DIR         apt's archive dir, default $RUNNER_TEMP/ffmpeg-debs (cached between runs by ci.yml)
#   FFMPEG_CACHE_MATCHED   the restored cache key, for the notice (empty = cache miss)
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
#
# On 2026-11-19 the opposite happened: DNS was fine and `apt-get update` worked, but one slow mirror stalled
# single packages of the 66 MB install until the budget ran out, four runs in a row. So the downloaded .debs
# live in a user-owned dir (FFMPEG_DEB_DIR) that the workflow caches between runs, and a first install that
# times out or fails to fetch (exit 100) is retried once with the rest of the budget: apt keeps every
# completed .deb, so the retry only fetches what is missing. `update` is still capped on its own, so the
# DNS outage still fails fast with the same message.
# ============================================================================
set -uo pipefail

limit="${FFMPEG_INSTALL_LIMIT:-720}"
update_limit="${FFMPEG_UPDATE_LIMIT:-300}"
attempt_limit="${FFMPEG_ATTEMPT_LIMIT:-240}"
if [[ "$update_limit" -gt "$limit" ]]; then
    update_limit="$limit"
fi
deb_dir="${FFMPEG_DEB_DIR:-${RUNNER_TEMP:-/tmp}/ffmpeg-debs}"
opts=(-o Acquire::Retries=3 -o Acquire::http::Timeout=20 -o Acquire::https::Timeout=20
      -o "Dir::Cache::archives=${deb_dir}")

# The dir and its partial/ must exist and belong to the runner user (no sudo), so actions/cache can read it.
mkdir -p "${deb_dir}/partial"

count_debs() {
    find "${deb_dir}" -maxdepth 1 -name '*.deb' | wc -l | tr -d ' '
}

before=$(count_debs)

# On every exit, including the error ones, say how many .debs are new so the workflow can save a partial
# download: completed .debs only ever sit in the dir itself (in-flight ones stay in partial/).
# shellcheck disable=SC2329  # invoked by the EXIT trap below
write_output() {
    if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
        echo "new_debs=$(( $(count_debs) - before ))" >> "$GITHUB_OUTPUT"
    fi
}
trap write_output EXIT

timeout_error() {
    echo "::error::Installing ffmpeg from the Ubuntu apt mirrors took longer than ${1}s (mirror or DNS outage on the runner?). Re-run the job once archive.ubuntu.com resolves."
    exit 1
}

# run_apt CAP ARGS...: apt-get under min(CAP, remaining budget). Returns 0, 124 (budget spent: TERM or KILL)
# or apt's own exit code; the caller decides what a failure means.
run_apt() {
    local cap="$1" remaining budget rc
    shift
    remaining=$(( limit - SECONDS ))
    if [[ "$remaining" -le 0 ]]; then
        return 124
    fi
    budget=$(( cap < remaining ? cap : remaining ))
    timeout --kill-after=10 "$budget" sudo apt-get "${opts[@]}" "$@"
    rc=$?
    # 137 (128+9): it ignored TERM and --kill-after's KILL had to end it; same meaning as 124.
    if [[ "$rc" -eq 137 ]]; then
        rc=124
    fi
    return "$rc"
}

# fail SUBCOMMAND RC BUDGET: the clear ::error:: and exit 1.
fail() {
    if [[ "$2" -eq 124 ]]; then
        timeout_error "$3"
    fi
    echo "::error::apt-get ${1} failed with exit ${2} while installing ffmpeg. Re-run the job; if it persists, check the Ubuntu mirrors."
    exit 1
}

if [[ -n "${FFMPEG_CACHE_MATCHED:-}" ]]; then
    echo "::notice::ffmpeg deb cache hit (${FFMPEG_CACHE_MATCHED}): ${before} .deb files already present"
else
    echo "::notice::ffmpeg deb cache miss: ${before} .deb files already present"
fi

run_apt "$update_limit" update
rc=$?
[[ "$rc" -eq 0 ]] || fail update "$rc" "$update_limit"

run_apt "$attempt_limit" install -y --no-install-recommends ffmpeg
rc=$?
# Retry once after a timeout or apt's fetch-failure exit (100), if enough budget is left to matter.
if [[ "$rc" -eq 124 || "$rc" -eq 100 ]] && [[ $(( limit - SECONDS )) -gt 30 ]]; then
    echo "::warning::apt-get install did not finish (exit ${rc}) with $(count_debs) .deb files downloaded; retrying once with the remaining budget"
    run_apt "$limit" install -y --no-install-recommends ffmpeg
    rc=$?
fi
[[ "$rc" -eq 0 ]] || fail install "$rc" "$limit"

echo "::notice::ffmpeg installed: $(( $(count_debs) - before )) new .deb files downloaded, $(count_debs) in the cache dir"

ffprobe -version | head -1
exit 0
