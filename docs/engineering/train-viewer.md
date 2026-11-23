# The train viewer

Written 2026-11-17. A measuring tool the owner runs by hand on the laptop: `scripts/replay/viewer.mjs`. Not part of the
app or its image; nothing in CineFilm depends on it. `train-viewer-plan.md` is the plan and the reasons.

## What it is, and is not

It starts the installed Chrome with no window, opens the real player on the real site at the start of a film, and lets
it play, so that a train journey leaves a playback record with nobody watching. The film comes from the owner's server
over whatever link the laptop is on, and the player's own rules decide everything. It never picks a quality, seeks or
pauses. It is not a test of the app (CI starts no Chrome), not the home replay (`home-replay.md`) and not a second
viewer: one viewing at a time.

## Signing in

`node scripts/replay/viewer.mjs --sign-in` opens an ordinary Chrome window on the site, on a profile the viewer
keeps in `~/cinefilm-viewer/profile/`. Sign in there, press Return in the terminal, and wait: it gives Chrome 35 seconds
to save the sign-in, then ends that Chrome and says whether the site took it. `--site` names another
address (a sign-in belongs to one host). **A release or a restart of the server signs every browser out**, so sign in
again after the last release before a journey, and check it says "Signed in".

## Where to run it

Run it from a Terminal window in your own login session, as yourself, with the screen unlocked. Chrome encrypts the kept
profile's cookie with a key from your login keychain. Started with `HOME` pointing somewhere else, the viewer looks for
its folder there (a new profile, not signed in), Chrome finds no login keychain, and macOS asks to make one: until that
prompt is answered Chrome asks the site for nothing, and the viewer says the site is not answering. Over ssh or from a
scheduled job it is untried.

## Before a journey

- The film's id is the number in the address of its page: `/films/42` is film 42.
- Choose a film longer than the journey: it does not play a second film when the first ends.
- Its quality must be Auto (a quality picked by hand is saved per film, and a pinned film would not measure the
  rules). The viewer refuses at once if it is not: set it back to Auto on the film's page.
- Do not run it beside a film being watched: the two would share the link and spoil both records. Nothing enforces it.

## On the train, in order

1. The laptop on power with the lid open (the viewer keeps the Mac from idling to sleep, but a closed lid sleeps it).
2. Tailscale up, then the train feed logger (`train-feed-logger.md`), answering that a film will be playing.
3. `node scripts/replay/viewer.mjs --film <id> --place train`. Add `--minutes <1 to 600>` to stop earlier; without
   it the run lasts until the film ends. `--place away` is for a hotel; with neither, the "Where are you watching?"
   card is answered "Not now", and Admin's filters leave such a record out.

If the site does not answer (at the session check, the film's facts or the opening of the player's page) it says so once
and starts again every 15 seconds, for up to ten minutes after the first miss: a dead spell, or Tailscale still coming
up, must not lose the journey. Signed out, no such film and a hand-picked quality each stop with one line. If it says so
while the site opens in your own browser, look for a keychain prompt from macOS on the screen, and see "Where to run
it".

## What the lines mean

Every 30 seconds one line: `HH:MM:SS +<seconds in>s playhead <seconds> in hand <seconds buffered>s | <what the screen
says>`. `DIRECT PLAY` means the player has given up on streaming and plays the original file directly: that is what it
does when streaming fails, and it is no measure of the rules. If the laptop stood still (slept) for a while, that is a
warning, kept in the results and printed in the summary at the end: it says for how many seconds nothing was watched.
It presses "Try again" on the player's two cards that offer it (counted), the place card once, nothing else.

## Ending it

