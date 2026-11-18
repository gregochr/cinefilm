### Fixed — The code-quality gate is clean again

The 71 reliability findings SonarQube Cloud had open on `main` are cleared: the release and deploy scripts use bash's
`[[ ]]` tests, the file-name, title-year, path and collection-name readers trim with plain loops instead of regular
expressions that could take long on odd input, the player no longer leaves a fullscreen or session-cleanup promise
unhandled, and two caches and one image record are written the way the analyser expects. Nothing a viewer sees or does
has changed.
