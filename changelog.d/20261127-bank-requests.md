### Player — the bank decider for the requests hls.js waits on

The decider the bank loader asks is in place (outside-bank-plan.md, PR B2b): it answers a fragment from held film, fails one held at another rung as a retryable `bank-rung` 503 after telling the engine, waits on the front's one fetch of a position with hls.js's own limits, and moves the front past or back to a request outside its position. Nothing in the app uses it yet.
