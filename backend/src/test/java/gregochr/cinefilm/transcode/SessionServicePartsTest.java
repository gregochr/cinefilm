package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.transcode.SessionDtos.CreateRequest;
import gregochr.cinefilm.transcode.SessionSources.AudioChoice;
import gregochr.cinefilm.transcode.SessionSources.Part;
import gregochr.cinefilm.transcode.SessionSources.Source;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Creating a session for a film split across two files: each part 100 segments of 4 s, 400 000 ms of video (so part 2
 * starts at 400 000 ms and is segments 100 to 199 of the film), parts that match and are 8-bit 4:2:0 H.264.
 */
@ExtendWith(MockitoExtension.class)
class SessionServicePartsTest {

    private static final long EDITION_ID = 40L;
    private static final String ID = "5b0f1c1e-6a52-4d5e-9a4c-0a7d3c1f2b11";
    private static final long PART_MS = 400_000;
    private static final CutList CUTS = CutList.fromKeyframes(
        IntStream.range(0, 100).mapToObj(i -> i * 4000L).toList(), PART_MS);
    private static final KeyframeIndexService.Index INDEX_1 = new KeyframeIndexService.Index(CUTS, 25.0);
    private static final KeyframeIndexService.Index INDEX_2 = new KeyframeIndexService.Index(CUTS, 24.0);
    private static final List<AudioChoice> AUDIO_1 = List.of(new AudioChoice(100, 0, true, "English"),
        new AudioChoice(101, 1, false, "French"));
    private static final List<AudioChoice> AUDIO_2 = List.of(new AudioChoice(200, 0, false, "French"),
        new AudioChoice(201, 1, true, "English"));

    @Mock
    private SessionSources sources;
    @Mock
    private KeyframeIndexService indexes;
    @Mock
    private Transcoder transcoder;
    @Mock
    private SessionManager manager;

    private SessionService service;
    private MediaFile file1;
    private MediaFile file2;

    private static MediaFile file(long id, int height) {
        MediaFile file = new MediaFile();
        file.setId(id);
        file.setDurationMs(PART_MS + 1_040);
        file.setVideoDurationMs(PART_MS);
        file.setHeight(height);
        file.setWidth(height * 24 / 10);
        file.setBitrate(15_000_000L);
        file.setVideoCodec("H.264");
        file.setPixelFormat("yuv420p");
        return file;
    }

    @BeforeEach
    void setUp() {
        service = new SessionService(sources, indexes, transcoder, manager);
        file1 = file(7, 800);
        file2 = file(8, 800);
    }

    private void source(boolean partsMatch) {
        when(sources.find(EDITION_ID)).thenReturn(new Source(EDITION_ID, "Towers", List.of(
            new Part(file1, Path.of("/library/pt1.m4v"), AUDIO_1, PART_MS),
            new Part(file2, Path.of("/library/pt2.m4v"), AUDIO_2, PART_MS)), partsMatch));
    }

    private void ready(boolean partsMatch) {
        source(partsMatch);
        when(transcoder.mode()).thenReturn(EncoderMode.VAAPI);
        when(indexes.ensureIndexed(file1, PART_MS)).thenReturn(INDEX_1);
        when(indexes.cached(file2, PART_MS)).thenReturn(Optional.of(INDEX_2));
    }

    private void readyToCreate(boolean partsMatch) {
        ready(partsMatch);
        when(manager.create(any())).thenAnswer(call -> new SessionView(ID, List.of(), null, null, CUTS, 1, 1));
    }

    private SessionSpec created() {
        ArgumentCaptor<SessionSpec> captor = ArgumentCaptor.forClass(SessionSpec.class);
        verify(manager).create(captor.capture());
        return captor.getValue();
    }

    private static CreateRequest request(Long audio, Long startMs, String quality) {
        return new CreateRequest(audio, startMs, quality, null);
    }

