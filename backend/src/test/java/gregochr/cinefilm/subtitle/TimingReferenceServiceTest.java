package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
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
import gregochr.cinefilm.film.FullNeedChoice;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository.ReferenceFacts;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.ForcedChoice.Decision;
import gregochr.cinefilm.subtitle.ForcedChoice.Source;
import gregochr.cinefilm.subtitle.ForcedChoice.Step;
import gregochr.cinefilm.subtitle.ForcedResolver.DownloadState;
import gregochr.cinefilm.subtitle.ForcedResolver.Resolution;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Pick;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Slot;
import gregochr.cinefilm.subtitle.TimingReferenceService.Origin;
import gregochr.cinefilm.subtitle.TimingReferenceService.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Which editions get a timing reference and what the fetch is told about them, with the repositories and the resolver
 * faked: the embedded read, the choice, the files and their origins, the ranked candidates (frame rate, hearing
 * impaired last), the references kept and the credits.
 */
@ExtendWith(MockitoExtension.class)
class TimingReferenceServiceTest {

    private static final String IMDB = "tt0000001";

    @Mock
    private SubtitleSidecarRepository sidecars;

    @Mock
    private EditionRepository editions;

    @Mock
    private EmbeddedCueStartsRepository reads;

    @Mock
    private ForcedResolver resolver;

    @Mock
    private FilmRepository films;

    @Mock
    private OpenSubtitlesReferenceRepository references;

    @Mock
    private CreditsDetectionRepository credits;

    private TimingReferenceService service;
    private Edition edition;
    private MediaFile part;

    @BeforeEach
    void setUp() {
        service = new TimingReferenceService(sidecars, editions, reads, resolver, films, references, credits, true);
        edition = newEdition(1L, 40L, 10L, 90 * MIN);
        part = edition.originalParts().get(0);
    }

    private static Edition newEdition(long filmId, long editionId, long partId, long runtimeMs) {
        Film film = film(filmId, "Film " + filmId, 2008, NOW);
        film.setImdbId(IMDB);
        Edition edition = edition(film, editionId, "Original", runtimeMs, null);
        MediaFile part = file(edition, "Film" + filmId + ".m4v");
        part.setId(partId);
        part.setPartNumber(1);
        part.setSizeBytes(1_000L);
        part.setModifiedAt(NOW);
        return edition;
    }

    private static EmbeddedCueStarts read(MediaFile file, EmbeddedCueStarts.Status status) {
        EmbeddedCueStarts read = new EmbeddedCueStarts();
        read.setMediaFileId(file.getId());
        read.setFingerprint(TimingKeys.fingerprint(file));
        read.setStatus(status);
        return read;
    }

    private static SubtitleSidecar sidecar(long id, long partId, int cues, boolean download) {
        SubtitleSidecar row = new SubtitleSidecar();
        row.setId(id);
        row.setMediaFileId(partId);
        row.setPath(download ? "opensubtitles:40:" + (5000 + id) : "Film.en.forced.srt");
        row.setCuesText("1\n00:00:10,000 --> 00:00:12,000\nHello\n\n");
        row.setCueCount(cues);
        row.setWholeEdition(download);
        row.setUpdatedAt(NOW);
        if (download) {
            row.setOrigin(SubtitleSidecar.Origin.OPENSUBTITLES);
            row.setOpenSubtitlesFileId(5000 + id);
            row.setImdbId(IMDB);
        }
        return row;
    }

    private static Resolution resolution(Edition edition, Source source, List<SubtitleSidecar> owner,
                                         List<SubtitleSidecar> downloads) {
        List<Long> partIds = edition.originalParts().stream().map(MediaFile::getId).toList();
        return new Resolution(edition, partIds, new Pick(owner.stream().map(s -> new Slot(s, 0)).toList(), Set.of()),
            null, null, null, null, Map.of(), downloads.stream().map(d -> new DownloadState(d, null, null, null, null))
            .toList(), source == Source.EMBEDDED, new Decision(Step.NONE, source, null, null, Map.of(), false, false),
            Map.of());
    }

