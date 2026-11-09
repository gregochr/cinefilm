package gregochr.cinefilm.transcode;

import static gregochr.cinefilm.transcode.Mp4Builder.box;
import static gregochr.cinefilm.transcode.Mp4Builder.box64;
import static gregochr.cinefilm.transcode.Mp4Builder.boxClaiming;
import static gregochr.cinefilm.transcode.Mp4Builder.boxToEnd;
import static gregochr.cinefilm.transcode.Mp4Builder.concat;
import static gregochr.cinefilm.transcode.Mp4Builder.ctts;
import static gregochr.cinefilm.transcode.Mp4Builder.elst;
import static gregochr.cinefilm.transcode.Mp4Builder.ftyp;
import static gregochr.cinefilm.transcode.Mp4Builder.fullBox;
import static gregochr.cinefilm.transcode.Mp4Builder.hdlr;
import static gregochr.cinefilm.transcode.Mp4Builder.mdat;
import static gregochr.cinefilm.transcode.Mp4Builder.mdhd;
import static gregochr.cinefilm.transcode.Mp4Builder.moov;
import static gregochr.cinefilm.transcode.Mp4Builder.moovAtEnd;
import static gregochr.cinefilm.transcode.Mp4Builder.moovFirst;
import static gregochr.cinefilm.transcode.Mp4Builder.mvhd;
import static gregochr.cinefilm.transcode.Mp4Builder.stbl;
import static gregochr.cinefilm.transcode.Mp4Builder.stss;
import static gregochr.cinefilm.transcode.Mp4Builder.stts;
import static gregochr.cinefilm.transcode.Mp4Builder.trak;
import static gregochr.cinefilm.transcode.Mp4Builder.u32;
import static gregochr.cinefilm.transcode.Mp4Builder.videoTrack;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The reader against MP4 files built byte by byte: what it reads and what it refuses. */
class Mp4SampleTableReaderTest {

    @TempDir
    Path dir;

    private final Mp4SampleTableReader reader = new Mp4SampleTableReader();

    private List<Long> read(byte[] file) throws IOException {
        Path path = dir.resolve("film.mp4");
        Files.write(path, file);
        return reader.index(path).keyframesMs();
    }

    private void assertRefused(byte[] file, String message) throws IOException {
        Path path = dir.resolve("bad.mp4");
        Files.write(path, file);
        assertThatThrownBy(() -> reader.index(path)).isInstanceOf(KeyframeIndexException.class)
            .hasMessage(message);
    }

    private static byte[] tracks(byte[]... children) {
        return moov(children);
    }

    private static byte[] videoWith(byte[]... tables) {
        return trak("vide", mdhd(0, 24_000), stbl(tables));
    }

    // ---- what it reads ---------------------------------------------------------------------------------------

    @Test
    void keyframeTimesComeFromTheSyncSamplesAndTheDecodeTimes() throws IOException {
        assertThat(read(moovFirst(tracks(videoTrack())))).containsExactly(0L, 1001L, 2002L, 3003L);
    }

    @Test
    void aMoovAtTheEndOfTheFileIsFoundBySeekingOverMdat() throws IOException {
        assertThat(read(moovAtEnd(tracks(videoTrack())))).containsExactly(0L, 1001L, 2002L, 3003L);
    }

    @Test
    void sixtyFourBitBoxSizesAreFollowedForMdatAndInsideMoov() throws IOException {
        byte[] trak = videoTrack();
        byte[] wideTrak = box64("trak", Arrays.copyOfRange(trak, 8, trak.length));
        byte[] file = concat(ftyp(), box64("mdat", new byte[300]), box64("moov", mvhd(1000), wideTrak));

        assertThat(read(file)).containsExactly(0L, 1001L, 2002L, 3003L);
    }

    @Test
    void aMoovWithSizeZeroExtendsToTheEndOfTheFile() throws IOException {
        assertThat(read(concat(ftyp(), mdat(10), boxToEnd("moov", mvhd(1000), videoTrack()))))
            .containsExactly(0L, 1001L, 2002L, 3003L);
    }

