### Fixed — Player and Film detail Fix… review fixes

On a weak link, a subtitle timing change whose new track failed to load no longer leaves a hidden second step: the new
timing is already saved, so the next Earlier, Later or Reset press (or the connection coming back) loads it, and only
the press after that moves it. The player's timing and "Subtitles are wrong" requests give up after 15 seconds
instead of hanging, and "Subtitles are wrong" no longer reads every subtitle of every film in the middle of a film.
When Admin stops a playback (or a split film cannot play) on an iPhone, Safari's native fullscreen player is left so the
card is visible; the stopped card is read with its message, any open menu closes, and Quality is greyed out while
stopped. A pending timing track of a subtitle that is not showing is no longer mounted, so system caption menus do not
list it twice. In Film detail's Fix… panel the tools stay focusable while one runs, a failed action keeps focus on the
button, the status line is one region, and a downloaded file's lines are named by its release.
