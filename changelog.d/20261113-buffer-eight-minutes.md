### Changed — the player banks up to eight minutes of film

On a train the link is good for ten or twenty minutes and then poor for five to twelve, and the player's two-minute
buffer sat full through the good stretches with the link idle. It now keeps up to eight minutes ahead on the
qualities whose eight minutes fit in 100 MB (240p, 360p and 480p), about four at 720p, and two as before on a heavy
original, so a good stretch banks the next bad one. The buffer lives in the browser tab's memory and is gone when the
player is closed; the iPhone's own player is unchanged. If the browser's buffer fills, the player lowers its own limit
and carries on.

The third of four steps (`docs/engineering/train-link-plan.md`). Not checked in a real browser yet: that Chrome and
Safari hold eight minutes.
