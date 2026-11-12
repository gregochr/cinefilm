package gregochr.cinefilm.opensubtitles;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A word in a release name or an edition's name that says which cut of the film it is. Pure: text in, tags out.
 * Matching is case-insensitive and ignores punctuation, so {@code The.Extended.Edition}, {@code EXTENDED} and
 * {@code Director's Cut} all read.
 */
public enum CutTag {

    /** "Extended", as in "Extended Edition". */
    EXTENDED("\\bextended\\b"),
    /** "Director's Cut", "Directors Cut", "Director.s.Cut". */
    DIRECTORS_CUT("\\bdirector(?: s|s)? ?cut\\b"),
    /** "Special Edition". */
    SPECIAL_EDITION("\\bspecial edition\\b"),
    /** "Unrated". */
    UNRATED("\\bunrated\\b"),
    /** "Theatrical", as in "Theatrical Cut". */
    THEATRICAL("\\btheatrical\\b");

    private final Pattern pattern;

    CutTag(String regex) {
        this.pattern = Pattern.compile(regex);
    }

    /**
     * The tags in a text.
     *
     * @param text a release name, file name or edition name; null reads as no tags
     * @return the tags it carries (empty when none)
     */
    public static Set<CutTag> in(String text) {
        Set<CutTag> found = EnumSet.noneOf(CutTag.class);
        if (text == null) {
            return found;
        }
        // Punctuation becomes a space, but an apostrophe disappears so that "Director's" reads as "Directors".
        String plain = text.toLowerCase(Locale.ROOT).replace("'", "").replace("’", "")
            .replaceAll("[^\\p{L}\\p{N}]+", " ");
        for (CutTag tag : values()) {
            if (tag.pattern.matcher(plain).find()) {
                found.add(tag);
            }
        }
        return found;
    }

    /**
     * The tags in a text as a stable key (sorted names joined by commas; empty for none), for noting which cut an
     * edition's name asked for.
     *
     * @param text an edition name; null reads as no tags
     * @return the key
     */
    public static String key(String text) {
        return in(text).stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(","));
    }

    /**
     * What a result's tags say about an edition's tags: one point for each tag they share, minus one for each tag
     * of the opposite cut. Theatrical is the opposite of every other tag, and every other tag is the opposite of
     * Theatrical; a tag the edition itself carries is never an opposite. An edition with no tag has no preference (0).
     *
     * @param edition the edition's tags
     * @param result the result's tags
     * @return the affinity; higher is a better fit
     */
    public static int affinity(Set<CutTag> edition, Set<CutTag> result) {
        if (edition.isEmpty()) {
            return 0;
        }
        Set<CutTag> opposite = EnumSet.noneOf(CutTag.class);
        for (CutTag tag : edition) {
            if (tag == THEATRICAL) {
                opposite.addAll(EnumSet.complementOf(EnumSet.of(THEATRICAL)));
            } else {
                opposite.add(THEATRICAL);
            }
        }
        opposite.removeAll(edition);
        int score = 0;
        for (CutTag tag : result) {
            if (edition.contains(tag)) {
                score++;
            } else if (opposite.contains(tag)) {
                score--;
            }
        }
        return score;
    }
}
