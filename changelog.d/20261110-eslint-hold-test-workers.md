### Changed — ESLint held at 9, local Vitest capped at half the cores

Dependabot no longer proposes major updates of `eslint` and `@eslint/js`, because `eslint-plugin-jsx-a11y` and `eslint-plugin-react` do not yet support ESLint 10 (remove the ignore when they do). Local Vitest runs now use 50% of the cores so concurrent sessions stop starving each other into timeouts; CI is unchanged and `VITEST_MAX_WORKERS` overrides it.
