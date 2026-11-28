### Added — a sandboxed frame that asks the train's Wi-Fi system for one answer

A new player module asks the train's own system for one answer through a hidden, sandboxed frame, as the operator
lookup does: no cookie, no referrer, no origin of ours, one ask at a time, and the answer as text only. Nothing calls
it yet. `SECURITY.md` rule 7 now covers it.
