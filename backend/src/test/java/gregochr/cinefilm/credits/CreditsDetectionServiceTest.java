package gregochr.cinefilm.credits;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.TestData.state;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.credits.CreditsDetectionService.DueFile;
import gregochr.cinefilm.credits.CreditsDetectionService.SaveOutcome;
import gregochr.cinefilm.credits.CreditsFrameReader.ReadException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.MediaKind;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.playback.PlaybackState;
import gregochr.cinefilm.subtitle.EmbeddedCueStarts;
import gregochr.cinefilm.subtitle.EmbeddedCueStartsRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The database side of credits detection against the real repositories on H2 (which files are due and in what order,
 * and the save, which revalidates the file and takes the library lock), and the analysis of one file against a
 * stand-in for ffmpeg that writes a film whose credits crawl up from 7:30.
 */
class CreditsDetectionServiceTest extends ApiTestBase {

    private static final Instant LATER = NOW.plus(Duration.ofHours(1));
    /** Where the stand-in film's credits start. */
    private static final long CREDITS_AT = 450_000;
    private static final long FILM_LENGTH = 10 * MIN;

    @TempDir
    Path cache;

    @Autowired
    private CreditsDetectionService service;

    @Autowired
    private CreditsDetectionRepository detections;

    @Autowired
    private EditionRepository editions;

    @Autowired
    private MediaFileRepository mediaFiles;

    @Autowired
    private EmbeddedCueStartsRepository cueStarts;

    @Autowired
    private LibraryLock lock;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory factory;

    private Statistics statistics;

    @BeforeEach
    void enableStatistics() {
        statistics = factory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
    }

    @AfterEach
    void disableStatistics() {
        statistics.setStatisticsEnabled(false);
    }

    // ---- Fixtures ------------------------------------------------------------------------------------------------

    /** A film with one edition of {@code parts} one-hour files, each with a size and modification time. */
    private Film filmOf(String title, int parts, Instant addedAt) {
        Film built = film(null, title, 2000, addedAt);
        Edition edition = edition(built, null, "Original", parts * 60 * MIN, null);
        for (int i = 1; i <= parts; i++) {
            MediaFile file = file(edition, parts == 1 ? title + ".m4v" : title + " - pt" + i + ".m4v");
            file.setPartNumber(i);
            file.setDurationMs(60 * MIN);
            file.setSizeBytes(1_000L + i);
            file.setModifiedAt(NOW);
        }
        return films.saveAndFlush(built);
    }

    private Film filmOf(String title) {
        return filmOf(title, 1, NOW);
    }

    private static MediaFile part(Film film, int number) {
        return film.getEditions().get(0).originalParts().get(number - 1);
    }

    private static Long fileId(Film film) {
        return part(film, 1).getId();
    }

    private List<Long> dueIds() {
        return service.dueFiles().stream().map(DueFile::mediaFileId).toList();
    }

    private void stored(MediaFile file, String fingerprint) {
        CreditsDetection row = new CreditsDetection();
        row.setMediaFileId(file.getId());
        row.setFingerprint(fingerprint);
        row.setVerdict(CreditsVerdict.NOT_FOUND);
        row.setReasons("No credits");
        row.setCheckedAt(NOW);
        detections.saveAndFlush(row);
    }

    private static DueFile due(MediaFile file) {
        return new DueFile(file.getId(), file.getPath(), file.fingerprint(), 60 * MIN, "Film");
    }

    // ---- Which files are due -------------------------------------------------------------------------------------

    @Test
    void aFilmWithNoStoredResultIsDueWithItsFileFingerprintLengthAndTitle() {
        Film film = filmOf("Taken");

        assertThat(service.dueFiles()).containsExactly(
            new DueFile(fileId(film), "Taken.m4v", "1001:" + NOW.toEpochMilli(), 60 * MIN, "Taken"));
    }