    @Test
    void aQuickTimeFileMayStartWithMdat() throws IOException {
        assertThat(read(concat(mdat(10), moov(videoTrack())))).containsExactly(0L, 1001L, 2002L, 3003L);
    }

    @Test
    void theFirstVideoTrackIsUsedNotAnAudioTrackBeforeIt() throws IOException {
        byte[] audio = trak("soun", mdhd(0, 44_100), stbl(stss(1), stts(new long[] {10, 1024})));

        assertThat(read(moovFirst(tracks(audio, videoTrack())))).containsExactly(0L, 1001L, 2002L, 3003L);
    }

    @Test
    void compositionOffsetsShiftPresentationTimes() throws IOException {
        byte[] file = moovFirst(tracks(videoWith(stss(1, 25, 49, 73), stts(new long[] {240, 1001}),
            ctts(0, new long[] {240, 2002}))));

        assertThat(read(file)).containsExactly(83L, 1084L, 2085L, 3086L);
    }

    @Test
    void theEditListStartOffsetCancelsTheCompositionOffsetOfTheFirstFrame() throws IOException {
        byte[] track = trak("vide", mdhd(0, 24_000), stbl(stss(1, 25, 49, 73), stts(new long[] {240, 1001}),
            ctts(0, new long[] {240, 2002})), elst(0, new long[] {10_000, 2002}));

        assertThat(read(moovFirst(tracks(track)))).containsExactly(0L, 1001L, 2002L, 3003L);
    }

    @Test
    void aSigned32BitCompositionOffsetsInVersionOneMayBeNegativeAndKeyframesBeforeZeroAreDropped()
        throws IOException {
        byte[] file = moovFirst(tracks(videoWith(stss(1, 25, 49, 73), stts(new long[] {240, 1001}),
            ctts(1, new long[] {240, -1001}))));

        assertThat(read(file)).containsExactly(959L, 1960L, 2961L);
    }

    @Test
    void aCompositionTableThatEndsEarlyMeansZeroOffsetForTheRest() throws IOException {
        byte[] file = moovFirst(tracks(videoWith(stss(1, 25, 49, 73), stts(new long[] {240, 1001}),
            ctts(0, new long[] {1, 2002}))));

        assertThat(read(file)).containsExactly(83L, 1001L, 2002L, 3003L);
    }

    @Test
    void aLeadingEmptyEditDelaysEverythingByItsLengthInMovieTimescale() throws IOException {
        byte[] track = videoTrack(elst(0, new long[] {1000, -1}, new long[] {5000, 0}));

        assertThat(read(moovFirst(tracks(track)))).containsExactly(1000L, 2001L, 3002L, 4003L);
    }

    @Test
    void versionOneEditListsAreRead() throws IOException {
        byte[] track = videoTrack(elst(1, new long[] {2500, -1}, new long[] {5000, 0}));

        assertThat(read(moovFirst(tracks(track)))).containsExactly(2500L, 3501L, 4502L, 5503L);
    }

    @Test
    void anEditThatSkipsTheStartDropsTheKeyframesBeforeIt() throws IOException {
        byte[] track = videoTrack(elst(0, new long[] {10_000, 24_024}));

        assertThat(read(moovFirst(tracks(track)))).containsExactly(0L, 1001L, 2002L);
    }

    @Test
    void anEditWithNoMediaAtAllLeavesTheTimesUnshifted() throws IOException {
        assertThat(read(moovFirst(tracks(videoTrack(elst(0, new long[] {1000, -1}))))))
            .containsExactly(0L, 1001L, 2002L, 3003L);
        assertThat(read(moovFirst(tracks(videoTrack(elst(0)))))).containsExactly(0L, 1001L, 2002L, 3003L);
    }

    @Test
    void theMovieTimescaleIsNeededOnlyForALeadingEmptyEdit() throws IOException {
        byte[] noMvhd = box("moov", videoTrack(elst(0, new long[] {1000, 0})));

        assertThat(read(concat(ftyp(), noMvhd))).containsExactly(0L, 1001L, 2002L, 3003L);
        assertRefused(concat(ftyp(), box("moov", videoTrack(elst(0, new long[] {1000, -1}, new long[] {5, 0})))),
            "moov has no mvhd");
    }

