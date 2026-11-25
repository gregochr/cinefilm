### Docs — plan: no frame-rate choice for the owner

A subtitle file holds no frame rate, so the owner can't know it: the plan drops the per-file rate choice from the Admin. The stated rate decides; two subtitles of the same rate family are compared on their original timing, so a correct pair never fails on a retime; and "don't retime" stays as the one per-file control.