    @Test
    void onlyTheLastPartOfASplitEditionIsDue() {
        Film film = filmOf("The Long Film", 2, NOW);

        assertThat(dueIds()).containsExactly(part(film, 2).getId());
    }

    @Test
    void theLastOfThreePartsIsTheOnlyOneDue() {
        Film film = filmOf("Three Parts", 3, NOW);

        assertThat(dueIds()).containsExactly(part(film, 3).getId());
    }

    @Test
    void aFileWhoseStoredResultMatchesItsFingerprintIsNotDue() {
        Film film = filmOf("Taken");
        stored(part(film, 1), part(film, 1).fingerprint());

        assertThat(dueIds()).isEmpty();
    }

    @Test
    void aChangedFileIsDueAgain() {
        Film film = filmOf("Taken");
        MediaFile file = part(film, 1);
        stored(file, file.fingerprint());
        file.setSizeBytes(file.getSizeBytes() + 1);
        mediaFiles.saveAndFlush(file);

        assertThat(dueIds()).containsExactly(file.getId());
    }

    @Test
    void aFileTouchedAtAnotherTimeIsDueAgain() {
        Film film = filmOf("Taken");
        MediaFile file = part(film, 1);
        stored(file, file.fingerprint());
        file.setModifiedAt(NOW.plusMillis(1));
        mediaFiles.saveAndFlush(file);

        assertThat(dueIds()).containsExactly(file.getId());
    }

    @Test
    void aStoredResultOfThePartThatIsNoLongerLastDoesNotMakeTheNewLastPartDone() {
        Film film = filmOf("The Long Film", 2, NOW);
        stored(part(film, 1), part(film, 1).fingerprint());

        assertThat(dueIds()).containsExactly(part(film, 2).getId());
    }

    @Test
    void filesTheAnalysisCannotReadAreNotDue() {
        Film missing = filmOf("Missing");
        part(missing, 1).setPresence(Presence.MISSING);
        Film broken = filmOf("Broken");
        part(broken, 1).setPlayability(Playability.NEEDS_CONVERSION);
        Film noSize = filmOf("No size");
        part(noSize, 1).setSizeBytes(null);
        Film noTime = filmOf("No time");
        part(noTime, 1).setModifiedAt(null);
        Film noDuration = filmOf("No duration");
        part(noDuration, 1).setDurationMs(null);
        Film zeroDuration = filmOf("Zero duration");
        part(zeroDuration, 1).setDurationMs(0L);
        Film fine = filmOf("Fine");
        films.flush();
        entityManager.clear();

        assertThat(dueIds()).containsExactly(fileId(fine));
    }

    @Test
    void aFilesLengthIsItsVideoDurationWhenKnown() {
        Film film = filmOf("Taken");
        part(film, 1).setVideoDurationMs(59 * MIN);
        films.saveAndFlush(film);

        assertThat(service.dueFiles()).extracting(DueFile::durationMs).containsExactly(59 * MIN);
    }

    @Test
    void anEditionWithOnlyPremadeRenditionsIsNotDue() {
        Film film = filmOf("Taken");
        part(film, 1).setKind(MediaKind.PREMADE_720);
        films.saveAndFlush(film);

        assertThat(service.dueFiles()).isEmpty();
    }

    @Test
    void filmsInProgressComeFirstThenTheMostRecentlyAddedFiles() {
        Film oldest = filmOf("Oldest", 1, NOW.minus(Duration.ofDays(30)));
        Film newest = filmOf("Newest", 1, NOW.minus(Duration.ofDays(1)));
        Film middle = filmOf("Middle", 1, NOW.minus(Duration.ofDays(10)));
        saveState(state(oldest.getEditions().get(0), 5 * MIN, false, NOW, null));

        assertThat(dueIds()).containsExactly(fileId(oldest), fileId(newest), fileId(middle));
    }

