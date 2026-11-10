### Fixed — CodeQL baseline: segment bounds, loop width, workflow permissions and pins

`CutList.startMs` and `durationMs` now refuse a segment number outside the list instead of indexing the array, the
edit-list loop in the MP4 reader runs on an `int` count, `ci.yml` declares read-only `contents` permission, and the
third-party actions (`docker/*`, `dependabot/fetch-metadata`) are pinned to commit SHAs. New tests prove that
traversal-style artwork names and HLS session, rung and segment names are all a 404.
