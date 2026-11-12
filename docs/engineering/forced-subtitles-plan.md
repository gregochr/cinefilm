# Forced English subtitles — plan

Written 2026-11-11. Status (2026-11-12): **phase 1 merged (#105) and released (v0.4.0); phase 2a merged (#109),
2b, 2c, 2d, the Admin display and phase 3a merged (#109 to #115); 2e (robust against bad reference files) in review;
phase 4a (downloads, replacements and "trust only proven files" playback, backend only) built on top of 2e, not yet
pushed; phases 3b (speech, only if still needed), 4b (Admin display of downloads), 4c (multi-part results), 5 and 6 not
started.** Phases 5 and 6 were added on 2026-11-12: the owner says
solving subtitles is what would let them stop depending on Plex.

## Decisions made while the owner was away (2026-11-12)

Judgement calls made while building 2d without the owner. Each: what was chosen, why, and the alternative.

1. **The live OpenSubtitles test was blocked, so 2d was built from the documented API.** Login, `/download` and the link
   fetch have never been run against the real service. Chosen: build from the documented v1 REST API and fail loudly and
   safely (anything unexpected is an outage, never "no match", nothing wrong is stored). Alternative: wait for a real
   download. **First real run: watch the log** and fix any difference (header names, `base_url`, the link host) in a small
   follow-up.
2. **Daily download limit 90 of the 100.** Why: leaves 10 of the dev-mode 100 for the owner's own use.
   Alternative: 100. Setting: `cinefilm.opensubtitles.daily-download-limit`.
3. **Thresholds 70% / 0.5x / 2x and a +-2 s match window**, from the plan's own wording. Alternatives: stricter or looser
   share; a 1 s window (more false `CHECK` on a loose file).
4. **Checks run per edition on the served (stitched) subtitle**, not per file: a split edition's per-part files are
   compared as the one timeline the player gets. Why: the owner's file is only right or wrong as played. Alternative: per
   file, which cannot judge a part against a whole-film reference.
5. **Owner-verified files are still checked for completeness.** "Looks right" is about lines and timing a person saw;
   it cannot see a missing half. They are checked after the unverified ones.
6. **Best offset: the best fit, not just the smallest offset.** Equal match counts are separated by the least total
   distance before the plan's "smallest |offset|" rule, because with a 2 s window a 1.2 s shift matches at offset 0 too and
   the hint would always say 0. Alternative: the literal tie-break (hint always near 0).
7. **Multi-CD OpenSubtitles results are never compared (coordinator, 2026-11-12).** A result split over several files
   (`nb_cd` above 1, or several `files[]`) is only part of the film. The parser reads both counts (`fileCount` is the
   larger); ranking puts single-file results above every multi-file one; when the best result left is multi-file, the
   edition is skipped (log: "Only multi-part subtitles on OpenSubtitles"), with no download and no verdict stored.
   A best result stored by 2c without `fileCount` is treated as unknown: the search's due query (`findOpenSubtitlesDue`)
   includes films whose `os_best` JSON lacks `fileCount` and searches them first, so the next run rewrites it with the
   count (chosen over treating a missing count as multi-file, which would skip those films for good).
8. **No `checkVerdict` on the film need rows.** Their cheap place would add a query and change a response the Admin UI is
   being built against; the verdict is on the subtitle list's `check` instead. Alternative: add it with the Admin display PR.
9. **A download that cannot be read is kept as an unusable reference** (no cues, or over 1 MB normalised) so that file id
   is never fetched again; a changed best result (new file id) is a new reference.
10a. **No refund once OpenSubtitles has issued its link** (the account is charged then). The client throws
    `OpenSubtitlesChargedException` for any failure after the link (link refused or file too big: permanent; timeout, 5xx,
    other status: transient); the refresher never refunds those. Permanent failures and zero-cue files make that file id
    final at once (`opensubtitles_reference.failed_downloads` = 3); a transient one counts one charged attempt, stops the
    run, and the file is tried again later, at most 3 charged attempts in all. A check is current only while its
    reference's file id is still the film's best (`os_best`) file id, as well as the served version and IMDb id.
10. **A download is given back to the budget on an outage** (before or during `/download`, nothing charged) (the request did not count at OpenSubtitles) and the whole day
    is spent on a quota answer or `remaining` of 0 or less, until the reset time it gave (at most 36 hours ahead) or the
    next UTC midnight.
11. **Owner decision (2026-11-12): trust only proven `.srt` files.** The owner will not routinely watch films to mark
    subtitles "Looks right", so "proven" comes from the automatic checks; "Looks right" is an optional override.
    - A sidecar keeps playing if the owner verified it, **or** 2d says `AGREES`, **or** phase 3 says `ALIGNED`.
    - A sidecar that fails both automatic checks and is not owner-verified is **replaced by an automatically checked
      download** in phase 4. An automatically checked download plays straight away, with no owner step. This replaces
      the earlier "a sidecar always wins" rule (Phase 4 below is updated).
    - A sidecar that cannot be checked at all (no reference, no alignment possible) keeps playing and is listed in
      Admin as **"Unchecked"**.
    - 2d only makes the verdict available per served edition; **no playback change in this PR.**
12. **The embedded track is found by the reader's own `ffprobe` listing, not from the stored streams.** `FfprobeParser`
    keeps only text subtitle streams (`subrip`, `ass`, `mov_text`, ...), so the picture tracks (PGS, DVD) this phase is
    for are not in the database. Alternative: store every subtitle stream at scan time (a model and scan change for
    something phase 6 will need anyway; left to it).
13. **One packet pass reads every candidate track at once** (`-select_streams s`, with `stream_index` in the output) instead
    of one run per stream with `-select_streams <index>`. A pass demuxes the whole file (minutes over SMB), and choosing
    "the most packets" needs the counts, so one pass is both cheaper and the only way to rank by cue count. The cost is a
    few KB of other tracks' packets parsed and dropped.
14. **The packet output is `compact` (`key=value|...`), not `csv=p=0`.** It does not depend on the order ffprobe prints its
    fields in, which could not be checked against a real ffprobe on the dev Mac (no ffmpeg there; the CI real-tool test
    proves it). Alternative: csv with a pinned field order.
15. **The PGS clear-packet threshold is 50 bytes, not about 100.** In a Matroska file a clear display set is a presentation
    composition (no objects), a window definition and an end segment: about 30 bytes. The smallest display set that draws
    anything adds a palette and a picture object and is more than 60. 50 sits between them with room either side; 100
    could drop a real one-word cue. A packet with no size is dropped. DVD and text packets are never filtered by size.
16. **Offsets are searched within +-60 s, but accepted only within 10 s.** Searching only +-10 s would show a file that is
    14 s out as "only 4 of 12 lines line up" instead of the useful "Best fit is more than 10 s out". Such a file is `CHECK`
    and never auto-applied. Alternative: +-10 s exactly (as the brief).
17. **The 10 ms refinement covers +-300 ms (a window), not +-100 ms (a bucket)** around the best bucket, so cues that wander
    either side of one offset are fitted in the middle and not at a bucket only half of them fall in. 61 scored offsets:
    nothing.
18. **The chance guard takes the best share over four wrong offsets: +3.7 s, -4.3 s, +7.1 s and -9.3 s** (changed after
    CI: the first version probed exactly +-5 s, and a fixture with a cue every 5 s matched everywhere there). One fixed
    probe is fragile and a round one can sit on a period; four odd ones, the highest counting, are not. A fit needs to beat
    it by 0.4. A track dense or regular enough that a true subset cannot clear that is `CHECK` ("...could be chance"),
    never `ALIGNED`: the safe side. The real-tool fixture now uses irregular gaps (3 to 7 s), and a unit test runs
    `TimingFit.fit` on the exact fixture lists.
19. **`TOO_FEW` is its own verdict** (under 8 lines), as is `NO_REFERENCE`; both show as Unchecked in Admin and neither
    counts as proof. With no reference at all `NO_REFERENCE` wins over `TOO_FEW` (nothing could be compared either way).
20. **The cached cue starts are comma-separated gaps in a `TEXT` column, not `bigint[]`.** The same column works on
    PostgreSQL and the H2 the tests run on (no custom Hibernate type), a few thousand cues is a few tens of KB, and the
    rows are only ever read whole. Alternative: `bigint[]` (about the same size, needs a type per database).
21. **The read cache's fingerprint is the `media_file` row's size and modification time as last scanned**, not a fresh
    `stat`: that is what the scan already uses to tell a file changed, so one rule decides. A file with no size or time is
    not read (it could not be told apart from a changed one).
22. **An alignment row is keyed by the raw file's identity and the reference's key, never by the served version.** The
    served version includes the offset, so keying on it would make applying an offset stale its own check and loop forever.
    The identity is the film file, the whole-edition flag and the cue hash; the reference key is each reference file's id and
    fingerprint (plus each part's start for a whole-edition file). Applying an offset does make 2d's check stale (its
    version includes the offset): it re-runs on its own, which is correct.
23. **`offset_source` is backfilled `OWNER` for every offset that is not 0** (only the owner could have set one so far) and for any
    file the owner attached by hand or verified (an old reset to 0 cannot be told from never set, so a touched file is
    treated as the owner's), and
    an `AUTO` offset is dropped (back to 0, never set) when the file's cues change or it moves to another film file, in the
    scan, attach and detach. A file rewritten with the same cues keeps it (changed after review: the alignment's identity
    is unchanged, so clearing it would never be fitted again). One rule, `SubtitleSidecar.retarget`, serves the scan, attach and "Back to automatic": the offset is dropped only when the film
    file or the whole flag changes, so a re-attach or rematch to the same place keeps it.
    The 0.80 share and 0.4 margin are compared as exact integer counts (a double 17/20 - 9/20 is 0.39999...), with
    boundary tests. Alternative: keep a stale automatic offset until the next
    fit replaces it (wrong in between).
24. **Each sidecar is fitted on the timeline of the span it covers, so "restrict the reference to the span" is by
    construction:** a per-part file against its own part's track in part time (the offset is the same as on the edition
    timeline), a whole-edition file against every part's track shifted by `EditionTimeline`. A whole-edition file waits
    until every part has been read; one part with no track makes it `NO_REFERENCE` ("Part 2: ...").
25. **The fit is saved under `LibraryLock` and revalidated; a taken lock stops the run and an hourly nudge picks it up.** A
    scan and Admin's edits rewrite the same sidecar rows, so the save holds the lock (never across the 20 minute read).
    The read cache's write takes it too (check of the file's fingerprint and write as one step, a taken lock drops that
    read to be redone: changed after review).
    A run stopped for the lock or for playback sets a flag; `SidecarTimingScheduler.hourly` (at :15) starts a run only when
    that flag is set. Alternative: wait for the next day.
26. **`SessionManager.isEncoding()` is new**: there was no "is anything transcoding" signal. It is true while any session's
    encoder process is running. It is checked before each film and before each read; a read already under way is not
    interrupted (it is niced and capped at 20 minutes).
27. **A failed read (ffprobe missing, an error, the timeout) is not stored; the file is left alone for 6 hours in memory.**
    Stored failures would hide a transient SMB outage for good; no backoff would re-read a broken file at every scan.
28. **`SidecarTrust.isProven(boolean ownerVerified, CheckDto, AlignmentDto)`** takes the verified flag rather than the
    sidecar (verification needs the edition's part ids), with a null check or alignment meaning "none, or not current"; an
    overload takes a listed `SidecarDto`. Phase 4 uses it; nothing does yet.
29. **The test profile switches the check off** (`cinefilm.subtitles.timing-check.enabled=false`), so no Spring test context
    starts a background run at start-up; the refresher's own tests construct it enabled.
30. **Doubts.** (a) The `ffprobe` stream listing (`-show_streams -select_streams s` JSON) and the `compact` packet lines
    were written against ffprobe's documented output and are proven only by `EmbeddedCueReaderRealToolsTest`, which runs in
    CI; picture subtitles cannot be generated from text by ffmpeg, so PGS is covered by a fixture of real-looking packet
    lines, not a real file: **the first real run on a PGS film should be watched** (the log says how many files were read).
    (b) The "pending set after the last pass" tail of the worker loop is not unit-testable (one branch uncovered, as in the
    other refreshers).

31. **Only codecs whose empty packets can be told apart are references** (changed after review). Accepted: `subrip`/`srt`,
    `ass`/`ssa` (one event per packet), `mov_text`/`tx3g` (an MP4 writes a 2-byte empty end sample per cue, dropped by
    size: a sample with text is 3 bytes or more), `hdmv_pgs_subtitle` (clear packets under 50 bytes dropped) and
    `dvd_subtitle`. Excluded as a reference rather than guessed: `webvtt` (an MP4 one writes empty cue-end boxes of a size a
    short real cue can share), `dvb_subtitle` (erase pages), `xsub`, teletext, `text` and any unknown codec.
32. **`applied` in the DTO is derived**, not the stored flag: the alignment is `ALIGNED`, `offsetSource` is `AUTO` and
    `offsetMs` equals `fittedOffsetMs`. An owner's nudge or reset therefore clears it at once. The column stays as the
    record of what the last save did.
33. **Every new service has an isolated unit test** (AGENTS.md rule 5): `SidecarTimingServiceUnitTest` (repositories faked, no
    Spring) beside the H2 `SidecarTimingServiceTest`; the reader, refresher, scheduler, keys, streams, packets, codec,
    fit and trust already had one.
34. **2e: a reference that is really full subtitles is judged by timing only (2026-11-12).** Evidence, the first live
    run (20 editions, 11 `AGREES`, 9 `CHECK`): OpenSubtitles' "foreign parts only" file for Spectre (1,362 lines),
    Taken 3 (1,456) and The Revenant (834) is full subtitles with a wrong flag, so the owner's 39, 13 and 138-line files
    were flagged "incomplete". A reference with **at least 300 cues** (`SubtitleComparison.FULL_LINES`) is "full";
    completeness is not judged. Instead the subset check: the share of served cues with a reference start within 2 s at
    the best offset must be **at least 70%** and the best offset **at most 10 s** either way, else `CHECK`. Reasons:
    "OpenSubtitles only had full subtitles; this file's lines are in them and in time" / "...and this file's lines don't
    line up with them". Alternatives: 200 or 500 as the line (a genuine forced file is well under 150 lines and a
    feature's full subtitles over 600, so anything from about 200 to 400 separates them in the live data; 300 is the
    brief's); a 50% subset share (too loose: a different cut shares many lines with the right one); judging against the
    film's runtime and cue density instead (needs data the check does not have).
35. **2e: "probably full subtitles, not forced" needs a served file of 300 lines or more.** Taken 2 (39 lines against 16)
    said it. Below 300 the "This file has M lines, OpenSubtitles has N" part stays (it is still a `CHECK`: more than
    twice the reference) without the guess. The completeness reason ("OpenSubtitles has N lines, this file has M") is
    unchanged.
36. **2e: the check walks up to three candidate files, not one.** `film.os_candidates` (V18) holds the film's single-file
    results in ranked order: the top 3 and then the best result of each cut tag (Extended, Director's Cut, Special
    Edition, Unrated, Theatrical) that is not among them, at most 8, so an edition's own cut can be found when it is not
    the film's overall favourite (the brief said "top 3"; with only 3 stored, an Extended file ranked 5th could never be
    reached). `os_best` stays the film-level best (2c's need rule, Admin's search evidence). Legacy rows have
    `os_candidates` null: the search's due query includes them, those with a best result first, and the checker leaves a
    film alone until it has them. A film searched with no single-file result stores `[]`, which is "none", not "legacy".
37. **2e: the candidates are re-ordered for each edition by the cut its name says.** `CutTag` reads the release name, the
    first file's name (`files[0].file_name`, now kept as `fileName`) and the edition's name, case-insensitively and
    ignoring punctuation: `extended`, `director's cut` (also `directors cut`, `director.s.cut`), `special edition`,
    `unrated`, `theatrical`. Each tag the result shares with the edition is +1; each tag of the opposite cut is -1
    (Theatrical is the opposite of every other tag, and every other tag of Theatrical; a tag the edition itself carries
    is never an opposite). The fit comes first, the film-level order breaks ties; an edition with no tag keeps the film
    order. The library's default edition name is "Theatrical Cut" (and "Original" in some fixtures); the owner names
    extra editions ("Extended Edition"), so only some names carry a tag. Alternatives: the fit after the frame rate (a
    poorly timed file of the right cut would lose to a well-timed file of the wrong one, which is the case this exists
    for); treating Director's Cut as the opposite of Extended (no evidence they differ in timing).
38. **2e: when to move to the next candidate, and the cap.** After a `CHECK`, the walk moves on when (a) the reference was
    full, or (b) the result looks like another cut: the best offset is **more than 30 s** either way, **or fewer than 30%**
    of served cues line up **while both files have at least 8 lines**. Live evidence: Bourne Supremacy -114 s, Taken 2
    -339 s and 15%, Dances with Wolves 19% at +54.5 s: all (b); an honest incomplete file (12 of 41 lines, all in time)
    is not (b) and is stored as it is. 30 s is above the timing drift seen (the Revenant's -100 ms) and below the shortest
    cut difference in the evidence (54.5 s); 30% leaves a wide gap under the 70% `AGREES` line. Exact integer arithmetic
    (`matched * 100 < served * 30`). Deliberately *not* (b): an `AGREES` (even at a far offset, which only phase 3's
    timing alignment can judge), and a served file that is itself full subtitles (300 lines or more and more than twice
    the reference: Amsterdam, 2,431 lines against 43, and Olympus Has Fallen, 1,300 against 29, are real catches that
    another file would not change, so they stay `CHECK`). RotK (3 lines against 12, 0% matched) is not (b) either (it
    has only 3 lines): the edition-name fit is what should find the extended file there. **At most 3 files are ever
    compared per edition and IMDb id**, persisted in `forced_check_candidate` (V18, seeded from the existing checks); a
    reused stored reference counts as one. A budget that is spent, a quota answer, an outage or a charged transient
    failure ends the run with nothing saved for that edition (the stored references are reused next time, so nothing is
    downloaded twice); a charged download is never refunded and counts on the ledger.
39. **2e: which result is kept.** The first `AGREES`. Otherwise the most plausible `CHECK`: a non-full reference with the
    most served lines lining up, then full references by the same count (all attempts compare the same served cues, so
    the count orders them exactly as the share would); ties keep the earlier candidate. When several files were tried and
    none agreed, the reasons begin "Tried N OpenSubtitles files; they look like a different cut" (every file was full
    or looked like another cut) or "...; none of them agreed with this file". Reasons are stored whole up to the column's
    1,000 characters.
40. **2e: `rules_version` and re-evaluation without downloads.** `forced_subtitle_check.rules_version` (V18; 1 = 2d,
    2 = 2e, `ForcedSubtitleCheck.RULES_VERSION`) makes every 2d row stale. `opensubtitles_reference` keeps the normalised
    cues, so a stale check is compared again locally with the stored references first, and only a candidate with no
    stored reference (within the cap) is downloaded. A check also records `best_file_id` (the film's best result then):
    the reference may be any candidate, so a changed best file, not the reference's own file id, makes it stale, and
    Admin's existing `isCurrent` call keeps working unchanged (its `bestFileId` argument now means that). A check whose
    reference is no longer one of the film's candidates is stale too, and a result is dropped on save when it is.
    The check also stores `cut_tags` (the sorted `CutTag` names of the edition's name when it was made): renaming an
    edition so its tags change (Theatrical Cut to Extended Edition) reorders the candidates, so it makes the check
    stale. When the ledger is full, an untried candidate that now ranks first is skipped and the tried (stored) ones
    below it are still compared, which costs no download.

### Decisions made building phase 4a (2026-11-12, owner away)

Each: what was chosen, why, and the alternative. Numbers continue 2e's.

41. **A download is a `subtitle_sidecar` row with `origin = OPENSUBTITLES`, not a table of its own.** V19 adds `origin`
    (`FILE` default), `opensubtitles_file_id`, `release`, `imdb_id`, `downloaded_at`. The cues live in the row exactly as for
    an owner's file, so the served version (`ServedSubtitles`), precedence, the comparison, the timing fit and its
    automatic offset, the owner's nudge and "Looks right", the Admin list and the foreign keys (a fit cascades from the
    sidecar) all work on it unchanged. **Alternative: `downloaded_subtitle`** (the original plan): rejected because every
    one of those code paths and the alignment table's key would have been written twice, and Admin would need two row
    shapes. The costs of sharing the table: a made-up `path` (`opensubtitles:<edition id>:<file id>`, unique by
    construction) and `media_file_id` set to the edition's part 1; **`SidecarSync` skips `OPENSUBTITLES` rows entirely**
    (it neither reads, marks missing nor name-matches them; tests in `SidecarSyncDownloadsTest` and the Postgres
    `ForcedDownloadIntegrationTest`), and `SidecarPrecedence.pick` ignores them, so a download can never be mistaken for an
    owner's file.
42. **The shared daily budget (90) has ceilings, not just an order.** `DownloadShares`: a missing subtitle may bring the
    day's total to the whole limit, a replacement to 70% (63 of 90), the second-opinion comparisons of 2d to 50% (45 of 90),
    each a plain `OpenSubtitlesBudget.take(day, ceiling)`, so a lower job can never starve a higher one and no new table is
    needed. Within a download run missing editions come first, then replacements. Integer arithmetic only, rounded down.
    No refund once OpenSubtitles has charged (unchanged from 2d: `ReferenceFetcher` is 2d's code moved so both refreshers
    share it). **Alternatives:** run order only (the checks run first in the chain, so they would eat the day); a separate
    budget row per job (a new table and a race between rows).
43. **A download is timed for the whole edition and stored against part 1.** A single-file OpenSubtitles result on a split
    edition is therefore a whole-edition file placed with `EditionTimeline`, exactly as an owner's whole-edition file.
    **Multi-CD results stay out of scope: phase 4c** (the 2d rule that skips them is unchanged: only single-file
    candidates are ever downloaded or compared). A download counts only for the edition its path names (`Downloads.madeFor`): if an edit
    joins, splits or reassigns the parts it follows its part file, stops counting anywhere (Admin says "made for another
    edition") and is never deleted automatically; the old edition is then due a new download (the dormant row still uses up
    one of its three files only while that edition is the one named). Phase 4c should decide whether to move or remove such rows.
44. **"Proven" and "failed".** Proven: owner-verified, or a current 2d `AGREES`, or a current 3a `ALIGNED` for every file
    served (`SidecarTrust.assess`; the owner's word beats a failure). **Failed is narrower than "not proven": a current 2d
    `CHECK` that nothing proves.** A 3a result that is not `ALIGNED` on its own, `TOO_FEW` and `NO_REFERENCE` leave a file
    *unchecked*, never failed (3a cannot prove a file wrong, only fail to prove it right), so a file with no way of being
    checked keeps playing, as the owner decided. For a split edition's per-part files the verdicts are the edition's (one
    comparison) and every file must be aligned for the timing proof. The embedded forced text track counts as proven.
45. **The playback order** (`ForcedChoice`, pure, a unit test for each row, tie and boundary): 1 a proven owner file; 2 a
    proven download (the lowest id among several proven ones, a proven one before an unchecked one whatever the ids); 3 the
    embedded forced text track; 4 an owner file that has not failed; 5 a failed owner file replaced by a download that has
    not failed; with no such download it keeps playing, **except at 300 lines or more** (`SubtitleComparison.FULL_LINES`),
    where it is full subtitles and plays nothing; 6 with no owner file, an unchecked download. A failed download never
    plays. Two owner files in conflict are no file (the download steps can still apply, but nothing is downloaded because
    of a conflict: it is the owner's to sort out). **The 300-line exception is applied now, not held for 4b's Admin control**
    (the brief's recommendation): showing full subtitles all film long is worse than showing nothing, and the Admin list says
    why (`servedReason`) and "Looks right" overrides it. It is logged at INFO once per download run per edition.
    **Alternative:** wait for 4b, leaving about five films showing full subtitles meanwhile.
46. **Checks are per subject, so replacing a file never erases its failure.** The owner's files keep the edition's one
    `forced_subtitle_check` row, now made for the files' *own* pick (not for whatever finally plays; for an unreplaced file
    the version is the same as before, so no existing row went stale); a download's second opinion is a row of its own in
    the new `forced_download_check` (keyed by the download, cascading with it; shared columns in `SubtitleCheckBase`).
    **Alternative:** one row per edition for what plays. Rejected after tracing it: a download's check would overwrite the
    file's `CHECK`, the file would look unchecked again and win (step 4 beats step 6), and the choice would flip every run.
    A second row per edition would have needed the old `UNIQUE (edition_id)` dropped, and an auto-named constraint cannot
    be dropped portably between H2 and PostgreSQL in an expand-only migration.
47. **Cheapest proof first.** A new download is fitted by 3a (it reads the film's own track, already cached, and costs
    nothing). 3a fits every candidate (the owner's pick and every live download), not only the one that plays, so a download is
    already proven when it is needed. The 2d second opinion (one more download of a different file) is asked for only when
    the fit exists and did not prove it (`CHECK`, `TOO_FEW`, `NO_REFERENCE`), or when the timing check is switched off
    (`cinefilm.subtitles.timing-check.enabled=false`); otherwise it waits for the fit. A download's own file is never in its
    candidate list and `SidecarCheckService.save` refuses a reference with the download's own file id (a file cannot agree
    with itself). Every downloaded or promoted file goes on 2e's ledger, so the second opinion counts it among the (at
    most) three files an edition is ever compared with; stored references are free, so the cap only stops new downloads.
    If neither proof is possible the download stays unchecked and plays.
48. **What is downloaded and when.** Due (`DownloadPlan.kind`, pure): the film's need is shown, it has single-file candidates
    from a search of its current IMDb id, and (a) **missing**: nothing plays from steps 1 to 4 and no download has not
    failed, or (b) **replacement**: the owner's file failed and nothing has replaced it (no live download, no embedded
    track). A live download that is not yet proven means nothing is due: the proof is on its way. At most **three OpenSubtitles
    files are ever made into downloads per edition and IMDb id, the removed ones counted**, so a run of bad candidates cannot
    burn the day's quota for ever. Candidates are `OpenSubtitlesRanking.rankFor(candidates, edition name)` leaving out files that are
    blocked, already downloads, already compared with that edition (judged by 2e: full, another cut, or the promotable
    ones below) and ones stored as unreadable or full (300 lines or more, whatever OpenSubtitles' flag says: **a full file is
    never made a download**). A fetched file that turns out full or unreadable is stored (never fetched again) and the next
    candidate is tried within the same cap.
49. **Promote before downloading (replacements only).** A reference already stored for the edition's ledger costs no
    download: the one the 2e walk kept for the owner's file first, then the others in the edition's order, provided it is a
    single-file candidate, has fewer than 300 lines and its cut fits: `!ReferenceWalk.looksLikeOtherCut(compare(owner's cues,
    reference))`, the walk's own test (so a file that is merely incomplete is promoted and one for another cut is not). A
    missing edition has no cues of the owner's to judge a cut against, so nothing is promoted for it.
50. **Adding is pinned and revalidated.** `ForcedDownloadService.add` takes the `LibraryLock`, runs in one transaction and
    checks again: the film still has the IMDb id, the file is still one of its single-file candidates and not blocked or
    already a download, there is room under the cap, the edition is still due for the same reason and, for a replacement,
    the owner's file still has the version it failed at (a nudge or the owner's "Looks right" meanwhile drops the add). The
    network call and the 2d-style download are outside the lock; a busy lock leaves the stored reference, so the next run
    promotes it free. Nothing in the run holds the lock across a download.
51. **Removal.** `DELETE /api/admin/subtitles/{id}` removes an `OPENSUBTITLES` row only (400 for a file beside the film),
    blocks that file for the edition and IMDb id in `forced_download_block` (kept until the edition goes), and publishes an
    event that starts a download run, which offers the next candidate. "Use my file" is the existing "Looks right" on the owner's
    file (a verified file is proven and wins step 1). The stored reference is kept so the file is never fetched again.
52. **The chain runs from events, each step only when something changed**, so it ends instead of going round forever:
    scan or search -> checks (2d) -> `SidecarChecksFinished` (only if a result was saved) -> downloads ->
    `SubtitleDownloadsFinished` (only if a download was added) -> timing (3a) -> `SidecarTimingFinished` (only if something was
    fitted) -> checks again (a download's second opinion) -> ... Downloads also run after a scan, a search, at start-up,
    after a removal and daily at 06:45 (`ForcedDownloadScheduler`).
53. **Need evidence and `hasForcedSubtitle` come from the same function.** Rule 2 of the need (`SIDECAR`) is still an owner's
    file only (a download exists because need was already shown, and counting it would make it circular);
    `hasForcedSubtitle` and `neededMissing` use `ForcedChoice` through `ForcedResolver` (the player's own rules), evaluated for
    every film with a subtitle of either kind, and an embedded forced track counts as playable there.
54. **"Embedded forced text track" means a stored subtitle stream that is forced and English, `und` or unlabelled in a
    present file of the edition.** The ffprobe parser stores only text codecs, so a picture track is never one. When it wins
    no subtitle is served from here (`forcedSubtitle` is null) and the player picks the track itself.
55. **The IMDb id changing removes the film's downloads** (`FilmRepository.deleteOpenSubtitlesDownloads`, called with the
    existing `clearOpenSubtitles` in `LibraryIngest.applyImdbId`): the only automatic deletion of a download. The resolver
    also ignores a download whose `imdb_id` is not the film's current one, so a race with that cleanup cannot play another
    film's subtitle. Blocks and ledger rows keep their IMDb id and are simply not matched any more.
56. **`ForcedSubtitleDto.version` is a stable 48-bit hash of which rows are served** (source, ids, part, offset and
    `updated_at` of each), still a JSON number: it is the player's cache-buster, so switching between an owner's file and a
    download, a nudge, a changed file or another stitch each change it even when timestamps are equal, and the same choice
    keeps it. 48 bits stay exact in JavaScript. **Alternative:** the latest `updated_at` (the first version of 4a): two
    sources with equal timestamps would share a URL.
57. **`servedReason` is built by `ForcedChoice`**, one short sentence per subtitle of the edition (the owner's files, the
    downloads, files that are missing, empty, unmatched, in conflict or beaten by a whole-edition file), from the same
    decision that picks what plays.

### Judgement calls made building phase 3a (2026-11-12, owner away)

Each: what was chosen, why, and the alternative.

## What the owner wants

Only **forced English** subtitles: the lines a cinema audience saw on screen when characters speak another language.
For every film, now and whenever one is added:

1. **Decide** whether the film needs forced subtitles (it has foreign dialogue in the cinema release).
2. **Find** them: already beside the film, or downloaded.
3. **Check** they are the right lines and in sync with the speech. This includes the existing `.srt` files: over the
   years the owner has edited, merged, hand-made and downloaded them, so none is trusted just because it exists.
4. **Show** them automatically during playback when Subtitles is Off (handover decision 6).

The original discs are gone, so nothing can be ripped again. DVDFab handled subtitles badly; Subscene no longer
exists.

## What the library holds today (inventory, 2026-11-11)

Read-only ffprobe run over all 1,108 files on dockermacmini (`/mnt/workhorse-films`). Raw results were kept in the
planning session's scratchpad, not the repo.

| What | Count | Notes |
|---|---|---|
| Film files (`.m4v`) | 1,108 | 23.976 fps in every file sampled |
| Forced `.srt` files beside a film | 28 | `<stem>.en.forced.srt` (one `.forced.en.srt`); the sample was UTF-8 with a BOM |
| …that match a film by exact name (ignoring case) | 21 | includes `No Time to Die` → `No Time To Die.m4v` |
| …that do not | 7 | 3 LOTR theatrical (`.srt` has no `{edition-Theatrical Cut}` tag, and the film has two editions); 3 LOTR extended (one `.srt` for a film split into `pt1`/`pt2`; Fellowship's also says "The Fellowship"); see phase 1 |
| Files with embedded subtitle tracks | 61 | **All** `dvd_subtitle` (pictures), all `eng`, **none** flagged forced |
| …with a real forced track | 3 | LOTR theatrical cuts (3–41 lines). Each also has a `.srt`, so no OCR is needed |
| …with a full English track | ~25 | Harry Potter, Hunger Games, Maze Runner, Top Gun… (not forced; picture-based) |
| …whose tracks are all empty | ~33 | DVDFab placeholders with no packets (checked directly on `Volcano.m4v`) |

Picture-based tracks stay unsupported (`FfprobeParser.TEXT_SUBTITLES`).

## Design

### Model (Flyway, expand-only)

- **`subtitle_sidecar`**: one row per forced `.srt` file found on disk.
  `id, path (unique, relative to the library root), size_bytes, modified_at, presence (PRESENT | MISSING),
  media_file_id (nullable), owner_attached (bool), cues_text (normalised SRT, ≤ 1 MB), parse_report,
  offset_ms (default 0), sync_status, sync_detail, owner_verified_at, updated_at`.
  The row hangs off the **media file** whose stem it matches, not the edition. Files move between editions when the
  owner edits, joins or Fix Matches editions (`EditionArranger`, `EditionJoin`, `LibraryIngest.merge`), and a sidecar
  then moves with its file for free. A row with no `media_file_id` is **unmatched**.
- **`downloaded_subtitle`** (phase 4, **not built: a download is a `subtitle_sidecar` row with `origin = OPENSUBTITLES`,
  decision 41**): edition-level, because a download is timed for the whole edition.
  `id, edition_id, opensubtitles_file_id, release_name, cues_text, offset_ms, sync_status, sync_detail,
  owner_verified_at, imdb_id (what it was fetched for), updated_at`.
- **`film.forced_need`** (`NEEDED | NOT_NEEDED | UNSURE`, null = not yet decided) and **`forced_need_source`**
  (`AUTO | OWNER`); a scan never overwrites the owner's choice. Phase 2.
- **`film.original_language`, `film.spoken_languages`** from TMDB. `TmdbModels.MovieDetails` does not parse either
  today, so it gains both fields. Phase 2.

Everything is stored in Postgres (a few KB per subtitle). `pg_dump` backs it up, and the read-only media mount is
never written.

**Why not reuse `subtitle_stream`:** those rows are updated in place by ffprobe index on every refresh
(`LibraryIngest.fill` deletes any index the probe did not report). Saved subtitle preferences hold their ids, and the
forced subtitle is never a menu choice (it shows when Subtitles is Off), so it needs no id in that space.

### Which forced subtitle an edition plays (precedence)

**Phase 4a replaced the order below with "trust only proven files" (owner decision 11, decisions 44 and 45):** a proven
owner file, a proven download, the embedded forced text track, an owner file that has not failed, a download that replaces a
failed file, an unchecked download. What follows is the phase 1 order, which still decides *which of the owner's files*
(one whole-edition sidecar, or per-part sidecars stitched) an edition would play.

1. Sidecars: one whole-edition sidecar (no part tag) beats per-part sidecars. Per-part sidecars are stitched onto the
   edition timeline exactly as embedded tracks are today (`EditionTimeline.partStartMs`, `VttShifter.join`); a part
   with none contributes nothing.
2. An embedded **text** track flagged forced (none today; future rips might have one).
3. A download.

Two sidecars competing for the same slot (`X.en.forced.srt` and `X.forced.en.srt`) show as a **conflict** in Admin.
Neither plays until the owner picks one.

### Keeping it right when editions change

- Sidecars follow their media files, so editing, joining and Fix Match need no extra code for them.
- **Downloads:** when `EditionArranger` or `EditionJoin` deletes an edition (JPQL bulk delete,
  `EditionRepository.deleteByIds`), its downloads move to the surviving edition that receives its part 1 before the
  delete, in the same place that handles playback state today. A plain FK would make the delete fail, and
  `ON DELETE CASCADE` would silently throw away downloads that cost quota. Whenever an edition's set of parts changes,
  its forced subtitles' `sync_status` resets to `UNCHECKED` and `owner_verified_at` is cleared (the timeline moved).
- **`LibraryIngest.merge`** carries `forced_need` (owner's choice wins) the way `mergePreferences` carries
  preferences.
- **Fix Match to a different TMDB id:** an `AUTO` need resets to undecided, and downloads fetched for another IMDb id
  are marked `CHECK` ("fetched for a different film").

### API

- `FilmDetail.editions[].forcedSubtitle`: `{ source: "SIDECAR" | "EMBEDDED" | "DOWNLOAD", syncStatus, ownerVerified,
  version } | null`. `version` is the latest `updated_at` among its parts, so a nudge changes the URL.
- `GET /api/editions/{id}/forced-subtitles.vtt?v=<version>` → `text/vtt;charset=UTF-8`: the winning subtitle on the
  edition timeline, offset applied; 404 when there is none. Built per request (small and fast), no disk cache.
- Admin routes per phase. Every action takes a **row id** (sidecar, download, edition), never a path
  (SECURITY.md rule 3).

### Player

- `src/player`: the forced subtitle is a track with its own key (`"forced"`), not a stream. `activeSubtitleKey`
  returns `"forced"` when the choice is Off and the edition has `forcedSubtitle`. That beats the embedded-forced pick,
  because the API already chose the winner. `applySubtitleModes` (`Player.jsx` ~228) covers the extra track.
- `hasSubtitles` (`Player.jsx` ~180) becomes true when the edition has streams **or** a forced subtitle, so the
  captions button and the C key work for a sidecar-only film.
- Wording stays the current code's "forced if needed" (`tracks.js` `subtitleOptions`).
- **iPhone first.** The owner watches on an iPhone, often in native fullscreen on HLS, and the native player may
  ignore out-of-band `<track>` elements (unverified). Phase 1 starts with a spike: one hard-coded VTT on an HLS
  session on the owner's iPhone. If it does not show, the forced subtitle is also published in the HLS master
  playlist as an `EXT-X-MEDIA TYPE=SUBTITLES,LANGUAGE="en",FORCED=YES,AUTOSELECT=YES` rendition
  (`PlaylistWriter.master`), which iOS honours and a future Apple TV app wants anyway. Embedded tracks have the same
  gap today; it gets recorded either way.

### SRT handling (pure Java, no ffmpeg)

`SrtParser`:
- **Encoding:** BOM-detected UTF-8 or UTF-16, else valid UTF-8, else Windows-1252.
- **Line endings and times:** CRLF, LF or CR; comma or dot before the milliseconds.
- **Cues:** blocks with missing or wrong numbers are accepted. Cues out of order are sorted. Overlapping and
  zero-length cues are kept and counted. Malformed blocks are skipped and counted.
- **Markup:** tags are dropped (text kept).
- **Limits:** at most 1 MB and 5,000 cues.

Each file gets a **parse report** shown in Admin ("3 blocks skipped, 2 cues overlap, read as Windows-1252"). A file
with no cues is "unusable". Downloaded and hand-made text is untrusted (SECURITY.md rule 7): cue text is escaped when
written as WebVTT, and Admin renders it as text.

## Phases

Each phase is one PR, branched from the updated `main`, built by Sonnet under the CLAUDE.md process. Phase 1 stands
on its own; after it ships, measure (how many films are still unsure or missing) before committing to phases 3–4.

### Phase 1 — use the forced `.srt` files that already exist, and let the owner check them

**Status: built.** Deviations from the plan above, with the reason:

- **The iPhone/HLS spike was not run** (it needs the owner's phone) and the HLS rendition is not built; Phase 1 serves
  the `<track>` only.
- **`subtitle_sidecar` has no `sync_status` or `sync_detail`** (phase 3 adds them when something sets them). It has
  `whole_edition`, `cue_count`, `last_end_ms` (the end of the last cue, for the "ends after the film" flag without
  re-parsing) and `verified_parts` instead. `owner_verified_at` alone would not reset when editions change, so the
  check is `owner_verified_at` set **and** `verified_parts` equal to the edition's current part ids joined with commas.
  Nothing in `EditionArranger` or `EditionJoin` was touched: a file moved into or out of an edition changes the
  edition's parts, and that is the reset.
- **`media_file_id` is a plain column with a foreign key, not a JPA relation**, so a sidecar follows its file through
  `MediaFileRepository.moveToEdition` with no code of its own. Original files are never deleted (only renditions are),
  so the foreign key cannot block an edit.
- **A conflict in a whole-edition slot stops the edition playing any sidecar** (a whole-edition file is meant to beat
  per-part ones, so falling through would hide the conflict). A conflict in one part's slot leaves only that part with
  none; the other parts still play.
- **A nudge, an attach, and any change to a file on disk clear the owner's check** (the lines or timing the owner
  looked at are no longer the ones that play). `offset_ms` is kept when a file changes on disk.
- **Stitching** reuses `VttShifter.join` on one WebVTT per part (`VttWriter` applies the offset); a first part with no
  file is replaced by an empty header so the later part is still shifted.
- **A cue with no duration** is kept and counted, and shown for one second in the WebVTT (a browser drops a cue that
  ends when it starts).
- **A markup tag** is `<` followed by a letter or `/` and a letter, up to `>`, so "5 < 6" survives; ASS overrides
  (`{\an8}`) are dropped too.
- **"Detach"** (Admin: "Back to automatic") clears the owner's attachment and matches the file by name at once.
- **Admin's film search** uses `GET /api/films?q=` and `GET /api/films/{id}` (no new endpoint); the Player's `at`
  value is an edition time in milliseconds that starts playback exactly there (clamped to the runtime), instead of the
  resume point.
- **`GET /api/admin/subtitles`** lists every file with its `state` (`matched`, `unmatched`, `conflict`); Admin groups
  them as Unmatched, Conflicts, Missing (a `MISSING` file wherever it belongs) and Matched.

Gives forced subtitles to the 28 films that have them, with no external service.

- **Spike first:** the iPhone/HLS check above, which decides between `<track>` and `<track>` + HLS rendition.
- **Scanner:** the existing `visitFile` walk also collects files ending `.en.forced.srt` or `.forced.en.srt`
  (case-insensitive), with the same skips (hidden, `Plex Versions`, `._*`) and resolved through `LibraryRoot`. Nothing
  is collected under a folder the walk could not read (`Found.isUnder`), and the existing "library looks empty, stop"
  guard in `ScanService` covers sidecars too.
- **Sync rows:** an unchanged size and modified time skips re-reading. A file not found is marked `MISSING` (never
  deleted, so a brief SMB outage cannot lose the owner's attachments). A missing file that comes back is
  `PRESENT` again.
- **Matching** runs on every scan, after `groupLibrary`, for every sidecar. It does not wait for the film to change:
  unchanged films are skipped by the scan, and a `.srt` added later must still be found. Owner attachments are never
  re-matched. **Owner decision 2026-11-11: exact names only; anything else is attached in Admin.** A sidecar attaches
  by itself only when its path minus the `.en.forced.srt` / `.forced.en.srt` suffix equals a known original file's
  path minus its video extension, ignoring case, in the same folder (`Taken.en.forced.srt` → `Taken.m4v`,
  `X - pt1.en.forced.srt` → `X - pt1.m4v`, `No Time to Die.en.forced.srt` → `No Time To Die.m4v`). Several matches
  (`.m4v` and `.mkv` with one stem) → unmatched. Whole-edition files for split editions, names without the edition
  tag and near-misses stay **unmatched** for the owner to attach in Admin; automatic matching never sets
  `whole_edition` (only an owner's attach with no part on a split edition does).
- **Admin → Subtitles:**
  - Unmatched files, each with "Attach to…" (an edition and, for a split edition, whole or a part). That covers the
    7 LOTR files without renaming anything on the owner's disk.
  - Conflicts.
  - Each sidecar with its parse report and cues; "Play from here" opens the player 3 s before a cue. Buttons:
    nudge ±0.25 s / ±1 s, "Looks right" (sets `owner_verified_at`), "Reset timing".
  - The owner's own spot-check is the first proof of "right lines, in sync"; phases 2–3 add automatic checks.
- **Check against the database:** a sidecar whose last cue ends after the edition's runtime is flagged
  ("ends after the film: wrong film or cut?").
- **Playback:** route, DTO field and player changes above.
- **Tests:**
  - parser: every edge case above, including cp1252 and UTF-16 samples;
  - matching table: every rule, plus the 28 real file names from the inventory;
  - scan: idempotent, unreadable-folder skip, missing then present;
  - edit and join: sidecars follow files, sync resets;
  - route: 404s and `?v`;
  - player: the forced track shows only when Off, and the captions button works for a sidecar-only film.

### Phase 2 — does a film need forced subtitles, and do the existing files agree with OpenSubtitles?

No source says outright whether a film needs them. CineFilm combines signals and the owner settles the rest:

| Signal | Result |
|---|---|
| Owner set it | That, always |
| A forced subtitle exists (sidecar, embedded text forced track) | `NEEDED` |
| OpenSubtitles has English "foreign parts only" subtitles for the film's IMDb id | `NEEDED` (that flag is set by uploaders, so not proof; Admin shows the evidence) |
| TMDB lists English as the only spoken language | `NOT_NEEDED` |
| TMDB lists other spoken languages, nothing found | `UNSURE` |
| TMDB `original_language` is not English | `UNSURE`, marked "foreign-language film" (needs full subtitles: out of scope) |

- **TMDB:** fields stored from the movie details already fetched; existing films backfilled by a rate-limited job
  like the ratings refresh.
- **OpenSubtitles:**
  - Search runs after each scan for undecided or `UNSURE` films and is retried monthly, since subtitles get uploaded
    later.
  - Searches do not use download quota (to be confirmed).
  - Every API detail in this plan is **from memory and confirmed at the start of this phase**: `api.opensubtitles.com`
    v1, `Api-Key` and `User-Agent` headers, `/subtitles?imdb_id=&languages=en&foreign_parts_only=only`, `/login` for a
    token, `/download` returning a temporary link, quotas.
- **Checking existing sidecars against OpenSubtitles:**
  - One download per film within quota: the best "foreign parts only" match, compared with the sidecar.
  - The comparison looks at cue counts, and at the share of the sidecar's cues that have a cue within 2 s in the
    other once a single best offset is applied.
  - Under 70% overlap, or a cue count off by more than half, marks the sidecar `CHECK` with the reason
    ("OpenSubtitles has 41 lines, this file has 12"). This catches incomplete files and files for a different cut.
  - The best offset found is also a timing hint: shown in Admin as "OpenSubtitles' file is 1.2 s later", never applied
    automatically.
- **Admin → Subtitles:**
  - Filters: Needed and missing, Unsure, Needs checking, Verified.
  - Needed / Not needed buttons per film.
- **Security** (SECURITY.md is updated in this PR: a new outbound service):
  - The API key, username, password and session token are server-side secrets (rule 5).
  - The download link comes from a response, so it is fetched GET-only, from an allowlist of OpenSubtitles hosts,
    without following redirects off them, with a size cap enforced while reading (before any decompression).

#### Phase 2 build: four small PRs (planned 2026-11-12)

**API facts checked on 2026-11-12** (one live search from dockermacmini with the owner's key):
- `GET https://api.opensubtitles.com/api/v1/subtitles?foreign_parts_only=only&imdb_id=936501&languages=en` with
  `Api-Key` and `User-Agent` headers answers 200 **without logging in**. Parameters are sent in alphabetical order and
  the IMDb id without `tt` or leading zeros (the API redirects otherwise).
- Rate limit: 5 requests a second (`ratelimit-*` headers). Responses carry `cache-control: max-age=86400`.
- Each result: `id`, and `attributes` with `foreign_parts_only`, `fps`, `download_count`, `from_trusted`,
  `hearing_impaired`, `machine_translated`, `ai_translated`, `release`, `upload_date`, `uploader.rank`,
  `feature_details.imdb_id`, and `files[].file_id` (what `/download` takes). Paged: `total_count`, `per_page` 50.
- *Taken* (tt0936501) returns 7 results, 23.976 to 25 fps. Login and `/download` are still unchecked (phase 2d).

**Model change from the design above:** the need is **worked out when read**, not stored. Only its inputs are stored:
the owner's choice, TMDB's languages and (2c) the OpenSubtitles evidence. A stored verdict would go stale whenever a
sidecar was attached or moved. So `film` gains `forced_need_owner` (`NEEDED | NOT_NEEDED`, null = automatic) instead
of `forced_need` + `forced_need_source`.

**The rule** (`ForcedNeed.decide`, pure, first match wins):

| # | Input | Need | Reason |
|---|---|---|---|
| 1 | Owner chose | that | `OWNER` |
| 2 | A `PRESENT` sidecar with at least one cue is attached to a `PRESENT` file of the film (a file with no cues is no evidence; a conflicting pair still is, though playback serves neither) | `NEEDED` | `SIDECAR` |
| 3 | A file of the film has an embedded forced track in English (`eng`, `en`) or with no language (null, `und`) | `NEEDED` | `EMBEDDED_FORCED` (pictures: cannot be shown yet) |
| 4 | (2c) OpenSubtitles has English foreign-parts-only results | `NEEDED` | `OPENSUBTITLES` |
| 5 | TMDB not asked yet, or no TMDB match | `UNKNOWN` | `NO_LANGUAGES` |
| 6 | `original_language` is not `en` | `UNSURE` | `FOREIGN_LANGUAGE_FILM` (needs full subtitles: out of scope) |
| 7 | Spoken languages are exactly English | `NOT_NEEDED` | `ENGLISH_ONLY` |
| 8 | Anything else (other spoken languages, or none listed) | `UNSURE` | `OTHER_LANGUAGES` / `NO_SPOKEN_LANGUAGES` |

TMDB's `xx` ("no language") is ignored in the spoken list and as the original language (a film whose original language is `xx` falls through to the spoken-language rules, not rule 6). `hasForcedSubtitle` (the Admin "has a forced subtitle"
filter, and `neededMissing`) is stricter than rule 2: at least one edition has a forced subtitle that playback would
serve (`ForcedSubtitleService.forEditions`, so `SidecarPrecedence` decides: empty files and conflicting files are not
served). Only the films with rule-2 evidence are evaluated.

**2a — backend: TMDB languages, the rule, the owner's choice** (no UI)
- `V14__forced_need.sql` (expand-only): `film.original_language varchar(8)`, `film.spoken_languages varchar(200)`
  (ISO 639-1 codes, lower case, comma-separated, TMDB's order, duplicates dropped, cut at a whole code to fit; empty string = TMDB lists none),
  `film.languages_fetched_at timestamptz` (null = not asked yet), `film.forced_need_owner varchar(16)` with a check
  constraint.
- `TmdbModels.MovieDetails` gains `originalLanguage` and `spokenLanguages` (`spoken_languages[].iso_639_1`). Keep the
  existing shorter constructors working. Codes are validated (`^[a-z]{2,3}$`) and anything else is dropped: TMDB text is
  untrusted.
- `LibraryIngest.applyMovie` stores both and stamps `languages_fetched_at` from the injected `Clock`. Every scan,
  Plex import and Fix match then keeps them current.
- **Backfill** (`LanguagesRefresher`, modelled on `RatingsRefresher`): films with a `tmdb_id` and no
  `languages_fetched_at`, one run at a time on its own executor, started after `ScanFinished`, `PlexImportFinished` and
  at start-up. Uses `TmdbApi.movie(id)` (paced by `TmdbClient`). Saves with one targeted update
  `... where id = :id and tmdb_id = :tmdbId` (a film rematched meanwhile is left for the next run). A TMDB outage stops
  the run; nothing is stamped, so it is retried. A film TMDB no longer has is stamped with null languages, so it is
  not asked again every scan (it shows `UNKNOWN`). No periodic refresh: languages do not change.
- **Read:** `GET /api/admin/forced-subtitles/films` → every film (sorted by sort title):
  `{filmId, title, year, need, reason, ownerChoice, originalLanguage, spokenLanguages[], hasForcedSubtitle}` and
  `counts {needed, neededMissing, unsure, unknown, notNeeded}`. Built from **three queries** (films with language
  columns; film ids with an attached present sidecar; film ids with an embedded English or language-less forced
  stream), plus one bounded fetch of the editions and files of the films with sidecar evidence and playback's own
  sidecar query (for `hasForcedSubtitle`), never one query per film (a test counts statements for 1 and 3 evidence
  films). 1,077 films is about 150 KB; no paging.
- **Write:** `PUT /api/admin/films/{id}/forced-need` `{choice: "NEEDED" | "NOT_NEEDED" | null}` → that film's row.
  Under `LibraryLock` (`LibraryLock.SUBTITLE`) in a `TransactionTemplate`, lock released after commit (409 while a
  scan holds it, 404 for an unknown film, 400 for another value), as in `SubtitleAdminService`.
- **Tests:** the rule table row by row, including `xx`, upper-case codes, empty lists and every precedence pair;
  TMDB parsing (missing, empty, malformed fields); `applyMovie` stamping; the refresher (outage stops it, a rematch
  during the call is not overwritten, a vanished film is stamped, one run at a time); the three-query read
  (`@DataJpaTest`, including a `MISSING` sidecar and an unmatched sidecar not counting); the controller contract;
  the `V14` migration in the integration suite. `foundation-plan.md` API section updated.

**2b — frontend: Admin → Subtitles, "Which films need them"**
- A section above the file list. Filters (radio chips): **Needed, none yet** (default), **Unsure**, **Unknown**,
  **Not needed**, **All**, each with its count.
- Each row: title (year), the need and a plain reason ("Forced subtitle file attached", "Forced track inside the file
  (pictures: can't be shown yet)", "Not in English originally: needs full subtitles", "English only (TMDB)", "Also
  spoken: French, Russian (TMDB)", "TMDB hasn't been asked yet"), and three buttons: **Needed**, **Not needed**,
  **Automatic** (the current one pressed, `aria-pressed`). The owner's choice is labelled "Your choice".
- Language names from `Intl.DisplayNames` (fallback: the code). Data and logic in `frontend/src/api` and
  `frontend/src/state`, no JSX there.
- A change applies the returned row and refreshes the counts. One write per film at a time, never aborted (the
  server may still process an aborted one, so two writes could land out of order, and the second would meet the
  library lock). When Admin closes, the write in flight still finishes, its result is ignored and no waiting click is
  sent. A newer click on a film that is saving waits and replaces any earlier waiting click, and is sent
  when the write in flight settles, even if that one failed. Only the last write's answer is shown and only its
  failure is reported beside the row, which stays at the last answer the server gave. A refresh error shows with Try
  again even while the old list is shown, and a list read that began before a save cannot undo it.
- Shown 100 rows at a time with "Show more" (the All filter has 1,077 rows).

**2c — backend: OpenSubtitles search (evidence for rule 4)**
- `OpenSubtitlesClient` (search only; no login, no download): key from `OPENSUBTITLES_API_KEY`, `User-Agent`
  `CineFilm v<version>`, never logged. Paced under 5 a second, retries 429 with `Retry-After`, a timeout, and a
  response-size cap. Off when the key is absent (as OMDb is).
- `film.os_forced_count int`, `film.os_searched_at timestamptz`, `film.os_best json` (the top-ranked result's id,
  file id, release, fps, download count; ≤ 2 KB). Searched for films with an IMDb id whose need is not settled by rules
  1–3, never searched before or searched more than 30 days ago with no result, after each scan and daily.
- Rule 4 switches on. Admin rows show "OpenSubtitles: 7 foreign-parts-only files" (2c includes that small frontend
  change, or it rides with 2d's).
- `SECURITY.md` gains OpenSubtitles as an outbound service.
- **Built (2026-11-12)**, with these differences from the plan above:
  - Package `gregochr.cinefilm.opensubtitles`: `OpenSubtitlesApi`/`Client`/`Config`, `OpenSubtitlesParser` and
    `OpenSubtitlesRanking` (pure), `OpenSubtitlesRefresher` (same `pending` re-run flag as `LanguagesRefresher`, its own
    `openSubtitlesExecutor`) and `OpenSubtitlesScheduler` (06:00, off with `cinefilm.scan.schedule=false`).
    `RequestPacer` (tmdb) became public and takes an interval, so the client uses it at 250 ms.
  - **Every film with an IMDb id is searched**, not only those rules 1–3 leave open: phase 2d needs `os_best` for
    films that already have sidecars, and a search costs no download quota. Due: never searched, `os_searched_imdb_id`
    differs from `imdb_id`, or `os_forced_count = 0` and searched more than 30 days ago (never searched first, then the
    oldest).
  - `os_best` is `varchar(2000)` holding JSON `{id, fileId, release, fps, downloadCount, fromTrusted,
    hearingImpaired}` (not a `json` column); `film.os_searched_imdb_id` was added. A count only counts while
    `os_searched_imdb_id = imdb_id` (rule 4 and the DTO ignore a stale one), and a change of IMDb id resets all four
    columns (`Film.setImdbId` in the entity, `FilmRepository.clearOpenSubtitles` on the row, flushed after the id).
  - The reply is read from `attributes` (`files[0].file_id`, `feature_details.imdb_id`); `id` may be a number or a
    digit string (OpenSubtitles writes a string). A result with no usable id or file id is dropped. A reply that is not
    JSON or has no `data` list is an outage, never "no results". Only the first page (50) is read.
  - The DTO gained `openSubtitlesForcedCount` and `openSubtitlesSearchedAt`; `ScanStatus` gained
    `openSubtitlesLastSearchedAt`, `openSubtitlesRunning` and `openSubtitlesEnabled`. The Admin wording ("7
    foreign-parts-only files") is a frontend change for a later PR.
  - The User-Agent version is `cinefilm.version`, which is `${APP_VERSION:1}` (the image's build argument, so a release
    sends `CineFilm v0.4.1`; a dev build or an unusable value sends `CineFilm v1`).
  - The connect and read timeouts are 10 s each; the read timeout covers the wait for the response headers, as for
    TMDB and OMDb (the body read is capped in size, not in time).

**2d — the cross-check of existing sidecars: built (2026-11-12).** Backend only; the Admin display is a separate PR.
Built from the documented API: the live login/download test was denied (see "Decisions made while the owner was away").
- **Client** (`OpenSubtitlesClient`, interface `OpenSubtitlesApi.download(fileId)`): `POST /api/v1/login` (token kept in
  memory, one re-login after a 401, only when the call did not just log in), `POST /api/v1/download`, then a GET of the
  link: https only, host below `opensubtitles.com` (checked before any request), no redirects, 2 MB read from the wire,
  no key or token sent to it. 406, or a reply with no link whose message says limit or quota, is `OpenSubtitlesQuotaException`
  (with `reset_time_utc`); everything else unexpected is an outage. `base_url` is used only when it is a host below
  `.opensubtitles.com`. Settings: `cinefilm.opensubtitles.username` / `.password` (blank = downloads off, search still on),
  `.daily-download-limit=90`.
- **Budget** (`opensubtitles_budget`, a table of its own rather than generalising `RatingsBudget`, because it also keeps
  `blocked_until`): `take` / `refund` / `exhaust` per UTC day, one conditional statement each.
- **Tables** (`V16`): `opensubtitles_reference` (unique per film and file id, cues as normalised SRT, `cue_count` 0 =
  unusable), `forced_subtitle_check` (one row per edition, cascade on the edition and the reference), `opensubtitles_budget`.
- **Served version** (`ServedSubtitle`, `ServedSubtitles`): `p<part file ids>|<sidecar id>@<start>/<offset>/<hash>,...`,
  each file's own state in serve order, the hash being the first 16 hex characters of the SHA-256 of its cues (no
  timestamp, so changes in the same millisecond cannot hide), so an attach, move, nudge, changed file, changed part list
  or changed part duration makes a check stale (`forced_subtitle_check.served_version` is 1,000 characters).
  `ForcedSubtitleService.served` gives the cues playback serves (same pick, offsets applied by `VttWriter.shift`, parts
  stitched with `EditionTimeline`).
- **Comparison** (`SubtitleComparison`, pure): candidate offsets from start-time differences within +-10 min, bucketed to
  100 ms (half rounds up), the 500 most frequent tried; score = matched share within 2 s (inclusive); best = most matches,
  then least total distance, then smallest |offset|, then lower. `CHECK` when share under 70%, served under half or over
  twice the reference; reasons as in the changelog; the offset hint at 500 ms or more.
- **Refresher** (`SidecarCheckRefresher`, executor `sidecarCheckExecutor`, `SidecarCheckScheduler` 06:30): same `pending`
  re-run as the search; triggers: `OpenSubtitlesSearchFinished` (new event, published when a search pass ends), `ScanFinished`,
  start-up, daily. Due (`SidecarCheckService.due`): the film's best result is from a search of its current IMDb id and is a
  single file, playback serves the edition a forced sidecar, and no check matches the served version and the film's IMDb
  id; owner-unverified first. Per edition: stored reference, else download within budget (stored at once), compare, save
  only if the served version is still the one compared (else dropped). Budget spent or quota stops the run; an outage stops
  it with nothing stored; one edition failing is counted and skipped.
- **API:** `check` on each `SidecarDto` (for the files an edition serves); `foundation-plan.md` updated.
- **Deviations / doubts:** no `checkVerdict` on need rows; the "pending set after the last check" tail of the worker loop is
  not unit-testable (one branch uncovered, as in the search refresher); the CI-only Postgres test
  (`SidecarCheckIntegrationTest`) was not run locally (no Docker).

**2e — robust against bad reference files: built (2026-11-12).** Backend only, no response shape changes (`check.reasons`
is still a list of sentences; the Admin file row shows them as sent). Decisions 34 to 40 above.
- **Evidence from the first live run** (20 editions, 11 `AGREES`, 9 `CHECK`). Real catches, left as `CHECK`: Amsterdam
  (2,431 lines against 43) and Olympus Has Fallen (1,300 against 29): the owner's "forced" file is full subtitles.
  Mislabelled reference (full subtitles flagged foreign-parts-only): Spectre 1,362 against 39 at offset 0, Taken 3 1,456
  against 13 at 0, The Revenant 834 against 138 at -100 ms: now `AGREES` by the subset check. Probably another cut or
  release: RotK (likely extended; 3 against 12 lines, 0%), Dances with Wolves (19%, +54.5 s), Bourne Supremacy (53 against
  12, -114 s), Taken 2 (39 against 16, 15%, -339 s): the next candidate is tried. Wording bug: Taken 2 said "probably
  full subtitles" about 39 lines.
- **Search** (`OpenSubtitlesParser`, `OpenSubtitlesRanking.candidates`, `OpenSubtitlesRefresher`): `fileName` read from
  `files[0].file_name`; `OpenSubtitlesSearch.candidates`; written as `film.os_candidates` with `os_best` in the one
  conditional statement (`saveOpenSubtitles`).
- **Comparison** (`SubtitleComparison`): `Result` gains `matched` (exact count) and `fullReference`; the full path and
  the wording rule above.
- **Walk** (`ReferenceWalk`, pure; `SidecarCheckRefresher.walk`): the decisions (move on, keep, reasons) and the loop with
  the ledger. `SidecarCheckService.due` carries each edition's ordered candidates and ledger; `markTried` records a file;
  `save` checks the reference is still a candidate and writes `rules_version` and `best_file_id`.
- **Logging (INFO, per edition):** which file was used and how many were compared ("Edition 10: used OpenSubtitles file
  5012 (compared 3), CHECK"), and why a candidate was skipped (full subtitles / another cut, trying the next; could not
  be read; already tried three). Only ids and counts: no key, token or link is ever logged.
- **Doubts:** (1) the 30 s / 30% / 8-line numbers are chosen from nine live results, not tuned; check them against the
  next run's log. (2) The ledger counts a reused stored reference, so an edition whose first three candidates were all
  stored for other editions never reaches a fourth (a deliberate, strict reading of "3 per edition"). (3) A check saved
  from a walk that ran out of candidates is not re-walked when a later search adds a better one, unless the best file
  changes. (4) The Postgres-only tests (`SidecarCheckIntegrationTest`: V18 columns, ledger, candidates JSON) did not run
  locally (no Docker).

### Phase 3 — automatic timing check

- **Reference first, speech second:**
  - About 25 films have a full English track (pictures). Their cue **times** can be read without OCR (ffprobe packet
    times), turned into a timing-only reference, and the forced file aligned to it subtitle-to-subtitle. Hundreds of
    reference cues make this far more reliable than speech.
  - Where there is no full track, a downloaded full English `.srt` from OpenSubtitles (phase 2's search, one more
    download) can be the reference.
  - Speech alignment is the last resort: it cannot tell foreign speech from English, and with 3–41 cues it can lock
    on to the wrong lines.
- **Tool:** `alass` (subtitle-to-subtitle and subtitle-to-audio alignment).
  - To verify at the start of the phase: its arguments, `--no-split`, frame-rate guessing, and whether it accepts an
    audio-only file.
  - It runs on x86_64 (dockermacmini is x86_64); the release is pinned by version and **SHA-256** in the Dockerfile
    (rule 8).
  - CineFilm extracts the audio itself with ffmpeg (argument list, `-protocol_whitelist file`, rule 4) to a mono
    8 kHz WAV in the cache. Parts are concatenated for a split edition, and the WAV is deleted afterwards. alass never
    runs ffmpeg on the media.
  - alass writes a corrected file, so CineFilm fits one offset (no speed: every file is 23.976) from input versus
    output cue times.
- **Acceptance:** `ALIGNED` when the fitted offset is within ±10 s and the residual is small; otherwise `CHECK`. The
  original cue times are always kept, and only `offset_ms` changes, so "Reset timing" undoes it. Owner-verified
  subtitles are never re-aligned.
- **Running it:** one job at a time, `nice`, a 10-minute timeout, and only while no HLS session is encoding
  (playback comes first).

#### Phase 3a build (planned 2026-11-12)

**Built (2026-11-12). Backend only; the Admin display ("Timing set automatically", the alignment verdict and reasons) is the
next PR.** Package `gregochr.cinefilm.subtitle`; migration `V17__forced_timing_check.sql` (expand-only).

- **Why.** About 25 films carry a full English subtitle track inside the file, often as pictures (PGS `hdmv_pgs_subtitle`,
  `dvd_subtitle`). Cue *times* can be read from packet timestamps without OCR. A full track contains every line, the
  translations of the foreign dialogue included, so the forced cues are a subset of it in time: a far stronger timing
  reference than OpenSubtitles or speech.
- **Reading** (`EmbeddedCueReader`, `EmbeddedStreams`, `SubtitlePackets`). One quick `ffprobe` lists the subtitle streams; one
  pass over the subtitle packets (`-protocol_whitelist file`, an argument list, the file last and absolute, `nice`, a 20
  minute timeout, the process killed on timeout) gives every candidate's cue starts. Candidates are English tracks that are
  not forced (not flagged, and not titled forced, foreign or signs). Choice: text over picture, then flagged default or titled
  full/SDH/CC, then most cues, then lowest index; a track under 100 cues is not a full track. PGS: packets under 50 bytes are
  clear packets and dropped; DVD and text: every packet is a cue. Starts are sorted and merged within 50 ms.
- **Cache** (`embedded_cue_starts`, `EmbeddedCueStarts`): one row per media file, keyed by its fingerprint (size and
  modification time); the starts as gap text; `NO_REFERENCE` is stored too (with the stream count and the reason) so it is not
  re-read; a failed read is not stored. A changed file is read again.
- **Fit** (`TimingFit`, pure): candidate offsets from start differences within +-60 s (100 ms buckets, the 40 most frequent
  scored, then refined at 10 ms around the best); a served cue matches a reference start within +-300 ms; best = most matched,
  then least total distance, then smallest |offset|. `ALIGNED`: at least 8 cues, share at least 0.80, |offset| at most 10 s,
  median residual at most 120 ms, and a share that beats the best share at four wrong offsets (+3.7, -4.3, +7.1, -9.3 s) by at least 0.4. Under 8 cues is `TOO_FEW`;
  no track is `NO_REFERENCE`; anything else `CHECK` with plain reasons ("Only 4 of 12 lines line up with the film's English
  track", "Best fit is more than 10 s out (14.0 s later)", "The lines line up only loosely (typically 250 ms off)", "The
  film's English track has so many lines that this fit could be chance").
- **Applying** (`SidecarTimingService.save`, under `LibraryLock`, revalidated): only for `ALIGNED`, only when the fit differs
  from the current offset by 100 ms or more, never for an owner-verified file (as the edition's parts are now), never when
  `offset_source` is `OWNER` (the owner's `PUT`, a reset to 0 included, sets it). It sets `offset_ms`, `offset_source = AUTO`
  and `updated_at` (a player holding the cues asks again) and logs at INFO (film, sidecar, old to new offset, share). An
  owner-verified file is still checked and its verdict stored.
- **Storage.** `forced_subtitle_alignment` (one row per sidecar, cascade on the sidecar, CHECK on the verdict; keyed by
  `sidecar_identity` and `reference_key`, neither including the offset, so an apply never makes a check stale);
  `embedded_cue_starts` (cascade on the media file); `subtitle_sidecar.offset_source` (CHECK `OWNER`/`AUTO`).
- **Running** (`SidecarTimingRefresher`, `subtitle-timing` executor, `SidecarTimingScheduler`): same `running` and `pending`
  flags as the other refreshers; triggers: `ScanFinished`, start-up, daily 07:00 and an hourly nudge that only starts a run
  when the last one waited; off with `cinefilm.scan.schedule=false` (the scheduler) or
  `cinefilm.subtitles.timing-check.enabled=false` (the check). One film at a time: due films are those with a served sidecar
  that has no read or no current fit, owner-unverified first, then never-checked, then by film. Reads run with no lock; a
  scan or edit holding the library lock, or any encoding HLS session (`SessionManager.isEncoding()`), stops the run to be
  tried again. No owner event triggers it: an attach or nudge is picked up by the next run.
- **API.** `SidecarDto` gains `offsetSource` and `alignment` (`foundation-plan.md` updated). `SidecarTrust.isProven` (owner
  verified, or 2d `AGREES`, or 3a `ALIGNED`, each only while current) is ready for phase 4; no playback change here.
- **Tests.** Unit: `TimingFitTest` (exact, +-0.3 s, 2 s, 9.9 s, over 10 s, dense references, jitter, subsets, too few,
  split-edition parts), `EmbeddedStreamsTest`, `SubtitlePacketsTest` (PGS fixture `ffprobe/pgs-packets.txt`),
  `EmbeddedCueReaderTest` (commands, failures), `TimingKeysTest` (fingerprint invalidation), `SidecarTimingServiceTest` (due
  order, apply rules, no endless re-check, save revalidation), `SidecarTimingRefresherTest` (concurrency, pending re-run,
  transcode-busy skip, lock busy, backoff, disabled), `SubtitleAdminTimingTest` (the owner's `PUT` sets `OWNER`),
  `SidecarTrustTest`, `SessionManagerEncodingTest`. Integration (Testcontainers): `SidecarTimingIntegrationTest` (migration
  constraints, cascades, a targeted save, the DTO JSON). Real tool: `EmbeddedCueReaderRealToolsTest` generates a Matroska clip
  with a full and a forced `subrip` track, reads it with the real reader and fits a shifted forced file: `ALIGNED` with the
  right offset. ffmpeg cannot make picture subtitles from text, so PGS is covered by the packet fixture only.

### Phase 4 — download what is missing

**Split (2026-11-12):** 4a is the backend (downloads, replacements, proof, playback precedence, the removal endpoint, the API
fields): built, see "Phase 4a build" below. **4b** is the Admin display (a download's row, "Needs checking", the candidate
list, remove and "use my file" buttons, `servedReason`). **4c** is multi-part (multi-CD) results.

- For each `NEEDED` edition with no forced subtitle, search OpenSubtitles (IMDb id, English, foreign parts only).
  Ranking:
  1. frame rate 23.976;
  2. a release name close to the edition (theatrical or extended);
  3. trusted uploader;
  4. download count.

  The hash match is skipped: DVDFab re-encodes will not match.
- Downloads go through a queue within the daily quota, and the rest carries to the next day. Each is stored and
  aligned at once (phase 3). A `CHECK` result tries the next candidate (at most 3). Admin shows the candidates, so the
  owner can pick another or remove a download.
- **Trust only proven `.srt` files (owner decision 2026-11-12; replaces "a sidecar always wins").** A sidecar plays
  while it is owner-verified, `AGREES` (2d) or `ALIGNED` (phase 3), or cannot be checked at all (then Admin lists it as
  "Unchecked"). One that fails both checks and is not owner-verified is replaced for playback by an automatically
  checked download, which plays at once with no owner step. A `.srt` the owner drops in later is checked like the others.
- **Multi-CD results:** a result split over several CDs can be used when its CD count equals the edition's part count,
  each CD placed at its part's start with `EditionTimeline`. Otherwise prefer a single-file result; 2d already skips an
  edition whose best result is multi-file.
- **Every new film** then runs the whole chain on the scan that finds it:
  1. match a sidecar (phase 1);
  2. decide the need and cross-check (phase 2);
  3. download if needed (phase 4);
  4. align (phase 3);
  5. wait in Admin for the owner's spot-check.

#### Phase 4a build (2026-11-12)

**Built. Backend only; the Admin display is 4b.** Migration `V19__forced_downloads.sql` (expand-only: five columns with
defaults and a check on `subtitle_sidecar`, two new tables). Decisions 41 to 57 above.

- **Pure:** `ForcedChoice` (the playback order, the 300-line exception, ties and every reason sentence), `SidecarTrust.assess`
  (`PROVEN` / `UNCHECKED` / `FAILED` and what proved it), `DownloadPlan` (what is due, what to promote, what to fetch, the cap),
  `DownloadShares` (the budget ceilings).
- **Database side:** `ForcedResolver` gathers the owner's files, the live downloads, the checks and fits that still describe
  them and the embedded track for some editions in a fixed number of queries and asks `ForcedChoice`; `ForcedSubtitleService`
  (the film detail, the WebVTT route, `served`, `playableEditionIds`), `SubtitleAdminService.list` and the need list all use it.
  `SidecarCheckService` makes a download due for its second opinion and saves it as a `ForcedDownloadCheck`;
  `SidecarTimingService` fits downloads too; `ForcedDownloadService` decides what is due and adds a download under the library
  lock after revalidating.
- **Background:** `ForcedDownloadRefresher` (executor `forcedDownloadExecutor`, `ForcedDownloadScheduler` at 06:45) and
  `ReferenceFetcher` (2d's download, store and budget code moved out of `SidecarCheckRefresher` so both share it). Off while
  downloads are off. Logs ids and counts only, never a key, token or link.
- **API:** `SidecarDto` gains `origin`, `release`, `openSubtitlesFileId` and `servedReason`; `forcedSubtitle.source` can be
  `DOWNLOAD`; `DELETE /api/admin/subtitles/{id}`. `foundation-plan.md` is updated.
- **Tests.** Unit: `ForcedChoiceTest`, `SidecarTrustTest`, `DownloadPlanTest`, `DownloadSharesTest`, `ForcedResolverUnitTest`,
  `ForcedDownloadServiceUnitTest`, `ForcedDownloadRefresherTest` (budget priority, no refund, promotion before download,
  the cap, every stop, the event), `ForcedDownloadSchedulerTest`, `SidecarCheckRefresherTest` and `SidecarTimingRefresherTest`
  (event chaining), `SubtitleAdminServiceUnitTest` (removal, attach refused). H2: `ForcedResolverTest`,
  `ForcedDownloadServiceTest`, `SidecarCheckDownloadsTest`, `SidecarSyncDownloadsTest`, `SubtitleAdminDownloadsTest`,
  `LibraryIngestDownloadsTest`, `ForcedDownloadApiTest`. Controller: `SubtitleAdminControllerTest`. Integration
  (Testcontainers, CI only): `ForcedDownloadIntegrationTest` (V19, sync with downloads present, removal, the DTO JSON).
- **Doubts.** (1) The first real run has not seen a real download beyond 2d's: watch the log lines "Edition N: downloaded
  OpenSubtitles file F for a missing|replacement subtitle" and "promoted". (2) Decision 43: a download belongs to the edition its path names, and goes dormant when an edit moves its file. (4) The
  download run reads the whole need list (`ForcedNeedService.list`, three queries and one fetch) once per run, about 1,000
  films: cheap, but not free. (5) The Postgres-only tests (`ForcedDownloadIntegrationTest` and the other integration tests)
  did not run locally (no Docker). (6) The thresholds 70% and 50% of the budget are chosen, not tuned.

### Phase 5 — full English subtitles for foreign-language films

Forced subtitles cover English-language films with some foreign dialogue. A film made in another language needs
**full** English subtitles from start to finish, which phases 1–4 deliberately leave out (rule 6 marks it
`FOREIGN_LANGUAGE_FILM`). Plex handles these today; CineFilm must too before the owner can leave Plex.

- **Which films:** rule 6's films (TMDB `original_language` not English), plus any the owner marks. Admin lists them
  with the same filters as phase 2b ("Full subtitles: needed, none yet").
- **The audio in the owner's copy decides, not only TMDB.** A survey on 2026-11-12 found 19 of 1,074 films with a
  non-English original language, and several are English for the owner:
  - *Léon* is mostly English (TMDB says French because it is a French production);
  - the three Leone westerns, *Crouching Tiger* and *Ballerina* have English-labelled audio;
  - the animated films are probably English dubs.

  So a film whose playing audio track is English is not a foreign-language film. Most files carry no audio language,
  so those stay `UNSURE` for the owner's one tap. The likely real list: *Oldboy*, the two Millennium films,
  *Martyrs*, *The Assassin*, the Bruce Lee films, *33 Days*.
- **Scale of the forced-subtitle `UNSURE` pile:** the same survey found 378 English-language films that list other
  spoken languages. Phase 2c's OpenSubtitles evidence has to settle most of these, so the owner isn't left with 378
  taps.
- **Find:**
  - Full English `.srt` files beside the film (`<stem>.en.srt`, `<stem>.eng.srt`) are matched as in phase 1, by exact
    name.
  - Otherwise OpenSubtitles is searched without `foreign_parts_only` and the download goes through phase 4's queue and
    ranking (not hearing-impaired by default).
- **Check:** phase 3's alignment. Hundreds of lines make speech alignment far more reliable than for forced files.
  Phase 2d's comparison against a second OpenSubtitles file catches the wrong cut.
- **Play:** a normal choice in the player's Subtitles menu ("English"), on by default for these films, never shown
  when Subtitles is Off. The forced track still plays when it is Off. The saved subtitle preference keeps working.
- **Model:** phase 4's `downloaded_subtitle` and phase 1's `subtitle_sidecar` gain a `kind` (`FORCED | FULL`) rather
  than new tables.
- **To decide at the start of the phase:** whether full subtitles are also wanted for English-language films (for
  hard-to-hear dialogue). Out of scope unless the owner asks.

### Phase 6 — subtitles stored as pictures inside the file

About 60 rips carry their subtitle tracks as pictures (`dvd_subtitle`, DVD-style bitmaps), not text: about 25 full
English tracks, 3 real forced tracks (the LOTR theatrical cuts) and about 33 empty placeholders. Browsers cannot
display them; Plex burns them into the video while transcoding.

Two ways, to be settled by a spike at the start of the phase:
- **A. Convert once to text (recommended).** OCR each picture track to `.srt` (ffmpeg extracts the bitmaps; an OCR
  tool turns them into text), store it like a downloaded subtitle, and check it with phases 2d and 3.
  - Pros: it plays everywhere, including iPhone native fullscreen and direct play, at no cost per playback.
  - The OCR output is also the timing reference phase 3 wants.
  - Risk: OCR errors (italics, `l`/`I`). Mitigation: comparison with an OpenSubtitles file and the owner's spot-check
    in Admin.
- **B. Burn in during transcoding (fallback).** An ffmpeg overlay in the HLS encode.
  - Only works when transcoding, so it forces a transcode even when the file could direct-play.
  - It costs CPU on every playback and cannot be switched off mid-stream.

Empty placeholder tracks are detected (no packets, or no bitmaps after OCR) and hidden. Phase 6 may shrink a lot if
phases 4–5 find text versions for most of these films: measure first.

## Owner decisions needed

1. ~~OpenSubtitles account and API key~~ **Done (2026-11-11).** `OPENSUBTITLES_API_KEY`, `OPENSUBTITLES_USERNAME`
   and `OPENSUBTITLES_PASSWORD` are in dockermacmini's `.env`. The key has `dev_mode` on: OpenSubtitles' pricing page
   allows up to **100 downloads a day** in dev mode (5 a day on the free API package, 2,000 a day on "Light", $20 a
   month). Dev mode is described as for testing, so CineFilm must not depend on it lasting: it reads the remaining
   allowance from the API and queues the rest for the next day. At 100 a day the back catalogue (downloads only for
   films that need them, plus one per existing sidecar for the cross-check) should take a few days, with no purchase.
2. **Unverified downloads during playback:** recommended is to show an automatically `ALIGNED` download straight
   away (Admin flags it as not yet verified). The alternative is to show it only after the owner taps "Looks right".
3. **Unmatched LOTR files:** attach them in Admin (the plan's default; nothing on disk changes), or rename them on
   workhorse to match the film files.

## Risks

- **Few-line alignment** can lock on to the wrong speech. Mitigations: a subtitle reference first, offset-only
  fitting, tight acceptance, the owner's spot-check, and original times always kept.
- **Wrong edition** (a theatrical `.srt` on an extended cut): the runtime check, the OpenSubtitles comparison and the
  alignment residual each catch it.
- **OpenSubtitles terms, quotas or availability change** (as Subscene's did). Phase 1 does not depend on it, and
  downloaded subtitles are stored in CineFilm, so they outlive the service.
- **Mac mini resources:** alignment decodes a film's audio (minutes of CPU). It runs one at a time, niced, and waits
  for transcoding.
- **iPhone native fullscreen** may need the HLS rendition; the phase 1 spike settles it before anything else is
  built.
