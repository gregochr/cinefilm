### Added — the train look-ahead is wired into the player

Away from home, the player now asks the train's own Wi-Fi system for where it is when a film starts and on a network
change, then every 30 seconds while it answers, matches that on the map the server learned from past journeys, and
hands the film bank its input once a second (and at once to any engine the player rebuilds). The asks go on every
30 seconds once the train has answered once, whatever a later answer is; until then there is one ask per trigger.
Nothing is asked while no hls.js engine is attached (direct play, Safari's native player, an Admin stop). The map
is fetched only after the first answer that makes sense, so home and hotel viewings never request it. At home
nothing is asked; with no answer nothing changes. The position stays in the browser's memory: it is not stored, logged or
sent anywhere.
