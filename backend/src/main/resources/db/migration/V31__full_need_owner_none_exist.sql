-- The owner's third choice on full English subtitles: NONE_EXIST (the film needs them, but none exist anywhere).
ALTER TABLE film DROP CONSTRAINT film_full_need_owner_check;
ALTER TABLE film ADD CONSTRAINT film_full_need_owner_check
    CHECK (full_need_owner IN ('NEEDED', 'NOT_NEEDED', 'NONE_EXIST'));