    @Test
    void theSessionIsOneTimelineOfBothPartsWithEachPartsFileAndFrameRate() {
        readyToCreate(true);

        service.create(EDITION_ID, request(null, null, "auto"));

        SessionSpec spec = created();
        assertThat(spec.cuts().segmentCount()).isEqualTo(200);
        assertThat(spec.cuts().partStartSegments()).containsExactly(0, 100);
        assertThat(spec.cuts().totalDurationMs()).isEqualTo(800_000);
        assertThat(spec.parts()).containsExactly(new SessionSpec.PartSource(Path.of("/library/pt1.m4v"), 0, 25.0),
            new SessionSpec.PartSource(Path.of("/library/pt2.m4v"), 1, 24.0));
        assertThat(spec.startSegment()).isZero();
        assertThat(spec.height()).isEqualTo(800);
        assertThat(spec.width()).isEqualTo(1920);
        assertThat(spec.rungs()).extracting(Rung::id).contains("original");
        assertThat(spec.hardwareDecode()).isTrue();
    }

    @Test
    void aStartPositionAfterTheJoinStartsInThePartThatHasIt() {
        readyToCreate(true);

        service.create(EDITION_ID, request(null, PART_MS + 8_000, "auto"));

        assertThat(created().startSegment()).isEqualTo(102);
    }

    @Test
    void theStartPositionIsCheckedAgainstTheSumOfThePartsVideoDurations() {
        source(true);

        assertThatThrownBy(() -> service.create(EDITION_ID, request(null, 2 * PART_MS, "auto")))
            .isInstanceOf(BadRequestException.class).hasMessage("startMs is outside the film");
        assertThatThrownBy(() -> service.create(EDITION_ID, request(null, -1L, "auto")))
            .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(manager);
    }

    @Test
    void theLastMillisecondOfTheFilmIsAStartPosition() {
        readyToCreate(true);

        service.create(EDITION_ID, request(null, 2 * PART_MS - 1, "auto"));

        assertThat(created().startSegment()).isEqualTo(199);
    }

    @Test
    void theChosenPartOneAudioIsMappedToTheSameLanguageInEveryLaterPart() {
        readyToCreate(true);

        service.create(EDITION_ID, request(101L, null, "auto"));

        assertThat(created().parts()).extracting(SessionSpec.PartSource::audioIndex).containsExactly(1, 0);
    }

    @Test
    void aLaterPartWithNoAudioStreamIs409() {
        when(sources.find(EDITION_ID)).thenReturn(new Source(EDITION_ID, "Towers", List.of(
            new Part(file1, Path.of("/library/pt1.m4v"), AUDIO_1, PART_MS),
            new Part(file2, Path.of("/library/pt2.m4v"), List.of(), PART_MS)), true));
        when(transcoder.mode()).thenReturn(EncoderMode.VAAPI);
        when(indexes.ensureIndexed(file1, PART_MS)).thenReturn(INDEX_1);
        when(indexes.cached(file2, PART_MS)).thenReturn(Optional.of(INDEX_2));

        assertThatThrownBy(() -> service.create(EDITION_ID, request(null, null, "auto")))
            .isInstanceOf(ConflictException.class).hasMessage("Part 2 has no audio stream");
    }

    @Test
    void partsThatDoNotMatchAreEncodedThroughoutWithNoCopyRung() {
        readyToCreate(false);

        service.create(EDITION_ID, request(null, null, "auto"));

        assertThat(created().rungs()).extracting(Rung::id).doesNotContain("original").startsWith("1080p");
    }

    @Test
    void pinningTheOriginalOfPartsThatDoNotMatchIs409() {
        ready(false);

        assertThatThrownBy(() -> service.create(EDITION_ID, request(null, null, "original")))
            .isInstanceOf(ConflictException.class).hasMessage("The original cannot be copied for this film");
    }

