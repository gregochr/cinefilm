package gregochr.cinefilm.film;

import java.time.Instant;
import java.util.List;

/** JSON shapes of the film endpoints (see docs/engineering/foundation-plan.md). */
public final class FilmDtos {

    private FilmDtos() {
    }

    /** Resume info of the most recently played, unfinished edition. */
    public record Progress(long positionMs, long runtimeMs, long editionId) {
    }

    /** The compact film representation used in rows and grids. */
    public record FilmCard(Long id, String title, Integer year, String posterPath, String backdropPath,
                           boolean watched, Progress progress, String director) {
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

    /** A selectable quality. */
    public record QualityDto(String id, Long bitrate, boolean premade, boolean available) {
    }

    /** One edition with its streams and qualities. */
    public record EditionDto(Long id, String name, Integer year, long runtimeMs, String note, Long creditsMarkerMs,
                             long positionMs, boolean watched, FileInfo file, List<AudioStreamDto> audioStreams,
                             List<SubtitleStreamDto> subtitleStreams, List<QualityDto> qualities) {
    }

    /** Remembered per-film choices. */
    public record Preferences(Long editionId, String quality, Long audioStreamId, String subtitle) {
    }

    /** The film detail page. */
    public record FilmDetail(Long id, String title, Integer year, String posterPath, String backdropPath,
                             boolean watched, Progress progress, String director, String overview,
                             List<String> genres, CollectionRef collection, List<PersonRef> directors,
                             List<CastEntry> cast, List<EditionDto> editions, Preferences preferences) {
    }

    /** A collection entry in the library filter. */
    public record CollectionFacet(Long id, String name) {
    }

    /** The values the library filters can offer. */
    public record Facets(List<String> genres, List<Integer> decades, List<CollectionFacet> collections) {
    }

    /** The library listing. */
    public record FilmList(int total, List<FilmCard> films, Facets facets) {
    }
}
