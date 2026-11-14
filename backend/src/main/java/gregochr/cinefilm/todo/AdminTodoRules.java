package gregochr.cinefilm.todo;

import gregochr.cinefilm.tmdb.TmdbMatcher;
import java.util.Comparator;
import java.util.List;

/**
 * The rules Admin's To do page, its rail and the Subtitles tabs share, so they cannot disagree. Pure: facts in,
 * booleans out; no Spring, no repositories. The callers gather the facts (in bulk) and hand them in.
 *
 * <p>A subtitle row is <em>in play</em> when its need is shown and no subtitle of that kind plays. An in-play row is
 * either <em>being checked</em> (an automatic search or download is still to come for it) or <em>unresolved</em> (the
 * owner has to act). Those two never overlap, and together they are exactly the in-play rows. A download that is due
 * but deferred (the daily limit) is still being checked: the limit never makes a film unresolved.
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
     * @param playsFullSubtitle true when some edition plays a full English subtitle already
     * @param hasImdbId true when the film has an IMDb id (OpenSubtitles is searched by it)
     * @param searched true when OpenSubtitles was searched for full candidates under the film's current IMDb id
     * @param hasPromotableCandidate true when the search left at least one candidate that can still become a download
     * @param editionDueWithRoom true when a present edition is due a full download and is under the cap
     */
    public record FullFacts(boolean needed, boolean playsFullSubtitle, boolean hasImdbId, boolean searched,
                            boolean hasPromotableCandidate, boolean editionDueWithRoom) {
    }

    /**
     * The facts a forced-subtitles row is judged on.
     *
     * @param needed true when the film's forced-subtitle need is {@code NEEDED}
     * @param hasForcedSubtitle true when some edition has a forced subtitle that playback would serve
     * @param hasImdbId true when the film has an IMDb id
     * @param openSubtitlesForcedCount how many forced files OpenSubtitles had for the film's current IMDb id, or null
     *     when it has not been searched
     * @param editionDueWithRoom true when a present edition is due a forced download and is under the cap
     */
    public record ForcedFacts(boolean needed, boolean hasForcedSubtitle, boolean hasImdbId,
                              Integer openSubtitlesForcedCount, boolean editionDueWithRoom) {
    }

    /**
     * Whether TMDB's best guess for an unmatched film is confident enough to offer as a one-click "yes": the top
     * score reaches {@link TmdbMatcher#ACCEPT_SCORE} and either it is the only candidate or it leads the runner-up by
     * at least {@link TmdbMatcher#MARGIN}. A match the guard refused to fold is one candidate stored at the accept
     * score, so it is confident.
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
        if (best < TmdbMatcher.ACCEPT_SCORE) {
            return false;
        }
        return ranked.size() == 1 || best - ranked.get(1) >= TmdbMatcher.MARGIN;
    }

    /** A full row is in play: {@code NEEDED} and no full English subtitle plays. */
    public static boolean inPlayFull(FullFacts facts) {
        return facts.needed() && !facts.playsFullSubtitle();
    }

    /** An in-play full row with an automatic download still to come. */
    public static boolean beingCheckedFull(FullFacts facts) {
        if (!inPlayFull(facts) || !facts.hasImdbId()) {
            return false;
        }
        return !facts.searched() || facts.hasPromotableCandidate() && facts.editionDueWithRoom();
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
        return count == null || count > 0 && facts.editionDueWithRoom();
    }

    /** An in-play forced row nothing automatic will fix: the owner has to act. */
    public static boolean unresolvedForced(ForcedFacts facts) {
        return inPlayForced(facts) && !beingCheckedForced(facts);
    }
}
