package gregochr.cinefilm.plex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.plex.ArtworkResizer.Probe;
import gregochr.cinefilm.plex.ResizedArtwork.Outcome;
import gregochr.cinefilm.plex.ResizedArtwork.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ResizedArtworkTest {

    private static final int STORED_SIZE = 1000;
    private static final int RESIZED_SIZE = 300;

    @TempDir
    Path base;

    private Path cache;
    private Path stored;
    private StubResizer resizer;
    private ExecutorService executor;

    /** Decides the probe by name and counts the resizes; the hook runs inside every resize. */
    private static class StubResizer extends ArtworkResizer {
        final AtomicInteger resizes = new AtomicInteger();
        volatile Function<String, Probe> probes = name -> Probe.RESIZABLE;
        volatile Optional<byte[]> output = Optional.of(new byte[RESIZED_SIZE]);
        volatile Runnable inResize = () -> { };

        @Override
        public Probe probe(Path file, String name, int targetWidth) {
            return probes.apply(name);
        }

        @Override
        public Optional<byte[]> resize(Path file, String name, int targetWidth) {
            resizes.incrementAndGet();
            inResize.run();
            return output;
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        cache = base.resolve("cache");
        stored = Files.write(base.resolve("stored.jpg"), new byte[STORED_SIZE]);
        resizer = new StubResizer();
        executor = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private ResizedArtwork service(long cap) {
        return new ResizedArtwork(resizer, cache.toString(), cap, 2, Duration.ofMillis(50));
    }

    private Path copy(String name) {
        return cache.resolve("art").resolve("342").resolve(name);
    }

    private Path seed(String name, int size, Instant modified) throws IOException {
        Path file = copy(name);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[size]);
        Files.setLastModifiedTime(file, FileTime.from(modified));
        return file;
    }

    @Test
    void aMissWritesTheCopyAndASecondCallReadsItWithoutResizing() throws IOException {
        ResizedArtwork service = service(ResizedArtwork.MAX_BYTES);

        Result first = service.resized("a.jpg", stored, 342);
        Result second = service.resized("a.jpg", stored, 342);

        assertThat(first.outcome()).isEqualTo(Outcome.RESIZED);
        assertThat(first.bytes()).hasSize(RESIZED_SIZE);
        assertThat(copy("a.jpg")).hasSize(RESIZED_SIZE);
        assertThat(second.outcome()).isEqualTo(Outcome.RESIZED);
        assertThat(second.bytes()).hasSize(RESIZED_SIZE);
        assertThat(resizer.resizes).hasValue(1);
        try (var files = Files.list(copy("a.jpg").getParent())) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("a.jpg");
        }
    }

    @Test
    void eachWidthHasItsOwnFolder() {
        ResizedArtwork service = service(ResizedArtwork.MAX_BYTES);

        service.resized("a.jpg", stored, 342);
        service.resized("a.jpg", stored, 780);

        assertThat(copy("a.jpg")).exists();
        assertThat(cache.resolve("art").resolve("780").resolve("a.jpg")).exists();
        assertThat(resizer.resizes).hasValue(2);
    }

    @Test
    void anImageNotWiderThanTheWidthIsServedAsStoredWithoutASlotOrACopy() {
        resizer.probes = name -> Probe.NOT_WIDER;

        Result result = service(ResizedArtwork.MAX_BYTES).resized("a.jpg", stored, 342);

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_WIDER);
        assertThat(resizer.resizes).hasValue(0);
        assertThat(copy("a.jpg")).doesNotExist();
    }

    @Test
    void aCopyNotSmallerThanTheStoredFileIsNotCached() {
        resizer.output = Optional.of(new byte[STORED_SIZE]);

        Result result = service(ResizedArtwork.MAX_BYTES).resized("a.jpg", stored, 342);

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_SMALLER);
        assertThat(copy("a.jpg")).doesNotExist();
    }

    @Test
    void aCopyOneByteSmallerIsServedAndCached() {
        resizer.output = Optional.of(new byte[STORED_SIZE - 1]);

        Result result = service(ResizedArtwork.MAX_BYTES).resized("a.jpg", stored, 342);

        assertThat(result.outcome()).isEqualTo(Outcome.RESIZED);
        assertThat(copy("a.jpg")).exists();
    }

    @Test
    void anImageTheHeaderRejectsIsUnreadableWithoutASlot() {
        resizer.probes = name -> Probe.CANNOT_RESIZE;

        Result result = service(ResizedArtwork.MAX_BYTES).resized("a.jpg", stored, 342);

        assertThat(result.outcome()).isEqualTo(Outcome.UNREADABLE);
        assertThat(resizer.resizes).hasValue(0);
    }

    @Test
    void anImageThatFailsToDecodeIsUnreadableAndNothingIsCached() {
        resizer.output = Optional.empty();

        Result result = service(ResizedArtwork.MAX_BYTES).resized("a.jpg", stored, 342);

        assertThat(result.outcome()).isEqualTo(Outcome.UNREADABLE);
        assertThat(copy("a.jpg")).doesNotExist();
    }

    @Test
    void aStoredFileThatWentAwayIsUnreadable() throws IOException {
        Files.delete(stored);

        Result result = service(ResizedArtwork.MAX_BYTES).resized("a.jpg", stored, 342);

        assertThat(result.outcome()).isEqualTo(Outcome.UNREADABLE);
    }

    @Test
    void overTheCapThePruneRemovesTheOldestDownToNinetyPercentAndKeepsTheNewestFile() throws IOException {
        Instant now = Instant.now();
        Path oldest = seed("oldest.jpg", 400, now.minusSeconds(300));
        Path middle = seed("middle.jpg", 400, now.minusSeconds(200));
        Path newer = seed("newer.jpg", 100, now.minusSeconds(100));

        // 900 bytes there + 300 written = 1200 > 1000; down to 900 means the oldest (400) goes: 800 left.
        service(1000).resized("a.jpg", stored, 342);

        assertThat(oldest).doesNotExist();
        assertThat(middle).exists();
        assertThat(newer).exists();
        assertThat(copy("a.jpg")).exists();
    }

    @Test
    void thePruneKeepsRemovingUntilItIsAtNinetyPercentButNeverTheFileJustWritten() throws IOException {
        Instant now = Instant.now();
        Path a = seed("a1.jpg", 400, now.minusSeconds(300));
        Path b = seed("b1.jpg", 400, now.minusSeconds(200));

        // The new copy (300) alone is over 90% of a cap of 100, yet it stays.
        service(100).resized("new.jpg", stored, 342);

        assertThat(a).doesNotExist();
        assertThat(b).doesNotExist();
        assertThat(copy("new.jpg")).exists();
    }

    @Test
    void thePruneStopsAsSoonAsTheFolderIsAtMostNinetyPercentOfTheCap() throws IOException {
        Instant now = Instant.now();
        Path a = seed("a1.jpg", 200, now.minusSeconds(300));
        Path b = seed("b1.jpg", 200, now.minusSeconds(200));
        Path c = seed("c1.jpg", 400, now.minusSeconds(100));

        // 800 + 300 = 1100 > 1000. Removing a leaves 900, which is exactly 90%: stop there.
        service(1000).resized("new.jpg", stored, 342);

        assertThat(a).doesNotExist();
        assertThat(b).exists();
        assertThat(c).exists();
    }

    @Test
    void aTotalAtExactlyTheCapDoesNotPrune() throws IOException {
        Path a = seed("a1.jpg", 700, Instant.now().minusSeconds(300));

        service(1000).resized("new.jpg", stored, 342);

        assertThat(a).exists();
    }

    @Test
    void aTemporaryFileOlderThanAnHourIsDeletedAndANewerOneKept() throws IOException {
        Path stale = seed("stale.tmp", 10, Instant.now().minus(Duration.ofHours(2)));
        Path fresh = seed("fresh.tmp", 10, Instant.now().minus(Duration.ofMinutes(5)));

        service(100).resized("a.jpg", stored, 342);

        assertThat(stale).doesNotExist();
        assertThat(fresh).exists();
    }

    private static void awaitQueued(ResizedArtwork service) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!service.pruneQueued()) {
            assertThat(System.nanoTime()).isLessThan(end);
            Thread.onSpinWait();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void aWriteThatLandsDuringAPruneRunsItsOwnPruneAfterwardsWhileTheTotalIsStillOver() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger prunes = new AtomicInteger();
        ResizedArtwork service = new ResizedArtwork(resizer, cache.toString(), 100, 2, Duration.ofMillis(50)) {
            @Override
            void prune(Path keep) {
                if (prunes.incrementAndGet() == 1) {
                    entered.countDown();
                    await(release);
                }
            }
        };

        Future<Result> first = executor.submit(() -> service.resized("a.jpg", stored, 342));
        await(entered);
        Future<Result> second = executor.submit(() -> service.resized("b.jpg", stored, 342));
        awaitQueued(service);
        release.countDown();

        assertThat(first.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.RESIZED);
        assertThat(second.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.RESIZED);
        assertThat(prunes).hasValue(2);
    }

    @Test
    void aWriteQueuedBehindAPruneThatFixedTheTotalDoesNotPrune() throws Exception {
        seed("old.jpg", 800, Instant.now().minusSeconds(300));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger prunes = new AtomicInteger();
        ResizedArtwork service = new ResizedArtwork(resizer, cache.toString(), 1000, 2, Duration.ofMillis(50)) {
            @Override
            void prune(Path keep) throws IOException {
                prunes.incrementAndGet();
                entered.countDown();
                await(release);
                super.prune(keep);
            }
        };

        Future<Result> first = executor.submit(() -> service.resized("a.jpg", stored, 342));
        await(entered);
        Future<Result> second = executor.submit(() -> service.resized("b.jpg", stored, 342));
        awaitQueued(service);
        release.countDown();

        assertThat(first.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.RESIZED);
        assertThat(second.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.RESIZED);
        assertThat(prunes).hasValue(1);
        assertThat(copy("old.jpg")).doesNotExist();
        assertThat(copy("a.jpg")).exists();
        assertThat(copy("b.jpg")).exists();
    }

    @Test
    void aWriteThatLandsDuringAPruneKeepsItsSizeInTheRunningTotal() throws Exception {
        seed("old.jpg", 800, Instant.now().minusSeconds(300));
        CountDownLatch walked = new CountDownLatch(1);
        ResizedArtwork[] holder = new ResizedArtwork[1];
        ResizedArtwork service = new ResizedArtwork(resizer, cache.toString(), 1000, 2, Duration.ofMillis(50)) {
            @Override
            void afterWalk() {
                walked.countDown();
                awaitQueued(holder[0]);
            }
        };
        holder[0] = service;

        Future<Result> first = executor.submit(() -> service.resized("a.jpg", stored, 342));
        await(walked);
        Future<Result> second = executor.submit(() -> service.resized("b.jpg", stored, 342));
        assertThat(first.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.RESIZED);
        assertThat(second.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.RESIZED);

        // old.jpg was pruned; a.jpg and b.jpg (300 each) remain, and the total still counts b.jpg.
        assertThat(copy("old.jpg")).doesNotExist();
        assertThat(service.trackedBytes()).isEqualTo(2L * RESIZED_SIZE);
    }

    @Test
    void aCopyThatAppearsBetweenTheResizeAndTheWriteIsNeitherWrittenNorCounted() throws IOException {
        ResizedArtwork service = service(ResizedArtwork.MAX_BYTES);
        service.resized("first.jpg", stored, 342);
        resizer.inResize = () -> {
            try {
                Files.write(copy("a.jpg"), new byte[50]);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };

        Result result = service.resized("a.jpg", stored, 342);

        assertThat(result.outcome()).isEqualTo(Outcome.RESIZED);
        assertThat(result.bytes()).hasSize(RESIZED_SIZE);
        assertThat(copy("a.jpg")).hasSize(50);
        assertThat(service.trackedBytes()).isEqualTo(RESIZED_SIZE);
    }

    @Test
    void aCopyThatCannotBeDeletedIsKeptAndCountedAndThePruneTakesTheNextOldest() throws IOException {
        Instant now = Instant.now();
        Path stuck = seed("stuck.jpg", 400, now.minusSeconds(300));
        Path next = seed("next.jpg", 400, now.minusSeconds(200));
        ResizedArtwork service = new ResizedArtwork(resizer, cache.toString(), 1000, 2, Duration.ofMillis(50)) {
            @Override
            boolean delete(Path file) {
                return !file.equals(stuck) && super.delete(file);
            }
        };

        service.resized("a.jpg", stored, 342);

        assertThat(stuck).exists();
        assertThat(next).doesNotExist();
        assertThat(copy("a.jpg")).exists();
        assertThat(service.trackedBytes()).isEqualTo(400 + RESIZED_SIZE);
    }

    @Test
    void theRealDeleteSaysWhetherTheFileIsGone() throws IOException {
        Path folder = Files.createDirectories(base.resolve("folder"));
        Path inside = Files.write(folder.resolve("inside"), new byte[1]);
        ResizedArtwork service = service(ResizedArtwork.MAX_BYTES);

        assertThat(service.delete(folder)).isFalse();
        assertThat(service.delete(inside)).isTrue();
        assertThat(service.delete(inside)).isTrue();
    }

    @Test
    void aStaleTemporaryFileThatCannotBeDeletedIsStillCounted() throws IOException {
        Path stale = seed("stale.tmp", 10, Instant.now().minus(Duration.ofHours(2)));
        ResizedArtwork service = new ResizedArtwork(resizer, cache.toString(), 100, 2, Duration.ofMillis(50)) {
            @Override
            boolean delete(Path file) {
                return false;
            }
        };

        service.resized("a.jpg", stored, 342);

        assertThat(stale).exists();
        assertThat(service.trackedBytes()).isEqualTo(10 + RESIZED_SIZE);
    }

    @Test
    void aPruneThatFailsIsLoggedTheBytesAreStillReturnedAndTheNextWriteTriesAgain() {
        AtomicInteger prunes = new AtomicInteger();
        ResizedArtwork service = new ResizedArtwork(resizer, cache.toString(), 100, 2, Duration.ofMillis(50)) {
            @Override
            void prune(Path keep) throws IOException {
                prunes.incrementAndGet();
                throw new IOException("disk");
            }
        };

        Result first = service.resized("a.jpg", stored, 342);
        Result second = service.resized("b.jpg", stored, 342);

        assertThat(first.outcome()).isEqualTo(Outcome.RESIZED);
        assertThat(second.outcome()).isEqualTo(Outcome.RESIZED);
        assertThat(prunes).hasValue(2);
    }

    @Test
    void aCachedFileRemovedBetweenTheCheckAndTheReadIsMadeAgain() throws IOException {
        seed("a.jpg", 50, Instant.now());
        ResizedArtwork service = new ResizedArtwork(resizer, cache.toString(), ResizedArtwork.MAX_BYTES, 2,
            Duration.ofMillis(50)) {
            @Override
            byte[] read(Path file) throws IOException {
                Files.delete(file);
                throw new NoSuchFileException(file.toString());
            }
        };

        Result result = service.resized("a.jpg", stored, 342);

        assertThat(result.outcome()).isEqualTo(Outcome.RESIZED);
        assertThat(result.bytes()).hasSize(RESIZED_SIZE);
        assertThat(resizer.resizes).hasValue(1);
        assertThat(copy("a.jpg")).hasSize(RESIZED_SIZE);
    }

    @Test
    void aCachedFileThatCannotBeReadIsAlsoAMiss() throws IOException {
        seed("a.jpg", 50, Instant.now());
        ResizedArtwork service = new ResizedArtwork(resizer, cache.toString(), ResizedArtwork.MAX_BYTES, 2,
            Duration.ofMillis(50)) {
            @Override
            byte[] read(Path file) throws IOException {
                throw new IOException("unreadable");
            }
        };

        assertThat(service.resized("a.jpg", stored, 342).bytes()).hasSize(RESIZED_SIZE);
        assertThat(resizer.resizes).hasValue(1);
    }

    @Test
    void withBothSlotsHeldARequestGetsBusyAfterTheWaitButHeaderOnlyAnswersNeedNoSlot() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        resizer.inResize = () -> {
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        resizer.probes = name -> switch (name) {
            case "wide.jpg" -> Probe.RESIZABLE;
            case "narrow.jpg" -> Probe.NOT_WIDER;
            default -> Probe.CANNOT_RESIZE;
        };
        ResizedArtwork service = service(ResizedArtwork.MAX_BYTES);
        Future<Result> one = executor.submit(() -> service.resized("wide.jpg", stored, 342));
        Future<Result> two = executor.submit(() -> service.resized("wide.jpg", stored, 500));
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

        Result busy = service.resized("wide.jpg", stored, 780);
        Result narrow = service.resized("narrow.jpg", stored, 780);
        Result huge = service.resized("huge.jpg", stored, 780);
        release.countDown();

        assertThat(busy.outcome()).isEqualTo(Outcome.BUSY);
        assertThat(narrow.outcome()).isEqualTo(Outcome.NOT_WIDER);
        assertThat(huge.outcome()).isEqualTo(Outcome.UNREADABLE);
        assertThat(one.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.RESIZED);
        assertThat(two.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.RESIZED);
        // Both slots are free again.
        assertThat(service.resized("wide.jpg", stored, 780).outcome()).isEqualTo(Outcome.RESIZED);
    }

    @Test
    void anInterruptedWaitIsBusyAndKeepsTheInterruptFlag() {
        ResizedArtwork service = service(ResizedArtwork.MAX_BYTES);
        Thread.currentThread().interrupt();

        Result result = service.resized("a.jpg", stored, 342);

        assertThat(Thread.interrupted()).isTrue();
        assertThat(result.outcome()).isEqualTo(Outcome.BUSY);
    }

    @Test
    void aFailureInsideTheResizeReleasesTheSlot() {
        ResizedArtwork service = new ResizedArtwork(resizer, cache.toString(), ResizedArtwork.MAX_BYTES, 1,
            Duration.ofMillis(50));
        resizer.inResize = () -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> service.resized("a.jpg", stored, 342)).isInstanceOf(IllegalStateException.class);
        resizer.inResize = () -> { };

        assertThat(service.resized("b.jpg", stored, 342).outcome()).isEqualTo(Outcome.RESIZED);
    }

    @Test
    void aFailedCacheWriteStillReturnsTheResizedBytes() throws IOException {
        // A regular file where the "art" folder should be makes the folder impossible to create.
        Files.createDirectories(cache);
        Files.write(cache.resolve("art"), new byte[1]);

        Result result = service(ResizedArtwork.MAX_BYTES).resized("a.jpg", stored, 342);

        assertThat(result.outcome()).isEqualTo(Outcome.RESIZED);
        assertThat(result.bytes()).hasSize(RESIZED_SIZE);
    }

    @ParameterizedTest
    @CsvSource({"342,342", "500,500", "780,780", "1280,1280"})
    void eachValidWidthStringParsesToItsConstant(String raw, int expected) {
        assertThat(ResizedArtwork.parseWidth(raw)).isEqualTo(OptionalInt.of(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "341", "343", "0342", "342.0", " 342", "342 ", "abc", "342,342", "+342",
        "-342", "0x156", "3420", "1281", "34"})
    void anythingElseIsInvalid(String raw) {
        assertThat(ResizedArtwork.parseWidth(raw)).isEmpty();
    }

    @Test
    void deletingQuietlyIgnoresNothingToDeleteAndAFailure() throws IOException {
        Path folder = Files.createDirectories(base.resolve("folder"));
        Files.write(folder.resolve("inside"), new byte[1]);

        ResizedArtwork.deleteQuietly(null);
        ResizedArtwork.deleteQuietly(base.resolve("nothing"));
        ResizedArtwork.deleteQuietly(folder);

        assertThat(folder.resolve("inside")).exists();
    }

    @Test
    void noWidthIsInvalidHere() {
        assertThat(ResizedArtwork.parseWidth(null)).isEmpty();
    }
}
