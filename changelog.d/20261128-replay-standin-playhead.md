### Changed — the home replay's stand-in film has real audio weight and the server's one encoder playhead

The replay rig's stand-in now gives each segment sound as heavy as its rung's real audio (64, 96 or 128 kbit/s of
AAC noise, the rates read from `Rung.java` by a test), so Chrome's audio buffer fills as it does on the real film. A
new `--encoder <speed>` option (off by default) also models the server's one encoder playhead per session: a segment
is answered when the encoder has made it, a request far from the playhead restarts the encoder there and deletes the
segments after it, a segment not made in six seconds gets 503, and the results count the restarts by reason. Segments lost and asked again (the server deleted them and the player asks for one) are counted too, a
session's start time and an encoder start-up time (`--encoder-start-ms`) are modelled. A
template made before this change is made again on the next run. The rig is not part of the app.
