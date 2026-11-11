-- Server settings (expand-only: one new table). A typed single row (id is always 1): there is one setting today and
-- each further one becomes a column.
-- continue_watching_min_minutes: how long (in minutes) the last played edition must have been watched before a film
-- counts as in progress (Continue watching, Library "In progress"). Applied at query time; no progress is changed.
CREATE TABLE server_setting (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    continue_watching_min_minutes INTEGER NOT NULL DEFAULT 5
        CHECK (continue_watching_min_minutes BETWEEN 0 AND 30)
);
INSERT INTO server_setting (id, continue_watching_min_minutes) VALUES (1, 5);
