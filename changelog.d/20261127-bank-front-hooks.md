### Player — the bank front can serve hls.js's own request

The bank's front can now serve a request hls.js makes for the position it is about to fetch with the one server request: a fetch on demand, subscribers to the fetch, and retries that go ahead when the front is paused or at its limit (outside-bank-plan.md, PR B2a-2). Nothing in the app uses it yet.
