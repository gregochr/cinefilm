package gregochr.cinefilm.credits.learn;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.plex.PlexCreditsMarker;
import gregochr.cinefilm.plex.PlexCreditsMarkerRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/**
 * Which Plex markers become dataset targets: the file must still be the one the marker describes, be the last
 * original part of its edition, and the marker must lie inside the window of the file's end.
 */
@ExtendWith(MockitoExtension.class)
class DatasetTargetsTest {

    /** A 100 minute film: its window is the last 35 minutes, from 65 minutes. */
    private static final long LENGTH = 100 * MIN;
    private static final long INSIDE = 95 * MIN;

    @Mock
    private PlexCreditsMarkerRepository markers;

    @Mock
    private EditionRepository editions;

    @Mock
    private MediaFileRepository files;

    private final Logger targetsLog = (Logger) LoggerFactory.getLogger(DatasetTargets.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final List<Edition> library = new ArrayList<>();
    private final List<PlexCreditsMarker> stored = new ArrayList<>();
    private DatasetTargets datasetTargets;

    @BeforeEach
    void setUp() {
        datasetTargets = new DatasetTargets(markers, editions, files);
        logged.start();
        targetsLog.addAppender(logged);
    }

    @AfterEach
    void detachLog() {
        targetsLog.detachAppender(logged);
    }

    private List<String> messages() {
        return logged.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** A single-file edition: a file of 100 minutes with a fingerprint, present and playable. */
    private MediaFile single(long id) {
        Film film = film(id, "Film " + id, 2000, NOW);
        Edition edition = edition(film, id, "Original", LENGTH, null);
        MediaFile file = file(edition, "f" + id + ".m4v");
        file.setId(id);
        file.setDurationMs(LENGTH);
        file.setSizeBytes(1_000L + id);
        file.setModifiedAt(NOW);
        library.add(edition);
        return file;
    }

    private void marked(MediaFile file, long startMs) {
        PlexCreditsMarker marker = new PlexCreditsMarker();
        marker.setMediaFileId(file.getId());
        marker.setFingerprint(file.fingerprint());
        marker.setStartMs(startMs);
        marker.setImportedAt(NOW);
        stored.add(marker);
    }

    private List<DatasetTarget> targets() {
        when(editions.findAllWithFiles()).thenReturn(library);
        when(markers.findAll()).thenReturn(stored);
        return datasetTargets.targets();
    }

    @Test
    void aMarkerWhoseFileIsUnchangedAndInsideTheWindowBecomesATargetWithTheFilesOwnValues() {
        MediaFile file = single(4);
        marked(file, INSIDE);

        assertThat(targets()).containsExactly(new DatasetTarget(4L, "f4.m4v", "1004:" + NOW.toEpochMilli(), LENGTH,
            INSIDE));
        assertThat(messages()).containsExactly("Credits dataset targets: 1 files; markers left out: none");
    }

    @Test
    void theTargetsAreOrderedByMediaFileIdWhateverOrderTheMarkersAreStored() {
        MediaFile three = single(3);
        MediaFile one = single(1);
        MediaFile two = single(2);
        marked(three, INSIDE);
        marked(one, INSIDE);
        marked(two, INSIDE);

        assertThat(targets()).extracting(DatasetTarget::mediaFileId).containsExactly(1L, 2L, 3L);
    }

    @Test
    void theTimelineDurationIsUsedWhenTheVideoStreamIsKnown() {
        MediaFile file = single(1);
        file.setVideoDurationMs(90 * MIN);
        marked(file, 80 * MIN);

        assertThat(targets()).extracting(DatasetTarget::durationMs).containsExactly(90 * MIN);
    }

    @Test
    void aMarkerWhoseFileIsNotInTheLibraryIsLeftOut() {
        PlexCreditsMarker orphan = new PlexCreditsMarker();
        orphan.setMediaFileId(99L);
        orphan.setFingerprint("1:1");
        orphan.setStartMs(INSIDE);
        stored.add(orphan);

        assertThat(targets()).isEmpty();
        assertThat(messages()).containsExactly(
            "Credits dataset targets: 0 files; markers left out: 1 because its file is not in the library");
    }

    @Test
    void aFingerprintThatChangedSinceTheImportIsLeftOut() {
        MediaFile file = single(1);
        marked(file, INSIDE);
        file.setSizeBytes(5L);

        assertThat(targets()).isEmpty();
        assertThat(messages()).containsExactly("Credits dataset targets: 0 files; markers left out: "
            + "1 because its file changed since the import, or has no fingerprint");
    }

    @Test
    void aFileWithNoFingerprintIsLeftOut() {
        MediaFile file = single(1);
        marked(file, INSIDE);
        file.setModifiedAt(null);

        assertThat(targets()).isEmpty();
    }

    @Test
    void aMissingFileIsLeftOut() {
        MediaFile file = single(1);
        marked(file, INSIDE);
        file.setPresence(Presence.MISSING);

        assertThat(targets()).isEmpty();
        assertThat(messages()).containsExactly(
            "Credits dataset targets: 0 files; markers left out: 1 because its file is missing or not playable");
    }

    @Test
    void aFileThatIsNotPlayableIsLeftOut() {
        MediaFile file = single(1);
        marked(file, INSIDE);
        file.setPlayability(Playability.NEEDS_CONVERSION);

        assertThat(targets()).isEmpty();
    }

    @Test
    void aFileWithNoDurationIsLeftOut() {
        MediaFile file = single(1);
        marked(file, INSIDE);
        file.setDurationMs(null);

        assertThat(targets()).isEmpty();
        assertThat(messages()).containsExactly(
            "Credits dataset targets: 0 files; markers left out: 1 because its file has no duration");
    }

    @Test
    void aFileWithAZeroDurationIsLeftOut() {
        MediaFile file = single(1);
        marked(file, INSIDE);
        file.setDurationMs(0L);

        assertThat(targets()).isEmpty();
        assertThat(messages()).containsExactly(
            "Credits dataset targets: 0 files; markers left out: 1 because its file has no duration");
    }

    @Test
    void onlyTheLastPartOfASplitEditionCanBeATarget() {
        Film film = film(1L, "Split", 2000, NOW);
        Edition edition = edition(film, 1L, "Original", 2 * LENGTH, null);
        MediaFile first = file(edition, "pt1.m4v");
        first.setId(10L);
        first.setPartNumber(1);
        MediaFile second = file(edition, "pt2.m4v");
        second.setId(11L);
        second.setPartNumber(2);
        for (MediaFile part : List.of(first, second)) {
            part.setDurationMs(LENGTH);
            part.setSizeBytes(part.getId());
            part.setModifiedAt(NOW);
        }
        library.add(edition);
        marked(first, INSIDE);
        marked(second, INSIDE);

        assertThat(targets()).extracting(DatasetTarget::mediaFileId).containsExactly(11L);
        assertThat(messages()).containsExactly("Credits dataset targets: 1 files; markers left out: "
            + "1 because its file is not the last part of its edition");
    }

    @Test
    void aMarkerBeforeTheWindowIsLeftOutAndOneExactlyAtItsStartIsKept() {
        MediaFile before = single(1);
        MediaFile at = single(2);
        marked(before, 65 * MIN - 1);
        marked(at, 65 * MIN);

        assertThat(targets()).extracting(DatasetTarget::mediaFileId).containsExactly(2L);
        assertThat(messages()).containsExactly(
            "Credits dataset targets: 1 files; markers left out: 1 because it is outside the window of the file's end");
    }

    @Test
    void aMarkerAtOrPastTheDurationIsLeftOutAndOneJustBeforeItIsKept() {
        MediaFile pastEnd = single(1);
        MediaFile atEnd = single(2);
        MediaFile lastMilli = single(3);
        marked(pastEnd, LENGTH + 1);
        marked(atEnd, LENGTH);
        marked(lastMilli, LENGTH - 1);

        assertThat(targets()).extracting(DatasetTarget::mediaFileId).containsExactly(3L);
    }

    @Test
    void theWindowIsFortyPercentOfAShortFile() {
        Film film = film(1L, "Short", 2000, NOW);
        Edition edition = edition(film, 1L, "Original", 50 * MIN, null);
        MediaFile file = file(edition, "short.m4v");
        file.setId(1L);
        file.setDurationMs(50 * MIN);
        file.setSizeBytes(1L);
        file.setModifiedAt(NOW);
        library.add(edition);
        MediaFile other = single(2);
        marked(file, 30 * MIN);
        marked(other, 30 * MIN);

        // 40% of 50 minutes is 20: the window starts at 30 minutes. For the 100 minute film it starts at 65.
        assertThat(targets()).extracting(DatasetTarget::mediaFileId).containsExactly(1L);
    }

    @Test
    void severalReasonsAreCountedEachOnceInTheLog() {
        MediaFile a = single(1);
        MediaFile b = single(2);
        MediaFile c = single(3);
        MediaFile kept = single(4);
        marked(a, 1);
        marked(b, 2);
        marked(c, LENGTH);
        marked(kept, INSIDE);
        a.setPresence(Presence.MISSING);
        b.setPresence(Presence.MISSING);

        assertThat(targets()).extracting(DatasetTarget::mediaFileId).containsExactly(4L);
        assertThat(messages()).containsExactly("Credits dataset targets: 1 files; markers left out: "
            + "2 because its file is missing or not playable, 1 because it is outside the window of the file's end");
    }

    @Test
    void aFileWithNoOriginalPartsHasNoTarget() {
        Film film = film(5L, "Empty", 2000, NOW);
        library.add(edition(film, 5L, "Original", LENGTH, null));
        PlexCreditsMarker marker = new PlexCreditsMarker();
        marker.setMediaFileId(5L);
        marker.setFingerprint("1:1");
        marker.setStartMs(INSIDE);
        stored.add(marker);

        assertThat(targets()).isEmpty();
    }

    // ---- stillCurrent --------------------------------------------------------------------------------------------

    private DatasetTarget target(MediaFile file) {
        return new DatasetTarget(file.getId(), file.getPath(), file.fingerprint(), LENGTH, INSIDE);
    }

    @Test
    void aFilePresentWithTheSameFingerprintIsStillCurrent() {
        MediaFile file = single(1);
        when(files.findById(1L)).thenReturn(Optional.of(file));

        assertThat(datasetTargets.stillCurrent(target(file))).isTrue();
    }

    @Test
    void aFileWithAnotherFingerprintIsNotCurrent() {
        MediaFile file = single(1);
        DatasetTarget target = target(file);
        file.setSizeBytes(77L);
        when(files.findById(1L)).thenReturn(Optional.of(file));

        assertThat(datasetTargets.stillCurrent(target)).isFalse();
    }

    @Test
    void aMissingFileIsNotCurrent() {
        MediaFile file = single(1);
        DatasetTarget target = target(file);
        file.setPresence(Presence.MISSING);
        when(files.findById(1L)).thenReturn(Optional.of(file));

        assertThat(datasetTargets.stillCurrent(target)).isFalse();
    }

    @Test
    void aFileThatIsGoneFromTheDatabaseIsNotCurrent() {
        MediaFile file = single(1);
        when(files.findById(1L)).thenReturn(Optional.empty());

        assertThat(datasetTargets.stillCurrent(target(file))).isFalse();
    }
}
