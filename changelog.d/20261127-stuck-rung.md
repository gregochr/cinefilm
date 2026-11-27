### Fixed — Auto no longer stays on 240p for the rest of a film after one failed fragment

hls.js does not choose a rung again after one of its fragments has failed until a fragment of that rung
loads, which it never does while the rung is not chosen: a film could play 240p to its end on a link that
had long recovered. The player now forgets such a mark after a minute without trouble, so hls.js can choose
the rung again on its own measurement. And the re-bank, which throws banked film away to fetch it again one
rung higher, now waits until hls.js is already fetching the higher rung: before, it could throw the bank
away and get the same rung back.
