#!/bin/sh
# Start Caddy only with a well-formed Cloudflare token. Caddy's Cloudflare module names a malformed token in its
# start-up error, which would persist the credential in `docker logs`; this refuses first and never prints the value
# (nor its length, prefix or characters). No `set -x`, and the variable is never echoed.
# Well-formed: 40 to 200 characters, only A-Z a-z 0-9 _ - (so no whitespace, newline, quotes or braces).
# deploy.sh (scripts/lib/release-lib.sh, token_is_wellformed) applies the same rule before compose.

token="${CLOUDFLARE_API_TOKEN:-}"
case "$token" in
    *[!A-Za-z0-9_-]*) token_ok=no ;;
    *) token_ok=yes ;;
esac
if [ "$token_ok" = yes ] && { [ "${#token}" -lt 40 ] || [ "${#token}" -gt 200 ]; }; then
    token_ok=no
fi
unset token
if [ "$token_ok" != yes ]; then
    echo "CLOUDFLARE_API_TOKEN is malformed (expected 40+ URL-safe characters); fix ~/cinefilm/.env and rotate the token if it was ever logged" >&2
    exit 1
fi

exec caddy run --config /etc/caddy/Caddyfile --adapter caddyfile
