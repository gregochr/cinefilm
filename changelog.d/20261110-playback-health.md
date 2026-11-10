### Added — playback health: storage, API and the Admin card

The first of three steps towards measuring how playback behaves before tuning it
(`docs/engineering/playback-health-plan.md`, from the review in `docs/engineering/network-resilience-review.md`).
The server can now keep one running summary per viewing: `PUT /api/playback-health/{id}` stores a report (start-up
time, stalls, seek and reload waits, reconnects, time on each quality, download speeds, a per-minute track, and
where it was watched), replacing it only with a newer one. Reports are length-checked before parsing, validated
field by field, and capped at 2,000 viewings. Migration V11 adds the `playback_health` table.

Admin has a new Playback health card listing the recent viewings, each with a Full record link
(`GET /api/admin/playback-health` and `/{id}`). It reads "No playbacks recorded yet." until the player starts
sending, which is the next step. `dev-and-testing.md` gains a poor-network check done by hand with Chrome's
throttling.
