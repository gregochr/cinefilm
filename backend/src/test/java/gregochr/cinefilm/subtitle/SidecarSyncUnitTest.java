package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.MediaFileRepository.Known;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.LibraryRoot;
import gregochr.cinefilm.library.LibraryScanner.Found;
import gregochr.cinefilm.library.LibraryScanner.FoundSidecar;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The sidecar sync's decisions, with the repositories faked and real files in a temporary library. */
@ExtendWith(MockitoExtension.class)
class SidecarSyncUnitTest {

    private static final Instant T1 = Instant.parse("2023-05-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2023-06-01T10:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(60);
    private static final String CUES = "1\n00:00:10,000 --> 00:00:12,000\nHello\n\n";

    @TempDir
    Path library;

    @Mock
    private SubtitleSidecarRepository sidecars;

    @Mock
    private MediaFileRepository files;

    private final List<SubtitleSidecar> rows = new ArrayList<>();
    private SidecarSync sync;

    @BeforeEach
    void setUp() {
        sync = new SidecarSync(sidecars, files, new LibraryRoot(library.toString()), CLOCK);
    }

    /** The in-memory table and the one film file every test that syncs needs, stubbed where it is used. */
    private void stubRepositories() {
        when(sidecars.findAll()).thenAnswer(call -> List.copyOf(rows));
        when(sidecars.save(any(SubtitleSidecar.class))).thenAnswer(call -> {
            SubtitleSidecar saved = call.getArgument(0);
            if (!rows.contains(saved)) {
                rows.add(saved);
            }
            return saved;
        });
        when(files.findKnownOriginals()).thenReturn(List.of(
            new Known(1L, "Taken.m4v", 10L, T1, Presence.PRESENT, Playability.PLAYABLE, "yuv420p")));
    }

    private SidecarSync at(Instant instant) {
        return new SidecarSync(sidecars, files, new LibraryRoot(library.toString()),
            Clock.fixed(instant, ZoneOffset.UTC));
    }

    private FoundSidecar write(String name, String text, Instant modified) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Path path = library.resolve(name);
        Files.write(path, bytes);
        Files.setLastModifiedTime(path, FileTime.from(modified));
        return new FoundSidecar(name, bytes.length, modified);
    }

    private static Found found(FoundSidecar... sidecars) {
        return new Found(List.of(), 0, List.of(), List.of(sidecars));
    }

    @Test
    void aNewFileIsParsedStoredAndMatchedByExactName() throws IOException {
        stubRepositories();
        sync.sync(found(write("Taken.en.forced.srt", CUES, T1)));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getCuesText()).isEqualTo(CUES);
            assertThat(row.getCueCount()).isEqualTo(1);
            assertThat(row.getLastEndMs()).isEqualTo(12_000);
            assertThat(row.getParseReport()).isEqualTo("No problems found");
            assertThat(row.getPresence()).isEqualTo(Presence.PRESENT);
            assertThat(row.getMediaFileId()).isEqualTo(1L);
            assertThat(row.isWholeEdition()).isFalse();
            assertThat(row.getUpdatedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void aNameThatIsNotExactlyAFilmsIsLeftUnmatched() throws IOException {
        stubRepositories();
        sync.sync(found(write("Taken - pt1.en.forced.srt", CUES, T1)));

        assertThat(rows).singleElement().satisfies(row -> assertThat(row.getMediaFileId()).isNull());
    }

    @Test
    void anUnchangedFileIsNotReadAgainAndAMissingOneComesBackPresent() throws IOException {
        stubRepositories();
        FoundSidecar listed = write("Taken.en.forced.srt", CUES, T1);
        sync.sync(found(listed));
        sync.sync(found());
        assertThat(rows.get(0).getPresence()).isEqualTo(Presence.MISSING);
        Files.writeString(library.resolve("Taken.en.forced.srt"), CUES.replace("Hello", "Jello"));

        at(LATER).sync(found(listed));

        assertThat(rows.get(0).getPresence()).isEqualTo(Presence.PRESENT);
        assertThat(rows.get(0).getCuesText()).contains("Hello");
        assertThat(rows.get(0).getUpdatedAt()).isEqualTo(LATER);
    }

    @Test
    void aChangedFileIsReadAgainAndLosesTheOwnersCheckButKeepsTheOffset() throws IOException {
        stubRepositories();
        sync.sync(found(write("Taken.en.forced.srt", CUES, T1)));
        rows.get(0).setOffsetMs(250);
        rows.get(0).setOwnerVerifiedAt(NOW);
        rows.get(0).setVerifiedParts("1");

        at(LATER).sync(found(write("Taken.en.forced.srt", CUES.replace("Hello", "Edited"), T2)));

        SubtitleSidecar row = rows.get(0);
        assertThat(row.getCuesText()).contains("Edited");
        assertThat(row.getOffsetMs()).isEqualTo(250);
        assertThat(row.getOwnerVerifiedAt()).isNull();
        assertThat(row.getVerifiedParts()).isNull();
    }

    @Test
    void aFileNotFoundIsMissingUnlessItIsUnderAnUnreadableFolder() throws IOException {
        stubRepositories();
        sync.sync(found(write("Taken.en.forced.srt", CUES, T1)));
        Files.createDirectories(library.resolve("Locked"));
        sync.sync(found(write("Locked/Heat.en.forced.srt", CUES, T1)));
        rows.forEach(row -> row.setPresence(Presence.PRESENT));

        at(LATER).sync(new Found(List.of(), 0, List.of("Locked"), List.of()));

        assertThat(rows).extracting(SubtitleSidecar::getPath, SubtitleSidecar::getPresence)
            .containsExactly(tuple("Taken.en.forced.srt", Presence.MISSING),
                tuple("Locked/Heat.en.forced.srt", Presence.PRESENT));
    }

    @Test
    void aChangedFileThatCannotBeReadStopsPlayingAndANewOneThatCannotIsSkipped() throws IOException {
        stubRepositories();
        sync.sync(found(write("Taken.en.forced.srt", CUES, T1)));
        Files.delete(library.resolve("Taken.en.forced.srt"));

        at(LATER).sync(found(new FoundSidecar("Taken.en.forced.srt", 99, T2),
            new FoundSidecar("Gone.en.forced.srt", 5, T2)));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getPresence()).isEqualTo(Presence.MISSING);
            assertThat(row.getSizeBytes()).isEqualTo(CUES.length());
            assertThat(row.getUpdatedAt()).isEqualTo(LATER);
        });
    }

    @Test
    void anOwnerAttachedFileIsNeverMatchedAgainButRematchGivesOneBack() throws IOException {
        stubRepositories();
        sync.sync(found(write("Taken.en.forced.srt", CUES, T1)));
        SubtitleSidecar row = rows.get(0);
        row.setOwnerAttached(true);
        row.setMediaFileId(7L);
        row.setWholeEdition(true);

        sync.sync(found(write("Taken.en.forced.srt", CUES, T1)));
        assertThat(row.getMediaFileId()).isEqualTo(7L);

        row.setOwnerAttached(false);
        sync.rematch(row);
        assertThat(row.getMediaFileId()).isEqualTo(1L);
        assertThat(row.isWholeEdition()).isFalse();
    }
}
