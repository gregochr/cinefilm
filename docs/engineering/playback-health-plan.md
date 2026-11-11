# Playback health and the environment — plan (step 1: measure)

Written 2026-11-10, after `network-resilience-review.md`, and revised the same day after an adversarial review
(its findings are folded in; the larger ones are listed at the end), and again on 2026-11-11 while it was built
("What building changed"). The owner chose to **measure first**, and
added a second dimension to the problem: not only how playback went, but **where** it happened (home, a train, a
hotel), so later steps can pre-empt problems instead of reacting to them. This step only records. It changes nothing
about how a film is streamed.

## Decisions (owner, 2026-11-10)

- Record every viewing, home included ("knowing how playback behaves on home Wi-Fi is important too").
- The environment is recognised by latency and by the network operator, and confirmed with a quiet question in the
  player. His laptop use is the London–Durham train and hotel Wi-Fi.
- The operator comes from a public lookup made by the browser (`https://ipinfo.io/json`). He accepted that the
  service sees his public address; he was told it learns nothing about CineFilm, and the design keeps that true.
- No location. A laptop has no GPS, and the lookup's own place guess was 40 miles out (Reading for Finsbury Park).

## Three pull requests

| PR | What | Why it stands alone |
|---|---|---|
| 1 | **Store and show:** the table, the report route, the Admin routes and the Admin card | Nothing sends yet; the card says "No playbacks recorded yet." |
| 2 | **The recorder** in the player, with the environment fixed at `unknown` | From here every viewing is recorded |
| 3 | **The environment:** latency, the operator lookup, the question card, remembered networks | Tags each viewing; adds the one new piece of player UI |

## What was checked before planning (2026-11-10, production, Chromium)

- **Latency from Resource Timing works.** `responseStart − requestStart` of a `GET /api/session` gave 48–57 ms
  from a hotel line while wall-clock time around the same `fetch` was 55–144 ms; the entry was there as soon as the
  body had been read. Main-thread work inflates wall-clock timing but not this figure.
- **A direct lookup would tell the service the app's hostname.** A cross-origin `fetch` always sends `Origin`
  (`https://cinefilm.chrisjgregory.dev` was received by an echo service, with `referrerPolicy: 'no-referrer'` set).
  **Through a sandboxed `srcdoc` frame the request carries `Origin: null`** and still returns
  `"org": "AS5089 Virgin Media Limited"`. The lookup therefore goes through such a frame.
- None of this was checked in Safari. No latency was measured at home, so the home threshold is a first guess.
- The server cannot tell networks apart itself: every request arrives from the device's tailnet address.

Checked again on 2026-11-11, before the environment was built:

- **A full Resource Timing buffer takes no new entry, but an observer is still handed it.** With the buffer's limit
  set below what it already held, a `GET /api/session` left nothing for `performance.getEntriesByName`, while a
  `PerformanceObserver`'s `takeRecords()` had the entry as soon as the body was read (before its callback ran).
  The buffer holds 250 entries and a Library page of posters fills it, so latency is read through an observer.
  The entry is there for a 401 too.
- **The frame exactly as built** (hidden, `sandbox="allow-scripts"`, `srcdoc`, `credentials: 'omit'`,
  `referrerPolicy: 'no-referrer'`) returned the operator, and an echo service saw its request carry `Origin: null`,
  no `Referer` and no cookie. The message it posts has the origin `"null"`; the page knows it by its `source`.
- **Under the project's Vitest and jsdom** a stubbed `fetch` produces no entry and a `srcdoc` frame's script does
  not run, which is what keeps the detection inert in the existing player tests.

## The record

One record per viewing: one mount of the player on one edition. The browser makes a random id
(`crypto.randomUUID()`) and sends the totals so far; each send replaces the one before.

