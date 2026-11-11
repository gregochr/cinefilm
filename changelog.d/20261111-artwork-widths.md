### Changed — Stored artwork is sized to the screen that shows it

`GET /api/art/{name}` takes an optional `?w=342|500|780|1280`. The server resizes the stored image to that width on the
first request with the JDK's own image reader and writer, keeps the copy under `cache/art/<width>/` (at most 128 MiB,
oldest removed first) and serves it from there; an image that is not wider than the width, one the resize would not
shrink, or one it cannot read is served as stored. `imageUrl` now asks for the width each screen needs, and Continue
watching stills ask for 780 instead of 1280. On sample photographs cut to Plex's sizes, a Library poster came down from
144 KB to 23 KB on average and a Continue watching still from 384 KB to 30 KB; the real figures depend on the stored
artwork. The first view of each image pays for its resize once.
