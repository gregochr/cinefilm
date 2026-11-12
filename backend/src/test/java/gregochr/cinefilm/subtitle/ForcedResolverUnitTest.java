package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.film.SubtitleStream;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.subtitle.ForcedChoice.Source;
import gregochr.cinefilm.subtitle.ForcedChoice.Step;
import gregochr.cinefilm.subtitle.ForcedResolver.Resolution;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Gathering what the choice needs, with the repositories faked: which downloads count, the embedded track, reasons. */
@ExtendWith(MockitoExtension.class)
class ForcedResolverUnitTest {

    private static final String CUES = "1\n00:00:10,000 --> 00:00:12,000\nHello\n\n";

    @Mock
    private SubtitleSidecarRepository sidecars;

    @Mock
    private ForcedSubtitleCheckRepository checks;

    @Mock
    private ForcedDownloadCheckRepository downloadChecks;

    @Mock
    private OpenSubtitlesReferenceRepository references;

    @Mock
    private SidecarTimingService timing;

    private ForcedResolver resolver;
    private Film film;
    private Edition edition;
    private MediaFile part;

    @BeforeEach
    void setUp() {
        resolver = new ForcedResolver(sidecars, checks, downloadChecks, references, timing);
        film = film(1L, "Taken", 2008, NOW);
        film.setImdbId("tt0936501");
        edition = edition(film, 40L, "Original", 90 * MIN, null);
        part = file(edition, "Taken.m4v");
        part.setId(10L);
        part.setPartNumber(1);
        part.setDurationMs(90 * MIN);
    }

    private static SubtitleSidecar row(long id, boolean download) {
        SubtitleSidecar row = new SubtitleSidecar();
        row.setId(id);
        row.setMediaFileId(10L);
        row.setPath(download ? "opensubtitles:40:" + (5000 + id) : "Taken.en.forced.srt");
        row.setCuesText(CUES);
        row.setCueCount(1);
        row.setWholeEdition(download);
        row.setUpdatedAt(NOW);
        if (download) {
            row.setOrigin(SubtitleSidecar.Origin.OPENSUBTITLES);
            row.setOpenSubtitlesFileId(5000 + id);
            row.setImdbId("tt0936501");
        }
        return row;
    }

    private Resolution resolve() {
        return resolver.resolve(List.of(edition)).get(40L);
    }

    private void embedded(String language, boolean forced) {
        SubtitleStream stream = new SubtitleStream();
        stream.setMediaFile(part);
        stream.setIndex(7);
        stream.setLanguage(language);
        stream.setForced(forced);
        part.getSubtitleStreams().add(stream);
    }

    @Test
    void anEditionWithNoPartFilesIsResolvedToNothingWithoutAskingForAnySubtitle() {
        Edition bare = edition(film(2L, "Bare", 2000, NOW), 41L, "Original", MIN, null);

        Resolution resolution = resolver.resolve(List.of(bare)).get(41L);

        assertThat(resolution.decision().step()).isEqualTo(Step.NONE);
        assertThat(resolution.filePick().slots()).isEmpty();
        assertThat(resolution.fileVersion()).isNull();
        assertThat(resolution.fileAssessment()).isNull();
        verifyNoInteractions(sidecars);
    }

    @Test
    void anOwnerFileAloneIsUncheckedAndPlaysWithItsReason() {
        SubtitleSidecar owner = row(5, false);
        when(sidecars.findByMediaFileIdIn(List.of(10L))).thenReturn(List.of(owner));

        Resolution resolution = resolve();

        assertThat(resolution.decision().step()).isEqualTo(Step.UNCHECKED_FILE);
        assertThat(resolution.partIds()).containsExactly(10L);
        assertThat(resolution.fileVersion()).startsWith("p10|5@0/0/");
        assertThat(resolution.reasons()).containsEntry(5L, "Plays: nothing says it is wrong, but it is not proven yet");
        assertThat(resolution.chosen().slots()).hasSize(1);
        assertThat(resolution.fileConflict()).isFalse();
    }

