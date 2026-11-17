# The train viewer

Written 2026-11-17. On 2026-11-15 the owner asked for "a test that watches a film on a headless browser", so that a
train journey gives a playback record with nobody watching. The planner built the home replay (`home-replay.md`)
instead, on a yes/no question that named only the replay. This is the viewer. It is wanted for the journey of
Monday 2026-11-23, where it runs beside the train feed logger (`train-feed-logger.md`).

## What it is

`node scripts/replay/viewer.mjs --film <id>` starts the installed Chrome with no window, opens the real player on the
real site at the start of that film, and lets it play. The film comes from the owner's server over whatever link the
laptop is on, and the player's own rules decide everything. Every five seconds the viewer notes the playhead, the
film in hand and what the screen says. It keeps its own copy of each playback-health report the player sends. At the
end it writes those to a folder and prints a summary. `viewer.mjs --sign-in`, once, signs its Chrome in.

It is a measuring tool run by hand, as the home replay and the logger are: not part of the app, in no image. It needs
no change to the app and no release.

## Decisions

1. **It reuses the home replay's driver where that fits.** `scripts/replay/cdp.mjs` starts Chrome and talks to it.
   From `drive.mjs` it takes, exported as they are, what is read from the page every five seconds, the cleaning of a
   sample, the stop flag and the keep-awake. Its loop is its own: the replay's is built round the stand-in. The
   replay's behaviour does not change.
2. **Every write is held back but three.** While the viewer runs, Chrome pauses each request the page makes to the
   site (the Fetch domain of its debugging protocol, at the request stage). A `GET` or `HEAD` goes through at once.
   Of the rest, three go through: `POST /api/editions/<n>/sessions` (start the stream), `DELETE /api/sessions/<id>`
   (end it) and `PUT /api/playback-health/<id>` (the record, once the viewer has kept a copy). Any other request with
   any other method is answered by the viewer and never reaches the server: a progress save gets what the server
   would say (`{ positionMs, watched: false }`), anything else a `204`. So a viewing by the viewer moves no resume
   point, marks nothing watched and saves no preference, and a write the app gains later is held back without anyone
   adding it to a list. A request is let through only when that rule says so: if handling a paused request fails, it
   is failed, never continued. The guard is in force before the first address is opened. Requests to other hosts
   (TMDB's images, the operator lookup) are not paused: the page only reads from them. The guard keeps the owner's
   watch history as it was; it is not a security boundary.
3. **The end is ordered so that nothing gets out.** The guard works only while the driver is attached, and the page
   sends a progress save, the record and the end of the stream as it closes. So, with the guard still attached, the
   page is sent to `about:blank` (its closing sends are paused and dealt with like any other) and three seconds are
   given for them to settle; only then is the channel closed and Chrome ended (`SIGTERM`, then `SIGKILL` if it
   lingers). Every way out takes that path: the film's end, `--minutes`, Ctrl-C, `SIGTERM`, `SIGHUP` (the terminal
   closed) and an error. When the page cannot be sent away (the channel has failed, Chrome does not answer), and on
   an exception or a rejected promise that nothing caught, Chrome's whole process group is killed with `SIGKILL`
   before anything else: a Chrome killed so sends nothing. Chrome is started in its own process group, so a Ctrl-C in
   the terminal reaches the driver alone. **One way out is not covered:** if the driver itself is killed outright
   (`kill -9`, a crash of Node), the pipe closes, Chrome ends by itself within a third of a second, and as it does
   the page's closing save gets out. That film's resume point moves to where the viewer was, the film appears in
   Continue watching, and past its credits it is marked watched; all three can be put back from the film's page. The
   manual says so. Closing this needs either relays between the driver and Chrome or a second guard inside the page;
   neither is worth its risk to the run itself in a first version. (A wrapper that only held Chrome's ends of the
   pipe open would not do it: Chrome sees the pipe end when the driver's ends close, whoever else holds its own.)
4. **The channel is a pipe, not a port.** The viewer's Chrome is signed in, and a debugging port on `127.0.0.1` would
   let any program on the laptop read its cookie or drive it. Chrome is started with `--remote-debugging-pipe`:
   nothing listens, and only the driver holds the pipe. The replay keeps its port: its Chrome is signed in to nothing.
5. **The sign-in is the owner's, in a normal window.** `viewer.mjs --sign-in` opens an ordinary Chrome window on the
   site, on a profile the viewer keeps, **started with no debugging port or pipe**: the script has no way to read the
   page while the password is typed. The owner signs in, then presses Return in the terminal. The script waits 35
   seconds (Chrome writes a new cookie to disk at its next 30-second commit), ends that Chrome, starts headless
   Chrome on the same profile, over the pipe, and says whether the site takes it as signed in; if not, it says so
   and to try again. Quitting that Chrome (Cmd-Q) before Return goes straight to the same check, which is what says
   whether a sign-in made that way was kept. The profile is made with Chrome's offer to save passwords turned off, so
   the password does not end up in the profile. `--site` names another
   address for both modes: a sign-in belongs to one host, so the fallback address needs its own. The password is
   never in a script, an option or the terminal.
6. **Where things are kept: under the home folder, never in a checkout.** `~/cinefilm-viewer/profile/` is the kept
   Chrome profile. It holds the session cookie, encrypted by the login keychain as in any Chrome profile: no start of
   Chrome on it ever passes `--use-mock-keychain` (with it the cookie is as good as plain text, and a start with it
   on a profile signed in without it could not read the cookie and removed it). `~/cinefilm-viewer/runs/<date>-<time>/`
   is one run's results. Both are made readable by the owner alone, and no option moves them.
   `~/cinefilm-viewer/lock` is held by a sign-in or a run from before Chrome starts until it has gone, so two never
   share the profile; a lock whose process has gone is taken over, and a start is also refused while Chrome's own
   lock in the profile names a live process (a window left behind by a driver that was killed). Removing `profile/`
   signs the viewer out of this laptop; the server keeps the session until it restarts, 30 days pass without use, or
   that browser signs out.
7. **What it checks before it plays.** The site answers (it waits up to ten minutes for that, saying so: a dead
   spell or Tailscale still coming up must not lose the journey); the profile is signed in; the film exists; the
   film's saved quality is Auto (a quality picked by hand is saved per film, and a pinned film would not measure the
   rules). Signed out, no such film and a pinned film each refuse at once with one plain line.
