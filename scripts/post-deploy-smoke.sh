#!/bin/bash
# Post-deploy smoke for CineFilm: is the release answering, and is the auth boundary intact?
#
# Usage: scripts/post-deploy-smoke.sh [BASE_URL]      (default http://127.0.0.1:8086, the loopback publish)
#
# Probes (read-only HTTP; nothing here touches /mnt/workhorse-films or the database):
#   GET /actuator/health   200 and "UP"
#   GET /                  200, HTML, the SPA shell (<div id="root">)
#   GET /api/home          401 with a JSON body (signed out)
#   GET /actuator/info     only when EXPECT_COMMIT is set: reports "commit":"<EXPECT_COMMIT>" (the running build)
#
# Exit codes: 0 passed, 1 the release is broken (a probe failed), 4 misconfigured (no curl, bad URL).
# Each probe is tried up to SMOKE_ATTEMPTS times (default 3, SMOKE_RETRY_SLEEP seconds apart) so a slow first
# response is not a failed release.

set -uo pipefail

BASE_URL="${1:-http://127.0.0.1:8086}"
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

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
failures=0

# probe NAME PATH EXPECTED_CODE BODY_NEEDLE [CONTENT_TYPE_NEEDLE]
probe() {
    local name="$1" path="$2" want_code="$3" needle="$4" ctype_needle="${5:-}"
    local attempt=1 result code ctype
    while [ "$attempt" -le "$ATTEMPTS" ]; do
        result="$(curl -sS --max-time 10 -o "$TMP/body" -w '%{http_code} %{content_type}' "${BASE_URL}${path}" 2>/dev/null)" || result="000 "
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
    failures=$((failures + 1))
}

probe "health" "/actuator/health" 200 '"UP"'
probe "spa index" "/" 200 '<div id="root">' "text/html"
probe "auth boundary" "/api/home" 401 '"error"' "json"
# The running build is the release: /actuator/info reports the commit the image was built from (GIT_COMMIT_SHA).
if [ -n "${EXPECT_COMMIT:-}" ]; then
    probe "running build" "/actuator/info" 200 "\"commit\":\"${EXPECT_COMMIT}\""
fi

if [ "$failures" -gt 0 ]; then
    echo "smoke: ${failures} probe(s) failed" >&2
    exit 1
fi
echo "smoke: all probes passed"