    @Test
    void versionOneMediaHeadersAreRead() throws IOException {
        byte[] track = trak("vide", mdhd(1, 24_000), stbl(stss(1, 25), stts(new long[] {240, 1001})));

        assertThat(read(moovFirst(tracks(track)))).containsExactly(0L, 1001L);
    }

    @Test
    void keyframesThatRoundToTheSameMillisecondAreListedOnce() throws IOException {
        byte[] file = moovFirst(tracks(videoWith(stss(1, 2), stts(new long[] {240, 1}))));

        assertThat(read(file)).containsExactly(0L);
    }

    @Test
    void runLengthEntriesAreWalkedAcrossSeveralSections() throws IOException {
        byte[] file = moovFirst(tracks(videoWith(stss(1, 11, 21), stts(new long[] {10, 1000}, new long[] {10, 500},
            new long[] {10, 2000}))));

        // timescale 24000: sample 11 at 10*1000 ticks, sample 21 at 10*1000 + 10*500 ticks
        assertThat(read(file)).containsExactly(0L, 417L, 625L);
    }

    @Test
    void aSyncSampleAtTheLastSampleOfTheTableIsAccepted() throws IOException {
        assertThat(read(moovFirst(tracks(videoWith(stss(10), stts(new long[] {10, 1001})))))).containsExactly(375L);
    }

    // ---- what it refuses -------------------------------------------------------------------------------------

    @Test
    void aFileThatIsNotAnMp4IsRefused() throws IOException {
        assertRefused("RIFF....AVI LIST".getBytes(java.nio.charset.StandardCharsets.US_ASCII), "Not an MP4 file");
        assertRefused(concat(u32(0x1A45DFA3L), new byte[20]), "Not an MP4 file");
    }

    @Test
    void anEmptyOrTinyFileHasNoMoov() throws IOException {
        assertRefused(new byte[0], "No moov box found");
        assertRefused(new byte[7], "No moov box found");
    }

    @Test
    void aFileWithoutMoovIsRefused() throws IOException {
        assertRefused(concat(ftyp(), mdat(100)), "No moov box found");
        assertRefused(concat(ftyp(), boxToEnd("mdat", new byte[50])), "No moov box found");
    }

    @Test
    void aMissingFileIsRefusedAsUnreadable() {
        assertThatThrownBy(() -> reader.index(dir.resolve("nope.mp4")))
            .isInstanceOf(KeyframeIndexException.class).hasMessageStartingWith("Cannot read the file's boxes");
    }

    @Test
    void aBoxSmallerThanItsOwnHeaderIsRefused() throws IOException {
        assertRefused(boxClaiming(4, "ftyp", new byte[12]), "Box smaller than its own header");
        assertRefused(concat(u32(1), Mp4Builder.type("ftyp"), Mp4Builder.u64(10), new byte[8]),
            "Box smaller than its own header");
    }

    @Test
    void aSixtyFourBitHeaderCutOffByTheEndOfTheFileIsRefused() throws IOException {
        assertRefused(concat(u32(1), Mp4Builder.type("ftyp")), "Truncated 64-bit box header");
    }

    @Test
    void aMoovLargerThanSixtyFourMegabytesIsRefusedBeforeAnythingIsAllocated() throws IOException {
        long limit = 64L * 1024 * 1024;

        assertRefused(concat(ftyp(), boxClaiming(8 + limit + 1, "moov")), "moov box is too large");
        assertRefused(concat(ftyp(), boxClaiming(8 + limit, "moov")), "moov box is truncated");
    }

    @Test
    void aTruncatedMoovIsRefused() throws IOException {
        byte[] whole = moovAtEnd(tracks(videoTrack()));

        assertRefused(Arrays.copyOf(whole, whole.length - 1), "moov box is truncated");
    }