8. **What it presses.** On the "Where are you watching?" card: "On a train" or "Hotel or away" when `--place train`
   or `--place away` is given (Admin's filters leave out a record that says neither), else "Not now". "Try again"
   on the player's two cards that offer it (the film cannot be played right now; the film could not be loaded), at
   most once a sample, counted in the summary: a viewer would press it, and a journey is too scarce to lose to a
   card. Nothing else: it never picks a quality, seeks or pauses.
9. **What ends a run, and what a run says.** The film's end, `--minutes`, a signal, the "Signed out" card (a release
   or restart of the server signs every browser out: one line says to run `--sign-in`), "Playback was stopped from
   Admin", or Chrome going away. The line printed every 30 seconds and the summary say whether the film is being
   streamed or has fallen back to playing the original file directly, which is what the player does when streaming
   fails and is no measure of the rules.
10. **What it changes on the server.** A stream session started and ended (which may save a film's keyframe index,
    and at the limit of three sessions pushes out the one least recently used), and one playback record. Admin shows
    that record like any other, with `HeadlessChrome` as its browser, and counts it in its figures.
11. **One viewing at a time.** It must not run beside a film being watched: the two would share the link and spoil
    both records. Nothing enforces that; the manual says it.
12. **Not in this version:** reading a run against the logger's log (by hand after the first journey, built only if
    it earns it); playing a second film when the first ends (the manual says to pick one longer than the journey);
    reloading a page that has died; minute rows in the summary (`health.json` and Admin hold them).

## What was tried first

Three scratch scripts on 2026-11-17, the real player against the home replay's stand-in unless said otherwise. Each
case ran once.

- **Holding back.** In 75 seconds all nine progress saves were answered inside Chrome and none reached the stand-in;
  the four health reports were read whole on their way out and all arrived; the sends the page makes as it closes
  were paused like the rest; the page logged no error. The pause cost a segment 0.6 ms at the median, 8.6 at most.
- **The sign-in's mechanics**, with a throwaway cookie from a local server. A cookie set in a normal window was sent
  by headless Chrome on the same profile. Ended by a signal 3 seconds after the cookie was set, Chrome had not kept
  it; after 36 seconds it had. One start with `--use-mock-keychain` could not read the cookie and removed it.
- **The end.** With the socket closed and Chrome left running, two saves reached the stand-in in 14 seconds: the
  guard goes with the driver. A Chrome ended by `SIGTERM` or `SIGKILL` sent nothing as it went, guard or no guard.
  Over the pipe the guard worked as over the port and Chrome wrote no port file. When the pipe was closed as if the
  driver had died, Chrome ended by itself in 0.3 seconds, and the page's closing save and report reached the
  stand-in.

## Four pull requests, each under 1,500 changed lines

The first estimate was two pull requests of about 950 and 1,050 lines. Built, the first came to 2,000, so each is
split again. Each is built on the one before and pushed only once that one is merged.

**1. The sign-in and the kept profile.** No behaviour of the replay changes. It goes in as two: **1a**, `cdp.mjs`
with its tests and this plan (about 1,050 lines); **1b**, `viewer.mjs` with its tests and `SECURITY.md` (about 1,400).