    /** The edition is known to the timing check with its part read as {@code status}, and the choice says so. */
    private void readyEdition(Edition e, EmbeddedCueStarts.Status status, Resolution resolution) {
        when(sidecars.findFilmIdsWithPresentSubtitle(SubtitleKind.FORCED)).thenReturn(
            List.of(e.getFilm().getId()));
        when(editions.findWithFilesByFilmIdIn(Set.of(e.getFilm().getId()))).thenReturn(List.of(e));
        MediaFile first = e.originalParts().get(0);
        when(reads.findAllById(List.of(first.getId()))).thenReturn(List.of(read(first, status)));
        if (resolution != null) {
            when(resolver.resolve(List.of(e))).thenReturn(Map.of(e.getId(), resolution));
        }
    }

    private void qualifying(List<SubtitleSidecar> owner, List<SubtitleSidecar> downloads) {
        readyEdition(edition, EmbeddedCueStarts.Status.NO_REFERENCE,
            resolution(edition, Source.FILE, owner, downloads));
    }

    private static OpenSubtitlesResult result(long id, Double fps, boolean impaired, Integer fileCount) {
        return new OpenSubtitlesResult(id, id + 1000, "Release " + id, fps, 10, false, impaired, null, null,
            fileCount);
    }

    private void candidates(OpenSubtitlesResult... results) {
        when(films.findOpenSubtitlesFull()).thenReturn(List.of(new OsFullRef(1L, IMDB,
            OpenSubtitlesResult.listToJson(List.of(results)))));
    }

    private static OpenSubtitlesReference stored(long fileId) {
        OpenSubtitlesReference reference = new OpenSubtitlesReference();
        reference.setFilmId(1L);
        reference.setImdbId(IMDB);
        reference.setOsFileId(fileId);
        reference.setCueCount(300);
        return reference;
    }

    @Test
    void anEditionWithATwentyLineOwnerFileAndNoEnglishTrackInTheFilmQualifiesAndItsFilmIsSearched() {
        qualifying(List.of(sidecar(5, 10L, 20, false)), List.of());

        assertThat(service.filmIds()).containsExactly(1L);
    }

    @Test
    void theFilesAreTheOwnersFileAndEveryDownloadWithTheirOriginsAndAPickPutsTheEditionFirst() {
        SubtitleSidecar owner = sidecar(5, 10L, 20, false);
        SubtitleSidecar plain = sidecar(6, 10L, 30, true);
        SubtitleSidecar picked = sidecar(7, 10L, 40, true);
        picked.setOwnerChosenAt(NOW);
        qualifying(List.of(owner), List.of(plain, picked));
        candidates(result(1, 23.976, false, 1));
        when(references.findByFilmIdIn(Set.of(1L))).thenReturn(List.of());

        List<Target> targets = service.targets();

        assertThat(targets).singleElement().satisfies(target -> {
            assertThat(target.ownerPick()).isTrue();
            assertThat(target.files()).extracting(f -> f.sidecarId(), f -> f.origin(), f -> f.lines())
                .containsExactly(tuple(5L, Origin.OWNER, 20),
                    tuple(6L, Origin.DOWNLOAD, 30),
                    tuple(7L, Origin.CHOSEN, 40));
            assertThat(target.files()).extracting(f -> f.osFileId()).containsExactly(null, 5006L, 5007L);
            assertThat(target.runtimeMs()).isEqualTo(90 * MIN);
            assertThat(target.imdbId()).isEqualTo(IMDB);
            assertThat(target.editionName()).isEqualTo("Original");
            assertThat(target.creditsStartMs()).isNull();
        });
    }

