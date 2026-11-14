### Added — a background job that reads the frames the credits classifier is trained on

A new job, off by default (`cinefilm.credits.dataset.enabled`), reads from each film with a Plex credits marker the
frames a classifier will be trained on, one film at a time, and keeps them on the server's disk. It stands aside for
playback and for the credits detection's own films, stops when the disk is nearly full, and carries on after a restart.
The Admin routes `GET /api/admin/credits/dataset`, `POST /api/admin/credits/dataset/start` and
`POST /api/admin/credits/dataset/stop` show it, start it and stop it. It is for training the credits classifier and
changes nothing a viewer sees: no credits are found differently and no screen is different.
