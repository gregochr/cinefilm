package gregochr.cinefilm.credits;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.credits.CreditsAutoTestData.crawl;
import static gregochr.cinefilm.credits.CreditsAutoTestData.denyFile;
import static gregochr.cinefilm.credits.CreditsAutoTestData.tmdbOn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.credits.CreditsAdminDtos.AutoCandidate;
import gregochr.cinefilm.credits.CreditsApply.AutoKind;
import gregochr.cinefilm.credits.learn.Side;
import gregochr.cinefilm.film.CreditsMarkerSource;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.EditionRepository.EditionFilmRow;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.plex.PlexCreditsMarker;
import gregochr.cinefilm.plex.PlexCreditsMarkerRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The candidates list over mocked collaborators: step 3 as if the switch were on and the source null, Plex still
 * winning, the pass's own editions and the list in force.
 */
@ExtendWith(MockitoExtension.class)
class CreditsAutoCandidatesTest {

    private static final long FILE_BYTES = 4_000_000_000L;
    private static final String FINGERPRINT = FILE_BYTES + ":" + NOW.toEpochMilli();
    private static final long DEV = tmdbOn(Side.DEV, 1);

    @Mock
    private EditionRepository editions;
    @Mock
    private PlexCreditsMarkerRepository markers;
    @Mock
    private FilmRepository films;
    @Mock
    private CreditsDetectionRepository detections;
    @Mock
    private CreditsDenyFileReader denyReader;
    @Mock
    private PlatformTransactionManager transactions;

    private CreditsDenyListKeeper keeper;

    @BeforeEach
    void setUp() {
        keeper = new CreditsDenyListKeeper(denyReader);
    }

    private CreditsAutoCandidates service(boolean enabled, boolean cards) {
        return new CreditsAutoCandidates(editions, markers, keeper,
            new CreditsAutoReader(editions, films, detections, enabled, cards), transactions);
    }

    /** Puts a good deny file in force, as a pass would. */
    private void inForce(String... entries) {
        when(denyReader.read()).thenReturn(Optional.of(denyFile(entries)));
        keeper.refresh();
    }

    /** A film (id = n, a development-side TMDB id) with a 100-minute edition (id = n) of one file (id = n). */
    private static Edition seeded(long n, CreditsMarkerSource source) {
        Edition edition = edition(film(n, "Film " + n, 2000, NOW), n, "Original", 100 * MIN, null);
        edition.setCreditsMarkerSource(source);
        MediaFile file = file(edition, "Dir/Film " + n + ".m4v");
        file.setId(n);
        file.setSizeBytes(FILE_BYTES);
        file.setModifiedAt(NOW);
        return edition;
    }

    private static CreditsDetection card(long fileId, long offsetMs) {
        CreditsDetection row = crawl(fileId, FINGERPRINT, offsetMs);
        row.setReasons(CreditsFinder.CARD_OPENING + "01:23:20");
        return row;
    }

    private static PlexCreditsMarker plexRow(long fileId, String fingerprint, long startMs) {
        PlexCreditsMarker row = new PlexCreditsMarker();
        row.setMediaFileId(fileId);
        row.setFingerprint(fingerprint);
        row.setStartMs(startMs);
        row.setImportedAt(NOW);
        return row;
    }

    /** Stubs the pass's reads for one batch of these editions, whose detection rows are given. */
    private void batch(List<Edition> batch, List<CreditsDetection> rows, List<PlexCreditsMarker> plex) {
        List<Long> ids = batch.stream().map(Edition::getId).toList();
        when(editions.findWithFilesByIdIn(ids)).thenReturn(batch);
        when(markers.findAllById(ids)).thenReturn(plex);
        when(detections.findAllById(ids)).thenReturn(rows);
        when(editions.findFilmRowsByIdIn(ids)).thenReturn(ids.stream()
            .map(id -> new EditionFilmRow(id, id, DEV, "Film " + id, 2000, null, null)).toList());
    }

