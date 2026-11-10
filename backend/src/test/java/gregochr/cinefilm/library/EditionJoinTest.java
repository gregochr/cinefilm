package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.TestData;
import gregochr.cinefilm.film.AudioStream;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmPreference;
import gregochr.cinefilm.film.MatchStatus;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.MediaKind;
import gregochr.cinefilm.film.SubtitleStream;
import gregochr.cinefilm.playback.PlaybackState;
import gregochr.cinefilm.tmdb.TmdbApi;
import gregochr.cinefilm.transcode.MediaKeyframes;
import gregochr.cinefilm.transcode.MediaKeyframesRepository;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Joining files into one multi-part edition and unfolding one again, on H2 through the real repositories. */
class EditionJoinTest extends ApiTestBase {

    private static final Instant MONDAY = NOW.minus(Duration.ofDays(5));
    private static final Instant TUESDAY = NOW.minus(Duration.ofDays(4));

    @Autowired
    private EditionJoin join;

    @Autowired
    private EditionRepository editions;

    @Autowired
    private MediaFileRepository mediaFiles;

    @Autowired
    private MediaKeyframesRepository keyframes;

    @Autowired
    private EntityManager em;

    @MockitoBean
    private TmdbApi tmdb;

    /** A film with one single-file edition of the given length. */
    private Film film(String title, MatchStatus status, String path, long minutes) {
        Film film = TestData.film(null, title, 2002, NOW);
        film.setMatchStatus(status);
        TestData.file(TestData.edition(film, null, "Extended", minutes * MIN, null), path);
        return films.saveAndFlush(film);
    }

    private static Long editionId(Film film) {
        return film.getEditions().get(0).getId();
    }

    private static Long fileId(Film film) {
        return film.getEditions().get(0).getMediaFiles().get(0).getId();
    }

    private void reset() {
        em.flush();
        em.clear();
    }

