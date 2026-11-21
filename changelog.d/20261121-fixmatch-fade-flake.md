### Fixed — Fix match dialog tests no longer fail now and then on a slow runner

The Fix match tests that watch the 150 ms fade-out ran on real timers, so a slow CI machine could use up the fade before the test looked, or see the dialog gone before focus had moved back. They now hold the clock and end the fade themselves, inside `act`. Tests only; nothing in the app changed.