    @Test
    void aDownloadForTheFilmsCurrentImdbIdCountsAndOneForAnotherOrWithNoFilmIdDoesNot() {
        SubtitleSidecar live = row(6, true);
        SubtitleSidecar old = row(7, true);
        old.setImdbId("tt0000009");
        when(sidecars.findByMediaFileIdIn(List.of(10L))).thenReturn(List.of(old, live));

        Resolution resolution = resolve();

        assertThat(resolution.downloads()).extracting(d -> d.sidecar().getId()).containsExactly(6L);
        assertThat(resolution.decision().step()).isEqualTo(Step.UNCHECKED_DOWNLOAD);
        assertThat(resolution.chosen().slots().get(0).partIndex()).isZero();
        assertThat(resolution.reasons().get(7L)).isEqualTo("Not used: it was fetched for another IMDb id");

        film.setImdbId(null);
        assertThat(resolve().downloads()).isEmpty();
    }

    @Test
    void anOwnerFileThatIsMissingOrEmptyOrBeatenByAWholeEditionFileSaysWhy() {
        SubtitleSidecar missing = row(5, false);
        missing.setPresence(Presence.MISSING);
        SubtitleSidecar empty = row(6, false);
        empty.setCueCount(0);
        SubtitleSidecar whole = row(8, false);
        whole.setWholeEdition(true);
        SubtitleSidecar perPart = row(9, false);
        when(sidecars.findByMediaFileIdIn(List.of(10L))).thenReturn(List.of(missing, empty, whole, perPart));

        Resolution resolution = resolve();

        assertThat(resolution.reasons().get(5L)).isEqualTo("Not used: the file is missing");
        assertThat(resolution.reasons().get(6L)).isEqualTo("Not used: it has no lines");
        assertThat(resolution.reasons().get(9L)).isEqualTo("Not used: a whole-edition file takes its place");
        assertThat(resolution.reasons().get(8L)).startsWith("Plays: ");
    }

    @Test
    void twoWholeEditionFilesAreAConflictThatSaysSoAndLeavesNoFileToPlay() {
        SubtitleSidecar first = row(5, false);
        first.setWholeEdition(true);
        SubtitleSidecar second = row(6, false);
        second.setWholeEdition(true);
        when(sidecars.findByMediaFileIdIn(List.of(10L))).thenReturn(List.of(first, second));

        Resolution resolution = resolve();

        assertThat(resolution.fileConflict()).isTrue();
        assertThat(resolution.decision().step()).isEqualTo(Step.NONE);
        assertThat(resolution.reasons().get(5L)).isEqualTo("Not used: two files compete for the same place, "
            + "so neither plays");
        assertThat(resolution.chosen().slots()).isEmpty();
    }

    @Test
    void anEmbeddedForcedTrackInEnglishOfNoLanguageOrAnyCaseCountsAndOthersDoNot() {
        embedded("French", true);
        embedded("fra", true);
        embedded("English", false);
        assertThat(resolve().embedded()).isFalse();

        embedded("ENG", true);
        assertThat(resolve().embedded()).isTrue();
        assertThat(resolve().decision().source()).isEqualTo(Source.EMBEDDED);
    }

    @Test
    void anEmbeddedTrackWithAShortCodeNoLanguageOrUndeterminedCountsToo() {
        for (String language : new String[] {"English", "ENGLISH", "en", "und", null}) {
            part.getSubtitleStreams().clear();
            embedded(language, true);
            assertThat(resolve().embedded()).as("language %s", language).isTrue();
        }
    }

    @Test
    void anEmbeddedTrackInAFileThatIsNotPresentIsNotCounted() {
        embedded("eng", true);
        part.setPresence(Presence.MISSING);

        assertThat(resolve().embedded()).isFalse();
    }
}
