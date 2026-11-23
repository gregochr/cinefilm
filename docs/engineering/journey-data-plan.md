# Journey data: a "how" flag on viewings, and the train's feed in the database

Written 2026-11-23, on the first journey with both the train feed logger and the train viewer running. The owner
asked for a flag that tells a headless run from a film somebody watched; for the train Wi-Fi data in the database
for journey profiling, linked to the film's record through a table of its own, in Boyce–Codd normal form; for the
device's kind and state with it, because a new laptop is planned for the new year and the present one is the
baseline; and for the class travelled. A fresh adversarial read of this plan found thirteen faults; they are settled
below.

## What is true today

- A headless run's playback record is stored like any other (`playback_health`; the viewer lets
  `PUT /api/playback-health/<id>` through). Nothing marks it. Only `client.userAgent` inside `summary` says
  `HeadlessChrome`, and every figure in Admin counts it as a viewing.
- The train's feed is on the laptop only: `~/train-feed-logger/logs/train-feed-<date>-<time>.jsonl`, one line per
  30 seconds. Each round holds the position, each modem's state and signal, the passengers online, the laptop's own
  allowance and the train unit, and also the laptop's MAC and network addresses.
- The logger reads the laptop's own Wi-Fi link once, in its first round, and nothing else about the laptop.
- `SECURITY.md` rule 7 says no location is requested or stored by the browser or the server.

## Decisions

1. **The flag is a column the server sets.** `playback_health.source`, `person` or `robot`, `NOT NULL DEFAULT
   'person'`. It is worked out beside the other stored figures (`HealthFigures`) from the report's
   `client.userAgent` (`HeadlessChrome` is a robot) and stored on create and in `replaceIfNewerWith`; the client
   sends nothing new and the viewer is not changed. The migration marks the rows already there with
   `summary LIKE '%"userAgent":"%HeadlessChrome/%'`: the summary is the report as the server serialised it, a quote
   inside any text is escaped there, and no free text follows `client`, so the pattern can only match the user
   agent (a test holds a row whose operator says `HeadlessChrome`).
2. **The chart and the counts leave robots out unless asked; the log shows them, marked.** The chart's sessions and
   the list's rows carry `source`. The chart filters it in the page, as it does the place: a third control
   (Watched, Headless, Both; Watched to begin with), so its tiles and its summary count people unless asked, and
   say so when they do not. The summary's two counts (`playbackCounts()`: films, smooth) are of people only. The
   log keeps its three filters as they are ("All" is still every play) and gains a fourth, "Headless runs", which
   sends the list's new parameter `source=robot`; a robot's row says "Headless run" where a person's names the
   device. "Playing now" is not changed: a headless run is playing, and shows. The joiner is not changed: it never
   joins across user agents, so every part of a viewing has the same source.
