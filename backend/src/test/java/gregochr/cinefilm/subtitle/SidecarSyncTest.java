package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.LibraryRoot;
import gregochr.cinefilm.library.LibraryScanner.Found;
import gregochr.cinefilm.library.LibraryScanner.FoundSidecar;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The scan's sidecar sync against real files in a temporary library and the real repositories on H2. */
class SidecarSyncTest extends SubtitleTestBase {

    private static final Instant T1 = Instant.parse("2023-05-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2023-06-01T10:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(3600);

    @TempDir
    Path library;

    private SidecarSync sync;

    @BeforeEach
    void setUp() {
        sync = new SidecarSync(sidecars, mediaFiles, new LibraryRoot(library.toString()), CLOCK);
    }

    private Path write(String relative, byte[] content, Instant modified) throws IOException {
        Path path = library.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.write(path, content);
        Files.setLastModifiedTime(path, FileTime.from(modified));
        return path;
    }

    private static FoundSidecar listed(String relative, byte[] content, Instant modified) {
        return new FoundSidecar(relative, content.length, modified);
    }

    private static Found found(FoundSidecar... files) {
        return new Found(List.of(), 0, List.of(), List.of(files));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aNewFileIsReadParsedStoredAndMatchedToItsFilm() throws IOException {
        Film taken = filmWithParts("Taken", 1, 90);
        byte[] content = bytes("﻿1\r\n00:00:10,000 --> 00:00:12,000\r\nHello\r\n\r\n2\r\n"
            + "00:00:20,000 --> 00:00:22,500\r\n<i>World</i>\r\n");
        write("Taken.en.forced.srt", content, T1);

        sync.sync(found(listed("Taken.en.forced.srt", content, T1)));

        SubtitleSidecar row = sidecars.findAll().get(0);
        assertThat(row.getPath()).isEqualTo("Taken.en.forced.srt");
        assertThat(row.getSizeBytes()).isEqualTo(content.length);
        assertThat(row.getModifiedAt()).isEqualTo(T1);
        assertThat(row.getPresence()).isEqualTo(Presence.PRESENT);
        assertThat(row.getCueCount()).isEqualTo(2);
        assertThat(row.getLastEndMs()).isEqualTo(22_500);
        assertThat(row.getCuesText()).isEqualTo(CUES);
        assertThat(row.getParseReport()).isEqualTo("No problems found");
        assertThat(row.getMediaFileId()).isEqualTo(fileId(taken, 1));
        assertThat(row.isWholeEdition()).isFalse();
        assertThat(row.isOwnerAttached()).isFalse();
        assertThat(row.getOffsetMs()).isZero();
        assertThat(row.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void aWindows1252FileIsStoredAsUtf8WithTheReport() throws IOException {
        byte[] content = "1\n00:00:01,000 --> 00:00:02,000\nCafé\n".getBytes(Charset.forName("windows-1252"));
        write("Heat.en.forced.srt", content, T1);

        sync.sync(found(listed("Heat.en.forced.srt", content, T1)));

        SubtitleSidecar row = sidecars.findAll().get(0);
        assertThat(row.getCuesText()).contains("Café");
        assertThat(row.getParseReport()).isEqualTo("read as Windows-1252");
        assertThat(row.getMediaFileId()).isNull();
    }

    @Test
    void aFileWithNoUsableCuesIsStoredWithZeroCues() throws IOException {
        byte[] content = bytes("not a subtitle file");
        write("Heat.en.forced.srt", content, T1);

        sync.sync(found(listed("Heat.en.forced.srt", content, T1)));

        SubtitleSidecar row = sidecars.findAll().get(0);
        assertThat(row.getCueCount()).isZero();
        assertThat(row.getLastEndMs()).isZero();
        assertThat(row.getParseReport()).isEqualTo("1 block skipped, no usable cues");
    }

    @Test
    void aFileOverOneMegabyteIsStoredUnreadWithTheReport() throws IOException {
        byte[] content = new byte[SrtParser.MAX_BYTES + 5];
        write("Heat.en.forced.srt", content, T1);

        sync.sync(found(listed("Heat.en.forced.srt", content, T1)));

        SubtitleSidecar row = sidecars.findAll().get(0);
        assertThat(row.getCueCount()).isZero();
        assertThat(row.getParseReport()).isEqualTo("file is larger than 1 MB, so it was not read");
    }

    @Test
    void anUnchangedFileIsNotReadAgainAndASecondSyncChangesNothing() throws IOException {
        filmWithParts("Taken", 1, 90);
        byte[] content = bytes(CUES);
        Path file = write("Taken.en.forced.srt", content, T1);
        sync.sync(found(listed("Taken.en.forced.srt", content, T1)));
        SubtitleSidecar row = sidecars.findAll().get(0);
        row.setOffsetMs(500);
        // Same size and modified time but different text: only a re-read would notice.
        Files.write(file, bytes(CUES.replace("Hello", "Jello")));
        Files.setLastModifiedTime(file, FileTime.from(T1));

        new SidecarSync(sidecars, mediaFiles, new LibraryRoot(library.toString()),
            Clock.fixed(LATER, ZoneOffset.UTC)).sync(found(listed("Taken.en.forced.srt", content, T1)));

        assertThat(sidecars.findAll()).hasSize(1);
        SubtitleSidecar again = sidecars.findAll().get(0);
        assertThat(again.getCuesText()).contains("Hello").doesNotContain("Jello");
        assertThat(again.getUpdatedAt()).isEqualTo(NOW);
        assertThat(again.getOffsetMs()).isEqualTo(500);
    }

    @Test
    void aChangedFileIsReadAgainKeepingTheOwnersOffsetAndAttachmentButLosingTheirCheck() throws IOException {
        Film taken = filmWithParts("Taken", 1, 90);
        byte[] content = bytes(CUES);
        write("Taken.en.forced.srt", content, T1);
        sync.sync(found(listed("Taken.en.forced.srt", content, T1)));
        SubtitleSidecar row = sidecars.findAll().get(0);
        row.setOffsetMs(750);
        row.setOwnerVerifiedAt(NOW);
        row.setVerifiedParts(String.valueOf(fileId(taken, 1)));
        byte[] edited = bytes("1\n00:00:30,000 --> 00:00:31,000\nEdited\n");
        write("Taken.en.forced.srt", edited, T2);

        new SidecarSync(sidecars, mediaFiles, new LibraryRoot(library.toString()),
            Clock.fixed(LATER, ZoneOffset.UTC)).sync(found(listed("Taken.en.forced.srt", edited, T2)));

        assertThat(sidecars.findAll()).hasSize(1);
        SubtitleSidecar again = sidecars.findAll().get(0);
        assertThat(again.getCuesText()).isEqualTo("1\n00:00:30,000 --> 00:00:31,000\nEdited\n\n");
        assertThat(again.getCueCount()).isEqualTo(1);
        assertThat(again.getLastEndMs()).isEqualTo(31_000);
        assertThat(again.getSizeBytes()).isEqualTo(edited.length);
        assertThat(again.getModifiedAt()).isEqualTo(T2);
        assertThat(again.getUpdatedAt()).isEqualTo(LATER);
        assertThat(again.getOffsetMs()).isEqualTo(750);
        assertThat(again.getOwnerVerifiedAt()).isNull();
        assertThat(again.getVerifiedParts()).isNull();
    }

    @Test
    void aSameSizeFileWithANewModifiedTimeIsReadAgain() throws IOException {
        byte[] content = bytes(CUES);
        write("Heat.en.forced.srt", content, T1);
        sync.sync(found(listed("Heat.en.forced.srt", content, T1)));
        byte[] sameSize = bytes(CUES.replace("Hello", "Jello"));
        write("Heat.en.forced.srt", sameSize, T2);

        sync.sync(found(listed("Heat.en.forced.srt", sameSize, T2)));

        assertThat(sidecars.findAll().get(0).getCuesText()).contains("Jello");
    }

    @Test
    void aFileThatIsNotFoundIsMarkedMissingNeverDeletedAndKeepsTheOwnersWork() throws IOException {
        Film taken = filmWithParts("Taken", 1, 90);
        byte[] content = bytes(CUES);
        write("Taken.en.forced.srt", content, T1);
        sync.sync(found(listed("Taken.en.forced.srt", content, T1)));
        sidecars.findAll().get(0).setOffsetMs(750);

        new SidecarSync(sidecars, mediaFiles, new LibraryRoot(library.toString()),
            Clock.fixed(LATER, ZoneOffset.UTC)).sync(found());

        assertThat(sidecars.findAll()).hasSize(1);
        SubtitleSidecar row = sidecars.findAll().get(0);
        assertThat(row.getPresence()).isEqualTo(Presence.MISSING);
        assertThat(row.getUpdatedAt()).isEqualTo(LATER);
        assertThat(row.getOffsetMs()).isEqualTo(750);
        assertThat(row.getMediaFileId()).isEqualTo(fileId(taken, 1));
    }

    @Test
    void aMissingFileThatComesBackUnchangedIsPresentAgainWithoutBeingReread() throws IOException {
        byte[] content = bytes(CUES);
        write("Heat.en.forced.srt", content, T1);
        sync.sync(found(listed("Heat.en.forced.srt", content, T1)));
        sync.sync(found());
        assertThat(sidecars.findAll().get(0).getPresence()).isEqualTo(Presence.MISSING);

        new SidecarSync(sidecars, mediaFiles, new LibraryRoot(library.toString()),
            Clock.fixed(LATER, ZoneOffset.UTC)).sync(found(listed("Heat.en.forced.srt", content, T1)));

        SubtitleSidecar row = sidecars.findAll().get(0);
        assertThat(row.getPresence()).isEqualTo(Presence.PRESENT);
        assertThat(row.getUpdatedAt()).isEqualTo(LATER);
    }

    @Test
    void aMissingFileThatComesBackChangedIsReadAndPresent() throws IOException {
        byte[] content = bytes(CUES);
        write("Heat.en.forced.srt", content, T1);
        sync.sync(found(listed("Heat.en.forced.srt", content, T1)));
        sync.sync(found());
        byte[] edited = bytes("1\n00:00:30,000 --> 00:00:31,000\nEdited\n");
        write("Heat.en.forced.srt", edited, T2);

        sync.sync(found(listed("Heat.en.forced.srt", edited, T2)));

        SubtitleSidecar row = sidecars.findAll().get(0);
        assertThat(row.getPresence()).isEqualTo(Presence.PRESENT);
        assertThat(row.getCuesText()).contains("Edited");
    }

    @Test
    void aFileUnderAFolderTheWalkCouldNotReadIsLeftAsItWas() throws IOException {
        byte[] content = bytes(CUES);
        write("Locked/Heat.en.forced.srt", content, T1);
        write("Open/Alien.en.forced.srt", content, T1);
        sync.sync(found(listed("Locked/Heat.en.forced.srt", content, T1), listed("Open/Alien.en.forced.srt", content,
            T1)));

        sync.sync(new Found(List.of(), 0, List.of("Locked"), List.of()));

        assertThat(sidecars.findAllByOrderByPathAscIdAsc())
            .extracting(SubtitleSidecar::getPath, SubtitleSidecar::getPresence)
            .containsExactly(tuple("Locked/Heat.en.forced.srt", Presence.PRESENT),
                tuple("Open/Alien.en.forced.srt", Presence.MISSING));
    }

    @Test
    void aMissingRowStaysMissingOnTheNextScanWithoutChangingAgain() throws IOException {
        byte[] content = bytes(CUES);
        write("Heat.en.forced.srt", content, T1);
        sync.sync(found(listed("Heat.en.forced.srt", content, T1)));
        sync.sync(found());

        new SidecarSync(sidecars, mediaFiles, new LibraryRoot(library.toString()),
            Clock.fixed(LATER, ZoneOffset.UTC)).sync(found());

        SubtitleSidecar row = sidecars.findAll().get(0);
        assertThat(row.getPresence()).isEqualTo(Presence.MISSING);
        assertThat(row.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void aChangedFileThatCannotBeReadStopsPlayingAndComesBackWithItsNewCuesWhenReadable() throws IOException {
        byte[] content = bytes(CUES);
        write("Heat.en.forced.srt", content, T1);
        sync.sync(found(listed("Heat.en.forced.srt", content, T1)));
        byte[] edited = bytes("1\n00:00:30,000 --> 00:00:31,000\nEdited\n");

        // The walk saw a change, but the file is gone by the time it is read.
        Files.delete(library.resolve("Heat.en.forced.srt"));
        new SidecarSync(sidecars, mediaFiles, new LibraryRoot(library.toString()),
            Clock.fixed(LATER, ZoneOffset.UTC)).sync(found(listed("Heat.en.forced.srt", edited, T2)));

        SubtitleSidecar row = sidecars.findAll().get(0);
        assertThat(row.getPresence()).isEqualTo(Presence.MISSING);
        assertThat(row.getUpdatedAt()).isEqualTo(LATER);
        assertThat(row.getSizeBytes()).isEqualTo(content.length);
        assertThat(row.getModifiedAt()).isEqualTo(T1);

        write("Heat.en.forced.srt", edited, T2);
        sync.sync(found(listed("Heat.en.forced.srt", edited, T2)));

        assertThat(row.getPresence()).isEqualTo(Presence.PRESENT);
        assertThat(row.getCuesText()).contains("Edited");
    }

    @Test
    void aChangedUnreadableFileThatIsAlreadyMissingIsLeftAlone() throws IOException {
        byte[] content = bytes(CUES);
        write("Heat.en.forced.srt", content, T1);
        sync.sync(found(listed("Heat.en.forced.srt", content, T1)));
        sync.sync(found());
        Files.delete(library.resolve("Heat.en.forced.srt"));

        new SidecarSync(sidecars, mediaFiles, new LibraryRoot(library.toString()),
            Clock.fixed(LATER, ZoneOffset.UTC)).sync(found(listed("Heat.en.forced.srt", bytes("x"), T2)));

        assertThat(sidecars.findAll().get(0).getPresence()).isEqualTo(Presence.MISSING);
        assertThat(sidecars.findAll().get(0).getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void aListedFileThatCannotBeResolvedUnderTheRootIsSkippedAndNoRowIsMade() {
        sync.sync(found(new FoundSidecar("Gone.en.forced.srt", 10, T1)));

        assertThat(sidecars.findAll()).isEmpty();
    }

    @Test
    void aFileThatCannotBeReadIsSkippedAndNoRowIsMade() throws IOException {
        Path file = write("Heat.en.forced.srt", bytes(CUES), T1);
        assumeFalse(!file.toFile().setReadable(false) || Files.isReadable(file),
            "running as a user that ignores file permissions");
        try {
            sync.sync(found(listed("Heat.en.forced.srt", bytes(CUES), T1)));

            assertThat(sidecars.findAll()).isEmpty();
        } finally {
            assertThat(file.toFile().setReadable(true)).isTrue();
        }
    }

    @Test
    void aFilmAddedLaterIsMatchedOnTheNextSyncEvenThoughTheFileDidNotChange() throws IOException {
        byte[] content = bytes(CUES);
        write("Taken.en.forced.srt", content, T1);
        sync.sync(found(listed("Taken.en.forced.srt", content, T1)));
        assertThat(sidecars.findAll().get(0).getMediaFileId()).isNull();
        Film taken = filmWithParts("Taken", 1, 90);

        new SidecarSync(sidecars, mediaFiles, new LibraryRoot(library.toString()),
            Clock.fixed(LATER, ZoneOffset.UTC)).sync(found(listed("Taken.en.forced.srt", content, T1)));

        SubtitleSidecar row = sidecars.findAll().get(0);
        assertThat(row.getMediaFileId()).isEqualTo(fileId(taken, 1));
        assertThat(row.getUpdatedAt()).isEqualTo(LATER);
    }

    @Test
    void aWholeEditionNameForASplitFilmIsLeftUnmatchedAndAPartNameMatchesItsPart() throws IOException {
        Film towers = filmWithParts("Towers", 2, 60);
        byte[] content = bytes(CUES);
        write("Towers.en.forced.srt", content, T1);
        write("Towers - pt2.en.forced.srt", content, T1);

        sync.sync(found(listed("Towers.en.forced.srt", content, T1),
            listed("Towers - pt2.en.forced.srt", content, T1)));

        assertThat(sidecars.findAllByOrderByPathAscIdAsc())
            .extracting(SubtitleSidecar::getPath, SubtitleSidecar::getMediaFileId, SubtitleSidecar::isWholeEdition)
            .containsExactly(tuple("Towers - pt2.en.forced.srt", fileId(towers, 2), false),
                tuple("Towers.en.forced.srt", null, false));
    }

    @Test
    void aFileTheOwnerAttachedIsNeverMatchedAgain() throws IOException {
        filmWithParts("Taken", 1, 90);
        Film heat = filmWithParts("Heat", 1, 90);
        byte[] content = bytes(CUES);
        write("Taken.en.forced.srt", content, T1);
        sync.sync(found(listed("Taken.en.forced.srt", content, T1)));
        SubtitleSidecar row = sidecars.findAll().get(0);
        row.setMediaFileId(fileId(heat, 1));
        row.setOwnerAttached(true);

        sync.sync(found(listed("Taken.en.forced.srt", content, T1)));

        assertThat(sidecars.findAll().get(0).getMediaFileId()).isEqualTo(fileId(heat, 1));
    }

    @Test
    void aWrongWholeEditionFlagOnAMatchedFileIsPutRightFromItsName() throws IOException {
        Film taken = filmWithParts("Taken", 1, 90);
        byte[] content = bytes(CUES);
        write("Taken.en.forced.srt", content, T1);
        sync.sync(found(listed("Taken.en.forced.srt", content, T1)));
        SubtitleSidecar row = sidecars.findAll().get(0);
        assertThat(row.getMediaFileId()).isEqualTo(fileId(taken, 1));
        row.setWholeEdition(true);

        sync.sync(found(listed("Taken.en.forced.srt", content, T1)));

        assertThat(sidecars.findAll().get(0).isWholeEdition()).isFalse();
        assertThat(sidecars.findAll().get(0).getMediaFileId()).isEqualTo(fileId(taken, 1));
    }

    @Test
    void aFileWhoseFilmIsNoLongerInTheLibraryBecomesUnmatched() throws IOException {
        Film heat = filmWithParts("Heat", 1, 90);
        byte[] content = bytes(CUES);
        write("Alien.en.forced.srt", content, T1);
        sync.sync(found(listed("Alien.en.forced.srt", content, T1)));
        SubtitleSidecar row = sidecars.findAll().get(0);
        row.setMediaFileId(fileId(heat, 1));
        row.setWholeEdition(true);

        sync.sync(found(listed("Alien.en.forced.srt", content, T1)));

        assertThat(sidecars.findAll().get(0).getMediaFileId()).isNull();
        assertThat(sidecars.findAll().get(0).isWholeEdition()).isFalse();
    }

    @Test
    void rematchGivesAFileBackToAutomaticMatching() {
        Film taken = filmWithParts("Taken", 1, 90);
        SubtitleSidecar row = sidecar("Taken.en.forced.srt", null, false);

        sync.rematch(row);

        assertThat(row.getMediaFileId()).isEqualTo(fileId(taken, 1));
        assertThat(row.getUpdatedAt()).isEqualTo(NOW);
    }
}
