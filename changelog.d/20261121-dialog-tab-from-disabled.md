### Fix — Tab stays in a dialog when its focused button is disabled

Pressing Tab right after "Use this match" (or any dialog button that disables itself) could move focus out of the Fix match dialog to the page header. The focus trap now moves on to the dialog's next control. This also stops a flaky Fix match test in CI.
