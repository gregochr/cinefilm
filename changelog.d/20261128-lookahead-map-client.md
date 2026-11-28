### Added — the player can read the train look-ahead's map

A new client reads the map the server learned from past journeys (`GET /api/journeys/link-map`): per track, its
points and its stretches. It drops anything malformed, gives an empty map on any failure (a sign-out included), and
keeps nothing: no storage, nothing sent anywhere. Nothing calls it yet.
