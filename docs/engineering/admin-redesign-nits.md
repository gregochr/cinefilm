# Admin redesign: nits for the cleanup PR

These are small and deliberately left alone; they are fixed together in one PR after 8b.

## 3c-2

- `adminTodo.js` `bestGuess` re-derives the ranking. Document "candidates ranked, best first" in the `AttentionItem`
  contract, use `candidates[0]`, and drop the reverse-order test.
- The hint is under "Yes" only; AR1 right-aligns it under both buttons.
- `adminTodoManyFixtures.test.js` checks top-level keys only. Deep-compare `editions[]` and `fullSubtitle`.
- `footerFor`'s comment says "group of 1 or 2". Name the group keys instead.
