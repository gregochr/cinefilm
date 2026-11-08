#!/bin/bash
# Tests for scripts/post-deploy-smoke.sh against a stub curl on PATH (no network).
# The stub answers from files: $STUB/<slug>.code, <slug>.type, <slug>.body where slug is the URL path with / -> _ .

set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=/dev/null
. "${HERE}/helpers.sh"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin" "$TMP/stub"
cat > "$TMP/bin/curl" <<'STUB'
#!/bin/bash
out=""; url=""
while [ $# -gt 0 ]; do
    case "$1" in
        -o) out="$2"; shift 2 ;;
        -w|--max-time) shift 2 ;;
        -*) shift ;;
        *) url="$1"; shift ;;
    esac
done
path="${url#http*://*/}"; slug="_${path//\//_}"
[ -f "$STUB/$slug.code" ] || { exit 7; }
cat "$STUB/$slug.body" > "$out"
printf '%s %s' "$(cat "$STUB/$slug.code")" "$(cat "$STUB/$slug.type")"
STUB
chmod +x "$TMP/bin/curl"

serve() { # slug code type body
    printf '%s' "$2" > "$TMP/stub/$1.code"; printf '%s' "$3" > "$TMP/stub/$1.type"; printf '%s' "$4" > "$TMP/stub/$1.body"
}
good() {
    serve "_actuator_health" 200 application/json '{"status":"UP"}'
    serve "_" 200 "text/html;charset=UTF-8" '<html><div id="root"></div></html>'
    serve "_api_home" 401 application/json '{"error":"Authentication required"}'
}
run() { STUB="$TMP/stub" PATH="$TMP/bin:$PATH" SMOKE_ATTEMPTS=1 SMOKE_RETRY_SLEEP=0 bash "${HERE}/../post-deploy-smoke.sh" "$@" 2>&1; }

good
out="$(run http://x)"; rc=$?
assert_eq "all probes good: exit 0" "0" "$rc"

good; serve "_actuator_health" 200 application/json '{"status":"DOWN"}'
out="$(run http://x)"; rc=$?
assert_eq "health DOWN: exit 1" "1" "$rc"; assert_contains "names the health probe" "$out" "health"

good; serve "_" 200 "text/html" '<html>no shell here</html>'
out="$(run http://x)"; rc=$?
assert_eq "SPA shell missing: exit 1" "1" "$rc"

good; serve "_api_home" 200 application/json '{"error":"x"}'
out="$(run http://x)"; rc=$?
assert_eq "api open to a signed-out caller: exit 1" "1" "$rc"

good; serve "_api_home" 401 text/html '<html>"error"</html>'
out="$(run http://x)"; rc=$?
assert_eq "401 that is HTML not JSON: exit 1" "1" "$rc"

good; rm "$TMP/stub/_actuator_health.code"
out="$(run http://x)"; rc=$?
assert_eq "no answer at all: exit 1" "1" "$rc"

out="$(run ftp://x)"; rc=$?
assert_eq "bad URL: exit 4" "4" "$rc"

finish_tests