    private void one(Edition edition, List<CreditsDetection> rows, List<PlexCreditsMarker> plex) {
        when(editions.findAllIds()).thenReturn(List.of(edition.getId()));
        batch(List.of(edition), rows, plex);
    }

    @Test
    void aCrawlCandidateCarriesTheFileTheOffsetTheEditionTimesAndTheKind() {
        inForce("file 999");
        Edition edition = seeded(7, null);
        one(edition, List.of(crawl(7, FINGERPRINT, 5_000_000)), List.of());

        assertThat(service(false, true).candidates()).containsExactly(new AutoCandidate(7, 7, 7, "Dir/Film 7.m4v",
            5_000_000, AutoKind.CRAWL, 5_000_000, 5_020_000, false));
    }

    @Test
    void aCardCandidateIsOfKindCardWhileCardsAreOnAndDroppedWhenTheyAreOff() {
        inForce("file 999");
        Edition edition = seeded(7, null);
        one(edition, List.of(card(7, 5_000_000)), List.of());

        assertThat(service(true, true).candidates()).extracting(AutoCandidate::kind).containsExactly(AutoKind.CARD);
        assertThat(service(true, false).candidates()).isEmpty();
    }

    @Test
    void anOwnerEditionIsACandidateFlaggedOwnerEvenWhileItsMarkerIsSet() {
        inForce("file 999");
        Edition edition = seeded(7, CreditsMarkerSource.OWNER);
        edition.setCreditsMarkerMs(4_000_000L);
        one(edition, List.of(crawl(7, FINGERPRINT, 5_000_000)), List.of());

        assertThat(service(true, true).candidates()).extracting(AutoCandidate::owner).containsExactly(true);
    }

    @Test
    void aPlexEditionIsNotACandidateAndIsNotFlaggedOwnerBecausePlexWinsInThePass() {
        inForce("file 999");
        Edition edition = seeded(7, CreditsMarkerSource.PLEX);
        edition.setCreditsMarkerMs(4_000_000L);
        one(edition, List.of(crawl(7, FINGERPRINT, 5_000_000)), List.of(plexRow(7, FINGERPRINT, 4_000_000)));

        assertThat(service(true, true).candidates()).isEmpty();
    }

    @Test
    void anOwnerEditionWithAUsablePlexRowIsNotACandidateEither() {
        inForce("file 999");
        Edition edition = seeded(7, CreditsMarkerSource.OWNER);
        one(edition, List.of(crawl(7, FINGERPRINT, 5_000_000)), List.of(plexRow(7, FINGERPRINT, 4_000_000)));

        assertThat(service(true, true).candidates()).isEmpty();
    }

    @Test
    void anEditionWhosePlexRowIsStaleIsStillACandidateNotFlaggedOwner() {
        inForce("file 999");
        Edition edition = seeded(7, CreditsMarkerSource.PLEX);
        edition.setCreditsMarkerMs(4_000_000L);
        one(edition, List.of(crawl(7, FINGERPRINT, 5_000_000)), List.of(plexRow(7, "1:1", 4_000_000)));

        assertThat(service(true, true).candidates()).extracting(AutoCandidate::owner).containsExactly(false);
    }

    @Test
    void aDeniedEditionIsLeftOutByTheSharedRule() {
        inForce("edition 7");
        Edition edition = seeded(7, null);
        one(edition, List.of(crawl(7, FINGERPRINT, 5_000_000)), List.of());

        assertThat(service(true, true).candidates()).isEmpty();
    }

    @Test
    void aHiddenEditionIsAnEditionOfThePassSoItIsACandidate() {
        inForce("file 999");
        Edition edition = seeded(7, null);
        edition.originalParts().get(0).setPresence(Presence.MISSING);
        one(edition, List.of(crawl(7, FINGERPRINT, 5_000_000)), List.of());

        assertThat(service(true, true).candidates()).extracting(AutoCandidate::editionId).containsExactly(7L);
    }

