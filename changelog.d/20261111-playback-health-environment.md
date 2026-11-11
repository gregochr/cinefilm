### Added — playback health: where each film is watched

The third of three steps (`docs/engineering/playback-health-plan.md`). Every viewing is now tagged with where it
happened. At home (the server answers within 20 ms) nothing is asked and nothing is looked up. Away from home the
player looks up the name of the network's operator and, the first time it sees a network, asks one quiet question
in the player: "Are you on a train?" (Yes, on a train / No / Not now). The answer is remembered in that browser, so
the same network is recognised silently from then on; Admin lists the remembered networks under Playback health,
each with Forget. The question never takes focus, never stops the film, and takes its turn with the slow-connection
card (handover decision 18).

The lookup is the browser's only request to an outside service besides TMDB's images (`SECURITY.md` rule 7). It is
made from a sandboxed frame, so the service sees the device's public address and nothing about CineFilm, and only
the operator's name is kept. No location is asked for or stored. A network that can't be named, or that is a relay
(iCloud Private Relay), is asked about each time and never remembered.
