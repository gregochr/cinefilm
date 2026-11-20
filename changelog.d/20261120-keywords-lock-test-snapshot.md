### Fixed — tests: the keywords lock test no longer times out on a stale pg_stat_activity snapshot

`FilmKeywordsLockIntegrationTest` probes `pg_stat_activity` from inside the rematch transaction, and PostgreSQL serves the first read of it for the whole transaction, so a probe that ran before the save had blocked never saw the waiter and the test failed after 30 s. The probe now calls `pg_stat_clear_snapshot()` first and also requires that the waiter is blocked by the probing transaction. No production code changed.
