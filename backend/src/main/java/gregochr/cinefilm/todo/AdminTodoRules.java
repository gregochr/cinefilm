package gregochr.cinefilm.todo;

import gregochr.cinefilm.tmdb.TmdbMatcher;
import java.util.Comparator;
import java.util.List;

/**
 * The rules Admin's To do page, its rail and the Subtitles tabs share, so they cannot disagree. Pure: facts in,
 * booleans out; no Spring, no repositories. The callers gather the facts (in bulk) and hand them in.
 *
 * <p>A subtitle row is <em>in play</em> when its need is shown and no subtitle of that kind plays. An in-play row is
 * either <em>being checked</em> (OpenSubtitles has not been searched for the film's current IMDb id yet, a forced
 * search left no candidates stored so the search scheduler will fill them in, or the
 * download scheduler would download a subtitle for some edition) or <em>unresolved</em> (the owner has to act). A film
 * with no IMDb id can never be searched, so it is unresolved. Those two never overlap, and together they are exactly
 * the in-play rows. A download that is due but deferred (the daily limit) is still being checked: the limit never
 * makes a film unresolved.
 *
 * <p>Lives in its own package so that {@code library} and {@code subtitle}, which both use it, do not depend on each
 * other for it.
 */
public final class AdminTodoRules {

    private AdminTodoRules() {
    }

    /**
     * The facts a full-subtitles row is judged on.
     *
     * @param needed true when the film's full-subtitle need is {@code NEEDED}
     * @param noneExist true when the owner said no English subtitles exist (the row is done, not in play)
     * @param playsFullSubtitle true when some edition plays a full English subtitle already
     * @param hasImdbId true when the film has an IMDb id (OpenSubtitles is searched by it)
     * @param searched true when OpenSubtitles was searched for full candidates under the film's current IMDb id
     * @param downloadDue true when the download scheduler would download a full subtitle for some edition
     * @param searchEnabled true when OpenSubtitles searches run at all (an API key is set)
     * @param downloadsEnabled true when downloads run at all (key, username and password are set)
     */
    public record FullFacts(boolean needed, boolean noneExist, boolean playsFullSubtitle, boolean hasImdbId, boolean searched,
                            boolean downloadDue, boolean searchEnabled, boolean downloadsEnabled) {
    }

    /**
     * The facts a forced-subtitles row is judged on.
     *
     * @param needed true when the film's forced-subtitle need is {@code NEEDED}
     * @param hasForcedSubtitle true when some edition has a forced subtitle that playback would serve
     * @param hasImdbId true when the film has an IMDb id
     * @param openSubtitlesForcedCount how many forced files OpenSubtitles had for the film's current IMDb id, or null
     *     when it has not been searched
     * @param candidatesMissing true when the film's stored candidates are null: the search scheduler fills them in
     *     (it picks every film with an IMDb id and no candidates), even when the film was searched
     * @param downloadDue true when the download scheduler would download a forced subtitle for some edition
     * @param searchEnabled true when OpenSubtitles searches run at all (an API key is set)
     * @param downloadsEnabled true when downloads run at all (key, username and password are set)
     */
    public record ForcedFacts(boolean needed, boolean hasForcedSubtitle, boolean hasImdbId,
                              Integer openSubtitlesForcedCount, boolean candidatesMissing, boolean downloadDue,
                              boolean searchEnabled, boolean downloadsEnabled) {
    }

    /**
     * Whether TMDB's best guess for an unmatched film is confident enough to offer as a one-click "yes": the top
     * score is above {@link TmdbMatcher#ACCEPT_SCORE} and either it is the only candidate or it leads the runner-up by
     * more than {@link TmdbMatcher#MARGIN}. A tagged ({@code {imdb-tt...}}) match the guard refused to fold is one
     * candidate at {@code REFUSED_SCORE} (100), TMDB's own id and certain; a refused title-search match keeps the
     * matcher's candidates and is judged like any other.
     *
     * <p>The matcher compares unrounded totals and stores each score as a whole number: floored now, rounded in rows
     * stored before that change. A stored score above the accept score ({@code >= ACCEPT_SCORE + 1}) means the real one
     * is at least the accept score under either, and a lead above the margin ({@code >= MARGIN + 1}) means the real
     * lead is at least the margin. So the rule never claims confidence the matcher would refuse; it may decline an
     * exact boundary, which is the safe side.
     *
     * @param scores the candidates' scores, in any order (none gives false)
     * @return true when the best guess is confident
     */
    public static boolean confident(List<Integer> scores) {
        if (scores == null || scores.isEmpty()) {
            return false;
        }
        List<Integer> ranked = scores.stream().sorted(Comparator.reverseOrder()).toList();
        int best = ranked.get(0);
        if (best < TmdbMatcher.ACCEPT_SCORE + 1) {
            return false;
        }
        return ranked.size() == 1 || best - ranked.get(1) >= TmdbMatcher.MARGIN + 1;
    }

    /** A full row is in play: {@code NEEDED}, not marked {@code NONE_EXIST}, and no full English subtitle plays. */
    public static boolean inPlayFull(FullFacts facts) {
        return facts.needed() && !facts.noneExist() && !facts.playsFullSubtitle();
    }

    /** A full row is settled: {@code NEEDED} and a full English subtitle plays (a {@code NONE_EXIST} film is not). */
    public static boolean subtitledFull(FullFacts facts) {
        return facts.needed() && facts.playsFullSubtitle();
    }

    /** An in-play full row with an automatic download still to come. */
    public static boolean beingCheckedFull(FullFacts facts) {
        if (!inPlayFull(facts) || !facts.hasImdbId()) {
            return false;
        }
        return !facts.searched() && facts.searchEnabled() || facts.downloadDue() && facts.downloadsEnabled();
    }

    /** An in-play full row nothing automatic will fix: the owner has to act. */
    public static boolean unresolvedFull(FullFacts facts) {
        return inPlayFull(facts) && !beingCheckedFull(facts);
    }

    /** A forced row is in play: {@code NEEDED} and no forced subtitle plays (today's "needed-missing"). */
    public static boolean inPlayForced(ForcedFacts facts) {
        return facts.needed() && !facts.hasForcedSubtitle();
    }

    /** An in-play forced row with an automatic search or download still to come. */
    public static boolean beingCheckedForced(ForcedFacts facts) {
        if (!inPlayForced(facts) || !facts.hasImdbId()) {
            return false;
        }
        Integer count = facts.openSubtitlesForcedCount();
        if (count == null || facts.candidatesMissing()) {
            return facts.searchEnabled();
        }
        return count > 0 && facts.downloadDue() && facts.downloadsEnabled();
    }

    /** An in-play forced row nothing automatic will fix: the owner has to act. */
    public static boolean unresolvedForced(ForcedFacts facts) {
        return inPlayForced(facts) && !beingCheckedForced(facts);
    }
}
