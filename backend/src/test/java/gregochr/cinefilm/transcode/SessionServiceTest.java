package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.transcode.SessionDtos.CreateRequest;
import gregochr.cinefilm.transcode.SessionDtos.RenditionDto;
import gregochr.cinefilm.transcode.SessionDtos.SessionCreated;
import gregochr.cinefilm.transcode.SessionSources.AudioChoice;
import gregochr.cinefilm.transcode.SessionSources.Source;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    private static final long EDITION_ID = 40L;
    private static final String ID = "5b0f1c1e-6a52-4d5e-9a4c-0a7d3c1f2b11";
    private static final Path PATH = Path.of("/library/Heat.m4v");
    private static final long DURATION = 400_000;
    private static final CutList CUTS = CutList.fromKeyframes(
        IntStream.range(0, 100).mapToObj(i -> i * 4000L).toList(), DURATION);
    private static final KeyframeIndexService.Index INDEX = new KeyframeIndexService.Index(CUTS, 25.0);
    private static final List<AudioChoice> AUDIO = List.of(new AudioChoice(100, 0, false),
        new AudioChoice(101, 1, true));

    @Mock
    private SessionSources sources;
    @Mock
    private KeyframeIndexService indexes;
    @Mock
    private Transcoder transcoder;
    @Mock
    private SessionManager manager;

    private SessionService service;
    private MediaFile file;

    @BeforeEach
    void setUp() {
        service = new SessionService(sources, indexes, transcoder, manager);
        file = new MediaFile();
        file.setId(7L);
        file.setDurationMs(DURATION);
        file.setHeight(1080);
        file.setWidth(1920);
        file.setBitrate(15_000_000L);
        file.setVideoCodec("H.264");
        file.setPixelFormat("yuv420p");
    }

    @Test
    void eightBitFourTwoZeroH264IsCopiedAndDecodedOnTheGpu() {
        file.setPixelFormat("yuvj420p");
        readyToCreate(EncoderMode.VAAPI, INDEX);

        service.create(EDITION_ID, request(null, null, "auto", null));

        assertThat(created().rungs()).extracting(Rung::id).containsExactly("original", "720p", "480p", "360p");
        assertThat(created().hardwareDecode()).isTrue();
    }

    @Test
    void tenBitH264HasNoCopyRungTheTopRungIsTranscodedAtSourceHeightAndDecodeIsSoftware() {
        file.setPixelFormat("yuv420p10le");
        readyToCreate(EncoderMode.VAAPI, INDEX);

        SessionCreated result = service.create(EDITION_ID, request(null, null, "auto", null));

        assertThat(result.renditions()).extracting(RenditionDto::id).containsExactly("1080p", "720p", "480p", "360p");
        assertThat(result.renditions()).noneMatch(RenditionDto::copy);
        assertThat(result.startRung()).isEqualTo("1080p");
        assertThat(created().rungs().get(0).height()).isEqualTo(1080);
        assertThat(created().hardwareDecode()).isFalse();
    }

    @Test
    void hevcHasNoCopyRungAndIsDecodedInSoftware() {
        file.setVideoCodec("HEVC");
        readyToCreate(EncoderMode.VAAPI, INDEX);

        service.create(EDITION_ID, request(null, null, "auto", null));

        assertThat(created().rungs()).extracting(Rung::id).doesNotContain("original");
        assertThat(created().hardwareDecode()).isFalse();
    }

    @Test
    void anUnknownPixelFormatIsTreatedAsNotCopyable() {
        file.setPixelFormat(null);
        readyToCreate(EncoderMode.VAAPI, INDEX);

        service.create(EDITION_ID, request(null, null, "auto", null));

        assertThat(created().rungs()).extracting(Rung::id).doesNotContain("original");
        assertThat(created().hardwareDecode()).isFalse();
    }

    @Test
    void pinningTheOriginalOfANonCopyableFileIs409() {
        file.setPixelFormat("yuv422p");
        ready(EncoderMode.VAAPI, INDEX);

        assertThatThrownBy(() -> service.create(EDITION_ID, request(null, null, "original", null)))
            .isInstanceOf(ConflictException.class).hasMessage("The original cannot be copied for this film");
    }

    private void source(List<AudioChoice> audio) {
        when(sources.find(EDITION_ID)).thenReturn(new Source(EDITION_ID, "Heat", file, PATH, audio));
    }

    private void ready(EncoderMode mode, KeyframeIndexService.Index index) {
        source(AUDIO);
        when(transcoder.mode()).thenReturn(mode);
        when(indexes.ensureIndexed(file)).thenReturn(index);
    }

    private void readyToCreate(EncoderMode mode, KeyframeIndexService.Index index) {
        ready(mode, index);
        when(manager.create(any())).thenAnswer(call -> new SessionView(ID, List.of(), null, null, CUTS, 1, 1));
    }

    private SessionSpec created() {
        ArgumentCaptor<SessionSpec> captor = ArgumentCaptor.forClass(SessionSpec.class);
        verify(manager).create(captor.capture());
        return captor.getValue();
    }

    private static CreateRequest request(Long audio, Long startMs, String quality, Long probe) {
        return new CreateRequest(audio, startMs, quality, probe);
    }

    @Test
    void autoCreatesTheWholeLadderStartingOnTheTopRungAtTheDefaultAudioAndStartOfFilm() {
        readyToCreate(EncoderMode.VAAPI, INDEX);

        SessionCreated result = service.create(EDITION_ID, request(null, null, "auto", null));

        assertThat(result.sessionId()).isEqualTo(ID);
        assertThat(result.masterUrl()).isEqualTo("/api/sessions/" + ID + "/master.m3u8");
        assertThat(result.startRung()).isEqualTo("original");
        assertThat(result.renditions()).containsExactly(
            new RenditionDto("original", 1080, 15_000_000, true), new RenditionDto("720p", 720, 2_500_000, false),
            new RenditionDto("480p", 480, 1_000_000, false), new RenditionDto("360p", 360, 600_000, false));
        SessionSpec spec = created();
        assertThat(spec.editionId()).isEqualTo(EDITION_ID);
        assertThat(spec.filmTitle()).isEqualTo("Heat");
        assertThat(spec.parts().get(0).source()).isEqualTo(PATH);
        assertThat(spec.parts().get(0).audioIndex()).isEqualTo(1);
        assertThat(spec.cuts()).isSameAs(CUTS);
        assertThat(spec.parts().get(0).fps()).isEqualTo(25.0);
        assertThat(spec.width()).isEqualTo(1920);
        assertThat(spec.height()).isEqualTo(1080);
        assertThat(spec.rungs()).extracting(Rung::id).containsExactly("original", "720p", "480p", "360p");
        assertThat(spec.startRung().id()).isEqualTo("original");
        assertThat(spec.pinned()).isNull();
        assertThat(spec.startSegment()).isZero();
    }

    @Test
    void theProbePicksTheStartRung() {
        readyToCreate(EncoderMode.VAAPI, INDEX);

        SessionCreated result = service.create(EDITION_ID, request(null, null, "auto", 5000L));

        assertThat(result.startRung()).isEqualTo("720p");
        assertThat(created().startRung().id()).isEqualTo("720p");
        assertThat(created().rungs()).hasSize(4);
    }

    @Test
    void aPinnedQualityEncodesOnlyThatRungAndStartsOnItButStillReportsTheWholeLadder() {
        readyToCreate(EncoderMode.VAAPI, INDEX);

        SessionCreated result = service.create(EDITION_ID, request(null, null, "480p", 100_000L));

        assertThat(result.startRung()).isEqualTo("480p");
        assertThat(result.renditions()).hasSize(4);
        SessionSpec spec = created();
        assertThat(spec.rungs()).extracting(Rung::id).containsExactly("480p");
        assertThat(spec.startRung().id()).isEqualTo("480p");
        assertThat(spec.pinned()).isEqualTo("480p");
    }

    @Test
    void anAudioStreamOfTheEditionIsChosenByIdAndMappedByPosition() {
        readyToCreate(EncoderMode.VAAPI, INDEX);

        service.create(EDITION_ID, request(100L, null, "auto", null));

        assertThat(created().parts().get(0).audioIndex()).isZero();
    }

    @Test
    void withNoDefaultAudioTheFirstStreamIsUsed() {
        readyToCreate(EncoderMode.VAAPI, INDEX);
        source(List.of(new AudioChoice(100, 0, false), new AudioChoice(101, 1, false)));

        service.create(EDITION_ID, request(null, null, "auto", null));

        assertThat(created().parts().get(0).audioIndex()).isZero();
    }

    @Test
    void anAudioStreamOfAnotherFileIs400() {
        source(AUDIO);

        assertThatThrownBy(() -> service.create(EDITION_ID, request(999L, null, "auto", null)))
            .isInstanceOf(BadRequestException.class).hasMessage("No such audio stream");
    }

    @Test
    void aFileWithNoAudioIs409SoTheClientDirectPlays() {
        source(List.of());

        assertThatThrownBy(() -> service.create(EDITION_ID, request(null, null, "auto", null)))
            .isInstanceOf(ConflictException.class).hasMessage("The file has no audio stream");
    }

    @Test
    void anInvalidQualityIs400() {
        source(AUDIO);

        assertThatThrownBy(() -> service.create(EDITION_ID, request(null, null, "bogus", null)))
            .isInstanceOf(BadRequestException.class).hasMessage("Invalid quality");
        assertThatThrownBy(() -> service.create(EDITION_ID, request(null, null, null, null)))
            .isInstanceOf(BadRequestException.class).hasMessage("Invalid quality");
    }

    @Test
    void aNegativeProbeIs400ButZeroIsAccepted() {
        readyToCreate(EncoderMode.VAAPI, INDEX);

        assertThatThrownBy(() -> service.create(EDITION_ID, request(null, null, "auto", -1L)))
            .isInstanceOf(BadRequestException.class).hasMessage("Invalid probe");

        assertThat(service.create(EDITION_ID, request(null, null, "auto", 0L)).startRung()).isEqualTo("360p");
    }

    @Test
    void aFilmTwoLinesHighIsTheSmallestThatCanBeServedAndOneLineIs409() {
        readyToCreate(EncoderMode.VAAPI, INDEX);
        file.setHeight(2);

        assertThat(service.create(EDITION_ID, request(null, null, "auto", null)).startRung()).isEqualTo("original");

        file.setHeight(1);
        assertThatThrownBy(() -> service.create(EDITION_ID, request(null, null, "auto", null)))
            .isInstanceOf(ConflictException.class).hasMessage("The film's duration or size is unknown");
    }

    @Test
    void theStartPositionMustBeInsideTheFilmAndPicksItsSegment() {
        readyToCreate(EncoderMode.VAAPI, INDEX);

        assertThatThrownBy(() -> service.create(EDITION_ID, request(null, -1L, "auto", null)))
            .isInstanceOf(BadRequestException.class).hasMessage("startMs is outside the film");
        assertThatThrownBy(() -> service.create(EDITION_ID, request(null, DURATION, "auto", null)))
            .isInstanceOf(BadRequestException.class).hasMessage("startMs is outside the film");

        service.create(EDITION_ID, request(null, DURATION - 1, "auto", null));
        assertThat(created().startSegment()).isEqualTo(99);
    }

    @Test
    void theStartSegmentIsTheOneContainingTheStartPosition() {
        readyToCreate(EncoderMode.VAAPI, INDEX);

        service.create(EDITION_ID, request(null, 8_000L, "auto", null));

        assertThat(created().startSegment()).isEqualTo(2);
    }

    @Test
    void aFileWithoutADurationOrHeightIs409() {
        file.setDurationMs(null);
        assertUnknownFact();

        file.setDurationMs(0L);
        assertUnknownFact();

        file.setDurationMs(DURATION);
        file.setHeight(null);
        assertUnknownFact();

        file.setHeight(1);
        assertUnknownFact();
    }

    private void assertUnknownFact() {
        source(AUDIO);
        assertThatThrownBy(() -> service.create(EDITION_ID, request(null, null, "auto", null)))
            .isInstanceOf(ConflictException.class).hasMessage("The film's duration or size is unknown");
    }

    @Test
    void anUnavailableEncoderIs409WithoutIndexingAnything() {
        source(AUDIO);
        when(transcoder.mode()).thenReturn(EncoderMode.UNAVAILABLE);

        assertThatThrownBy(() -> service.create(EDITION_ID, request(null, null, "auto", null)))
            .isInstanceOf(ConflictException.class).hasMessage("Transcoding is unavailable");

        verifyNoInteractions(indexes, manager);
    }

    @Test
    void aFilmThatCannotBeIndexedIs409SoTheClientDirectPlays() {
        source(AUDIO);
        when(transcoder.mode()).thenReturn(EncoderMode.VAAPI);
        when(indexes.ensureIndexed(file)).thenThrow(new KeyframeIndexException("no moov"));

        assertThatThrownBy(() -> service.create(EDITION_ID, request(null, null, "auto", null)))
            .isInstanceOf(ConflictException.class).hasMessage("This film cannot be streamed adaptively");
    }

    @Test
    void originalWithoutACopyRungIs409ButAnotherMissingRungIs400() {
        CutList sparse = CutList.fromKeyframes(List.of(30_000L), DURATION);
        assertThat(sparse.copyOk()).isFalse();
        ready(EncoderMode.VAAPI, new KeyframeIndexService.Index(sparse, null));

        assertThatThrownBy(() -> service.create(EDITION_ID, request(null, null, "original", null)))
            .isInstanceOf(ConflictException.class).hasMessage("The original cannot be copied for this film");
        assertThatThrownBy(() -> service.create(EDITION_ID, request(null, null, "240p", null)))
            .isInstanceOf(BadRequestException.class).hasMessage("Quality not available for this film");
    }

    @Test
    void theSoftwareEncoderTrimsTheLadderAndAPinnedRungItDoesNotHaveIs400() {
        readyToCreate(EncoderMode.SOFTWARE, INDEX);

        SessionCreated auto = service.create(EDITION_ID, request(null, null, "auto", null));
        assertThat(auto.renditions()).extracting(RenditionDto::id).containsExactly("original", "480p", "360p");

        assertThatThrownBy(() -> service.create(EDITION_ID, request(null, null, "720p", null)))
            .isInstanceOf(BadRequestException.class).hasMessage("Quality not available for this film");
    }

    @Test
    void aMissingWidthIsDerivedFromA16By9Picture() {
        file.setWidth(null);
        file.setHeight(1000);
        readyToCreate(EncoderMode.VAAPI, INDEX);

        service.create(EDITION_ID, request(null, null, "auto", null));

        assertThat(created().width()).isEqualTo(1776);
        assertThat(created().height()).isEqualTo(1000);
    }

    @Test
    void aMissingBitrateGivesTheCopyRungItsDefault() {
        file.setBitrate(null);
        readyToCreate(EncoderMode.VAAPI, INDEX);

        SessionCreated result = service.create(EDITION_ID, request(null, null, "auto", null));

        assertThat(result.renditions().get(0)).isEqualTo(new RenditionDto("original", 1080, 5_000_000, true));
    }

    @Test
    void anEncoderThatCannotStartIs409() {
        source(AUDIO);
        when(transcoder.mode()).thenReturn(EncoderMode.VAAPI);
        when(indexes.ensureIndexed(file)).thenReturn(INDEX);
        when(manager.create(any())).thenThrow(new SessionStartException(new java.io.IOException("no")));

        assertThatThrownBy(() -> service.create(EDITION_ID, request(null, null, "auto", null)))
            .isInstanceOf(ConflictException.class).hasMessage("Transcoding could not start");
    }

    @Test
    void theMasterPlaylistIsTheSessionsOwnRungsStartFirst() {
        List<Rung> rungs = Ladder.rungs(1080, 15_000_000, true, EncoderMode.VAAPI);
        SessionView view = new SessionView(ID, rungs, rungs.get(2), null, CUTS, 1920, 1080);
        when(manager.view(ID)).thenReturn(view);

        assertThat(service.master(ID)).isEqualTo(PlaylistWriter.master(rungs, rungs.get(2), null, 1920, 1080));
    }

    @Test
    void aMediaPlaylistIsTheCutListAndItsRungMustBeInTheSession() {
        List<Rung> rungs = Ladder.rungs(1080, 15_000_000, true, EncoderMode.VAAPI);
        when(manager.view(ID)).thenReturn(new SessionView(ID, rungs.subList(0, 2), rungs.get(0), null, CUTS, 1, 1));

        assertThat(service.media(ID, "720p")).isEqualTo(PlaylistWriter.media(CUTS));
        assertThatThrownBy(() -> service.media(ID, "480p")).isInstanceOf(NotFoundException.class)
            .hasMessage("No such rung");
        assertThatThrownBy(() -> service.media(ID, "../etc")).isInstanceOf(NotFoundException.class)
            .hasMessage("No such rung");
    }

    @Test
    void aSegmentRequestIsPatternCheckedThenHandedToTheManager() {
        when(manager.segment(ID, "720p", 12)).thenReturn(PATH);
        when(manager.segment(ID, "original", 999_999)).thenReturn(PATH);

        assertThat(service.segment(ID, "720p", "seg-12.ts")).isEqualTo(PATH);
        assertThat(service.segment(ID, "original", "seg-999999.ts")).isEqualTo(PATH);
    }

    @Test
    void malformedIdsRungsAndSegmentNamesAre404WithoutReachingTheManager() {
        assertThatThrownBy(() -> service.segment("not-a-uuid", "720p", "seg-1.ts"))
            .isInstanceOf(NotFoundException.class).hasMessage("No such session");
        assertThatThrownBy(() -> service.segment(ID.toUpperCase(), "720p", "seg-1.ts"))
            .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.segment(ID + "0", "720p", "seg-1.ts"))
            .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.segment(ID, "999p", "seg-1.ts")).isInstanceOf(NotFoundException.class)
            .hasMessage("No such segment");
        assertThatThrownBy(() -> service.segment(ID, "720p", "seg-1000000.ts"))
            .isInstanceOf(NotFoundException.class).hasMessage("No such segment");
        assertThatThrownBy(() -> service.segment(ID, "720p", "seg-1.ts/../../x"))
            .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.segment(ID, "720p", "seg-.ts")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.segment(ID, "720p", "seg-1.mp4")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.master("x")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.media("x", "720p")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.end("x")).isInstanceOf(NotFoundException.class);

        verifyNoInteractions(manager);
    }

    @Test
    void endingASessionEndsItInTheManager() {
        service.end(ID);

        verify(manager).end(ID);
    }
}
