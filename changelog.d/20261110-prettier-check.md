### Changed — CI fails on unformatted frontend code

The frontend job now runs `npm run format:check` (`prettier --check .`) right after ESLint, so a pull request with files that `npm run format` would change fails the `Frontend — Lint, Test & Build` check. The existing frontend files that did not comply were reformatted (whitespace and style only), and `CLAUDE.md` and `docs/engineering/dev-and-testing.md` list the new step in the frontend gate.
