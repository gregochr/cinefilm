### Fixed — the deploy compose file failed to parse (v0.2.0 deploy)

`deploy/docker-compose.yml` had a `${CINEFILM_TAILNET_IPV6:?...}` guard whose message contained a colon-space inside an unquoted YAML scalar, so `docker compose` refused the file and the v0.2.0 deploy stopped before changing anything. Every `${VAR:?message}` value is now double-quoted and the message reworded. CI now runs `docker compose config --quiet` on the file with dummy values in the Docker job, and `scripts/test/compose-yaml.test.sh` checks the quoting and parses the YAML, so this cannot recur.
