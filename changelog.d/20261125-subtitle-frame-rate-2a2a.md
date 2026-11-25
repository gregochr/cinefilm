### Added — the subtitle file check knows how to compare a subtitle that has been retimed

The check of the owner's subtitle files and downloads against OpenSubtitles' files now has a rule for comparing a
subtitle that has been retimed for a 25 fps copy: on the right timeline, or not at all when the frame rates are not
known well enough. Nothing is retimed yet, so nothing visible changes: every check gives the same answer as before.
