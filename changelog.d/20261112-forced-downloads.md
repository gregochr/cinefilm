### Added — forced subtitles are downloaded when missing and replaced when the owner's file fails every check

CineFilm now downloads a forced English subtitle from OpenSubtitles for a film that needs one and has none, and replaces an
owner's `.srt` that failed both automatic checks (backend only; the Admin display follows). Only proven subtitles are
trusted: the owner's file plays when you said "Looks right", OpenSubtitles agrees or its timing lines up with the film's own
English track; a download is proven the same way (a second OpenSubtitles file when its timing cannot be fitted). A file that
failed and has no replacement keeps playing, unless it is full subtitles (300 lines or more), which show nothing. Removing a
download in Admin (`DELETE /api/admin/subtitles/{id}`) blocks that file for the edition and looks for the next. Downloads share
the daily OpenSubtitles budget with the checks (missing first). Admin's list gains `origin`, `release`, `openSubtitlesFileId`
and `servedReason`.