    @Test
    void aFilmRemovedFromContinueWatchingOrNotStartedIsNotMovedUp() {
        Film oldest = filmOf("Oldest", 1, NOW.minus(Duration.ofDays(30)));
        Film hidden = filmOf("Hidden", 1, NOW.minus(Duration.ofDays(20)));
        Film unstarted = filmOf("Unstarted", 1, NOW.minus(Duration.ofDays(10)));
        Film newest = filmOf("Newest", 1, NOW.minus(Duration.ofDays(1)));
        PlaybackState hiddenState = state(hidden.getEditions().get(0), 5 * MIN, false, NOW, null);
        hiddenState.setHiddenFromContinueWatching(true);
        saveState(hiddenState);
        saveState(state(unstarted.getEditions().get(0), 0, true, NOW, NOW));

        assertThat(dueIds()).containsExactly(fileId(newest), fileId(unstarted), fileId(hidden), fileId(oldest));
    }

    @Test
    void filesAddedAtTheSameTimeComeNewestIdFirst() {
        Film first = filmOf("First", 1, NOW);
        Film second = filmOf("Second", 1, NOW);

        assertThat(dueIds()).containsExactly(fileId(second), fileId(first));
    }

    @Test
    void theNumberOfStatementsDoesNotGrowWithTheNumberOfFilms() {
        filmOf("One", 1, NOW);
        long withOne = statementsOfOneCall();

        filmOf("Two", 2, NOW);
        filmOf("Three", 3, NOW);
        long withThree = statementsOfOneCall();

        assertThat(withOne).isPositive();
        assertThat(withThree).isEqualTo(withOne);
    }

    private long statementsOfOneCall() {
        entityManager.flush();
        entityManager.clear();
        statistics.clear();
        service.dueFiles();
        return statistics.getPrepareStatementCount();
    }

    // ---- Saving --------------------------------------------------------------------------------------------------

    @Test
    void aFoundResultIsStoredAgainstTheFileWithItsFingerprintOffsetReasonsAndTime() {
        MediaFile file = part(filmOf("Taken"), 1);
        CreditsResult result = new CreditsResult(CreditsVerdict.FOUND, 3_600_500L,
            List.of("Text moves up from 1:00:00", "Nobody is talking"));

        SaveOutcome outcome = service.save(due(file), result, NOW);

        assertThat(outcome).isEqualTo(SaveOutcome.SAVED);
        CreditsDetection row = detections.findById(file.getId()).orElseThrow();
        assertThat(row.getFingerprint()).isEqualTo(file.fingerprint());
        assertThat(row.getVerdict()).isEqualTo(CreditsVerdict.FOUND);
        assertThat(row.getOffsetMs()).isEqualTo(3_600_500L);
        assertThat(row.getReasons()).isEqualTo("Text moves up from 1:00:00\nNobody is talking");
        assertThat(row.getCheckedAt()).isEqualTo(NOW);
    }

    @Test
    void aNotFoundResultHasNoOffsetAndAnUnsureOneMayHaveOne() {
        MediaFile first = part(filmOf("Taken"), 1);
        MediaFile second = part(filmOf("Taken 2"), 1);

        service.save(due(first), new CreditsResult(CreditsVerdict.NOT_FOUND, null, List.of("Nothing")), NOW);
        service.save(due(second), new CreditsResult(CreditsVerdict.UNSURE, 1_000L, List.of("Maybe")), NOW);

        assertThat(detections.findById(first.getId()).orElseThrow().getVerdict()).isEqualTo(CreditsVerdict.NOT_FOUND);
        assertThat(detections.findById(first.getId()).orElseThrow().getOffsetMs()).isNull();
        assertThat(detections.findById(second.getId()).orElseThrow().getVerdict()).isEqualTo(CreditsVerdict.UNSURE);
        assertThat(detections.findById(second.getId()).orElseThrow().getOffsetMs()).isEqualTo(1_000L);
    }