    @Test
    void editionsWithAnOwnersPickComeFirstThenByFilmAndEdition() {
        Edition other = newEdition(2L, 41L, 11L, 90 * MIN);
        SubtitleSidecar pickedOnOther = sidecar(8, 11L, 20, true);
        pickedOnOther.setOwnerChosenAt(NOW);
        when(sidecars.findFilmIdsWithPresentSubtitle(SubtitleKind.FORCED)).thenReturn(List.of(1L, 2L));
        when(editions.findWithFilesByFilmIdIn(Set.of(1L, 2L))).thenReturn(List.of(edition, other));
        when(reads.findAllById(List.of(10L, 11L))).thenReturn(List.of(
            read(part, EmbeddedCueStarts.Status.NO_REFERENCE),
            read(other.originalParts().get(0), EmbeddedCueStarts.Status.NO_REFERENCE)));
        when(resolver.resolve(List.of(edition, other))).thenReturn(Map.of(
            40L, resolution(edition, Source.FILE, List.of(sidecar(5, 10L, 20, false)), List.of()),
            41L, resolution(other, Source.DOWNLOAD, List.of(), List.of(pickedOnOther))));
        when(films.findOpenSubtitlesFull()).thenReturn(List.of(
            new OsFullRef(1L, IMDB, OpenSubtitlesResult.listToJson(List.of(result(1, null, false, 1)))),
            new OsFullRef(2L, IMDB, OpenSubtitlesResult.listToJson(List.of(result(2, null, false, 1))))));
        when(references.findByFilmIdIn(Set.of(1L, 2L))).thenReturn(List.of());

        assertThat(service.targets()).extracting(Target::filmId, Target::ownerPick)
            .containsExactly(tuple(2L, true),
                tuple(1L, false));
    }

    @Test
    void editionsWithNoPickAreOrderedByFilmIdAndNotByTheOrderTheyWereLoaded() {
        Edition other = newEdition(2L, 41L, 11L, 90 * MIN);
        when(sidecars.findFilmIdsWithPresentSubtitle(SubtitleKind.FORCED)).thenReturn(List.of(1L, 2L));
        when(editions.findWithFilesByFilmIdIn(Set.of(1L, 2L))).thenReturn(List.of(other, edition));
        when(reads.findAllById(List.of(11L, 10L))).thenReturn(List.of(
            read(part, EmbeddedCueStarts.Status.NO_REFERENCE),
            read(other.originalParts().get(0), EmbeddedCueStarts.Status.NO_REFERENCE)));
        when(resolver.resolve(List.of(other, edition))).thenReturn(Map.of(
            40L, resolution(edition, Source.FILE, List.of(sidecar(5, 10L, 20, false)), List.of()),
            41L, resolution(other, Source.FILE, List.of(sidecar(8, 11L, 20, false)), List.of())));
        when(films.findOpenSubtitlesFull()).thenReturn(List.of(
            new OsFullRef(1L, IMDB, OpenSubtitlesResult.listToJson(List.of(result(1, null, false, 1)))),
            new OsFullRef(2L, IMDB, OpenSubtitlesResult.listToJson(List.of(result(2, null, false, 1))))));
        when(references.findByFilmIdIn(Set.of(1L, 2L))).thenReturn(List.of());

        assertThat(service.targets()).extracting(Target::filmId).containsExactly(1L, 2L);
    }

    @Test
    void aFilmTheOwnerSaidNoSubtitlesExistForNeverQualifiesWhileAnotherFilmStillDoes() {
        when(sidecars.findFilmIdsWithPresentSubtitle(SubtitleKind.FORCED)).thenReturn(List.of(1L, 2L));
        when(films.findIdsByFullNeedOwner(FullNeedChoice.NONE_EXIST)).thenReturn(List.of(2L));
        when(editions.findWithFilesByFilmIdIn(Set.of(1L))).thenReturn(List.of(edition));
        when(reads.findAllById(List.of(10L))).thenReturn(List.of(read(part, EmbeddedCueStarts.Status.NO_REFERENCE)));
        when(resolver.resolve(List.of(edition))).thenReturn(Map.of(
            40L, resolution(edition, Source.FILE, List.of(sidecar(5, 10L, 20, false)), List.of())));
        when(films.findOpenSubtitlesFull()).thenReturn(List.of(
            new OsFullRef(1L, IMDB, OpenSubtitlesResult.listToJson(List.of(result(1, null, false, 1)))),
            new OsFullRef(2L, IMDB, OpenSubtitlesResult.listToJson(List.of(result(2, null, false, 1))))));
        when(references.findByFilmIdIn(Set.of(1L))).thenReturn(List.of());

        assertThat(service.filmIds()).containsExactly(1L);
        assertThat(service.targets()).extracting(Target::filmId).containsExactly(1L);
    }

