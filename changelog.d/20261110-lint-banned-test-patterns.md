### Changed — lint and Checkstyle now fail the test patterns test-standards.md bans

ESLint rejects `querySelector(All)`, DOM walking (`parentElement`, `firstChild`, `lastChild`, `children`),
`fireEvent.click` and `fireEvent.keyDown` on anything but `document.activeElement` in frontend tests. Checkstyle
rejects `Thread.sleep(` and wildcard matchers (`any*`, `isA`, `notNull`, `nullable`) inside a positive `verify(` statement (multi-line too) in backend tests; `verify(x, never())` with wildcards stays allowed as the strict never-called form. Existing offenders were fixed
(keys now go through `userEvent.keyboard` or the focused element; positive `verify` wildcards became exact arguments or captors).
