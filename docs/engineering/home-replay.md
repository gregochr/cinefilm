# The home replay

Written 2026-11-16. A measuring rig the owner runs by hand on the laptop: `scripts/replay/`. It is not part of the
app or its image, and nothing in CineFilm depends on it. `home-replay-plan.md` is the plan that put it in git, in
two phases; this file describes what is merged so far.

## Why

A train is not reproducible, and journeys are scarce (`train-link-plan.md`). The first train viewing left a
playback-health record that says, minute by minute, what the link carried and how the film fared. The rig plays
that link back at home: the real player watches a stand-in film that arrives at the pace the train managed. A
change to the player's rules can then meet a known bad journey before it meets a real one.

## What is real and what stands in

| | |
|---|---|
| **Real** | The app itself: the Player screen, the hls.js engine and its rules, the speed probe and the playback-health recorder, built for production and run in headless Chrome. The part of the rig that builds and drives it arrives in phase 2. |
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

## The record in the repository

The repository is public. The file holds only what the replay reads: when the viewing started, the minute rows
and eight totals (time watched; the stalls' count, total and longest; steps down and up; timeouts; time at each
quality). The ids, the film's title, the network operator, the browser's user-agent string, the screen size and
the core count were left out. `train-link-plan.md` already describes the viewing itself.

## What the stand-in keeps of what the player sends

Figures, and words from its own fixed lists; never the text of a request. Its log of requests holds the kind of
route, the method, the quality and segment asked for, sizes and timings, and no address. A playback-health report
is read field by field from the list of names in `standin.mjs`: a figure the player adds to its report later is
not kept until it is added to that list. The operator's name and the browser's details are not kept at all.

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
runs `node --test` over the files in `scripts/test/replay/` and fails when fewer tests ran than it expects, or
when any was skipped). They need Node 22 or newer and nothing else: no browser and no network beyond this
machine. The link's arithmetic is tested with made-up times; the server's tests run over real HTTP on a free
port with the link left open, so nothing in them waits on a clock for longer than the link's 10 ms tick. CI runs
them on Node 24. When a test is added, raise the number in the wrapper.

What needs Chrome has no automated test (phase 2): after any change to the rig or to the player, run one minute
on a steady link and read the summary.
