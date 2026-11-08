package gregochr.cinefilm;

import gregochr.cinefilm.collection.FilmCollection;
import gregochr.cinefilm.film.AudioStream;
import gregochr.cinefilm.film.Credit;
import gregochr.cinefilm.film.CreditRole;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmView;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaKind;
import gregochr.cinefilm.film.SubtitleStream;
import gregochr.cinefilm.person.Person;
import gregochr.cinefilm.playback.PlaybackState;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Builders for domain objects. Pass null ids when the object will be persisted, real ids for pure unit tests. */
public final class TestData {

    /** A fixed "now" for rule tests. */
    public static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");

    public static final long MIN = 60_000L;

    private TestData() {
    }

    public static Film film(Long id, String title, int year, Instant addedAt, String... genres) {
        Film film = new Film();
        film.setId(id);
        film.setTitleAndSort(title);
        film.setYear(year);
        film.setAddedAt(addedAt);
        film.getGenres().addAll(java.util.List.of(genres));
        return film;
    }

    public static Edition edition(Film film, Long id, String name, long runtimeMs, Long marker) {
        Edition edition = new Edition();
        edition.setId(id);
        edition.setFilm(film);
        edition.setName(name);
        edition.setYear(film.getYear());
        edition.setRuntimeMs(runtimeMs);
        edition.setCreditsMarkerMs(marker);
        film.getEditions().add(edition);
        return edition;
    }

    /** Adds an ORIGINAL file with a default 5.1 and a stereo audio stream and one subtitle. */
    public static MediaFile file(Edition edition, String path) {
        MediaFile file = new MediaFile();
        file.setEdition(edition);
        file.setPath(path);
        file.setKind(MediaKind.ORIGINAL);
        file.setContainer("mp4");
        file.setVideoCodec("HEVC");
        file.setHeight(1080);
        file.setWidth(1920);
        file.setBitrate(9_000_000L);
        file.setAddedAt(edition.getFilm().getAddedAt());
        file.getAudioStreams().add(audio(file, 0, "5.1", "DTS", null, true));
        file.getAudioStreams().add(audio(file, 1, "Stereo", "AAC", "Commentary — Someone", false));
        SubtitleStream subtitle = new SubtitleStream();
        subtitle.setMediaFile(file);
        subtitle.setIndex(0);
        subtitle.setLanguage("English");
        subtitle.setTitle("English");
        subtitle.setFormat("SRT");
        file.getSubtitleStreams().add(subtitle);
        edition.getMediaFiles().add(file);
        return file;
    }

    private static AudioStream audio(MediaFile file, int index, String channels, String codec, String title,
                                     boolean isDefault) {
        AudioStream stream = new AudioStream();
        stream.setMediaFile(file);
        stream.setIndex(index);
        stream.setLanguage("English");
        stream.setChannels(channels);
        stream.setCodec(codec);
        stream.setTitle(title);
        stream.setDefault(isDefault);
        return stream;
    }

    public static Person person(Long id, String name) {
        Person person = new Person(name);
        person.setId(id);
        return person;
    }

    public static Credit credit(Film film, Person person, CreditRole role, String character, int sequence) {
        Credit credit = new Credit();
        credit.setFilm(film);
        credit.setPerson(person);
        credit.setRole(role);
        credit.setCharacter(character);
        credit.setSequence(sequence);
        film.getCredits().add(credit);
        return credit;
    }

    public static FilmCollection collection(String name) {
        FilmCollection collection = new FilmCollection();
        collection.setName(name);
        return collection;
    }

    public static PlaybackState state(Edition edition, long positionMs, boolean watched, Instant lastPlayedAt,
                                      Instant watchedAt) {
        PlaybackState state = new PlaybackState(edition.getId());
        state.setPositionMs(positionMs);
        state.setWatched(watched);
        state.setLastPlayedAt(lastPlayedAt);
        state.setWatchedAt(watchedAt);
        return state;
    }

    /** A view over a film with the given states keyed by edition id. */
    public static FilmView view(Film film, PlaybackState... states) {
        Map<Long, PlaybackState> map = new HashMap<>();
        for (PlaybackState state : states) {
            map.put(state.getEditionId(), state);
        }
        return new FilmView(film, map);
    }
}
