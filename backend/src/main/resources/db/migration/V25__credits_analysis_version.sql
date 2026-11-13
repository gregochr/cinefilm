-- Credits detection, phase 2b part 2 (docs/engineering/credits-detection-plan.md; expand-only: one new column).
--
-- credits_detection.analysis_version: which version of the analysis (CreditsTuning.ANALYSIS_VERSION) produced the
-- row. Every row stored before this column existed was made by version 1, hence the default. A file whose row has a
-- lower version than the current one is analysed again, so a change to the analysis can re-run the whole library.
ALTER TABLE credits_detection ADD COLUMN analysis_version INT NOT NULL DEFAULT 1;
