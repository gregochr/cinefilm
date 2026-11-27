### Docs — a plan for Auto staying on 240p after one failed fragment

On a train run Auto played 240p for almost the whole film on a link that was often good: hls.js does not choose a rung again after one of its fragments has failed, the cap that stays one rung above the fetch then never offers a higher one, and the re-bank refetched the bank at the same rung. `docs/engineering/stuck-rung-plan.md` sets out the cause, the proof in the browser, and the change.