- `scripts/replay/cdp.mjs`: Chrome's arguments built by a pure function for each way of starting (the replay's, word
  for word what they were; the viewer's; the sign-in window's). A second channel, the pipe, with the same `send`,
  `on`, `evaluate` and `close` as the socket's. A kept profile, never removed. Chrome in its own process group when
  asked. The sign-in window: no `--headless`, no debugging switch, resolves when that Chrome has gone.
- `scripts/replay/viewer.mjs` (new), the sign-in only: `--sign-in` and `--site` (`https`, or `http` on `127.0.0.1`
  for the stand-in), the folders, the lock, the profile's first preferences, the Return and the 35 seconds, and the
  check (headless Chrome opens `<site>/api/session`, which runs none of the app, and reads the answer).
- Tests: the three sets of arguments, with no debugging switch in the window's and no `--use-mock-keychain` in any;
  the pipe's framing (a message split across chunks, two in one chunk, a reply and an event, a pipe that closes with
  requests waiting); `--site` refusals (another scheme, a path, a query, a user name, `http` to another host); the
  lock (taken, refused while its process lives, taken over when it has gone, let go at the end); folders and the
  preferences file made for the owner alone and never overwritten; the sign-in's order (window, then Return or the
  window's end, then the wait, then the check) and its two answers.
- `SECURITY.md` (the kept profile and its cookie, the window, the pipe, what is never kept), this plan, the changelog
  entry, the new file's line in `sonar-project.properties`.

**2. The run and its guard.** It goes in as two: **2a**, `guard.mjs` with its tests (about 600 lines, a doubled
estimate); **2b**, the run (about 1,300, likewise), from which the results and the manual are split off again if it
will not fit.

- `scripts/replay/guard.mjs` (new): decision 2 as a pure function of method, address and site; the canned answers;
  the keeping of reports (the latest by `seq` per viewing, how many were sent; one that cannot be read or is over
  64 KB is let through uncopied and counted); the handler that wires them to a page.
- `scripts/replay/viewer.mjs`: `--film`, `--minutes`, `--place`; the checks of decision 7; the run (each sample has
  a clock since the film opened and the time of day, so a run lays beside the logger's log); the presses; decisions
  3 and 9; `health.json`, `samples.json`, `held.json` and `summary.txt` in the run's folder. `drive.mjs`: exports.
- Tests: every write in `frontend/src/api/resources.js` by name, held or let through as decision 2 says; `PATCH` and
  `OPTIONS` held; look-alike addresses held (another host or port, a user name, a longer path, a trailing slash,
  `//`, a percent-encoded segment, a query, upper case); a request paused again after a redirect is judged by the
  address it now has; a progress save whose body cannot be read is still answered here (position 0); a paused
  request is answered exactly once, and failed when its handling throws; the guard is on before the first address is
  opened; the end's order on every way out (the page sent away and the wait before Chrome is ended, the channel
  closed last, and the kill first when the page cannot be sent away); the checks' answers; the presses; what ends a
  run; the summary; printing that fails once the terminal has gone does not stop the results being written;
  `held.json` holds methods, address shapes and counts, never a header or a body.
- `docs/engineering/train-viewer.md` (the manual: lid open and on power, a film longer than the journey, sign in
  after the last release, never beside a film being watched, `--place train` in the journey's command, what a
  driver killed outright leaves behind, and that on a slow link the server's copy of the record may lack its last
  minute where the viewer's does not), `SECURITY.md`, the changelog entry, the pointers in `dev-and-testing.md`, the
  new file's line in `sonar-project.properties`.

Each one's lines are counted when the builder reports, before any review.

## Checked by hand

Nothing in CI starts Chrome, so the planner runs these once on each branch as it will be pushed.

- **1:** a command over the pipe is answered, and that Chrome listens on no port (`lsof` for its own process, never
  a listing of every process); the whole sign-in against a scratch server that sets a cookie on its first page, with
  Return fed to it: signed in; a second start meanwhile refuses; the window's arguments, read from that one process,
  hold no debugging switch.
- **2:** the viewer against the stand-in (started alone by a scratch script, as the first check was) on a steady
  link. No progress save reaches the stand-in on a run to `--minutes`, on Ctrl-C or on `SIGHUP`; with the driver
  killed outright, what gets out is what decision 3 says and no more; the report the viewer kept matches the
  stand-in's figure by figure; the results are the owner's alone; a second viewer refuses.

With the owner, at home: the sign-in once the first is merged (Chrome does not offer to save the password, and the
profile's saved-logins file holds no row afterwards); ten minutes of a real film once the second is. The record
appears in Admin with `HeadlessChrome`; the film's resume point and watched mark are what they were; Auto reaches
Original and the playhead keeps time (decoding 1080p with no window is a load the stand-in never tested).

## What nobody will have verified before the journey

- A train: whether the site is reached, the link's bad spells and the player's recovery are what the journey is for.
- Two hours and more of a real film in headless Chrome on this laptop.
- The cookie staying readable if the login keychain locks while the laptop sleeps.
