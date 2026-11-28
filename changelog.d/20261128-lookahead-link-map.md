### Added — the server serves the map of past journeys' link quality

`GET /api/journeys/link-map` answers each stored journey's track (positions, course and seconds along it) and the bad
stretches that lie on it, merged across tracks that overlap, each with the seconds of film to hold ahead. It names no
journey, day, train or modem, is never cached, and takes `excludeJourney` for a replay. Nothing calls it yet.
