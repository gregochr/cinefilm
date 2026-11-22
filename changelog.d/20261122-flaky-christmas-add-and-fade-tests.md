### Test — four flaky tests are now order- and clock-proof

The two Christmas add tests that remove a film before the first answer from `/states` now wait for the film states to be asked before pressing Remove, so the held answer is always older than the write. The Edit editions fade test holds the clock instead of racing the real 150 ms timer, and the Edit editions focus test now waits for the dialog to focus its first field before moving focus away. No product change.
