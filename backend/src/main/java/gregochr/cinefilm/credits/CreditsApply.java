package gregochr.cinefilm.credits;

import gregochr.cinefilm.credits.CreditsDenyFile.DenyList;
import gregochr.cinefilm.credits.learn.CreditsSplit;
import gregochr.cinefilm.credits.learn.Side;
import gregochr.cinefilm.film.CreditsMarkerSource;
import gregochr.cinefilm.film.EditionTimeline;
import java.util.List;
import java.util.Map;

/**
 * Decides what an edition's credits marker and its source should be, from what is known about it now. Pure: no
 * Spring, no repositories. The order is fixed: an owner's marker is never touched; otherwise Plex's marker for the
 * last part applies when it still describes the same file and lies inside the {@link EditionGuard} limits;
 * otherwise (step 3) an automatic marker from version 2's detection, when the switch is on, a deny list is in force
 * and none of its rules holds ({@link AutoInputs}); otherwise the marker and its source are both cleared.
 */
public final class CreditsApply {

    /**
     * One original part of an edition.
     *
     * @param mediaFileId the part's media file
     * @param timelineDurationMs how long the part is on the edition timeline; null when unknown
     */
    public record Part(long mediaFileId, Long timelineDurationMs) {
    }

    /**
     * What Plex said about the last part's file.
     *
     * @param fingerprint the file's fingerprint when imported
     * @param startMs where the credits start inside the file
     */
    public record PlexRow(String fingerprint, long startMs) {
    }

    /**
     * The marker an edition should have.
     *
     * @param markerMs the marker in edition milliseconds; null when there is none
     * @param source where it came from; null exactly when {@code markerMs} is null
     */
    public record Applied(Long markerMs, CreditsMarkerSource source) {
    }

    /** Why Plex's row does or does not apply to an edition, in the order the checks run. */
    public enum PlexState {
        /** Plex has no row for the last part's file. */
        NONE,
        /** The last part has no fingerprint, so the row cannot be matched to it. */
        NO_FINGERPRINT,
        /** The row's fingerprint differs from the file's: it describes an older copy. */
        STALE,
        /** The edition's parts have no timeline (no original part, or a part with no duration). */
        NO_TIMELINE,
        /** The row's offset lies outside the last part. */
        OUTSIDE,
        /** The time is outside the {@link EditionGuard} limits. */
        REFUSED,
        /** The pass applies the row. */
        USABLE
    }

    /**
     * Plex's row as it stands for an edition.
     *
     * @param state which check decided
     * @param markerMs Plex's time in edition milliseconds for {@code REFUSED} and {@code USABLE}; null otherwise
     */
    public record PlexStatus(PlexState state, Long markerMs) {
    }

    /** The version of the analysis whose results may become {@code AUTO} markers. */
    public static final int AUTO_ANALYSIS_VERSION = 2;
    /** How far after the detected start an {@code AUTO} marker lies, milliseconds. */
    static final long AUTO_MARGIN_MS = 20_000;

    /**
     * The inputs of a film's unit key, read the way L1's export query reads them.
     *
     * @param collectionTmdbId {@code film_collection.tmdb_id} through {@code film.collection_id}; null when none
     * @param collectionId {@code film.collection_id}; null when none
     * @param filmTmdbId {@code film.tmdb_id}; null when the film is not matched
     * @param title {@code film.title}
     * @param year {@code film.release_year}; null when unknown
     */
    public record UnitInputs(Long collectionTmdbId, Long collectionId, Long filmTmdbId, String title, Integer year) {

        /** The unit key, by the one rule L1 uses. */
        public String unitKey() {
            return CreditsSplit.unitKey(collectionTmdbId, collectionId, filmTmdbId, title, year);
        }
    }

    /**
     * The detection row of the last part's file.
     *
     * @param analysisVersion the analysis version that wrote it
     * @param verdict the verdict
     * @param offsetMs where the credits start inside the file; null when none
     * @param fingerprint the file's fingerprint when analysed
     * @param reasons the stored reasons, one per line
     */
    public record Detection(int analysisVersion, CreditsVerdict verdict, Long offsetMs, String fingerprint,
                            String reasons) {
    }

