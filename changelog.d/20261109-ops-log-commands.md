### Changed — operations runbook log commands

`operations.md` "Everyday commands" now reads logs with `docker logs cinefilm` / `cinefilm-proxy`, which work in any
shell, instead of `docker compose logs`, which fails with "required variable CINEFILM_VERSION is missing a value"
unless the version is exported first. It also notes that a tab left on the old `dockermacmini.tail0c74fa.ts.net`
address (port 443) fails every API call since the custom domain took that port.
