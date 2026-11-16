package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.GoneException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.transcode.SessionDtos.CreateRequest;
import gregochr.cinefilm.transcode.SessionSources.AudioChoice;
import gregochr.cinefilm.transcode.SessionSources.Source;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** What the session service does about a playback Admin stopped: 410 for it, and the 404 and new sessions otherwise. */
@ExtendWith(MockitoExtension.class)
class SessionServiceStopTest {

    private static final long EDITION_ID = 40L;
    private static final String ID = "5b0f1c1e-6a52-4d5e-9a4c-0a7d3c1f2b11";
    private static final String PLAYBACK = "c1000000-0000-4000-8000-000000000001";
    private static final Path PATH = Path.of("/library/Heat.m4v");
    private static final long DURATION = 400_000;
    private static final CutList CUTS = CutList.fromKeyframes(
        IntStream.range(0, 100).mapToObj(i -> i * 4000L).toList(), DURATION);

    @Mock
    private SessionSources sources;
    @Mock
    private KeyframeIndexService indexes;
    @Mock
    private Transcoder transcoder;
    @Mock
    private SessionManager manager;
    @Mock
    private StoppedPlaybacks stopped;

    private SessionService service;

    @BeforeEach
    void setUp() {
        service = new SessionService(sources, indexes, transcoder, manager, Runnable::run, Duration.ofSeconds(8),
            stopped);
    }

    private static CreateRequest request(String playbackId) {
        return new CreateRequest(null, null, "auto", null, playbackId);
    }

    private void readyToCreate() {
        MediaFile file = new MediaFile();
        file.setId(7L);
        file.setDurationMs(DURATION);
        file.setHeight(1080);
        file.setWidth(1920);
        file.setBitrate(15_000_000L);
        file.setVideoCodec("H.264");
        file.setPixelFormat("yuv420p");
        when(sources.find(EDITION_ID)).thenReturn(new Source(EDITION_ID, "Heat", file, PATH,
            List.of(new AudioChoice(100, 0, true))));
        when(transcoder.mode()).thenReturn(EncoderMode.VAAPI);
        when(indexes.ensureIndexed(file)).thenReturn(new KeyframeIndexService.Index(CUTS, 25.0));
        when(manager.create(any())).thenAnswer(call -> new SessionView(ID, List.of(), null, null, CUTS, 1, 1));
    }

    @Test
    void creatingASessionForAStoppedPlaybackIs410AndMakesNoSession() {
        when(stopped.playbackStopped(PLAYBACK)).thenReturn(true);

        assertThatThrownBy(() -> service.create(EDITION_ID, request(PLAYBACK)))
            .isInstanceOf(GoneException.class).hasMessage("Playback was stopped from Admin");

        verifyNoInteractions(manager, sources);
    }

    @Test
    void creatingASessionForAPlaybackThatWasNotStoppedWorksAndRemembersTheViewing() {
        readyToCreate();
        when(stopped.playbackStopped(PLAYBACK)).thenReturn(false);

        service.create(EDITION_ID, request(PLAYBACK));

        ArgumentCaptor<SessionSpec> captor = ArgumentCaptor.forClass(SessionSpec.class);
        verify(manager).create(captor.capture());
        assertThat(captor.getValue().playbackId()).isEqualTo(PLAYBACK);
    }

    @Test
    void aStopThatArrivesWhileTheSessionIsBeingMadeEndsTheNewSessionAndIs410() {
        readyToCreate();
        when(stopped.playbackStopped(PLAYBACK)).thenReturn(false, true);

        assertThatThrownBy(() -> service.create(EDITION_ID, request(PLAYBACK)))
            .isInstanceOf(GoneException.class).hasMessage("Playback was stopped from Admin");

        verify(manager).end(ID);
    }

    @Test
    void creatingASessionThatNamesNoViewingNeverAsksWhetherItWasStopped() {
        readyToCreate();

        service.create(EDITION_ID, request(null));

        verifyNoInteractions(stopped);
        ArgumentCaptor<SessionSpec> captor = ArgumentCaptor.forClass(SessionSpec.class);
        verify(manager).create(captor.capture());
        assertThat(captor.getValue().playbackId()).isNull();
    }

