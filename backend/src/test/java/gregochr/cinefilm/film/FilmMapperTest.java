package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.TestData.view;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FilmMapperTest {

    private final Film film = film(1L, "Heat", 1995, NOW, "Crime");
    private final Edition edition = edition(film, 10L, "Theatrical", 170 * MIN, null);

    @Test
    void theFileSummaryReportsTheDefaultAudioStreamChannels() {
        file(edition, "/films/heat.mp4");

        var info = FilmMapper.detail(view(film), null, false).editions().get(0).file();

        assertThat(info.audioSummary()).isEqualTo("5.1");
    }

    @Test
    void theFileSummaryFallsBackToTheFirstAudioStreamWhenNoneIsDefault() {
        MediaFile original = file(edition, "/films/heat.mp4");
        original.getAudioStreams().forEach(a -> a.setDefault(false));

        var info = FilmMapper.detail(view(film), null, false).editions().get(0).file();

        assertThat(info.audioSummary()).isEqualTo("5.1");
    }

    @Test
    void anEditionWithoutAFileHasNoFileSummary() {
        assertThat(FilmMapper.detail(view(film), null, false).editions().get(0).file()).isNull();
    }

    @Test
    void theFileSummaryCarriesTheMediaFileIdTheSubtitleUrlsAreBuiltFrom() {
        file(edition, "heat.mp4").setId(77L);

        assertThat(FilmMapper.detail(view(film), null, false).editions().get(0).file().id()).isEqualTo(77L);
    }

    @Test
    void anEditionWhoseFileIsMissingOrCannotBePlayedIsNotOffered() {
        Edition missing = edition(film, 11L, "Missing cut", 100 * MIN, null);
        file(missing, "gone.mp4").setPresence(Presence.MISSING);
        Edition drm = edition(film, 12L, "Protected cut", 100 * MIN, null);
        file(drm, "drm.m4v").setPlayability(Playability.PROTECTED);
        file(edition, "heat.mp4");

        assertThat(FilmMapper.detail(view(film), null, false).editions()).extracting(FilmDtos.EditionDto::name)
            .containsExactly("Theatrical");
    }
}
