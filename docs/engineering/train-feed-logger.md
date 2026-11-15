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
| `https://www.gstatic.com/generate_204` | how long one tiny request to the internet took and the status it got, or why it failed. The printed line notes any status but 204: a sign-in page answers 200 there |
| only when no film is playing: the first 256 KB of CineFilm's own page code | bytes received, the time, and the speed over the transfer |

`www.ombord.info` is a name that resolves only on the train, so anywhere else the feed is reported as not answering
and the other checks still run. The first good answer of each part also keeps its response headers and whether the
feed used the function name it was given: what the player would need to know to read the feed itself one day. The
first round reads the laptop's own Wi-Fi link once (signal, noise, rate); reading it can make the Mac look for
other networks, so it is not repeated.

Each request names a function for the answer (`callback=cb`) and carries a time stamp, so nothing on the way can
answer from a cache. An answer over 200,000 bytes is refused.

Each round is one line of JSON in `~/train-feed-logger/logs/train-feed-<date>-<time>.jsonl`, written at once. The
`logs` folder, when the script is the one to create it, and every log are readable by the owner alone. **The log
holds where the train went**, so it is kept outside any checkout and is never committed (`SECURITY.md`).

The speed sample would compete with a film, so the script asks at the start whether one will be playing and takes
the sample only when the answer is no. If nobody answers within a minute it assumes a film and starts. Run without
a terminal it takes none. `--film` and `--no-film` answer the question in advance; `--speed-site` samples from
another site. When a release changes the script's name, it reads the page again.

## What the log holds

The first line is a `start` event (the addresses and parts, the internet check's address, the interval, the timeout
and speed site in force, and the versions) and the last a `stop` event with the number of rounds. Each round
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
| `speed`, `speed_from` | the speed sample: `bytes`, `ms`, `first_byte_ms` and `kbps` (null when too fast to measure or nothing arrived), with `why` when it failed (alone, when the page could not be read or names no script); and the script's address, in the round it is found and again whenever a 4xx answer makes it be looked up again |

A part that fails three rounds running is then asked only every tenth round (`--retry-every`), and so is every
address but the one in use. A certificate that was refused is tried again at each of those full checks, so
`untrusted` is never carried from memory for longer than that. A round that fails as a whole is a line with only `t`, `round` and `error`.

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

## Where the feed's description comes from

A 2016 comment on <http://www.trefor.net/2012/11/13/train-wifi-the-unexpurgated-truth/> (an East Coast train between
Newark and King's Cross) lists the addresses and shows the `connectivity` and `users` answers. The npm package
`live-icomera-position` reads `position` from `https://www.ombord.info/api/jsonp/position/?callback=cb`.
