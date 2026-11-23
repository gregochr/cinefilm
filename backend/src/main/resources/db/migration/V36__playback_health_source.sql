-- Whether a playback was a person's or the headless viewer's (the train viewer's Chrome): 'person' or 'robot'.
-- The rows already stored are marked from the user agent in the stored summary. The summary is the report
-- serialised again by the server, so a quote inside any text is escaped there (\") and the text '"userAgent":"'
-- can only be the client's user agent key; what follows the user agent inside client is a screen of digits, a
-- number and a boolean (HealthRules), and no free text comes after client, so a '%HeadlessChrome/%' after that key
-- can only be in the user agent. The operator's text comes before it and cannot match.
ALTER TABLE playback_health ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'person';

UPDATE playback_health SET source = 'robot' WHERE summary LIKE '%"userAgent":"%HeadlessChrome/%';
