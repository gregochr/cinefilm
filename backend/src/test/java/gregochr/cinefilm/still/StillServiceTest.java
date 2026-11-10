package gregochr.cinefilm.still;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.config.ServiceUnavailableException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaKind;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.library.CommandRunner;
import gregochr.cinefilm.library.LibraryRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class StillServiceTest {

    private static final long EDITION_ID = 2L;
    private static final long MIN = 60_000;
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3};

    @TempDir
    Path base;

    private Path library;
    private Path cache;
    private EditionRepository editions;
    private FakeRunner runner;
    private Edition edition;
    private MediaFile part1;

    /** Records every command and, unless told otherwise, writes a JPEG to the last argument. */
    private static final class FakeRunner implements CommandRunner {
        final List<List<String>> commands = new ArrayList<>();
        Result result = new Result(0, "", false);
        boolean writes = true;
        IOException failure;

        @Override
        public synchronized Result run(List<String> command, Duration timeout) throws IOException {
            commands.add(command);
            if (failure != null) {
                throw failure;
            }
            if (writes) {
                Files.write(Path.of(command.get(command.size() - 1)), JPEG);
            }
            return result;
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        library = Files.createDirectory(base.resolve("library"));
        cache = base.resolve("cache");
        Files.writeString(library.resolve("Heat.m4v"), "0123456789");
        editions = mock(EditionRepository.class);
        runner = new FakeRunner();
        edition = edition(film(1L, "Heat", 1995, NOW), EDITION_ID, "Original", 100 * MIN, null);
        part1 = file(edition, "Heat.m4v");
        part1.setId(7L);
        part1.setSizeBytes(10L);
        part1.setModifiedAt(Instant.ofEpochMilli(1_700_000_000_123L));
        when(editions.findById(EDITION_ID)).thenReturn(Optional.of(edition));
        service = service(2, Duration.ZERO, 300);
    }

    private StillService service;

    private StillService service(int concurrent, Duration acquire, int max) {
        return new StillService(editions, new LibraryRoot(library.toString()), runner,
            new TransactionTemplate(mock(PlatformTransactionManager.class)), "ffmpeg", cache.toString(), concurrent,
            acquire, max);
    }

    private Path stills() {
        return cache.toAbsolutePath().resolve("stills");
    }

    private MediaFile secondPart(long durationMs) throws IOException {
        part1.setDurationMs(60 * MIN);
        Files.writeString(library.resolve("Heat 2.m4v"), "abcdefghij");
        MediaFile part2 = file(edition, "Heat 2.m4v");
        part2.setPartNumber(2);
        part2.setId(8L);
        part2.setDurationMs(durationMs);
        part2.setSizeBytes(10L);
        return part2;
    }

    private String seekOf(List<String> command) {
        return command.get(command.indexOf("-ss") + 1);
    }

    @Test
    void grabsTheFrameWithTheExactCommandAndCachesItUnderTheStamp() throws IOException {
        byte[] jpeg = service.still(EDITION_ID, 750_900L);

        assertThat(jpeg).isEqualTo(JPEG);
        assertThat(stills().resolve("7-750-10-1700000000123.jpg")).hasBinaryContent(JPEG);
        assertThat(runner.commands).singleElement().satisfies(command -> {
            assertThat(command.subList(0, command.size() - 1)).containsExactly("ffmpeg", "-nostdin", "-hide_banner",
                "-v", "error", "-protocol_whitelist", "file", "-threads", "1", "-ss", "750", "-i",
                library.toRealPath().resolve("Heat.m4v").toString(), "-map", "0:v:0", "-frames:v", "1", "-an", "-sn",
                "-dn", "-vf",
                "scale='trunc(iw*sar/2)*2':ih,setsar=1,scale='min(1280,iw)':-2", "-q:v", "5", "-f", "image2", "-y");
            assertThat(command.get(command.size() - 1)).startsWith(stills().toString()).endsWith(".tmp");
        });
        assertThat(StillService.TIMEOUT).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void flooringToWholeSecondsMeansTimesInTheSameSecondShareOneGrab() throws IOException {
        byte[] first = service.still(EDITION_ID, 12_000L);
        byte[] second = service.still(EDITION_ID, 12_999L);

        assertThat(second).isEqualTo(first);
        assertThat(runner.commands).hasSize(1);
        assertThat(seekOf(runner.commands.get(0))).isEqualTo("12");
    }

    @Test
    void aCacheHitRunsNothing() throws IOException {
        Files.createDirectories(stills());
        Files.write(stills().resolve("7-30-10-1700000000123.jpg"), new byte[] {9, 8, 7});

        assertThat(service.still(EDITION_ID, 30_500L)).isEqualTo(new byte[] {9, 8, 7});
        assertThat(runner.commands).isEmpty();
    }

    @Test
    void aCachedFrameIsNotServedOnceTheFilmIsGoneFromDisk() throws IOException {
        service.still(EDITION_ID, 30_000L);
        Files.delete(library.resolve("Heat.m4v"));

        assertThatThrownBy(() -> service.still(EDITION_ID, 30_000L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aReplacedFileMissesTheCache() throws IOException {
        service.still(EDITION_ID, 30_000L);
        part1.setModifiedAt(Instant.ofEpochMilli(1_800_000_000_000L));

        service.still(EDITION_ID, 30_000L);

        assertThat(files(stills())).contains("7-30-10-1800000000000.jpg");
        assertThat(runner.commands).hasSize(2);
    }

    @Test
    void aFileWithNoSizeOrModifiedTimeStillGetsAStamp() {
        part1.setSizeBytes(null);
        part1.setModifiedAt(null);

        service.still(EDITION_ID, 0L);

        assertThat(stills().resolve("7-0-0-0.jpg")).exists();
    }

    @Test
    void aCachedFileRemovedBetweenTheCheckAndTheReadFallsThroughToAFreshGrab() throws IOException {
        Files.createDirectories(stills());
        Path cached = Files.write(stills().resolve("7-30-10-1700000000123.jpg"), new byte[] {9});
        StillService racing = new StillService(editions, new LibraryRoot(library.toString()), runner,
            new TransactionTemplate(mock(PlatformTransactionManager.class)), "ffmpeg", cache.toString(), 2,
            Duration.ZERO, 300) {
                @Override
                byte[] read(Path file) throws IOException {
                    Files.delete(cached); // pruned by another request just after the existence check
                    return super.read(file);
                }
            };

        byte[] jpeg = racing.still(EDITION_ID, 30_000L);

        assertThat(jpeg).isEqualTo(JPEG);
        assertThat(runner.commands).hasSize(1);
    }

    @Test
    void aCachedFileThatCannotBeReadIs503() throws IOException {
        Files.createDirectories(stills());
        Files.write(stills().resolve("7-30-10-1700000000123.jpg"), new byte[] {9});
        StillService broken = new StillService(editions, new LibraryRoot(library.toString()), runner,
            new TransactionTemplate(mock(PlatformTransactionManager.class)), "ffmpeg", cache.toString(), 2,
            Duration.ZERO, 300) {
                @Override
                byte[] read(Path file) throws IOException {
                    throw new IOException("disk error");
                }
            };

        assertThatThrownBy(() -> broken.still(EDITION_ID, 30_000L)).isInstanceOf(ServiceUnavailableException.class)
            .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void aMultiPartEditionMapsToTheLaterPartsFileAndOffset() throws IOException {
        secondPart(40 * MIN);

        service.still(EDITION_ID, 60 * MIN + 90_500);

        assertThat(runner.commands).singleElement().satisfies(command -> {
            assertThat(command).contains(library.toRealPath().resolve("Heat 2.m4v").toString());
            assertThat(seekOf(command)).isEqualTo("90");
        });
    }

    @Test
    void aTimeExactlyOnTheJoinIsTheStartOfTheLaterPart() throws IOException {
        secondPart(40 * MIN);

        service.still(EDITION_ID, 60 * MIN);

        assertThat(runner.commands.get(0)).contains(library.toRealPath().resolve("Heat 2.m4v").toString());
        assertThat(seekOf(runner.commands.get(0))).isEqualTo("0");
    }

    @Test
    void theVeryEndOfTheFilmIsClampedToOneSecondBeforeTheEndOfTheLastPart() throws IOException {
        secondPart(40 * MIN);

        service.still(EDITION_ID, 100 * MIN);

        assertThat(seekOf(runner.commands.get(0))).isEqualTo(Long.toString(40 * 60 - 1));
    }

    @Test
    void theEndOfAPartShorterThanOneSecondClampsToZeroNeverNegative() throws IOException {
        edition.getMediaFiles().clear();
        MediaFile tiny = file(edition, "Heat.m4v");
        tiny.setId(7L);
        tiny.setDurationMs(500L);

        service.still(EDITION_ID, 500L);

        assertThat(seekOf(runner.commands.get(0))).isEqualTo("0");
    }

    @Test
    void aTimeOutsideTheFilmIsA400() {
        assertThatThrownBy(() -> service.still(EDITION_ID, -1L)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.still(EDITION_ID, 100 * MIN + 1)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.still(EDITION_ID, null)).isInstanceOf(BadRequestException.class);
        assertThat(runner.commands).isEmpty();
    }

    @Test
    void theExactEndOfTheFilmAndZeroAreAccepted() {
        service.still(EDITION_ID, 0L);
        service.still(EDITION_ID, 100 * MIN);

        assertThat(runner.commands).hasSize(2);
    }

    @Test
    void anUnknownEditionIs404() {
        assertThatThrownBy(() -> service.still(99L, 0L)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such edition");
    }

    @Test
    void anEditionWithNoOriginalFileIs404() {
        part1.setKind(MediaKind.PREMADE_720);

        assertThatThrownBy(() -> service.still(EDITION_ID, 0L)).isInstanceOf(NotFoundException.class)
            .hasMessage("Edition has no original file");
    }

    @Test
    void aPartWithNoDurationIs404() {
        part1.setDurationMs(null);

        assertThatThrownBy(() -> service.still(EDITION_ID, 0L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aPartThatIsNotPlayableIs404() {
        part1.setPlayability(Playability.NEEDS_CONVERSION);

        assertThatThrownBy(() -> service.still(EDITION_ID, 0L)).isInstanceOf(NotFoundException.class)
            .hasMessage("Media file is not available");
    }

    @Test
    void aFileMissingUnderTheLibraryRootIs404AndRunsNothing() throws IOException {
        Files.delete(library.resolve("Heat.m4v"));

        assertThatThrownBy(() -> service.still(EDITION_ID, 0L)).isInstanceOf(NotFoundException.class);
        assertThat(runner.commands).isEmpty();
    }

    @Test
    void ffmpegThatCannotStartIs503() {
        runner.failure = new IOException("No such file: ffmpeg");

        assertThatThrownBy(() -> service.still(EDITION_ID, 0L)).isInstanceOf(ServiceUnavailableException.class)
            .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void aTimeoutIs503AndLeavesNoTemporaryFile() throws IOException {
        runner.result = new CommandRunner.Result(-1, "", true);

        assertThatThrownBy(() -> service.still(EDITION_ID, 0L)).isInstanceOf(ServiceUnavailableException.class);
        assertThat(files(stills())).isEmpty();
    }

    @Test
    void aNonZeroExitIs503AndTheTemporaryFileIsRemoved() throws IOException {
        runner.result = new CommandRunner.Result(1, "", false);

        assertThatThrownBy(() -> service.still(EDITION_ID, 0L)).isInstanceOf(ServiceUnavailableException.class);
        assertThat(files(stills())).isEmpty();
    }

    @Test
    void aSuccessfulExitWithNoOutputFileIs503() throws IOException {
        runner.writes = false;

        assertThatThrownBy(() -> service.still(EDITION_ID, 0L)).isInstanceOf(ServiceUnavailableException.class);
        assertThat(files(stills())).isEmpty();
    }

    @Test
    void aCacheFolderThatCannotBeCreatedIs503() throws IOException {
        Files.writeString(cache, "a file where the folder should be");

        assertThatThrownBy(() -> service.still(EDITION_ID, 0L)).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void whenAllSlotsAreBusyTheRequestIs503WithoutRunningFfmpeg() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CommandRunner blocking = (command, timeout) -> {
            inside.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Files.write(Path.of(command.get(command.size() - 1)), JPEG);
            return new CommandRunner.Result(0, "", false);
        };
        StillService busy = new StillService(editions, new LibraryRoot(library.toString()), blocking,
            new TransactionTemplate(mock(PlatformTransactionManager.class)), "ffmpeg", cache.toString(), 1,
            Duration.ZERO, 300);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<byte[]> first = pool.submit(() -> busy.still(EDITION_ID, 10_000L));
            assertThat(inside.await(30, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> busy.still(EDITION_ID, 20_000L)).isInstanceOf(ServiceUnavailableException.class);

            release.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(JPEG);
            assertThat(busy.still(EDITION_ID, 20_000L)).isEqualTo(JPEG);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void anInterruptedWaitForASlotIs503AndKeepsTheInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> service.still(EDITION_ID, 0L)).isInstanceOf(ServiceUnavailableException.class)
                .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(runner.commands).isEmpty();
    }

    @Test
    void pruningKeepsTheNewestStillsDownToTheCapAndTheOneJustWritten() throws IOException {
        StillService small = service(2, Duration.ZERO, 3);
        Files.createDirectories(stills());
        for (int i = 0; i < 5; i++) {
            Path old = Files.write(stills().resolve("old-" + i + ".jpg"), JPEG);
            Files.setLastModifiedTime(old, FileTime.fromMillis(1_000_000L + i * 1000L));
        }

        small.still(EDITION_ID, 0L);

        assertThat(files(stills())).containsExactlyInAnyOrder("old-3.jpg", "old-4.jpg", "7-0-10-1700000000123.jpg");
    }

    @Test
    void pruningDoesNothingWhileUnderTheCap() throws IOException {
        service.still(EDITION_ID, 0L);
        service.still(EDITION_ID, 5_000L);

        assertThat(files(stills())).hasSize(2);
    }

    @Test
    void pruningIgnoresFilesThatAreNotStills() throws IOException {
        StillService small = service(2, Duration.ZERO, 1);
        Files.createDirectories(stills());
        Files.write(stills().resolve("other.txt"), JPEG);
        Files.write(stills().resolve("old.jpg"), JPEG);

        small.still(EDITION_ID, 0L);

        assertThat(files(stills())).containsExactlyInAnyOrder("other.txt", "7-0-10-1700000000123.jpg");
    }

    private static List<String> files(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> listing = Files.list(dir)) {
            return listing.map(p -> p.getFileName().toString()).toList();
        }
    }
}