```json
{
  "seq": 4,
  "editionId": 11,
  "elapsedMs": 185000,
  "environment": "train",
  "operator": "AS5089 Virgin Media Limited",
  "latencyMs": 48,
  "engine": "hlsjs",
  "quality": "auto",
  "autoplayRefused": false,
  "startupMs": 2380,
  "watchedMs": 171000,
  "stallCount": 2,
  "stallMs": 9400,
  "longestStallMs": 6100,
  "seekCount": 1,
  "seekWaitMs": 1800,
  "reloadCount": 0,
  "reloadMs": 0,
  "reconnectCount": 1,
  "reconnectMs": 12000,
  "recoveries": 0,
  "fellBack": false,
  "stepsDown": 1,
  "stepsUp": 0,
  "netErrors": 3,
  "timeouts": 1,
  "kbpsLow": 420,
  "kbpsMedian": 910,
  "kbpsHigh": 2400,
  "kbpsSamples": 41,
  "rungMs": { "480p": 58000, "360p": 113000 },
  "minutes": [[0, 900, 4, 58000, 0], [1, 0, 5, 41600, 9400], [2, 760, 5, 60000, 0], [3, 800, 5, 11400, 0]],
  "client": { "userAgent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)", "screen": "1512x982", "cores": 8 }
}
```

| Field | Meaning |
|---|---|
| `seq` | rises by one with every send attempt; the server keeps a report only when its `seq` is higher than the one it holds |
| `elapsedMs` | time since this viewing's route opened (the player opened, or its address last changed to another film or edition, which starts a new viewing in the same player); the server takes the start time from it |
| `environment` | `home`, `train`, `away` (fixed Wi-Fi that is not home) or `unknown`; always `unknown` until PR 3 |
| `operator`, `latencyMs` | the lookup's `org` and the round trip measured at open; null when not known (always null until PR 3) |
| `engine` | `hlsjs`, `native` (iPhone) or `direct`; null until a source is attached |
| `quality` | the viewer's choice at the time of the send (`auto`, `original`, a rung) |
| `autoplayRefused` | true when the browser refused to start the film by itself and the viewer had to press play |
| `startupMs` | this viewing's route opened to the first `playing` event, less any time spent waiting for the viewer after a refused autoplay and less any sleep (a single step over 150 s, see "Moving"); null until there is a first frame |
| `watchedMs` | time the picture was moving (see "Moving") |
| `stallCount`, `stallMs`, `longestStallMs` | `waiting` after the first frame, not during a seek or a reload, until `playing`, `pause` or `ended`. A pause ends a stall, so a stall the viewer pauses through counts twice |
| `seekCount`, `seekWaitMs` | seeks the viewer asked for, and the time from each to `seeked` or `playing`. A seek through the player's own controls is counted where it is asked for. A seek made any other way (the iPhone's native fullscreen player, the system's media controls) is counted from the element's `seeking` event, except under hls.js, whose own position corrections fire the same event. The player's own restoring seeks are never counted |
| `reloadCount`, `reloadMs` | times the source was replaced after the first frame (a rebuilt session, a quality or audio change, a fallback), and the time from each until the film was playing again or ready while paused |
| `reconnectCount`, `reconnectMs` | times the "Reconnecting…" notice began and how long it showed; this time usually lies inside a stall as well |
| `recoveries` | times the session had expired and the player set about making a new one. Counted when the rebuild begins: the expired session is the event, whether or not a new one can then be made (a rebuild that fails also shows as `fellBack`, and either way it is one reload) |
| `fellBack` | true once the player has direct-played because streaming failed: no session could be made, no HLS engine could start, or the stream was abandoned. Not when Original was chosen for a film without a copy rung, and not when the worker is offline |
| `stepsDown`, `stepsUp` | rung changes to a shorter or a taller picture while the quality is Auto |
| `netErrors`, `timeouts` | hls.js network errors that got no HTTP answer at all: `timeouts` when the error's detail ends `TimeOut`, `netErrors` otherwise. An error with an HTTP status (an expired session's 404, the encoder's 503) is neither |
| `kbpsLow`, `kbpsMedian`, `kbpsHigh`, `kbpsSamples` | 10th, 50th and 90th percentile (nearest rank) of the segment download speeds and how many there were; null, null, null and 0 with no samples (always so on iPhone) |
| `rungMs` | time the picture was moving on each rung, by rung id |
| `minutes` | one entry per minute in which something happened, at most 360, in order: `[minute number since the player opened, median kbit/s of that minute's samples (0 for none), the rung, ms the picture moved, ms stalled]`. The rung is the one being played when time was last counted in that minute (moving or stalled), as a code: 0 none (nothing moved or stalled in it, or no rung known yet), 1 `original`, 2 `1080p`, 3 `720p`, 4 `480p`, 5 `360p`, 6 `240p`. The current, unfinished minute is included |
| `client` | the browser's own description: user agent, screen size, logical cores (null where the browser has none) |

