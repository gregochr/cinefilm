# The train feed logger

Written 2026-11-15. A measuring script the owner runs by hand on the laptop during a train journey:
`scripts/train-feed-logger.py`. It is not part of the app or its image, and nothing in CineFilm depends on it.

## Why

The first train record (`train-link-plan.md`) was laid over the train's actual running times, and every bad spell
fell in open country between towns: the Wi-Fi rides on mobile signal, so where the train is decides what the link
delivers. Before any rule is built on that ("Not in this phase" in the same plan), a journey has to show three
things the playback record cannot:

- whether LNER's trains still publish the onboard Wi-Fi system's own feed, last reported by a passenger in 2016;
- what it holds: the train's position, the signal on each of its mobile modems, how many passengers are online;
- whether the modems' signal falls before the film stalls, which is the warning a smarter buffer would need.

## What it does

Every 30 seconds (`--interval` changes it) it asks, with `curl`:

| Asked | Recorded |
|---|---|
| `https://www.ombord.info/api/jsonp/` and the same over `http` (the `https` one is used when both answer): the parts `position`, `connectivity`, `users`, `user` and `system`, and once the feed's own index | each answer as data, how long it took, the address that answered and whether its certificate was trusted |
| `https://www.gstatic.com/generate_204` | how long one tiny request to the internet took and the status it got, or why it failed. The printed line notes any status but 204: a sign-in page answers 200 there. Its body is not kept, and is cut off past 64 KB. |
| only when no film is playing: the first 256 KB of CineFilm's own page code, and never more (a server that ignores the range and sends more is stopped there and reported as such) | bytes received, the time, and the speed over the transfer |