    @Test
    void twoFilmsAndTwoEditionsBecomeOneFilmWithOneEditionOfTwoOrderedParts() {
        Film one = film("Towers", MatchStatus.MATCHED, "Towers - pt1.m4v", 90);
        Film two = film("Towers - pt2", MatchStatus.NEEDS_MATCH, "Towers - pt2.m4v", 110);
        Long survivor = editionId(one);
        Long absorbed = editionId(two);
        Long first = fileId(one);
        Long second = fileId(two);
        List<Long> streamIds = mediaFiles.findById(second).orElseThrow().getAudioStreams().stream()
            .map(AudioStream::getId).toList();
        keyframes.save(new MediaKeyframes(second, "0,5000", 5000, true, 10, 20, NOW, 24.0));
        reset();

        Long result = join.join(List.of(first, second));
        reset();

        assertThat(result).isEqualTo(survivor);
        assertThat(films.findAll()).extracting(Film::getId).containsExactly(one.getId());
        Edition edition = editions.findById(survivor).orElseThrow();
        assertThat(edition.originalParts()).extracting(MediaFile::getId, MediaFile::getPartNumber)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(first, 1),
                org.assertj.core.groups.Tuple.tuple(second, 2));
        assertThat(edition.getRuntimeMs()).isEqualTo(200 * MIN);
        assertThat(edition.getFilm().getMatchStatus()).isEqualTo(MatchStatus.MATCHED);
        assertThat(editions.findById(absorbed)).isEmpty();
        assertThat(mediaFiles.findById(second).orElseThrow().getAudioStreams()).extracting(AudioStream::getId)
            .containsExactlyElementsOf(streamIds);
        assertThat(keyframes.findById(second)).isPresent();
    }

    @Test
    void aMatchedFilmSurvivesEvenWhenTheEarlierPartBelongsToAnUnmatchedOne() {
        Film one = film("Towers - pt1", MatchStatus.NEEDS_MATCH, "Towers - pt1.m4v", 90);
        Film two = film("Towers", MatchStatus.MATCHED, "Towers - pt2.m4v", 110);
        Long survivor = editionId(one);
        Long first = fileId(one);
        Long second = fileId(two);
        reset();

        join.join(List.of(first, second));
        reset();

        assertThat(films.findAll()).extracting(Film::getId).containsExactly(two.getId());
        assertThat(editions.findById(survivor).orElseThrow().getFilm().getId()).isEqualTo(two.getId());
    }

    @Test
    void whenNoFilmIsMatchedTheOldestSurvives() {
        Film one = film("Towers - pt1", MatchStatus.PENDING, "Towers - pt1.m4v", 90);
        Film two = film("Towers - pt2", MatchStatus.NEEDS_MATCH, "Towers - pt2.m4v", 110);
        Long first = fileId(two);
        Long second = fileId(one);
        reset();

        join.join(List.of(first, second));
        reset();

        assertThat(films.findAll()).extracting(Film::getId).containsExactly(one.getId());
    }

    @Test
    void joiningFilesThatAreAlreadyInOneEditionJustReordersTheirParts() {
        Edition edition = twoParts(0);
        Long editionId = edition.getId();
        Long first = edition.getMediaFiles().get(0).getId();
        Long second = edition.getMediaFiles().get(1).getId();
        saveState(TestData.state(edition, 10 * MIN, false, TUESDAY, null));
        reset();

        Long result = join.join(List.of(second, first));
        reset();

        assertThat(result).isEqualTo(editionId);
        Edition after = editions.findById(editionId).orElseThrow();
        assertThat(after.originalParts()).extracting(MediaFile::getId, MediaFile::getPartNumber)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(second, 1),
                org.assertj.core.groups.Tuple.tuple(first, 2));
        assertThat(after.getRuntimeMs()).isEqualTo(200 * MIN);
        assertThat(states.findById(editionId).orElseThrow().getPositionMs()).isEqualTo(10 * MIN);
    }

    @Test
    void filesOfOneFilmJoinWithoutAnyFilmBeingMerged() {
        Film film = film("Towers", MatchStatus.MATCHED, "Towers - pt1.m4v", 90);
        Edition other = TestData.edition(film, null, "Extended", 110 * MIN, null);
        TestData.file(other, "Towers - pt2.m4v");
        films.saveAndFlush(film);
        reset();
        Film loaded = films.findById(film.getId()).orElseThrow();
        Long first = loaded.getEditions().get(0).getMediaFiles().get(0).getId();
        Long second = loaded.getEditions().get(1).getMediaFiles().get(0).getId();
        Long absorbed = loaded.getEditions().get(1).getId();
        reset();

        join.join(List.of(first, second));
        reset();

        assertThat(films.findById(film.getId()).orElseThrow().getEditions()).hasSize(1);
        assertThat(editions.findById(absorbed)).isEmpty();
    }

    @Test
    void threeFilesJoinInTheGivenOrder() {
        Film one = film("Epic", MatchStatus.MATCHED, "Epic - cd1.m4v", 60);
        Film two = film("Epic 2", MatchStatus.PENDING, "Epic - cd2.m4v", 70);
        Film three = film("Epic 3", MatchStatus.PENDING, "Epic - cd3.m4v", 80);
        Long a = fileId(one);
        Long b = fileId(two);
        Long c = fileId(three);
        reset();

        join.join(List.of(a, c, b));
        reset();

        Edition edition = editions.findById(editionId(one)).orElseThrow();
        assertThat(edition.originalParts()).extracting(MediaFile::getId).containsExactly(a, c, b);
        assertThat(edition.getRuntimeMs()).isEqualTo(210 * MIN);
    }

    @Test
    void aWatchedFirstPartAndAnUnplayedSecondResumeAtTheStartOfTheSecond() {
        Film one = film("Towers", MatchStatus.MATCHED, "Towers - pt1.m4v", 90);
        Film two = film("Towers 2", MatchStatus.PENDING, "Towers - pt2.m4v", 110);
        Long survivor = editionId(one);
        saveState(TestData.state(one.getEditions().get(0), 0, true, MONDAY, MONDAY));
        Long first = fileId(one);
        Long second = fileId(two);
        reset();

        join.join(List.of(first, second));
        reset();

        PlaybackState state = states.findById(survivor).orElseThrow();
        assertThat(state.isWatched()).isFalse();
        assertThat(state.getWatchedAt()).isNull();
        assertThat(state.getPositionMs()).isEqualTo(90 * MIN);
    }

    @Test
    void aSecondPartsResumeBecomesGlobalAndTheAbsorbedStateIsGone() {
        Film one = film("Towers", MatchStatus.MATCHED, "Towers - pt1.m4v", 90);
        Film two = film("Towers 2", MatchStatus.PENDING, "Towers - pt2.m4v", 110);
        Long survivor = editionId(one);
        Long absorbed = editionId(two);
        saveState(TestData.state(two.getEditions().get(0), 25 * MIN, false, TUESDAY, null));
        Long first = fileId(one);
        Long second = fileId(two);
        reset();

        join.join(List.of(first, second));
        reset();

        PlaybackState state = states.findById(survivor).orElseThrow();
        assertThat(state.getPositionMs()).isEqualTo(115 * MIN);
        assertThat(state.getLastPlayedAt()).isEqualTo(TUESDAY);
        assertThat(states.findById(absorbed)).isEmpty();
    }

    @Test
    void aWatchedLastPartMakesTheJoinedEditionWatched() {
        Film one = film("Towers", MatchStatus.MATCHED, "Towers - pt1.m4v", 90);
        Film two = film("Towers 2", MatchStatus.PENDING, "Towers - pt2.m4v", 110);
        Long survivor = editionId(one);
        saveState(TestData.state(two.getEditions().get(0), 0, true, TUESDAY, TUESDAY));
        Long first = fileId(one);
        Long second = fileId(two);
        reset();

        join.join(List.of(first, second));
        reset();

        PlaybackState state = states.findById(survivor).orElseThrow();
        assertThat(state.isWatched()).isTrue();
        assertThat(state.getWatchedAt()).isEqualTo(TUESDAY);
        assertThat(state.getPositionMs()).isZero();
    }

    @Test
    void noStateAnywhereLeavesNoStateRow() {
        Film one = film("Towers", MatchStatus.MATCHED, "Towers - pt1.m4v", 90);
        Film two = film("Towers 2", MatchStatus.PENDING, "Towers - pt2.m4v", 110);
        Long survivor = editionId(one);
        Long first = fileId(one);
        Long second = fileId(two);
        reset();

        join.join(List.of(first, second));
        reset();

        assertThat(states.findById(survivor)).isEmpty();
    }

    @Test
    void theCreditsMarkerOfTheEditionHoldingTheLastPartIsShiftedByItsStart() {
        Film one = film("Towers", MatchStatus.MATCHED, "Towers - pt1.m4v", 90);
        Film two = film("Towers 2", MatchStatus.PENDING, "Towers - pt2.m4v", 110);
        one.getEditions().get(0).setCreditsMarkerMs(80 * MIN);
        two.getEditions().get(0).setCreditsMarkerMs(100 * MIN);
        films.saveAndFlush(one);
        films.saveAndFlush(two);
        Long first = fileId(one);
        Long second = fileId(two);
        reset();

        join.join(List.of(first, second));
        reset();

        assertThat(editions.findById(editionId(one)).orElseThrow().getCreditsMarkerMs()).isEqualTo(190 * MIN);
    }

    @Test
    void aLastPartWithNoMarkerLeavesTheJoinedEditionWithNone() {
        Film one = film("Towers", MatchStatus.MATCHED, "Towers - pt1.m4v", 90);
        Film two = film("Towers 2", MatchStatus.PENDING, "Towers - pt2.m4v", 110);
        one.getEditions().get(0).setCreditsMarkerMs(80 * MIN);
        films.saveAndFlush(one);
        Long first = fileId(one);
        Long second = fileId(two);
        reset();

        join.join(List.of(first, second));
        reset();

        assertThat(editions.findById(editionId(one)).orElseThrow().getCreditsMarkerMs()).isNull();
    }

    @Test
    void rememberedChoicesAreRepointedAndStreamChoicesInALaterPartMoveToPartOne() {
        Film one = film("Towers", MatchStatus.MATCHED, "Towers - pt1.m4v", 90);
        Film two = film("Towers 2", MatchStatus.PENDING, "Towers - pt2.m4v", 110);
        Long survivor = editionId(one);
        Long absorbed = editionId(two);
        MediaFile secondFile = two.getEditions().get(0).getMediaFiles().get(0);
        SubtitleStream forced = new SubtitleStream();
        forced.setMediaFile(secondFile);
        forced.setIndex(1);
        forced.setLanguage("English");
        forced.setForced(true);
        secondFile.getSubtitleStreams().add(forced);
        MediaFile firstFile = one.getEditions().get(0).getMediaFiles().get(0);
        SubtitleStream firstForced = new SubtitleStream();
        firstForced.setMediaFile(firstFile);
        firstForced.setIndex(1);
        firstForced.setLanguage("English");
        firstForced.setForced(true);
        firstFile.getSubtitleStreams().add(firstForced);
        films.saveAndFlush(one);
        films.saveAndFlush(two);
        reset();
        MediaFile reloaded = mediaFiles.findById(fileId(two)).orElseThrow();
        Long stereoOfSecond = reloaded.getAudioStreams().get(1).getId();
        Long forcedOfSecond = reloaded.getSubtitleStreams().stream().filter(SubtitleStream::isForced).findFirst()
            .orElseThrow().getId();
        Long firstFileId = fileId(one);
        Long secondFileId = fileId(two);
        Long stereoOfFirst = mediaFiles.findById(firstFileId).orElseThrow().getAudioStreams().get(1).getId();
        Long forcedOfFirst = mediaFiles.findById(firstFileId).orElseThrow().getSubtitleStreams().stream()
            .filter(SubtitleStream::isForced).findFirst().orElseThrow().getId();
        FilmPreference preference = new FilmPreference(one.getId());
        preference.setDefaultEditionId(absorbed);
        preference.setLastEditionId(absorbed);
        preference.setAudioStreamId(stereoOfSecond);
        preference.setSubtitle(String.valueOf(forcedOfSecond));
        preference.setQuality("720p");
        preferences.save(preference);
        reset();

        join.join(List.of(firstFileId, secondFileId));
        reset();

        FilmPreference after = preferences.findById(one.getId()).orElseThrow();
        assertThat(after.getDefaultEditionId()).isEqualTo(survivor);
        assertThat(after.getLastEditionId()).isEqualTo(survivor);
        assertThat(after.getAudioStreamId()).isEqualTo(stereoOfFirst);
        assertThat(after.getSubtitle()).isEqualTo(String.valueOf(forcedOfFirst));
        assertThat(after.getQuality()).isEqualTo("720p");
    }

    @Test
    void aStreamChoiceWithNoMatchInPartOneIsClearedAndOneInPartOneIsKept() {
        Film one = film("Towers", MatchStatus.MATCHED, "Towers - pt1.m4v", 90);
        Film two = film("Towers 2", MatchStatus.PENDING, "Towers - pt2.m4v", 110);
        MediaFile secondFile = two.getEditions().get(0).getMediaFiles().get(0);
        AudioStream third = new AudioStream();
        third.setMediaFile(secondFile);
        third.setIndex(2);
        third.setLanguage("French");
        third.setChannels("Stereo");
        secondFile.getAudioStreams().add(third);
        SubtitleStream extra = new SubtitleStream();
        extra.setMediaFile(secondFile);
        extra.setIndex(5);
        extra.setLanguage("German");
        secondFile.getSubtitleStreams().add(extra);
        films.saveAndFlush(two);
        reset();
        MediaFile reloaded = mediaFiles.findById(fileId(two)).orElseThrow();
        Long frenchOfSecond = reloaded.getAudioStreams().stream().filter(s -> "French".equals(s.getLanguage()))
            .findFirst().orElseThrow().getId();
        Long germanOfSecond = reloaded.getSubtitleStreams().stream().filter(s -> "German".equals(s.getLanguage()))
            .findFirst().orElseThrow().getId();
        FilmPreference preference = new FilmPreference(one.getId());
        preference.setAudioStreamId(frenchOfSecond);
        preference.setSubtitle(String.valueOf(germanOfSecond));
        preferences.save(preference);
        Long first = fileId(one);
        Long second = fileId(two);
        reset();

        join.join(List.of(first, second));
        reset();

        FilmPreference after = preferences.findById(one.getId()).orElseThrow();
        assertThat(after.getAudioStreamId()).as("rank 3 has no part-1 stream, French has none").isNull();
        assertThat(after.getSubtitle()).isEqualTo("off");
    }

    @Test
    void anAudioChoiceMovesToTheLikeStreamOfPartOneEvenWhenItIsNotOfTheSameRank() {
        Film one = film("Towers", MatchStatus.MATCHED, "Towers - pt1.m4v", 90);
        Film two = film("Towers 2", MatchStatus.PENDING, "Towers - pt2.m4v", 110);
        one.getEditions().get(0).getMediaFiles().get(0).getAudioStreams().get(0).setLanguage("French");
        films.saveAndFlush(one);
        reset();
        FilmPreference preference = new FilmPreference(one.getId());
        preference.setAudioStreamId(mediaFiles.findById(fileId(two)).orElseThrow().getAudioStreams().get(0).getId());
        preferences.save(preference);
        Long first = fileId(one);
        Long second = fileId(two);
        Long englishOfFirst = mediaFiles.findById(first).orElseThrow().getAudioStreams().get(1).getId();
        reset();

        join.join(List.of(first, second));
        reset();

        assertThat(preferences.findById(one.getId()).orElseThrow().getAudioStreamId()).isEqualTo(englishOfFirst);
    }

    @Test
    void aForcedSubtitleChoiceWhosePartOneHasOnlyAnUnforcedOneOfTheLanguageIsTurnedOff() {
        Film one = film("Towers", MatchStatus.MATCHED, "Towers - pt1.m4v", 90);
        Film two = film("Towers 2", MatchStatus.PENDING, "Towers - pt2.m4v", 110);
        MediaFile secondFile = two.getEditions().get(0).getMediaFiles().get(0);
        SubtitleStream forced = new SubtitleStream();
        forced.setMediaFile(secondFile);
        forced.setIndex(1);
        forced.setLanguage("English");
        forced.setForced(true);
        secondFile.getSubtitleStreams().add(forced);
        films.saveAndFlush(two);
        reset();
        FilmPreference preference = new FilmPreference(one.getId());
        preference.setSubtitle(String.valueOf(mediaFiles.findById(fileId(two)).orElseThrow().getSubtitleStreams()
            .stream().filter(SubtitleStream::isForced).findFirst().orElseThrow().getId()));
        preferences.save(preference);
        Long first = fileId(one);
        Long second = fileId(two);
        reset();

        join.join(List.of(first, second));
        reset();

        assertThat(preferences.findById(one.getId()).orElseThrow().getSubtitle()).isEqualTo("off");
    }

    @Test
    void aStreamChoiceByRankIsUsedWhenNoLanguageMatches() {
        Film one = film("Towers", MatchStatus.MATCHED, "Towers - pt1.m4v", 90);
        Film two = film("Towers 2", MatchStatus.PENDING, "Towers - pt2.m4v", 110);
        two.getEditions().get(0).getMediaFiles().get(0).getAudioStreams().get(0).setLanguage(null);
        two.getEditions().get(0).getMediaFiles().get(0).getSubtitleStreams().get(0).setLanguage("Klingon");
        films.saveAndFlush(two);
        reset();
        MediaFile reloaded = mediaFiles.findById(fileId(two)).orElseThrow();
        FilmPreference preference = new FilmPreference(one.getId());
        preference.setAudioStreamId(reloaded.getAudioStreams().get(0).getId());
        preference.setSubtitle(String.valueOf(reloaded.getSubtitleStreams().get(0).getId()));
        preferences.save(preference);
        Long first = fileId(one);
        Long second = fileId(two);
        Long audioOfFirst = mediaFiles.findById(first).orElseThrow().getAudioStreams().get(0).getId();
        Long subtitleOfFirst = mediaFiles.findById(first).orElseThrow().getSubtitleStreams().get(0).getId();
        reset();

        join.join(List.of(first, second));
        reset();

        FilmPreference after = preferences.findById(one.getId()).orElseThrow();
        assertThat(after.getAudioStreamId()).isEqualTo(audioOfFirst);
        assertThat(after.getSubtitle()).isEqualTo(String.valueOf(subtitleOfFirst));
    }

    @Test
    void aChoiceThatAlreadyPointsIntoPartOneOrIsOffIsLeftAlone() {
        Film one = film("Towers", MatchStatus.MATCHED, "Towers - pt1.m4v", 90);
        Film two = film("Towers 2", MatchStatus.PENDING, "Towers - pt2.m4v", 110);
        MediaFile firstFile = mediaFiles.findById(fileId(one)).orElseThrow();
        FilmPreference preference = new FilmPreference(one.getId());
        preference.setAudioStreamId(firstFile.getAudioStreams().get(1).getId());
        preference.setSubtitle("off");
        preferences.save(preference);
        Long first = fileId(one);
        Long second = fileId(two);
        Long chosen = preference.getAudioStreamId();
        reset();

        join.join(List.of(first, second));
        reset();

        FilmPreference after = preferences.findById(one.getId()).orElseThrow();
        assertThat(after.getAudioStreamId()).isEqualTo(chosen);
        assertThat(after.getSubtitle()).isEqualTo("off");
    }

    @Test
    void aFilmWithNoRememberedChoicesNeedsNoRepointing() {
        Film one = film("Towers", MatchStatus.MATCHED, "Towers - pt1.m4v", 90);
        Film two = film("Towers 2", MatchStatus.PENDING, "Towers - pt2.m4v", 110);
        Long first = fileId(one);
        Long second = fileId(two);
        reset();

        join.join(List.of(first, second));
        reset();

        assertThat(preferences.findById(one.getId())).isEmpty();
    }

    @Test
    void aJoinNeedsTwoDifferentOriginalFilesWithDurations() {
        Film one = film("Towers", MatchStatus.MATCHED, "Towers - pt1.m4v", 90);
        Film two = film("Towers 2", MatchStatus.PENDING, "Towers - pt2.m4v", 110);
        Long first = fileId(one);
        Long second = fileId(two);
        MediaFile noDuration = mediaFiles.findById(second).orElseThrow();
        noDuration.setDurationMs(null);
        mediaFiles.saveAndFlush(noDuration);
        reset();

        assertThatThrownBy(() -> join.join(List.of(first))).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A join needs at least two different files");
        assertThatThrownBy(() -> join.join(List.of(first, first))).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A join needs at least two different files");
        assertThatThrownBy(() -> join.join(List.of(first, 9_999L))).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("File 9999 is not an original file");
        assertThatThrownBy(() -> join.join(List.of(first, second))).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Part 2 needs a duration above 0");
    }

    @Test
    void anAbsorbedEditionStillHoldingAnotherFileIsNeverDeleted() {
        Film one = film("Towers", MatchStatus.MATCHED, "Towers - pt1.m4v", 90);
        Film two = film("Towers 2", MatchStatus.PENDING, "Towers - pt2.m4v", 110);
        Film three = film("Towers 3", MatchStatus.PENDING, "Towers - pt3.m4v", 100);
        Edition absorbed = two.getEditions().get(0);
        MediaFile premade = TestData.file(absorbed, "Towers - pt2.720.m4v");
        premade.setKind(MediaKind.PREMADE_720);
        em.persist(premade);
        em.flush();
        Long first = fileId(one);
        Long second = fileId(two);
        Long other = fileId(three);
        Long premadeId = premade.getId();
        reset();

        assertThatThrownBy(() -> join.join(List.of(first, second))).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Edition " + absorbed.getId() + " still holds file " + premadeId
                + " that is not part of the join");
        assertThat(editions.findById(absorbed.getId())).isPresent();
        assertThat(other).isNotNull();
    }

    @Test
    void anAbsorbedEditionHoldingAnOriginalOutsideTheJoinIsNotDeletedEither() {
        Film one = film("Towers", MatchStatus.MATCHED, "Towers - pt1.m4v", 90);
        Film two = film("Towers 2", MatchStatus.PENDING, "Towers - pt2.m4v", 110);
        Edition absorbed = two.getEditions().get(0);
        MediaFile stray = TestData.file(absorbed, "Towers - extra.m4v");
        em.persist(stray);
        em.flush();
        Long first = fileId(one);
        Long second = fileId(two);
        Long strayId = stray.getId();
        reset();

        assertThatThrownBy(() -> join.join(List.of(first, second))).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("still holds file " + strayId);
    }

    @Test
    void aRenditionIsNotAnOriginalFile() {
        Film one = film("Towers", MatchStatus.MATCHED, "Towers - pt1.m4v", 90);
        Film two = film("Towers 2", MatchStatus.PENDING, "Towers - pt2.m4v", 110);
        Long first = fileId(one);
        Long second = fileId(two);
        MediaFile premade = mediaFiles.findById(second).orElseThrow();
        premade.setKind(MediaKind.PREMADE_720);
        mediaFiles.saveAndFlush(premade);
        reset();

        assertThatThrownBy(() -> join.join(List.of(first, second))).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("File " + second + " is not an original file");
    }

    // ---- unfold

    /** One edition of two parts (90 and 110 minutes) with an optional state. */
    private Edition twoParts(long marker) {
        Film film = TestData.film(null, "Towers", 2002, NOW);
        Edition edition = TestData.edition(film, null, "Extended", 90 * MIN, null);
        TestData.file(edition, "Towers - pt1.m4v");
        MediaFile second = TestData.file(edition, "Towers - pt2.m4v");
        second.setDurationMs(110 * MIN);
        second.setPartNumber(2);
        edition.setRuntimeMs(200 * MIN);
        if (marker > 0) {
            edition.setCreditsMarkerMs(marker);
        }
        films.saveAndFlush(film);
        return edition;
    }

    @Test
    void unfoldingGivesEveryGroupItsOwnEditionOfTheSameFilmAndSetsRuntimesAndPartNumbers() {
        Edition edition = twoParts(190 * MIN);
        Long editionId = edition.getId();
        Long first = edition.getMediaFiles().get(0).getId();
        Long second = edition.getMediaFiles().get(1).getId();
        Long film = edition.getFilm().getId();
        reset();

        List<Long> ids = join.unfold(editionId, List.of(List.of(first), List.of(second)));
        reset();

        assertThat(ids).hasSize(2).startsWith(editionId);
        Edition kept = editions.findById(ids.get(0)).orElseThrow();
        Edition created = editions.findById(ids.get(1)).orElseThrow();
        assertThat(kept.originalParts()).extracting(MediaFile::getId).containsExactly(first);
        assertThat(created.originalParts()).extracting(MediaFile::getId, MediaFile::getPartNumber)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(second, 1));
        assertThat(kept.getRuntimeMs()).isEqualTo(90 * MIN);
        assertThat(created.getRuntimeMs()).isEqualTo(110 * MIN);
        assertThat(created.getFilm().getId()).isEqualTo(film);
        assertThat(created.getName()).isEqualTo("Extended");
        assertThat(kept.getCreditsMarkerMs()).isNull();
        assertThat(created.getCreditsMarkerMs()).isEqualTo(100 * MIN);
    }

    @Test
    void unfoldingMovesTheResumePositionToTheEditionHoldingItAndDropsTheOriginalsState() {
        Edition edition = twoParts(0);
        Long editionId = edition.getId();
        Long first = edition.getMediaFiles().get(0).getId();
        Long second = edition.getMediaFiles().get(1).getId();
        saveState(TestData.state(edition, 100 * MIN, false, TUESDAY, null));
        reset();

        List<Long> ids = join.unfold(editionId, List.of(List.of(first), List.of(second)));
        reset();

        assertThat(states.findById(ids.get(0))).isEmpty();
        assertThat(states.findById(ids.get(1)).orElseThrow().getPositionMs()).isEqualTo(10 * MIN);
    }

    @Test
    void unfoldingAWatchedEditionWatchesTheOneHoldingTheLastPart() {
        Edition edition = twoParts(0);
        Long editionId = edition.getId();
        Long first = edition.getMediaFiles().get(0).getId();
        Long second = edition.getMediaFiles().get(1).getId();
        saveState(TestData.state(edition, 0, true, TUESDAY, TUESDAY));
        reset();

        List<Long> ids = join.unfold(editionId, List.of(List.of(first), List.of(second)));
        reset();

        assertThat(states.findById(ids.get(0))).isEmpty();
        assertThat(states.findById(ids.get(1)).orElseThrow().isWatched()).isTrue();
    }

    @Test
    void unfoldingWhereTheOriginalKeepsTheStateUpdatesItInPlace() {
        Edition edition = twoParts(0);
        Long editionId = edition.getId();
        Long first = edition.getMediaFiles().get(0).getId();
        Long second = edition.getMediaFiles().get(1).getId();
        saveState(TestData.state(edition, 30 * MIN, false, TUESDAY, null));
        reset();

        join.unfold(editionId, List.of(List.of(first), List.of(second)));
        reset();

        assertThat(states.findById(editionId).orElseThrow().getPositionMs()).isEqualTo(30 * MIN);
    }

    @Test
    void unfoldingNeedsAPartitionOfTheEditionsFilesInAtLeastTwoGroups() {
        Edition edition = twoParts(0);
        Long editionId = edition.getId();
        Long first = edition.getMediaFiles().get(0).getId();
        Long second = edition.getMediaFiles().get(1).getId();
        reset();

        assertThatThrownBy(() -> join.unfold(editionId, List.of(List.of(first, second))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> join.unfold(editionId, List.of(List.of(first), List.of(first))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> join.unfold(editionId, List.of(List.of(first), List.of(9_999L))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("File 9999 is not one of the edition's parts, or repeats");
        assertThatThrownBy(() -> join.unfold(editionId, List.of(List.of(first), List.of())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The groups must split all of the edition's files, at least two ways");
        assertThatThrownBy(() -> join.unfold(editionId, List.of(List.of(first, second), List.of())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The groups must split all of the edition's files, at least two ways");
    }
}