The per-minute track is what later steps need: a minute with the picture moving and no speed sample is a gap the
buffer covered, a minute with stalled time is one it did not, and the run lengths of both say how much to bank.

**Moving.** The picture counts as moving from `playing` until `pause`, `waiting`, `ended` or a source change, and
only while the playhead advances: `timeupdate` is a heartbeat, and moving time is credited no further than 1.5 s
past the last heartbeat that moved the playhead. That covers a frozen picture that fires no event (iPhone's native
player) and a closed lid. The wait of a seek the viewer asked for is never moving time, whether or not the element
fires `waiting` for it (a seek inside the buffer often does not): it is seek wait only, and the heartbeat starts
again at `seeked`. The clock is `performance.now()`. Any single step longer than 150 s is taken to be a sleep and
is not counted towards anything.

**A source change** is the `<video>` element's `emptied` event. It fires for every rebuilt session, quality or
audio change, native reload and fallback, and the element then resets itself without firing `pause` or `seeked`.
After the first frame it stops the picture, ends any open stall or seek, and starts a reload that lasts until the
next `playing`, or `canplay` while paused. During a reload `waiting` is ignored.

**When it sends** (`PUT /api/playback-health/{playbackId}`):

- at the first frame; and 20 s after the player mounts when there is still none (so a viewing that never starts,
  or is waiting for the viewer to press play, is recorded; the first frame then sends again);
- every 60 s when something has changed since the last send that **succeeded**: a counter, a flag, or any moving,
  stalled, seeking, reloading or reconnecting time. A paused, idle player sends nothing;
- when the page is hidden or closed, and when the player is left, with `keepalive`, under the same condition;
- the minute's send waits while another timed send is still in flight; the first-frame send does not wait (the
  server keeps the higher `seq`). A timed send is aborted after 15 s; a failed send is dropped, because the next
  one carries everything. A 400 is logged to the console once per viewing.

The client normalises before it sends (rounds, clamps numbers to 0–2,000,000,000, cuts strings to their limits), so
one bad value cannot make every later report of the viewing fail. These limits therefore exist on both sides by
design: the client keeps to them and the server enforces them.

## The environment (PR 3)

Decided when the player opens, in this order. The film starts regardless: nothing here delays it, and nothing here
can break it (the rules inside the player apply).

1. **Latency** (`src/player/latency.js`). Small `GET /api/session` requests, one after another, each given up after
   4 s: one that is timed, then two more. The browser's own timing of each request (`responseStart − requestStart`
   of its Resource Timing entry) is the network round trip; the smallest of them, rounded once, is the latency. No
   figure means `unknown`: no lookup and no question. Under **20 ms** is `home`: no lookup, no question.
   - The entry is read through a `PerformanceObserver` (`takeRecords()` as soon as the body has been read, then up
     to 200 ms more for a browser that delivers it late), **not** from `performance.getEntriesByName`: the page's
     Resource Timing buffer holds 250 entries, a Library page of posters fills it, and a full buffer takes no new
     entry. An observer is handed every entry all the same (checked 2026-11-11, "What was checked before planning").
   - An entry counts when `requestStart > 0` and `responseStart >= requestStart`, whatever the HTTP status.
   - Until one request has been timed, a request that fails or times out is followed by another after a pause that
     grows (2, 5, 10, 20, 30 and 30 s), so a dropout as the player opens (a tunnel, a cutting) does not leave the
     viewing untagged; when the pauses are used up there is no figure. Once one has been timed, two more follow at
     once, and a failure among those two is simply left out.
   - A request that is answered but has no usable entry ends the measurement at once: this browser gives no timing
     and asking again will not change that (this is what happens under jsdom, so the player's tests send one
     request and stay `unknown`).
   - No `PerformanceObserver`, or one that will not observe `resource` entries: no request at all, `unknown`.