    @Test
    void moreTopLevelBoxesThanTheLimitStopTheSearch() throws IOException {
        byte[] free = box("free");
        byte[] manyBefore = concat(ftyp(), repeat(free, 998), moov(videoTrack()));
        byte[] tooMany = concat(ftyp(), repeat(free, 999), moov(videoTrack()));

        assertThat(read(manyBefore)).hasSize(4);
        assertRefused(tooMany, "No moov box found");
    }

    @Test
    void moreBoxesThanTheLimitInsideOneParentAreRefused() throws IOException {
        byte[] free = box("free");

        assertThat(read(moovFirst(moov(repeat(free, 9998), videoTrack())))).hasSize(4);
        assertRefused(moovFirst(moov(repeat(free, 9999), videoTrack())), "Too many boxes");
    }

    @Test
    void strayBytesAfterTheLastChildBoxAreRefused() throws IOException {
        assertRefused(moovFirst(moov(videoTrack(), new byte[3])), "Truncated box header");
        assertRefused(moovFirst(moov(videoTrack(), u32(1), Mp4Builder.type("free"))), "Truncated 64-bit box header");
    }

    @Test
    void aChildThatClaimsMoreThanItsParentHoldsOrLessThanItsHeaderIsRefused() throws IOException {
        assertRefused(moovFirst(moov(boxClaiming(10_000, "trak", new byte[8]))), "Box size is out of bounds");
        assertRefused(moovFirst(moov(boxClaiming(4, "trak", new byte[8]))), "Box size is out of bounds");
    }

    @Test
    void aFileWithoutAVideoTrackIsRefused() throws IOException {
        byte[] audio = trak("soun", mdhd(0, 44_100), stbl(stss(1), stts(new long[] {10, 1024})));
        byte[] noMdia = box("trak", box("free"));
        byte[] shortHdlr = box("trak", box("mdia", mdhd(0, 1000), box("hdlr", new byte[4])));
        byte[] noHdlr = box("trak", box("mdia", mdhd(0, 1000)));

        assertRefused(moovFirst(moov(audio)), "No video track");
        assertRefused(moovFirst(moov(noMdia)), "No video track");
        assertRefused(moovFirst(moov(shortHdlr)), "No video track");
        assertRefused(moovFirst(moov(noHdlr)), "No video track");
        assertRefused(moovFirst(moov()), "No video track");
    }

    @Test
    void aVideoTrackMissingABoxOnThePathIsRefusedByName() throws IOException {
        assertRefused(moovFirst(moov(box("trak", box("mdia", hdlr("vide"))))), "video track has no minf");
        assertRefused(moovFirst(moov(box("trak", box("mdia", hdlr("vide"), box("minf"))))),
            "video track has no stbl");
        assertRefused(moovFirst(moov(box("trak", box("mdia", hdlr("vide"), box("minf", stbl(stss(1))))))),
            "video track has no mdhd");
        assertRefused(moovFirst(moov(trak("vide", mdhd(0, 24_000), stbl(stts(new long[] {1, 1}))))),
            "video track has no sync sample table");
        assertRefused(moovFirst(moov(trak("vide", mdhd(0, 24_000), stbl(stss(1))))), "video track has no stts");
    }

    @Test
    void aTrackWithNoKeyframesIsRefused() throws IOException {
        assertRefused(moovFirst(moov(videoWith(stss(), stts(new long[] {10, 1001})))), "No keyframes found");
    }

    @Test
    void syncSamplesMustIncreaseFromOne() throws IOException {
        assertRefused(moovFirst(moov(videoWith(stss(5, 5), stts(new long[] {10, 1001})))),
            "Sync samples are not increasing");
        assertRefused(moovFirst(moov(videoWith(stss(0), stts(new long[] {10, 1001})))),
            "Sync samples are not increasing");
        assertRefused(moovFirst(moov(videoWith(stss(7, 3), stts(new long[] {10, 1001})))),
            "Sync samples are not increasing");
    }

    @Test
    void aSyncSampleBeyondTheTimeTableIsRefused() throws IOException {
        assertRefused(moovFirst(moov(videoWith(stss(11), stts(new long[] {10, 1001})))),
            "Sync sample lies beyond the stts table");
    }

