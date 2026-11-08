#!/bin/bash
# IMAGE-ONLY rollback to the previous release tag, after deploy.sh found the new release broken.
#
# Usage: scripts/auto-rollback.sh FAILED_VERSION       (also usable by hand: scripts/auto-rollback.sh vX.Y.Z)
# Reads ~/cinefilm/.previous-version (written by deploy.sh), recreates only the `cinefilm` container on that tag,
# waits for health and re-runs the smoke. It NEVER touches the database: the failed release may have applied
# migrations (expand-then-contract keeps the previous image compatible). Restoring data from
# ~/cinefilm-backups/pre-release/ is a human decision (operations.md).
#
# Exit: 1 rolled back and verified (a rolled-back release is still a failed release), 2 manual action needed.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
. "${SCRIPT_DIR}/lib/release-lib.sh"

FAILED="${1:-unknown}"
export DEPLOY_HOME="${DEPLOY_HOME:-$HOME/cinefilm}"

PREVIOUS="$(cat "${DEPLOY_HOME}/.previous-version" 2>/dev/null || true)"
if ! is_valid_version "$PREVIOUS"; then
    echo "ROLLBACK: no previous version recorded in ${DEPLOY_HOME}/.previous-version (first release?). Manual action needed." >&2
    exit 2
fi
if [ "$PREVIOUS" = "$FAILED" ]; then
    echo "ROLLBACK: the previous version is the failed one (${FAILED}). Manual action needed." >&2
    exit 2
fi

echo "ROLLBACK: ${FAILED} -> ${PREVIOUS} (image only)"
export CINEFILM_VERSION="$PREVIOUS"
if ! dc up -d --no-deps --force-recreate cinefilm; then
    echo "ROLLBACK: could not start ${PREVIOUS}. Manual action needed." >&2
    exit 2
fi
echo "$PREVIOUS" > "${DEPLOY_HOME}/.deployed-version"

if wait_healthy cinefilm 180 && bash "${SCRIPT_DIR}/post-deploy-smoke.sh"; then
    echo "ROLLBACK: ${PREVIOUS} is running and verified. ${FAILED} remains a FAILED release."
    exit 1
fi
echo "ROLLBACK: ${PREVIOUS} did not come up healthy either. Manual action needed." >&2
exit 2
