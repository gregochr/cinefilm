### Added — Christmas films Admin API

`GET /api/admin/christmas` lists the films Home's Christmas week counts (in Home's order, with no 12 cap), the ones the owner added and the ones left out, with the week's dates, the keyword backfill progress and whether Home would fall back to the normal rotation. `PUT /api/admin/christmas/{tmdbId}` includes, excludes or forgets a film (409 while a scan runs) and `GET /api/admin/christmas/states` says where up to 50 films stand. Home and this API build the list with the same code. No screen yet.
