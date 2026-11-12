package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository.NeedRow;
import gregochr.cinefilm.film.FilmRepository.TmdbRef;
import gregochr.cinefilm.film.ForcedNeedChoice;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.film.SubtitleStream;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The queries behind the forced-need read and the languages backfill, on the in-memory database. */
class ForcedNeedQueriesTest extends SubtitleTestBase {

    private static final Instant LATER = NOW.plusSeconds(60);

    @Autowired
    private EntityManager entityManager;

    private SubtitleStream stream(Film film, String language, boolean forced) {
        MediaFile file = film.getEditions().get(0).originalParts().get(0);
        SubtitleStream stream = new SubtitleStream();
        stream.setMediaFile(file);
        stream.setIndex(10 + file.getSubtitleStreams().size());
        stream.setLanguage(language);
        stream.setForced(forced);
        stream.setFormat("subrip");
        file.getSubtitleStreams().add(stream);
        mediaFiles.saveAndFlush(file);
        return stream;
    }

    private Long filmWithTmdbId(String title, Long tmdbId, Instant fetchedAt) {
        Film film = filmWithParts(title, 1, 90);
        film.setTmdbId(tmdbId);
        film.setLanguagesFetchedAt(fetchedAt);
        return films.saveAndFlush(film).getId();
    }

    @Test
    void aPresentSidecarOnAFileOfTheFilmCountsForThatFilmOnly() {
        Film taken = filmWithParts("Taken", 1, 90);
        Film heat = filmWithParts("Heat", 1, 90);
        sidecar("Taken.en.forced.srt", fileId(taken, 1), false);

        assertThat(sidecars.findFilmIdsWithPresentSidecar()).containsExactly(taken.getId());
        assertThat(sidecars.countPresentForFilm(taken.getId())).isEqualTo(1);
        assertThat(sidecars.countPresentForFilm(heat.getId())).isZero();
    }

    @Test
    void aMissingSidecarAnUnmatchedOneAndOneOnAnotherFilmsFileDoNotCount() {
        Film taken = filmWithParts("Taken", 1, 90);
        Film heat = filmWithParts("Heat", 1, 90);
        sidecar("Taken.en.forced.srt", fileId(taken, 1), false).setPresence(Presence.MISSING);
        sidecar("Unmatched.en.forced.srt", null, false);
        sidecar("Heat.en.forced.srt", fileId(heat, 1), false);

        assertThat(sidecars.findFilmIdsWithPresentSidecar()).containsExactly(heat.getId());
        assertThat(sidecars.countPresentForFilm(taken.getId())).isZero();
        assertThat(sidecars.countPresentForFilm(heat.getId())).isEqualTo(1);
    }

    @Test
    void aSidecarWithNoCuesIsNoEvidence() {
        Film empty = filmWithParts("Empty", 1, 90);
        Film cued = filmWithParts("Cued", 1, 90);
        sidecar("Empty.en.forced.srt", fileId(empty, 1), false).setCueCount(0);
        sidecar("Cued.en.forced.srt", fileId(cued, 1), false);

        assertThat(sidecars.findFilmIdsWithPresentSidecar()).containsExactly(cued.getId());
        assertThat(sidecars.countPresentForFilm(empty.getId())).isZero();
        assertThat(sidecars.countPresentForFilm(cued.getId())).isEqualTo(1);
    }

    @Test
    void severalSidecarsOnOneFilmListItOnceAndAreCounted() {
        Film split = filmWithParts("Towers", 2, 60);
        sidecar("Towers - pt1.en.forced.srt", fileId(split, 1), false);
        sidecar("Towers - pt2.en.forced.srt", fileId(split, 2), false);

        assertThat(sidecars.findFilmIdsWithPresentSidecar()).containsExactly(split.getId());
        assertThat(sidecars.countPresentForFilm(split.getId())).isEqualTo(2);
    }

    @Test
    void anEmbeddedForcedTrackInEnglishOrNoLanguageCounts() {
        Film english = filmWithParts("English", 1, 90);
        Film codeEn = filmWithParts("Code en", 1, 90);
        Film undetermined = filmWithParts("Undetermined", 1, 90);
        Film unstated = filmWithParts("Unstated", 1, 90);
        Film upperCase = filmWithParts("Upper case", 1, 90);
        stream(english, "eng", true);
        stream(codeEn, "en", true);
        stream(undetermined, "und", true);
        stream(unstated, null, true);
        stream(upperCase, "ENG", true);

        assertThat(mediaFiles.findFilmIdsWithEmbeddedForced()).containsExactlyInAnyOrder(
            english.getId(), codeEn.getId(), undetermined.getId(), unstated.getId(), upperCase.getId());
        assertThat(mediaFiles.countEmbeddedForcedForFilm(english.getId())).isEqualTo(1);
    }

    @Test
    void aForcedTrackInAnotherLanguageOrAnEnglishTrackThatIsNotForcedDoesNotCount() {
        Film french = filmWithParts("French", 1, 90);
        Film plain = filmWithParts("Plain", 1, 90);
        Film none = filmWithParts("None", 1, 90);
        stream(french, "fre", true);
        stream(plain, "eng", false);

        assertThat(mediaFiles.findFilmIdsWithEmbeddedForced()).isEmpty();
        assertThat(mediaFiles.countEmbeddedForcedForFilm(french.getId())).isZero();
        assertThat(mediaFiles.countEmbeddedForcedForFilm(plain.getId())).isZero();
        assertThat(mediaFiles.countEmbeddedForcedForFilm(none.getId())).isZero();
    }