    @Test
    void whenEveryForcedFilmIsNoneExistNothingIsLookedAtAfterThat() {
        when(sidecars.findFilmIdsWithPresentSubtitle(SubtitleKind.FORCED)).thenReturn(List.of(2L));
        when(films.findIdsByFullNeedOwner(FullNeedChoice.NONE_EXIST)).thenReturn(List.of(2L));

        assertThat(service.filmIds()).isEmpty();
        assertThat(service.targets()).isEmpty();
        verifyNoInteractions(editions, reads, resolver);
    }

    @Test
    void aQualifyingFilmWithNoFullCandidatesIsLeftOutWhileAnotherFilmsAreKept() {
        Edition other = newEdition(2L, 41L, 11L, 90 * MIN);
        when(sidecars.findFilmIdsWithPresentSubtitle(SubtitleKind.FORCED)).thenReturn(List.of(1L, 2L));
        when(editions.findWithFilesByFilmIdIn(Set.of(1L, 2L))).thenReturn(List.of(edition, other));
        when(reads.findAllById(List.of(10L, 11L))).thenReturn(List.of(
            read(part, EmbeddedCueStarts.Status.NO_REFERENCE),
            read(other.originalParts().get(0), EmbeddedCueStarts.Status.NO_REFERENCE)));
        when(resolver.resolve(List.of(edition, other))).thenReturn(Map.of(
            40L, resolution(edition, Source.FILE, List.of(sidecar(5, 10L, 20, false)), List.of()),
            41L, resolution(other, Source.FILE, List.of(sidecar(8, 11L, 20, false)), List.of())));
        when(films.findOpenSubtitlesFull()).thenReturn(List.of(
            new OsFullRef(2L, IMDB, OpenSubtitlesResult.listToJson(List.of(result(2, null, false, 1))))));
        when(references.findByFilmIdIn(Set.of(2L))).thenReturn(List.of());

        assertThat(service.targets()).extracting(Target::filmId).containsExactly(2L);
    }

    @Test
    void theCandidatesAreSingleFilesOfAPlausibleFrameRateWithAHearingImpairedFileLast() {
        qualifying(List.of(sidecar(5, 10L, 20, false)), List.of());
        candidates(result(1, 23.976, true, 1), result(2, 25.0, false, 1), result(3, null, false, 1),
            result(4, 23.976, false, 2), result(5, 23.98, false, 1), result(6, 24.0, false, 1));
        when(references.findByFilmIdIn(Set.of(1L))).thenReturn(List.of());

        assertThat(service.targets()).singleElement().satisfies(target -> assertThat(target.candidates())
            .extracting(OpenSubtitlesResult::id).containsExactly(5L, 3L, 1L));
    }

    @Test
    void aHearingImpairedFileOfTheEditionsOwnCutStillComesAfterANonImpairedFileOfAnotherCut() {
        edition.setName("Extended Edition");
        qualifying(List.of(sidecar(5, 10L, 20, false)), List.of());
        candidates(new OpenSubtitlesResult(1, 1001, "Film.Extended.SDH", 23.976, 10, false, true, null, null, 1),
            new OpenSubtitlesResult(2, 1002, "Film.Theatrical", 23.976, 10, false, false, null, null, 1));
        when(references.findByFilmIdIn(Set.of(1L))).thenReturn(List.of());

        assertThat(service.targets()).singleElement().satisfies(target -> assertThat(target.candidates())
            .extracting(OpenSubtitlesResult::id).containsExactly(2L, 1L));
    }