2. **Operator** (`src/player/operator.js`). Otherwise the operator is looked up, given up after **15 s** (a lookup
   needs a DNS answer, a TLS handshake and a request, which is several round trips on the kind of link where the
   answer matters most; the film is already starting behind it). An operator this browser has an answer for gives
   that answer (`train` or `away`) and no question.
3. **The question.** A new operator, or no operator, opens the card:

   > **Are you on a train?**
   > CineFilm hasn’t seen this network before: Virgin Media Limited. It will remember your answer on this device.
   > [ Yes, on a train ] [ No ] [ Not now ]

   With an operator the answer is kept in `localStorage` (at most 50 operators, the oldest dropped) and a toast
   says "Remembered for Virgin Media Limited"; when storage fails there is no toast and the answer holds until
   the player is left. With no operator the text is "CineFilm can’t tell which network this is, so it will ask
   again next time." and the answer holds until the player is left: nothing is stored, because an answer with no
   network to hang it on would be handed to the next network whose lookup fails. **Yes, on a train** is `train`;
   **No** is `away`.
4. Until it is answered the environment is `unknown`. **Not now** and Escape close the card until the player is
   next opened and remember nothing.

Whatever is known is handed to the recorder as it becomes known (`health.environment({ environment, operator,
latencyMs })`), so the next report carries it: the latency as soon as it is measured, the operator when the lookup
answers, the environment when it is decided or answered.

The operator's display name is the lookup's `org` without its leading `AS<digits> ` (`operatorName` in
`src/state/healthFormat.js`, from PR 1). The stored key is the whole `org`, and Admin shows the whole `org`.

- **Why a plain question and not "It looks like you're on train Wi-Fi":** on a network CineFilm has never seen
  there is nothing to base a guess on. Once the owner has answered, the network is recognised silently.
- **Why per browser and not on the server:** a table, three routes and a request at every open, on the link that is
  already struggling, to save one answer per browser. The label is recorded with every viewing regardless.
- **The 20 ms line is a guess.** Away measured 48 ms; home is expected to be a few ms. Every viewing records its
  latency, so the first home viewings confirm or move it. A slow day at home shows the card; **No** is the right
  answer and costs nothing.
- **Decided at the open, once.** A film paused in one place and carried on in another without leaving the player
  keeps the label it opened with. Leaving the player and coming back measures again.
- **A relay is not a network.** iCloud Private Relay, and Safari's "Hide IP address from trackers" if it treats the
  lookup as a tracker, send the lookup out through Akamai, Cloudflare or Fastly, so the operator named is theirs
  wherever the viewer is. An operator whose name contains one of those three is recorded with the viewing but
  never remembered: the question is asked as if there were no operator.
- **One answer covers everywhere an operator is used.** A phone's own data connection, or a laptop tethered to it,
  names the mobile operator, which is the same on a train and in a hotel; a VPN or a Tailscale exit node names
  itself. The record keeps the operator and the latency with every viewing, and Admin can forget an answer so the
  question is asked again.
- **The lookup** runs in a hidden `sandbox="allow-scripts"` frame built from `srcdoc`. Its one request
  (`https://ipinfo.io/json`, `credentials: 'omit'`, `referrerPolicy: 'no-referrer'`) was seen by an echo service
  to carry `Origin: null`, no `Referer` and no cookie. The frame posts `org` to the page and nothing else. The page
  accepts a message only from that frame's own window, keeps a trimmed string of 1–100 characters, and removes the
  frame when it answers, after 15 s, or when the player is left. A `srcdoc` frame inherits the page's
  Content-Security-Policy: CineFilm sends none today, and one added later must allow this frame's inline script and
  its request, or every lookup ends as "can't tell which network this is".

