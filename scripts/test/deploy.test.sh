#!/bin/bash
# Orchestration tests for deploy.sh / pre-release-backup.sh / auto-rollback.sh against a recording stub `docker`.
#
# The stub logs every call to $STUB/calls.log and behaves from flag files in $STUB:
#   db_running       cinefilm-db is up (docker ps lists it)       has_pgdata  the data dir holds PG_VERSION
#   fail_dump        pg_dump (docker exec) fails                  fail_pull   compose pull fails
#   fail_up          compose up -d (the release) fails            fail_dbstart compose up of cinefilm-db fails
#   unhealthy_versions / smoke_bad_versions   versions (one per line) whose container is unhealthy / fails the smoke
# `mountpoint` and `ls /mnt/...` are stubs that only log, so the tests can prove the media mount is only ever probed.

set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
# shellcheck source=/dev/null
. "${HERE}/helpers.sh"

ROOT="$(mktemp -d)"
trap 'rm -rf "$ROOT"' EXIT

# new_world: a fresh stub dir, deploy home with .env, data and backup dirs. Sets STUB, DH, DATA, BK.
new_world() {
    STUB="$(mktemp -d "$ROOT/w.XXXXXX")"
    DH="$STUB/home"; DATA="$STUB/data"; BK="$STUB/backups"
    mkdir -p "$STUB/bin" "$DH" "$DATA"
    : > "$DH/.env"
    : > "$STUB/calls.log"
    echo "v0.1.0" > "$STUB/healthy_dummy"
    cat > "$STUB/bin/docker" <<'EOS'
#!/bin/bash
echo "docker $* [V=${CINEFILM_VERSION:-}]" >> "$STUB/calls.log"
case "$1" in
    ps) [ -f "$STUB/db_running" ] && echo cinefilm-db; exit 0 ;;
    run) [ -f "$STUB/has_pgdata" ]; exit $? ;;
    exec) [ -f "$STUB/fail_dump" ] && exit 1; echo DUMPDATA; exit 0 ;;
    inspect)
        if [ "${*: -1}" = cinefilm-db ]; then echo healthy; exit 0; fi
        if grep -qx "$(cat "$STUB/running_version" 2>/dev/null)" "$STUB/unhealthy_versions" 2>/dev/null; then echo unhealthy; else echo healthy; fi
        exit 0 ;;
    compose)
        case " $* " in
            *" pull "*) [ -f "$STUB/fail_pull" ] && exit 1; exit 0 ;;
            *" up "*cinefilm-db*) [ -f "$STUB/fail_dbstart" ] && exit 1; touch "$STUB/db_running"; exit 0 ;;
            *" up "*) [ -f "$STUB/fail_up" ] && [ "${CINEFILM_VERSION}" = "$(cat "$STUB/fail_up")" ] && exit 1
                      echo "$CINEFILM_VERSION" > "$STUB/running_version"; touch "$STUB/db_running"; exit 0 ;;
        esac ;;
esac
exit 0
EOS
    # shellcheck disable=SC2016
    printf '#!/bin/bash\necho "mountpoint $*" >> "$STUB/calls.log"\nexit 0\n' > "$STUB/bin/mountpoint"
    # shellcheck disable=SC2016
    printf '#!/bin/bash\ncase "${*: -1}" in /mnt/*) echo "ls $*" >> "$STUB/calls.log"; exit 0 ;; esac\nexec /bin/ls "$@"\n' > "$STUB/bin/ls"
    printf '#!/bin/bash\nexit 0\n' > "$STUB/bin/sleep"
    cat > "$STUB/smoke.sh" <<'EOS'
