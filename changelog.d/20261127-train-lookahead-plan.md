### Docs — a plan for banking ahead of the train's known bad stretches

Three train journeys lost the link in the same places each time, and two of those stretches last longer than the
eight-minute bank. `docs/engineering/train-lookahead-plan.md` plans a first version: the player reads the train's
own position, matches it to a map learned from imported journeys, and before a known stretch banks deep enough to
cover it, at no rung whose film the browser cannot hold. It starts with three checks (whether Chrome lets the player
reach the train's system, how much the browser's buffers hold, the encoder's speed), and the engine's part is
reviewed again with those measurements before it is built.
