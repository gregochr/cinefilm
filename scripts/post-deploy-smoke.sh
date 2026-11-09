#!/bin/bash
# Post-deploy smoke for CineFilm: is the release answering, and is the auth boundary intact?
#
# Usage: scripts/post-deploy-smoke.sh [BASE_URL]      (default http://127.0.0.1:8090, the loopback publish)
# Env:   SMOKE_PROXY_URL=https://cinefilm.chrisjgregory.dev   also probe the app THROUGH the Caddy proxy (deploy.sh sets
#        it from CINEFILM_DOMAIN). Unset or empty: the proxy is not checked. SMOKE_PROXY_ATTEMPTS (default 24) and
#        SMOKE_PROXY_SLEEP (default 5) are longer than the app's because Caddy may be issuing its first certificate.
#
# Probes (read-only HTTP; nothing here touches /mnt/workhorse-films or the database):
#   GET /actuator/health   200 and "UP"
#   GET /                  200, HTML, the SPA shell (<div id="root">)
#   GET /api/home          401 with a JSON body (signed out)
#   GET <proxy>/ and /api/home   as the two lines above, over HTTPS with a verified certificate (only when SMOKE_PROXY_URL is set)
#   GET /actuator/info     only when EXPECT_COMMIT is set: reports git.commit.id.full = <EXPECT_COMMIT> (the running build)
#
# Exit codes: 0 passed, 1 the release is broken (an app probe failed), 3 inconclusive (only the proxy probes failed: the
# app is fine, the way in is not, and rolling the image back would not fix a proxy, DNS or certificate problem),
# 4 misconfigured (no curl, bad URL).
# Each probe is tried up to SMOKE_ATTEMPTS times (default 3, SMOKE_RETRY_SLEEP seconds apart) so a slow first
# response is not a failed release.

set -uo pipefail

BASE_URL="${1:-http://127.0.0.1:8090}"
ATTEMPTS="${SMOKE_ATTEMPTS:-3}"
RETRY_SLEEP="${SMOKE_RETRY_SLEEP:-3}"

if ! command -v curl >/dev/null 2>&1; then
    echo "smoke: curl is not installed" >&2
    exit 4
fi
case "$BASE_URL" in
    http://* | https://*) ;;
    *) echo "smoke: BASE_URL must start with http:// or https:// (got '$BASE_URL')" >&2; exit 4 ;;
esac

PROXY_URL="${SMOKE_PROXY_URL:-}"
PROXY_ATTEMPTS="${SMOKE_PROXY_ATTEMPTS:-24}"
PROXY_SLEEP="${SMOKE_PROXY_SLEEP:-5}"
if [ -n "$PROXY_URL" ]; then
    case "$PROXY_URL" in
        https://*) PROXY_URL="${PROXY_URL%/}" ;;
        *) echo "smoke: SMOKE_PROXY_URL must start with https:// (got '$PROXY_URL')" >&2; exit 4 ;;
    esac
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
failures=0
proxy_failures=0

# probe NAME PATH EXPECTED_CODE BODY_NEEDLE [CONTENT_TYPE_NEEDLE]: returns 1 when it failed. PROBE_BASE overrides BASE_URL.
probe() {
    local name="$1" path="$2" want_code="$3" needle="$4" ctype_needle="${5:-}" base="${PROBE_BASE:-$BASE_URL}"
    local attempt=1 result code ctype
    while [ "$attempt" -le "$ATTEMPTS" ]; do
        result="$(curl -sS --max-time 10 -o "$TMP/body" -w '%{http_code} %{content_type}' "${base}${path}" 2>/dev/null)" || result="000 "
        code="${result%% *}"
        ctype="${result#* }"
        if [ "$code" = "$want_code" ] && grep -qF -- "$needle" "$TMP/body" \
            && { [ -z "$ctype_needle" ] || printf '%s' "$ctype" | grep -qiF -- "$ctype_needle"; }; then
            echo "smoke ok   ${name}: ${path} -> ${code}"
            return 0
        fi
        attempt=$((attempt + 1))
        [ "$attempt" -le "$ATTEMPTS" ] && sleep "$RETRY_SLEEP"
    done
    echo "smoke FAIL ${name}: ${path} -> ${code} (${ctype}), wanted ${want_code} containing '${needle}'" >&2
    return 1
}

probe "health" "/actuator/health" 200 '"UP"' || failures=$((failures + 1))
probe "spa index" "/" 200 '<div id="root">' "text/html" || failures=$((failures + 1))
probe "auth boundary" "/api/home" 401 '"error"' "json" || failures=$((failures + 1))
# The running build is the release: /actuator/info reports the commit the image was built from (git.properties baked from GIT_COMMIT_SHA).
if [ -n "${EXPECT_COMMIT:-}" ]; then
    probe "running build" "/actuator/info" 200 "\"full\":\"${EXPECT_COMMIT}\"" || failures=$((failures + 1))
fi

# Through the Caddy proxy (tailnet only; the runner is on the tailnet). Proves DNS, the certificate and the proxy hop.
if [ -n "$PROXY_URL" ]; then
    export PROBE_BASE="$PROXY_URL"
    export ATTEMPTS="$PROXY_ATTEMPTS" RETRY_SLEEP="$PROXY_SLEEP"
    probe "proxy spa index" "/" 200 '<div id="root">' "text/html" || proxy_failures=$((proxy_failures + 1))
    probe "proxy auth boundary" "/api/home" 401 '"error"' "json" || proxy_failures=$((proxy_failures + 1))
fi

if [ "$failures" -gt 0 ]; then
    echo "smoke: ${failures} probe(s) failed" >&2
    exit 1
fi
if [ "$proxy_failures" -gt 0 ]; then
    echo "smoke: the app is fine but ${proxy_failures} probe(s) through the proxy (${PROXY_URL}) failed: inconclusive (docs/engineering/operations.md, Custom domain)" >&2
    exit 3
fi
echo "smoke: all probes passed"
