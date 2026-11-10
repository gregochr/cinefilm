### Fixed — SonarQube Cloud blocker and critical issues

The MP4 sample-table reader now reads with positional `FileChannel.read` calls instead of wrapping the channel in an
unclosed `InputStream` (no behaviour change, and the channel position is no longer moved); the `WeeklyTheme` constant
`CAPTION` is renamed `STANDING_CAPTION` so it no longer differs from the `caption` component only by case. The two
accepted findings (the temporary capture file in `ProcessCommandRunner`, and the disabled Spring CSRF replaced by
`CsrfHeaderFilter`) are recorded as single-rule, single-file ignores in `sonar-project.properties`.
