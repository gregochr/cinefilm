### Added — Admin shows the OpenSubtitles checks and search evidence

Admin → Subtitles now says, for each forced subtitle file, whether it agrees with OpenSubtitles' file for the film,
needs checking (with the reasons, such as "OpenSubtitles has 41 lines, this file has 12"), or is unchecked because
there is nothing to compare with yet; a timing hint such as "OpenSubtitles' file is 1.2 s later" is shown but never
applied. The file list can be filtered to All, Needs checking or Unchecked. "Which films need them" says how many
foreign-parts-only files OpenSubtitles has for a film, and notes films OpenSubtitles has not been searched for. The
Library scan card shows when subtitles were last searched.
Admin keeps checking the scan status while a search or a comparison with OpenSubtitles runs, and reads the subtitle lists again when either finishes, so new verdicts appear without a reload. The status gained `subtitleChecksRunning` and `subtitleChecksLastFinishedAt`.
