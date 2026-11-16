# Joined viewings: one film watched in more than one playback

Owner request (2026-11-16): the Admin "How films played" chart showed The Rock on 12 Nov as two columns, but it was
one film watched once. The selected session's figures line should also say when it started and ended.

## What the records show

The two rows of 12 Nov (production, read by the owner on 2026-11-16):

| | started (London) | last report | watched | stalls | network | device |
|---|---|---|---|---|---|---|
| fb22ac5e | 17:23:30 | 17:56:47 | 32 min | 4 | train, Icomera | Mac Chrome |
| 323111c2 | 17:56:53 | 20:33:53 | 106 min | 155 | train, Icomera | Mac Chrome |

The gap was **6 seconds**: the player reloaded and opened a new playback id. 32 + 106 min is the whole film. A
real pause (a station, dinner, a hotel) splits a viewing the same way, only with a longer gap.

The reports carry no film position, so "picked up where it stopped" cannot be proven on the old records.

## The rule: when two playbacks are one viewing

Playback B joins the viewing whose last part is A when **all** of these hold:

1. **The same film** (`filmId`).
2. **The same device:** the same `client.userAgent`, and neither is empty.
3. **B started at most 6 hours after A's last report** (`B.startedAt - A.updatedAt` between 0 and 6 h).
4. **It picks up where A stopped:**
   - When both reports carry positions: B's `startPositionMs` is within 2 minutes of A's `endPositionMs`.
   - When either lacks them (every record before this change): the same `environment` and the same `operator`.

Only the newest viewing's last part can take a new part, and rows are walked oldest first. So a viewing is a chain,
never a tree.

A rewatch from the start fails rule 4 once positions are recorded, so it stays apart.

The 3-minute "film session" rule (`FILM_SESSION_MIN_MS`) applies to the **joined** viewing's total watched time,
after joining. Joining runs over every row in the window, the short ones included, so a 2-minute part between two
long ones still joins. A part that started before `since` is not read: the window is by `startedAt` of rows, as
today.

## PR A: backend (`feat/joined-viewings-backend`)

### The report gains positions

`HealthReport` gets `startPositionMs` and `endPositionMs` (`Long`, nullable, each at least 0 when present; a
negative value is a 400 like other bad figures). They are kept in the stored summary.

The player sends them in PR B. Old reports lack them and read as null.

### Joining

The join lives in `PlaybackHealthService`, in one package-private pure function with its own unit tests:
`ViewingJoiner.join(List<rows>)`, or a static method; Sonnet's choice. Rows go in oldest first, each carrying:

- `playbackId`, `filmId`, `startedAt`, `updatedAt`;
- the figures;
- `environment` and operator;
- `userAgent`;
- the two positions.

It returns viewings.

**`chart(since)`** reads every row with `startedAt >= since` (no `minWatchedMs` filter in the query), joins them, and
keeps the viewings whose total `watchedMs` is at least `FILM_SESSION_MIN_MS`. Each `ChartRow` is now a viewing:

- `playbackId`: the **first** part's (the column's key and the selection).
- `startedAt`: the first part's. A new field, `endedAt`, is the last part's `updatedAt`.
- `watchedMs`, `stallCount`, `stallMs`: the sums. `rungMs`: summed per rung (null only if every part's is null).
- `startupMs`: the first part's. `environment`, `userAgent`, `touch`: the first part's.
- New: `parts`, a list of `{playbackId, startedAt, endedAt, watchedMs, environment}`, oldest first. It always has
  at least one entry, and a single playback is a one-part viewing.

**`playbackCounts()`** (the Admin rail and To do strip) counts viewings the same way, so the rail agrees with the
chart.

- Load the rows of the 30-day window and join them.
- Count the viewings of at least 3 minutes, and those where no part `hasProblem`.

The table is capped at 2,000 rows and one user makes a few a day, so loading them is cheap. The two `count`
repository methods go if nothing else uses them.

The log (`GET /api/admin/playback-health`) and one record (`/{playbackId}`) are unchanged: each playback stays its
own record there.

### Docs and tests

- foundation-plan: the chart row's new fields (`endedAt`, `parts`), the report's two positions and the joining rule
  (point to this file).
- Unit tests for the joiner:
  - each rule's boundary: 6 h exactly joins, 6 h + 1 ms does not; 2 min exactly joins, 2 min + 1 ms does not;
  - an empty user agent;
  - a different film;
  - a different operator without positions;
  - positions present on one side only (the network rule applies);
  - three parts in a chain;
  - a short part between two long ones;
  - a negative gap (overlap) does not join.
- Service tests: `chart()` and `playbackCounts()` on real rows (H2), including the 12 Nov pair above as a fixture.
- MockMvc: the chart answer's new fields; a negative position is a 400.
- Changelog: `changelog.d/20261116-joined-viewings.md`.

## PR B: frontend (`feat/joined-viewings-frontend`, merges after A)

### The player sends positions

`player/health.js` records the first finite position a `beat` sees as `startPositionMs` and the latest as
`endPositionMs`. Both are whole ms, and null before any beat. They go in every report. `newViewing()` resets them.

### The chart

A column is a viewing, the server's row as it is. The tiles and the summary already read the rows, so they count
viewings with no change.

The selected viewing shows, for **each part** in order:

- **Its strip and figures lines** (its own record, read as today through `usePlaybackRecord`, one read per part).
- **A start and end time** at the front of its figures line: `17:56–20:33 · Start 6.3 s · 155 stalls, 33m 31s`.
  When the part ends on a later day than it starts, the end also gets its date: `23:40–12 Nov 01:10`. A one-part
  viewing shows the times too, which is the owner's second ask.
- **Between two parts, one quiet line:**
  - `Picked up again after 6 s` when the gap is under 1 minute (`after 6 s`);
  - `Paused 1h 20m` otherwise.

The heading keeps the first part's date and time. It says watched for the viewing's total and, for more than one
part, adds `in 2 parts`.

The record reads are made for the selected viewing's parts only, as today for one. A part whose record is gone (404)
shows the existing "no minutes" text for that part alone.

### Tests

- `health.js`: the positions, null before a beat, reset by `newViewing()`.
- `playbackChart.js` / `healthFormat.js`:
  - the time range, including across midnight;
  - the gap line on both sides of 1 minute (59,999 ms vs 60,000 ms);
  - `in 2 parts`.
- `SelectedSession`: two parts, a 404 on one, loading and error per part.
- Fixtures: the 12 Nov pair as one two-part viewing.
- Changelog: `changelog.d/20261116-joined-viewings-chart.md`.
