#!/bin/bash
# Deploy a released version to dockermacmini. Run by the deploy job on the self-hosted `cinefilm-prod` runner from
# a checkout of the tag (no SSH). The image must already be in GHCR and the runner logged in to it.
#
# Usage: scripts/deploy.sh vX.Y.Z
# Env:   SMOKE_ENFORCE=true   roll back (image only) when the release is broken; else report-only
#        EXPECT_COMMIT=<sha>  the smoke also checks /actuator/info reports this commit (deploy.yml passes github.sha)
#        SMOKE_PROXY_URL is derived here from CINEFILM_DOMAIN in .env (default cinefilm.chrisjgregory.dev)
#        DEPLOY_HOME (~/cinefilm), CINEFILM_DATA_DIR (/home/gregochr/cinefilm-data, the same variable and default as
#        deploy/docker-compose.yml), BACKUP_DIR (/home/gregochr/cinefilm-backups)
#
# Order: guards -> pre-release pg_dump -> pull -> up -d -> wait healthy -> smoke -> rollback decision.
# Exit:  0 deployed and verified; 1 broken release (rolled back => see auto-rollback.sh exit); 3 inconclusive or
#        infrastructure (pull failed, ...); 4 smoke misconfigured; 2 manual action needed after a failed rollback.
# This script never writes under /mnt/workhorse-films: the media mount is only ever probed with `mountpoint` and `ls`.

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
export CINEFILM_DATA_DIR="${CINEFILM_DATA_DIR:-$CINEFILM_DATA_DIR_DEFAULT}"
export BACKUP_DIR="${BACKUP_DIR:-/home/gregochr/cinefilm-backups}"
export EXPECT_COMMIT="${EXPECT_COMMIT:-}"
SMOKE_ENFORCE="${SMOKE_ENFORCE:-}"
SMOKE_SCRIPT="${SMOKE_SCRIPT:-${SCRIPT_DIR}/post-deploy-smoke.sh}"
export SMOKE_SCRIPT

if [ ! -f "${DEPLOY_HOME}/.env" ]; then
    echo "ERROR: ${DEPLOY_HOME}/.env is missing: create it from deploy/.env.example (docs/engineering/operations.md)." >&2
    exit 4
fi

# The Caddy proxy (cinefilm-proxy) needs these three; compose would stop on the missing one anyway, but name it here
# before anything is dumped or pulled (operations.md, Custom domain). Values are never printed.
for key in CLOUDFLARE_API_TOKEN CINEFILM_TAILNET_IPV4 CINEFILM_TAILNET_IPV6; do
    if ! grep -Eq "^${key}=." "${DEPLOY_HOME}/.env"; then
        echo "ERROR: ${key} is not set in ${DEPLOY_HOME}/.env (the proxy needs it): see operations.md, Custom domain." >&2
        exit 4
    fi
done
# A malformed token (stray space, quote, brace, wrong length) would make Caddy echo it into `docker logs`: reject it here,
# without printing it, its length or any character of it. The proxy image's entrypoint applies the same rule.
cf_token="$(sed -n 's/^CLOUDFLARE_API_TOKEN=//p' "${DEPLOY_HOME}/.env" | tail -n 1)"
if ! token_is_wellformed "$cf_token"; then
    unset cf_token
    echo "ERROR: CLOUDFLARE_API_TOKEN in ${DEPLOY_HOME}/.env is malformed (expected 40+ URL-safe characters, no spaces, quotes or braces); fix it and rotate the token if it was ever logged." >&2
    exit 4
fi
unset cf_token
# The smoke also probes the app through the proxy. CINEFILM_DOMAIN is optional in .env (compose defaults it).
proxy_domain="$(sed -n 's/^CINEFILM_DOMAIN=//p' "${DEPLOY_HOME}/.env" | tail -n 1 | tr -d "\"' \r")"
export SMOKE_PROXY_URL="https://${proxy_domain:-cinefilm.chrisjgregory.dev}"

mkdir -p "${CINEFILM_DATA_DIR}"/{postgres,logs,cache,artwork,caddy/data,caddy/config} "${BACKUP_DIR}/pre-release"
PREVIOUS="$(cat "${DEPLOY_HOME}/.deployed-version" 2>/dev/null || true)"

# Guard: a previous deploy exists, so the Postgres data directory must hold a database. Pointed at an empty
# directory Postgres silently runs initdb and the app comes up healthy against an EMPTY database.
if [ -n "$PREVIOUS" ] && ! pgdata_has_database "${CINEFILM_DATA_DIR}/postgres"; then
    echo "ERROR: ${CINEFILM_DATA_DIR}/postgres holds no Postgres data (no PG_VERSION) but ${PREVIOUS} was deployed before." >&2
    echo "       Starting would create an EMPTY database. Find the data before deploying." >&2
    exit 1
fi

# The media mount is optional for a deploy: warn, never fail or roll back.
# (A read-only `ls` first: the fstab entry is an automount that only mounts on first access.)
ls /mnt/workhorse-films >/dev/null 2>&1 || true
if ! mountpoint -q /mnt/workhorse-films 2>/dev/null; then
    echo "WARNING: /mnt/workhorse-films is not mounted: CineFilm will start with an empty library (operations.md)." >&2
fi

# Dump BEFORE anything changes. A failed dump (or no way to take one on an existing deployment) stops the deploy.
bash "${SCRIPT_DIR}/pre-release-backup.sh" "$VERSION"

cp "${SCRIPT_DIR}/../deploy/docker-compose.yml" "${DEPLOY_HOME}/docker-compose.yml"
export CINEFILM_VERSION="$VERSION"

if ! dc pull cinefilm cinefilm-proxy; then
    echo "ERROR: could not pull ghcr.io/gregochr/cinefilm:${VERSION} and cinefilm-caddy:${VERSION} (is the runner logged in to GHCR?)." >&2
    exit 3
fi

if [ -n "$PREVIOUS" ] && [ "$PREVIOUS" != "$VERSION" ]; then
    echo "$PREVIOUS" > "${DEPLOY_HOME}/.previous-version"
fi

# ---- Everything from here on is AFTER the pull: only these steps can cause a rollback. ----
check_rc=0
if dc up -d; then
    echo "$VERSION" > "${DEPLOY_HOME}/.deployed-version"
    if ! wait_healthy cinefilm 180; then
        check_rc=1
    else
        bash "$SMOKE_SCRIPT" || check_rc=$?
    fi
else
    echo "ERROR: docker compose up failed." >&2
    check_rc=1
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