    @Test
    void aLongKeyframeGapAnywhereAcrossTheJoinRulesTheCopyRungOut() {
        CutList gappy = CutList.parse("0,4000", 4_000 + CutList.MAX_COPY_GAP_MS + 1);
        file1.setVideoDurationMs(4_000 + CutList.MAX_COPY_GAP_MS + 1);
        when(sources.find(EDITION_ID)).thenReturn(new Source(EDITION_ID, "Towers", List.of(
            new Part(file1, Path.of("/library/pt1.m4v"), AUDIO_1, 4_000 + CutList.MAX_COPY_GAP_MS + 1),
            new Part(file2, Path.of("/library/pt2.m4v"), AUDIO_2, PART_MS)), true));
        when(transcoder.mode()).thenReturn(EncoderMode.VAAPI);
        when(indexes.ensureIndexed(file1, 4_000 + CutList.MAX_COPY_GAP_MS + 1))
            .thenReturn(new KeyframeIndexService.Index(gappy, 25.0));
        when(indexes.cached(file2, PART_MS)).thenReturn(Optional.of(INDEX_2));
        when(manager.create(any())).thenAnswer(call -> new SessionView(ID, List.of(), null, null, CUTS, 1, 1));

        service.create(EDITION_ID, request(null, null, "auto"));

        assertThat(created().rungs()).extracting(Rung::id).doesNotContain("original");
    }

    @Test
    void theSmallestPartsPictureSetsTheLadderAndTheSessionsSize() {
        file2 = file(8, 480);
        readyToCreate(false);

        service.create(EDITION_ID, request(null, null, "auto"));

        SessionSpec spec = created();
        assertThat(spec.height()).isEqualTo(480);
        assertThat(spec.width()).isEqualTo(1152);
        assertThat(spec.rungs().get(0).height()).isEqualTo(480);
    }

    @Test
    void aPartWithoutAWidthGetsTheSixteenByNineWidth() {
        file1.setWidth(null);
        readyToCreate(true);

        service.create(EDITION_ID, request(null, null, "auto"));

        assertThat(created().width()).isEqualTo(1422);
    }

    @Test
    void softwareDecodeIsUsedWhenAnyPartIsNotEightBitFourTwoZeroH264() {
        file2.setPixelFormat("yuv420p10le");
        readyToCreate(false);

        service.create(EDITION_ID, request(null, null, "auto"));

        assertThat(created().hardwareDecode()).isFalse();
    }

    @Test
    void aPartWithoutADurationOrHeightIs409() {
        when(sources.find(EDITION_ID)).thenReturn(new Source(EDITION_ID, "Towers", List.of(
            new Part(file1, Path.of("/library/pt1.m4v"), AUDIO_1, PART_MS),
            new Part(file2, Path.of("/library/pt2.m4v"), AUDIO_2, 0)), true));

        assertThatThrownBy(() -> service.create(EDITION_ID, request(null, null, "auto")))
            .isInstanceOf(ConflictException.class).hasMessage("The film's duration or size is unknown");

        file2.setHeight(null);
        source(true);
        assertThatThrownBy(() -> service.create(EDITION_ID, request(null, null, "auto")))
            .isInstanceOf(ConflictException.class).hasMessage("The film's duration or size is unknown");
    }

    @Test
    void aLaterPartThatIsNotIndexedYetIsIndexedInTheBackgroundAndTheSessionWaitsForIt() {
        source(true);
        when(transcoder.mode()).thenReturn(EncoderMode.VAAPI);
        when(indexes.ensureIndexed(file1, PART_MS)).thenReturn(INDEX_1);
        when(indexes.cached(file2, PART_MS)).thenReturn(Optional.empty());
        when(indexes.ensureIndexed(file2, PART_MS)).thenReturn(INDEX_2);
        when(manager.create(any())).thenAnswer(call -> new SessionView(ID, List.of(), null, null, CUTS, 1, 1));

        service.create(EDITION_ID, request(null, null, "auto"));

        assertThat(created().parts()).extracting(SessionSpec.PartSource::fps).containsExactly(25.0, 24.0);
    }

