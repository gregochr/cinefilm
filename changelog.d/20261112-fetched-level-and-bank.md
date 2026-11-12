### Changed — Auto follows the quality being fetched, and the health record shows the buffer

Auto's limit on quality used to follow a step down only when the lower quality reached the screen, which is as much
later as the buffer is deep. Until then it went on raising the limit, and afterwards it held a recovered link at the
low quality. It now follows the quality the player starts to fetch, so a step down limits Auto at once. Toasts and
the corner indicator still describe what is on screen.

Each minute of a viewing's playback-health record now also carries how many seconds of film were buffered ahead and
which quality was being fetched, so the next journey shows whether the buffer fills and what the player was doing
when the link fell away. Records made before this keep their shorter rows. The second of four steps
(`docs/engineering/train-link-plan.md`); the deeper buffer comes next and depends on it.
