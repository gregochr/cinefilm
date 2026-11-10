### Added — A compact search index for search-as-you-type (first step)

The server now serves the whole library as one small search index (`GET /api/search-index`): every film with its year,
directors, poster, time left and when it was last played, the collections you own two or more films of, and every
actor and director with their film counts. It carries an ETag, so the app asks "has it changed?" and gets a quick 304
when it has not, and JSON responses are now compressed. The app loads the index once after sign-in, keeps a copy so it
is ready on reload, and refreshes it when you return to the tab, after a scan or Plex import finishes, and after you
close the player. Nothing in the header uses it yet: matching, ranking and the dropdown follow in the next steps.
