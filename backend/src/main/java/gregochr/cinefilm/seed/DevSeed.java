package gregochr.cinefilm.seed;

import gregochr.cinefilm.collection.CollectionPart;
import gregochr.cinefilm.collection.FilmCollection;
import gregochr.cinefilm.collection.FilmCollectionRepository;
import gregochr.cinefilm.film.AudioStream;
import gregochr.cinefilm.film.Credit;
import gregochr.cinefilm.film.CreditRole;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaKind;
import gregochr.cinefilm.film.SubtitleStream;
import gregochr.cinefilm.person.Person;
import gregochr.cinefilm.person.PersonRepository;
import gregochr.cinefilm.playback.PlaybackState;
import gregochr.cinefilm.playback.PlaybackStateRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds a sample library mirroring the design comps so every Home row has content. Dev profile only, and only
 * into an empty database. Poster and backdrop paths stay null (the UI shows placeholder blocks).
 */
@Component
@Profile("dev")
public class DevSeed implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DevSeed.class);
    private static final long MINUTE = 60_000L;
    private static final String PLACEHOLDER_DIR = "/nonexistent/cinefilm-sample/";

    private final FilmRepository films;
    private final PersonRepository people;
    private final FilmCollectionRepository collections;
    private final PlaybackStateRepository states;
    private final Clock clock;
    private final String sampleVideo;

    private final Map<String, Person> personCache = new HashMap<>();
    private Instant now;

    public DevSeed(FilmRepository films, PersonRepository people, FilmCollectionRepository collections,
                   PlaybackStateRepository states, Clock clock,
                   @Value("${cinefilm.sample-video:}") String sampleVideo) {
        this.films = films;
        this.people = people;
        this.collections = collections;
        this.states = states;
        this.clock = clock;
        this.sampleVideo = sampleVideo;
    }

    /** Cast member shorthand. */
    private record Cast(String name, String character) {
    }

    /** How a film's single (or first) edition has been watched. */
    private enum Watch { NONE, PROGRESS, WATCHED }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (films.count() > 0) {
            LOG.info("Library already has films; skipping dev seed");
            return;
        }
        now = clock.instant();
        FilmCollection alien = collection(8091L, "Alien",
            part(348, "Alien", 1979), part(679, "Aliens", 1986),
            part(8077, "Alien³", 1992), part(8078, "Alien Resurrection", 1997));
        FilmCollection bladeRunner = collection(422837L, "Blade Runner",
            part(78, "Blade Runner", 1982), part(335984, "Blade Runner 2049", 2017));
        FilmCollection threeColours = collection(1247L, "Three Colours",
            part(108, "Three Colours: Blue", 1993), part(109, "Three Colours: White", 1994),
            part(110, "Three Colours: Red", 1994));

        // Continue watching (Heat is the hero, at 1:04:31).
        film(949, "Heat", 1995, 170, "Crime, Thriller", "Michael Mann", 90, null,
            "A meticulous crew of professional thieves and the obsessive LAPD detective tracking them circle each"
                + " other across Los Angeles as one last score draws near.",
            Watch.PROGRESS, 3_871_000L, 1, 9_600_000L, true,
            new Cast("Al Pacino", "Lt. Vincent Hanna"), new Cast("Robert De Niro", "Neil McCauley"),
            new Cast("Val Kilmer", "Chris Shiherlis"));
        film(329865, "Arrival", 2016, 116, "Sci-fi, Drama", "Denis Villeneuve", 70, null,
            "A linguist is recruited to talk to visitors whose ships have settled over twelve places on Earth.",
            Watch.PROGRESS, pct(116, 55), 2, null, false,
            new Cast("Amy Adams", "Louise Banks"), new Cast("Jeremy Renner", "Ian Donnelly"));
        film(829, "Chinatown", 1974, 130, "Crime, Mystery", "Roman Polanski", 120, null,
            "A private eye hired to expose an adulterer finds himself tangled in a conspiracy over Los Angeles"
                + " water.",
            Watch.PROGRESS, pct(130, 17), 4, null, false,
            new Cast("Jack Nicholson", "J.J. Gittes"), new Cast("Faye Dunaway", "Evelyn Mulwray"));
        film(655, "Paris, Texas", 1984, 145, "Drama", "Wim Wenders", 150, null,
            "A drifter walks out of the desert after four years and tries to piece together his family.",
            Watch.PROGRESS, pct(145, 52), 6, null, false,
            new Cast("Harry Dean Stanton", "Travis Henderson"), new Cast("Nastassja Kinski", "Jane"));
        film(17431, "Moon", 2009, 97, "Sci-fi, Drama", "Duncan Jones", 200, null,
            "Nearing the end of a three-year shift mining the far side of the Moon, an astronaut meets"
                + " someone he should not.",
            Watch.PROGRESS, pct(97, 88), 8, null, false, new Cast("Sam Rockwell", "Sam Bell"));

        // Recently added.
        film(1949, "Zodiac", 2007, 157, "Crime, Thriller", "David Fincher", 2, null,
            "A cartoonist and two detectives chase a serial killer who writes to the newspapers.",
            Watch.NONE, 0, 0, null, true,
            new Cast("Jake Gyllenhaal", "Robert Graysmith"), new Cast("Mark Ruffalo", "Dave Toschi"));
        film(11036, "Local Hero", 1983, 111, "Comedy, Drama", "Bill Forsyth", 4, null,
            "An oil company executive is sent to buy up a Scottish fishing village and is slowly won over by it.",
            Watch.NONE, 0, 0, null, true,
            new Cast("Peter Riegert", "Mac"), new Cast("Burt Lancaster", "Felix Happer"));
        film(843, "In the Mood for Love", 2000, 98, "Drama, Romance", "Wong Kar-wai", 6, null,
            "Two neighbours in 1960s Hong Kong discover their spouses are having an affair.",
            Watch.NONE, 0, 0, null, true,
            new Cast("Tony Leung", "Chow Mo-wan"), new Cast("Maggie Cheung", "Su Li-zhen"));
        film(592, "The Conversation", 1974, 113, "Thriller, Drama", "Francis Ford Coppola", 9, null,
            "A surveillance expert becomes haunted by a recording he fears will lead to murder.",
            Watch.NONE, 0, 0, 5_900_000L, true,
            new Cast("Gene Hackman", "Harry Caul"), new Cast("John Cazale", "Stan"));
        film(129, "Spirited Away", 2001, 125, "Animation, Fantasy", "Hayao Miyazaki", 12, null,
            "A girl wanders into a world of spirits and must work in a bathhouse to free her parents.",
            Watch.NONE, 0, 0, null, true, new Cast("Rumi Hiiragi", "Chihiro"));
        film(64690, "Drive", 2011, 100, "Crime, Drama", "Nicolas Winding Refn", 15, null,
            "A Hollywood stunt driver who moonlights as a getaway driver falls for his neighbour.",
            Watch.NONE, 0, 0, null, true,
            new Cast("Ryan Gosling", "Driver"), new Cast("Carey Mulligan", "Irene"));
        film(348, "Alien", 1979, 117, "Sci-fi, Horror", "Ridley Scott", 20, alien,
            "The crew of a commercial tug answers a distress call and brings something terrible aboard.",
            Watch.WATCHED, 0, 2_070, null, true,
            new Cast("Sigourney Weaver", "Ellen Ripley"), new Cast("Tom Skerritt", "Dallas"));
        film(679, "Aliens", 1986, 137, "Sci-fi, Action", "James Cameron", 26, alien,
            "Ripley returns to the moon where it began, this time with a squad of Colonial Marines.",
            Watch.NONE, 0, 0, null, true,
            new Cast("Sigourney Weaver", "Ellen Ripley"), new Cast("Michael Biehn", "Hicks"));

        // Not watched in years.
        film(1092, "The Third Man", 1949, 104, "Thriller, Mystery", "Carol Reed", 400, null,
            "A writer arrives in post-war Vienna to find his friend Harry Lime has died, or has he?",
            Watch.WATCHED, 0, 2_600, null, false,
            new Cast("Orson Welles", "Harry Lime"), new Cast("Joseph Cotten", "Holly Martins"));
        film(1070, "Brief Encounter", 1945, 86, "Drama, Romance", "David Lean", 380, null,
            "A housewife and a doctor meet by chance on a station platform and fall quietly in love.",
            Watch.WATCHED, 0, 3_000, null, true,
            new Cast("Celia Johnson", "Laura Jesson"), new Cast("Trevor Howard", "Alec Harvey"));
        bladeRunner(bladeRunner);
        film(153, "Lost in Translation", 2003, 102, "Drama, Comedy", "Sofia Coppola", 300, null,
            "Two strangers adrift in Tokyo find an unlikely friendship.",
            Watch.WATCHED, 0, 2_300, null, true,
            new Cast("Bill Murray", "Bob Harris"), new Cast("Scarlett Johansson", "Charlotte"));
        film(11353, "Withnail and I", 1987, 107, "Comedy, Drama", "Bruce Robinson", 310, null,
            "Two unemployed actors escape rainy Camden for a disastrous holiday in the Lake District.",
            Watch.WATCHED, 0, 3_300, null, true,
            new Cast("Richard E. Grant", "Withnail"), new Cast("Paul McGann", "I"));
        film(11880, "The Long Good Friday", 1980, 114, "Crime, Thriller", "John Mackenzie", 280, null,
            "A London gangster's plans to go legitimate unravel when someone starts bombing his empire.",
            Watch.WATCHED, 0, 2_250, null, true,
            new Cast("Bob Hoskins", "Harold Shand"), new Cast("Helen Mirren", "Victoria"));
        film(5167, "Kes", 1969, 110, "Drama", "Ken Loach", 330, null,
            "A boy in a Yorkshire mining town finds purpose in training a kestrel.",
            Watch.WATCHED, 0, 3_700, null, false,
            new Cast("David Bradley", "Billy Casper"), new Cast("Colin Welland", "Mr Farthing"));

        // The rest of the 1970s and the other collection.
        film(103, "Taxi Driver", 1976, 114, "Crime, Drama", "Martin Scorsese", 250, null,
            "A lonely night-shift cabbie sinks into obsession in a grimy New York.",
            Watch.WATCHED, 0, 200, null, true,
            new Cast("Robert De Niro", "Travis Bickle"), new Cast("Jodie Foster", "Iris"));
        film(2756, "Don't Look Now", 1973, 110, "Horror, Thriller", "Nicolas Roeg", 220, null,
            "A grieving couple in Venice is drawn into a series of eerie warnings.",
            Watch.NONE, 0, 0, null, true,
            new Cast("Donald Sutherland", "John Baxter"), new Cast("Julie Christie", "Laura Baxter"));
        film(11235, "Get Carter", 1971, 112, "Crime, Thriller", "Mike Hodges", 190, null,
            "A London gangster returns to Newcastle to investigate his brother's death.",
            Watch.NONE, 0, 0, null, true,
            new Cast("Michael Caine", "Jack Carter"), new Cast("Ian Hendry", "Eric Paice"));
        film(11025, "The Wicker Man", 1973, 88, "Horror, Mystery", "Robin Hardy", 160, null,
            "A devout policeman searches a remote Scottish island for a missing girl.",
            Watch.NONE, 0, 0, null, true,
            new Cast("Edward Woodward", "Sergeant Howie"), new Cast("Christopher Lee", "Lord Summerisle"));
        film(108, "Three Colours: Blue", 1993, 98, "Drama", "Krzysztof Kieślowski", 140, threeColours,
            "A woman rebuilds her life after losing her husband and daughter.",
            Watch.WATCHED, 0, 900, null, true, new Cast("Juliette Binoche", "Julie"));
        film(109, "Three Colours: White", 1994, 92, "Comedy, Drama", "Krzysztof Kieślowski", 140,
            threeColours, "A Polish hairdresser humiliated by divorce plans his revenge.",
            Watch.NONE, 0, 0, null, true,
            new Cast("Zbigniew Zamachowski", "Karol"), new Cast("Julie Delpy", "Dominique"));
        film(110, "Three Colours: Red", 1994, 99, "Drama, Mystery", "Krzysztof Kieślowski", 140,
            threeColours, "A model and a retired judge are drawn together by a chance encounter.",
            Watch.NONE, 0, 0, null, true,
            new Cast("Irène Jacob", "Valentine"), new Cast("Jean-Louis Trintignant", "The Judge"));
        LOG.info("Dev seed: {} films, {} collections", films.count(), collections.count());
    }

    private void bladeRunner(FilmCollection collection) {
        Film film = newFilm(78, "Blade Runner", 1982, "Sci-fi", "Ridley Scott", 350, collection,
            "In a rain-soaked Los Angeles of 2019, a weary detective is pressed back into service to hunt down"
                + " four escaped replicants who have come to Earth looking for more life.",
            new Cast("Harrison Ford", "Rick Deckard"), new Cast("Rutger Hauer", "Roy Batty"),
            new Cast("Sean Young", "Rachael"));
        Edition finalCut = edition(film, "The Final Cut", 2007, 117, null, 6_800_000L, true, false);
        edition(film, "Director’s Cut", 1992, 116, "No voice-over", null, true, false);
        edition(film, "Theatrical Cut", 1982, 117, "Original release", null, true, false);
        films.save(film);
        // The Final Cut was last watched in May 2019 (more than five years ago); the others are untouched.
        state(finalCut, 6_900_000L, true, daysAgo(2_730));
    }

    private void film(long tmdbId, String title, int year, int minutes, String genres, String director,
                      int addedDaysAgo, FilmCollection collection, String overview, Watch watch, long position,
                      long lastPlayedDaysAgo, Long marker, boolean subtitles, Cast... cast) {
        Film film = newFilm(tmdbId, title, year, genres, director, addedDaysAgo, collection, overview, cast);
        Edition edition = edition(film, "Theatrical Cut", year, minutes, null, marker, subtitles,
            title.equals("Heat"));
        films.save(film);
        switch (watch) {
            case PROGRESS -> state(edition, position, false, daysAgo(lastPlayedDaysAgo));
            case WATCHED -> state(edition, edition.getRuntimeMs(), true, daysAgo(lastPlayedDaysAgo));
            default -> { }
        }
    }

    private Film newFilm(long tmdbId, String title, int year, String genres, String director, int addedDaysAgo,
                         FilmCollection collection, String overview, Cast... cast) {
        Film film = new Film();
        film.setTmdbId(tmdbId);
        film.setTitleAndSort(title);
        film.setYear(year);
        film.setOverview(overview);
        film.setAddedAt(daysAgo(addedDaysAgo));
        film.setCollection(collection);
        for (String genre : genres.split(",\\s*")) {
            film.getGenres().add(genre);
        }
        credit(film, director, CreditRole.DIRECTOR, null, 0);
        int order = 1;
        for (Cast member : cast) {
            credit(film, member.name(), CreditRole.CAST, member.character(), order++);
        }
        return film;
    }

    private Edition edition(Film film, String name, int year, int minutes, String note, Long marker,
                            boolean subtitles, boolean commentary) {
        Edition edition = new Edition();
        edition.setFilm(film);
        edition.setName(name);
        edition.setYear(year);
        edition.setRuntimeMs(minutes * MINUTE);
        edition.setNote(note);
        edition.setCreditsMarkerMs(marker);
        MediaFile file = new MediaFile();
        file.setEdition(edition);
        file.setPath(sampleVideo == null || sampleVideo.isBlank()
            ? PLACEHOLDER_DIR + film.getSortTitle().replaceAll("[^a-z0-9]+", "-") + "-" + edition.getYear() + ".mp4"
            : sampleVideo);
        file.setKind(MediaKind.ORIGINAL);
        file.setContainer("mp4");
        file.setVideoCodec("HEVC");
        file.setWidth(1920);
        file.setHeight(1080);
        file.setBitrate(9_500_000L);
        file.setSizeBytes(edition.getRuntimeMs() / 1000 * 9_500_000L / 8);
        file.setDurationMs(edition.getRuntimeMs());
        file.setAddedAt(film.getAddedAt());
        file.getAudioStreams().add(audio(file, 0, "5.1", "DTS", null, true));
        file.getAudioStreams().add(audio(file, 1, "Stereo", "AAC", null, false));
        if (commentary) {
            file.getAudioStreams().add(audio(file, 2, "Stereo", "AAC", "Commentary — Michael Mann", false));
        }
        if (subtitles) {
            file.getSubtitleStreams().add(subtitle(file, 0, false, "English"));
            file.getSubtitleStreams().add(subtitle(file, 1, true, "English (forced)"));
        }
        edition.getMediaFiles().add(file);
        film.getEditions().add(edition);
        return edition;
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

    private static SubtitleStream subtitle(MediaFile file, int index, boolean forced, String title) {
        SubtitleStream stream = new SubtitleStream();
        stream.setMediaFile(file);
        stream.setIndex(index);
        stream.setLanguage("English");
        stream.setForced(forced);
        stream.setTitle(title);
        stream.setFormat("SRT");
        return stream;
    }

    private void credit(Film film, String name, CreditRole role, String character, int order) {
        Person person = personCache.computeIfAbsent(name, n -> people.save(new Person(n)));
        Credit credit = new Credit();
        credit.setFilm(film);
        credit.setPerson(person);
        credit.setRole(role);
        credit.setCharacter(character);
        credit.setSequence(order);
        film.getCredits().add(credit);
    }

    private void state(Edition edition, long position, boolean watched, Instant lastPlayed) {
        PlaybackState state = new PlaybackState(edition.getId());
        state.setPositionMs(position);
        state.setWatched(watched);
        state.setLastPlayedAt(lastPlayed);
        state.setWatchedAt(watched ? lastPlayed : null);
        states.save(state);
    }

    private FilmCollection collection(long tmdbId, String name, CollectionPart... parts) {
        FilmCollection collection = new FilmCollection();
        collection.setTmdbId(tmdbId);
        collection.setName(name);
        int order = 1;
        for (CollectionPart part : parts) {
            part.setCollection(collection);
            part.setReleaseOrder(order++);
            collection.getParts().add(part);
        }
        return collections.save(collection);
    }

    private static CollectionPart part(long tmdbId, String title, int year) {
        CollectionPart part = new CollectionPart();
        part.setTmdbId(tmdbId);
        part.setTitle(title);
        part.setYear(year);
        return part;
    }

    private static long pct(int minutes, int percent) {
        return minutes * MINUTE * percent / 100;
    }

    private Instant daysAgo(long days) {
        return now.minus(Duration.ofDays(days));
    }
}
