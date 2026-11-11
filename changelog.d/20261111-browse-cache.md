### Changed — Going back to Library, Home or a film shows the last answer at once

Back to Library, Home or a film shows the last answer straight away (for up to 30 minutes) and checks it in the
background, and Play straight after Film detail does not fetch the film again. Nothing stale is shown after a change
made in this tab: any change empties the saved answers, and an answer whose request overlapped a change is fetched again once the change has finished (after waiting at most a second for a change whose request hangs, the next answer is shown).
A change made on another device shows once the background check lands. The playback health record's start-up time
no longer includes the film's fetch when Play follows Film detail within a minute, so compare readings from before
and after with that in mind.