    @Test
    void theReferencesKeptAreTheStoredOnesAmongTheCurrentCandidatesOfTheCurrentImdbId() {
        qualifying(List.of(sidecar(5, 10L, 20, false)), List.of());
        candidates(result(1, 23.976, false, 1), result(2, 25.0, false, 1), result(3, null, false, 2));
        OpenSubtitlesReference other = stored(1_001);
        other.setImdbId("tt0000002");
        when(references.findByFilmIdIn(Set.of(1L))).thenReturn(List.of(stored(1_001), stored(1_002), stored(1_003),
            stored(9_999), other));

        assertThat(service.targets()).singleElement().satisfies(target -> {
            assertThat(target.referencesKept()).isEqualTo(3);
            assertThat(target.stored()).containsOnlyKeys(1_001L, 1_002L, 1_003L, 9_999L);
        });
    }

    @Test
    void aFilmWithNoFullCandidatesIsSearchedButHasNothingToFetch() {
        qualifying(List.of(sidecar(5, 10L, 20, false)), List.of());
        when(films.findOpenSubtitlesFull()).thenReturn(List.of());

        assertThat(service.filmIds()).containsExactly(1L);
        assertThat(service.targets()).isEmpty();
    }

    private static ReferenceFacts facts(long fileId, String imdbId, int cues) {
        return new ReferenceFacts(fileId, 1L, imdbId, fileId, null, cues, 90_000L, null);
    }

    @Test
    void aFilmNeverSearchedButWithAStoredFullFileUnderItsImdbIdIsATargetWithNoCandidates() {
        qualifying(List.of(sidecar(5, 10L, 20, false)), List.of());
        when(films.findOpenSubtitlesFull()).thenReturn(List.of());
        when(references.findFactsByFilmIdIn(Set.of(1L))).thenReturn(List.of(facts(1_001, IMDB, 300)));
        when(references.findByFilmIdIn(Set.of(1L))).thenReturn(List.of(stored(1_001), stored(1_002)));

        assertThat(service.targets()).singleElement().satisfies(target -> {
            assertThat(target.candidates()).isEmpty();
            assertThat(target.imdbId()).isEqualTo(IMDB);
            assertThat(target.referencesKept()).isZero();
            assertThat(target.stored()).containsOnlyKeys(1_001L, 1_002L);
        });
    }

    @Test
    void aStoredFileUnder300LinesOrUnderAnotherImdbIdDoesNotMakeAFilmWithNoCandidatesATarget() {
        qualifying(List.of(sidecar(5, 10L, 20, false)), List.of());
        when(films.findOpenSubtitlesFull()).thenReturn(List.of());
        when(references.findFactsByFilmIdIn(Set.of(1L))).thenReturn(List.of(facts(1_001, IMDB, 299),
            facts(1_002, "tt0000002", 400)));

        assertThat(service.targets()).isEmpty();
    }

    @Test
    void creditsDetectionPlacesTheCreditsStartOnTheEditionsTimeline() {
        qualifying(List.of(sidecar(5, 10L, 20, false)), List.of());
        candidates(result(1, null, false, 1));
        when(references.findByFilmIdIn(Set.of(1L))).thenReturn(List.of());
        CreditsDetection found = new CreditsDetection();
        found.setVerdict(CreditsVerdict.FOUND);
        found.setOffsetMs(80 * MIN);
        when(credits.findById(10L)).thenReturn(Optional.of(found));

        assertThat(service.targets()).singleElement().satisfies(target ->
            assertThat(target.creditsStartMs()).isEqualTo(80 * MIN));
    }

    @Test
    void nothingQualifiesWhileTheTimingCheckIsOff() {
        TimingReferenceService off = new TimingReferenceService(sidecars, editions, reads, resolver, films,
            references, credits, false);

        assertThat(off.filmIds()).isEmpty();
        assertThat(off.targets()).isEmpty();
        verifyNoInteractions(sidecars, editions, reads, resolver, films, references, credits);
    }

