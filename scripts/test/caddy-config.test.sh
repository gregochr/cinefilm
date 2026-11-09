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

finish_tests
