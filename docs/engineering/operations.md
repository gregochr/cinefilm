# Operations: CineFilm on dockermacmini

How CineFilm runs in production, the one-time host setup, how to release and how to roll back. Background:
`foundation-plan.md` (Deployment topology) and `ci-test-deploy-plan.md` (phases 7 and 8; the names and ports table that
avoids goldenhour's). Backups, backup verification and the daily health workflow are phase 9 and are **not** set up yet.

## What runs where

| Thing | Value |
|---|---|
| Host | dockermacmini (Ubuntu), shared with goldenhour production |
| Image | `ghcr.io/gregochr/cinefilm:vX.Y.Z` (also `:latest`), one image: Spring Boot serves the SPA |
| Proxy image | `ghcr.io/gregochr/cinefilm-caddy:vX.Y.Z` (same tag as the app): Caddy 2.11.8 + `caddy-dns/cloudflare` v0.2.4, built from `deploy/caddy/` |
| Containers | `cinefilm-db` (postgres:17, no published port), `cinefilm` (`127.0.0.1:8090` to 8080; 8086 is used by vbgone on dockermacmini), `cinefilm-proxy` (Caddy, host network, binds only the two tailnet addresses on 443) |
| Compose | `~/cinefilm/docker-compose.yml` (copied from `deploy/` by every deploy), project `cinefilm`, secrets in `~/cinefilm/.env` |
| Data | `/home/gregochr/cinefilm-data/{postgres,logs,cache,artwork,caddy}` (`caddy` holds the proxy certificates; owned by uid 1001, except `postgres`, which Postgres owns); `cache` is the container's `/app/cache`, the application's default `CINEFILM_CACHE_DIR` (generated WebVTT subtitles), kept in step by `scripts/test/config-agreement.test.sh`; `artwork` is the container's `/app/artwork` (`CINEFILM_ARTWORK_DIR`, posters imported from Plex): **persistent data, not a cache, backed up with the database** (phase 9 must include it) |
| Pre-release dumps | `/home/gregochr/cinefilm-backups/pre-release/` (10 newest kept) |
| Media | workhorse share `Movies`, mounted read-only at `/mnt/workhorse-films`, seen in the container as `/media` |
| Remote access | **https://cinefilm.chrisjgregory.dev** (Caddy `cinefilm-proxy`, tailnet only; "Custom domain" below). Fallback: `tailscale serve` on :8443 to `127.0.0.1:8090`, https://dockermacmini.tail0c74fa.ts.net:8443 |
| Runner | second GitHub runner `~/actions-runner-cinefilm`, label `cinefilm-prod`, service |
| State files | `~/cinefilm/.deployed-version`, `~/cinefilm/.previous-version` (written by the deploy scripts) |

RAM is 15 GiB and the root disk is 98 G with only about 5.8 G free (94% used). Host ports in use: 22 53 80 5050 5432
5433 8081-8087 9000 (8086 is vbgone's frontend, hence CineFilm on 8090). Port 80 belongs to goldenhour: nothing of CineFilm's ever binds it
(Caddy has no redirect listener and uses the DNS challenge). 443 on the tailnet addresses is Caddy's; `tailscale serve`
moves to 8443 (Custom domain). RAM is not a constraint: the container is capped at `mem_limit: 2g` with the heap pinned
at `-Xmx512m` in `deploy/docker-compose.yml` (ffmpeg children live outside the heap). Disk is: see the
warning in the host setup below.

## One-time host setup (owner, on dockermacmini unless stated)

> **WARNING: free disk space before the first deploy.** The root disk is 94% full (about 5.8 G free) and the image
> pull plus Postgres need room. Free space first with `docker image prune -a` and `docker builder prune`. Do **not**
> run `docker volume prune`: it could delete other projects' data (goldenhour's volumes).

### 1. Check what Tailscale already serves, and the Docker group

```bash
tailscale status | head -3
tailscale serve status        # is port 443 already used (goldenhour)?
groups                        # must include docker
```

### 2. Directories

```bash
sudo mkdir -p /home/gregochr/cinefilm-data/{postgres,logs,cache,artwork,caddy/data,caddy/config} /home/gregochr/cinefilm-backups/pre-release
sudo chown -R 1001:1001 /home/gregochr/cinefilm-data/logs /home/gregochr/cinefilm-data/cache /home/gregochr/cinefilm-data/artwork
sudo chown gregochr:gregochr /home/gregochr/cinefilm-backups /home/gregochr/cinefilm-backups/pre-release
mkdir -p ~/cinefilm
```

(`postgres` stays as created; the Postgres container takes ownership on first start.)

### 3. `~/cinefilm/.env`

```bash
cd ~/cinefilm
# Get deploy/.env.example from the repo (browser: raw file on GitHub, or copy it over from your Mac with scp).
# ~/cinefilm/.env may already exist (it holds PLEX_TOKEN): NEVER overwrite it with `cp .env.example .env`.
# Append only the keys that are missing and keep what is there.
touch .env && chmod 600 .env
DBPW="$(openssl rand -hex 24)"; PW="$(openssl rand -base64 24 | tr -d '\n')"
grep -q '^CINEFILM_DB_PASSWORD=' .env || echo "CINEFILM_DB_PASSWORD=${DBPW}" >> .env
grep -q '^CINEFILM_PASSWORD=' .env || { echo "CINEFILM_PASSWORD=${PW}" >> .env; echo "Sign-in password: ${PW}"; }   # note it in your password manager, then clear the terminal
grep -q '^TMDB_READ_TOKEN=' .env || echo "TMDB_READ_TOKEN=" >> .env
grep -q '^OMDB_API_KEY=' .env || echo "OMDB_API_KEY=" >> .env
```

Compare the result with `deploy/.env.example` for any other keys it lists.

`TMDB_READ_TOKEN` stays empty until the TMDB phase. Never commit `.env`; never paste it anywhere.

**IMDb and Rotten Tomatoes ratings** come from OMDb and are off while `OMDB_API_KEY` is blank (Admin then says
*Ratings need an OMDb API key*). Get a free key at https://www.omdbapi.com/apikey.aspx (1,000 requests a day) and put
it in `~/cinefilm/.env` by editing the `OMDB_API_KEY=` line; do not paste it into chat or a commit. OMDb takes the key
in the request URL, so CineFilm never logs that URL. The app asks for at most 900 films a day (`cinefilm.omdb.daily-limit`,
counted per UTC day) and stops for the day when OMDb says its limit is reached. A film's ratings are refetched when
they are over 30 days old, after each library scan and Plex import, and daily at 05:30 (London). The first run also
fetches each existing film's IMDb id from TMDB (`/3/movie/{id}/external_ids`), so a large library fills in over a few
days. **Refresh ratings** in Admin (Library scan card) refetches every film, oldest first, within the same daily
budget.

**Plex import settings** (all in `~/cinefilm/.env`; the import is off while `PLEX_URL` or `PLEX_TOKEN` is blank):

```bash
grep -q '^PLEX_URL=' .env || echo 'PLEX_URL=http://100.92.70.50:32400' >> .env   # over Tailscale; WireGuard encrypts the hop
grep -q '^PLEX_PATH_PREFIX=' .env || echo 'PLEX_PATH_PREFIX=/Volumes/Media Library/Movies/' >> .env   # where Plex sees the library
```

`PLEX_TOKEN` is already there. **Keep Plex Media Server running on workhorse until the import report is right**; then it can be uninstalled. After the first deploy and a completed scan: Admin, **Import from Plex**, read the report (*In Plex, not on disk*, *On disk, not in Plex*, failures). The importer only GETs from `PLEX_URL`, never writes to Plex, and can be run again safely.

### 4. The second GitHub runner

On the GitHub website: repo `gregochr/cinefilm`, Settings, Actions, Runners, New self-hosted runner, Linux / X64. It
shows a download URL, a version and a one-hour registration token; use them below. Do **not** touch goldenhour's
runner in `~/actions-runner` (or wherever it lives).

```bash
mkdir -p ~/actions-runner-cinefilm && cd ~/actions-runner-cinefilm
# paste the "Download" and "Extract" commands from the GitHub page here, then:
./config.sh --url https://github.com/gregochr/cinefilm --token <TOKEN-FROM-GITHUB> \
  --name dockermacmini-cinefilm --labels cinefilm-prod --unattended
sudo ./svc.sh install gregochr
sudo ./svc.sh start
sudo ./svc.sh status
```

It must appear as Idle on the Runners page with the `cinefilm-prod` label. Check it restarts after a reboot
(`systemctl list-units 'actions.runner.*'` shows `actions.runner.gregochr-cinefilm.*`).

### 5. The film share on workhorse (macOS, on workhorse)

> **WARNING: share ONLY the `Movies` folder.** The disk `/Volumes/Media Library` also holds personal documents (tax
> returns, CVs, bank folders) and the irreplaceable `Pictures` folder. **Never share the whole `Media Library`
> volume, and never share `Pictures`.** `MediaBackup` and `TV Shows` are out of scope for CineFilm. A share of the
> whole disk would put all of that on the network (and inside a container) for the sake of a few films.

1. Create a dedicated **Sharing Only** user: System Settings, Users & Groups, Add User, type Sharing Only, name
   `cinefilm`. No admin rights.
2. System Settings, General, Sharing, turn on File Sharing, then (i) Options: tick SMB. Leave your own admin account
   **unticked** under "Windows File Sharing". macOS often leaves Sharing Only users out of that list, so enable the
   NTLM hash Linux needs from Terminal instead, **then** set the password (the hash is only made when a password is
   set): `sudo pwpolicy -u cinefilm -sethashtypes SMB-NT on`, then Users & Groups, (i) on `cinefilm`, Reset, a long
   random password (it goes in the credentials file below). Check: `sudo pwpolicy -u cinefilm -gethashtypes` lists
   `SMB-NT`.
3. Under Shared Folders click `+` and add **`/Volumes/Media Library/Movies`** (just that folder). Its share name is
   `Movies`. Remove any other shared folder (for example your Public folder).
4. With Movies selected: `cinefilm` **Read Only**, **Everyone** **No Access** (the Everyone row cannot be removed, only
   set to No Access).
5. **Make the share itself read-only and guest-free.** The `Media Library` disk has "Ignore ownership on this volume"
   on (`diskutil info "/Volumes/Media Library" | grep Owners` says `Disabled`). With ownership ignored, every SMB user
   is treated as the owner, so the folder permissions in step 4 do **not** stop writes. Only the share-level flag does:

   ```bash
   sudo sharing -e Movies -R 1 -g 000   # read-only for every SMB login, guest access off
   sudo sharing -l                      # Movies must show: read-only: 1, guest access: 0, and be the only share point
   ```

   Then turn File Sharing off and on. Leave ownership ignored on the disk: turning it back on can lock you and Plex
   out of files written by older Macs.
6. **Fix the folder's owner bits if step 4 stripped them.** Setting Everyone to No Access can leave the folder as
   `d---------`, which locks your own account and Plex out. `ls -led "/Volumes/Media Library/Movies"` must start
   `drwx------+`; if not, `sudo chmod u+rwx "/Volumes/Media Library/Movies"`.
7. **Test from another Mac as `cinefilm`** (eject any earlier connection first; Finder reuses an admin login
   silently, and an admin login shows the whole disk, which is not what CineFilm sees):

   ```bash
   # Finder, Cmd-K: smb://cinefilm@<workhorse-ip>/Movies   (do not save the password in the keychain)
   mount | grep '<workhorse-ip>'        # must show //cinefilm@<workhorse-ip>/Movies
   touch /Volumes/Movies/.cinefilm-write-test && echo "WRITABLE: stop and fix step 5" || echo "refused: good"
   ```

   If Finder asks for *your laptop's* password to copy a file, that is the refusal: click Cancel.

The films are flat files: `/Volumes/Media Library/Movies/<Title>.m4v`. Workhorse's hostname is `Study`
(`Study.local`); its LAN address was `192.168.0.101` when this was set up, and its Tailscale name is `study`.

**Surviving a power cut while nobody is home.** Workhorse has no automatic login (keep it that way) and FileVault
is off, so after a power cut it boots to the login window. To make the films come back without a login:

- System Settings, Energy: **Prevent automatic sleeping** on, **Start up automatically after a power failure** on,
  **Put hard disks to sleep** off.
- Mount USB disks at boot without a login:
  `sudo defaults write /Library/Preferences/SystemConfiguration/autodiskmount AutomountDisksWithoutUserLogin -bool true`
  (check with `sudo defaults read /Library/Preferences/SystemConfiguration/autodiskmount`).

File sharing then serves `Movies` from the login window. Plex and the Tailscale app run only after a login, so
until someone logs in workhorse is reachable from dockermacmini over the LAN but not remotely over Tailscale.

### 6. Mount it on dockermacmini, read-only

```bash
sudo apt-get install -y cifs-utils
sudo mkdir -p /mnt/workhorse-films
sudo install -m 600 /dev/null /root/.smb-workhorse
sudo tee /root/.smb-workhorse >/dev/null <<'EOF'
username=cinefilm
password=<THE-PASSWORD-YOU-SET-ON-WORKHORSE>
EOF
echo '//192.168.0.101/Movies /mnt/workhorse-films cifs ro,credentials=/root/.smb-workhorse,vers=3.0,uid=1001,gid=1001,_netdev,nofail,x-systemd.automount 0 0' | sudo tee -a /etc/fstab
sudo systemctl daemon-reload
ls /mnt/workhorse-films
```

`Study.local` does not resolve on dockermacmini, so the fstab line uses workhorse's LAN IP `192.168.0.101`. It is the LAN
address on purpose: it keeps working when workhorse's Tailscale app is logged out after a power cut.

The `touch` check below only proves the Linux mount is `ro`. The share's own read-only flag is proved by step 5's
`sharing -l` and step 7's test from a Mac; both layers must hold.

**Verify (do not skip):**

```bash
ls /mnt/workhorse-films            # must list ONLY .m4v film files: no Pictures, no documents, no folders you don't expect
touch /mnt/workhorse-films/x       # must FAIL with "Read-only file system"
findmnt /mnt/workhorse-films       # options must include ro
```

If `ls` shows anything that is not a film, **stop**: `sudo umount /mnt/workhorse-films` and fix the share on workhorse
(steps 3 to 5). Nothing in CineFilm's scripts writes under `/mnt/workhorse-films`; the deploy only does a read-only `ls` and
a `mountpoint` check, and warns (never fails) when it is not mounted.

### 7. Tailscale HTTPS (the fallback URL; the main one is "Custom domain" below)

Enable MagicDNS and HTTPS certificates in the Tailscale admin console (DNS page) if they are not already on. Then,
according to `tailscale serve status` from step 1:

```bash
# The fallback on 8443 (443 belongs to the Caddy proxy, see "Custom domain"):
sudo tailscale serve --bg --https=8443 http://127.0.0.1:8090

tailscale serve status        # shows the URL
```

The URL is `https://dockermacmini.tail0c74fa.ts.net:8443`. (Before the custom domain existed, `--https=443` was used
and the URL had no port; "Custom domain" step 5 moves it.) Open it on the Mac and the iPhone (both on the tailnet).
Never use `tailscale funnel` and never open a router port: CineFilm is tailnet-only (`SECURITY.md` rule 6).

## Custom domain: https://cinefilm.chrisjgregory.dev (owner, one time)

The app is served at `https://cinefilm.chrisjgregory.dev` by `cinefilm-proxy`, a Caddy container (`deploy/caddy/`)
that is deployed with every release. It binds **only** the host's two tailnet addresses on 443, gets its certificate
from Let's Encrypt with the **DNS challenge** through the Cloudflare API (so it never needs port 80, which is
goldenhour's), and proxies to the app on `127.0.0.1:8090`. Do these steps **in order, before the first release that
includes the proxy**; `scripts/deploy.sh` stops with exit 4 before touching anything if one of the three `.env` keys
is missing.

**1. Cloudflare DNS records (dashboard, zone `chrisjgregory.dev`, DNS, Records).** Add two records, both with the
proxy status **DNS only (grey cloud)**:

| Type | Name | Content | Proxy status |
|---|---|---|---|
| A | `cinefilm` | `100.76.73.16` | DNS only |
| AAAA | `cinefilm` | `fd7a:115c:a1e0::6501:49b3` | DNS only |

It must be grey cloud: an orange-cloud (proxied) record would publish Cloudflare's addresses instead of the tailnet
ones, send the traffic to Cloudflare (a public path that cannot reach a tailnet-only host, and exactly the exposure
this setup avoids), and break the certificate flow. Do not use a Cloudflare Tunnel either.

**2. A scoped API token (Cloudflare dashboard, My Profile, API Tokens, Create Token, Custom token).** Name it
`cinefilm-caddy-dns`. Permissions: **Zone, DNS, Edit** and **Zone, Zone, Read** (the module looks the zone up by name,
so it needs the read permission too; the two together are the module's documented "single token"). Zone Resources:
Include, Specific zone, `chrisjgregory.dev`, nothing wider. No other permissions. Copy the token once; it is shown
only once. Never paste it into chat, an issue or the repo.

**3. Put the token and the tailnet addresses in `~/cinefilm/.env` (on dockermacmini).** The snippet reads the token
without echoing it, appends only keys that are missing (never overwrites the file) and keeps mode 600. The token
never appears in the shell history or the terminal:

```bash
cd ~/cinefilm
touch .env && chmod 600 .env
[ -z "$(tail -c1 .env)" ] || echo >> .env                    # make sure the last line ends in a newline
read -rsp 'Cloudflare API token (input hidden): ' CF_TOKEN; echo
grep -q '^CLOUDFLARE_API_TOKEN=' .env || printf 'CLOUDFLARE_API_TOKEN=%s\n' "$CF_TOKEN" >> .env
unset CF_TOKEN
grep -q '^CINEFILM_TAILNET_IPV4=' .env || echo "CINEFILM_TAILNET_IPV4=$(tailscale ip -4)" >> .env
grep -q '^CINEFILM_TAILNET_IPV6=' .env || echo "CINEFILM_TAILNET_IPV6=$(tailscale ip -6)" >> .env
ls -l .env                                                   # -rw------- (600)
cut -d= -f1 .env | grep -E 'CLOUDFLARE|TAILNET'              # the key names only, never the values
tailscale ip -4; tailscale ip -6                             # should be 100.76.73.16 and fd7a:115c:a1e0::6501:49b3
```

(`CINEFILM_DOMAIN` is optional; it defaults to `cinefilm.chrisjgregory.dev`.) If a key already exists with a wrong
value, edit the file by hand; the snippet leaves existing keys alone.

**4. The Caddy data directory.** Certificates and the ACME account live here, so a redeploy never re-issues them
(Let's Encrypt rate limits). Created by step 2 of the host setup; on an existing host:

```bash
sudo mkdir -p /home/gregochr/cinefilm-data/caddy/data /home/gregochr/cinefilm-data/caddy/config
```

**5. Move `tailscale serve` off 443** (Caddy needs 443 on the tailnet addresses). Add the 8443 fallback first, then
turn the old 443 config off, then check:

```bash
sudo tailscale serve --bg --https=8443 http://127.0.0.1:8090   # the fallback: https://dockermacmini.tail0c74fa.ts.net:8443
sudo tailscale serve --https=443 off                          # the old config: https://dockermacmini.tail0c74fa.ts.net
tailscale serve status                                        # only :8443 is listed
```

Check the fallback URL answers from the Mac before going on. Never `tailscale funnel`.

**6. Release** (`scripts/release.sh`, below). The deploy pulls both images, starts `cinefilm-proxy`, and the smoke
also probes `https://cinefilm.chrisjgregory.dev/` and `/api/home` through it (up to about two minutes, because the
first start has to obtain the certificate). If only the proxy probes fail, the smoke exits 3 (inconclusive): the
release is **not** rolled back, because an image rollback cannot fix DNS, a token or a certificate.

**7. Verify** (the host, then a tailnet device, then a device that is off the tailnet):

```bash
cd ~/cinefilm && export CINEFILM_VERSION=$(cat .deployed-version)
docker compose ps                                                   # cinefilm-proxy Up (healthy)
docker logs --tail 40 cinefilm-proxy                                # "certificate obtained successfully", no errors
dig +short cinefilm.chrisjgregory.dev @1.1.1.1                      # 100.76.73.16 (and AAAA: fd7a:...)
curl -sSI https://cinefilm.chrisjgregory.dev/ | head -3             # HTTP/2 200, valid certificate
ss -ltn | grep -E ':(80|443) '                                      # 443 only on 100.76.73.16 and [fd7a:...], never *:443 or 0.0.0.0; :80 is goldenhour's, unchanged
```

Then open https://cinefilm.chrisjgregory.dev on the Mac and the iPhone (on the tailnet), sign in and play a film. With
Tailscale off on the phone the site must **not** load (the address is not routable).

**Things to know.**

- **Certificate Transparency.** Every Let's Encrypt certificate is published in public Certificate Transparency logs,
  so the name `cinefilm.chrisjgregory.dev` is public knowledge. That is accepted: it reveals only that a name exists.
- **The public A record exposes only an unroutable tailnet address.** `100.76.73.16` is in the CGNAT range (and the
  AAAA is a tailnet ULA): not reachable from the internet, and reachable on the tailnet only by devices the tailnet
  admits. The access control is Tailscale, not the name.
- **Renewal** is automatic (Caddy renews about 30 days before expiry, over the same DNS challenge). Rotating the
  token: create a new one, edit the `CLOUDFLARE_API_TOKEN` line in `.env` by hand, `docker compose up -d cinefilm-proxy`
  (compose recreates it with the new value), then delete the old token in Cloudflare.
- **A malformed token is refused before Caddy starts.** Caddy's Cloudflare module would name a malformed token in its
  start-up error, which would persist the credential in `docker logs cinefilm-proxy`. So the proxy image's entrypoint
  (and `deploy.sh`, exit 4, before anything changes) only accept 40 to 200 characters of `A-Z a-z 0-9 _ -`: no spaces,
  newline, quotes or braces. The error is "CLOUDFLARE_API_TOKEN is malformed" and never shows the value, its length or
  any character. Paste the token exactly as Cloudflare gave it, unquoted. If a token was ever logged anyway (an older
  release, a shared terminal), rotate it in Cloudflare and do not share that log.
- **Boot order.** If `tailscale0` comes up after Docker, Caddy cannot bind, exits, and Docker restarts it until the
  address exists. If the proxy is not up after a reboot, check `tailscale status` first.
- Port 80 is never bound: there is no HTTP redirect, so `http://cinefilm.chrisjgregory.dev` simply does not answer.

**Roll back to `tailscale serve` on 443 (and remove the proxy).** Stop the proxy first (it frees 443), then give the
port back to `tailscale serve`:

```bash
cd ~/cinefilm && export CINEFILM_VERSION=$(cat .deployed-version)
docker compose stop cinefilm-proxy && docker compose rm -f cinefilm-proxy
sudo tailscale serve --bg --https=443 http://127.0.0.1:8090
sudo tailscale serve --https=8443 off                         # optional: drop the fallback
tailscale serve status                                         # https://dockermacmini.tail0c74fa.ts.net on 443 again
```

The proxy comes back at the next deploy unless the compose file is changed (revert the PR that added it);
`scripts/deploy.sh` also requires the three `.env` keys until then. The Cloudflare records and token can be deleted in
the dashboard once the proxy is gone.

### 8. GitHub repository variable (optional, after the first release)

Settings, Secrets and variables, Actions, Variables: `SMOKE_ENFORCE`. Leave it unset (report-only) for the first
release; set it to `true` once a release has deployed cleanly, so a broken release rolls back automatically.
`GITHUB_TOKEN` already has package read for the deploy job, so there is no PAT to create. After the first release,
if the GHCR package is private (the default) nothing more is needed: the deploy job logs the host's Docker in.

## Release

The owner runs this; sessions never tag.

```bash
git checkout main && git pull
scripts/release.sh v0.1.0      # or without an argument for a 1/2/3 patch/minor/major menu
```

It checks you are on a clean, in-sync `main`, tags, pushes the tag and watches the **Deploy** workflow:
CI (reused, includes `Docker — image builds`), build and push to GHCR, then on dockermacmini `scripts/deploy.sh`:

1. guards (a database must exist at `$CINEFILM_DATA_DIR/postgres`, default `/home/gregochr/cinefilm-data`, once something was deployed before);
2. `pg_dump` (starting `cinefilm-db` first if it is stopped on an existing deployment; skipped only on a genuine first deploy) of the live database into `cinefilm-backups/pre-release/<version>-<timestamp>.dump` (a failed dump stops the deploy);
3. pull the app and proxy images, `docker compose up -d`, wait for the app container to be healthy;
4. smoke on `http://127.0.0.1:8090`: `/actuator/health` 200 UP, `/` serves the SPA, `/api/home` is 401 JSON, `/actuator/info` reports the tagged commit; then `/` and `/api/home` again through `https://cinefilm.chrisjgregory.dev` (exit 3, never a rollback, if only those fail);
5. smoke failed and `SMOKE_ENFORCE=true`: automatic image-only rollback (below). Otherwise the job just fails and says so.

Changelog promotion (`changelog.d/` into `CHANGELOG.md`) is not automated yet.

## Roll back

**Automatic (Plan A).** With `SMOKE_ENFORCE=true`, a release whose smoke fails is replaced by the previous tag
(`~/cinefilm/.previous-version`), the container is recreated and re-smoked. The deploy job still fails: a rolled-back
release is a failed release. **The database is never restored automatically**: migrations are expand-then-contract
(`dev-and-testing.md`) so the previous image runs on the new schema.

**Manual image rollback.** On dockermacmini:

```bash
cd ~/cinefilm
cat .deployed-version .previous-version
~/actions-runner-cinefilm/_work/cinefilm/cinefilm/scripts/auto-rollback.sh <failed-version>   # from any checkout of the repo
```

The script lives in the repo, so run it from any checkout of the tag (`git clone`/`git archive`); it needs only
`docker` and the files in `~/cinefilm`.

**Plan B: restore data (only if a migration or the app damaged data).** Stop the app, restore a dump, start the
previous version:

```bash
cd ~/cinefilm
docker compose stop cinefilm
ls -lt /home/gregochr/cinefilm-backups/pre-release/
docker exec -i cinefilm-db sh -c 'pg_restore -U "$POSTGRES_USER" -d cinefilm --clean --if-exists' \
  < /home/gregochr/cinefilm-backups/pre-release/<file>.dump
export CINEFILM_VERSION=$(cat .previous-version); docker compose up -d
```

This **discards everything written since that dump** (watch history included): a human decision.

## Everyday commands

Logs need nothing set up: `docker logs` reads a container by name and never parses the compose file.

```bash
docker logs -f cinefilm
docker logs --tail 40 cinefilm-proxy
docker logs --since 2026-11-09T21:45:00 --until 2026-11-09T22:05:00 cinefilm 2>&1 | grep -iE "error|exception"
```

Every `docker compose` command parses the compose file, which refuses to load without `CINEFILM_VERSION` ("required
variable CINEFILM_VERSION is missing a value"), so export it first in the same shell:

```bash
cd ~/cinefilm && export CINEFILM_VERSION=$(cat .deployed-version)
docker compose ps
docker compose restart cinefilm
curl -s http://127.0.0.1:8090/actuator/health
curl -sI https://cinefilm.chrisjgregory.dev/ | head -1
```

The browse URL is https://cinefilm.chrisjgregory.dev. A tab still open on the old
`https://dockermacmini.tail0c74fa.ts.net/` (port 443) now reaches Caddy under a name it does not serve, so every API
call fails and pages show "The projector jammed": reopen it on the domain (or the fallback on `:8443`).

## Deferred (not built yet)

Playwright E2E and axe a11y in CI, ZAP, release changelog
promotion, nightly backups with verification and the daily health workflow
(phase 9), the `cinefilm-worker` service.

## Transcoding (live HLS)

The container transcodes with Intel Quick Sync (`h264_vaapi`, i965 driver). The compose file passes only
`/dev/dri`, adds the `render` group (`group_add: ["993"]`; confirm with `getent group render` on the host) and sets
`LIBVA_DRIVER_NAME=i965` and `init: true`. Check the encoder in **Admin > Transcoding**: `vaapi` is the goal,
`software` means the render node was not usable (libx264, 480p/360p only; check `ls -l /dev/dri` and the group id),
`unavailable` means ffmpeg itself is unusable and the player shows "worker offline" (original direct-plays).
The same panel shows keyframe-index progress, running sessions (Stop per session) and the size of `cache/hls`
(capped at 5 GB, idle sessions evicted; leftovers are deleted at start-up).
