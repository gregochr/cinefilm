package gregochr.cinefilm.film;

import gregochr.cinefilm.film.FilmDtos.AudioStreamDto;
import gregochr.cinefilm.film.FilmDtos.CastEntry;
import gregochr.cinefilm.film.FilmDtos.CollectionRef;
import gregochr.cinefilm.film.FilmDtos.EditionDto;
import gregochr.cinefilm.film.FilmDtos.FileInfo;
import gregochr.cinefilm.film.FilmDtos.FilmCard;
import gregochr.cinefilm.film.FilmDtos.FilmDetail;
import gregochr.cinefilm.film.FilmDtos.PersonRef;
import gregochr.cinefilm.film.FilmDtos.Preferences;
import gregochr.cinefilm.film.FilmDtos.QualityDto;
import gregochr.cinefilm.film.FilmDtos.SubtitleStreamDto;
import gregochr.cinefilm.person.Person;
import gregochr.cinefilm.playback.PlaybackState;
import gregochr.cinefilm.transcode.CutList;
import gregochr.cinefilm.transcode.EncoderMode;
import gregochr.cinefilm.transcode.Ladder;
import gregochr.cinefilm.transcode.Rung;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Maps domain objects to the API shapes. */
public final class FilmMapper {

    private FilmMapper() {
    }

    /** The compact card. */
    public static FilmCard card(FilmView view) {
        Film film = view.film();
        String director = view.directorNames();
        return new FilmCard(film.getId(), film.getTitle(), film.getYear(), film.getPosterPath(),
            film.getBackdropPath(), view.watched(), view.progress().orElse(null),
            director.isEmpty() ? null : director);
    }

    /** A person reference. */
    public static PersonRef personRef(Person person) {
        return new PersonRef(person.getId(), person.getName(), person.getProfilePath());
    }

    /** The full detail page; the collection ref is set only when {@code inCollection}; {@code mode} sets qualities. */
    public static FilmDetail detail(FilmView view, Preferences preferences, boolean inCollection, EncoderMode mode) {
        return detail(view, preferences, inCollection, mode, file -> null);
    }

    /**
     * The full detail page with the stored cut lists in play.
     *
     * @param cuts the film file's stored cut list, or null when it is not indexed yet
     */
    public static FilmDetail detail(FilmView view, Preferences preferences, boolean inCollection, EncoderMode mode,
                                    Function<MediaFile, CutList> cuts) {
        Film film = view.film();
        FilmCard card = card(view);
        CollectionRef collection = film.getCollection() == null || !inCollection ? null
            : new CollectionRef(film.getCollection().getId(), film.getCollection().getName());
        return new FilmDetail(card.id(), card.title(), card.year(), card.posterPath(), card.backdropPath(),
            card.watched(), card.progress(), card.director(), film.getOverview(),
            film.getGenres().stream().sorted().toList(), collection,
            view.directors().stream().map(FilmMapper::personRef).toList(),
            view.cast().stream().map(c -> new CastEntry(personRef(c.getPerson()), c.getCharacter())).toList(),
            film.getEditions().stream().filter(e -> !e.hiddenFromBrowse())
                .map(e -> edition(e, view.state(e), mode, cuts)).toList(), preferences);
    }

    private static EditionDto edition(Edition edition, Optional<PlaybackState> state, EncoderMode mode,
                                       Function<MediaFile, CutList> cuts) {
        MediaFile original = edition.originalFile();
        List<AudioStreamDto> audio = original == null ? List.of() : original.getAudioStreams().stream()
            .map(a -> new AudioStreamDto(a.getId(), a.getIndex(), a.getLanguage(), a.getChannels(), a.getCodec(),
                a.getTitle(), a.isDefault(), a.isSafariPlayable())).toList();
        List<SubtitleStreamDto> subtitles = original == null ? List.of() : original.getSubtitleStreams().stream()
            .map(s -> new SubtitleStreamDto(s.getId(), s.getIndex(), s.getLanguage(), s.isForced(), s.getTitle(),
                s.getFormat())).toList();
        return new EditionDto(edition.getId(), edition.getName(), edition.getYear(), edition.getRuntimeMs(),
            edition.getNote(), edition.getCreditsMarkerMs(), state.map(PlaybackState::getPositionMs).orElse(0L),
            state.map(PlaybackState::isWatched).orElse(false), fileInfo(original), audio, subtitles,
            qualities(original, mode, cuts));
    }

    private static FileInfo fileInfo(MediaFile original) {
        if (original == null) {
            return null;
        }
        String summary = original.getAudioStreams().stream()
            .filter(AudioStream::isDefault)
            .findFirst()
            .or(() -> original.getAudioStreams().stream().findFirst())
            .map(AudioStream::getChannels)
            .orElse(null);
        return new FileInfo(original.getId(), original.getVideoCodec(), original.getHeight(), summary,
            original.getAddedAt());
    }

    /**
     * Auto, Original (always available: when it cannot be copied the client direct-plays it), then the transcoded
     * rungs of the same ladder a playback session builds ({@link Ladder#forSource}), so a source that cannot be
     * copied (HEVC, 10-bit, unknown pixel format, or a stored cut list with long gaps) lists its top transcoded rung
     * named by its own height. When the keyframe index does not exist yet the codec and pixel format decide alone.
     * Without an encoder the rungs are listed as unavailable, as the player's "worker offline" state expects; the
     * software encoder offers fewer rungs.
     */
    private static List<QualityDto> qualities(MediaFile original, EncoderMode mode,
                                              Function<MediaFile, CutList> cuts) {
        Long bitrate = original == null ? null : original.getBitrate();
        boolean encoder = mode != EncoderMode.UNAVAILABLE;
        List<QualityDto> qualities = new ArrayList<>();
        qualities.add(new QualityDto(Ladder.AUTO, bitrate, false, encoder));
        qualities.add(new QualityDto(Ladder.ORIGINAL, bitrate, false, true));
        if (original != null && original.getHeight() != null && original.getHeight() > 1) {
            EncoderMode shown = encoder ? mode : EncoderMode.VAAPI;
            long fileBitrate = bitrate == null ? 0 : bitrate;
            for (Rung rung : Ladder.forSource(original.getHeight(), fileBitrate, original.getVideoCodec(),
                original.getPixelFormat(), () -> cuts.apply(original), shown)) {
                if (!rung.copy()) {
                    qualities.add(new QualityDto(rung.id(), rung.videoBitrate(), false, encoder));
                }
            }
        }
        return List.copyOf(qualities);
    }
}