    @Test
    void aLaterPartIsIndexedEvenWhileTheLibraryWideIndexThreadIsBusyBecauseSessionsHaveTheirOwnPool()
        throws Exception {
        TranscodeConfig config = new TranscodeConfig();
        java.util.concurrent.ExecutorService library = (java.util.concurrent.ExecutorService) config.indexExecutor();
        java.util.concurrent.ExecutorService sessions = config.sessionIndexExecutor();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        library.submit(() -> {
            release.await(30, java.util.concurrent.TimeUnit.SECONDS);
            return null;
        }); // IndexJob holds the library executor for a whole pass
        SessionService service = new SessionService(sources, indexes, transcoder, manager, sessions,
            Duration.ofSeconds(10));
        source(true);
        when(transcoder.mode()).thenReturn(EncoderMode.VAAPI);
        when(indexes.ensureIndexed(file1, PART_MS)).thenReturn(INDEX_1);
        when(indexes.cached(file2, PART_MS)).thenReturn(Optional.empty());
        when(indexes.ensureIndexed(file2, PART_MS)).thenReturn(INDEX_2);
        when(manager.create(any())).thenAnswer(call -> new SessionView(ID, List.of(), null, null, CUTS, 1, 1));
        try {
            service.create(EDITION_ID, request(null, null, "auto"));

            assertThat(created().parts()).extracting(SessionSpec.PartSource::fps).containsExactly(25.0, 24.0);
        } finally {
            release.countDown();
            library.shutdown();
            sessions.shutdown();
        }
    }

    @Test
    void aLaterPartStillBeingIndexedWhenTheWaitEndsAnswers503ForTheClientToRetry() {
        SessionService slow = new SessionService(sources, indexes, transcoder, manager, runnable -> { },
            Duration.ofMillis(20));
        source(true);
        when(transcoder.mode()).thenReturn(EncoderMode.VAAPI);
        when(indexes.ensureIndexed(file1, PART_MS)).thenReturn(INDEX_1);
        when(indexes.cached(file2, PART_MS)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> slow.create(EDITION_ID, request(null, null, "auto")))
            .isInstanceOf(SegmentNotReadyException.class).hasMessage("The film is still being prepared");
        verifyNoInteractions(manager);
    }

    @Test
    void aLaterPartThatCannotBeIndexedIs409() {
        source(true);
        when(transcoder.mode()).thenReturn(EncoderMode.VAAPI);
        when(indexes.ensureIndexed(file1, PART_MS)).thenReturn(INDEX_1);
        when(indexes.cached(file2, PART_MS)).thenReturn(Optional.empty());
        when(indexes.ensureIndexed(file2, PART_MS)).thenThrow(new KeyframeIndexException("no keyframes"));

        assertThatThrownBy(() -> service.create(EDITION_ID, request(null, null, "auto")))
            .isInstanceOf(ConflictException.class).hasMessage("This film cannot be streamed adaptively");
    }

    @Test
    void partOneThatCannotBeIndexedIs409() {
        source(true);
        when(transcoder.mode()).thenReturn(EncoderMode.VAAPI);
        when(indexes.ensureIndexed(file1, PART_MS)).thenThrow(new KeyframeIndexException("no keyframes"));

        assertThatThrownBy(() -> service.create(EDITION_ID, request(null, null, "auto")))
            .isInstanceOf(ConflictException.class).hasMessage("This film cannot be streamed adaptively");
        verify(indexes).ensureIndexed(file1, PART_MS);
        verifyNoMoreInteractions(indexes);
    }

    @Test
    void anInterruptedWaitAnswers503AndKeepsTheInterruptFlag() {
        Executor never = runnable -> { };
        SessionService waiting = new SessionService(sources, indexes, transcoder, manager, never,
            Duration.ofSeconds(30));
        source(true);
        when(transcoder.mode()).thenReturn(EncoderMode.VAAPI);
        when(indexes.ensureIndexed(file1, PART_MS)).thenReturn(INDEX_1);
        when(indexes.cached(file2, PART_MS)).thenReturn(Optional.empty());
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> waiting.create(EDITION_ID, request(null, null, "auto")))
                .isInstanceOf(SegmentNotReadyException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