    @Test
    void anEditionWithNoFileAndOneWhoseFilmRowIsGoneAreNotCandidates() {
        inForce("file 999");
        Edition bare = new Edition();
        bare.setId(8L);
        Edition orphan = seeded(9, null);
        when(editions.findAllIds()).thenReturn(List.of(8L, 9L));
        when(editions.findWithFilesByIdIn(List.of(8L, 9L))).thenReturn(List.of(bare, orphan));
        when(markers.findAllById(List.of(9L))).thenReturn(List.of());
        when(detections.findAllById(List.of(9L))).thenReturn(List.of(crawl(9, FINGERPRINT, 5_000_000)));
        when(editions.findFilmRowsByIdIn(List.of(8L, 9L))).thenReturn(List.of(
            new EditionFilmRow(8L, 108L, DEV, "Film", 2000, null, null)));

        assertThat(service(true, true).candidates()).isEmpty();
    }

    @Test
    void noListInForceIsAConflictAndNeitherTheFileNorTheLibraryIsRead() {
        assertThatThrownBy(() -> service(true, true).candidates()).isInstanceOf(ConflictException.class)
            .hasMessage("No deny list");
        verify(denyReader, times(0)).read();
    }

    @Test
    void anOlderCopyInForceStillAnswers() {
        when(denyReader.read()).thenReturn(Optional.of(denyFile("edition 8")), Optional.empty());
        keeper.refresh();
        keeper.refresh();
        Edition edition = seeded(7, null);
        one(edition, List.of(crawl(7, FINGERPRINT, 5_000_000)), List.of());

        assertThat(service(true, true).candidates()).hasSize(1);
    }

    @Test
    void theFileReplacedWithNoPassRunIsStillAnsweredFromTheKeptCopyWithoutReadingTheFile() {
        inForce("file 999");
        Edition edition = seeded(7, null);
        one(edition, List.of(crawl(7, FINGERPRINT, 5_000_000)), List.of());

        assertThat(service(true, true).candidates()).hasSize(1);
        verify(denyReader, times(1)).read();
    }

    @Test
    void afterAStartUpPassWithTheSwitchOffTheListIsInForceAndItAnswers() {
        inForce("film 999");
        when(films.findUnitRowsByIdIn(List.of(999L))).thenReturn(List.of());
        Edition edition = seeded(7, null);
        one(edition, List.of(crawl(7, FINGERPRINT, 5_000_000)), List.of());

        assertThat(service(false, true).candidates()).hasSize(1);
    }

    @Test
    void theEditionsAreReadInBatchesOfOneHundredAndAnsweredInIdOrder() {
        inForce("file 999");
        List<Edition> all = LongStream.rangeClosed(1, 101).mapToObj(n -> seeded(n, null)).toList();
        when(editions.findAllIds()).thenReturn(all.stream().map(Edition::getId).toList());
        List<Edition> first = new ArrayList<>(all.subList(0, 100));
        Collections.reverse(first);
        List<CreditsDetection> firstRows = first.stream().map(e -> crawl(e.getId(), FINGERPRINT, 5_000_000)).toList();
        List<Long> firstIds = all.subList(0, 100).stream().map(Edition::getId).toList();
        when(editions.findWithFilesByIdIn(firstIds)).thenReturn(first);
        List<Long> loadedOrder = first.stream().map(Edition::getId).toList();
        when(markers.findAllById(loadedOrder)).thenReturn(List.of());
        when(detections.findAllById(loadedOrder)).thenReturn(firstRows);
        when(editions.findFilmRowsByIdIn(loadedOrder)).thenReturn(firstIds.stream()
            .map(id -> new EditionFilmRow(id, id, DEV, "Film " + id, 2000, null, null)).toList());
        batch(List.of(all.get(100)), List.of(crawl(101, FINGERPRINT, 5_000_000)), List.of());

        List<AutoCandidate> found = service(true, true).candidates();

        assertThat(found).extracting(AutoCandidate::editionId)
            .containsExactlyElementsOf(LongStream.rangeClosed(1, 101).boxed().toList());
    }
}
