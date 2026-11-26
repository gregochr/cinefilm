package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.config.ServiceUnavailableException;
import gregochr.cinefilm.config.UnprocessableException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesApi;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesBudget;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesChargedException;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesDownload;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesQuotaException;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesSearch;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesUnavailableException;
import gregochr.cinefilm.subtitle.EditionOpenSubtitlesDtos.DownloadDto;
import gregochr.cinefilm.subtitle.EditionOpenSubtitlesDtos.DownloadRequest;
import gregochr.cinefilm.subtitle.EditionOpenSubtitlesDtos.SearchDto;
import gregochr.cinefilm.subtitle.ForcedDownloadService.ChosenOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;

/** The picker's search and download with OpenSubtitles, the budget and the download service faked. */
@ExtendWith(MockitoExtension.class)
class EditionOpenSubtitlesServiceTest {

    private static final String IMDB = "tt0936501";
    private static final String CUES = "1\n00:00:10,000 --> 00:00:12,000\nHello\n\n";
    private static final LocalDate DAY = LocalDate.of(2024, 3, 31);

    /** A clock the test moves. */
    private static final class MovableClock extends Clock {

        private Instant now = NOW;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Mock
    private OpenSubtitlesApi api;

    @Mock
    private EditionRepository editions;

    @Mock
    private ForcedDownloadService downloads;

    @Mock
    private OpenSubtitlesBudget budget;

    @Mock
    private OpenSubtitlesReferenceRepository references;

    @Mock
    private DailyDownloadLimit limits;

    @Mock
    private ApplicationEventPublisher events;

    private final LibraryLock lock = new LibraryLock();
    private MovableClock clock;
    private EditionOpenSubtitlesService service;
    private Film film;
    private Edition edition;

    @BeforeEach
    void setUp() {
        clock = new MovableClock();
        service = new EditionOpenSubtitlesService(api, editions, downloads, budget, references, limits, lock,
            new PickerSearches(clock), clock, events);
        film = film(1L, "Taken", 2008, NOW);
        film.setImdbId(IMDB);
        edition = edition(film, 40L, "Extended Edition", 90 * MIN, null);
        MediaFile part = file(edition, "Taken.m4v");
        part.setId(10L);
    }

    private static OpenSubtitlesResult result(long fileId, String release, Double fps, boolean impaired,
                                              int fileCount) {
        return new OpenSubtitlesResult(fileId - 1000, fileId, release, fps, 10, false, impaired,
            "2020-01-02T03:04:05Z", "Gold member", fileCount, release + ".srt");
    }

    private static OpenSubtitlesResult plain(long fileId) {
        return result(fileId, "Taken.2008.BluRay", 23.976, false, 1);
    }

    private static OpenSubtitlesReference stored(long fileId, int cueCount) {
        OpenSubtitlesReference reference = new OpenSubtitlesReference();
        reference.setFilmId(1L);
        reference.setImdbId(IMDB);
        reference.setOsFileId(fileId);
        reference.setRelease("Release " + fileId);
        reference.setTimedFps(23.976);
        reference.setCuesText(CUES);
        reference.setCueCount(cueCount);
        reference.setFetchedAt(NOW);
        return reference;
    }

    private void editionLoads() {
        when(editions.findWithFilmById(40L)).thenReturn(Optional.of(edition));
    }

    private void theEditionHasAFile() {
        when(downloads.hasOriginalFile(1L, 40L)).thenReturn(true);
    }

    /** A forced search that has offered these results to edition 40. */
    private void searchedForced(OpenSubtitlesResult... results) {
        editionLoads();
        when(api.enabled()).thenReturn(true);
        theEditionHasAFile();
        when(api.searchForced(IMDB)).thenReturn(new OpenSubtitlesSearch(results.length, null, List.of(),
            List.of(results)));
        when(downloads.downloadsLeft(1L, 40L, SubtitleKind.FORCED, IMDB)).thenReturn(3);
        service.search(40L, "forced");
    }

    /** The state a download needs once a search has offered the file: downloads on, nothing refuses it. */
    private void readyToDownload(long fileId) {
        when(api.downloadsEnabled()).thenReturn(true);
        when(downloads.refusal(1L, 40L, SubtitleKind.FORCED, IMDB, fileId)).thenReturn(Optional.empty());
    }

