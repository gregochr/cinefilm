### Added — the list of journeys, and a journey deleted

`GET /api/admin/journeys` lists every stored journey (its device, how many samples it has and when they began and ended, how many playback records are linked, and its train), and `DELETE /api/admin/journeys/{id}` takes a journey away with its samples, modems and links, and its train's run when no other journey is on it. A file can be imported again once its journeys are deleted. Admin's page does not use them yet.