3. **Six tables, each fact where its key is.**

   | Table | Key | Columns |
   |---|---|---|
   | `device` | `id`; the five columns together are unique and none is null | `model`, `cpu`, `cores`, `threads`, `memory_bytes` |
   | `journey` | `id`; `run_started_ms`, `segment` are unique | `device_id` (null when the file gave no whole device), `os_version`, `travel_class`, `train_unit`, `source_file`, `logger_version`, `imported_at` |
   | `journey_sample` | `journey_id`, `round_no` | `sampled_at`; the train: `feed_at`, `fix_mode`, `fix_age_s`, `latitude`, `longitude`, `altitude_m`, `speed_mps`, `course_deg`, `satellites`, `online`, `users_online`, `users_total`, `down_limit`, `up_limit`, `cap_level`, `user_class`, `data_down_used`, `data_up_used`, `probe_ms`, `probe_code`; the laptop: `power_source`, `battery_percent`, `low_power`, `cpu_limit`, `load_avg`, `swap_mb`; its Wi-Fi link: `wifi_rssi`, `wifi_noise`, `wifi_rate`, `wifi_phy`, `wifi_channel`, `wifi_band`, `wifi_width` |
   | `journey_modem` | `journey_id`, `modem_index` | `device_type`, `device_subtype` |
   | `journey_modem_sample` | `journey_id`, `round_no`, `modem_index` | `device_state`, `link_state`, `rssi`, `technology`, `operator_id`, `cell_id` |
   | `journey_playback` | `journey_id`, `playback_health_id` | none |

   - **A journey is one stretch of one logger run on one train unit.** `run_started_ms` is the `t` of the file's
     `start` line (milliseconds, kept as the whole number it is) and `segment` counts from 0, going up where the unit a round reports differs from
     the journey's. A round with no `system` part, or one that failed as a whole, stays in the journey it falls in
     and never starts one. Two logger runs on one trip are two journeys. When a journey began and ended is its first
     and last sample, and is not stored.
   - **A sample's key is its round number** (`round_no`), which the logger counts up within a run. `sampled_at`
     (from the round's `t`, to the millisecond) is a plain column: the laptop's clock can jump, so a time is not a
     key, and nothing depends on it. Every round of the file is a sample, the failed ones with nothing but the
     round and the time: the logger was there and nothing answered.
   - **The position is stored as the feed gives it, with its own time, mode and age** (`feed_at`, `fix_mode`,
     `fix_age_s`). In the file of 2026-11-23 a fifth of the rounds say mode 0, scattered one at a time, and their
     positions are still fresh and moving: whoever reads decides what a mode is worth; the import does not.
   - `journey_modem_sample` points at its sample (`journey_id`, `round_no`) and at its modem (`journey_id`,
     `modem_index`, the feed's own index: 102 to 105 that day); no column depends on part of its key.
   - The speed limit, the cap level and the user class stay on the sample: the feed can change them during a
     journey. A modem's model does not change, so it is on `journey_modem`.
   - **The feed's "no value".** Only where the feed uses the exact text `-1` for it (`rssi`, `technology`,
     `operator_id`, `cell_id`) is it stored as null; a longitude of -1 is a place near York. Limits are stored as
     the feed gives them: 625000 on 2026-11-23, which is bytes a second, 5 Mbit/s (no minute of the film played
     beside it had a median speed above 4.53 Mbit/s, and its eight best all lie between 4.48 and 4.53); 0 is no
     limit.
   - **A device is a kind of machine, not one machine:** model, chip, cores, threads and memory, with no serial
     number or other identifier. A file whose `device` lacks any of the five gives the journey no device. The
     operating system's version changes on one device, so it is on the journey; the browser's is already in each
     playback record. `cpu_limit` is a figure only Intel Macs give: on an Apple chip it is null, and the figures
     that will compare across the two laptops are the power, the battery, low-power mode, the load and the swap.
   - **The laptop's Wi-Fi link** is its own link to the carriage's access point, not the train's to the masts.
     `wifi_rssi` and `wifi_noise` are dBm and `wifi_rate` is the transmit rate in Mbit/s; `wifi_phy`, `wifi_band`
     and `wifi_width` are the Mac's own numbers for the standard, the band and the channel's width, stored as they
     come and put into words only when shown. The network's name and the access point's address are never read.
   - **The class travelled** is a fact of the journey that only the owner knows: `standard`, `first`, or null when
     the logger's question went unanswered. It is not the feed's `userclass`, the train's own word for the kind of
     user, which stays on the sample as `user_class`: the two together show whether first class is given another
     speed limit. A run that spans two trains gives both journeys the one answer.
   - **Deleting.** `journey_sample`, `journey_modem`, `journey_modem_sample` and `journey_playback` go with their
     journey (`ON DELETE CASCADE`). `journey_playback` also goes with its playback record (`ON DELETE CASCADE` on
     `playback_health.id`): the table of records is capped and the cap deletes the oldest in bulk, which a plain
     foreign key would refuse, and every new viewing after that would fail. A record the cap removes takes its
     link with it.
4. **The link is the fact, the alignment is computed.** `journey_playback` says a viewing happened on a journey; a
   journey may hold several records (a reload starts a new one) and a record may touch two journeys. Which sample
   lies beside which minute of the film is worked out from the two clocks whenever it is asked for, never stored.
   The import links every record whose time (from `started_at` to `updated_at`, two minutes allowed either side)
   overlaps the journey's and whose `environment` is not `home`. A record written after the import is not linked;
   deleting the journey and importing the file again links it.
5. **The file gets there through Admin, from the owner's own browser, in one request.** No script holds a
   password. The page reads the logger's file and turns it into the columns above: numbers as numbers, the feed's
   "no value" as null, nothing else kept (the MAC, the addresses, the cell tower's area code and the rest never
   leave the laptop). That code is plain JavaScript under `frontend/src/state` (no JSX). The server takes one file
   as one request and one transaction: at most 2,000 rounds and 4,000,000 characters, read with a bound; every field named, typed
   and ranged; **anything it does not know refused with a 400, not ignored**; a file already imported (the same
   `run_started_ms`) refused with a 409 until its journeys are deleted. A longer file is refused and said to be.
6. **The logger gains the laptop and the class (version 3 of its log).** `scripts/train-feed-logger.py` records
   the device once in its `start` line, the class the owner answers at the start, and the state in every round,
   from the system's own small tools (`sysctl`, `pmset`), each with a short limit, each failing to null, in a
   side thread of their own beside the internet check's. Version 2's one read of the Wi-Fi link stays as it is (it
   takes seconds and can make the Mac look for networks). A light read is added to every round: one fixed script,
   run by `osascript`, asks the Mac's own Wi-Fi library for the signal, the noise, the transmit rate, the standard
   and the channel, and for nothing that names the network or the access point. Tried on the owner's Mac on
   2026-11-23: a twentieth of a second, no scan, no prompt. Version 3 has to be in the owner's copy before
   Thursday 2026-11-26, so that the journeys on the present laptop are the baseline.
7. **`SECURITY.md` rule 7 changes with the tables:** the browser still never asks for a location; the server stores
   a journey's positions, and which train it was, only when the owner hands them over in Admin; they are then in the database, in its dumps
   (rule 9) and in Admin's answers, and nowhere else; the device's addresses never leave the laptop and the server
   refuses a file that carries a field it does not know; the raw files are never committed.
8. **Every fixture is made up.** The repository is public: no test, fixture or doc holds a real position, time of
   travel, cell id, train unit, headcode, timetable id or the owner's machine. Shapes are real; values are
   invented.
9. **Which train it was is the train's fact, not the logger's** (the owner, 2026-11-23: this week). The feed names
   the unit, not the service. The service's identity and its planned and actual times at each timing point are in
   the railway's open running data. They are read once for a journey, by hand, never on a schedule, and
   handed to Admin as a small file of their own: the server fetches nothing.

   | Table | Key | Columns |
   |---|---|---|
   | `train_run` | `id`; `timetable_uid`, `run_date` are unique | `headcode`, `trust_id`, `operator`, `source`, `imported_at` |
   | `train_run_call` | `train_run_id`, `seq` | `location`, `tiploc`, `planned_arrival`, `planned_departure`, `planned_pass`, `actual_arrival`, `actual_departure`, `actual_pass` |

   - `journey` gains a nullable `train_run_id`. Two journeys on one train point at one run; the run's times are
     stored once.
   - A run is the timetable's id for the service on one date. Where it began and ended is its first and last
     call, and is not stored. A call is one timing point in order (`seq` from 0): a train arrives and departs, or
     passes, so a call has the times of the one or of the other, each planned (the working timetable, to the half
     minute) and actual. Every time is an instant: whoever makes the file turns the day's clock times into
     instants, so the server needs no time zone and no rule for midnight.
   - Which sample lies beside which timing point is computed from the two clocks when asked for, never stored.
   - Giving a journey a train finds the run by its key or makes it, and replaces the run's own columns and calls
     with the file's: the newest file is the best account of that train. A run that no journey points at any more
     (the journey was given another, had it taken away, or was deleted) is deleted with its calls.
   - It is the owner's travel as much as a position is: decision 7 covers it.

## The API (all under the admin session; `POST` and `DELETE` need the `X-CineFilm` header)

- `GET /api/admin/playback-health`: a new optional parameter `source` (`person` or `robot`; without it both, as
  today; anything else is a 400); every row gains `source`. `/chart`: every session gains `source` (no parameter).
  `GET /api/admin/playback-health/{playbackId}`: the row gains `source`.
- `POST /api/admin/journeys` (one file; answers the journeys made and the records linked), `GET
  /api/admin/journeys`, `GET /api/admin/journeys/{id}` (the samples and modem samples as compact rows), `DELETE
  /api/admin/journeys/{id}`.
- `PUT /api/admin/journeys/{id}/train` (one train file; decision 9) and `DELETE /api/admin/journeys/{id}/train`.

## The train's file (`PUT /api/admin/journeys/{id}/train`)

One JSON object of at most 200,000 characters, read as strictly as the import's request: a name that is not
here, a value of the wrong kind or one outside its range is a 400, `Invalid train file`. Texts are printable ASCII.

| Field | Kind and range |
|---|---|
| `uid` | required; text, 1 to 10 |
| `date` | required; a date as `YYYY-MM-DD`, from 2020-01-01 to 2100-01-01 |
| `headcode` | text, 1 to 10 |
| `trustId` | text, 1 to 20 |
| `operator` | text, 1 to 100 |
| `source` | text, 1 to 200 (where the times were read) |
| `calls` | required; 1 to 300 objects, in the order the train meets them |

A call: `location` (required; text, 1 to 100), `tiploc` (text, 1 to 10), and six times, each whole seconds from
2020-01-01 to 2100-01-01: `plannedArrival`, `plannedDeparture`, `plannedPass`, `actualArrival`,
`actualDeparture`, `actualPass`. A call has at least one time, and never a pass together with an arrival or a
departure of the same kind (planned with planned, actual with actual).

The server answers 404 for a journey it does not know, and otherwise 200 with `{ "trainRunId", "calls" }` (the
count). `DELETE /api/admin/journeys/{id}/train` takes the journey's train away (204; 404 for an unknown journey).

## The import's request (`POST /api/admin/journeys`)

One JSON object of at most 4,000,000 characters, made by the Admin page from one logger file. The names below are
the whole vocabulary. A name that is not here, at any depth, is a 400; so is a value of the wrong kind (a number
written as text, a decimal where a whole number belongs, a boolean for a number) or outside its range. `null` and
a missing name mean the same for every field that is not marked required. Texts are printable ASCII.

| Field | Kind and range |
|---|---|
| `runStartedMs` | required; whole; milliseconds from 2020-01-01 to 2100-01-01 |
| `loggerVersion` | required; 2 or 3 |
| `sourceFile` | text, 1 to 200 |
| `osVersion` | text, 1 to 40 |
| `travelClass` | `standard` or `first` |
| `device` | an object with all five of `model` and `cpu` (text, 1 to 100), `cores` (1 to 1,024), `threads` (1 to 4,096), `memoryBytes` (1 to 2^50) |
| `samples` | required; 1 to 2,000 objects, each `round` higher than the one before |

A sample:

| Field | Kind and range |
|---|---|
| `round` | required; whole, 1 to 10,000,000 |
| `at` | required; whole; milliseconds, the range of `runStartedMs` |
| `trainUnit` | text, 1 to 100 |
| `feedAt` | whole; seconds from 2020-01-01 to 2100-01-01 |
| `fixMode`, `fixAgeS` | whole; 0 to 9, and 0 to 1,000,000 |
| `latitude`, `longitude`, `altitudeM`, `speedMps`, `courseDeg` | decimal; -90 to 90, -180 to 180, -1,000 to 10,000, 0 to 200, 0 to 360 |
| `satellites`, `online`, `usersOnline`, `usersTotal` | whole; 0 to 200, 0 to 9, 0 to 100,000 for both counts |
| `downLimit`, `upLimit`, `dataDownUsed`, `dataUpUsed` | whole; 0 to 10^12 for the limits, 0 to 10^15 for what was used |
| `capLevel`, `userClass` | whole; 0 to 1,000 |
| `probeMs`, `probeCode` | whole; 0 to 600,000, and 100 to 599 |
| `powerSource` | `ac` or `battery` |
| `batteryPercent`, `lowPower`, `cpuLimit` | whole; 0 to 100, 0 or 1, 0 to 100 |
| `loadAvg`, `swapMb` | decimal; 0 to 100,000, and 0 to 10,000,000 |
| `wifiRssi`, `wifiNoise` | whole; -120 to -1 |
| `wifiRate` | decimal; above 0, at most 100,000 |
| `wifiPhy`, `wifiBand`, `wifiWidth`, `wifiChannel` | whole; 1 to 20 for the first three, 1 to 233 for the channel |
| `modems` | 0 to 16 objects, each `index` once in the sample |

A modem of a sample: `index` (required; whole, 0 to 100,000), `deviceType`, `deviceSubtype`, `deviceState`,
`linkState` and `technology` (text, 1 to 40), `rssi` (whole, -200 to 0), `operatorId` (text of 1 to 10 digits),
`cellId` (whole, 0 to 2^40).

What the server does with it, in one transaction:

- A run already stored (`runStartedMs`) is a 409, `Already imported`. Every other refusal is a 400 with the one
  message `Invalid journey file`, and nothing is stored.
- It walks the samples in order and makes the journeys: segment 0 begins at the first sample; a sample whose
  `trainUnit` is given and differs from its journey's begins the next segment; a journey whose unit is not yet
  known takes the first one given.
- A journey's modems are the indexes its samples name, each with the type and subtype of the first sample that
  names it; a later sample that gives the same index another type or subtype refuses the file.
- The device is found by its five columns or made. Each journey is linked as decision 4 says.
- It answers 201 with `{ "journeys": [ { "id", "segment", "trainUnit", "samples", "from", "to", "linked" } ] }`:
  the count of samples, the first and last `sampled_at`, and the count of playback records linked.

The page writes the feed's `-1` as `null` in `rssi`, `technology`, `operatorId` and `cellId`, and leaves out
everything that has no name here.

## Pull requests, each under 1,500 changed lines

Each one owns its own contract rows in `foundation-plan.md`, its HTTP contract tests, its entries in
`RouteSecurityDriftTest`, its made-up fixtures and its changelog entry.

1. **The flag** (backend and Admin). The migration with its backfill; the rule beside the other figures with its
   unit test; the MockMvc cases (the parameter, a wrong value, the 401); the counts; the chart's control and the
   log's filter in Admin. About 900 lines. Carries this plan.
2. **The logger's version 3** (`scripts/` only): the device, the class, the state and the Wi-Fi link, its tests,
   its manual and `SECURITY.md`'s paragraph about the logger. About 600 lines. Independent of the first.
3. **3a, the tables** (backend): the migration, the entities and repositories, the cascades, with repository tests
   and an integration test (CI only) in which the cap's own delete removes a linked record and the next viewing
   still stores. About 1,000 lines. It can be built beside the flag (it shares no file with it) and is pushed
   after the flag has merged, because its migration is numbered after the flag's.
4. **3b, the import** (backend): `POST`, the bounds and refusals, the segments, the device, the linking; MockMvc and
   integration tests that read back through the repositories. `SECURITY.md` rule 7. About 1,200 lines.
5. **The train's run** (backend, decision 9): the migration (two tables and the journey's column), the two calls
   that give a journey its train and take it away, their rules and tests. About 800 lines. After 3b.