    @Test
    void savingAgainReplacesTheFilesOneRow() {
        MediaFile file = part(filmOf("Taken"), 1);
        service.save(due(file), new CreditsResult(CreditsVerdict.NOT_FOUND, null, List.of("Nothing")), NOW);

        service.save(due(file), new CreditsResult(CreditsVerdict.FOUND, 99L, List.of("Found it")), LATER);

        assertThat(detections.findAll()).hasSize(1);
        CreditsDetection row = detections.findById(file.getId()).orElseThrow();
        assertThat(row.getVerdict()).isEqualTo(CreditsVerdict.FOUND);
        assertThat(row.getOffsetMs()).isEqualTo(99L);
        assertThat(row.getCheckedAt()).isEqualTo(LATER);
    }

    @Test
    void reasonsAreCutAtTwoThousandCharacters() {
        MediaFile file = part(filmOf("Taken"), 1);

        service.save(due(file), new CreditsResult(CreditsVerdict.UNSURE, null, List.of("x".repeat(2_001))), NOW);
        MediaFile exact = part(filmOf("Exact"), 1);
        service.save(due(exact), new CreditsResult(CreditsVerdict.UNSURE, null, List.of("y".repeat(2_000))), NOW);

        assertThat(detections.findById(file.getId()).orElseThrow().getReasons()).isEqualTo("x".repeat(2_000));
        assertThat(detections.findById(exact.getId()).orElseThrow().getReasons()).isEqualTo("y".repeat(2_000));
    }

    @Test
    void aResultForAFileThatChangedWhileItWasAnalysedIsDroppedAndTheFileIsDueAgain() {
        MediaFile file = part(filmOf("Taken"), 1);
        DueFile asAnalysed = due(file);
        file.setSizeBytes(file.getSizeBytes() + 1);
        mediaFiles.saveAndFlush(file);

        SaveOutcome outcome = service.save(asAnalysed, new CreditsResult(CreditsVerdict.NOT_FOUND, null,
            List.of("Nothing")), NOW);

        assertThat(outcome).isEqualTo(SaveOutcome.DROPPED);
        assertThat(detections.findAll()).isEmpty();
        assertThat(dueIds()).containsExactly(file.getId());
    }

    @Test
    void aResultForAFileThatIsNoLongerPresentIsDropped() {
        MediaFile file = part(filmOf("Taken"), 1);
        DueFile asAnalysed = due(file);
        file.setPresence(Presence.MISSING);
        mediaFiles.saveAndFlush(file);

        assertThat(service.save(asAnalysed, new CreditsResult(CreditsVerdict.NOT_FOUND, null, List.of("Nothing")),
            NOW)).isEqualTo(SaveOutcome.DROPPED);
        assertThat(detections.findAll()).isEmpty();
    }

    @Test
    void aResultForAFileThatWasRemovedIsDropped() {
        DueFile gone = new DueFile(987_654L, "Gone.m4v", "1:1", 60 * MIN, "Gone");

        assertThat(service.save(gone, new CreditsResult(CreditsVerdict.NOT_FOUND, null, List.of("Nothing")), NOW))
            .isEqualTo(SaveOutcome.DROPPED);
    }

    @Test
    void whileAScanHoldsTheLockNothingIsStoredAndTheLockStaysWithTheScan() {
        MediaFile file = part(filmOf("Taken"), 1);
        assertThat(lock.tryAcquire(LibraryLock.SCAN)).isTrue();

        SaveOutcome outcome = service.save(due(file), new CreditsResult(CreditsVerdict.NOT_FOUND, null,
            List.of("Nothing")), NOW);

        assertThat(outcome).isEqualTo(SaveOutcome.BUSY);
        assertThat(detections.findAll()).isEmpty();
        assertThat(lock.heldBy(LibraryLock.SCAN)).isTrue();
        lock.release();
    }

    @Test
    void theLockIsTakenForTheWriteAndGivenBackAfterIt() {
        MediaFile file = part(filmOf("Taken"), 1);

        service.save(due(file), new CreditsResult(CreditsVerdict.NOT_FOUND, null, List.of("Nothing")), NOW);

        assertThat(lock.holder()).isNull();
        assertThat(lock.tryAcquire(LibraryLock.SCAN)).isTrue();
        lock.release();
    }

