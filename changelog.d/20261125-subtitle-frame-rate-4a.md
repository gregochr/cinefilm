### Added — Admin API: retime off or on per downloaded subtitle, and the rate facts

The Admin API can now turn retime off or on for one downloaded subtitle (`PUT /api/admin/subtitles/{id}/retime`) and reports each download's frame-rate facts and a plain sentence about them (`timedFps`, `filmFps`, `retime`, `retimedBy`, `rateLine`). Nothing is retimed while the global `cinefilm.subtitles.retime` switch is off, except by the owner's own per-file "on" (which is refused only while the switch is `restore`, the rollback).
