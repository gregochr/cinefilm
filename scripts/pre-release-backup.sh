#!/bin/bash
# Pre-release database dump (SECURITY.md rule 9: the watch history is irreplaceable).
#
# Usage: scripts/pre-release-backup.sh VERSION     (the version about to be deployed)
# Writes ${BACKUP_DIR}/pre-release/<VERSION>-<UTC timestamp>.dump (pg_dump custom format) and a sibling .txt noting
# the version that was running. Never overwrites a dump; keeps the 10 newest. If cinefilm-db is not running
# (the very first deploy) there is nothing to dump and it says so.
# Exit: 0 dumped or nothing to dump, 1 the dump failed (the deploy must stop).

set -euo pipefail

VERSION="${1:?usage: pre-release-backup.sh VERSION}"
DEPLOY_HOME="${DEPLOY_HOME:-$HOME/cinefilm}"
BACKUP_DIR="${BACKUP_DIR:-/home/gregochr/cinefilm-backups}"
DEST="${BACKUP_DIR}/pre-release"

if ! docker ps --format '{{.Names}}' | grep -qx cinefilm-db; then
    echo "pre-release backup: cinefilm-db is not running (first deploy?); nothing to dump."
    exit 0
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
    echo "running: $(cat "${DEPLOY_HOME}/.deployed-version" 2>/dev/null || echo unknown)"
} > "${OUT%.dump}.txt"
echo "pre-release backup: ${OUT} ($(wc -c < "$OUT") bytes)"

# Keep the 10 newest dumps.
# shellcheck disable=SC2012
ls -1t "${DEST}"/*.dump 2>/dev/null | tail -n +11 | while read -r old; do
    rm -f "$old" "${old%.dump}.txt"
done