    @Test
    void theLockIsGivenBackEvenWhenTheWriteFails() {
        MediaFile file = part(filmOf("Taken"), 1);
        CreditsResult result = new CreditsResult(CreditsVerdict.UNSURE, null, List.of("x"));
        DueFile badFingerprint = new DueFile(file.getId(), file.getPath(), null, 60 * MIN, "Taken");

        assertThatThrownBy(() -> service.save(badFingerprint, result, NOW)).isInstanceOf(NullPointerException.class);

        assertThat(lock.holder()).isNull();
    }

    // ---- Analysing one file --------------------------------------------------------------------------------------

    private CreditsDetectionService serviceReading(FakeFfmpeg ffmpeg) {
        CreditsFrameReader reader = new CreditsFrameReader(ffmpeg, "ffmpeg", 0, cache.toString());
        return new CreditsDetectionService(editions, mediaFiles, detections, cueStarts, states, reader, lock,
            transactions);
    }

    /** A film of picture whose credits start crawling up at {@link #CREDITS_AT}. */
    private static FakeFfmpeg filmWithCrawl() {
        FakeFfmpeg ffmpeg = new FakeFfmpeg();
        ffmpeg.frameAt = ms -> ms < CREDITS_AT ? SyntheticFrames.picture()
            : SyntheticFrames.crawl((int) ((ms - CREDITS_AT) / 500), 3);
        return ffmpeg;
    }

    private DueFile tenMinuteFilm() {
        MediaFile file = part(filmOf("Taken"), 1);
        return new DueFile(file.getId(), file.getPath(), file.fingerprint(), FILM_LENGTH, "Taken");
    }

    private void cachedCues(DueFile due, String fingerprint, EmbeddedCueStarts.Status status, List<Long> starts) {
        EmbeddedCueStarts row = new EmbeddedCueStarts();
        row.setMediaFileId(due.mediaFileId());
        row.setFingerprint(fingerprint);
        row.setStatus(status);
        row.setSubtitleStreamCount(2);
        row.setStartsMs(starts);
        row.setReadAt(NOW);
        cueStarts.saveAndFlush(row);
    }