    @Test
    void tablesTooShortForTheirHeaderOrTheirDeclaredEntriesAreRefused() throws IOException {
        assertRefused(moovFirst(moov(videoWith(box("stss", new byte[4]), stts(new long[] {1, 1})))),
            "Table box is too short");
        assertRefused(moovFirst(moov(videoWith(fullBox("stss", 0, u32(1000)), stts(new long[] {1, 1})))),
            "Table entries do not fit");
        assertRefused(moovFirst(moov(videoWith(fullBox("stss", 0, u32(0xFFFFFFFFL)), stts(new long[] {1, 1})))),
            "Table entries do not fit");
        assertRefused(moovFirst(moov(videoWith(stss(1), fullBox("stts", 0, u32(3), new byte[8])))),
            "Table entries do not fit");
        assertRefused(moovFirst(moov(videoWith(stss(1), stts(new long[] {1, 1}), fullBox("ctts", 0, u32(2))))),
            "Table entries do not fit");
    }

    @Test
    void headerBoxesTooShortOrWithATimescaleOfZeroAreRefused() throws IOException {
        assertRefused(moovFirst(moov(trak("vide", box("mdhd", new byte[2]), stbl(stss(1),
            stts(new long[] {1, 1}))))), "Header box is too short");
        assertRefused(moovFirst(moov(trak("vide", box("mdhd", new byte[8]), stbl(stss(1),
            stts(new long[] {1, 1}))))), "Header box is too short");
        assertRefused(moovFirst(moov(trak("vide", fullBox("mdhd", 1, u32(0)), stbl(stss(1),
            stts(new long[] {1, 1}))))), "Header box is too short");
        assertRefused(moovFirst(moov(trak("vide", mdhd(0, 0), stbl(stss(1), stts(new long[] {1, 1}))))),
            "Timescale is zero");
    }

    @Test
    void editListsTooShortOrOverflowingAreRefused() throws IOException {
        assertRefused(moovFirst(moov(videoTrack(box("elst", new byte[4])))), "elst is too short");
        assertRefused(moovFirst(moov(videoTrack(fullBox("elst", 0, u32(5))))), "elst entries do not fit");
        assertRefused(moovFirst(moov(videoTrack(elst(1, new long[] {1, Long.MAX_VALUE})))),
            "Malformed MP4 sample tables");
    }

    @Test
    void aNonStandardBoxTypeCountsAsNoSuchBoxNotAsAnError() throws IOException {
        byte[] track = box("trak", box("mdia", mdhd(0, 1000), hdlr("vide"), box("minf", box("free"),
            stbl(stss(1), stts(new long[] {1, 1000})))), box("udta"));

        assertThat(read(moovFirst(moov(track)))).containsExactly(0L);
    }

    private static byte[] repeat(byte[] box, int times) {
        byte[][] parts = new byte[times][];
        Arrays.fill(parts, box);
        return concat(parts);
    }

    @Test
    void aChildWithSizeZeroExtendsToTheEndOfItsParent() throws IOException {
        byte[] trak = videoTrack();
        byte[] lastChild = boxToEnd("trak", Arrays.copyOfRange(trak, 8, trak.length));

        assertThat(read(moovFirst(box("moov", mvhd(1000), lastChild)))).containsExactly(0L, 1001L, 2002L, 3003L);
    }

    @Test
    void anEditListDeclaringMoreEntriesThanTheLimitIsRefused() throws IOException {
        assertRefused(moovFirst(moov(videoTrack(fullBox("elst", 0, u32(0xFFFFFFFFL))))),
            "elst entries do not fit");
    }

    @Test
    void theFrameRateIsSamplesOverTheTrackDurationFromTheTimeTable() throws IOException {
        Path path = dir.resolve("fps.mp4");
        Files.write(path, moovFirst(tracks(videoTrack())));

        assertThat(reader.index(path).fps()).isEqualTo(24_000.0 / 1001);
    }

