package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.ScanFinished;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class IndexJobTest {

    private final MediaFileRepository files = mock(MediaFileRepository.class);
    private final MediaKeyframesRepository rows = mock(MediaKeyframesRepository.class);
    private final KeyframeIndexService service = mock(KeyframeIndexService.class);
    private final List<Runnable> queued = new ArrayList<>();
    private final IndexJob job = new IndexJob(files, rows, service, queued::add, true);

    private static MediaFile media(long id, String path) {
        MediaFile file = new MediaFile();
        file.setId(id);
        file.setPath(path);
        return file;
    }

    @Test
    void aRunIndexesEveryPresentPlayableOriginalInPathOrder() {
        MediaFile a = media(1, "a.mp4");
        MediaFile b = media(2, "b.mp4");
        when(files.findOriginals(Presence.PRESENT, Playability.PLAYABLE)).thenReturn(List.of(a, b));

        job.run();

        InOrder order = inOrder(service);
        order.verify(service).ensureIndexed(a);
        order.verify(service).ensureIndexed(b);
    }

    @Test
    void aFileThatFailsIsSkippedAndTheRestAreStillIndexed() {
        MediaFile bad = media(1, "bad.mp4");
        MediaFile good = media(2, "good.mp4");
        when(files.findOriginals(Presence.PRESENT, Playability.PLAYABLE)).thenReturn(List.of(bad, good));
        when(service.ensureIndexed(bad)).thenThrow(new KeyframeIndexException("Not an MP4 file"));

        job.run();

        verify(service).ensureIndexed(good);
    }

    @Test
    void theFinishedLineCountsTheFilesAndTheFailures() {
        MediaFile bad = media(1, "bad.mp4");
        MediaFile good = media(2, "good.mp4");
        MediaFile worse = media(3, "worse.mp4");
        when(files.findOriginals(Presence.PRESENT, Playability.PLAYABLE)).thenReturn(List.of(bad, good, worse));
        when(service.ensureIndexed(bad)).thenThrow(new KeyframeIndexException("Not an MP4 file"));
        when(service.ensureIndexed(worse)).thenThrow(new KeyframeIndexException("Not an MP4 file"));
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(IndexJob.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            job.run();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
            .contains("Keyframe index pass finished: 3 files, 2 failed");
    }

    @Test
    void startRunsOnTheExecutorAndOnlyOneRunAtATime() {
        when(files.findOriginals(Presence.PRESENT, Playability.PLAYABLE)).thenReturn(List.of());

        assertThat(job.start()).isTrue();
        assertThat(job.isRunning()).isTrue();
        assertThat(job.start()).isFalse();
        assertThat(queued).hasSize(1);

        queued.get(0).run();

        assertThat(job.isRunning()).isFalse();
        assertThat(job.start()).isTrue();
        assertThat(queued).hasSize(2);
    }

    @Test
    void aRunThatThrowsStillReleasesTheJob() {
        when(files.findOriginals(Presence.PRESENT, Playability.PLAYABLE)).thenThrow(new IllegalStateException("db"));
        job.start();

        assertThatThrownBy(() -> queued.get(0).run()).isInstanceOf(IllegalStateException.class);

        assertThat(job.isRunning()).isFalse();
    }

    @Test
    void anExecutorThatRefusesDoesNotLeaveTheJobStuckRunning() {
        IndexJob refusing = new IndexJob(files, rows, service, command -> {
            throw new RejectedExecutionException("shutting down");
        }, true);

        assertThatThrownBy(refusing::start).isInstanceOf(RejectedExecutionException.class);

        assertThat(refusing.isRunning()).isFalse();
    }

    @Test
    void aFinishedScanStartsTheJob() {
        job.onScanFinished(new ScanFinished("nightly"));

        assertThat(queued).hasSize(1);
    }

    @Test
    void startupStartsTheJobOnlyWhenEnabled() {
        job.onStartup();
        IndexJob disabled = new IndexJob(files, rows, service, queued::add, false);
        disabled.onStartup();

        assertThat(queued).hasSize(1);
    }

    @Test
    void progressIsTheIndexedCountOverThePresentPlayableTotal() {
        when(rows.countIndexedPlayable()).thenReturn(12L);
        when(files.countPresent(Playability.PLAYABLE)).thenReturn(40L);

        assertThat(job.progress()).isEqualTo(new IndexJob.Progress(12, 40));
    }
}