#!/bin/bash
echo "smoke [expect=${EXPECT_COMMIT:-}]" >> "$STUB/calls.log"
grep -qx "$(cat "$STUB/running_version" 2>/dev/null)" "$STUB/smoke_bad_versions" 2>/dev/null && exit 1
exit 0
EOS
    chmod +x "$STUB"/bin/* "$STUB/smoke.sh"
}

# deployed VERSION: pretend VERSION is what runs now (existing deployment with data and a running database).
deployed() {
    echo "$1" > "$DH/.deployed-version"; echo "$1" > "$STUB/running_version"
    touch "$STUB/has_pgdata" "$STUB/db_running"
}

# run_deploy VERSION [ENV=VALUE ...]: runs deploy.sh; output in $OUT, exit code in $RC.
run_deploy() {
    local v="$1"; shift
    OUT="$(env STUB="$STUB" PATH="$STUB/bin:$PATH" DEPLOY_HOME="$DH" CINEFILM_DATA_DIR="$DATA" BACKUP_DIR="$BK" \
        SMOKE_SCRIPT="$STUB/smoke.sh" "$@" bash "$REPO/scripts/deploy.sh" "$v" 2>&1)"
    RC=$?
}

log() { cat "$STUB/calls.log"; }
# line_of PATTERN: line number of the first log line matching, or 0
line_of() { grep -n -m1 -- "$1" "$STUB/calls.log" | cut -d: -f1 | grep . || echo 0; }

# --- normal upgrade: dump, then pull, then up; versions recorded -------------------------------------------------------
new_world; deployed v0.1.0
run_deploy v0.2.0 SMOKE_ENFORCE=true EXPECT_COMMIT=abc123
assert_eq "upgrade succeeds" "0" "$RC"
d="$(line_of 'docker exec cinefilm-db')"; p="$(line_of 'compose.* pull')"; u="$(line_of 'compose.* up -d \[')"
if [ "$d" -gt 0 ] && [ "$d" -lt "$p" ] && [ "$p" -lt "$u" ]; then ok "the dump runs before pull, and pull before up"; else bad "order dump=$d pull=$p up=$u"; fi
assert_file_has "up used the new version" "$STUB/calls.log" "up -d [V=v0.2.0]"
assert_eq ".deployed-version is the new one" "v0.2.0" "$(cat "$DH/.deployed-version")"
assert_eq ".previous-version is the old one" "v0.1.0" "$(cat "$DH/.previous-version")"
if ls "$BK"/pre-release/v0.2.0-*.dump >/dev/null 2>&1; then ok "a dump file was written"; else bad "no dump file"; fi
assert_file_has "the smoke was told the expected commit" "$STUB/calls.log" "smoke [expect=abc123]"

# --- a failed dump stops the deploy before anything is pulled or started ---------------------------------------------
new_world; deployed v0.1.0; touch "$STUB/fail_dump"
run_deploy v0.2.0 SMOKE_ENFORCE=true
assert_eq "failed dump: deploy fails" "1" "$RC"
assert_file_lacks "failed dump: nothing pulled" "$STUB/calls.log" " pull"
assert_file_lacks "failed dump: nothing started" "$STUB/calls.log" " up -d"
assert_eq "failed dump: .deployed-version untouched" "v0.1.0" "$(cat "$DH/.deployed-version")"

# --- the new release is unhealthy -----------------------------------------------------------------------------------
new_world; deployed v0.1.0; echo v0.2.0 > "$STUB/unhealthy_versions"
run_deploy v0.2.0 SMOKE_ENFORCE=true
assert_eq "unhealthy + enforced: rolled back (exit 1)" "1" "$RC"
assert_file_has "rollback recreated the container on the version in .previous-version" "$STUB/calls.log" "up -d --no-deps --force-recreate cinefilm [V=v0.1.0]"
assert_eq "rollback leaves the previous version recorded as running" "v0.1.0" "$(cat "$DH/.deployed-version")"
assert_file_lacks "rollback never restores the database" "$STUB/calls.log" "pg_restore"

new_world; deployed v0.1.0; echo v0.2.0 > "$STUB/unhealthy_versions"
run_deploy v0.2.0
assert_eq "unhealthy + report-only: exit 1" "1" "$RC"
assert_file_lacks "report-only: no rollback" "$STUB/calls.log" "--force-recreate"
assert_contains "report-only says so" "$OUT" "report-only"
assert_eq "report-only: the failed version stays recorded" "v0.2.0" "$(cat "$DH/.deployed-version")"

# --- the smoke fails on a healthy container ---------------------------------------------------------------------------
new_world; deployed v0.1.0; echo v0.2.0 > "$STUB/smoke_bad_versions"
run_deploy v0.2.0 SMOKE_ENFORCE=true
assert_eq "smoke fails + enforced: rolled back" "1" "$RC"
assert_file_has "smoke rollback targets the previous version" "$STUB/calls.log" "--force-recreate cinefilm [V=v0.1.0]"
if [ "$(grep -c '^smoke' "$STUB/calls.log")" = "2" ]; then ok "the rolled-back build is smoked too"; else bad "expected 2 smoke runs"; fi
assert_file_has "the rollback smoke does not demand the failed release's commit" "$STUB/calls.log" "smoke [expect=]"

new_world; deployed v0.1.0; echo v0.2.0 > "$STUB/smoke_bad_versions"
run_deploy v0.2.0 SMOKE_ENFORCE=false
assert_eq "smoke fails + not enforced: exit 1, no rollback" "1" "$RC"
assert_file_lacks "not enforced: no rollback" "$STUB/calls.log" "--force-recreate"

# --- compose up itself fails ------------------------------------------------------------------------------------------
new_world; deployed v0.1.0; echo v0.2.0 > "$STUB/fail_up"
run_deploy v0.2.0 SMOKE_ENFORCE=true
assert_eq "up fails + enforced: rolled back" "1" "$RC"
assert_file_has "up failure rolls back to the previous version" "$STUB/calls.log" "--force-recreate cinefilm [V=v0.1.0]"

new_world; deployed v0.1.0; echo v0.2.0 > "$STUB/fail_up"
run_deploy v0.2.0
assert_eq "up fails + report-only: exit 1" "1" "$RC"
assert_file_lacks "up fails, report-only: no rollback" "$STUB/calls.log" "--force-recreate"

# --- a pull failure is infrastructure (3), never a rollback --------------------------------------------------------------
new_world; deployed v0.1.0; touch "$STUB/fail_pull"
run_deploy v0.2.0 SMOKE_ENFORCE=true
assert_eq "pull fails: exit 3" "3" "$RC"
assert_file_lacks "pull fails: nothing started or rolled back" "$STUB/calls.log" " up -d"

# --- first deployment: nothing to dump, no rollback target ---------------------------------------------------------------
new_world
run_deploy v0.1.0 SMOKE_ENFORCE=true
assert_eq "first deploy succeeds" "0" "$RC"
assert_file_lacks "first deploy: no dump taken" "$STUB/calls.log" "docker exec"
assert_file_has "first deploy: started" "$STUB/calls.log" "up -d [V=v0.1.0]"
assert_file_missing "first deploy: no .previous-version" "$DH/.previous-version"

new_world; echo v0.1.0 > "$STUB/unhealthy_versions"
run_deploy v0.1.0 SMOKE_ENFORCE=true
assert_eq "first deploy broken + enforced: no previous version, manual action (2)" "2" "$RC"

# --- existing deployment, database container stopped: start it and dump, or abort ------------------------------------------
new_world; deployed v0.1.0; rm "$STUB/db_running"
run_deploy v0.2.0
assert_eq "db stopped on an existing deployment: still deploys" "0" "$RC"
s="$(line_of 'up -d --no-deps cinefilm-db')"; d="$(line_of 'docker exec cinefilm-db')"; p="$(line_of 'compose.* pull')"
if [ "$s" -gt 0 ] && [ "$s" -lt "$d" ] && [ "$d" -lt "$p" ]; then ok "db started, then dumped, then pulled"; else bad "order start=$s dump=$d pull=$p"; fi

new_world; deployed v0.1.0; rm "$STUB/db_running"; touch "$STUB/fail_dbstart"
run_deploy v0.2.0
assert_eq "db cannot be started on an existing deployment: abort" "1" "$RC"
assert_file_lacks "no dump, no pull when the db cannot start" "$STUB/calls.log" " pull"

new_world; touch "$STUB/has_pgdata"   # no version files, but a database is on disk (an earlier first deploy half done)
run_deploy v0.1.0
assert_file_has "a database on disk counts as existing: db started to dump" "$STUB/calls.log" "up -d --no-deps cinefilm-db"
assert_file_has "and dumped" "$STUB/calls.log" "docker exec cinefilm-db"

new_world; echo v0.1.0 > "$DH/.deployed-version"   # deployed before, but the data directory is empty
run_deploy v0.2.0
assert_eq "previous deploy but no PG_VERSION: refuse (EMPTY database guard)" "1" "$RC"
assert_file_lacks "guard: nothing pulled" "$STUB/calls.log" " pull"

# --- the media mount is only ever probed read-only --------------------------------------------------------------------------
new_world; deployed v0.1.0
run_deploy v0.2.0 SMOKE_ENFORCE=true
bad_lines="$(grep 'workhorse-films' "$STUB/calls.log" | grep -vE '^(mountpoint|ls) ' || true)"
assert_eq "only mountpoint and ls ever mention /mnt/workhorse-films" "" "$bad_lines"
assert_file_has "mountpoint was probed" "$STUB/calls.log" "mountpoint -q /mnt/workhorse-films"
writes="$(grep -nE '(touch|rm|mkdir|cp|mv|tee|dd|chmod|chown|>>?) *[^ ]*/mnt/workhorse-films' "$REPO"/scripts/*.sh "$REPO"/scripts/lib/*.sh || true)"
assert_eq "no script writes under /mnt/workhorse-films" "" "$writes"

# --- the script and the compose file agree on the data directory ------------------------------------------------------------
compose_default="$(grep -oE '\$\{CINEFILM_DATA_DIR:-[^}]*\}' "$REPO/deploy/docker-compose.yml" | sort -u)"
assert_eq "compose has exactly one default for the data directory" "1" "$(printf '%s\n' "$compose_default" | grep -c .)"
# shellcheck source=/dev/null
. "$REPO/scripts/lib/release-lib.sh"
# shellcheck disable=SC2154
assert_eq "compose default equals the scripts' default" "\${CINEFILM_DATA_DIR:-${CINEFILM_DATA_DIR_DEFAULT}}" "$compose_default"
hardcoded="$(grep -n '/home/gregochr/cinefilm-data' "$REPO/deploy/docker-compose.yml" | grep -v 'CINEFILM_DATA_DIR:-' || true)"
assert_eq "compose has no hard-coded data path outside the default" "" "$hardcoded"
new_world; deployed v0.1.0
run_deploy v0.2.0
assert_file_has "deploy checked the PG_VERSION guard under the same data directory it was given" "$STUB/calls.log" "-v ${DATA}/postgres:/d:ro"

finish_tests
