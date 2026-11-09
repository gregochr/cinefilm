#!/bin/bash
# Pre-release database dump (SECURITY.md rule 9: the watch history is irreplaceable).
#
# Usage: scripts/pre-release-backup.sh VERSION     (the version about to be deployed)
# Writes ${BACKUP_DIR}/pre-release/<VERSION>-<UTC timestamp>.dump (pg_dump custom format) and a sibling .txt noting
# the version that was running. Never overwrites a dump; keeps the 10 newest.
#
# The dump is skipped ONLY on a genuine first deployment: no ~/cinefilm/.deployed-version or .previous-version AND no
# Postgres data (PG_VERSION) in the data directory. On an existing deployment whose cinefilm-db is stopped, it starts
# the database (previous version's compose file) and dumps it; if that cannot be done it FAILS, so the deploy stops
# rather than proceeding with no backup.
# Exit: 0 dumped or genuine first deploy, 1 no dump could be taken (the deploy must stop).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
. "${SCRIPT_DIR}/lib/release-lib.sh"

VERSION="${1:?usage: pre-release-backup.sh VERSION}"
DEPLOY_HOME="${DEPLOY_HOME:-$HOME/cinefilm}"
CINEFILM_DATA_DIR="${CINEFILM_DATA_DIR:-$CINEFILM_DATA_DIR_DEFAULT}"
BACKUP_DIR="${BACKUP_DIR:-/home/gregochr/cinefilm-backups}"
DEST="${BACKUP_DIR}/pre-release"
export DEPLOY_HOME CINEFILM_DATA_DIR

RUNNING="$(cat "${DEPLOY_HOME}/.deployed-version" 2>/dev/null || true)"

RUNNING_CONTAINERS="$(docker ps --format '{{.Names}}')"
if ! printf '%s\n' "$RUNNING_CONTAINERS" | grep -qx cinefilm-db; then
    existing=false
    if [ -n "$RUNNING" ] || [ -f "${DEPLOY_HOME}/.previous-version" ] || pgdata_has_database "${CINEFILM_DATA_DIR}/postgres"; then
        existing=true
    fi
    if [ "$existing" = false ]; then
        echo "pre-release backup: first deployment (no previous version, no database); nothing to dump."
        exit 0
    fi
    echo "pre-release backup: cinefilm-db is stopped on an existing deployment; starting it to take the dump."
    if ! CINEFILM_VERSION="${RUNNING:-$VERSION}" dc up -d --no-deps cinefilm-db || ! wait_healthy cinefilm-db 120; then
        echo "pre-release backup: could not start cinefilm-db; not deploying without a backup." >&2
        exit 1
    fi
fi

mkdir -p "$DEST"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="${DEST}/${VERSION}-${STAMP}.dump"
TMP="${OUT}.partial"

# The role and database come from the container's own environment, so no secret is read or printed here.
if ! docker exec cinefilm-db sh -c 'pg_dump -U "$POSTGRES_USER" -d cinefilm -Fc' > "$TMP"; then
    rm -f "$TMP"
    echo "pre-release backup: pg_dump FAILED; not deploying." >&2
    exit 1
fi
if [ ! -s "$TMP" ]; then
    rm -f "$TMP"
    echo "pre-release backup: the dump is empty; not deploying." >&2
    exit 1
fi
mv "$TMP" "$OUT"
{
    echo "before: ${VERSION}"
    echo "running: ${RUNNING:-unknown}"
} > "${OUT%.dump}.txt"
echo "pre-release backup: ${OUT} ($(wc -c < "$OUT") bytes)"

# Keep the 10 newest dumps.
# shellcheck disable=SC2012
ls -1t "${DEST}"/*.dump 2>/dev/null | tail -n +11 | while read -r old; do
    rm -f "$old" "${old%.dump}.txt"
done