    @Test
    void withNoForcedSubtitleAnywhereNothingIsLookedAt() {
        when(sidecars.findFilmIdsWithPresentSubtitle(SubtitleKind.FORCED)).thenReturn(List.of());

        assertThat(service.filmIds()).isEmpty();
        verifyNoInteractions(editions, reads, resolver);
    }

    @Test
    void anEditionWithAnUnknownRuntimeOrNoImdbIdOrNoPartIsNotEvenRead() {
        Edition noRuntime = newEdition(1L, 40L, 10L, 0);
        Edition noImdb = newEdition(2L, 41L, 11L, 90 * MIN);
        noImdb.getFilm().setImdbId(null);
        Film bareFilm = film(3L, "Bare", 2000, NOW);
        bareFilm.setImdbId(IMDB);
        Edition bare = edition(bareFilm, 42L, "Original", MIN, null);
        when(sidecars.findFilmIdsWithPresentSubtitle(SubtitleKind.FORCED)).thenReturn(List.of(1L, 2L, 3L));
        when(editions.findWithFilesByFilmIdIn(Set.of(1L, 2L, 3L))).thenReturn(List.of(noRuntime, noImdb, bare));
        when(reads.findAllById(List.of())).thenReturn(List.of());

        assertThat(service.filmIds()).isEmpty();
        verifyNoInteractions(resolver);
    }

    @Test
    void aPartWhoseTrackWasNotReadAsItIsNowOrHasAUsableTrackKeepsTheEditionOut() {
        when(sidecars.findFilmIdsWithPresentSubtitle(SubtitleKind.FORCED)).thenReturn(List.of(1L));
        when(editions.findWithFilesByFilmIdIn(Set.of(1L))).thenReturn(List.of(edition));
        EmbeddedCueStarts stale = read(part, EmbeddedCueStarts.Status.NO_REFERENCE);
        stale.setFingerprint("1:2");
        when(reads.findAllById(List.of(10L))).thenReturn(List.of(stale), List.of(),
            List.of(read(part, EmbeddedCueStarts.Status.USABLE)));

        assertThat(service.filmIds()).isEmpty();
        assertThat(service.filmIds()).isEmpty();
        assertThat(service.filmIds()).isEmpty();
        verifyNoInteractions(resolver);
    }

    @Test
    void aPartWithNoFingerprintKeepsTheEditionOut() {
        part.setSizeBytes(null);
        readyEdition(edition, EmbeddedCueStarts.Status.NO_REFERENCE, null);

        assertThat(service.filmIds()).isEmpty();
        verifyNoInteractions(resolver);
    }

    @Test
    void aMissingPartKeepsTheEditionOut() {
        part.setPresence(Presence.MISSING);
        readyEdition(edition, EmbeddedCueStarts.Status.NO_REFERENCE, null);

        assertThat(service.filmIds()).isEmpty();
        verifyNoInteractions(resolver);
    }

    @Test
    void anEditionThatPlaysItsEmbeddedForcedTrackIsKeptOut() {
        readyEdition(edition, EmbeddedCueStarts.Status.NO_REFERENCE,
            resolution(edition, Source.EMBEDDED, List.of(sidecar(5, 10L, 20, false)), List.of()));

        assertThat(service.filmIds()).isEmpty();
    }

    @Test
    void aVerifiedFileOrOnlyFullLengthFilesKeepTheEditionOut() {
        SubtitleSidecar verified = sidecar(5, 10L, 20, false);
        verified.setOwnerVerifiedAt(NOW);
        verified.setVerifiedParts("10");
        readyEdition(edition, EmbeddedCueStarts.Status.NO_REFERENCE,
            resolution(edition, Source.FILE, List.of(verified), List.of()));
        assertThat(service.filmIds()).isEmpty();

        when(resolver.resolve(List.of(edition))).thenReturn(Map.of(40L, resolution(edition, Source.FILE,
            List.of(sidecar(6, 10L, 300, false)), List.of())));
        assertThat(service.filmIds()).isEmpty();
    }

