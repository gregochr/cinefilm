### Fixed — older stored OpenSubtitles files now count as references for the forced timing fit

Full English OpenSubtitles files stored by earlier versions did not record where their last cue ends, so the
forced timing fit left them out. The background timing check now fills that in once per file, for the stored files
among the candidates of a film whose forced subtitles have no English track of their own (never for a film marked as
having no English subtitles), before it fits. A film whose fit was made without such a file may be fitted again once
(when the filled file is usable and not the sidecar's own). The playback path still never reads cue text.
