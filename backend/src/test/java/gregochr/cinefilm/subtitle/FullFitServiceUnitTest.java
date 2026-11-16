package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.credits.CreditsDetection;
import gregochr.cinefilm.credits.CreditsDetectionRepository;
import gregochr.cinefilm.credits.CreditsVerdict;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmRepository.OsFullRef;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.FullFitService.Job;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Which full downloads the measurement looks at, with the repositories faked: only films whose full need is
 * {@code NEEDED} (films searched for a forced timing reference have full candidates too and are not measured), and the
 * credits start taken from {@link CreditsStarts}. (The same against H2 is in {@code FullFitServiceTest}.)
 */
@ExtendWith(MockitoExtension.class)
class FullFitServiceUnitTest {

    private static final String IMDB = "tt0000001";

    @Mock
    private FilmRepository films;

    @Mock
    private SubtitleSidecarRepository sidecars;

    @Mock
    private EditionRepository editions;

    @Mock
    private OpenSubtitlesReferenceRepository references;

    @Mock
    private CreditsDetectionRepository credits;

    @Mock
    private FullNeedService fullNeed;

    private FullFitService service;
    private Edition edition;

    @BeforeEach
    void setUp() {
        service = new FullFitService(films, sidecars, editions, references, credits, fullNeed);
        Film film = film(1L, "Oldboy", 2003, NOW);
        film.setImdbId(IMDB);
        edition = edition(film, 40L, "Original", 120 * MIN, null);
        MediaFile part = file(edition, "Oldboy.m4v");
        part.setId(10L);
        part.setPartNumber(1);
        OpenSubtitlesResult one = new OpenSubtitlesResult(1, 5011, "A", 23.976, 10, false, false, null, null);
        OpenSubtitlesResult two = new OpenSubtitlesResult(2, 5012, "B", 23.976, 10, false, false, null, null);
        when(films.findOpenSubtitlesFull()).thenReturn(List.of(new OsFullRef(1L, IMDB,
            OpenSubtitlesResult.listToJson(List.of(one, two)))));
        when(sidecars.findFilmIdsWithPresentSubtitle(SubtitleKind.FULL)).thenReturn(List.of(1L));
    }

    private SubtitleSidecar fullDownload() {
        SubtitleSidecar row = new SubtitleSidecar();
        row.setId(7L);
        row.setMediaFileId(10L);
        row.setKind(SubtitleKind.FULL);
        row.setPath("opensubtitles:40:5011");
        row.setCuesText("1\n00:00:10,000 --> 00:00:12,000\nHello\n\n");
        row.setCueCount(1);
        row.setWholeEdition(true);
        row.setUpdatedAt(NOW);
        row.setOrigin(SubtitleSidecar.Origin.OPENSUBTITLES);
        row.setOpenSubtitlesFileId(5011L);
        row.setImdbId(IMDB);
        return row;
    }

    private void editionLoaded() {
        when(editions.findWithFilesByFilmIdIn(Set.of(1L))).thenReturn(List.of(edition));
        when(sidecars.findByMediaFileIdInAndKind(List.of(10L), SubtitleKind.FULL)).thenReturn(List.of(fullDownload()));
        when(references.findByFilmIdIn(Set.of(1L))).thenReturn(List.of());
    }

    @Test
    void aFullDownloadOfAFilmThatNeedsFullSubtitlesIsAJobWithTheOtherCandidate() {
        when(fullNeed.neededFilmIds()).thenReturn(Set.of(1L));
        editionLoaded();
        when(credits.findById(10L)).thenReturn(Optional.empty());

        List<Job> jobs = service.due(Set.of());

        assertThat(jobs).singleElement().satisfies(job -> {
            assertThat(job.filmId()).isEqualTo(1L);
            assertThat(job.editionId()).isEqualTo(40L);
            assertThat(job.sidecarId()).isEqualTo(7L);
            assertThat(job.downloadFileId()).isEqualTo(5011L);
            assertThat(job.others()).extracting(OpenSubtitlesResult::fileId).containsExactly(5012L);
            assertThat(job.creditsStartMs()).isNull();
        });
    }

    @Test
    void aFilmWhoseFullNeedIsNotNeededIsNotDueEvenWithFullCandidatesAndAFullDownload() {
        when(fullNeed.neededFilmIds()).thenReturn(Set.of());

        assertThat(service.due(Set.of())).isEmpty();
        verifyNoInteractions(editions, references, credits);
    }

    @Test
    void theCreditsStartComesFromCreditsDetectionOfTheLastPart() {
        when(fullNeed.neededFilmIds()).thenReturn(Set.of(1L));
        editionLoaded();
        CreditsDetection found = new CreditsDetection();
        found.setVerdict(CreditsVerdict.FOUND);
        found.setOffsetMs(110 * MIN);
        when(credits.findById(10L)).thenReturn(Optional.of(found));

        assertThat(service.due(Set.of())).singleElement()
            .satisfies(job -> assertThat(job.creditsStartMs()).isEqualTo(110 * MIN));
    }
}
