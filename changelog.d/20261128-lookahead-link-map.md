### Added — the server serves the map of past journeys' link quality

`GET /api/journeys/link-map` answers each stored journey's track (positions, course and seconds along it) and the bad
stretches that lie on it, merged across tracks that overlap, each with the seconds of film to hold ahead. It names no
journey, day, train or modem and is never cached. `excludeJourney` leaves out the whole logger run of that journey,
for a replay. The player already asks for it; once this is released, the player away from home starts receiving the
map and acting on its stretches.