**The card** (`EnvironmentCard` in `src/components/PlayerOverlays.jsx`). The film starts behind it. It does not
take focus: Space pauses the film, and a focused button would take Space as an answer and remember it. It is a
`role="group"` labelled by its question and described by its text, placed before the player's controls in the
document so Tab reaches it first. Because it takes no focus, the player keeps one polite live region mounted for
its whole life (empty until there is something to say) and puts the question and its text there while the card
shows: a live region inserted already filled is often not spoken. Its three buttons are plain buttons: none is the
amber primary, because none is the answer CineFilm wants. Every way of closing it (the three buttons, Escape)
returns focus to the player. Escape closes an open menu first, then the card if it is showing; what Escape does in
fullscreen is the browser's. The card uses the slow-connection card's style and place.

**One card at a time.** The question does not show over the end card or the "can't be played" card, hides while a
menu is open (on a phone the card would cover it), and waits while the slow-connection card is open. The
slow-connection card waits while the question is due (showing, or waiting its turn), and does not count as shown
while it waits. The indicator pill keeps its own rule (measured speed) until the strategy step. This is handover
decision 17.

**Admin** lists what this browser remembers under the playbacks, newest first, each with **Forget** (see the Admin
card below).

## API

All behind the session cookie; the `PUT` needs `X-CineFilm`.

| Route | Contract |
|---|---|
| `PUT /api/playback-health/{playbackId}` body: the report | 204. A new id creates the record (404 `No such edition` when the edition does not exist). A known id replaces the report when `seq` is higher and is otherwise left alone (still 204). 400 `Invalid health report` for: an id that is not a lower-case UUID, a body over 20,000 characters or not JSON, a report outside the rules, a known id with another `editionId` |
| `GET /api/admin/playback-health` | `{ playbacks: [{ playbackId, filmId, filmTitle, editionId, startedAt, updatedAt, report }] }`: the 20 with the newest `updatedAt`, newest first; `report` is the stored report **without** `minutes` |
| `GET /api/admin/playback-health/{playbackId}` | one such row with the whole report; 404 `No such playback` (also for an id that is not a UUID) |

**Rules for a report:** every field above present; `seq` and `editionId` at least 1; every number from 0 to
2,000,000,000; `environment` one of the four; `engine` one of the three or null; `quality` one of
`Ladder.QUALITY_IDS`; `operator` at most 100 characters; `client.userAgent` at most 300; `client.screen` matching
`^\d{1,5}x\d{1,5}$`; `rungMs` keys only rung ids (`Ladder.QUALITY_IDS` without `auto`) with non-null values;
`minutes` at most 360 entries of exactly five non-null numbers, the rung code from 0 to 6; nullable only `operator`,
`latencyMs`, `engine`, `startupMs`, the three `kbps` figures and `client.cores`; and the stored serialisation at
most 16,000 characters. Unknown fields are ignored. A field added in a later release is nullable on the server.

The body is read as text and its length checked before it is parsed, so a malformed body on this route answers
`Invalid health report` rather than the usual `Invalid request`.

## Storage (V11, expand-only: one new table)

```sql
CREATE TABLE playback_health (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    playback_id VARCHAR(36) NOT NULL UNIQUE,
    edition_id BIGINT NOT NULL,
    film_id BIGINT NOT NULL,
    film_title VARCHAR(255) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    seq INTEGER NOT NULL,
    summary VARCHAR(16000) NOT NULL
);
CREATE INDEX playback_health_updated_at_idx ON playback_health (updated_at);
```

- **The report is one JSON column**, `VARCHAR` like `media_keyframes.cuts` so Hibernate validates on PostgreSQL and
  on H2. What is measured will change as we learn what matters; a new figure then needs no migration. The server
  validates the typed report, stores its own serialisation, and hands it back as parsed JSON. A row whose summary
  cannot be read is left out of the list (and is 404 by id), never a 500.
- **No foreign key to `edition`.** Regrouping deletes editions with a bulk delete that assumes nothing else refers
  to them (`EditionRepository.deleteByIds`); a foreign key would break that, and a cascade would delete the record of
  a train ride because the library was regrouped. The film's id and title are copied at the first report.
- `started_at` is the server's clock minus `elapsedMs` at the first report; `updated_at` is the clock at each
  accepted report. Time only through the injected `Clock`.
