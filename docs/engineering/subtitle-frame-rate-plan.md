# Subtitles that follow the film's frame rate — plan

Status: plan, 2026-11-25, written at the owner's request ("Fix playback first"). Found during 3e
(`soundtrack-confirm-plan.md`), which waits until this is released.

## Why

Most of the library is 25 fps, but the subtitle code assumes every film is 23.976 fps.

**The library** (owner's query, 2026-11-25, `media_keyframes.fps` of every `ORIGINAL` media file):

| Frame rate | All files | With a forced file |
|---|---|---|
| 25.000 | 659 | 96 |
| 23.976 | 420 | 102 |
| 24.000 | 10 | 0 |
| 30.000 | 7 | 1 |
| others or none | 12 | 1 |

A 25 fps file is a European (PAL) copy: the 24 fps film played 4% fast, so it is about 4% shorter. A subtitle timed
for a 23.976 or 24 fps release drifts on it, at about 2.4 s per minute: in sync at the start, about 4 minutes late
after 100 minutes. The reverse holds for a 25 fps subtitle on a 23.976 film. A 30 fps file is a telecined copy that
runs at the film's own speed, so it needs no change.

**The served subtitles** (owner's query, 2026-11-25, every present sidecar by the film's rate and the rate
OpenSubtitles states for the file):

- **Forced, the owner's own files:** 28, all on 23.976 films. None drift.
- **Forced, downloaded by CineFilm, on 25 fps films:** 84 stated as about 24 fps (77 at 23.976, 2 at 23.98, 5 at
  24.0), 4 stated as 25, and 10 with no stated rate.
- **Forced, downloaded, on 23.976 films:** 71 stated as about 24 fps, 1 stated as 25, and 5 with no stated rate.
- **Full, downloaded:** 8 at 23.976 on 25 fps films, and 4 at 23.976 on 23.976 films.

So about 92 downloaded files drift today, chosen by CineFilm itself: `OpenSubtitlesRanking` ranks candidates by
closeness to 23.976 (`TARGET_FPS`), which is the wrong rate for a 25 fps film.

**The stated rate mostly holds.** The planner checked it against the soundtrack on 3e's design films: half A, the
`tiny` speech maps, score B. That uses no Plex data and no held-out data. For each forced file on a 25 fps film with 8
or more cues, the best z-score near the offset the file plays with (within ±2 s) was measured at ratio 1 and at
ratio stated/25:

| Film | Stated | As played | Retimed |
|---|---|---|---|
| 1917 | 23.976 | 1.5 | 3.4 |
| Comic Book Origins: Captain America | 23.976 | 1.4 | 4.5 |
| Hitman | 23.976 | 0.1 | 3.1 |
| Inferno | 24.0 | 0.0 | 4.1 |
| Jack Ryan: Shadow Recruit | 23.976 | −0.3 | 3.2 |
| Bridge of Spies | 25.0 | −0.4 (no change) | 3.5 at 24/25 |

- On the five files stated as about 24 fps, the retimed file sits on the foreign speech and the file as played does
  not. Hitman as played reads 0% foreign and 97% English under its cues; retimed, it reads 67% and 6%.
- Bridge of Spies is stated as 25, but behaves like a 24 fps file. A stated rate can be wrong.
- Four more (A Brilliant Young Mind, A Most Wanted Man, Batman v Superman, Severance) show too little signal either
  way. Rocky IV is a 30 fps copy and fits best as it is.

The owner can hear it: Jack Ryan: Shadow Recruit's "Mr. Cherevin, we need to act now." is shown at 1:32:45, but the
Russian it translates is spoken at about 1:28:57.

## What changes

A downloaded subtitle is **retimed to the film**: every cue time t becomes k·t, then the part's start and the
owner's offset are added as today. k is the subtitle's stated rate over the film's rate, but only across the PAL
speed-up:

- the film is 25 fps (24.9–25.1) and the subtitle is stated as about 24: k = 23.976/25 when stated as 23.976 or 23.98,
  and 24/25 when stated as 24.0;
- the film is about 24 fps (23.9–24.1) and the subtitle is stated as 25: k = 25/23.976 or 25/24 by the film's rate;
- anything else gives k = 1: an unknown rate on either side, a 30 fps copy, or a matching rate.

The owner's own files have no stated rate, so they are never retimed. Embedded tracks are already on the film's
timeline.

Five more changes:

- **Every check sees the film's timeline.** The served cues (`ServedSubtitles.of`) carry the retime. A reference file
  that a download is compared with is retimed by its own stated rate before the comparison. Today a download and its
  reference both drift the same way and the proof passes; once only the download is retimed, the proof would fail
  and the download would stop playing. The comparison rules version goes up, so every check is made again.
- **Ranking follows the film.** On a 25 fps film, a file stated as 25 ranks first, a file stated as about 24 next
  (it can be retimed), and a file with no stated rate last; the reverse holds on a 23.976 film. The same order is used
  for forced and full downloads, the checks' reference candidates, the owner's picker and `TimingReferenceRule`'s
  plausible rates. A file already downloaded is not replaced because of this. It is retimed instead.
- **The owner can switch a file's retime off.** Bridge of Spies shows that a stated rate can be wrong. Each
  downloaded file gets **Retime: automatic or off**, set in the Admin. Off serves the file as it is, as today.
- **The Admin says what happened.** For example: "Timed for 23.976 fps; this film is 25 fps, so it is sped up 4%".
  The picker shows each result's rate against the film's.
- **The served version changes when the retime does**, so the player and the checks never keep a stale file.

Not in this plan:
- the 10 forced downloads on 25 fps films with no stated rate. They stay as they are, and the Admin says the rate is
  unknown;
- finding a file's rate from the soundtrack (3e) or from an embedded track. Only 3 forced files have one.

## Phases (each PR at most 1,500 changed lines, tests included)

1. **The rate data (backend, no change in what plays).**
   - Migration `V39`: `subtitle_sidecar.timed_fps` and `opensubtitles_reference.timed_fps` (nullable `DOUBLE
     PRECISION`), and `subtitle_sidecar.retime` (`AUTO` or `OFF`, default `AUTO`).
   - A download, and a reference when it is fetched, stores the rate from its OpenSubtitles result.
   - A one-time backfill fills rows already stored from the film's candidate JSON (`os_candidates`,
     `os_full_candidates`, by file id). It is done in Java, not SQL, because H2 has no `jsonb`. A file it can't match
     stays null, so it is never retimed.
   - A pure `FrameRateRetime` gives k from a stated rate and a film rate, by the rules above, with exhaustive unit
     tests at each band edge.
