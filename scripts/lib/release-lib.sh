#!/bin/bash
# Pure helpers shared by release.sh, deploy.sh and auto-rollback.sh (and unit-tested by scripts/test/release-lib.test.sh).
# Sourced, never run. No side effects, no docker, no network.

# is_valid_version VERSION: vMAJOR.MINOR.PATCH, nothing else.
is_valid_version() {
    [[ "${1:-}" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]
}

# next_version CURRENT KIND: prints the next version. KIND is major, minor or patch. No CURRENT (empty) means v0.0.0.
next_version() {
    local current="${1:-}" kind="${2:-patch}"
    [ -n "$current" ] || current="v0.0.0"
    is_valid_version "$current" || return 1
    local nums="${current#v}"
    local major="${nums%%.*}"
    local rest="${nums#*.}"
    local minor="${rest%%.*}"
    local patch="${rest#*.}"
    case "$kind" in
        major) echo "v$((major + 1)).0.0" ;;
        minor) echo "v${major}.$((minor + 1)).0" ;;
        patch) echo "v${major}.${minor}.$((patch + 1))" ;;
        *) return 1 ;;
    esac
}

# version_gt A B: true when version A is strictly newer than B.
version_gt() {
    local a="${1#v}" b="${2#v}"
    [ "$a" != "$b" ] && [ "$(printf '%s\n%s\n' "$a" "$b" | sort -V | tail -n 1)" = "$a" ]
}

# rollback_decision SMOKE_EXIT ENFORCE: prints "rollback", "report-only" or "none".
# Exit-code contract of the smoke and deploy checks: 0 passed, 1 the release is broken, 3 inconclusive or
# infrastructure, 4 the smoke is misconfigured. ONLY 1 can roll back, and only when ENFORCE is exactly "true".
rollback_decision() {
    local rc="${1:-}" enforce="${2:-}"
    if [ "$rc" = "0" ]; then
        echo none
    elif [ "$rc" = "1" ] && [ "$enforce" = "true" ]; then
        echo rollback
    else
        echo report-only
    fi
}

# The data root; deploy/docker-compose.yml uses the same variable and default (scripts/test/deploy.test.sh checks).
export CINEFILM_DATA_DIR_DEFAULT=/home/gregochr/cinefilm-data

# ---- Host helpers (call docker; exercised by scripts/test/deploy.test.sh against a stub docker) ----

# pgdata_has_database DIR: true when DIR holds a Postgres data directory (PG_VERSION). The directory is mode 700 and
# owned by the container's postgres user, hence the throwaway container.
pgdata_has_database() {
    docker run --rm -v "$1:/d:ro" alpine test -f /d/PG_VERSION >/dev/null 2>&1
}

# dc ARGS...: docker compose against the deployed stack in $DEPLOY_HOME. CINEFILM_VERSION must be exported.
dc() {
    : "${DEPLOY_HOME:?DEPLOY_HOME must be set}"
    docker compose --project-directory "$DEPLOY_HOME" -f "$DEPLOY_HOME/docker-compose.yml" \
        --env-file "$DEPLOY_HOME/.env" "$@"
}

# wait_healthy CONTAINER TIMEOUT_SECONDS: 0 when Docker reports healthy, 1 when unhealthy or timed out.
wait_healthy() {
    local container="$1" timeout="${2:-180}" waited=0 status
    while [ "$waited" -lt "$timeout" ]; do
        status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container" 2>/dev/null || echo missing)"
        case "$status" in
            healthy) return 0 ;;
            unhealthy) echo "${container} is unhealthy" >&2; return 1 ;;
        esac
        sleep 5
        waited=$((waited + 5))
    done
    echo "${container} did not become healthy within ${timeout}s" >&2
    return 1
}
