### Changed — one film watched in several playbacks is one viewing in Admin's chart and counts

When the player reloads, or you pause for a while, it starts a new playback, so one film watched once showed as two
columns in "How films played". The server now joins the playbacks of one film on one device that follow each other
within 6 hours (and, when the player reports the film position, pick up within 2 minutes of where the last stopped)
into one viewing. The chart rows gain `endedAt` and `parts`, the Admin film and smooth counts count viewings, and a
health report may now carry the film's `startPositionMs` and `endPositionMs`. The log and single-record routes still
show each playback on its own.
