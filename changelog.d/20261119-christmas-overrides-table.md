### Added — Christmas week overrides table

The owner's include and exclude choices for Home's Christmas week are stored in a `christmas_override` table (one row per TMDB id: `INCLUDE` or `EXCLUDE`, so a film cannot be both). It starts empty, so the keyword alone decides until the Admin screen arrives. Exclude still beats include beats the keyword, and Home reads the table on every load.
