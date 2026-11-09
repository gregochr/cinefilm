#!/bin/bash
# The application's production defaults and the deploy's mounts must name the same folders, or the film library
# cannot be read and subtitles cannot be written (a subtitle request would 404 in production). Reads the committed files
# only: no Docker, no network.

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
# shellcheck source=scripts/test/helpers.sh
. "${HERE}/helpers.sh"

PROPS="$REPO/backend/src/main/resources/application.properties"
COMPOSE="$REPO/deploy/docker-compose.yml"

# default_of KEY: the default inside  key=${ENV_VAR:default}
default_of() {
    grep -E "^$1=" "$PROPS" | head -1 | sed -E 's/^[^=]*=\$\{[A-Z_]+:(.*)\}$/\1/'
}

cache_default="$(default_of cinefilm.cache.dir)"
library_default="$(default_of cinefilm.library.root)"

# The compose volume line for the cache, e.g.  - ${CINEFILM_DATA_DIR:-...}/cache:/app/cache
cache_mount="$(grep -E '/cache:/' "$COMPOSE" | head -1 | sed -E 's/.*\/cache:([^ ]*).*/\1/')"
library_mount="$(awk '/target: \/media/ {print $2}' "$COMPOSE" | head -1)"

assert_eq "the application's production cache default is set" "/app/cache" "$cache_default"
assert_eq "compose mounts the writable cache where the application writes it" "$cache_default" "$cache_mount"
assert_eq "compose mounts the films where the application reads them" "$library_default" "$library_mount"

# One source of truth: compose must not set the variables to something else.
env_cache="$(grep -E 'CINEFILM_CACHE_DIR *:' "$COMPOSE" | sed -E 's/.*: *//' || true)"
if [ -n "$env_cache" ]; then
    assert_eq "compose's CINEFILM_CACHE_DIR agrees with the mount" "$cache_mount" "$env_cache"
else
    ok "compose leaves the cache folder to the application default"
fi
env_library="$(grep -E 'CINEFILM_LIBRARY_ROOT *:' "$COMPOSE" | sed -E 's/.*: *//' || true)"
if [ -n "$env_library" ]; then
    assert_eq "compose's CINEFILM_LIBRARY_ROOT agrees with the mount" "$library_mount" "$env_library"
else
    ok "compose leaves the library folder to the application default"
fi

# Artwork imported from Plex is persistent data, not cache: it has its own mount, which must be where the application writes.
artwork_default="$(default_of cinefilm.artwork.dir)"
artwork_mount="$(grep -E '/artwork:/' "$COMPOSE" | head -1 | sed -E 's/.*\/artwork:([^ ]*).*/\1/')"
assert_eq "the application's production artwork default is set" "/app/artwork" "$artwork_default"
assert_eq "compose mounts the artwork folder where the application writes it" "$artwork_default" "$artwork_mount"
if [ "$artwork_mount" != "$cache_mount" ]; then ok "artwork is not on the cache mount"; else bad "artwork shares the cache mount"; fi

# The image runs as an unprivileged user that owns /app only: the cache folder must be under it.
case "$cache_default" in
    /app/*) ok "the cache folder is under /app, which the image's user owns" ;;
    *) bad "the cache folder '$cache_default' is outside /app" ;;
esac

# The published host port in compose and the smoke script's default base URL must agree (the deploy smokes the loopback publish).
SMOKE="$REPO/scripts/post-deploy-smoke.sh"
compose_port="$(grep -E '"127\.0\.0\.1:[0-9]+:8080"' "$COMPOSE" | head -1 | sed -E 's/.*"127\.0\.0\.1:([0-9]+):8080".*/\1/')"
smoke_port="$(grep -E '^BASE_URL=' "$SMOKE" | head -1 | sed -E 's/.*127\.0\.0\.1:([0-9]+).*/\1/')"
assert_eq "compose publishes the host port on loopback" "8090" "$compose_port"
assert_eq "the smoke script's default base URL uses the compose host port" "$compose_port" "$smoke_port"

finish_tests
