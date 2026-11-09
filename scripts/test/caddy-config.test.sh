#!/bin/bash
# The proxy's safety properties, read from the committed files only (no Docker, no network): it never listens on port
# 80, never binds a wildcard address, takes the token from the environment, and compose passes it only what it needs.
# Everything is checked through files and here-strings, never `printf | grep -q`: with pipefail the early exit of
# grep -q can SIGPIPE the printf and flip a result on a large input.

# The needles are literal Caddy and compose placeholders ({$VAR}, ${VAR:?}), never meant to expand here.
# shellcheck disable=SC2016

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
# shellcheck source=scripts/test/helpers.sh
. "${HERE}/helpers.sh"

CADDYFILE="$REPO/deploy/caddy/Caddyfile"
DOCKERFILE="$REPO/deploy/caddy/Dockerfile"
COMPOSE="$REPO/deploy/docker-compose.yml"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
CODE="$TMP/caddyfile.code"
PROXY="$TMP/proxy.service"
# Strip comments so a warning in prose cannot satisfy or fail a check.
sed -E 's/#.*$//' "$CADDYFILE" > "$CODE"
# Just the cinefilm-proxy service block of the compose file.
awk '/^  cinefilm-proxy:/{f=1;next} /^  [a-z]/{f=0} f' "$COMPOSE" > "$PROXY"

# has_not DESCRIPTION FILE ERE: passes when the file has no line matching the extended regex.
has_not() {
    if grep -Eq -- "$3" "$2"; then bad "$1"; else ok "$1"; fi
}

assert_file_has "no admin API" "$CODE" "admin off"
assert_file_has "no HTTP redirect listener" "$CODE" "auto_https disable_redirects"
assert_file_has "the HTTP port is moved off 80" "$CODE" "http_port 18080"
assert_file_has "the site is bound to the tailnet IPv4" "$CODE" '{$CINEFILM_TAILNET_IPV4}'
assert_file_has "the site is bound to the tailnet IPv6" "$CODE" '{$CINEFILM_TAILNET_IPV6}'
assert_file_has "the token comes from the environment" "$CODE" "dns cloudflare {env.CLOUDFLARE_API_TOKEN}"
assert_file_has "streaming is not buffered" "$CODE" "flush_interval -1"
assert_file_has "the cookie is kept out of the access log" "$CODE" "request>headers>Cookie delete"
assert_file_has "the Authorization header is kept out of the access log" "$CODE" "request>headers>Authorization delete"
has_not "no wildcard address and no port 80 in the Caddyfile" "$CODE" '0\.0\.0\.0|\[::\]|:80([^0-9]|$)|http_port 80([^0-9]|$)'

# Pins: exact versions, no floating tags.
assert_file_has "Caddy version is pinned" "$DOCKERFILE" "ARG CADDY_VERSION=2."
assert_file_has "the Cloudflare module is pinned" "$DOCKERFILE" "ARG CLOUDFLARE_DNS_VERSION=v"
has_not "no floating Caddy tag" "$DOCKERFILE" '^FROM .*(:latest|:2( |$)|:builder)'

# Compose: the proxy service.
assert_file_has "the proxy service exists" "$PROXY" "image: ghcr.io/gregochr/cinefilm-caddy:"
assert_file_has "host networking (binds the tailnet IPs, reaches loopback:8090)" "$PROXY" "network_mode: host"
assert_file_has "restarts until the tailnet interface is up" "$PROXY" "restart: unless-stopped"
assert_file_has "the token is guarded" "$PROXY" 'CLOUDFLARE_API_TOKEN: ${CLOUDFLARE_API_TOKEN:?'
assert_file_has "the IPv4 is guarded" "$PROXY" 'CINEFILM_TAILNET_IPV4: ${CINEFILM_TAILNET_IPV4:?'
assert_file_has "the IPv6 is guarded" "$PROXY" 'CINEFILM_TAILNET_IPV6: ${CINEFILM_TAILNET_IPV6:?'
assert_file_has "the domain has a default" "$PROXY" 'CINEFILM_DOMAIN: ${CINEFILM_DOMAIN:-cinefilm.chrisjgregory.dev}'
assert_file_has "certificates persist" "$PROXY" "/caddy/data:/data"
has_not "the proxy does not get the whole .env (database password, tokens)" "$PROXY" 'env_file'
has_not "no ports mapping (host networking cannot publish ports)" "$PROXY" '^\s*ports:'
has_not "the proxy does not mount the films" "$PROXY" '/media|workhorse'

# The image's entrypoint: refuses a malformed token without ever printing it, starts caddy only for a good one.
assert_file_has "the image runs the entrypoint" "$DOCKERFILE" 'ENTRYPOINT ["/entrypoint.sh"]'
ENTRY="$REPO/deploy/caddy/entrypoint.sh"
sed -E 's/^[[:space:]]*#.*$//' "$ENTRY" > "$TMP/entrypoint.code"
has_not "the entrypoint never traces or echoes the token" "$TMP/entrypoint.code" 'set -[a-z]*x|(echo|printf)[^|;&]*\$[{(]?(token|CLOUDFLARE_API_TOKEN)'
mkdir -p "$TMP/bin"
printf '#!/bin/sh\necho "caddy-started $*"\n' > "$TMP/bin/caddy"
chmod +x "$TMP/bin/caddy"

GOOD40="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmn"
# entry_run TOKEN: runs the entrypoint with the stub caddy; output in $OUT, exit code in $RC.
entry_run() {
    OUT="$(env PATH="$TMP/bin:$PATH" CLOUDFLARE_API_TOKEN="$1" sh "$ENTRY" 2>&1)"
    RC=$?
}
entry_refuses() { # DESCRIPTION TOKEN
    entry_run "$2"
    if [ "$RC" -ne 0 ]; then ok "entrypoint refuses: $1"; else bad "entrypoint accepted: $1"; fi
    assert_not_contains "entrypoint prints no token text: $1" "$OUT" "ABCDEFGH"
    assert_not_contains "entrypoint starts no caddy: $1" "$OUT" "caddy-started"
    assert_contains "entrypoint says it is malformed: $1" "$OUT" "CLOUDFLARE_API_TOKEN is malformed"
}
entry_refuses "trailing space" "${GOOD40} "
entry_refuses "trailing newline" "${GOOD40}"$'\n'
entry_refuses "embedded newline" "${GOOD40:0:20}"$'\n'"${GOOD40:20}"
entry_refuses "double quotes" "\"${GOOD40}\""
entry_refuses "single quotes" "'${GOOD40}'"
entry_refuses "braces" "{${GOOD40}}"
entry_refuses "short (39)" "${GOOD40:0:39}"
entry_refuses "too long (201)" "${GOOD40}$(printf 'x%.0s' $(seq 1 161))"
entry_refuses "empty" ""
entry_run "$GOOD40"
assert_eq "a 40-character token starts caddy" "0" "$RC"
assert_contains "caddy runs the baked Caddyfile" "$OUT" "caddy-started run --config /etc/caddy/Caddyfile --adapter caddyfile"
entry_run "cfut_${GOOD40}_-"
assert_eq "a longer URL-safe token (with _ and -) starts caddy" "0" "$RC"
entry_run "${GOOD40}$(printf 'x%.0s' $(seq 1 160))"
assert_eq "a 200-character token starts caddy" "0" "$RC"

finish_tests