    /**
     * What step 3 needs to decide.
     *
     * @param enabled whether the switch is on
     * @param cards whether card results may take {@code AUTO}
     * @param denyList the deny list in force; null when there is none
     * @param editionId the edition
     * @param filmId the edition's film
     * @param film the film's unit key inputs
     * @param listedFilmUnitKeys the current unit key of every film the deny list names as a {@code film}, by film id
     *     (for rule 6)
     * @param detection the last part's detection row; null when there is none
     */
    public record AutoInputs(boolean enabled, boolean cards, DenyList denyList, long editionId, long filmId,
                             UnitInputs film, Map<Long, String> listedFilmUnitKeys, Detection detection) {
    }

    /** Which opening of a {@code FOUND} reason an {@code AUTO} candidate matched. */
    public enum AutoKind {
        /** Text on a dark screen moves up steadily. */
        CRAWL,
        /** Cards of text on a dark screen change. */
        CARD
    }

    /**
     * What step 3 produces for an edition.
     *
     * @param kind which opening the detection's first reason matched
     * @param offsetMs the detection's start inside the last part's file (file time)
     * @param startMs the detected start in edition milliseconds
     * @param markerMs the {@code AUTO} marker in edition milliseconds
     */
    public record AutoMarker(AutoKind kind, long offsetMs, long startMs, long markerMs) {
    }

    private CreditsApply() {
    }

    /**
     * Whether any of the six deny rules holds for an edition: its film is listed; its unit key hashes to test; its
     * unit key is listed; one of its files is listed; the edition is listed; or a listed film has today the same
     * unit key.
     */
    static boolean denied(DenyList list, long editionId, long filmId, String unitKey, List<Part> parts,
                          Map<Long, String> listedFilmUnitKeys) {
        return list.films().contains(filmId)
            || CreditsSplit.hashSide(unitKey) == Side.TEST
            || list.units().contains(unitKey)
            || parts.stream().anyMatch(p -> list.files().contains(p.mediaFileId()))
            || list.editions().contains(editionId)
            || list.films().stream().anyMatch(id -> unitKey.equals(listedFilmUnitKeys.get(id)));
    }

    /** Step 3's result, or null when step 3 does not hold. */
    private static AutoMarker autoMarker(List<Part> parts, String lastFingerprint, AutoInputs auto) {
        Detection d = auto.detection();
        if (!auto.enabled() || auto.denyList() == null || auto.film().filmTmdbId() == null || d == null
            || d.verdict() != CreditsVerdict.FOUND || d.offsetMs() == null
            || d.analysisVersion() != AUTO_ANALYSIS_VERSION
            || lastFingerprint == null || !lastFingerprint.equals(d.fingerprint())) {
            return null;
        }
        String first = d.reasons().split("\n", 2)[0];
        boolean crawl = first.startsWith(CreditsFinder.CRAWL_OPENING);
        boolean card = first.startsWith(CreditsFinder.CARD_OPENING);
        if (!crawl && !(card && auto.cards())) {
            return null;
        }
        if (denied(auto.denyList(), auto.editionId(), auto.filmId(), auto.film().unitKey(), parts,
            auto.listedFilmUnitKeys())) {
            return null;
        }
        EditionTimeline timeline;
        try {
            timeline = EditionTimeline.of(parts.stream().map(Part::timelineDurationMs).toList());
        } catch (IllegalArgumentException e) {
            return null;
        }
        long start;
        try {
            start = timeline.toGlobal(parts.size() - 1, d.offsetMs());
        } catch (IllegalArgumentException e) {
            return null;
        }
        long total = timeline.totalMs();
        if (!EditionGuard.plausible(start, total)) {
            return null;
        }
        return new AutoMarker(crawl ? AutoKind.CRAWL : AutoKind.CARD, d.offsetMs(), start,
            Math.min(start + AUTO_MARGIN_MS, total - CreditsTuning.MIN_BEFORE_END_MS));
    }

