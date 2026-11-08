#!/bin/bash
# Deploy a released version to dockermacmini. Run by the deploy job on the self-hosted `cinefilm-prod` runner from
# a checkout of the tag (no SSH). The image must already be in GHCR and the runner logged in to it.
#
# Usage: scripts/deploy.sh vX.Y.Z
# Env:   SMOKE_ENFORCE=true   roll back (image only) when the smoke says the release is broken; else report-only
#        DEPLOY_HOME (~/cinefilm), DATA_DIR (/home/gregochr/cinefilm-data), BACKUP_DIR (/home/gregochr/cinefilm-backups)
#
# Order: guards -> pre-release pg_dump -> pull -> up -d -> wait healthy -> smoke -> rollback decision.
# Exit:  0 deployed and verified; 1 broken release (rolled back => see auto-rollback.sh exit); 3 inconclusive or
#        infrastructure (pull failed, ...); 4 smoke misconfigured; 2 manual action needed after a failed rollback.
# This script never writes under /mnt/workhorse-films (the media mount is read-only and not touched here).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
. "${SCRIPT_DIR}/lib/release-lib.sh"

VERSION="${1:-}"
if ! is_valid_version "$VERSION"; then
    echo "usage: deploy.sh vX.Y.Z (got '${VERSION}')" >&2
    exit 4
fi

export DEPLOY_HOME="${DEPLOY_HOME:-$HOME/cinefilm}"
export DATA_DIR="${DATA_DIR:-/home/gregochr/cinefilm-data}"
export BACKUP_DIR="${BACKUP_DIR:-/home/gregochr/cinefilm-backups}"
SMOKE_ENFORCE="${SMOKE_ENFORCE:-}"

if [ ! -f "${DEPLOY_HOME}/.env" ]; then
    echo "ERROR: ${DEPLOY_HOME}/.env is missing: create it from deploy/.env.example (docs/engineering/operations.md)." >&2
    exit 4
fi

mkdir -p "${DATA_DIR}"/{postgres,logs,cache} "${BACKUP_DIR}/pre-release"
PREVIOUS="$(cat "${DEPLOY_HOME}/.deployed-version" 2>/dev/null || true)"

# Guard: a previous deploy exists, so the Postgres data directory must hold a database. Pointed at an empty
# directory Postgres silently runs initdb and the app comes up healthy against an EMPTY database. (The host user
# cannot read the mode-700 directory, hence the throwaway container.)
if [ -n "$PREVIOUS" ] && ! docker run --rm -v "${DATA_DIR}/postgres:/d:ro" alpine test -f /d/PG_VERSION 2>/dev/null; then
    echo "ERROR: ${DATA_DIR}/postgres holds no Postgres data (no PG_VERSION) but ${PREVIOUS} was deployed before." >&2
    echo "       Starting would create an EMPTY database. Find the data before deploying." >&2
    exit 1
fi

# The media mount is optional for a deploy: warn, never fail or roll back.
# (A read-only `ls` first: the fstab entry is an automount that only mounts on first access.)
ls /mnt/workhorse-films >/dev/null 2>&1 || true
if ! mountpoint -q /mnt/workhorse-films 2>/dev/null; then
    echo "WARNING: /mnt/workhorse-films is not mounted: CineFilm will start with an empty library (operations.md)." >&2
fi

# Dump BEFORE anything changes.
bash "${SCRIPT_DIR}/pre-release-backup.sh" "$VERSION"

cp "${SCRIPT_DIR}/../deploy/docker-compose.yml" "${DEPLOY_HOME}/docker-compose.yml"
export CINEFILM_VERSION="$VERSION"

if ! dc pull cinefilm; then
    echo "ERROR: could not pull ghcr.io/gregochr/cinefilm:${VERSION} (is the runner logged in to GHCR?)." >&2
    exit 3
fi

if [ -n "$PREVIOUS" ] && [ "$PREVIOUS" != "$VERSION" ]; then
    echo "$PREVIOUS" > "${DEPLOY_HOME}/.previous-version"
fi
dc up -d
echo "$VERSION" > "${DEPLOY_HOME}/.deployed-version"

# ---- Everything from here on is AFTER `up`: only these steps can cause a rollback. ----
check_rc=0
if ! wait_healthy cinefilm 180; then
    check_rc=1
else
    bash "${SCRIPT_DIR}/post-deploy-smoke.sh" || check_rc=$?
fi

if [ "$check_rc" -eq 0 ]; then
    echo "Deployed ${VERSION}."
    exit 0
fi

echo "ERROR: post-deploy checks failed with exit ${check_rc} (1 release broken, 3 inconclusive, 4 smoke misconfigured)." >&2
case "$(rollback_decision "$check_rc" "$SMOKE_ENFORCE")" in
    rollback)
        echo "SMOKE_ENFORCE is true: rolling back to the previous image (the database is not touched)." >&2
        rollback_rc=0
        bash "${SCRIPT_DIR}/auto-rollback.sh" "$VERSION" || rollback_rc=$?
        # 1 = rolled back, 2 = manual action needed. A stray 0 must never turn a failed release green.
        [ "$rollback_rc" -ne 0 ] || rollback_rc=2
        exit "$rollback_rc"
        ;;
    *)
        echo "SMOKE_ENFORCE is not 'true' (or exit ${check_rc} is never a reason to roll back): report-only." >&2
        exit "$check_rc"
        ;;
esac
