-- The Admin change log reads grouping_change by a time range; without an index that scanned the whole table.
CREATE INDEX idx_grouping_change_happened_at ON grouping_change (happened_at);
