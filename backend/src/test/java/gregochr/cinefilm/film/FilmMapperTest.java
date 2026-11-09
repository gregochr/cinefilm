package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.TestData.view;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.transcode.EncoderMode;
import org.junit.jupiter.api.Test;

class FilmMapperTest {

    private final Film film = film(1L, "Heat", 1995, NOW, "Crime");
    private final Edition edition = edition(film, 10L, "Theatrical", 170 * MIN, null);

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
        file(edition, "heat.mp4").setHeight(height);
        return FilmMapper.detail(view(film), null, mode).editions().get(0).qualities();
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

        assertThat(FilmMapper.detail(view(film), null, EncoderMode.VAAPI).editions().get(0).qualities())
            .extracting(FilmDtos.QualityDto::id).containsExactly("auto", "original");
    }

    @Test
    void aHeightOfOneLineIsTooSmallForARungSoOnlyAutoAndOriginalAreOffered() {
        assertThat(qualities(1, EncoderMode.VAAPI)).extracting(FilmDtos.QualityDto::id)
            .containsExactly("auto", "original");
    }
}