- **Replacing is one statement:** `update … where playback_id = ? and seq < ?`. Two sends can overlap (leaving the
  page fires `visibilitychange` and `pagehide` together), and read-compare-save would let the older one land last.
- **Two first reports can race** (closing the tab before the first frame sends two). The loser's insert fails on
  the unique id and its transaction rolls back; the controller then asks the service once more, which now finds the
  row and takes the update path, so the higher `seq` wins. A second failure propagates, so a different integrity
  failure is never swallowed.
- **The body is read with a hard stop** at 20,001 characters (`BoundedText`), so an oversized body is never held in
  memory; the service checks the length again.
- **Bounded:** after a record is created, the oldest by `updated_at` are deleted until 2,000 remain, as that
  transaction sees the table. Records created at the same moment do not see each other's rows, so they can leave
  the table over by their number until the next creation trims it again. It is a bound on growth, not an exact count.

## Code

**PR 1, backend**, new package `gregochr.cinefilm.health`: `PlaybackHealth` (entity, Lombok as `PlaybackState`),
`PlaybackHealthRepository`, `HealthDtos` (the report and client records with boxed fields, the Admin shapes),
`HealthRules` (pure), `PlaybackHealthService`, `PlaybackHealthController`, `V11__playback_health.sql`.

**PR 1, frontend:** `src/components/PlaybackHealthPanel.jsx`, `src/state/usePlaybackHealth.js`,
`src/state/healthFormat.js`, `adminApi.playbackHealth` and `adminApi.playbackHealthUrl` in `src/api/resources.js`,
the fixture `admin-playback-health.json`, and the new routes in `src/test/mockApi.jsx` and
`src/api/fixtureServer.js`.

**PR 2:** `src/player/health.js` (the counter: pure, clock injected), `src/player/useHealthReport.js` (the id and
the sends), `healthApi.report`, and the wiring in `src/screens/Player.jsx`, `src/player/useStream.js`,
`hlsjsEngine.js` and `streamSource.js`.

**PR 3:** `src/player/latency.js`, `src/player/operator.js` (the frame), `src/player/environment.js` (pure:
`HOME_LATENCY_MS`, `needsLookup`, `classify`, `isRelay`, the card's copy), `src/state/networks.js` (the remembered
answers; in `src/state` because Admin reads them too), `src/player/useEnvironment.js`, `EnvironmentCard` in
`src/components/PlayerOverlays.jsx`, `keymap.js` (Escape), the wiring in `Player.jsx` (the slow card's condition
and the announcement included), the remembered networks in `PlaybackHealthPanel.jsx`, and with `VITE_FIXTURES=1`
a fixed latency of 48 ms and operator `AS64496 Example Rail Wi-Fi` so the card can be seen without a backend.
`operatorName` and `environmentLabel` have been in `src/state/healthFormat.js` since PR 1.

### Rules inside the player (PR 2 and PR 3)

The recorder runs inside the code that plays the film, so:

1. **It can never break playback.** Every method of the object handed to the player and to `useStream` catches its
   own errors and does nothing. Effects in the new hooks do the same.
2. In an existing handler the existing statements run first and the recorder call comes last. Where an early
   return would skip it (the hls.js error listener) it is called first, which rule 1 makes safe.
3. `useStream`'s effect reaches the recorder only through its `latest` ref. Its dependency array does not change.
   New engine handlers are optional (`handlers.onNetworkError?.()`).