`www.ombord.info` is a name that resolves only on the train, so anywhere else the feed is reported as not answering
and the other checks still run. The first good answer of each part also keeps its response headers and whether the
feed used the function name it was given: what the player would need to know to read the feed itself one day. The
first round reads the laptop's own Wi-Fi link once (signal, noise, rate) with `system_profiler`; reading it can make
the Mac look for other networks, so it is not repeated. A light read of the same link is made every round (see "The
laptop" below).

## The laptop

The owner plans a new laptop and wants the journeys on the present one as a baseline, so each log says which kind of
machine made it and what state it was in. The log is **version 3**.

- **`device`, in the `start` line, read once** from one `sysctl` call: `model`, `cpu`, `cores`, `threads` and
  `memory_bytes`. Texts are printable ASCII of at most 100 characters and numbers whole and positive, or null. It is
  null when the call fails or is not there (Linux). No serial number, hardware id or address. With `--no-laptop` the
  key is left out of the line (null means it could not be read).
- **`laptop`, in every round**, read in one more side thread, started and waited for like the internet check's, so it
  never holds the feed's questions up. If it has not ended when the round stops waiting for it (14 seconds), or when
  every field is null (not a Mac), the round has no `laptop` key. Every field is null when it cannot be read:
  - `power`: `ac` or `battery`, from the first line of `pmset -g batt`; `battery`: the percentage (0 to 100) and
    `charge`: the word after it (`charged`, `charging`, `discharging`, or another word of at most 20 letters), from its
    second line. The battery's own `id=` number is never taken.
  - `low_power`: 0 or 1, from the `lowpowermode` line of `pmset -g`.
  - `cpu_limit`: the `CPU_Speed_Limit` of `pmset -g therm`, 0 to 100. **Only Intel Macs give this figure**, so it will
    not compare with a Mac that has an Apple chip (null there).
  - `load`: the first of the three numbers of `sysctl -n vm.loadavg`; `swap_mb`: the `used` figure of
    `sysctl -n vm.swapusage`, in megabytes.
  - `wifi_rssi` and `wifi_noise` (dBm, -120 to -1), `wifi_rate` (Mbit/s, one decimal), `wifi_phy`, `wifi_band`,
    `wifi_width` (CoreWLAN's own numbers, 1 to 20) and `wifi_channel` (1 to 233), from one `osascript -l JavaScript`
    call running a fixed script. They are null unless the Wi-Fi is on and the value is a number in range. CoreWLAN's
    numbers are logged as they come: standard 1 is a, 2 b, 3 g, 4 n, 5 ac, 6 ax; band 1 is 2.4 GHz, 2 is 5 GHz, 3 is
    6 GHz; width 1 is 20 MHz, 2 is 40, 3 is 80, 4 is 160. **They describe the laptop's link to the carriage's access
    point, not the train's link to the masts.** The script asks for no network name, access point address, hardware
    address or country code and never starts a scan.
- Each tool is called by its absolute path, with no shell, its output limited to 20,000 bytes and its time to 2
  seconds (at most six calls one after another, twelve seconds at the worst). A tool that fails, is missing, times out
  or says something unexpected gives null for its fields and nothing else. Nothing a tool prints is logged as it
  stands: only the parsed fields.
- `--no-laptop` leaves `device` and `laptop` out. `--no-wifi` now leaves out both Wi-Fi reads: the `system_profiler`
  one of round 1 and the seven `wifi_` fields (the tool is not called; they are null).
- The status line on the terminal does not change.

## The class travelled

After the question about the film the script asks "Standard or first class? [S/f] ", because first class may be given
another speed limit by the train. An answer starting with f or F is `first`; any other answer, Return included, is
`standard`; with no answer in time, or no terminal, it is null: nobody said. The two questions share the one
minute of allowance, so they never hold logging up. `--class standard|first` answers it without asking. The `start`
line holds `travel_class` and `travel_class_from` (`answer`, `option` or null).

Each request names a function for the answer (`callback=cb`) and carries a time stamp, so nothing on the way can
answer from a cache. An answer over 50,000 bytes is refused, and one nested more than 32 levels deep is not taken
as data. The script needs curl 8.4.0 or newer, which is what stops an answer that has no length at that limit; with
an older curl it refuses to start.

Each round is one line of JSON in `~/train-feed-logger/logs/train-feed-<date>-<time>.jsonl`, written at once. The
`logs` folder, when the script is the one to create it, and every log are readable by the owner alone. **The log
holds where the train went**, so it is kept outside any checkout and is never committed (`SECURITY.md`).

The speed sample would compete with a film, so the script asks at the start whether one will be playing and takes
the sample only when the answer is no. If nobody answers within a minute it assumes a film and starts. Run without
a terminal it takes none. `--film` and `--no-film` answer the question in advance; `--speed-site` samples from
another site. When a release changes the script's name, it reads the page again.

## What the log holds

The first line is a `start` event (the addresses and parts, the internet check's address, the interval, the timeout
and speed site in force, the versions, `travel_class` and `travel_class_from`, and `device`) and the last a `stop` event with the number of rounds. Each round
between them holds:

| Key | What it is |
|---|---|
| `t`, `at`, `round` | the time (milliseconds since 1970, and as UTC text) and the round's number |
| `asked`, `bases` | the feed addresses asked this round, and each address's state: `ok`, why not, or null when never asked |
| `bases_text` | what an address said when it answered with something that is not the feed (a sign-in page, say) |
| `base`, `ip`, `tls` | when the feed answers: the address in use, the machine that answered, and `untrusted` when its certificate was refused and it was read anyway |
| `feed`, `ms` | each part's answer as data, and how long each took |
| `errs`, `skipped` | why a part failed (with `<part>_text` when it answered something that is not data), and the parts not asked because they keep failing |
| `first` | once for each part: its response headers, its content type and whether the feed used the function name it was given |
| `index` | once for each address: the feed's own index, or why it could not be read |
| `probe` | the internet check: `ms` and the status `code`, or `ms` null and `why` |
| `wifi` | first round only, on a Mac: the laptop's own Wi-Fi link |
| `laptop` | the laptop's power, battery, low power mode, CPU limit, load, swap and the seven `wifi_` fields of its link (above); absent when nothing could be read |
| `speed`, `speed_from` | the speed sample: `bytes`, `ms`, `first_byte_ms` and `kbps` (null when too fast to measure or nothing arrived), with `why` when it failed (alone, when the page could not be read or names no script); and the script's address, in the round it is found and again whenever a 4xx answer makes it be looked up again |

A part that fails three rounds running is then asked only every tenth round (`--retry-every`), and so is every
address but the one in use. A certificate that was refused is tried again at each of those full checks, so
`untrusted` is never carried from memory for longer than that. A round that fails as a whole is a line with only
`t`, `round` and `error`.

## Running it

Once, to put a copy where a double-click can start it:

    mkdir -p ~/train-feed-logger
    cp scripts/train-feed-logger.py ~/train-feed-logger/train-feed-logger.command

On the train: double-click that file (or run it in Terminal), answer the question, leave the window open, and close
it when getting off. `--help` lists the options; `--rounds 2` makes a short trial.

## What nobody has verified

- **A real train.** The script has only met the stand-in feed in `scripts/test/`, whose `position`, `connectivity`
  and `users` answers follow what has been published about the feed; `user` and `system` are invented.
- **That LNER's current trains publish the feed at all**, and over `https` with a certificate a browser accepts.
- **Starting it by double-click.**
- **A Mac with an Apple chip.** The laptop reads were tried on one Intel Mac on macOS 15 (the Wi-Fi read of every round
  took a twentieth of a second, with no scan and no prompt). An Apple-chip Mac prints a different `pmset -g therm`
  (it may have no `CPU_Speed_Limit` line) and a different chip name; the parsers give null for what is not there.

## Where the feed's description comes from

A 2016 comment on <http://www.trefor.net/2012/11/13/train-wifi-the-unexpurgated-truth/> (an East Coast train between
Newark and King's Cross) lists the addresses and shows the `connectivity` and `users` answers. The npm package
`live-icomera-position` reads `position` from `https://www.ombord.info/api/jsonp/position/?callback=cb`.