    @Test
    void aMalformedPlaybackIdIs400() {
        assertThatThrownBy(() -> service.create(EDITION_ID, request("not-an-id")))
            .isInstanceOf(BadRequestException.class).hasMessage("Invalid playbackId");

        verifyNoInteractions(stopped, manager);
    }

    @Test
    void theMasterMediaAndSegmentRoutesAnswer410ForAStoppedSession() {
        when(stopped.sessionStopped(ID)).thenReturn(true);

        assertThatThrownBy(() -> service.master(ID)).isInstanceOf(GoneException.class);
        assertThatThrownBy(() -> service.media(ID, "720p")).isInstanceOf(GoneException.class);
        assertThatThrownBy(() -> service.segment(ID, "720p", "seg-1.ts")).isInstanceOf(GoneException.class);

        verifyNoInteractions(manager);
    }

    @Test
    void anExpiredOrUnknownSessionStillAnswersTheManagersNotFound() {
        when(stopped.sessionStopped(ID)).thenReturn(false);
        when(manager.view(ID)).thenThrow(new NotFoundException("No such session"));
        when(manager.segment(ID, "720p", 1)).thenThrow(new NotFoundException("No such session"));

        assertThatThrownBy(() -> service.master(ID)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.media(ID, "720p")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.segment(ID, "720p", "seg-1.ts")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aMalformedSessionIdIsStill404NotAskedAboutAtAll() {
        assertThatThrownBy(() -> service.master("x")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.stopFromAdmin("x")).isInstanceOf(NotFoundException.class);

        verifyNoInteractions(stopped, manager);
    }

    @Test
    void stopFromAdminRemembersTheSessionAndItsViewingThenEndsTheSession() {
        when(manager.playbackId(ID)).thenReturn(Optional.of(PLAYBACK));

        service.stopFromAdmin(ID);

        InOrder order = inOrder(stopped, manager);
        order.verify(stopped).record(ID, PLAYBACK);
        order.verify(manager).end(ID);
    }

    @Test
    void stopFromAdminOfASessionAlreadyReplacedRecordsTheGivenViewingAndEndsItsLiveSession() {
        when(manager.playbackId(ID)).thenReturn(Optional.empty());

        service.stopFromAdmin(ID, PLAYBACK);

        InOrder order = inOrder(stopped, manager);
        order.verify(stopped).record(ID, PLAYBACK);
        order.verify(manager).end(ID);
        order.verify(manager).endViewing(PLAYBACK);
    }

    @Test
    void everySessionEndedForTheViewingIsRecordedAsStoppedSoItsRequestsAre410() {
        String newer = "6b0f1c1e-6a52-4d5e-9a4c-0a7d3c1f2b11";
        String quiet = "7b0f1c1e-6a52-4d5e-9a4c-0a7d3c1f2b11";
        when(manager.playbackId(ID)).thenReturn(Optional.empty());
        when(manager.endViewing(PLAYBACK)).thenReturn(List.of(newer, quiet));

        service.stopFromAdmin(ID, PLAYBACK);

        verify(stopped).record(newer, PLAYBACK);
        verify(stopped).record(quiet, PLAYBACK);
    }

    @Test
    void stopFromAdminPrefersTheSessionsOwnViewingOverTheGivenOne() {
        String other = "c1000000-0000-4000-8000-000000000002";
        when(manager.playbackId(ID)).thenReturn(Optional.of(PLAYBACK));

        service.stopFromAdmin(ID, other);

        verify(stopped).record(ID, PLAYBACK);
        verify(manager).endViewing(PLAYBACK);
    }

    @Test
    void stopFromAdminWithAMalformedViewingIs400AndChangesNothing() {
        assertThatThrownBy(() -> service.stopFromAdmin(ID, "nope"))
            .isInstanceOf(BadRequestException.class).hasMessage("Invalid playbackId");

        verifyNoInteractions(stopped, manager);
    }

    @Test
    void stopFromAdminOfASessionThatNamedNoViewingRemembersOnlyTheSession() {
        when(manager.playbackId(ID)).thenReturn(Optional.empty());

        service.stopFromAdmin(ID);

        verify(stopped).record(ID, null);
        verify(manager).end(ID);
    }

    @Test
    void thePlayersOwnEndIsNotAStopAndRecordsNothing() {
        service.end(ID);

        verify(manager).end(ID);
        verifyNoInteractions(stopped);
    }
}
