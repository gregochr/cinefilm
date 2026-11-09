package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.TestData.view;
import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.transcode.CutList;
import gregochr.cinefilm.transcode.EncoderMode;
import gregochr.cinefilm.transcode.KeyframeIndexService;
import gregochr.cinefilm.transcode.Ladder;
import gregochr.cinefilm.transcode.SessionDtos.RenditionDto;
import gregochr.cinefilm.transcode.SessionManager;
import gregochr.cinefilm.transcode.SessionService;
import gregochr.cinefilm.transcode.SessionSources;
import gregochr.cinefilm.transcode.SessionView;
import gregochr.cinefilm.transcode.Transcoder;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class FilmMapperTest {

    private final Film film = film(1L, "Heat", 1995, NOW, "Crime");
    private final Edition edition = edition(film, 10L, "Theatrical", 170 * MIN, null);

    @Test
    void aCardHasACollectionIdOnlyWhenItsCollectionIsInTheQualifyingSet() {
        var saga = gregochr.cinefilm.TestData.collection("Saga");
        saga.setId(7L);
        film.setCollection(saga);

        assertThat(FilmMapper.card(view(film)).collectionId()).isNull();
        assertThat(FilmMapper.card(view(film), java.util.Set.of(7L)).collectionId()).isEqualTo(7L);
        assertThat(FilmMapper.card(view(film), java.util.Set.of(8L)).collectionId()).isNull();
        film.setCollection(null);
        assertThat(FilmMapper.card(view(film), java.util.Set.of(7L)).collectionId()).isNull();
    }

    @Test
    void theFileSummaryReportsTheDefaultAudioStreamChannels() {
        file(edition, "/films/heat.mp4");

        var info = FilmMapper.detail(view(film), null, false, EncoderMode.SOFTWARE).editions().get(0).file();

        assertThat(info.audioSummary()).isEqualTo("5.1");
    }

    @Test
    void theFileSummaryFallsBackToTheFirstAudioStreamWhenNoneIsDefault() {
        MediaFile original = file(edition, "/films/heat.mp4");
        original.getAudioStreams().forEach(a -> a.setDefault(false));

        var info = FilmMapper.detail(view(film), null, false, EncoderMode.SOFTWARE).editions().get(0).file();

        assertThat(info.audioSummary()).isEqualTo("5.1");
    }

    @Test
    void theFileSummaryCarriesTheWidthAndHeightOfALetterboxedFile() {
        MediaFile original = file(edition, "/films/heat.mp4");
        original.setWidth(1920);
        original.setHeight(796);

        var info = FilmMapper.detail(view(film), null, false, EncoderMode.SOFTWARE).editions().get(0).file();

        assertThat(info.width()).isEqualTo(1920);
        assertThat(info.height()).isEqualTo(796);
    }

    @Test
    void anEditionWithoutAFileHasNoFileSummary() {
        assertThat(FilmMapper.detail(view(film), null, false, EncoderMode.SOFTWARE).editions().get(0).file()).isNull();
    }

    @Test
    void theFileSummaryCarriesTheMediaFileIdTheSubtitleUrlsAreBuiltFrom() {
        file(edition, "heat.mp4").setId(77L);

        assertThat(FilmMapper.detail(view(film), null, false, EncoderMode.SOFTWARE).editions().get(0).file().id())
            .isEqualTo(77L);
    }

    @Test
    void anEditionWhoseFileIsMissingOrCannotBePlayedIsNotOffered() {
        Edition missing = edition(film, 11L, "Missing cut", 100 * MIN, null);
        file(missing, "gone.mp4").setPresence(Presence.MISSING);
        Edition drm = edition(film, 12L, "Protected cut", 100 * MIN, null);
        file(drm, "drm.m4v").setPlayability(Playability.PROTECTED);
        file(edition, "heat.mp4");

        assertThat(FilmMapper.detail(view(film), null, false, EncoderMode.SOFTWARE).editions())
            .extracting(FilmDtos.EditionDto::name)
            .containsExactly("Theatrical");
    }

    private java.util.List<FilmDtos.QualityDto> qualities(int height, EncoderMode mode) {
        MediaFile original = file(edition, "heat.mp4");
        original.setVideoCodec("H.264");
        original.setPixelFormat("yuv420p");
        original.setHeight(height);
        return FilmMapper.detail(view(film), null, false, mode).editions().get(0).qualities();
    }

    @Test
    void withVaapiAFullHdFilmOffersOriginalThen720p480pAnd360p() {
        assertThat(qualities(1080, EncoderMode.VAAPI)).extracting(FilmDtos.QualityDto::id)
            .containsExactly("auto", "original", "720p", "480p", "360p");
        assertThat(qualities(1080, EncoderMode.VAAPI)).extracting(FilmDtos.QualityDto::available)
            .containsOnly(true);
    }

    @Test
    void withTheSoftwareEncoderOnlyTheLowRungsAreOffered() {
        assertThat(qualities(1080, EncoderMode.SOFTWARE)).extracting(FilmDtos.QualityDto::id)
            .containsExactly("auto", "original", "480p", "360p");
    }

    @Test
    void aTargetBitrateIsReportedForATranscodedRungAndTheFileBitrateForOriginal() {
        var list = qualities(1080, EncoderMode.VAAPI);

        assertThat(list.get(1).bitrate()).isEqualTo(9_000_000L);
        assertThat(list.get(2).bitrate()).isEqualTo(2_500_000L);
    }

    @Test
    void withoutAnEncoderOnlyOriginalIsAvailableAndTheRungsAreStillListed() {
        var list = qualities(1080, EncoderMode.UNAVAILABLE);

        assertThat(list).extracting(FilmDtos.QualityDto::id).containsExactly("auto", "original", "720p", "480p",
            "360p");
        assertThat(list).extracting(FilmDtos.QualityDto::available).containsExactly(false, true, false, false,
            false);
    }

    @Test
    void aFilmOf300LinesOffersOnly240pBelowOriginal() {
        assertThat(qualities(300, EncoderMode.VAAPI)).extracting(FilmDtos.QualityDto::id)
            .containsExactly("auto", "original", "240p");
    }

    @Test
    void aFileWithoutAKnownHeightOffersOnlyAutoAndOriginal() {
        file(edition, "heat.mp4").setHeight(null);

        assertThat(FilmMapper.detail(view(film), null, false, EncoderMode.VAAPI).editions().get(0).qualities())
            .extracting(FilmDtos.QualityDto::id).containsExactly("auto", "original");
    }

    @Test
    void aHeightOfOneLineIsTooSmallForARungSoOnlyAutoAndOriginalAreOffered() {
        assertThat(qualities(1, EncoderMode.VAAPI)).extracting(FilmDtos.QualityDto::id)
            .containsExactly("auto", "original");
    }

    private static final long DURATION_MS = 400_000;
    private static final CutList DENSE = CutList.fromKeyframes(
        java.util.stream.IntStream.range(0, 100).mapToObj(i -> i * 4000L).toList(), DURATION_MS);
    private static final CutList SPARSE = CutList.fromKeyframes(List.of(), DURATION_MS);

    private MediaFile source(String codec, String pixelFormat) {
        MediaFile original = file(edition, "heat.mp4");
        original.setId(7L);
        original.setVideoCodec(codec);
        original.setPixelFormat(pixelFormat);
        original.setDurationMs(DURATION_MS);
        return original;
    }

    private List<String> listedRungs(CutList cuts) {
        return FilmMapper.detail(view(film), null, false, EncoderMode.VAAPI, f -> cuts).editions().get(0)
            .qualities().stream().map(FilmDtos.QualityDto::id).filter(id -> !id.equals("auto")).toList();
    }

    /** What a playback session would build for the same file, through the real SessionService. */
    private List<String> sessionRungs(MediaFile original, CutList cuts) {
        SessionSources sources = mock(SessionSources.class);
        KeyframeIndexService indexes = mock(KeyframeIndexService.class);
        Transcoder transcoder = mock(Transcoder.class);
        SessionManager manager = mock(SessionManager.class);
        when(sources.find(10L)).thenReturn(new SessionSources.Source(10L, "Heat", original,
            Path.of("/library/heat.mp4"), List.of(new SessionSources.AudioChoice(1L, 0, true))));
        when(transcoder.mode()).thenReturn(EncoderMode.VAAPI);
        when(indexes.ensureIndexed(original)).thenReturn(new KeyframeIndexService.Index(cuts, 25.0));
        when(manager.create(any())).thenReturn(new SessionView("5b0f1c1e-6a52-4d5e-9a4c-0a7d3c1f2b11", List.of(),
            null, null, cuts, 1, 1));
        return new SessionService(sources, indexes, transcoder, manager).create(10L,
            new gregochr.cinefilm.transcode.SessionDtos.CreateRequest(null, null, "auto", null)).renditions().stream()
            .map(RenditionDto::id).toList();
    }

    @Test
    void anHevcSourceListsItsTopTranscodedRungByHeightAndStillListsOriginal() {
        source("HEVC", "yuv420p");

        assertThat(listedRungs(DENSE)).containsExactly("original", "1080p", "720p", "480p", "360p");
        assertThat(FilmMapper.detail(view(film), null, false, EncoderMode.VAAPI, f -> DENSE).editions().get(0)
            .qualities()).filteredOn(q -> q.id().equals("1080p")).extracting(FilmDtos.QualityDto::available)
            .containsExactly(true);
    }

    @Test
    void aTenBitSourceIsNotCopyable() {
        source("H.264", "yuv420p10le");
        assertThat(listedRungs(DENSE)).contains("1080p");
    }

    @Test
    void anUnknownPixelFormatIsNotCopyable() {
        source("H.264", null);

        assertThat(listedRungs(DENSE)).contains("1080p");
    }

    @Test
    void aCutListThatForbidsCopyingListsTheTopTranscodedRungEvenForH264() {
        source("H.264", "yuv420p");

        assertThat(listedRungs(SPARSE)).containsExactly("original", "1080p", "720p", "480p", "360p");
        assertThat(listedRungs(DENSE)).containsExactly("original", "720p", "480p", "360p");
    }

    @Test
    void withoutAnIndexTheCodecAndPixelFormatDecideAlone() {
        source("H.264", "yuv420p");
        assertThat(listedRungs(null)).containsExactly("original", "720p", "480p", "360p");
    }

    @Test
    void theListedRungsEqualTheSessionLadderForTheSameInputs() {
        MediaFile hevc = source("HEVC", "yuv420p");
        assertThat(sessionRungs(hevc, DENSE)).containsExactlyElementsOf(
            listedRungs(DENSE).stream().filter(id -> !id.equals("original")).toList());

        hevc.setVideoCodec("H.264");
        hevc.setPixelFormat("yuv420p");
        assertThat(sessionRungs(hevc, DENSE)).containsExactlyElementsOf(listedRungs(DENSE));
        assertThat(sessionRungs(hevc, SPARSE)).containsExactlyElementsOf(
            listedRungs(SPARSE).stream().filter(id -> !id.equals("original")).toList());
        assertThat(Ladder.forSource(1080, 9_000_000, "H.264", "yuv420p", () -> DENSE, EncoderMode.VAAPI))
            .extracting(gregochr.cinefilm.transcode.Rung::id).containsExactly("original", "720p", "480p", "360p");
    }
}
