### Fixed — The OpenSubtitles check no longer flags a good subtitle because OpenSubtitles' own file was wrong

The first live run of the forced-subtitle check flagged films whose OpenSubtitles "foreign parts only" file was really
full subtitles, and films checked against another cut or release. A reference with 300 or more lines is now treated as
full subtitles and the owner's file is judged only by whether its lines are in them and in time. When a file turns out
to be full subtitles or looks like another cut (a timing offset over 30 seconds, or under 30% of lines lining up), the
check tries the next OpenSubtitles file, preferring one named for the edition's cut (Extended, Director's Cut, Special
Edition, Unrated, Theatrical), up to three files per edition, and keeps the best result. "Probably full subtitles, not
forced" is now said only about a file of 300 lines or more. Existing checks are made again with the new rules, using the
files already downloaded where possible (migration `V18`: stored candidates on each film, a rules version on each check,
and a record of which files each edition has been compared with).
