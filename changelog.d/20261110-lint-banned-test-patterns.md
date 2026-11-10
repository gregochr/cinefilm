### Changed — lint and Checkstyle now fail the test patterns test-standards.md bans

ESLint rejects `querySelector(All)`, DOM walking (`parentElement`, `firstChild`, `lastChild`, `children`),
`fireEvent.click` and `fireEvent.keyDown` on anything but `document.activeElement` in frontend tests. Checkstyle
rejects `Thread.sleep(` and wildcard matchers (`any*`, `isA`, `notNull`, `nullable`) inside a `verify(` statement (multi-line too) in backend tests. Existing offenders were fixed
(keys now go through `userEvent.keyboard` or the focused element; `verify(x, never()).m(any())` became
`verifyNoInteractions` / `verifyNoMoreInteractions`).
