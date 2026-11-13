### Docs — what the train-link changes showed in a real browser

`docs/engineering/train-link-plan.md` now says that its four steps are merged, records what was seen when the real
player engine and the real hls.js were run in a browser against a public test stream (the order of hls.js's events,
the quality limit following what is fetched, eight minutes held in the buffer, the 360p hold arming, releasing and
not pulling), and narrows the list of what nobody has verified to what is still true: a link that is really slow or
dead, the server's encoder at 240p, other browsers and a real train.
