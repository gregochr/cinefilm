### Changed — review process

Every Sonnet brief's self-check now says each new or changed service, component or controller needs its own unit
test class, and the planner runs a test-class check on every diff review (`docs/engineering/dev-and-testing.md`).
PR #129 shipped a service with only a full-application test, which the review missed.
