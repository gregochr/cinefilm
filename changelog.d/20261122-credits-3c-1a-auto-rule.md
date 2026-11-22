### Added — Automatic end-credits markers: the rule, nothing wired yet

Internal groundwork for markers from the version 2 detection. `CreditsApply` gains the automatic step (the switch, a deny list, the detection row, a 20 s margin), a parser reads the deny file, and the train/test unit key is public so the product computes the same key as the labels tool. Nothing calls any of it yet, so nothing changes for the owner.