2. **Retime what plays (backend).**
   - `ServedSubtitles.vtt`, `ServedSubtitles.of` and the version string apply k per slot. The rate is read from the
     slot's media file (`media_keyframes`), and the scale comes before the part start and the offset.
   - References are retimed before every comparison.
   - The rules version goes up.
   - The owner's offset stays in film time.
3. **Rank by the film's rate (backend).** `OpenSubtitlesRanking` and `TimingReferenceRule` take the edition's film
   rate instead of `TARGET_FPS`. The tests that pin 23.976 are rewritten for both film rates.
4. **The Admin (frontend and one endpoint).**
   - `PUT /api/admin/subtitles/{id}/retime` takes `AUTO` or `OFF`.
   - The file's rate line and the switch go in the Admin's subtitle view.
   - The picker shows each result's rate against the film's.

Phases 1–3 are backend and 4 is frontend. 2 depends on 1. 3 and 4 depend only on 1 and touch different files from 2,
so they may be built beside it (CLAUDE.md step 7).

## Checks after release

- The owner plays Jack Ryan: Shadow Recruit at about 1:28:57. The Russian line's subtitle should be on screen.
- A read-only query counts the forced and full downloads on 25 fps films whose served version carries a retime, and
  how many of them are proven (expect about 92, and no drop in the proven count).
- The proven count of every kind, before and after, from the Admin's subtitle summary.

## Decisions taken in this plan

1. **The stated rate decides.** It held on 5 of the 6 files with signal. The owner's switch covers a wrong one.
2. **Only across the PAL speed-up.** 30 fps copies run at film speed, and odd rates are left alone.
3. **23.976 and 24.0 are kept apart** (k = 23.976/25 against 24/25). They differ by 0.1%, about 7 s over two hours.
   The stated value is used as given.
4. **No replacing of downloads for their rate.** A retime fixes them without spending downloads.
5. **No Plex input or answer.** The film's own rate and OpenSubtitles' stated rate only.
