### Added — credits AUTO: the candidates list and the runbook

`GET /api/admin/credits/auto-candidates` lists the editions the credits `AUTO` rule would mark, computed by the same
code and over the same editions as the apply pass (hidden ones included), as if the switch were on and no owner's
marker were set. It is for the owner's stills check before switching `AUTO` on, and works with the switch off. It
answers 409 until a pass has a deny list in force. `operations.md` gains the credits `AUTO` runbook: the deny file,
the read-only edition and follow-up queries, fetching the candidates, and switching on and off. Nothing changes for
viewers: the switch stays off.
