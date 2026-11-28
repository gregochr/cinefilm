### Fixed — the film bank no longer loops at a change of quality

When the bank held one segment at one quality and the next at another, the player could ask for the first again
after the change and be refused, then ask for the next at the old quality and be refused again, stalling at the
edge of its buffer. The bank now keeps a second copy of the segment before each quality change, at the new quality,
and answers that request from it; without one it fetches that single segment at the quality asked for. When the
bank turns off, the fetch under way now finishes for the player instead of being cut.
