# The home replay

Written 2026-11-16. A measuring rig the owner runs by hand on the laptop: `scripts/replay/`. It is not part of the
app or its image, and nothing in CineFilm depends on it. `home-replay-plan.md` is the plan that put it in git.

## Why

A train is not reproducible, and journeys are scarce (`train-link-plan.md`). The first train viewing left a
playback-health record that says, minute by minute, what the link carried and how the film fared. The rig plays
that link back at home: the real player watches a stand-in film that arrives at the pace the train managed. A
change to the player's rules can then meet a known bad journey before it meets a real one.

## What is real and what stands in

| | |
|---|---|
| **Real** | The app itself: the Player screen, the hls.js engine and its rules, the speed probe and the playback-health recorder, built for production from this checkout (`drive.mjs` runs Vite's build into `scripts/replay/dist/`) and run in the installed Chrome, headless, with a throwaway profile (`cdp.mjs`). The driver only looks at the page every five seconds; it clicks once, to wave away the "Where are you watching?" card. |
| **Stand-in: the server** | `standin.mjs` answers the few API calls the Player makes (the film, the session, progress, the health report) and serves the film. No CineFilm server, no sign-in, no watch history. It listens on `127.0.0.1` only. |
| **Stand-in: the film** | A 1080p source as `Ladder.java` and `Rung.java` ladder it: the original copied at 10 Mbit/s, then 720p, 480p, 360p and the 240p floor, with playlists written as `PlaylistWriter.java` writes them and four-second MPEG-TS segments of H.264 and AAC (`ts.mjs`). Every segment is the same four seconds of a drawn picture and of silence with its own time stamps, padded to the size its rung's average bitrate implies, times 1.08 for the wrapping. The padding sits inside the picture's stream, so the browser's media buffer weighs what a real rung would. |
| **Stand-in: the link** | One shared allowance for everything the player asks for (`link.mjs`): API answers, playlists, segments and the speed probe. It is refilled at the trace's rate for the current replay minute, saves no more than a twentieth of a second of it for later, and gives nothing at all in a dead minute. Each answer first waits 115 ms, the round trip the record measured. The app's own files are served at once: loading the app is not under test. |

## The link, one figure a minute

`trace.mjs` rebuilds the link from the record of 12 November (`scripts/replay/record/train-2026-11-12.json`),
whose minute rows hold the median of the speed samples, the quality played, the milliseconds the picture moved
and the milliseconds it stalled. The record was made under the rules of that day: a two-minute buffer and a 360p
floor.

- **A minute with 5 s or more stalled:** the buffer was empty, so what played is what arrived. The link carried
  the seconds that moved, over 60, times the rate of the quality played, times 1.08 for the MPEG-TS wrapping.
  Nothing moved means nothing arrived. (A minute that was mostly paused is not read this way.)
- **A clean minute:** the median of its speed samples. That overstates what a film gets, because a sample times
  a segment from its first byte and leaves out the wait before it. So there are two traces: `sampled` takes clean
  minutes at the sampled figure, `cautious` at 0.6 of it. The 0.6 is a guess, not a measured limit.
- **A minute with no sample or no row** takes the lower of its neighbours.

`node scripts/replay/trace.mjs` prints both traces. Inside the six bad spells both average about 0.3 Mbit/s, the
figure `train-link-plan.md` reached by hand.

## Running it

Once, after a fresh checkout: `npm ci` in `frontend/`. Chrome must be installed in `/Applications`, and Node must
be 22 or newer; nothing else is installed.

    node scripts/replay/drive.mjs --start 24 --minutes 15 --trace sampled --name smoke

| Option | |
|---|---|
| `--start` | The record minute to begin at; 0 is 17:56:53 on 12 November (`node scripts/replay/trace.mjs` lists them). Default 0. |
| `--minutes` | How long to run. A replay runs in real time: the whole viewing is `--start 0 --minutes 143`, two hours and 23 minutes. Default 15. It must end within the trace (152 minutes for the record's two traces, 400 for a flat one). |
| `--trace` | `sampled`, `cautious`, or `flat:<kbit/s>` for a steady link, which is for checking the rig itself. Default `sampled`. |
| `--name` | The folder under `scripts/replay/out/` for the results: 1 to 60 letters, digits, dots, dashes and underscores. Default `run-<start>-<minutes>-<trace>`, with the colon of a flat trace written as a dash. |
| `--wire` | How heavy a segment is, as a multiple of its quality's average bitrate. Default 1.08, the weight the trace assumes; `--wire 1` is the bare average. |
| `--fill` | `media` (the default: padding inside the picture's stream, which the browser keeps) or `null` (padding the player's demuxer throws away, so the browser's buffer stays tiny however heavy the quality). |
| `--tmp` | Where the browser's throwaway profile is made and removed. Default: the system's temporary folder. |
| `--rebuild` | Build the app again first. Use it after the checkout changes. |

The stand-in uses port 5301 on `127.0.0.1`, so one replay runs at a time; a second one stops with an error
before it starts a browser. Chrome picks its own debugging port, also on `127.0.0.1`, and the rig reads which
from the throwaway profile, so it can only ever talk to the Chrome it started. No host name but `localhost`
resolves in that browser: the app's one outside request, the operator lookup, fails as it would with no
internet, which is why the record a replay makes says `unknown` where a train viewing says `train`. (An address
written as numbers would still be reached; the app asks for none.)

Once the driver has looked at the film for the first time, five seconds in, Ctrl-C ends a replay early and
still writes its results. In a terminal the same Ctrl-C reaches Chrome too, so the player's last report may not
be sent: the summary then uses the last one it sent by itself, which can be some seconds old. Before that first
look a Ctrl-C just stops the run and nothing is written, so an earlier run of the same name stays as it was. A
second Ctrl-C quits at once and writes nothing; Chrome's throwaway profile may then be left in the folder it was
made in (`--tmp`, by default the system's temporary folder).

While a replay runs it keeps the Mac from idling to sleep; a closed lid still sleeps it, and the summary then
carries a warning, because the link's clock is the wall clock. The first run makes the film's picture and
silence with Chrome's own encoders (`make-template.mjs`) and keeps them in `scripts/replay/out/template.json`.

**After any change to the rig or to the player**, run `--minutes 1 --trace flat:3000 --rebuild` and read the
summary: about 55 s watched, no stall, every request answered. Nothing else tests what needs Chrome.

## What comes out

In `scripts/replay/out/<name>/`, which git ignores:

- `summary.txt`: for each minute, the link's rate; from the replay, seconds stalled, the quality played, the
  quality being fetched and the seconds buffered (all from the player's own health report); what the stand-in
  was asked for; and beside them the 12 November record's stalled seconds and quality for the same minute.
- `health.json`: how many playback-health reports the stand-in kept and from how many viewings, and the last
  report of the last viewing: when it arrived, and the figures kept of it.
- `samples.json`: every five seconds, the playhead, the film buffered ahead and what the screen says, as
  printable ASCII (a few common signs, such as the dot between the parts of the quality pill, are written as
  their plain equivalents; anything else outside ASCII becomes `?`).
- `requests.json`: every answer the stand-in sent, with its kind, size and timing, and whether the player cut it
  short.

## Reading a run

    node scripts/replay/analyse.mjs smoke

Read its first section before believing anything else. A replay runs in real time, so a busy Mac can stall the
film for the wrong reason. A run is sound when the five-second samples have no long gap, no pause came with film
in hand, the playhead kept the clock outside those pauses, and what the stand-in delivered in thin minutes
matches the trace. After that it lists the stretches when nothing was being fetched, the buffer going into each
of the six bad spells and its lowest point in each, what the screen showed against the link, and the time at each
quality beside 12 November. It reads which record minute the run started at from the first line of the run's
`summary.txt`; when that line is missing it says so in a warning and reads the run as starting at minute 0.

## What two replays of the whole journey showed

Both ran the 143 minutes of the record on 15 and 16 November, on the scratch version of this rig, against the
player as it stood on `main` that day (the rules of `train-link-plan.md`: a 240p floor, up to eight minutes
banked, 360p held while the bank is deep). Both were sound by the checks above. The first ran before the
segments' weight was corrected, so its film was about 8% lighter than its trace assumes.

| | 12 November, on the train | Replay, kind | Replay, harsh |
|---|---|---|---|
| Trace; weight of a segment | | `sampled`; bare average | `cautious`; 1.08 |
| Stalled | 2,011 s in 155 stalls, the longest 192 s | 5 s in 2 stalls | 206 s in 47 stalls, the longest 34 s |
| Film played in the 143 minutes | 6,331 s | 8,569 s | 8,368 s |
| Minutes at 480p, 360p, 240p | 30, 75, none | 61, 40, 39 | 40, 57, 43 |
| Steps down and up | 39 and 38 | 22 and 22 | 15 and 14 |

Stalled seconds in each of the six bad spells, with the seconds buffered going in:

| Record minutes | 12 November | Kind | Harsh |
|---|---|---|---|
| 5 to 7 | 122 | 0 (102 s banked) | 67 (18 s) |
| 12 to 23 | 450 | 0 (302 s) | 73 (140 s) |
| 27 to 37 | 478 | 5 (402 s) | 66 (202 s) |
| 52 to 61 | 370 | 0 (427 s) | 0 (396 s) |
| 81 to 85 | 143 | 0 (423 s) | 0 (420 s) |
| 100 to 110 | 371 | 0 (458 s) | 0 (460 s) |

So on this model the rules now live stall for somewhere between a few seconds and three and a half minutes where
the day stalled for 33 and a half. Read it as that order of size and no finer: it is one journey, the 0.6 of the
harsh trace is a guess, and the old rules have never been run on the rig.

Four things the replays showed in the rules as they are. None has been acted on: the next real journey runs the
same rules, and its record says how far to trust the rig.

1. **The start of the film is the weak point.** On both traces the eight-minute bank was first full 41 to 42
   minutes in, and every second of stalling in both runs came before that. The bank builds slowly at the start
   because the player fetches the best quality the link sustains: on the harsh trace it fetched 480p for four
   minutes on a link falling from 2.4 to 0.8 Mbit/s and had 77 s banked when the first spell came. By arithmetic,
   not a run, 360p would have banked about 260 s in those minutes and 240p the full 480.
2. **Film banked at 240p in a bad spell is watched after the link is back.** In the kind run 240p was on screen
   for 39 minutes, and for 18 and a half of them the link was already at 2 Mbit/s or better.
3. **A step up to 720p stops the fetching.** hls.js sizes its buffer from the quality it is loading, and 100 MB
   holds about 256 s of 720p, so the player waits for the 480 s bank to run down to that. In the kind run this
   happened five times, 955 s in all with nothing fetched, for 144 s of 720p watched; and the player takes no
   speed samples while it waits, so once the link fell to 152 kbit/s unseen.
4. **The 360p hold spent the bank in the worst spell.** In the kind run the player went in with 402 s banked, held
   360p at about half the speed of play until 238 s were left, and then met two dead minutes: the one stall. The
   same hold kept the picture at 360p or better through the short spell at minutes 81 to 85.

## The record in the repository

The repository is public. The file holds only what the replay reads: when the viewing started, the minute rows
and eight totals (time watched; the stalls' count, total and longest; steps down and up; timeouts; time at each
quality). The ids, the film's title, the network operator, the browser's user-agent string, the screen size and
the core count were left out. `train-link-plan.md` already describes the viewing itself.

## What the stand-in keeps of what the player sends

Figures, and words from its own fixed lists; never the text of a request. The one thing it sends back from a
request is the position a progress call reports, as a number. Its log of requests holds the kind of
route, the method, the quality and segment asked for, sizes and timings, and no address. A playback-health report
is read field by field from the list of names in `standin.mjs`: a figure the player adds to its report later is
not kept until it is added to that list. The operator's name and the browser's details are not kept at all.
While a replay runs the stand-in also holds, in memory only, the playback ids it has seen, to number the viewings
in what it keeps; the ids themselves go nowhere.

## What is approximate

- **The link is one rate a minute.** A real link moves within a minute.
- **Every segment of a quality is the same size.** Real segments vary, up to the declared peak.
- **The server always has the segment ready.** The real one encodes as it goes and can answer 503 for a moment.
- **The picture is the same four seconds throughout and the sound is silence:** only the bytes and the timing
  matter. Every quality carries the same small picture, whatever height its playlist declares.
- **The ladder and the playlists are copies.** They were taken from `Ladder.java`, `Rung.java`, `StartRung.java`
  and `PlaylistWriter.java` on 2026-11-15, and nothing compares them: change them here when those change.
- **A replay is compared with the real day, not with a replay of the old rules.** Until the player as it was on
  12 November has been replayed and stalls for about 33 minutes, the rig's own error is not known.

## Tests

`bash scripts/test/run-all.sh` runs them with every other script test (`scripts/test/replay-rig.test.sh`, which
runs `node --test` on each file in `scripts/test/replay/` and fails when fewer tests ran than it expects, when
any was skipped, or when a file has not ended after two minutes). They need Node 22 or newer and nothing else: no
browser and no network beyond this machine. The link's arithmetic is tested with made-up times. The server's
tests run over real HTTP on a free port, most with the link left open. Those about the link use a dead or a slow
one and wait for an event (a byte arriving, an entry in the log), never for a fixed time, except one that waits
40 ms to show that an answer waits for the round trip. CI runs them on Node 24. When a test is added, raise the
number in the wrapper.

What needs Chrome has no automated test: launching it, the socket to it, building the app, encoding the film's
picture, and the driver's own loop. The by-hand minute under "Running it" is their only check. What can be
given plain values is tested without Chrome: the matching of its replies to the driver's requests, the reading
of the port it chose, the order in which it is stopped, and the claim on the stand-in's port.

## What nobody has verified

- The rig on any machine but the owner's Mac, or with any browser but its installed Chrome.
- The old rules on the rig (above, under "What is approximate").
- A replay of a record made under the rules now live. In such a record a minute's film may have been fetched
  minutes earlier and at another quality than the one played, so "what played is what arrived", the reading
  `trace.mjs` uses for the 12 November record, does not hold. A reading for those records has not been written.
