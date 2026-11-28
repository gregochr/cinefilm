### Added — the playback record says what the train look-ahead and the film bank did

The playback health record now carries four more figures for each viewing: the minutes in which the look-ahead held a
fresh input, a short list of the stretches it reached (the film wanted ahead, the film the bank held and the bank's
rung at that moment, in whole seconds), the times the bank changed rung, and the media requests that reached the server
through hls.js's own loader while the bank was on, or while a bank fetch was still in flight (none expected unless the viewer seeks beyond held film while the bank is on;
the bank handing hls.js back its loading is not counted). No position, place or time of day is in any of them. The
server accepts them as optional figures within bounds and a list of at most 50 stretches; an older player's record is
stored as before.
