### Added — playback health: the player records each viewing

The second of three steps (`docs/engineering/playback-health-plan.md`). The player now keeps a running summary of
every viewing and sends it to the server, so Admin's Playback health card fills in: how long the film took to start,
how often and for how long it stalled, waits after a seek or a change of quality, reconnects, recoveries, a fall back
to the original file, time spent on each quality, download speeds, and a track of every minute. A report goes out at
the first frame, after 20 seconds if nothing has played, every minute while something has changed, and when the tab
is hidden or closed. Time is only counted while the picture is actually moving, so a paused film, a refused autoplay
and a sleeping laptop do not count as stalls.

The recorder cannot affect playback: it never throws into the player, sends nothing if the browser cannot make an
identifier, and gives up quietly when a report is refused. Where the film was watched reads "Unknown" until the next
step, which works out the environment.