4. The counter lives in a ref. Recording never sets React state, so no event causes a render.
5. Each new hook has one effect per concern, keyed on what it belongs to (the recorder's on the viewing, the
   environment's on the player being open); it keeps every timer id, clears them all on cleanup, aborts what is in
   flight and sets a closed flag its callbacks check. Nothing is guarded by "ran once": under StrictMode an effect
   is mounted, cleaned up and mounted again, and must restart.
6. A send can outlive the test that started it (the suite unstubs `fetch` before the player unmounts), so every
   rejection and every synchronous throw is swallowed.

**In tests the detection is inert by design:** jsdom has no Resource Timing entry for a stubbed `fetch`, so the
measurement sends one request, finds no entry and stops. Latency is null, the environment is `unknown`, no state
is set, and no lookup, frame or card appears in the existing player tests. The tests of the environment itself
stand in for the browser at its boundary: a `PerformanceObserver` that hands over an entry, and a message from the
lookup frame's own window.

## Admin: the "Playback health" card

After the Transcoding card. Loaded once when Admin opens (no polling).

- Loading: a skeleton block. Failure: "Couldn't load playback health." with Try again. None: "No playbacks recorded
  yet."
- **Recent playbacks** (list, `aria-label="Recent playbacks"`). Each item:
  - the film title and a tag: `Home`, `Train`, `Away` or `Unknown`;
  - `10 Nov 19:43 · 1h 12m watched · Mac`: the start as `formatScanTime`; watched as `formatRuntime`, or
    `under 1m` below a minute; the device is the first of `iPhone`, `iPad`, `Android`, `Windows`, `Mac`, `Linux`
    found in the user agent, else `Unknown device`;
  - `Start 2.4 s · 2 stalls, 9 s · 1 reconnect`: one decimal for the start; `Not played yet` with no start and
    autoplay refused; `Did not start` with no start otherwise; `No stalls`, `1 stall, 6 s`, `3 stalls, 2m 5s`
    (whole seconds under a minute, minutes and seconds from there); the reconnects part only when there are some;
    `· fell back to direct play` when `fellBack`;
  - `480p 34% · 360p 66% · 0.4 to 2.4 Mbit/s, typically 0.9`: rungs in ladder order holding at least 1% of the
    watched time, named by `rungName`; `no speed samples` when there are none; the line left out when nothing was
    watched;
  - `Virgin Media Limited · 48 ms` when an operator or a latency is known (either part alone when only one is);
  - a **Full record** link to `/api/admin/playback-health/{playbackId}` in a new tab: the whole report as JSON,
    which is what to copy when a journey needs looking at.
- **Remembered networks on this device** (PR 3; a heading and a list, only when there are some, shown whatever the
  playbacks request did; the newest answer first). Each item: the whole `org` (`AS5089 Virgin Media Limited`: two
  networks can share a display name) with a `Train` or `Away` tag, and **Forget**
  (`aria-label="Forget AS5089 Virgin Media Limited"`). Forgetting removes the button that had focus, so focus
  moves to the card's heading.

## Security (`SECURITY.md`)

- The new routes are under `/api`, behind the session, and the `PUT` needs `X-CineFilm`; `RouteSecurityDriftTest`
  finds them by itself.
- The report is client-supplied: its length is checked before parsing, it is bounded field by field and in total,
  stored as the server's own serialisation, returned as JSON and rendered as text. Operator names and user agents
  are untrusted strings.
- PR 3 adds the browser's only request to a service other than CineFilm and TMDB's images, and adds it to
  `SECURITY.md` rule 7. It runs only when the player opens away from home, from the sandboxed frame described
  under "The environment", and only the operator's name comes back from it.
- The table is bounded. Nothing here reads a file or runs a process.

## Tests

- **Backend** (`test-standards.md`): `HealthRulesTest` (the example passes; then one rule at a time at its
  boundary: every required field null, every nullable field null, each number at -1, 0, 2,000,000,000 and
  2,000,000,001, each enum-like field outside its list, 100 and 101, 300 and 301 characters, the screen pattern,
  `rungMs` keys and a null value, 360 and 361 minutes, four, five and six numbers, a null inside a minute, the rung
  code at 6 and 7); `PlaybackHealthServiceTest` on `TestData.CLOCK`; the controller slice (401, missing header,
  exact error bodies, 20,000 and 20,001 characters, the race both ways); `PlaybackHealthApiTest` on H2 end to end
  with the report above, including `seq` higher, equal and lower through the real update, the 2,000 cap at its edge
  and the list without `minutes`; `PlaybackHealthIntegrationTest` on PostgreSQL (CI): the migration, a
  16,000-character summary, and the unique id failing and the lookup afterwards working in a new transaction.
- **Frontend:** `health.js` event by event with exact values (a stall across a minute boundary, a seek inside a
  stall, a source change with a seek open, a refused autoplay, the heartbeat limit at 1,500 and 1,501 ms, a step of
  150,000 and 150,001 ms, percentiles at 1, 2 and 10 samples, the 360-entry cap, the normalising); the hooks with
  `renderHook`; `classify` at 19, 20 and 21 ms and for each row of the decision; the latency measurement with a
  stand-in observer (the smallest of three, the entry arriving late, each pause at its boundary, each place an
  abort can land); the lookup frame (its attributes, a message from another window, the timeout, the frame gone
  after every ending); the remembered networks (the cap of 50, every kind of bad stored value, storage that
  throws); the cards by role and exact copy, with `toHaveFocus()` on the player after each way of closing the
  question; the player screen for the wiring (Tab reaches the card before the controls, Space still pauses, one
  card at a time).
- **Mutation:** new logic lives in pure modules with thin hooks, because Stryker mutates all of `src/player`,
  `src/api` and `src/state` against a floor of 95 (95.56 measured) and PIT the whole backend against 98. Each PR
  runs the mutation tool once, when its build is finished, on the files it adds, and reports the score: not after
  each review fix, and never while a gate is running on the same machine (a run pins the processor for ten
  minutes or more, and the suite's 30 ms speed tests then fail).
- **Seen in a browser before each commit** (`dev-and-testing.md`, "UI work"): the Admin card and the question card
  under `VITE_FIXTURES=1`.
- **By hand, by the owner after release:** on Safari on the Mac and on the iPhone a viewing away from home shows a
  latency and an operator in Admin (the two things not provable here), the operator named is the network's own and
  not Akamai, Cloudflare or Fastly (which would mean Safari is hiding the address from the lookup), and a viewing
  at home shows `Home` with a latency well under 20 ms.

## What the adversarial review changed

- A source reload was invisible to the counter and would have been counted as watching, stalls and viewer seeks:
  the `emptied` input, the reload figures, and viewer seeks counted where the viewer asks for them.
- Nothing isolated the recorder from playback: the rules above.
- Per-minute latency from the report's own request was neither reliable nor latency: cut.
- Answering the card left the keyboard dead: focus returns to the player.
- The lookup would have sent the app's hostname: the sandboxed frame.
- Replace-if-newer and the first-report race on the server: the conditional update and the narrow catch.
- Cut: the server-side network table and its routes, the 180-day prune, frame counts, the client address, the
  device name beyond a platform word. Split: one pull request became three.

## What building changed (2026-11-11)

Found by the local Codex reviews of PR 2, by a second adversarial review before PR 3 was built, and by checking
the pieces in a real browser:

- **The recorder (PR 2).** A viewing's clock is taken again when the address changes to another film or edition
  in the same player. The wait of a viewer's seek is never watched time. Start-up is counted like everything else,
  so a sleep before the first frame is not in it. `recoveries` is counted when a rebuild begins.
- **Latency is read through an observer,** because the page's timing buffer is usually full by the time a film is
  opened; and a dropout as the player opens is retried for about a minute and a half before giving up.
- **The lookup is given 15 s, not 4.** It needs several round trips on the links where its answer matters most.
- **An answer is stored only against a named network.** The first design kept an answer with no operator for the
  tab, which would have handed a hotel's answer to the next train whose lookup failed. A relay's name
  (Akamai, Cloudflare, Fastly) is treated the same way.
- **The card:** it hides while a menu is open, its three buttons are equals (no amber), and the question is spoken
  through a live region the player keeps mounted, because a region inserted already filled often is not.
- **Admin** shows the whole `org`, and moves focus to the card's heading after Forget.
- The remembered answers live in `src/state/networks.js`, not `src/player`.

## Later steps (not this plan)

2. The four small fixes in `network-resilience-review.md` (A1–A4) and item 8.
3. A streaming strategy per environment, set from what the record shows: on a train start low, bank deep and step
   up only with a reserve; at home start at the top. The indicator pill then takes its Home from the environment.
4. Early warning (does latency climb before a dropout? needs its own latency samples during play) and, only if a
   gap ever outlasts the bank, route knowledge.
