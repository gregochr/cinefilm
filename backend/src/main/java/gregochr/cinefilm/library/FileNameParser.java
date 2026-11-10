package gregochr.cinefilm.library;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads everything a film's path says about it: title, year, edition tag, part number and ids. Pure and path-only:
 * nothing probe-derived (embedded title, date tag) feeds grouping, so the facts can be recomputed for any known file
 * at any time. The rules and their order are in {@code docs/engineering/split-films-plan.md}, "Facts come from the
 * path only".
 */
public final class FileNameParser {

    private static final int EDITION_MAX = 32;
    private static final int FIRST_FILM_YEAR = 1888;
    private static final int LAST_YEAR = 2100;

    private static final Pattern VIDEO_EXTENSION = Pattern.compile("^(.+)\\.(?:m4v|mp4|mov|avi|mkv)$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern TMDB_TAG = Pattern.compile("\\{tmdb-(\\d{1,18})}", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMDB_TAG = Pattern.compile("\\{imdb-(tt\\d{1,12})}", Pattern.CASE_INSENSITIVE);
    private static final Pattern EDITION_TAG = Pattern.compile("\\{edition-([^}]+)}", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEPARATOR = Pattern.compile("[ ._-]");
    private static final Pattern BRACKET_YEAR = Pattern.compile("[(\\[](\\d{4})[)\\]]");
    private static final Pattern BARE_YEAR = Pattern.compile("(?<![A-Za-z0-9])(\\d{4})(?![A-Za-z0-9])");
    private static final Pattern PART_SUFFIX = Pattern.compile(
        "[ ._-]+(?:(?:pt|cd|disc|disk|dvd)[ ._-]?|part)([1-8])$", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUALITY = Pattern.compile("(?<![A-Za-z0-9])(?:"
        + "Blu-ray|BluRay|BRRip|BDRip|WEB-DL|WEBRip|DVDRip|HDR10|2160p|1080p|1080i|720p|576p|480p|Remux|HEVC|H\\.264"
        + "|H264|x264|x265|HDR|UHD|AAC|AC3|DTS|4K|HD|SD)(?![A-Za-z0-9])", Pattern.CASE_INSENSITIVE);
    private static final Pattern EDITION_WORD = Pattern.compile("(?<![\\p{L}\\p{N}])(?:"
        + "director['’]?s\\s+cut|extended\\s+edition|extended\\s+cut|extended|theatrical\\s+cut|theatrical|unrated"
        + "|final\\s+cut|special\\s+edition|remastered|imax|uncut)(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> EDITION_WORDS = new LinkedHashMap<>();
    private static final Map<String, String> ROMAN = new LinkedHashMap<>();

    static {
        EDITION_WORDS.put("directors cut", "Director's Cut");
        EDITION_WORDS.put("extended edition", "Extended Edition");
        EDITION_WORDS.put("extended cut", "Extended Cut");
        EDITION_WORDS.put("extended", "Extended");
        EDITION_WORDS.put("theatrical cut", "Theatrical Cut");
        EDITION_WORDS.put("theatrical", "Theatrical");
        EDITION_WORDS.put("unrated", "Unrated");
        EDITION_WORDS.put("final cut", "Final Cut");
        EDITION_WORDS.put("special edition", "Special Edition");
        EDITION_WORDS.put("remastered", "Remastered");
        EDITION_WORDS.put("imax", "IMAX");
        EDITION_WORDS.put("uncut", "Uncut");
        String[] numerals = {"ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x", "xi", "xii", "xiii", "xiv", "xv",
            "xvi", "xvii", "xviii", "xix", "xx"};
        for (int i = 0; i < numerals.length; i++) {
            ROMAN.put(numerals[i], String.valueOf(i + 2));
        }
    }

    private FileNameParser() {
    }

    /**
     * What a path says about its film.
     *
     * @param title the display title: tags, quality words, year and part suffix removed
     * @param year the year from the file name, else from the folder's {@code Title (Year)}; null when there is none
     * @param editionTag the {@code {edition-...}} name (at most 32 characters), null when untagged
     * @param partTag the part number 1-8 from a {@code pt1}/{@code cd2}/{@code .part3} suffix, null when none
     * @param tagConflict true when the file and its folder (or the file twice) carry different edition tags
     * @param tmdbId the {@code {tmdb-N}} id, null when absent
     * @param imdbId the {@code {imdb-ttN}} id (lower case), null when absent
     * @param comparisonTitle the title normalised for "are these the same film": plain edition words removed, accents
     *     dropped, lower case, punctuation runs as one space, roman numerals II-XX as numbers
     * @param plainEdition the plain edition word removed from the title ("Director's Cut"), null when none
     * @param groupKey the folder, comparison title, year and lower-cased edition tag joined with {@code |}
     */
    public record ParsedName(String title, Integer year, String editionTag, Integer partTag, boolean tagConflict,
                             Long tmdbId, String imdbId, String comparisonTitle, String plainEdition,
                             String groupKey) {
    }

    /**
     * Parses a path relative to the library root. Only the file name and its immediate folder are read; the whole
     * folder path only feeds the group key.
     *
     * @param relativePath the path below the library root, {@code /} (or {@code \}) separated
     * @return the parsed facts
     */
    public static ParsedName parse(String relativePath) {
        String path = relativePath.replace('\\', '/');
        int slash = path.lastIndexOf('/');
        String fileName = path.substring(slash + 1);
        String dir = slash < 0 ? "" : path.substring(0, slash);
        String folder = dir.substring(dir.lastIndexOf('/') + 1);

        String stem = stripExtensions(fileName);
        String rawStem = stem;

        // 1. Id tags (the file's, else the folder's), removed from both.
        Long tmdbId = tmdbId(stem);
        if (tmdbId == null) {
            tmdbId = tmdbId(folder);
        }
        String imdbId = imdbId(stem);
        if (imdbId == null) {
            imdbId = imdbId(folder);
        }
        stem = collapse(IMDB_TAG.matcher(TMDB_TAG.matcher(stem).replaceAll("")).replaceAll(""));
        folder = collapse(IMDB_TAG.matcher(TMDB_TAG.matcher(folder).replaceAll("")).replaceAll(""));

        // 2. Edition tag, from the file and the immediate folder (read from the original text, removed from both).
        List<String> fileTags = editionTags(fileName);
        List<String> folderTags = editionTags(dir.substring(dir.lastIndexOf('/') + 1));
        boolean conflict = fileTags.size() > 1 || folderTags.size() > 1;
        String editionTag = fileTags.isEmpty() ? null : fileTags.get(0);
        String folderTag = folderTags.isEmpty() ? null : folderTags.get(0);
        if (editionTag == null) {
            editionTag = folderTag;
        } else if (folderTag != null && !folderTag.equalsIgnoreCase(editionTag)) {
            conflict = true;
        }
        stem = collapse(EDITION_TAG.matcher(stem).replaceAll(""));
        folder = collapse(EDITION_TAG.matcher(folder).replaceAll(""));

        // 3. Quality tokens after the first word.
        stem = removeQuality(stem);

        // 4. Year: bracketed anywhere, else (scene-style names without spaces) the last bare year token that is not the
        // first; it and everything after go.
        Integer year = null;
        String tail = "";
        int[] cut = yearCut(stem);
        if (cut != null) {
            year = cut[0];
            tail = stem.substring(cut[2]);
            stem = stem.substring(0, cut[1]);
        }
        if (year == null) {
            year = folderYear(folder);
        }

        // 5. Part suffix (before the year, else after it: "Title (2002) - pt2").
        Integer partTag = null;
        stem = stem.replaceAll("[\\s._-]+$", "");
        Matcher part = PART_SUFFIX.matcher(stem);
        if (part.find()) {
            partTag = Integer.parseInt(part.group(1));
            stem = stem.substring(0, part.start());
        } else {
            Matcher afterYear = PART_SUFFIX.matcher(tail.stripTrailing());
            if (afterYear.find()) {
                partTag = Integer.parseInt(afterYear.group(1));
            }
        }

        // 6. Trailing separators, dots and underscores as spaces.
        String title = tidy(stem);
        if (title.isEmpty()) {
            title = tidy(rawStem);
        }

        String[] compared = compare(title);
        String comparisonTitle = compared[0];
        String groupKey = dir + "|" + comparisonTitle + "|" + (year == null ? "" : year) + "|"
            + (editionTag == null ? "" : editionTag.toLowerCase(Locale.ROOT));
        return new ParsedName(title, year, editionTag, partTag, conflict, tmdbId, imdbId, comparisonTitle,
            compared[1], groupKey);
    }

    private static String stripExtensions(String fileName) {
        String stem = fileName;
        Matcher matcher = VIDEO_EXTENSION.matcher(stem);
        while (matcher.matches()) {
            stem = matcher.group(1);
            matcher = VIDEO_EXTENSION.matcher(stem);
        }
        return stem;
    }

    private static Long tmdbId(String text) {
        Matcher matcher = TMDB_TAG.matcher(text);
        return matcher.find() ? Long.valueOf(matcher.group(1)) : null;
    }

    private static String imdbId(String text) {
        Matcher matcher = IMDB_TAG.matcher(text);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    /** The distinct (case-insensitive) non-blank edition names in a text, each cut to 32 characters. */
    private static List<String> editionTags(String text) {
        List<String> tags = new ArrayList<>();
        Matcher matcher = EDITION_TAG.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1).strip();
            name = name.substring(0, Math.min(name.length(), EDITION_MAX)).strip();
            String candidate = name;
            if (!name.isEmpty() && tags.stream().noneMatch(t -> t.equalsIgnoreCase(candidate))) {
                tags.add(name);
            }
        }
        return tags;
    }

    private static String removeQuality(String stem) {
        Matcher firstSeparator = SEPARATOR.matcher(stem);
        if (!firstSeparator.find()) {
            return stem;
        }
        int end = firstSeparator.start();
        return stem.substring(0, end) + QUALITY.matcher(stem.substring(end)).replaceAll("");
    }

    /**
     * Finds the year to cut at: {year, where the kept text ends, where the year ends}. A bracketed year anywhere wins
     * over a bare one, which only counts in names without spaces (the last such token); a year with no title
     * before it never counts (so "(1999)" and "1917" stay titles).
     */
    private static int[] yearCut(String stem) {
        Matcher bracket = BRACKET_YEAR.matcher(stem);
        while (bracket.find()) {
            int year = Integer.parseInt(bracket.group(1));
            if (isFilmYear(year) && !stem.substring(0, bracket.start()).isBlank()) {
                return new int[] {year, bracket.start(), bracket.end()};
            }
        }
        if (stem.contains(" ")) {
            return null; // in a name with spaces a bare number is part of the title
        }
        int[] last = null;
        Matcher bare = BARE_YEAR.matcher(stem);
        while (bare.find()) {
            int year = Integer.parseInt(bare.group(1));
            boolean first = stem.substring(0, bare.start()).replaceAll("[^\\p{L}\\p{N}]", "").isEmpty();
            if (isFilmYear(year) && !first) {
                last = new int[] {year, bare.start(), bare.end()};
            }
        }
        if (last != null) {
            return last;
        }
        return null;
    }

    private static boolean isFilmYear(int year) {
        return year >= FIRST_FILM_YEAR && year <= LAST_YEAR;
    }

    private static Integer folderYear(String folder) {
        Matcher bracket = BRACKET_YEAR.matcher(folder);
        while (bracket.find()) {
            int year = Integer.parseInt(bracket.group(1));
            if (isFilmYear(year)) {
                return year;
            }
        }
        return null;
    }

    /** Trailing separators trimmed; a name with no spaces but dots or underscores reads as words. */
    private static String tidy(String text) {
        String trimmed = text.replaceAll("[\\s._-]+$", "").strip();
        if (!trimmed.contains(" ")) {
            trimmed = trimmed.replaceAll("[._]+", " ").strip();
        }
        return collapse(trimmed);
    }

    private static String collapse(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    /** Returns {comparisonTitle, plainEdition-or-null}. */
    private static String[] compare(String title) {
        String plainEdition = null;
        Matcher matcher = EDITION_WORD.matcher(title);
        StringBuilder kept = new StringBuilder();
        while (matcher.find()) {
            if (plainEdition == null) {
                String key = matcher.group().toLowerCase(Locale.ROOT).replaceAll("['’]", "")
                    .replaceAll("\\s+", " ");
                plainEdition = EDITION_WORDS.get(key);
            }
            matcher.appendReplacement(kept, " ");
        }
        matcher.appendTail(kept);
        String folded = Normalizer.normalize(kept.toString(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
        List<String> words = new ArrayList<>();
        for (String word : folded.split(" ")) {
            words.add(ROMAN.getOrDefault(word, word));
        }
        return new String[] {String.join(" ", words), plainEdition};
    }
}
