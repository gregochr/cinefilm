package gregochr.cinefilm.playback;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.EditionTimeline;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.playback.PlaybackDtos.ProgressResponse;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Resume and watched on an edition split across two files: every position is an edition time (from the start of part
 * 1), the runtime is the length of both parts' video together, and the credits marker is in edition time too.
 * Part 1 is 100 minutes of video and part 2 is 80 minutes, so the edition is 180 minutes and 92% of it is 165m36s.
 */
@ExtendWith(MockitoExtension.class)
class PlaybackServicePartsTest {

    private static final long EDITION_ID = 80L;
    private static final long PART_1 = 100 * MIN;
    private static final long PART_2 = 80 * MIN;
    private static final long RUNTIME = PART_1 + PART_2;

    @Mock
    private EditionRepository editions;

    @Mock
    private PlaybackStateRepository states;

    @Mock
    private PreferencesService preferences;

    @Mock
    private ApplicationEventPublisher events;

    private PlaybackService service;
    private Edition split;

    @BeforeEach
    void setUp() {
        service = new PlaybackService(editions, states, preferences, CLOCK, events);
        Film film = film(7L, "The Two Towers", 2002, NOW);
        split = edition(film, EDITION_ID, "Extended Edition", 0, null);
        part("pt1.m4v", 1, 1L, PART_1 + 1_040, PART_1);
        part("pt2.m4v", 2, 2L, PART_2 + 85, PART_2);
        split.recomputeRuntime();
    }

    private void known() {
        when(editions.findById(EDITION_ID)).thenReturn(Optional.of(split));
    }

    private MediaFile part(String path, int number, long id, long durationMs, long videoDurationMs) {
        MediaFile file = file(split, path);
        file.setId(id);
        file.setPartNumber(number);
        file.setDurationMs(durationMs);
        file.setVideoDurationMs(videoDurationMs);
        return file;
    }

    private PlaybackState saved() {
        ArgumentCaptor<PlaybackState> captor = ArgumentCaptor.forClass(PlaybackState.class);
        verify(states).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void theRuntimeIsTheSumOfTheVideoOfBothPartsNotOfTheirContainers() {
        assertThat(split.getRuntimeMs()).isEqualTo(RUNTIME);
        assertThat(EditionTimeline.of(split).totalMs()).isEqualTo(RUNTIME);
    }

    @Test
    void aPositionInPartTwoIsSavedAsTheEditionTimeAndIsAResumePositionNotAReset() {
        known();
        ProgressResponse response = service.reportProgress(EDITION_ID, PART_1 + 30 * MIN);

        assertThat(response).isEqualTo(new ProgressResponse(PART_1 + 30 * MIN, false));
        assertThat(saved().getPositionMs()).isEqualTo(PART_1 + 30 * MIN);
    }

    @Test
    void theProgressReportedJustAfterTheJoinIsKeptAsEditionTime() {
        known();
        ProgressResponse response = service.reportProgress(EDITION_ID, PART_1 + 1);

        assertThat(response).isEqualTo(new ProgressResponse(PART_1 + 1, false));
    }

    @Test
    void theEditionIsWatchedAt92PercentOfTheSummedRuntimeAndNotOneMillisecondBefore() {
        known();
        long threshold = RUNTIME * 92 / 100;

        assertThat(service.reportProgress(EDITION_ID, threshold - 1).watched()).isFalse();
        assertThat(service.reportProgress(EDITION_ID, threshold).watched()).isTrue();
    }

    @Test
    void thePositionAtTheEndOfPartOneAloneIsNowhereNearWatchedEvenThoughItIsPastAPartsWorthOfTheRuntime() {
        known();
        assertThat(service.reportProgress(EDITION_ID, PART_1).watched()).isFalse();
    }

    @Test
    void aCreditsMarkerInPartTwoIsInEditionTimeAndDecidesWatched() {
        known();
        long marker = PART_1 + 70 * MIN;
        split.setCreditsMarkerMs(marker);

        assertThat(service.reportProgress(EDITION_ID, marker - 1).watched()).isFalse();
        ProgressResponse atMarker = service.reportProgress(EDITION_ID, marker);

        assertThat(atMarker).isEqualTo(new ProgressResponse(0, true));
    }
}
