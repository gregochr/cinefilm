### Added — train look-ahead: reading the feed and matching a position to past tracks

Two pure player modules, not yet used by anything: `trainFeed.js` reads the train feed's position answer (JSONP, trailing commas, kinds and ranges checked) into a fix, and `trackMatch.js` places a fix on a map of stored tracks (within 300 m, heading within 45 degrees, tracks read either way, stale fixes refused) and gives the time to the stretches ahead.
