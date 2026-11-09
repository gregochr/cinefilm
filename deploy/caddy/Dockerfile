# syntax=docker/dockerfile:1
# Caddy with the Cloudflare DNS module, for the tailnet-only reverse proxy (deploy/caddy/Caddyfile). Only the official
# caddy images are used; the one third-party piece is the pinned caddy-dns/cloudflare module, compiled in by xcaddy.
# Build from this folder:  docker build -t cinefilm-caddy deploy/caddy
# Bump both pins together and by hand (the module's go.mod must support the Caddy version); CI builds this on every PR.

ARG CADDY_VERSION=2.11.8
ARG CLOUDFLARE_DNS_VERSION=v0.2.4

FROM caddy:${CADDY_VERSION}-builder AS builder
ARG CADDY_VERSION
ARG CLOUDFLARE_DNS_VERSION
RUN xcaddy build "v${CADDY_VERSION}" --with "github.com/caddy-dns/cloudflare@${CLOUDFLARE_DNS_VERSION}"

FROM caddy:${CADDY_VERSION}
COPY --from=builder /usr/bin/caddy /usr/bin/caddy
COPY Caddyfile /etc/caddy/Caddyfile
# Refuses a malformed CLOUDFLARE_API_TOKEN before Caddy can echo it into the logs, then execs caddy run.
COPY --chmod=0755 entrypoint.sh /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
CMD []
