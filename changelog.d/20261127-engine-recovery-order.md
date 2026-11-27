### Fixed — a playback error no longer sends a resumed film back to its resume point

When a film hit a media error, hls.js reloaded it from the point the film was asked to start at, not from where it
had got to: a film resumed at 2:00 and watched to 5:00 jumped back to 2:00. The player now keeps hls.js's start
position at the playhead, and after a fatal media error starts loading again from the playhead itself (hls.js
stops loading just before the player hears of the error). A reset that hls.js has already recovered is no longer
recovered a second time.
