### Added — the playback record counts what the re-bank and stuck-rung rules did

The playback health record now carries four totals for each viewing: re-banks started, re-banks done, re-banks
undone, and fragment-error marks forgotten. They are the rules' own events, counted where each happens in the
player, so the effect of the rules is read straight off the record and no longer inferred from the bank's figures.
The server accepts them as optional whole numbers from 0 to 2,000,000,000 (an older player that sends none is still
accepted and its record is stored as before) and Admin's playback detail shows them inside the stored report.