    @Test
    void theFrameRateSpansEveryRunLengthSectionAndIsUnknownForATableWithNoTime() throws IOException {
        Path mixed = dir.resolve("mixed.mp4");
        Files.write(mixed, moovFirst(tracks(videoWith(stss(1), stts(new long[] {10, 1000}, new long[] {10, 500})))));
        Path empty = dir.resolve("empty.mp4");
        Files.write(empty, moovFirst(tracks(videoWith(stss(1), stts(new long[] {10, 0})))));

        assertThat(reader.index(mixed).fps()).isEqualTo(20.0 * 24_000 / 15_000);
        assertThat(reader.index(empty).fps()).isNull();
    }

    // ---- boundaries ------------------------------------------------------------------------------------------

    private static byte[] minimalVideoTrack(byte[] mdhd, byte[] hdlr) {
        return box("trak", box("mdia", mdhd, hdlr, box("minf", stbl(stss(1), stts(new long[] {1, 1000})))));
    }

    @Test
    void aSixtyFourBitBoxThatFillsExactlyTheRestOfItsParentIsAccepted() throws IOException {
        assertThat(read(moovFirst(moov(videoTrack(), box64("free"))))).containsExactly(0L, 1001L, 2002L, 3003L);
    }

    @Test
    void aChildClaimingFourBytesMoreThanTheRestOfItsParentIsRefused() throws IOException {
        assertRefused(moovFirst(moov(videoTrack(), boxClaiming(20, "free", new byte[8]))),
            "Box size is out of bounds");
    }

    @Test
    void aHandlerBoxOfExactlyTwelveBytesIsLongEnoughToName() throws IOException {
        byte[] hdlr = fullBox("hdlr", 0, u32(0), Mp4Builder.type("vide"));

        assertThat(read(moovFirst(moov(minimalVideoTrack(mdhd(0, 1000), hdlr))))).containsExactly(0L);
    }

    @Test
    void aMediaHeaderOfExactlyTheBytesItNeedsIsRead() throws IOException {
        byte[] version0 = fullBox("mdhd", 0, u32(0), u32(0), u32(1000));
        byte[] version1 = fullBox("mdhd", 1, Mp4Builder.u64(0), Mp4Builder.u64(0), u32(1000));

        assertThat(read(moovFirst(moov(minimalVideoTrack(version0, hdlr("vide")))))).containsExactly(0L);
        assertThat(read(moovFirst(moov(minimalVideoTrack(version1, hdlr("vide")))))).containsExactly(0L);
    }

    @Test
    void anEmptyMediaHeaderAtTheVeryEndOfTheMovieIsRefusedAsTooShort() throws IOException {
        byte[] track = box("trak", box("mdia", hdlr("vide"), box("minf", stbl(stss(1),
            stts(new long[] {1, 1000}))), box("mdhd")));

        assertRefused(moovFirst(moov(track)), "Header box is too short");
    }

    @Test
    void aTableWithExactlyTheMostEntriesAllowedIsReadAndFlaggedForItsContents() throws IOException {
        byte[] stss = fullBox("stss", 0, u32(Mp4SampleTableReader.MAX_ENTRIES),
            new byte[Mp4SampleTableReader.MAX_ENTRIES * 4]);

        assertRefused(moovFirst(moov(videoWith(stss, stts(new long[] {1, 1})))), "Sync samples are not increasing");
    }

    @Test
    void anEditListWithExactlyTheMostEntriesAllowedIsRead() throws IOException {
        byte[] elst = fullBox("elst", 0, u32(Mp4SampleTableReader.MAX_ENTRIES),
            new byte[Mp4SampleTableReader.MAX_ENTRIES * 12]);

        assertThat(read(moovFirst(moov(videoTrack(elst))))).containsExactly(0L, 1001L, 2002L, 3003L);
    }

    @Test
    void unequalRunLengthsAreReadEntryByEntryForTimesAndFrameRate() throws IOException {
        Path path = dir.resolve("unequal.mp4");
        Files.write(path, moovFirst(tracks(videoWith(stss(1, 11, 40),
            stts(new long[] {10, 1000}, new long[] {30, 500})))));

        KeyframeIndex index = reader.index(path);

        assertThat(index.keyframesMs()).containsExactly(0L, 417L, 1021L);
        assertThat(index.fps()).isEqualTo(40.0 * 24_000 / 25_000);
    }
}