Ctrl-C once, and wait: the page is sent to a blank page, three seconds are given for its closing sends, then Chrome
is ended and the results are written. A second Ctrl-C quits at once, after Chrome is hard-stopped and the results are
written as far as there are any. Each of these ends a run, with its own word in the results: the film's end,
`--minutes`, a signal, a sign-out (one line saying to run `--sign-in` again), a stop from Admin ("Playback was stopped
from Admin"), Chrome going away, and the player's page no longer answering: three looks at it in a row, each given ten
seconds, that could not be read (a page whose renderer has died answers nothing; the viewer does not reload it).

## The results

`~/cinefilm-viewer/runs/<YYYYMMDD-HHMMSS>/` (local time the run began; readable by the owner alone, never in a
checkout). The four JSON files are written with the first sample, again whenever a minute has passed since the last
write, and at the end, each time whole (a temporary file renamed onto the real name), so a laptop that dies leaves the
last good files, with `ended` null: the run had not ended. `summary.txt` is written at the end only. The writes made
during the run do not hold up the film's requests; the last one is made after Chrome has gone. A write that fails
mid-run is one warning and the run goes on. A file ending `.async.tmp` in a run's folder is a mid-run write that was cut
short (a second Ctrl-C, a crash or a kill at that moment): it is safe to delete.

| File | Holds |
|---|---|
| `summary.txt` | the lines printed at the end: the film, when it began, how long it played from the film's opening, how it ended, the totals of the latest record, the samples, the presses, what was held, the first ten page errors, the warnings |
| `samples.json` | every five seconds: what the screen said, the playhead, the buffer, the four flags (direct play, signed out, stopped, a "Try again" offered), milliseconds since the film opened and the time of day |
| `health.json` | the playback records the player sent, as it sent them: how many, how many could not be read, and for each viewing its id, how many were sent and the latest (this includes the network operator's name, the browser's name and the screen size) |
| `held.json` | the run's facts and how it ended, what was held back (method, address shape, count: never a header, a cookie or a body), the presses, the page's errors, the warnings |
| `segments.json` | every media segment download the page made, one entry each (see below) |

`summary.txt` is made from the other files. The samples carry the time of day, so a run lays beside the logger's log.

### `segments.json`

Every download of a media segment, so that each download's speed can be laid beside the train's position (a minute's
median in `health.json` hides a ten-second drop). It is made from Chrome's `Network` events for `GET` requests to the
site's own origin whose path is `/api/sessions/<id>/<rung>/<file>` and whose file is not a playlist (`.m3u8`). The
session id, the query and the address are never stored. Playlists are counted, not listed. The guard (`guard.mjs`)
answers or fails only writes, never a `GET`, so a request it handled on purpose is never an entry and a failure here is
the network's. Written with the other files, each time whole, and on every way a run ends, signal and crash included.
Chrome's own times are used (the same clock the page's requests are measured on), not the viewer's.

```
{ "cap": 20000, "capped": false, "dropped": 0, "playlists": 41, "unseen": 0, "segments": [ { ... } ] }
```

| Field | Meaning |
|---|---|
| `cap`, `capped`, `dropped` | the list holds at most `cap` (20,000) segments; `capped` is true when later ones were left out, `dropped` is how many |
| `playlists` | how many playlist requests were seen (not listed) |
| `unseen` | Network events for a request whose start was never seen; they are dropped |
| `rung` | the rung in the address, such as `720p` |
| `file` | the file name, such as `seg_00012.ts` |
| `status` | the HTTP status, null when no answer came |
| `bytes` | bytes on the wire (`encodedDataLength`), null for a download that failed, was cancelled or had not finished |
| `startMs` | when the request began, milliseconds since 1970 (Chrome's `wallTime`); null when Chrome gave none |
| `ttfbMs` | from the request's start to the first byte of its answer (the headers' start, else their end), in ms; includes any wait for a connection; null when Chrome gave no timing (a cached answer) |
| `totalMs` | from the request's start to its finish, failure or cancelling, in ms; null while unfinished |
| `failed` | the download failed (not cancelled); `errorText` says how |
| `canceled` | Chrome says the page cancelled it (the player aborts a download it no longer wants) |
| `errorText` | Chrome's error text (`net::ERR_...`), printable and cut at 100 characters; null when none |
| `unfinished` | true for a request still in flight when the results were written (the last write is made after Chrome has gone, so these were cut off) |

Speed in kbit/s is `bytes * 8 / totalMs`, for a finished download with a 2xx status only. `summary.txt` has one line:
segments recorded, failed, cancelled, unfinished, and the median and 10th-percentile speed.

## What a run changes on the server

A stream session started and ended (which may save a film's keyframe index, and at the limit of three sessions pushes
out the one least recently used), and one playback record. Admin shows that record like any other, with
`HeadlessChrome` as its browser, and counts it in its figures. While the viewer runs, every other write the page tries
(a progress save, a watched mark, a preference) is answered inside Chrome and never sent: the film's resume point and
watched mark are what they were. On a slow link the server's copy of the record may lack its last minute where the
viewer's, in `health.json`, does not.

## What a driver killed outright leaves behind

If the driver itself is killed (`kill -9`, or Node itself dying; an error in the script is not this case: Chrome is
killed first and the results written), Chrome ends by itself, but it takes about ten seconds. As it does, the page's
closing save may get out (in two tries by hand it did once). That film's resume point then moves to where the viewer
was, the film appears in Continue watching, and past its credits it is marked watched. Marking the film watched or
unwatched on its page clears the resume point, and the menu on its Continue watching card takes it out of there. The
results on disk are those of the last write, about a minute old, and they say the run had not ended. The viewer's own
lock is left behind and the next start takes it over. If the next start says a Chrome is still using the profile, wait
ten seconds and start again.

## What nobody has verified

A train: whether the site is reached, the bad spells and the player's recovery are what the journey is for. Two hours
and more of a real film in headless Chrome on this laptop. The cookie staying readable if the login keychain locks
while the laptop sleeps. The viewer started over ssh or from a scheduled job.
