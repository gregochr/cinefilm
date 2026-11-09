#!/bin/bash
# The proxy's safety properties, read from the committed files only (no Docker, no network): it never listens on port
# 80, never binds a wildcard address, takes the token from the environment, and compose passes it only what it needs.

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
# shellcheck source=scripts/test/helpers.sh
. "${HERE}/helpers.sh"

CADDYFILE="$REPO/deploy/caddy/Caddyfile"
DOCKERFILE="$REPO/deploy/caddy/Dockerfile"
COMPOSE="$REPO/deploy/docker-compose.yml"

# Strip comments so a warning in prose cannot satisfy or fail a check.
code="$(sed -E 's/#.*$//' "$CADDYFILE")"

assert_contains "no admin API" "$code" "admin off"
assert_contains "no HTTP redirect listener" "$code" "auto_https disable_redirects"
assert_contains "the HTTP port is moved off 80" "$code" "http_port 18080"
assert_contains "the site is bound to the tailnet IPv4" "$code" '{$CINEFILM_TAILNET_IPV4}'
assert_contains "the site is bound to the tailnet IPv6" "$code" '{$CINEFILM_TAILNET_IPV6}'
assert_contains "the token comes from the environment" "$code" "dns cloudflare {env.CLOUDFLARE_API_TOKEN}"
assert_contains "streaming is not buffered" "$code" "flush_interval -1"
assert_contains "the cookie is kept out of the access log" "$code" "request>headers>Cookie delete"
assert_contains "the Authorization header is kept out of the access log" "$code" "request>headers>Authorization delete"
if printf '%s' "$code" | grep -Eq '0\.0\.0\.0|\[::\]|:80([^0-9]|$)|http_port 80([^0-9]|$)'; then
    bad "the Caddyfile names a wildcard address or port 80"
else
    ok "no wildcard address and no port 80"
fi

# Pins: exact versions, no floating tags.
assert_contains "Caddy version is pinned" "$(cat "$DOCKERFILE")" "ARG CADDY_VERSION=2."
assert_contains "the Cloudflare module is pinned" "$(cat "$DOCKERFILE")" "ARG CLOUDFLARE_DNS_VERSION=v"
if grep -Eq '^FROM .*(:latest|:2( |$)|:builder)' "$DOCKERFILE"; then bad "the Dockerfile uses a floating Caddy tag"; else ok "no floating Caddy tag"; fi

# Compose: the proxy service.
proxy="$(awk '/^  cinefilm-proxy:/{f=1;next} /^  [a-z]/{f=0} f' "$COMPOSE")"
assert_contains "host networking (binds the tailnet IPs, reaches loopback:8090)" "$proxy" "network_mode: host"
assert_contains "restarts until the tailnet interface is up" "$proxy" "restart: unless-stopped"
assert_contains "the token is guarded" "$proxy" 'CLOUDFLARE_API_TOKEN: ${CLOUDFLARE_API_TOKEN:?'
assert_contains "the IPv4 is guarded" "$proxy" 'CINEFILM_TAILNET_IPV4: ${CINEFILM_TAILNET_IPV4:?'
assert_contains "the IPv6 is guarded" "$proxy" 'CINEFILM_TAILNET_IPV6: ${CINEFILM_TAILNET_IPV6:?'
assert_contains "the domain has a default" "$proxy" 'CINEFILM_DOMAIN: ${CINEFILM_DOMAIN:-cinefilm.chrisjgregory.dev}'
assert_contains "certificates persist" "$proxy" "/caddy/data:/data"
if printf '%s' "$proxy" | grep -q 'env_file'; then bad "the proxy must not receive the whole .env (database password, tokens)"; else ok "the proxy does not get the whole .env"; fi
if printf '%s' "$proxy" | grep -Eq '^\s*ports:'; then bad "network_mode host cannot publish ports"; else ok "no ports mapping"; fi
if printf '%s' "$proxy" | grep -q '/media\|workhorse'; then bad "the proxy must not mount the films"; else ok "the proxy does not mount the films"; fi

finish_tests