    /**
     * What step 3 would mark an edition with, whatever the edition's current source is: for the candidates list, the
     * edition evaluated as if its source were null (an owner's marker is not looked at). Plex's marker still wins,
     * exactly as in {@link #apply}, so an edition with a usable Plex row has no candidate. The caller builds
     * {@code auto} as if the switch were on.
     *
     * @param parts the edition's original parts in play order (may be empty)
     * @param lastFingerprint the last part's file's current fingerprint; null when unknown
     * @param plexRow Plex's row for the last part's file; null when there is none
     * @param auto step 3's inputs; null (no film) gives no candidate
     * @return step 3's result; null when the edition would not get an {@code AUTO} marker
     */
    public static AutoMarker candidate(List<Part> parts, String lastFingerprint, PlexRow plexRow, AutoInputs auto) {
        if (auto == null || plexStatus(parts, lastFingerprint, plexRow).state() == PlexState.USABLE) {
            return null;
        }
        return autoMarker(parts, lastFingerprint, auto);
    }

    /**
     * Works out the marker and source for one edition.
     *
     * @param parts the edition's original parts in play order (may be empty)
     * @param lastFingerprint the last part's file's current fingerprint; null when unknown
     * @param markerMs the edition's current marker; null when none
     * @param source the current marker's source; null when none (or stored before sources existed)
     * @param plexRow Plex's row for the last part's file; null when there is none
     * @return the marker and source the edition should have
     */
    public static Applied apply(List<Part> parts, String lastFingerprint, Long markerMs,
                                CreditsMarkerSource source, PlexRow plexRow) {
        return apply(parts, lastFingerprint, markerMs, source, plexRow, null);
    }

    /**
     * Works out the marker and source for one edition, with step 3 available.
     *
     * @param parts the edition's original parts in play order (may be empty)
     * @param lastFingerprint the last part's file's current fingerprint; null when unknown
     * @param markerMs the edition's current marker; null when none
     * @param source the current marker's source; null when none (or stored before sources existed)
     * @param plexRow Plex's row for the last part's file; null when there is none
     * @param auto step 3's inputs; null leaves step 3 off
     * @return the marker and source the edition should have
     */
    public static Applied apply(List<Part> parts, String lastFingerprint, Long markerMs,
                                CreditsMarkerSource source, PlexRow plexRow, AutoInputs auto) {
        if (source == CreditsMarkerSource.OWNER) {
            return new Applied(markerMs, source);
        }
        PlexStatus status = plexStatus(parts, lastFingerprint, plexRow);
        if (status.state() == PlexState.USABLE) {
            return new Applied(status.markerMs(), CreditsMarkerSource.PLEX);
        }
        if (auto != null) {
            AutoMarker autoMarker = autoMarker(parts, lastFingerprint, auto);
            if (autoMarker != null) {
                return new Applied(autoMarker.markerMs(), CreditsMarkerSource.AUTO);
            }
        }
        return new Applied(null, null);
    }

    /**
     * What Plex's row means for an edition, whatever the edition's source is. The checks run in the order of
     * {@link PlexState}, and {@link #apply} applies the row exactly when the state is {@code USABLE}, so a list and
     * the pass cannot disagree.
     *
     * @param parts the edition's original parts in play order (may be empty)
     * @param lastFingerprint the last part's file's current fingerprint; null when unknown
     * @param plexRow Plex's row for the last part's file; null when there is none
     * @return the state, and Plex's time in edition milliseconds for {@code REFUSED} and {@code USABLE} only
     */
    public static PlexStatus plexStatus(List<Part> parts, String lastFingerprint, PlexRow plexRow) {
        if (plexRow == null) {
            return new PlexStatus(PlexState.NONE, null);
        }
        if (lastFingerprint == null) {
            return new PlexStatus(PlexState.NO_FINGERPRINT, null);
        }
        if (!lastFingerprint.equals(plexRow.fingerprint())) {
            return new PlexStatus(PlexState.STALE, null);
        }
        EditionTimeline timeline;
        try {
            timeline = EditionTimeline.of(parts.stream().map(Part::timelineDurationMs).toList());
        } catch (IllegalArgumentException e) {
            return new PlexStatus(PlexState.NO_TIMELINE, null);
        }
        long global;
        try {
            global = timeline.toGlobal(parts.size() - 1, plexRow.startMs());
        } catch (IllegalArgumentException e) {
            return new PlexStatus(PlexState.OUTSIDE, null);
        }
        return new PlexStatus(EditionGuard.plausible(global, timeline.totalMs())
            ? PlexState.USABLE : PlexState.REFUSED, global);
    }
}