6. **Reading and deleting** (backend): the three other calls; a journey's answer carries its train's run and
   calls, and deleting a journey removes a run no journey points at any more. About 700 lines.
7. **Admin's Journeys page**: choose a file, see what will be sent, import, add the train's file, list, delete; the reading and trimming
   of the file under `frontend/src/state`; a short manual, `docs/engineering/journeys.md`. About 1,100 lines.
8. **Later, with two journeys in hand:** the journey laid out in time, each modem's signal and the passengers
   online with the film's stalled seconds beside them. Not designed here.

The first two are independent of each other and of the rest, and both are wanted before Thursday's journey
(2026-11-26). The owner's time for the build is 2026-11-23 in the office and the two evenings after it; three to
seven follow in that order as far as that time goes.

## Checked against today's file

Every column of the train's comes from a round of the owner's file of 2026-11-23 (logger version 2): `position`
(`time`, `latitude`, `longitude`, `altitude`, `speed`, `cmg`, `satellites`, `mode`, `age`), `connectivity` (`online`, four
`links` with `index`, `device_type`, `device_subtype`, `device_state`, `link_state`, `rssi`, `technology`,
`operator_id`, `umts_info.cellid`), `users` (`total`, `online`), `user` (the limits, what has been used,
`cap_level`, `userclass`), `system` (`system_name`) and `probe`. Every value in `feed` is text as the train sends
it. Speed is metres a second. Not stored: the answers' timings, `apninfo`, `lac`, the bundle's id and address.

Read again when the film had ended (314 rounds, every one answered, one train unit): a modem's states are words
(`up` or `down`, `available` or `disconnected`), its technology is `lte` or `-1`, its operator a number or `-1`,
and the feed's index for the four is 102 to 105; `online`, the user class and the cap level never changed; the
internet check gave a code (204) or, five times, none. The largest round is under 4,000 bytes as the logger wrote
it. The whole run, stopped by hand at the end of the journey, is 363 rounds over three hours with none missing and
every one answered, 822 KB before the browser trims it, and it ends with a `stop` line after the last round (a run
that dies has none: the page must take a file with or without it).

## Questions for the owner

1. The words in Admin for the flag: built as "Watched", "Headless" and "Both" on the chart and "Headless runs" in
   the log, unless the owner says otherwise.
2. Robots left out of the chart and the counts unless asked for (decision 2): built so, unless the owner says
   otherwise.
