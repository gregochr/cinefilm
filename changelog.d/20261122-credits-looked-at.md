### Added — credits labels: a looked-at list keeps films out of the test split

`LabelsMain` takes `--looked-at <file of film ids>`. A listed film that would land on the test side is excluded with the new reason `LOOKED_AT`, so no test film the credits stills gate has seen is ever scored later. The manifest keeps the film on its test side and flags it (`looked_at`), so the deny file and later runs follow it, and the report counts the exclusions.
