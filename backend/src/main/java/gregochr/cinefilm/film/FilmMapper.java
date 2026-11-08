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
import java.util.List;
import java.util.Optional;

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

    /** The full detail page. */
    public static FilmDetail detail(FilmView view, Preferences preferences) {
        Film film = view.film();
        FilmCard card = card(view);
        CollectionRef collection = film.getCollection() == null ? null
            : new CollectionRef(film.getCollection().getId(), film.getCollection().getName());
        return new FilmDetail(card.id(), card.title(), card.year(), card.posterPath(), card.backdropPath(),
            card.watched(), card.progress(), card.director(), film.getOverview(),
            film.getGenres().stream().sorted().toList(), collection,
            view.directors().stream().map(FilmMapper::personRef).toList(),
            view.cast().stream().map(c -> new CastEntry(personRef(c.getPerson()), c.getCharacter())).toList(),
            film.getEditions().stream().map(e -> edition(e, view.state(e))).toList(), preferences);
    }

    private static EditionDto edition(Edition edition, Optional<PlaybackState> state) {
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
            qualities(original));
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
        return new FileInfo(original.getVideoCodec(), original.getHeight(), summary, original.getAddedAt());
    }

    /** Phase 1: only auto and original are available; lower qualities are listed as unavailable. */
    private static List<QualityDto> qualities(MediaFile original) {
        Long bitrate = original == null ? null : original.getBitrate();
        return List.of(
            new QualityDto("auto", bitrate, false, true),
            new QualityDto("original", bitrate, false, true),
            new QualityDto("1080p", null, false, false),
            new QualityDto("720p", null, false, false),
            new QualityDto("480p", null, false, false));
    }
}
