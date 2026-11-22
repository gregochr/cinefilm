### Fixed — Tab cannot leave a dialog from a button that has just been disabled

While a dialog fades out after "Use this match", the button that was clicked is disabled. Where focus stays on it, Tab used to walk out to the page behind the dialog. The dialog now treats focus on a disabled button as stray and pulls it back in, which also fixes an intermittent failure of the Fix match focus test.
