### Added — Resume shows the frame at the resume point

Resuming a film now shows the frame at the resume point behind the startup indicator instead of a black screen, until
the film plays. The server grabs the frame with ffmpeg (`GET /api/editions/{id}/still?atMs=`), caches it, and the
player hides it on the first playing event or any seek; if the frame cannot be fetched the player stays black as before.