    /** The film has this file stored already (it is never fetched twice), and the day's limit is 20. */
    private OpenSubtitlesReference storedReferenceExists(long fileId, int cueCount) {
        when(limits.today()).thenReturn(20);
        OpenSubtitlesReference reference = stored(fileId, cueCount);
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, IMDB, fileId)).thenReturn(Optional.of(reference));
        return reference;
    }

    private String refusedWith(DownloadRequest request) {
        return catchThrowableOfType(ConflictException.class, () -> service.download(40L, request)).getMessage();
    }

    private static DownloadRequest forced(long fileId) {
        return new DownloadRequest(fileId, "forced");
    }

    // ---------------------------------------------------------------- search

    @Test
    void aForcedSearchIsRankedForTheEditionsNameAndShowsEveryField() {
        editionLoads();
        when(api.enabled()).thenReturn(true);
        theEditionHasAFile();
        OpenSubtitlesResult theatrical = result(5011, "Taken.2008.BluRay", 23.976, false, 1);
        OpenSubtitlesResult extended = result(5012, "Taken.2008.Extended.BluRay", 25.0, false, 1);
        when(api.searchForced(IMDB)).thenReturn(new OpenSubtitlesSearch(2, theatrical, List.of(theatrical),
            List.of(theatrical, extended)));
        when(downloads.downloadsLeft(1L, 40L, SubtitleKind.FORCED, IMDB)).thenReturn(2);

        SearchDto dto = service.search(40L, "forced");

        assertThat(dto.editionId()).isEqualTo(40L);
        assertThat(dto.kind()).isEqualTo("forced");
        assertThat(dto.imdbId()).isEqualTo(IMDB);
        assertThat(dto.downloadsLeft()).isEqualTo(2);
        assertThat(dto.results()).extracting(r -> r.fileId()).containsExactly(5012L, 5011L);
        assertThat(dto.results().get(0).release()).isEqualTo("Taken.2008.Extended.BluRay");
        assertThat(dto.results().get(0).fileName()).isEqualTo("Taken.2008.Extended.BluRay.srt");
        assertThat(dto.results().get(0).fps()).isEqualTo(25.0);
        assertThat(dto.results().get(0).downloadCount()).isEqualTo(10);
        assertThat(dto.results().get(0).fromTrusted()).isFalse();
        assertThat(dto.results().get(0).hearingImpaired()).isFalse();
        assertThat(dto.results().get(0).uploadDate()).isEqualTo("2020-01-02T03:04:05Z");
    }

    @Test
    void aFullSearchUsesTheFullSearchAndTheFullRankingSoAHearingImpairedFileComesLast() {
        editionLoads();
        when(api.enabled()).thenReturn(true);
        theEditionHasAFile();
        edition.setName("Original");
        OpenSubtitlesResult impaired = result(6011, "Taken.HI", 23.976, true, 1);
        OpenSubtitlesResult plain = result(6012, "Taken.Plain", 25.0, false, 1);
        when(api.searchFull(IMDB)).thenReturn(new OpenSubtitlesSearch(2, plain, List.of(plain, impaired),
            List.of(impaired, plain)));
        when(downloads.downloadsLeft(1L, 40L, SubtitleKind.FULL, IMDB)).thenReturn(3);

        SearchDto dto = service.search(40L, "full");

        assertThat(dto.kind()).isEqualTo("full");
        assertThat(dto.results()).extracting(r -> r.fileId()).containsExactly(6012L, 6011L);
    }

    @Test
    void aForcedSearchRanksAHearingImpairedFileByTheFilmLevelOrderNotLast() {
        editionLoads();
        when(api.enabled()).thenReturn(true);
        theEditionHasAFile();
        edition.setName("Original");
        OpenSubtitlesResult impaired = result(5011, "Taken.HI", 23.976, true, 1);
        OpenSubtitlesResult plain = result(5012, "Taken.Plain", 25.0, false, 1);
        when(api.searchForced(IMDB)).thenReturn(new OpenSubtitlesSearch(2, impaired, List.of(),
            List.of(plain, impaired)));
        when(downloads.downloadsLeft(1L, 40L, SubtitleKind.FORCED, IMDB)).thenReturn(3);

        assertThat(service.search(40L, "forced").results()).extracting(r -> r.fileId())
            .containsExactly(5011L, 5012L);
    }

    @Test
    void aFileSplitOverSeveralDiscsIsNotOffered() {
        editionLoads();
        when(api.enabled()).thenReturn(true);
        theEditionHasAFile();
        OpenSubtitlesResult single = plain(5011);
        OpenSubtitlesResult split = result(5012, "Taken.CD1", 23.976, false, 2);
        OpenSubtitlesResult another = result(5013, "Taken.Old", 23.976, false, 1);
        OpenSubtitlesResult countUnknown = new OpenSubtitlesResult(1, 5014, "Taken.NoCount", 23.976, 1, false, false,
            null, null, null, null);
        when(api.searchForced(IMDB)).thenReturn(new OpenSubtitlesSearch(4, single, List.of(single),
            List.of(single, split, another, countUnknown)));
        when(downloads.downloadsLeft(1L, 40L, SubtitleKind.FORCED, IMDB)).thenReturn(3);

        assertThat(service.search(40L, "forced").results()).extracting(r -> r.fileId())
            .containsExactlyInAnyOrder(5011L, 5013L);
    }

    @Test
    void aSearchWithNoResultsAnswersAnEmptyList() {
        editionLoads();
        when(api.enabled()).thenReturn(true);
        theEditionHasAFile();
        when(api.searchForced(IMDB)).thenReturn(new OpenSubtitlesSearch(0, null));
        when(downloads.downloadsLeft(1L, 40L, SubtitleKind.FORCED, IMDB)).thenReturn(3);

        assertThat(service.search(40L, "forced").results()).isEmpty();
    }

    @Test
    void aMissingOrUnknownKindIs400() {
        for (String kind : new String[] {null, "", "FULL", "Forced", "both"}) {
            assertThatThrownBy(() -> service.search(40L, kind)).isInstanceOf(BadRequestException.class)
                .hasMessage("The kind must be full or forced");
        }
    }

    @Test
    void anUnknownEditionIs404() {
        when(editions.findWithFilmById(40L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.search(40L, "forced")).isInstanceOf(NotFoundException.class)
            .hasMessage("No such edition");
    }

    @Test
    void aFilmWithNoImdbIdIs400BeforeOpenSubtitlesIsAsked() {
        editionLoads();
        film.setImdbId(null);
        assertThatThrownBy(() -> service.search(40L, "forced")).isInstanceOf(BadRequestException.class)
            .hasMessage("This film has no IMDb id, so OpenSubtitles cannot be searched. Fix its match first.");

        film.setImdbId("  ");
        assertThatThrownBy(() -> service.search(40L, "forced")).isInstanceOf(BadRequestException.class)
            .hasMessage("This film has no IMDb id, so OpenSubtitles cannot be searched. Fix its match first.");
        verifyNoInteractions(api);
    }

    @Test
    void anImdbIdTheClientRejectsIs400LikeAMissingOne() {
        editionLoads();
        when(api.enabled()).thenReturn(true);
        theEditionHasAFile();
        when(api.searchForced(IMDB)).thenThrow(new IllegalArgumentException("Not an IMDb id"));

        assertThatThrownBy(() -> service.search(40L, "forced")).isInstanceOf(BadRequestException.class)
            .hasMessage("This film has no IMDb id, so OpenSubtitles cannot be searched. Fix its match first.");
    }

    @Test
    void anEditionWithNoOriginalFileIs409OnASearchAndNothingIsAskedOfOpenSubtitles() {
        editionLoads();
        when(downloads.hasOriginalFile(1L, 40L)).thenReturn(false);

        assertThatThrownBy(() -> service.search(40L, "forced")).isInstanceOf(ConflictException.class)
            .hasMessage("This edition has no original file to attach subtitles to.");
        verifyNoInteractions(api);
    }

    @Test
    void anEditionWithNoOriginalFileIs409OnADownloadBeforeAnyDownloadIsSpent() {
        searchedForced(plain(5011));
        when(api.downloadsEnabled()).thenReturn(true);
        when(downloads.refusal(1L, 40L, SubtitleKind.FORCED, IMDB, 5011L)).thenReturn(
            Optional.of(ChosenOutcome.NO_FILE));

        assertThat(refusedWith(forced(5011))).isEqualTo("This edition has no original file to attach subtitles to.");

        verifyNoInteractions(budget);
        verifyNoInteractions(limits);
        verifyNoInteractions(events);
    }

    @Test
    void anEditionWhoseFileGoesBetweenTheCheckAndTheLockIs409AfterTheFetchAndAnnouncesNothing() {
        searchedForced(plain(5011));
        readyToDownload(5011);
        OpenSubtitlesReference reference = storedReferenceExists(5011, 12);
        when(downloads.addChosen(1L, 40L, SubtitleKind.FORCED, reference, 23.976, NOW))
            .thenReturn(ChosenOutcome.NO_FILE);

        assertThat(refusedWith(forced(5011))).isEqualTo("This edition has no original file to attach subtitles to.");

        verifyNoInteractions(events);
    }

    @Test
    void aSearchWithNoApiKeyIs503() {
        editionLoads();
        when(api.enabled()).thenReturn(false);
        theEditionHasAFile();

        assertThatThrownBy(() -> service.search(40L, "full")).isInstanceOf(ServiceUnavailableException.class)
            .hasMessage("OpenSubtitles is not set up");
        verify(api).enabled();
        verifyNoMoreInteractions(api);
    }

    @Test
    void anOutageDuringTheSearchReachesTheCaller() {
        editionLoads();
        when(api.enabled()).thenReturn(true);
        theEditionHasAFile();
        OpenSubtitlesUnavailableException outage = new OpenSubtitlesUnavailableException("down");
        when(api.searchForced(IMDB)).thenThrow(outage);

        assertThatThrownBy(() -> service.search(40L, "forced")).isSameAs(outage);
    }

    // -------------------------------------------------------------- download

    @Test
    void aFileASearchOfferedIsDownloadedPinnedAnnouncedAndAnsweredWithWhatIsLeft() {
        searchedForced(plain(5011));
        readyToDownload(5011);
        OpenSubtitlesReference reference = storedReferenceExists(5011, 12);
        when(downloads.addChosen(1L, 40L, SubtitleKind.FORCED, reference, 23.976, NOW)).thenReturn(ChosenOutcome.ADDED);
        when(downloads.downloadsLeft(1L, 40L, SubtitleKind.FORCED, IMDB)).thenReturn(2);

        DownloadDto dto = service.download(40L, forced(5011));

        assertThat(dto).isEqualTo(new DownloadDto(40L, "forced", 5011, "Taken.2008.BluRay", 2));
        verify(events).publishEvent(new SubtitleDownloadsFinished());
    }

    @Test
    void aPickedFileGetsTheRateItsResultStatesWhenTheReferenceHasNoneAndTheFillFails() {
        searchedForced(plain(5011));
        readyToDownload(5011);
        OpenSubtitlesReference reference = storedReferenceExists(5011, 12);
        reference.setId(7L);
        reference.setTimedFps(null);
        doThrow(new DataAccessResourceFailureException("database busy")).when(references).fillTimedFps(7L, 23.976);
        when(downloads.addChosen(1L, 40L, SubtitleKind.FORCED, reference, 23.976, NOW))
            .thenReturn(ChosenOutcome.ADDED);
        when(downloads.downloadsLeft(1L, 40L, SubtitleKind.FORCED, IMDB)).thenReturn(2);

        assertThat(service.download(40L, forced(5011)).fileId()).isEqualTo(5011L);

        assertThat(reference.getTimedFps()).isNull();
        verify(downloads).addChosen(1L, 40L, SubtitleKind.FORCED, reference, 23.976, NOW);
    }

    @Test
    void aFullPickGoesThroughTheFullKind() {
        editionLoads();
        when(api.enabled()).thenReturn(true);
        theEditionHasAFile();
        when(api.searchFull(IMDB)).thenReturn(new OpenSubtitlesSearch(1, null, List.of(), List.of(plain(6011))));
        when(downloads.downloadsLeft(1L, 40L, SubtitleKind.FULL, IMDB)).thenReturn(3, 2);
        service.search(40L, "full");
        when(api.downloadsEnabled()).thenReturn(true);
        when(downloads.refusal(1L, 40L, SubtitleKind.FULL, IMDB, 6011)).thenReturn(Optional.empty());
        OpenSubtitlesReference reference = storedReferenceExists(6011, 400);
        when(downloads.addChosen(1L, 40L, SubtitleKind.FULL, reference, 23.976, NOW)).thenReturn(ChosenOutcome.ADDED);

        assertThat(service.download(40L, new DownloadRequest(6011L, "full")))
            .isEqualTo(new DownloadDto(40L, "full", 6011, "Taken.2008.BluRay", 2));
    }

    @Test
    void aMissingBodyKindOrFileIdIs400() {
        assertThatThrownBy(() -> service.download(40L, null)).isInstanceOf(BadRequestException.class)
            .hasMessage("The kind must be full or forced");
        assertThatThrownBy(() -> service.download(40L, new DownloadRequest(5011L, null)))
            .isInstanceOf(BadRequestException.class).hasMessage("The kind must be full or forced");
        assertThatThrownBy(() -> service.download(40L, new DownloadRequest(5011L, "both")))
            .isInstanceOf(BadRequestException.class).hasMessage("The kind must be full or forced");
        for (Long fileId : new Long[] {null, 0L, -1L}) {
            assertThatThrownBy(() -> service.download(40L, new DownloadRequest(fileId, "forced")))
                .isInstanceOf(BadRequestException.class).hasMessage("A file id is required");
        }
    }

    @Test
    void anUnknownEditionIs404OnADownload() {
        when(editions.findWithFilmById(40L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.download(40L, forced(5011))).isInstanceOf(NotFoundException.class)
            .hasMessage("No such edition");
    }

    @Test
    void downloadsThatAreNotSetUpAre503() {
        editionLoads();
        when(api.downloadsEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.download(40L, forced(5011))).isInstanceOf(ServiceUnavailableException.class)
            .hasMessage("OpenSubtitles downloads are not set up");
        verifyNoInteractions(budget, downloads);
    }

    @Test
    void aFileNoSearchOfferedIs400AndNothingIsSpent() {
        editionLoads();
        when(api.downloadsEnabled()).thenReturn(true);

        assertThatThrownBy(() -> service.download(40L, forced(5011))).isInstanceOf(BadRequestException.class)
            .hasMessage("That file was not in a recent search for this edition. Search again.");
        verifyNoInteractions(budget, downloads);
    }

    @Test
    void aFileFromAnotherEditionsSearchOrTheOtherKindsSearchIs400() {
        searchedForced(plain(5011));
        when(api.downloadsEnabled()).thenReturn(true);
        Film other = film(2L, "Other", 2010, NOW);
        other.setImdbId("tt0000002");
        Edition otherEdition = edition(other, 41L, "Original", 90 * MIN, null);
        file(otherEdition, "Other.m4v").setId(11L);
        when(editions.findWithFilmById(41L)).thenReturn(Optional.of(otherEdition));

        assertThatThrownBy(() -> service.download(41L, forced(5011))).isInstanceOf(BadRequestException.class)
            .hasMessage("That file was not in a recent search for this edition. Search again.");
        assertThatThrownBy(() -> service.download(40L, new DownloadRequest(5011L, "full")))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("That file was not in a recent search for this edition. Search again.");
        verifyNoInteractions(budget);
    }

    @Test
    void aFileFromASearchFifteenMinutesOldIsAcceptedAtTheLimitAndRefusedAMillisecondLater() {
        searchedForced(plain(5011));
        readyToDownload(5011);
        OpenSubtitlesReference reference = storedReferenceExists(5011, 12);
        when(downloads.addChosen(1L, 40L, SubtitleKind.FORCED, reference, 23.976,
            NOW.plusSeconds(15 * 60).minusMillis(1)))
            .thenReturn(ChosenOutcome.ADDED);
        when(downloads.addChosen(1L, 40L, SubtitleKind.FORCED, reference, 23.976, NOW.plusSeconds(15 * 60)))
            .thenReturn(ChosenOutcome.ADDED);
        when(downloads.downloadsLeft(1L, 40L, SubtitleKind.FORCED, IMDB)).thenReturn(2);

        clock.now = NOW.plusSeconds(15 * 60).minusMillis(1);
        assertThat(service.download(40L, forced(5011)).fileId()).isEqualTo(5011L);

        clock.now = NOW.plusSeconds(15 * 60);
        assertThat(service.download(40L, forced(5011)).fileId()).isEqualTo(5011L);

        clock.now = NOW.plusSeconds(15 * 60).plusMillis(1);
        assertThatThrownBy(() -> service.download(40L, forced(5011))).isInstanceOf(BadRequestException.class)
            .hasMessage("That file was not in a recent search for this edition. Search again.");
    }

    @Test
    void aScanHoldingTheLockIs409BeforeAnyDownloadIsSpent() {
        searchedForced(plain(5011));
        when(api.downloadsEnabled()).thenReturn(true);
        assertThat(lock.tryAcquire(LibraryLock.SCAN)).isTrue();

        assertThatThrownBy(() -> service.download(40L, forced(5011))).isInstanceOf(ConflictException.class)
            .hasMessage("A scan is running");

        verifyNoInteractions(budget);
        verify(downloads).downloadsLeft(1L, 40L, SubtitleKind.FORCED, IMDB);
        verifyNoMoreInteractions(downloads);
        assertThat(lock.holder()).isEqualTo(LibraryLock.SCAN);
    }

    @Test
    void aTimingOrCheckPassHoldingTheLockIsNotCalledAScan() {
        searchedForced(plain(5011));
        when(api.downloadsEnabled()).thenReturn(true);
        assertThat(lock.tryAcquire(LibraryLock.SUBTITLE)).isTrue();

        assertThatThrownBy(() -> service.download(40L, forced(5011))).isInstanceOf(ConflictException.class)
            .hasMessage("Subtitles are being changed or checked; try again in a moment");

        assertThat(lock.holder()).isEqualTo(LibraryLock.SUBTITLE);
    }

    @Test
    void everyReasonTheEditionCannotTakeTheFileIs409WithItsOwnTextAndNothingIsSpent() {
        searchedForced(plain(5011));
        when(api.downloadsEnabled()).thenReturn(true);
        when(downloads.refusal(1L, 40L, SubtitleKind.FORCED, IMDB, 5011L)).thenReturn(
            Optional.of(ChosenOutcome.CHANGED), Optional.of(ChosenOutcome.NOT_WANTED),
            Optional.of(ChosenOutcome.BLOCKED), Optional.of(ChosenOutcome.ALREADY_DOWNLOADED),
            Optional.of(ChosenOutcome.CAP_REACHED), Optional.of(ChosenOutcome.BUSY));
        List<String> messages = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            messages.add(refusedWith(forced(5011)));
        }

        assertThat(messages).containsExactly(
            "This film or edition changed while you were choosing. Search again.",
            "You marked this film as not needing these subtitles. Change that first.",
            "You removed that file from this edition earlier, so it is never downloaded for it again.",
            "That file is already this edition's download.",
            "This edition has already used its 3 OpenSubtitles files (removed ones count), so no more can be "
                + "downloaded for it.",
            "The library is busy; try again in a moment");
        verifyNoInteractions(budget);
        verifyNoInteractions(events);
    }

    @Test
    void aBudgetThatRefusesTheDownloadIsAQuotaErrorAndNothingIsAnnounced() {
        searchedForced(plain(5011));
        readyToDownload(5011);
        when(limits.today()).thenReturn(20);
        when(budget.take(DAY, 20)).thenReturn(false);

        assertThatThrownBy(() -> service.download(40L, forced(5011))).isInstanceOf(OpenSubtitlesQuotaException.class);

        verify(budget).take(DAY, 20);
        verifyNoMoreInteractions(budget);
        verifyNoInteractions(events);
    }

    @Test
    void anAnswerOfQuotaFromOpenSubtitlesSpendsTheDayAndIsAQuotaError() {
        searchedForced(plain(5011));
        readyToDownload(5011);
        when(limits.today()).thenReturn(20);
        when(budget.take(DAY, 20)).thenReturn(true);
        Instant reset = NOW.plusSeconds(3600);
        when(api.download(5011L)).thenThrow(new OpenSubtitlesQuotaException(reset));

        assertThatThrownBy(() -> service.download(40L, forced(5011))).isInstanceOf(OpenSubtitlesQuotaException.class);

        verify(budget).exhaust(DAY, 20, reset);
        verifyNoInteractions(events);
    }

    @Test
    void anOutageBeforeTheLinkIsGivenBackToTheBudgetAndReachesTheCaller() {
        searchedForced(plain(5011));
        readyToDownload(5011);
        when(limits.today()).thenReturn(20);
        when(budget.take(DAY, 20)).thenReturn(true);
        OpenSubtitlesUnavailableException outage = new OpenSubtitlesUnavailableException("down");
        when(api.download(5011L)).thenThrow(outage);

        assertThatThrownBy(() -> service.download(40L, forced(5011))).isSameAs(outage);

        verify(budget).refund(DAY);
        verifyNoInteractions(events);
    }

    @Test
    void aChargedDownloadThatMayBeTriedAgainReachesTheCallerWithNoRefund() {
        searchedForced(plain(5011));
        readyToDownload(5011);
        when(limits.today()).thenReturn(20);
        when(budget.take(DAY, 20)).thenReturn(true);
        OpenSubtitlesChargedException charged = new OpenSubtitlesChargedException("timeout", false);
        when(api.download(5011L)).thenThrow(charged);
        when(references.saveAndFlush(argThat(saved -> saved.getOsFileId() == 5011L)))
            .thenAnswer(call -> call.getArgument(0));

        assertThatThrownBy(() -> service.download(40L, forced(5011))).isSameAs(charged);

        verify(budget).take(DAY, 20);
        verifyNoMoreInteractions(budget);
        verifyNoInteractions(events);
    }

    @Test
    void aFileThatIsNotUsableIs422WithATextForEachReasonAndAnnouncesNothing() {
        searchedForced(plain(5011), plain(5012));
        when(api.downloadsEnabled()).thenReturn(true);
        when(limits.today()).thenReturn(20);
        for (long fileId : new long[] {5011, 5012}) {
            when(downloads.refusal(1L, 40L, SubtitleKind.FORCED, IMDB, fileId)).thenReturn(Optional.empty());
        }
        OpenSubtitlesReference unreadable = stored(5011, 0);
        unreadable.setFailedDownloads(OpenSubtitlesReference.MAX_FAILED_DOWNLOADS);
        OpenSubtitlesReference full = stored(5012, 400);
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, IMDB, 5011L)).thenReturn(Optional.of(unreadable));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, IMDB, 5012L)).thenReturn(Optional.of(full));
        when(downloads.addChosen(1L, 40L, SubtitleKind.FORCED, unreadable, 23.976, NOW))
            .thenReturn(ChosenOutcome.UNUSABLE);
        when(downloads.addChosen(1L, 40L, SubtitleKind.FORCED, full, 23.976, NOW)).thenReturn(ChosenOutcome.UNUSABLE);

        assertThatThrownBy(() -> service.download(40L, forced(5011))).isInstanceOf(UnprocessableException.class)
            .hasMessage("That subtitle file could not be read.");
        assertThatThrownBy(() -> service.download(40L, forced(5012))).isInstanceOf(UnprocessableException.class)
            .hasMessage("That file is a full subtitle, not a forced one.");
        verifyNoInteractions(events);
    }

    @Test
    void aFullPickTooShortToBeAFullSubtitleIs422() {
        editionLoads();
        when(api.enabled()).thenReturn(true);
        theEditionHasAFile();
        when(api.searchFull(IMDB)).thenReturn(new OpenSubtitlesSearch(1, null, List.of(), List.of(plain(6011))));
        when(downloads.downloadsLeft(1L, 40L, SubtitleKind.FULL, IMDB)).thenReturn(3);
        service.search(40L, "full");
        when(api.downloadsEnabled()).thenReturn(true);
        when(downloads.refusal(1L, 40L, SubtitleKind.FULL, IMDB, 6011L)).thenReturn(Optional.empty());
        OpenSubtitlesReference shortOne = storedReferenceExists(6011, 12);
        when(downloads.addChosen(1L, 40L, SubtitleKind.FULL, shortOne, 23.976, NOW)).thenReturn(ChosenOutcome.UNUSABLE);

        assertThatThrownBy(() -> service.download(40L, new DownloadRequest(6011L, "full")))
            .isInstanceOf(UnprocessableException.class).hasMessage("That file is too short to be a full subtitle.");
    }

    @Test
    void aChargedDownloadThatCanNeverBeFetchedIs422WithTheCouldNotBeReadTextTheDayIsSpentAndTheCopyIsFinal() {
        searchedForced(plain(5011));
        readyToDownload(5011);
        when(limits.today()).thenReturn(20);
        when(budget.take(DAY, 20)).thenReturn(true);
        when(api.download(5011L)).thenThrow(new OpenSubtitlesChargedException("refused", true));
        when(references.saveAndFlush(argThat(saved -> saved.getOsFileId() == 5011L)))
            .thenAnswer(call -> call.getArgument(0));
        when(downloads.addChosen(eq(1L), eq(40L), eq(SubtitleKind.FORCED),
            argThat(failed -> failed.getOsFileId() == 5011L && !failed.isUsable()), eq(23.976), eq(NOW)))
            .thenReturn(ChosenOutcome.UNUSABLE);

        assertThatThrownBy(() -> service.download(40L, forced(5011))).isInstanceOf(UnprocessableException.class)
            .hasMessage("That subtitle file could not be read.");

        // The account was charged: the budget was taken once and never given back.
        verify(budget).take(DAY, 20);
        verifyNoMoreInteractions(budget);
        ArgumentCaptor<OpenSubtitlesReference> stored = ArgumentCaptor.forClass(OpenSubtitlesReference.class);
        verify(references).saveAndFlush(stored.capture());
        assertThat(stored.getValue().isFinal()).isTrue();
        assertThat(stored.getValue().isUsable()).isFalse();
        assertThat(stored.getValue().getFailedDownloads()).isEqualTo(OpenSubtitlesReference.MAX_FAILED_DOWNLOADS);
        verifyNoInteractions(events);
    }

    @Test
    void aListenerThatFailsWhenTheDownloadIsAnnouncedDoesNotUndoTheDownload() {
        searchedForced(plain(5011));
        readyToDownload(5011);
        OpenSubtitlesReference reference = storedReferenceExists(5011, 12);
        when(downloads.addChosen(1L, 40L, SubtitleKind.FORCED, reference, 23.976, NOW)).thenReturn(ChosenOutcome.ADDED);
        when(downloads.downloadsLeft(1L, 40L, SubtitleKind.FORCED, IMDB)).thenReturn(2);
        doThrow(new IllegalStateException("listener")).when(events)
            .publishEvent((Object) new SubtitleDownloadsFinished());

        assertThat(service.download(40L, forced(5011)))
            .isEqualTo(new DownloadDto(40L, "forced", 5011, "Taken.2008.BluRay", 2));
    }

    @Test
    void theLockTakenByAScanDuringTheDownloadIs409AfterItAndAnnouncesNothing() {
        searchedForced(plain(5011));
        readyToDownload(5011);
        OpenSubtitlesReference reference = storedReferenceExists(5011, 12);
        when(downloads.addChosen(1L, 40L, SubtitleKind.FORCED, reference, 23.976, NOW)).thenAnswer(call -> {
            lock.tryAcquire(LibraryLock.SCAN);
            return ChosenOutcome.BUSY;
        });

        assertThatThrownBy(() -> service.download(40L, forced(5011))).isInstanceOf(ConflictException.class)
            .hasMessage("A scan is running");
        verifyNoInteractions(events);
    }

    @Test
    void aSecondPostWhileTheFirstIsRunningIsRefusedAtOnceSoTheEditionIsNeverDownloadedTwice() {
        searchedForced(plain(5011));
        readyToDownload(5011);
        OpenSubtitlesReference reference = storedReferenceExists(5011, 12);
        AtomicReference<String> secondOutcome = new AtomicReference<>();
        when(downloads.addChosen(1L, 40L, SubtitleKind.FORCED, reference, 23.976, NOW)).thenAnswer(call -> {
            // The first request is inside its add: a second one for the same edition and kind arrives now.
            try {
                service.download(40L, forced(5011));
            } catch (ConflictException e) {
                secondOutcome.set(e.getMessage());
            }
            return ChosenOutcome.ADDED;
        });
        when(downloads.downloadsLeft(1L, 40L, SubtitleKind.FORCED, IMDB)).thenReturn(2);

        assertThat(service.download(40L, forced(5011)).fileId()).isEqualTo(5011L);

        assertThat(secondOutcome.get()).isEqualTo("A download for this edition is already running");
        verify(downloads).addChosen(1L, 40L, SubtitleKind.FORCED, reference, 23.976, NOW);
        verify(events).publishEvent(new SubtitleDownloadsFinished());
    }

    @Test
    void onceTheFirstHasFinishedTheEditionCanBeDownloadedForAgainAndTheRepeatIsStoppedByTheCap() {
        searchedForced(plain(5011));
        when(api.downloadsEnabled()).thenReturn(true);
        OpenSubtitlesReference reference = storedReferenceExists(5011, 12);
        when(downloads.refusal(1L, 40L, SubtitleKind.FORCED, IMDB, 5011L)).thenReturn(Optional.empty(),
            Optional.of(ChosenOutcome.ALREADY_DOWNLOADED));
        when(downloads.addChosen(1L, 40L, SubtitleKind.FORCED, reference, 23.976, NOW)).thenReturn(ChosenOutcome.ADDED);
        when(downloads.downloadsLeft(1L, 40L, SubtitleKind.FORCED, IMDB)).thenReturn(2);

        assertThat(service.download(40L, forced(5011)).fileId()).isEqualTo(5011L);
        assertThatThrownBy(() -> service.download(40L, forced(5011))).isInstanceOf(ConflictException.class)
            .hasMessage("That file is already this edition's download.");
    }

    @Test
    void aFailedDownloadFreesTheEditionForTheNextTry() {
        searchedForced(plain(5011));
        readyToDownload(5011);
        when(limits.today()).thenReturn(20);
        when(budget.take(DAY, 20)).thenReturn(true);
        OpenSubtitlesUnavailableException outage = new OpenSubtitlesUnavailableException("down");
        when(api.download(5011L)).thenThrow(outage);

        assertThatThrownBy(() -> service.download(40L, forced(5011))).isSameAs(outage);
        assertThatThrownBy(() -> service.download(40L, forced(5011))).isSameAs(outage);

        verify(api, times(2)).download(5011L);
    }

    @Test
    void theBudgetIsAskedForTheWholeDailyLimitTheAccountAllows() {
        searchedForced(plain(5011));
        readyToDownload(5011);
        when(limits.today()).thenReturn(7);
        when(budget.take(DAY, 7)).thenReturn(false);

        assertThatThrownBy(() -> service.download(40L, forced(5011))).isInstanceOf(OpenSubtitlesQuotaException.class);

        verify(budget).take(DAY, 7);
    }

    @Test
    void aPickedFileRetimedAsItIsStoredIsReadAgainAndTheRewrittenRowIsWhatBecomesTheDownload() {
        searchedForced(plain(5011));
        readyToDownload(5011);
        when(limits.today()).thenReturn(20);
        when(budget.take(DAY, 20)).thenReturn(true);
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, IMDB, 5011L)).thenReturn(Optional.empty());
        when(api.download(5011L)).thenReturn(new OpenSubtitlesDownload(SrtParser.parse(
            "1\n00:00:10,000 --> 00:00:12,000\nHello\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)), null,
            null));
        when(references.saveAndFlush(argThat(saved -> saved.getOsFileId() == 5011L))).thenAnswer(call -> {
            OpenSubtitlesReference reference = call.getArgument(0);
            reference.setId(9L);
            return reference;
        });
        RetimeOnArrival arrival = org.mockito.Mockito.mock(RetimeOnArrival.class);
        when(arrival.referenceArrived(9L)).thenReturn(true);
        OpenSubtitlesReference rewritten = stored(5011, 1);
        rewritten.setId(9L);
        when(references.findById(9L)).thenReturn(Optional.of(rewritten));
        service.retimeOnArrival(arrival);
        when(downloads.addChosen(1L, 40L, SubtitleKind.FORCED, rewritten, 23.976, NOW))
            .thenReturn(ChosenOutcome.ADDED);
        when(downloads.downloadsLeft(1L, 40L, SubtitleKind.FORCED, IMDB)).thenReturn(2);

        assertThat(service.download(40L, forced(5011)).fileId()).isEqualTo(5011L);

        verify(downloads).addChosen(1L, 40L, SubtitleKind.FORCED, rewritten, 23.976, NOW);
    }
}
