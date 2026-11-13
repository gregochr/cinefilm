package gregochr.cinefilm.film;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** JSON shapes of the film endpoints (see docs/engineering/foundation-plan.md). */
public final class FilmDtos {

    private FilmDtos() {
    }

    /** Resume info of the most recently played, unfinished edition. */
    public record Progress(long positionMs, long runtimeMs, long editionId) {
    }

    /**
     * The compact film representation used in rows and grids. {@code collectionId} is set only on the library
     * listing ({@code GET /api/films}) and only when the film's collection has at least
     * {@code CollectionService.MIN_OWNED} films in the library; everywhere else it is null. {@code sortTitle} is the
     * key the library sorts titles by (an import may set it to something other than the title).
     *
     * <p>{@code progress} is any saved position above 0 (Film detail and the player resume below the minimum watch
     * time too). {@code inProgress} is the rule clients use: the last played edition is at or above the minimum
     * watch time. {@code lastWatchedAt} is when that edition was last played; {@code hiddenFromContinueWatching} is
     * that edition's Remove flag.
     */
    public record FilmCard(Long id, String title, Integer year, String posterPath, String backdropPath,
                           boolean watched, Progress progress, String director, Long collectionId,
                           String sortTitle, boolean inProgress, Instant lastWatchedAt,
                           boolean hiddenFromContinueWatching) {
    }

    /**
     * A film as the Library list carries it: a {@link FilmCard} without {@code backdropPath}, which the Library never
     * shows. Same fields, same order.
     */
    public record LibraryCard(Long id, String title, Integer year, String posterPath, boolean watched,
                              Progress progress, String director, Long collectionId, String sortTitle,
                              boolean inProgress, Instant lastWatchedAt, boolean hiddenFromContinueWatching) {

        /** The card without its backdrop. */
        static LibraryCard of(FilmCard card) {
            return new LibraryCard(card.id(), card.title(), card.year(), card.posterPath(), card.watched(),
                card.progress(), card.director(), card.collectionId(), card.sortTitle(), card.inProgress(),
                card.lastWatchedAt(), card.hiddenFromContinueWatching());
        }
    }

    /** A director or actor reference. */
    public record PersonRef(Long id, String name, String profilePath) {
    }

    /** A cast entry. */
    public record CastEntry(PersonRef person, String character) {
    }

    /** Reference to the collection a film belongs to. */
    public record CollectionRef(Long id, String name) {
    }

    /** The original file of an edition, summarised for the "file line". */
    public record FileInfo(Long id, String videoCodec, Integer width, Integer height, String audioSummary,
                           Instant addedAt) {
    }

    /** An audio track. */
    public record AudioStreamDto(Long id, int index, String language, String channels, String codec, String title,
                                 boolean isDefault, boolean safariPlayable) {
    }

    /** A subtitle track. */
    public record SubtitleStreamDto(Long id, int index, String language, boolean forced, String title,
                                    String format) {
    }

    /**
     * The forced English subtitle an edition plays by itself when Subtitles is Off, served by
     * {@code GET /api/editions/{id}/forced-subtitles.vtt}.
     *
     * @param source where it comes from; {@code SIDECAR} (a {@code .srt} file beside the film) is the only one today
     * @param ownerVerified true while the owner has checked it and the edition's parts have not changed since
     * @param version the latest change time of the files it is made from, in epoch milliseconds: it goes on the
     *     subtitle URL so a nudge in Admin is fetched again
     */
    public record ForcedSubtitleDto(String source, boolean ownerVerified, long version) {
    }

    /** A selectable quality. */
    public record QualityDto(String id, Long bitrate, boolean premade, boolean available) {
    }

    /**
     * One edition with its streams and qualities. {@code parts} is how many files the edition is made of (1 for a film
     * in one piece): a split film plays as one continuous film (times are edition times, from the start of part 1), is
     * never direct-played and shows no part numbers; the audio and subtitle streams are part 1's, the logical ones.
     * {@code forcedSubtitle} is null when the edition has no forced subtitle to play.
     */
    public record EditionDto(Long id, String name, Integer year, long runtimeMs, String note, Long creditsMarkerMs,
                             long positionMs, boolean watched, FileInfo file, List<AudioStreamDto> audioStreams,
                             List<SubtitleStreamDto> subtitleStreams, List<QualityDto> qualities, int parts,
                             ForcedSubtitleDto forcedSubtitle) {
    }

    /** Remembered per-film choices. */
    public record Preferences(Long editionId, String quality, Long audioStreamId, String subtitle) {
    }

    /**
     * The IMDb score.
     *
     * @param score out of 10, one decimal
     * @param votes how many votes it rests on, or null
     * @param url the film's IMDb page, or null when the IMDb id is unknown
     */
    public record ImdbRating(BigDecimal score, Integer votes, String url) {
    }

    /**
     * The Rotten Tomatoes score.
     *
     * @param score the percentage, 0 to 100
     * @param url the film's Rotten Tomatoes page, or null
     */
    public record RottenTomatoesRating(int score, String url) {
    }

    /**
     * The film's ratings; a source with no score is null.
     *
     * @param imdb the IMDb score, or null
     * @param rottenTomatoes the Rotten Tomatoes score, or null
     */
    public record Ratings(ImdbRating imdb, RottenTomatoesRating rottenTomatoes) {
    }

    /**
     * The film detail page.
     *
     * <p>{@code fullNeed} is whether the film needs full English subtitles: {@code NEEDED}, {@code NOT_NEEDED},
     * {@code UNSURE} or {@code UNKNOWN}; null when the mapper was not given it.
     */
    public record FilmDetail(Long id, String title, Integer year, String posterPath, String backdropPath,
                             boolean watched, Progress progress, String director, String overview,
                             List<String> genres, CollectionRef collection, List<PersonRef> directors,
                             List<CastEntry> cast, List<EditionDto> editions, Preferences preferences,
                             Ratings ratings, String fullNeed) {
    }

    /** The values the library filters can offer. */
    public record Facets(List<String> genres, List<Integer> decades) {
    }

    /** The library listing. */
    public record FilmList(int total, List<LibraryCard> films, Facets facets) {
    }
}