    @Test
    void aMixedSplitEditionWithATrackOrNoReadInOnePartIsLeftOutWithoutAskingTheResolver() {
        Edition split = newEdition(1L, 40L, 10L, 90 * MIN);
        MediaFile second = file(split, "Film1b.m4v");
        second.setId(11L);
        second.setPartNumber(2);
        second.setSizeBytes(2_000L);
        second.setModifiedAt(NOW);
        when(sidecars.findFilmIdsWithPresentSubtitle(SubtitleKind.FORCED)).thenReturn(List.of(1L));
        when(editions.findWithFilesByFilmIdIn(Set.of(1L))).thenReturn(List.of(split));
        List<MediaFile> parts = split.originalParts();
        when(reads.findAllById(List.of(10L, 11L))).thenReturn(
            List.of(read(parts.get(0), EmbeddedCueStarts.Status.NO_REFERENCE),
                read(parts.get(1), EmbeddedCueStarts.Status.USABLE)),
            List.of(read(parts.get(0), EmbeddedCueStarts.Status.NO_REFERENCE)));

        assertThat(service.filmIds()).isEmpty();
        assertThat(service.filmIds()).isEmpty();
        verifyNoInteractions(resolver);
    }

    @Test
    void aSplitEditionNeedsEveryPartReadWithNoTrackAndEveryFileTimedForTheWholeEdition() {
        Edition split = newEdition(1L, 40L, 10L, 90 * MIN);
        MediaFile second = file(split, "Film1b.m4v");
        second.setId(11L);
        second.setPartNumber(2);
        second.setSizeBytes(2_000L);
        second.setModifiedAt(NOW);
        when(sidecars.findFilmIdsWithPresentSubtitle(SubtitleKind.FORCED)).thenReturn(List.of(1L));
        when(editions.findWithFilesByFilmIdIn(Set.of(1L))).thenReturn(List.of(split));
        List<MediaFile> parts = split.originalParts();
        when(reads.findAllById(List.of(10L, 11L))).thenReturn(List.of(
            read(parts.get(0), EmbeddedCueStarts.Status.NO_REFERENCE),
            read(parts.get(1), EmbeddedCueStarts.Status.NO_REFERENCE)));
        List<SubtitleSidecar> perPart = new ArrayList<>(List.of(sidecar(5, 10L, 20, false)));
        when(resolver.resolve(List.of(split))).thenReturn(Map.of(40L,
            resolution(split, Source.FILE, perPart, List.of()))).thenReturn(Map.of(40L,
            resolution(split, Source.DOWNLOAD, List.of(), List.of(sidecar(6, 10L, 20, true)))));

        assertThat(service.filmIds()).isEmpty();
        assertThat(service.filmIds()).containsExactly(1L);
    }

    @Test
    void aFilesSideIsItsRowsOwnSoARetimedDownloadIsADownloadWithItsRetimeAndAnOwnersFileIsTheFile() {
        SubtitleSidecar owner = sidecar(5, 10L, 20, false);
        SubtitleSidecar retimed = sidecar(6, 10L, 30, true);
        retimed.setTimedFps(23.976);
        retimed.setRetime(RetimeChoice.AUTO);
        retimed.setRetimedBy(0.959);
        retimed.setOriginalCuesText("1\n00:01:40,000 --> 00:01:42,000\nHello\n\n");
        qualifying(List.of(owner), List.of(retimed));
        candidates(result(1, 23.976, false, 1));
        when(references.findByFilmIdIn(Set.of(1L))).thenReturn(List.of());

        List<Target> targets = service.targets();

        assertThat(targets).singleElement().satisfies(target -> {
            assertThat(target.files().get(0).rateSide()).isEqualTo(RateComparison.Side.file());
            assertThat(target.files().get(1).side()).isEqualTo(RateComparison.Side.of(retimed));
            assertThat(target.files().get(1).side().kind()).isEqualTo(RateComparison.Kind.DOWNLOAD);
            assertThat(target.files().get(1).side().retimedBy()).isEqualTo(0.959);
        });
    }
}