    @Test
    void anEndingWithACrawlIsFoundWhereTheCrawlStartsAfterOneSeekPerSampleAndOneDenseDecode() {
        FakeFfmpeg ffmpeg = filmWithCrawl();
        DueFile due = tenMinuteFilm();

        CreditsResult result = serviceReading(ffmpeg).detect(due, Path.of("/library/Taken.m4v"), () -> { });

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.FOUND);
        assertThat(result.offsetMs()).isBetween(CREDITS_AT, CREDITS_AT + 1_000);
        // Five active-area seeks, 48 coarse ones (7:30 to 10:00 is the last 4 minutes and then some) and one decode.
        assertThat(ffmpeg.commands).hasSize(5 + 48 + 1);
        assertThat(ffmpeg.commands.getLast()).contains("-start_number", "-t");
        assertThat(cache.resolve("credits")).isEmptyDirectory();
    }

    @Test
    void anEndingThatIsAllPictureIsNotFoundWithoutADenseDecode() {
        FakeFfmpeg ffmpeg = new FakeFfmpeg();

        CreditsResult result = serviceReading(ffmpeg).detect(tenMinuteFilm(), Path.of("/library/Taken.m4v"),
            () -> { });

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.NOT_FOUND);
        assertThat(result.offsetMs()).isNull();
        assertThat(result.reasons()).containsExactly("The ending has no black screen and no text on a dark screen");
        assertThat(ffmpeg.commands).hasSize(5 + 48);
        assertThat(cache.resolve("credits")).isEmptyDirectory();
    }

    @Test
    void dialogueInTheCachedCueStartsPastTheCreditsStartMakesTheResultUnsure() {
        DueFile due = tenMinuteFilm();
        cachedCues(due, due.fingerprint(), EmbeddedCueStarts.Status.USABLE, List.of(300_000L, 460_000L, 485_000L));

        CreditsResult result = serviceReading(filmWithCrawl()).detect(due, Path.of("/library/Taken.m4v"), () -> { });

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.UNSURE);
        assertThat(result.reasons()).singleElement().asString().startsWith("Someone is still talking");
    }

    @Test
    void cachedCueStartsThatEndBeforeTheCreditsLeaveTheResultFound() {
        DueFile due = tenMinuteFilm();
        cachedCues(due, due.fingerprint(), EmbeddedCueStarts.Status.USABLE, List.of(300_000L, 440_000L));

        assertThat(serviceReading(filmWithCrawl()).detect(due, Path.of("/library/Taken.m4v"), () -> { }).verdict())
            .isEqualTo(CreditsVerdict.FOUND);
    }

    @Test
    void cueStartsReadFromAnOlderVersionOfTheFileAreIgnored() {
        DueFile due = tenMinuteFilm();
        cachedCues(due, "1:1", EmbeddedCueStarts.Status.USABLE, List.of(300_000L, 460_000L, 485_000L));

        assertThat(serviceReading(filmWithCrawl()).detect(due, Path.of("/library/Taken.m4v"), () -> { }).verdict())
            .isEqualTo(CreditsVerdict.FOUND);
    }

    @Test
    void aFileWithNoUsableEnglishTrackIsAnalysedWithoutDialogue() {
        DueFile due = tenMinuteFilm();
        cachedCues(due, due.fingerprint(), EmbeddedCueStarts.Status.NO_REFERENCE, List.of());

        assertThat(serviceReading(filmWithCrawl()).detect(due, Path.of("/library/Taken.m4v"), () -> { }).verdict())
            .isEqualTo(CreditsVerdict.FOUND);
    }

    @Test
    void aFailingFfmpegAbandonsTheWholeFileAndLeavesNoDirectory() {
        FakeFfmpeg ffmpeg = filmWithCrawl();
        ffmpeg.failOnCall = 10;

        assertThatThrownBy(() -> serviceReading(ffmpeg).detect(tenMinuteFilm(), Path.of("/library/Taken.m4v"),
            () -> { })).isInstanceOf(ReadException.class);

        assertThat(ffmpeg.commands).hasSize(10);
        assertThat(cache.resolve("credits")).isEmptyDirectory();
    }

    @Test
    void aFailingDenseDecodeAbandonsTheFileToo() {
        FakeFfmpeg ffmpeg = filmWithCrawl();
        ffmpeg.failOnCall = 5 + 48 + 1;

        assertThatThrownBy(() -> serviceReading(ffmpeg).detect(tenMinuteFilm(), Path.of("/library/Taken.m4v"),
            () -> { })).isInstanceOf(ReadException.class);

        assertThat(cache.resolve("credits")).isEmptyDirectory();
    }

    @Test
    void aCheckpointThatStopsTheRunBetweenTheCoarseAndDensePassesStopsBeforeTheDenseDecode() {
        FakeFfmpeg ffmpeg = filmWithCrawl();
        Runnable stopsBeforeTheDenseDecode = () -> {
            if (ffmpeg.commands.size() == 5 + 48) {
                throw new IllegalStateException("playback started");
            }
        };

        assertThatThrownBy(() -> serviceReading(ffmpeg).detect(tenMinuteFilm(), Path.of("/library/Taken.m4v"),
            stopsBeforeTheDenseDecode)).hasMessage("playback started");

        assertThat(ffmpeg.commands).hasSize(5 + 48);
        assertThat(cache.resolve("credits")).isEmptyDirectory();
    }

    @Test
    void nothingIsStoredByAnalysingAFile() {
        serviceReading(filmWithCrawl()).detect(tenMinuteFilm(), Path.of("/library/Taken.m4v"), () -> { });

        assertThat(detections.findAll()).isEmpty();
    }
}
