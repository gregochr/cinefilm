### Fixed — A film paused for more than ten minutes carries on from its buffer

A paused film, or a laptop with its lid closed, sends no request, and after ten minutes the server deleted its
session: on resume the player got a 404, threw its buffer away and started a new session from nothing, which Playback
health counted as a recovery and a reload. The server now keeps what it needs to rebuild a session that went quiet
(its spec and its files' size and modification time, at most 8, for up to a day) and rebuilds it under the same id
on its next request, restarting the encoder at the segment asked for, so the player's playlists stay valid and it
plays on from its buffer. A session that was ended, replaced, pushed out at the cap of 3, or whose file has changed
still answers 404. The player is unchanged.
