### Changed — GitHub Actions bumped to current majors

One pull request replaces seven stale Dependabot PRs that edited the same workflows: `actions/checkout` 7,
`actions/setup-node` 7, `actions/setup-java` 6, `actions/setup-python` 7, `actions/upload-artifact` 7,
`actions/github-script` 9 and `dependabot/fetch-metadata` 3.1.0 (still SHA-pinned). `actions/download-artifact` moves
to 8, the major that pairs with upload-artifact 7. No workflow input needed changing.
