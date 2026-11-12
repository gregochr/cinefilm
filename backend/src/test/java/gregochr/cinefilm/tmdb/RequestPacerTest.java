package gregochr.cinefilm.tmdb;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RequestPacerTest {

    private final AtomicLong nanos = new AtomicLong(5_000_000_000L);
    private final List<Duration> sleeps = new ArrayList<>();
    private final RequestPacer pacer = new RequestPacer(nanos::get, sleeps::add);

    @Test
    void theIntervalIsFiftyMilliseconds() {
        assertThat(RequestPacer.INTERVAL).isEqualTo(Duration.ofMillis(50));
    }

    @Test
    void theFirstRequestGoesAtOnce() throws InterruptedException {
        pacer.await();

        assertThat(sleeps).isEmpty();
    }

    @Test
    void aRequestRightAfterAnotherWaitsTheFullInterval() throws InterruptedException {
        pacer.await();
        pacer.await();

        assertThat(sleeps).containsExactly(Duration.ofMillis(50));
    }

    @Test
    void aRequestPartWayThroughTheIntervalWaitsOnlyTheRemainder() throws InterruptedException {
        pacer.await();
        nanos.addAndGet(Duration.ofMillis(20).toNanos());
        pacer.await();

        assertThat(sleeps).containsExactly(Duration.ofMillis(30));
    }

    @Test
    void aRequestExactlyOneIntervalLaterDoesNotWait() throws InterruptedException {
        pacer.await();
        nanos.addAndGet(Duration.ofMillis(50).toNanos());
        pacer.await();

        assertThat(sleeps).isEmpty();
    }

    @Test
    void slotsAreReservedBackToBackSoABurstIsSpreadOut() throws InterruptedException {
        pacer.await();
        pacer.await();
        pacer.await();

        assertThat(sleeps).containsExactly(Duration.ofMillis(50), Duration.ofMillis(100));
    }

    @Test
    void aPacerWithItsOwnIntervalSpacesRequestsByIt() throws InterruptedException {
        RequestPacer slower = new RequestPacer(nanos::get, sleeps::add, Duration.ofMillis(250));

        slower.await();
        slower.await();
        nanos.addAndGet(Duration.ofMillis(100).toNanos());
        slower.await();

        assertThat(sleeps).containsExactly(Duration.ofMillis(250), Duration.ofMillis(400));
    }
}
