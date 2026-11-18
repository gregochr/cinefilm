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
case "$url" in https://proxy.test/*) slug="_proxy${slug}" ;; esac
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
proxy_good() {
    serve "_proxy_" 200 "text/html;charset=UTF-8" '<html><div id="root"></div></html>'
    serve "_proxy_api_home" 401 application/json '{"error":"Authentication required"}'
}
run() { STUB="$TMP/stub" PATH="$TMP/bin:$PATH" SMOKE_ATTEMPTS=1 SMOKE_RETRY_SLEEP=0 SMOKE_PROXY_ATTEMPTS=1 SMOKE_PROXY_SLEEP=0 bash "${HERE}/../post-deploy-smoke.sh" "$@" 2>&1; }

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

SHA=0123456789abcdef0123456789abcdef01234567
good; serve "_actuator_info" 200 application/json "{\"git\":{\"commit\":{\"id\":{\"full\":\"${SHA}\"}}}}"
out="$(EXPECT_COMMIT=$SHA run http://x)"; rc=$?
assert_eq "running build matches the expected commit: exit 0" "0" "$rc"

serve "_actuator_info" 200 application/json '{"git":{"commit":{"id":{"full":"ffffffffffffffffffffffffffffffffffffffff"}}}}'
out="$(EXPECT_COMMIT=$SHA run http://x)"; rc=$?
assert_eq "running build is a different commit: exit 1" "1" "$rc"
assert_contains "names the build probe" "$out" "running build"

out="$(run http://x)"; rc=$?
assert_eq "no EXPECT_COMMIT: the commit is not checked" "0" "$rc"

out="$(run ftp://x)"; rc=$?
assert_eq "bad URL: exit 4" "4" "$rc"

# --- through the Caddy proxy (SMOKE_PROXY_URL) ---
good; proxy_good
out="$(SMOKE_PROXY_URL=https://proxy.test run http://x)"; rc=$?
assert_eq "proxy answers: exit 0" "0" "$rc"
assert_contains "the proxy probes ran" "$out" "proxy spa index"

good; rm "$TMP/stub/_proxy_.code"
out="$(SMOKE_PROXY_URL=https://proxy.test run http://x)"; rc=$?
assert_eq "proxy down, app fine: exit 3 (inconclusive, never a rollback)" "3" "$rc"
assert_contains "names the proxy" "$out" "proxy"

good; proxy_good; serve "_proxy_api_home" 200 application/json '{"error":"x"}'
out="$(SMOKE_PROXY_URL=https://proxy.test run http://x)"; rc=$?
assert_eq "api open to a signed-out caller through the proxy: exit 3" "3" "$rc"

good; proxy_good; serve "_actuator_health" 200 application/json '{"status":"DOWN"}'; rm "$TMP/stub/_proxy_.code"
out="$(SMOKE_PROXY_URL=https://proxy.test run http://x)"; rc=$?
assert_eq "app broken and proxy down: exit 1 wins" "1" "$rc"

good
out="$(SMOKE_PROXY_URL='' run http://x)"; rc=$?
assert_eq "empty SMOKE_PROXY_URL: the proxy is not checked" "0" "$rc"

out="$(SMOKE_PROXY_URL=http://proxy.test run http://x)"; rc=$?
assert_eq "proxy URL that is not https: exit 4" "4" "$rc"

# A bad number in the environment is a misconfiguration (4), never a failed release: [[ ]] would read it as arithmetic.
# `run_env VAR=value...` runs the script with those values in place of the defaults of `run`.
run_env() { STUB="$TMP/stub" PATH="$TMP/bin:$PATH" env SMOKE_ATTEMPTS=1 SMOKE_RETRY_SLEEP=0 SMOKE_PROXY_ATTEMPTS=1 SMOKE_PROXY_SLEEP=0 "$@" bash "${HERE}/../post-deploy-smoke.sh" http://x 2>&1; }

good
out="$(run_env SMOKE_ATTEMPTS=abc)"; rc=$?
assert_eq "SMOKE_ATTEMPTS that is not a number: exit 4" "4" "$rc"
assert_contains "names SMOKE_ATTEMPTS" "$out" "smoke: SMOKE_ATTEMPTS must be a whole number (got 'abc')"

out="$(run_env SMOKE_ATTEMPTS=)"; rc=$?
assert_eq "SMOKE_ATTEMPTS that is empty: the default applies, exit 0" "0" "$rc"

out="$(run_env SMOKE_ATTEMPTS=08)"; rc=$?
assert_eq "SMOKE_ATTEMPTS=08 is eight, not a bad octal: exit 0" "0" "$rc"

rm -f "$TMP/marker"
# shellcheck disable=SC2016 # the command substitution must reach the script unexpanded
out="$(run_env 'SMOKE_PROXY_ATTEMPTS=a[$(touch '"$TMP"'/marker)]')"; rc=$?
assert_eq "SMOKE_PROXY_ATTEMPTS with a command inside: exit 4" "4" "$rc"
assert_contains "names SMOKE_PROXY_ATTEMPTS" "$out" "smoke: SMOKE_PROXY_ATTEMPTS must be a whole number"
assert_file_missing "the command in SMOKE_PROXY_ATTEMPTS did not run" "$TMP/marker"

out="$(run_env SMOKE_RETRY_SLEEP=1.5)"; rc=$?
assert_eq "SMOKE_RETRY_SLEEP that is not whole: exit 4" "4" "$rc"
assert_contains "names SMOKE_RETRY_SLEEP" "$out" "smoke: SMOKE_RETRY_SLEEP must be a whole number (got '1.5')"

out="$(run_env SMOKE_PROXY_SLEEP=-1)"; rc=$?
assert_eq "SMOKE_PROXY_SLEEP that is negative: exit 4" "4" "$rc"
assert_contains "names SMOKE_PROXY_SLEEP" "$out" "smoke: SMOKE_PROXY_SLEEP must be a whole number (got '-1')"

out="$(run_env SMOKE_ATTEMPTS=0)"; rc=$?
assert_eq "SMOKE_ATTEMPTS=0 (no probe would run): exit 4" "4" "$rc"
assert_contains "says it must be at least 1" "$out" "smoke: SMOKE_ATTEMPTS must be at least 1 (got '0')"

out="$(run_env SMOKE_PROXY_ATTEMPTS=00)"; rc=$?
assert_eq "SMOKE_PROXY_ATTEMPTS=00: exit 4" "4" "$rc"
assert_contains "says it must be at least 1" "$out" "smoke: SMOKE_PROXY_ATTEMPTS must be at least 1 (got '00')"

finish_tests