    @Test
    void aFilmWithTwoQualifyingTracksIsListedOnce() {
        Film film = filmWithParts("Two tracks", 1, 90);
        stream(film, "eng", true);
        stream(film, "und", true);

        assertThat(mediaFiles.findFilmIdsWithEmbeddedForced()).containsExactly(film.getId());
        assertThat(mediaFiles.countEmbeddedForcedForFilm(film.getId())).isEqualTo(2);
    }

    @Test
    void aMissingFilmFileCountsForNothingNeitherItsEmbeddedForcedTrackNorASidecarBesideIt() {
        Film gone = filmWithParts("Gone", 1, 90);
        Film here = filmWithParts("Here", 1, 90);
        stream(gone, "eng", true);
        stream(here, "eng", true);
        sidecar("Gone.en.forced.srt", fileId(gone, 1), false);
        MediaFile missing = gone.getEditions().get(0).originalParts().get(0);
        missing.setPresence(Presence.MISSING);
        mediaFiles.saveAndFlush(missing);

        assertThat(mediaFiles.findFilmIdsWithEmbeddedForced()).containsExactly(here.getId());
        assertThat(mediaFiles.countEmbeddedForcedForFilm(gone.getId())).isZero();
        assertThat(mediaFiles.countEmbeddedForcedForFilm(here.getId())).isEqualTo(1);
        assertThat(sidecars.findFilmIdsWithPresentSidecar()).isEmpty();
        assertThat(sidecars.countPresentForFilm(gone.getId())).isZero();
    }

    @Test
    void aFilmWithOnePresentAndOneMissingFileStillCountsThePresentOnes() {
        Film split = filmWithParts("Towers", 2, 60);
        stream(split, "eng", true);
        sidecar("Towers - pt2.en.forced.srt", fileId(split, 2), false);
        MediaFile first = split.getEditions().get(0).originalParts().get(0);
        first.setPresence(Presence.MISSING);
        mediaFiles.saveAndFlush(first);

        assertThat(mediaFiles.findFilmIdsWithEmbeddedForced()).isEmpty();
        assertThat(sidecars.findFilmIdsWithPresentSidecar()).containsExactly(split.getId());
        assertThat(sidecars.countPresentForFilm(split.getId())).isEqualTo(1);
    }

    @Test
    void theNeedRowsComeInSortTitleOrderWithTheColumnsTheNeedIsWorkedOutFrom() {
        Film zulu = filmWithParts("Zulu", 1, 90);
        Film alien = filmWithParts("Alien", 1, 90);
        alien.setTmdbId(348L);
        alien.setOriginalLanguage("en");
        alien.setSpokenLanguages("en,es");
        alien.setLanguagesFetchedAt(LATER);
        alien.setForcedNeedOwner(ForcedNeedChoice.NEEDED);
        films.saveAndFlush(alien);

        List<NeedRow> rows = films.findNeedRows();

        assertThat(rows).extracting(NeedRow::title).containsExactly("Alien", "Zulu");
        assertThat(rows.get(0)).isEqualTo(new NeedRow(alien.getId(), "Alien", 2000, 348L, "en", "en,es", LATER,
            ForcedNeedChoice.NEEDED, null, null, null, null));
        assertThat(rows.get(1)).isEqualTo(new NeedRow(zulu.getId(), "Zulu", 2000, null, null, null, null, null,
            null, null, null, null));
    }

    @Test
    void theBackfillListsOnlyFilmsWithATmdbIdAndNoStamp() {
        Long due = filmWithTmdbId("Due", 10L, null);
        Long stamped = filmWithTmdbId("Stamped", 20L, LATER);
        filmWithTmdbId("No match", null, null);

        List<TmdbRef> found = films.findWithoutLanguages();

        assertThat(found).containsExactly(new TmdbRef(due, 10L));
        assertThat(found).doesNotContain(new TmdbRef(stamped, 20L));
    }

    @Test
    void savingLanguagesWritesThemAndTheStampForTheTmdbIdTheyWereFetchedFor() {
        Long id = filmWithTmdbId("Taken", 8681L, null);

        int changed = films.saveLanguages(id, 8681L, "en", "en,fr", LATER);
        entityManager.clear();

        assertThat(changed).isEqualTo(1);
        Film reloaded = films.findById(id).orElseThrow();
        assertThat(reloaded.getOriginalLanguage()).isEqualTo("en");
        assertThat(reloaded.getSpokenLanguages()).isEqualTo("en,fr");
        assertThat(reloaded.getLanguagesFetchedAt()).isEqualTo(LATER);
        assertThat(films.findWithoutLanguages()).isEmpty();
    }

    @Test
    void aFilmRematchedMeanwhileKeepsWhatTheRematchWroteAndIsStillDue() {
        Long id = filmWithTmdbId("Taken", 8681L, null);
        // The scan, Fix match or Plex import rematched it to another TMDB id and applied that film's languages.
        Film rematched = films.findById(id).orElseThrow();
        rematched.setTmdbId(9999L);
        rematched.setOriginalLanguage("fr");
        rematched.setSpokenLanguages("fr");
        rematched.setLanguagesFetchedAt(null);
        films.saveAndFlush(rematched);

        int changed = films.saveLanguages(id, 8681L, "en", "en", LATER);
        entityManager.clear();

        assertThat(changed).isZero();
        Film reloaded = films.findById(id).orElseThrow();
        assertThat(reloaded.getTmdbId()).isEqualTo(9999L);
        assertThat(reloaded.getOriginalLanguage()).isEqualTo("fr");
        assertThat(reloaded.getSpokenLanguages()).isEqualTo("fr");
        assertThat(reloaded.getLanguagesFetchedAt()).isNull();
    }

    @Test
    void aFilmThatHasGoneChangesNoRow() {
        assertThat(films.saveLanguages(987_654L, 1L, "en", "en", LATER)).isZero();
    }
}
