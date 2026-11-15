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

Every 30 seconds it asks, with `curl`:

| Asked | Recorded |
|---|---|
| `https://www.ombord.info/api/jsonp/`, then the same over `http`: the parts `position`, `connectivity`, `users`, `user` and `system` | each answer as data, how long it took, the address that answered and whether its certificate was trusted |
| `https://www.gstatic.com/generate_204` | how long one tiny request to the internet took, or why it failed |
| only when no film is playing: the first 256 KB of CineFilm's own page code | bytes received, the time, and the speed over the transfer |

`www.ombord.info` is a name that resolves only on the train, so anywhere else the feed is reported as not answering
and the other checks still run. The first good answer of each part also keeps its response headers and whether the
feed used the function name it was given: what the player would need to know to read the feed itself one day. The
first round reads the laptop's own Wi-Fi link once (signal, noise, rate); reading it can make the Mac look for
other networks, so it is not repeated.

Each round is one line of JSON in `~/train-feed-logger/logs/train-feed-<date>-<time>.jsonl`, written at once. **The
log holds where the train went**, so it is kept outside any checkout and is never committed (`SECURITY.md`).

The speed sample would compete with a film, so the script asks at the start whether one will be playing and takes
the sample only when the answer is no. Run without a terminal it takes none. `--film` and `--no-film` answer the
question in advance; `--speed-site` samples from another site.

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
