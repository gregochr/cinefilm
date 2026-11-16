# The home replay: putting the rig into git

Written 2026-11-16 and revised the same day after an adversarial review (its findings are folded in below).
Owner: "Yes, put it in". What the rig is for, how to run it and what it has shown belong in `home-replay.md`,
which these two phases write. This file is the plan for getting it there.

## Where it stands

The rig was built on 2026-11-15 as untracked scratch (a folder `replay/` in one session's worktree, about 1,400
lines of Node with no dependencies) and has run the whole 12 November journey twice (`train-link-plan.md` holds
that journey's record). It lets the real Player screen, built for production, watch a stand-in film in headless
Chrome while a stand-in server delivers that film at the pace the train's link managed. It works against `main`
as of `b89cd39` (one minute on a steady link, 2026-11-16: every request the player made was answered).

Nothing in the app, its image or its tests depends on it, and that stays so: `scripts` is in `.dockerignore`.

## Decisions

1. **It lives in `scripts/replay/`, its tests in `scripts/test/replay/`**, beside the train feed logger: both are
   measuring tools run by hand on the owner's laptop. `sonar-project.properties` already treats `scripts` as
   sources and `scripts/test/**` as tests.
2. **Two pull requests.** The film and the link first (no browser anywhere in it), then the driver and the
   reading of a run. Each is about the size of the logger's (#171).
3. **The record goes in cut down.** The repository is public. `train-link-plan.md` already publishes the film,
   the day, the operator, the playback id and where each bad spell fell, so the journey is no secret, but the
   replay reads only the start time, the minute rows and eight totals. The file keeps those and drops the ids,
   the film's title, the operator, the browser's user-agent string, the screen size and the core count (the only
   fingerprint of the device). It is `scripts/replay/record/train-2026-11-12.json`: not a folder called `data`,
   because `.gitignore` ignores every `data/`.
4. **Film and trace weigh the same by construction.** `trace.mjs` turns a stalled minute's film into kbit/s with
   8% added for the MPEG-TS wrapping. The first full replay ran with segments at the bare average bitrate, so its
   thin minutes delivered about 8% too much film (found on 2026-11-16). The factor becomes one exported constant,
   `WIRE = 1.08` in `trace.mjs`, and is the default weight of a segment; `--wire 1` reproduces the first run.
5. **What a request says is never a file path and never a response body, and it is kept only as figures and
   words from fixed lists.** CodeQL scans every JavaScript file here, tests included, and a new alert of high
   severity blocks the merge. So:
   - The built app is served from a table of its files read once at start-up. No request text reaches the file
     system. (This is for that reason alone: the scratch version's join-and-check could not be climbed out of
     over HTTP, because parsing the address removes `..` first.)
   - Nothing is echoed. An unknown write answers a fixed `null`; an internal error answers a fixed text.
   - The request log keeps the kind of route that matched, the method from a fixed set, the rung and segment
     from the stand-in's own ladder, and numbers. It does not keep the address.
   - A session request is refused as the real server refuses it (`SessionService.java`): a quality that is not
     `auto` or a rung of the ladder, and a probe that is not a whole number of 0 or more.
   - A health report is read into a fresh object field by field from the stand-in's own list of names: finite
     numbers, booleans, `engine`, `environment` and `quality` each mapped to a fixed set (else `other`), time on
     each rung by the ladder's own ids, and minute rows of five or seven finite numbers, at most 1,000. The
     operator and the client block are not kept. A body that is not JSON keeps only its length. A body over
     64 KB is refused.
   - In phase 2 every line the driver prints passes one function that turns line breaks into spaces and anything
     that is not printable ASCII into `?`, and cuts it at a limit; text read from the page (the pill, a toast,
     an error) is stored the same way.
   - Expected and accepted: CodeQL may still report, at medium severity, that data from the network is written
     to a file (the results of a run). That is the rig's purpose; the planner dismisses it with that reason and
     the pull request names it.
6. **The allowance is a pure module.** The shared allowance that paces every answer moves out of the server into
   `link.mjs`, driven by the milliseconds since it was last advanced, so its arithmetic is tested with made-up
   times and no waiting. The server only wires it to a timer. Its behaviour is unchanged, so the two finished
   replays still describe it: a job that is added is served at once from what is saved, and an answer with no
   body waits for a live link through the same module (the scratch version polled for that on its own timer).
7. **Tests are `node --test`, run by a wrapper in `scripts/test/`** (`replay-rig.test.sh`), so
   `bash scripts/test/run-all.sh` and the Shell job run them like every other script test. `node --test` passes
   with nothing tested (no files, an empty file, a skipped test) and never ends when a test leaves a timer
   running, so the wrapper names its files from a glob and fails on none, reads the TAP summary and fails unless
   at least the expected number of tests ran with none failed, skipped, cancelled or left to do, and sets a
   per-test timeout. It fails, never skips, when Node is missing or older than 22. The Shell job gains a pinned
   Node 24 (the frontend job's version, with no cache: the rig has no lockfile).
8. **SonarQube.** Its gate on new code is no new issues, every new hotspot reviewed, and coverage.
   - The rig's source files are listed one by one in `sonar.coverage.exclusions` with a dated reason, as the
     logger is: Sonar reads no coverage from `node --test` as wired here, and the parts that drive Chrome cannot
     run in CI. They stay in analysis.
   - The code is written for Sonar's default JavaScript rules from the start: routes are small functions found
     in a table, no function is deeply nested or long enough to trip the complexity rule, no assignment inside a
     condition, `RegExp.exec` not `String.match`, no nested conditional expressions or template literals,
     top-level `await` not a promise chain.
   - Hotspots are avoided by construction, because only the owner can sign in to mark one reviewed: no address
     but `localhost` and `127.0.0.1`, no command looked up on the `PATH`, temporary files only through `mkdtemp`.
     If one appears anyway, the owner is asked.
9. **No linter covers `scripts/replay/`** (ESLint and Prettier are the frontend's). Accepted: Sonar and CodeQL
   read it, and the tests run it.
10. **No browser in CI.** Chrome on the runner cannot make the film's sound (no AAC encoder on Linux), and a
    real-time run is the wrong kind of test for a required check. What needs Chrome (`drive.mjs`'s `main`, the
    launch and the socket in `cdp.mjs`, the encoding in `make-template.mjs`, building the app) is checked by
    hand: one minute on a steady link after any change to the rig or to the player. The manual says so.
11. **The film's picture is still made on first use**, not committed: 20 KB of base64 from Chrome's encoder that
    differs by machine and that every segment pads over anyway. Tests build a small template of their own.
12. **`SECURITY.md` gains a paragraph under rule 6** in each phase: what listens, on which address, for how
    long, with no sign-in, and what it can serve (a build of the app and a film made of padding: no library, no
    watch history, no secret); in phase 2, Chrome's throwaway profile, its debugging port on `127.0.0.1` for the
    length of a run, and that no host but this machine resolves in it.
13. **Tests make their files under `mkdtempSync(join(tmpdir(), 'replay-'))`** and remove them in `after()`; no
    file is checked for and then written. Every stand-in a test starts is stopped in `after()`.
14. **Not built here:** replaying a record made under the new rules (its stalled minutes need a different
    reading), a browser run in CI, segments of varying size, a server that is briefly not ready, the old rules
    on the rig.

## Phase 1: the film and the link

Branch `feat/home-replay-film-and-link`. Source: the scratch files `trace.mjs`, `ts.mjs` and `standin.mjs`.

| File | What changes from the scratch version |
|---|---|
| `scripts/replay/trace.mjs` | `RECORD_PATH` points at the cut-down record. `TS_OVERHEAD` becomes the exported `WIRE`. Run as a program it prints both traces and writes nothing. |
| `scripts/replay/record/train-2026-11-12.json` | New: `about` (one sentence on what it is and what was dropped), `startedAt`, and `report` with `watchedMs`, `stallCount`, `stallMs`, `longestStallMs`, `stepsDown`, `stepsUp`, `timeouts`, `rungMs` and `minutes`, copied unchanged from the record. |
| `scripts/replay/ts.mjs` | The same bytes for the same inputs; `buildSegment` is split into smaller functions for decision 8. |
| `scripts/replay/link.mjs` | New, taken out of `standin.mjs`: `createLink(rate)` gives `add(job)`, `remove(job)`, `whenAlive(callback)`, `advance(ms)` and `size()`; `rate()` is bits a second now and `ms` is the time since the last advance. The arithmetic is unchanged: nothing is saved while the rate is 0, never more than a twentieth of a second (or 3,000 bytes) is saved, jobs share equally with an odd byte to the first, one send is at most 64 KB. Also `minuteAt(startMinute, elapsedMs)` and `rateAt(trace, minute)` (past the end: the last figure; a missing one: 0). |
| `scripts/replay/standin.mjs` | Decision 5, as a table of routes. `wire` defaults to `WIRE` and must be a number from 1 to 1.5. `port: 0` takes any free port; the returned object gains `port` and `address`. The base for parsing a request's address is `http://localhost`. The progress answer's `positionMs` is the body's when that is a finite number, else 0. A request for a file with an extension that the build does not hold answers 404; any other address is still the app's page. It still listens on `127.0.0.1` only. |
| `scripts/test/replay/*.test.mjs`, `scripts/test/replay-rig.test.sh` | New (below). |
| `.github/workflows/ci.yml` | The Shell job sets up Node 24 before the script tests, under the same docs-only condition as its other steps; its header comment no longer says "no network". |
| `sonar-project.properties`, `.gitignore` | Decision 8; ignore `scripts/replay/dist/` and `scripts/replay/out/`. |
| `docs/engineering/home-replay.md`, this plan, `SECURITY.md`, `AGENTS.md`, `dev-and-testing.md`, `changelog.d/` | The manual's first half (what it is, what is real and what stands in, the link model and what is approximate); decision 12; the "deliberate decisions" line; the Shell job's row; one changelog entry. |

Tests, each named for the rule it pins, with exact values:

- **`trace.test.mjs`.** A clean minute takes the sampled figure, and the cautious trace 0.6 of it, rounded. A
  stalled minute is seconds moved over 60 times the rung's rate times `WIRE` (5,000 ms of 360p is 63). The two
  edges of "stalled": 4,999 ms stalled is clean, 5,000 is stalled; moved plus stalled of 49,999 ms is read as
  clean (a paused minute), 50,000 as stalled. Nothing moved gives 0. An unknown rung is read as 360p. A clean
  minute with no sample, and a minute with no row, take the lower neighbour; with one neighbour, that one; with
  none, 0. The cautious trace leaves stalled minutes alone. `clockOf` across midnight. On the committed record:
  144 rows, a trace of 152 minutes, minutes 0, 5, 34, 111, 112 and 143 are 4030, 61, 0, 2687, 2541 and 4425
  (cautious 2418, 61, 0, 1612, 1525, 2655), the two traces sum to 276,966 and 172,721, the six spells average
  303.519 and 290.904, and the record holds exactly the keys decision 3 lists.
- **`ts.test.mjs`**, on a template made in the test. A segment is the target rounded down to whole 188-byte
  packets, and never smaller than its frames need, with either padding. Every packet starts `0x47`. The tables
  come first, with their check bytes pinned; the programme map lists the picture alone when there is no silence,
  and both with it. Each frame's time stamp is exact for segments 0 and 1 and for one past 2^32 ticks.
  Continuity counters rise by one, modulo 16, on each stream. `media` padding puts filler inside every frame and
  `null` padding none, with the count of null packets exact. A four-second template carries 188 frames of
  silence in segment 0 and 187 in segment 1. `loadTemplate` reads frames, silence and length from a file.
- **`link.test.mjs`.** At 800 kbit/s, a hundred advances of 10 ms move 100,000 bytes and no more; one advance of
  a whole second moves only the 5,000 that may be saved. Two jobs sharing 1,001 bytes get 501 and 500. A rate of
  0 moves nothing and forgets what was saved. On a slow link 3,000 bytes may be saved. A job added to a link
  with something saved is served at once. A finished job leaves; a removed job gets nothing more. `whenAlive`
  runs at once on a live link, and on a dead one at the first advance after the rate rises. `minuteAt` at 59,999
  and 60,000 ms. `rateAt` inside, at and past the end of a trace.
- **`standin.test.mjs`**, over real HTTP on a free port with no round-trip delay, raw paths sent with
  `http.request`. The ladder of a 1080p source, every figure. `startRung` exactly at a rung's peak times 1.25
  (18910, 3910, 1660, 1020 and 455 kbit/s), one under each, with no probe, and under every rung.
  `masterPlaylist` with the start rung first, and pinned; `mediaPlaylist(3, 4000)` exactly, and from the server
  the playlist's first lines, its count of segments and its last two lines. The film it serves is the app's own
  fixture (`frontend/src/test/fixtures/film-heat.json`, on purpose: the rig reads that file, so a change to it
  should fail here) with one edition, nothing to resume, no subtitles and Auto. A session's answer; a quality
  that is no rung and a probe that is a string or negative are refused in the server's words; a probe of 0
  starts on the floor. A segment's type and exact size at weights 1 and 1.08; the last segment answers and the
  one after it does not; an unknown session's 404 text; ending a session. The probe answers 206 with its range
  headers. A health report keeps its known figures, maps an unknown engine to `other`, drops the operator and
  any other key, keeps only well-formed minute rows, and one that is not JSON keeps only its length; one over
  64 KB is refused. Progress answers the number sent and 0 for anything else. An unknown write answers `null`
  whatever its body. A file of the build answers its exact bytes and type; an app route answers the page; a
  missing `.js` answers 404; a file added to the build after start-up answers 404; three spellings of a path
  that climbs out (plain, percent-encoded, and into a sibling folder whose name starts like the build's) never
  return a file from outside it. Each answer is in the request log with its kind, status and bytes, and no entry
  holds an address. With a dead link and the clock started, a segment that was asked for has received nothing
  when, after `finish()`, an API answer arrives. A weight outside 1 to 1.5 is refused. It listens on `127.0.0.1`.

Before the pull request the refactored stand-in is also proved by hand: the scratch driver, pointed at it and
not committed, plays one minute on a steady link with every request answered.

Gates: `bash scripts/test/run-all.sh` exits 0; `shellcheck -x -o check-unassigned-uppercase` over the wrapper
and `actionlint` both pass locally (CI's pinned versions are the proof). No file under `backend/` or
`frontend/` changes, so their gates are not re-run; the pull request says so.

## Phase 2: the driver and the reading of a run

Branch `feat/home-replay-driver`, from `main` after phase 1. Source: `drive.mjs`, `lib/cdp.mjs`,
`make-template.mjs` and `analyse.mjs`.

| File | What changes from the scratch version |
|---|---|
| `scripts/replay/text.mjs` | New: the one function every printed line and every text from the page passes (decision 5). |
| `scripts/replay/drive.mjs` | `options(argv)` is exported and checks its values: whole numbers for `--start` and `--minutes`, a known trace, `--fill`, `--wire`, and a `--name` of letters, digits, dot, dash and underscore. The app is built by running Vite's own script with this Node (`process.execPath`), not by looking `npx` up on the `PATH`. The paths to `frontend/` gain a level. The template is loaded, and made only when loading says it is not there. `--wire` defaults to `WIRE`. `summarise` is split for decision 8 and prints the same lines. |
| `scripts/replay/cdp.mjs` | Moved (no `lib/` folder). The matching of replies to requests and of events to listeners becomes a function of its own, tested with made-up messages; the launch and the socket stay by-hand. |
| `scripts/replay/make-template.mjs` | Moved. `silentFrame` and `nalTypes` stay exported for their tests. |
| `scripts/replay/analyse.mjs` | Its sums become exported functions over the run's data (was the run sound, idle gaps, the six spells, screen against link, rungs and bytes) with the printing kept apart; run as a program it prints what it prints today. |
| `scripts/test/replay/*.test.mjs` | `summarise` line by line on a two-minute run, with and without a report, and with an engine that holds a line break; every option and each refusal; each sum in `analyse.mjs` at its edges (a sampling gap of 6,500 ms, a pause with 5 s in hand, 0.5 s behind the clock, an idle gap of 30 s, a request that spans two minutes); the routing in `cdp.mjs` (a reply, an error, an event, an unknown id); `silentFrame` for a good frame and for each reason to refuse one; `nalTypes`; the printing function at and one past its limit. |
| `docs/engineering/home-replay.md`, `train-link-plan.md`, `SECURITY.md`, `sonar-project.properties`, `changelog.d/` | Running it, checking a run, the two replays of 15 and 16 November and what they showed; a pointer from the train plan; decision 12; the new files' coverage entries; one changelog entry. |

Before the pull request: one minute on a steady link, and the fifteen record minutes 24 to 38 on the sampled
trace, whose scratch run stalled for 95 s at the bare weight, as a check that the moved rig behaves like the
scratch one.

## What nobody will have verified when both are merged

- The rig on any machine but the owner's Mac, or with any browser but its installed Chrome.
- That the stand-in's ladder and playlists still match the server's after either changes: they were copied from
  `Ladder.java`, `Rung.java`, `StartRung.java` and `PlaylistWriter.java` on 2026-11-15 and nothing compares them.
- The old rules on the rig. Until the player as it was on 12 November has been replayed and stalls for about
  33 minutes, a replay is compared with the real day, not like with like.
