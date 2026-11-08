# Operations: CineFilm on dockermacmini

How CineFilm runs in production, the one-time host setup, how to release and how to roll back. Background:
`foundation-plan.md` (Deployment topology) and `ci-test-deploy-plan.md` (phases 7 and 8; the names and ports table that
avoids goldenhour's). Backups, backup verification and the daily health workflow are phase 9 and are **not** set up yet.

## What runs where

| Thing | Value |
|---|---|
| Host | dockermacmini (Ubuntu), shared with goldenhour production |
| Image | `ghcr.io/gregochr/cinefilm:vX.Y.Z` (also `:latest`), one image: Spring Boot serves the SPA |
| Containers | `cinefilm-db` (postgres:17, no published port), `cinefilm` (`127.0.0.1:8086` to 8080) |
| Compose | `~/cinefilm/docker-compose.yml` (copied from `deploy/` by every deploy), project `cinefilm`, secrets in `~/cinefilm/.env` |
| Data | `/home/gregochr/cinefilm-data/{postgres,logs,cache}` (owned by uid 1001, except `postgres`, which Postgres owns); `cache` is the container's `/app/cache`, the application's default `CINEFILM_CACHE_DIR` (generated WebVTT subtitles), kept in step by `scripts/test/config-agreement.test.sh` |
| Pre-release dumps | `/home/gregochr/cinefilm-backups/pre-release/` (10 newest kept) |
| Media | workhorse share `Movies`, mounted read-only at `/mnt/workhorse-films`, seen in the container as `/media` |
| Remote access | `tailscale serve` to `127.0.0.1:8086` (HTTPS with a real certificate, tailnet only) |
| Runner | second GitHub runner `~/actions-runner-cinefilm`, label `cinefilm-prod`, service |
| State files | `~/cinefilm/.deployed-version`, `~/cinefilm/.previous-version` (written by the deploy scripts) |

TODO(owner): find out dockermacmini's RAM (`free -h`) and free disk. The container is capped at `mem_limit: 1g` with
`-XX:MaxRAMPercentage=75` in `deploy/docker-compose.yml` until then; revisit it before ffmpeg/transcoding lands.

## One-time host setup (owner, on dockermacmini unless stated)

### 1. Check what Tailscale already serves, and the Docker group

```bash
tailscale status | head -3
tailscale serve status        # is port 443 already used (goldenhour)?
groups                        # must include docker
```

### 2. Directories

```bash
sudo mkdir -p /home/gregochr/cinefilm-data/{postgres,logs,cache} /home/gregochr/cinefilm-backups/pre-release
sudo chown -R 1001:1001 /home/gregochr/cinefilm-data/logs /home/gregochr/cinefilm-data/cache
sudo chown gregochr:gregochr /home/gregochr/cinefilm-backups /home/gregochr/cinefilm-backups/pre-release
mkdir -p ~/cinefilm
```

(`postgres` stays as created; the Postgres container takes ownership on first start.)

### 3. `~/cinefilm/.env`

```bash
cd ~/cinefilm
# Get deploy/.env.example from the repo (browser: raw file on GitHub, or copy it over from your Mac with scp).
cp .env.example .env && chmod 600 .env
DBPW="$(openssl rand -hex 24)"; PW="$(openssl rand -base64 24 | tr -d '\n')"
sed -i "s|^CINEFILM_DB_PASSWORD=.*|CINEFILM_DB_PASSWORD=${DBPW}|; s|^CINEFILM_PASSWORD=.*|CINEFILM_PASSWORD=${PW}|" .env
echo "Sign-in password: ${PW}"       # note it in your password manager, then clear the terminal
```

`TMDB_READ_TOKEN` stays empty until the TMDB phase. Never commit `.env`; never paste it anywhere.

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

1. Create a dedicated standard user for the share: System Settings, Users & Groups, Add User, name `cinefilm`, a long
   random password (you will paste it into a credentials file below). No admin rights.
2. System Settings, General, Sharing, turn on File Sharing, then (i) Options: tick SMB, and tick **only** the
   `cinefilm` user under "Windows File Sharing" accounts.
3. Under Shared Folders click `+` and add **`/Volumes/Media Library/Movies`** (just that folder). Its share name is
   `Movies`.
4. Click the (i) next to that folder: set `cinefilm` to **Read Only**, set **Everyone** to **No Access**, remove any
   other user or group, or set them to No Access.
5. Make sure no other shared folder lists the disk or `Pictures` (File Sharing's folder list should show only
   `Movies` for this disk).

The films are flat files: `/Volumes/Media Library/Movies/<Title>.m4v`.

### 6. Mount it on dockermacmini, read-only

```bash
sudo apt-get install -y cifs-utils
sudo mkdir -p /mnt/workhorse-films
sudo install -m 600 /dev/null /root/.smb-workhorse
sudo tee /root/.smb-workhorse >/dev/null <<'EOF'
username=cinefilm
password=<THE-PASSWORD-YOU-SET-ON-WORKHORSE>
EOF
echo '//workhorse.local/Movies /mnt/workhorse-films cifs ro,credentials=/root/.smb-workhorse,vers=3.0,uid=1001,gid=1001,_netdev,nofail,x-systemd.automount 0 0' | sudo tee -a /etc/fstab
sudo systemctl daemon-reload
ls /mnt/workhorse-films
```

If `workhorse.local` does not resolve on Ubuntu, use workhorse's LAN IP or its Tailscale name in the fstab line.

**Verify (do not skip):**

```bash
ls /mnt/workhorse-films            # must list ONLY .m4v film files: no Pictures, no documents, no folders you don't expect
touch /mnt/workhorse-films/x       # must FAIL with "Read-only file system"
findmnt /mnt/workhorse-films       # options must include ro
```

If `ls` shows anything that is not a film, **stop**: `sudo umount /mnt/workhorse-films` and fix the share on workhorse
(step 5). Nothing in CineFilm's scripts writes under `/mnt/workhorse-films`; the deploy only does a read-only `ls` and
a `mountpoint` check, and warns (never fails) when it is not mounted.

### 7. Tailscale HTTPS

Enable MagicDNS and HTTPS certificates in the Tailscale admin console (DNS page) if they are not already on. Then,
according to `tailscale serve status` from step 1:

```bash
# 443 is free:
sudo tailscale serve --bg --https=443 http://127.0.0.1:8086
# 443 is already used by goldenhour (do NOT replace it): use 8443 instead
sudo tailscale serve --bg --https=8443 http://127.0.0.1:8086

tailscale serve status        # shows the URL
```

The URL is `https://<machine>.<tailnet>.ts.net` (443) or `https://<machine>.<tailnet>.ts.net:8443`. Open it on the Mac
and the iPhone (both on the tailnet). Never use `tailscale funnel` and never open a router port: CineFilm is tailnet-only
(`SECURITY.md` rule 6).

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
3. pull the image, `docker compose up -d`, wait for the container to be healthy;
4. smoke on `http://127.0.0.1:8086`: `/actuator/health` 200 UP, `/` serves the SPA, `/api/home` is 401 JSON, `/actuator/info` reports the tagged commit;
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

```bash
cd ~/cinefilm && export CINEFILM_VERSION=$(cat .deployed-version)
docker compose ps
docker compose logs -f cinefilm
docker compose restart cinefilm
curl -s http://127.0.0.1:8086/actuator/health
```

## Deferred (not built yet)

Playwright E2E and axe a11y in CI, ZAP, release changelog
promotion, nightly backups with verification and the daily health workflow
(phase 9), the `cinefilm-worker` service.
