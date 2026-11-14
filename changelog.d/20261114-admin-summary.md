### Added — Admin summary endpoint

`GET /api/admin/summary` answers the figures the redesigned Admin rail and To do strip need in one read: the To do
group counts, unresolved subtitles, live sessions, the library size and last scan, how smoothly the last 30 days of
film sessions played, and what is playing now. It reuses the services' existing rules and is not cached. No UI yet.
