package gregochr.cinefilm.transcode;

import static gregochr.cinefilm.TestData.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.library.LibraryRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class KeyframeIndexServiceTest {

    private static final long DURATION = 20_000;

    @TempDir
    Path library;

    private final KeyframeIndexer indexer = mock(KeyframeIndexer.class);
    private final MediaKeyframesRepository rows = mock(MediaKeyframesRepository.class);
    private FileStamps.Stamp stamp = new FileStamps.Stamp(1000, 5000);
    private boolean statFails;
    private KeyframeIndexService service;
    private Path path;

    @BeforeEach
    void setUp() throws IOException {
        path = library.resolve("Heat.mp4");
        Files.writeString(path, "x");
        FileStamps stamps = file -> {
            if (statFails) {
                throw new IOException("stale handle");
            }
            return stamp;
        };
        service = new KeyframeIndexService(new LibraryRoot(library.toString()), indexer, rows, stamps, CLOCK);
    }

    private MediaFile media(String relative, Long duration) {
        MediaFile file = new MediaFile();
        file.setId(7L);
        file.setPath(relative);
        file.setDurationMs(duration);
        return file;
    }

    private MediaKeyframes row(String cuts, long size, long mtime) {
        return new MediaKeyframes(7L, cuts, 8000, true, size, mtime, CLOCK.instant(), 25.0);
    }

    @Test
    void aFileWithoutARowIsIndexedAndTheRowStoresCutsGapStampAndTime() {
        when(rows.findById(7L)).thenReturn(Optional.empty());
        when(indexer.index(realPath())).thenReturn(new KeyframeIndex(List.of(0L, 4000L, 9000L, 19_000L), 23.976));

        KeyframeIndexService.Index index = service.ensureIndexed(media("Heat.mp4", DURATION));
        CutList cuts = index.cuts();

        assertThat(cuts.toText()).isEqualTo("0,4000,9000,19000");
        assertThat(index.fps()).isEqualTo(23.976);
        ArgumentCaptor<MediaKeyframes> saved = ArgumentCaptor.forClass(MediaKeyframes.class);
        verify(rows).save(saved.capture());
        MediaKeyframes row = saved.getValue();
        assertThat(row.getMediaFileId()).isEqualTo(7L);
        assertThat(row.getCuts()).isEqualTo("0,4000,9000,19000");
        assertThat(row.getMaxGapMs()).isEqualTo(10_000);
        assertThat(row.isCopyOk()).isTrue();
        assertThat(row.getSourceSize()).isEqualTo(1000);
        assertThat(row.getSourceMtime()).isEqualTo(5000);
        assertThat(row.getIndexedAt()).isEqualTo(CLOCK.instant());
        assertThat(row.getFps()).isEqualTo(23.976);
    }

    private Path realPath() {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aCurrentRowIsReturnedWithoutIndexingAgain() {
        when(rows.findById(7L)).thenReturn(Optional.of(row("0,4000", 1000, 5000)));

        KeyframeIndexService.Index index = service.ensureIndexed(media("Heat.mp4", DURATION));
        CutList cuts = index.cuts();

        assertThat(cuts.toText()).isEqualTo("0,4000");
        assertThat(index.fps()).isEqualTo(25.0);
        verify(indexer, never()).index(realPath());
        verify(rows, never()).save(ArgumentCaptor.forClass(MediaKeyframes.class).capture());
    }

    @Test
    void aChangedSizeOrModificationTimeReindexes() {
        when(rows.findById(7L)).thenReturn(Optional.of(row("0,4000", 999, 5000)))
            .thenReturn(Optional.of(row("0,4000", 1000, 4999)));
        when(indexer.index(realPath())).thenReturn(new KeyframeIndex(List.of(0L, 5000L), null));

        assertThat(service.ensureIndexed(media("Heat.mp4", DURATION)).cuts().toText()).isEqualTo("0,5000");
        assertThat(service.ensureIndexed(media("Heat.mp4", DURATION)).cuts().toText()).isEqualTo("0,5000");

        verify(indexer, times(2)).index(realPath());
        verify(rows, times(2)).save(ArgumentCaptor.forClass(MediaKeyframes.class).capture());
    }

    @Test
    void aCorruptStoredRowIsReindexed() {
        when(rows.findById(7L)).thenReturn(Optional.of(row("garbage", 1000, 5000)));
        when(indexer.index(realPath())).thenReturn(new KeyframeIndex(List.of(0L, 6000L), null));

        assertThat(service.ensureIndexed(media("Heat.mp4", DURATION)).cuts().toText()).isEqualTo("0,6000");
    }

    @Test
    void cachedNeverIndexesAndReportsEmptyForEveryUnusableCase() {
        when(rows.findById(7L)).thenReturn(Optional.of(row("0,4000", 1000, 5000)));

        assertThat(service.cached(media("Heat.mp4", DURATION))).isPresent();
        assertThat(service.cached(media("Heat.mp4", null))).isEmpty();
        assertThat(service.cached(media("Heat.mp4", 0L))).isEmpty();
        assertThat(service.cached(media("gone.mp4", DURATION))).isEmpty();
        assertThat(service.cached(media("../outside.mp4", DURATION))).isEmpty();
        statFails = true;
        assertThat(service.cached(media("Heat.mp4", DURATION))).isEmpty();
        stamp = new FileStamps.Stamp(1000, 5000);
        statFails = false;
        when(rows.findById(7L)).thenReturn(Optional.empty());
        assertThat(service.cached(media("Heat.mp4", DURATION))).isEmpty();
        verify(indexer, never()).index(realPath());
    }

    @Test
    void aStoredRowThatRunsPastTheDurationIsNotCurrent() {
        when(rows.findById(7L)).thenReturn(Optional.of(row("0,25000", 1000, 5000)));

        assertThat(service.cached(media("Heat.mp4", DURATION))).isEmpty();
    }

    @Test
    void aMissingFileCannotBeIndexed() {
        when(rows.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ensureIndexed(media("gone.mp4", DURATION)))
            .isInstanceOf(KeyframeIndexException.class).hasMessage("The file is missing or outside the library");
    }

    @Test
    void aFileWithoutADurationCannotBeIndexed() {
        when(rows.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ensureIndexed(media("Heat.mp4", null)))
            .hasMessage("The file has no known duration");
        assertThatThrownBy(() -> service.ensureIndexed(media("Heat.mp4", 0L)))
            .hasMessage("The file has no known duration");
    }

    @Test
    void aFileWhoseSizeCannotBeReadCannotBeIndexed() {
        when(rows.findById(7L)).thenReturn(Optional.empty());
        statFails = true;

        assertThatThrownBy(() -> service.ensureIndexed(media("Heat.mp4", DURATION)))
            .isInstanceOf(KeyframeIndexException.class)
            .hasMessage("Cannot read the file's size and time: stale handle");
    }

    @Test
    void aFilmWithTooManySegmentsToStoreIsRefused() {
        when(rows.findById(7L)).thenReturn(Optional.empty());
        List<Long> keyframes = new java.util.ArrayList<>();
        for (long t = 0; t < 40_000_000; t += 4000) {
            keyframes.add(t);
        }
        when(indexer.index(realPath())).thenReturn(new KeyframeIndex(keyframes, null));

        assertThatThrownBy(() -> service.ensureIndexed(media("Heat.mp4", 40_000_000L)))
            .hasMessage("The film has too many segments to store");
        verify(rows, never()).save(ArgumentCaptor.forClass(MediaKeyframes.class).capture());
    }

    /** Keyframes whose stored text is exactly {@code length} characters long, ending in one cut far past the rest. */
    private static List<Long> keyframesForTextOfLength(int length) {
        List<Long> keyframes = new java.util.ArrayList<>();
        keyframes.add(0L);
        int used = 1;
        long next = 0;
        while (length - used > 20) {
            next += 4000;
            keyframes.add(next);
            used += 1 + Long.toString(next).length();
        }
        int digits = length - used - 1;
        keyframes.add((long) Math.pow(10, digits - 1));
        return keyframes;
    }

    @Test
    void aStoredTextOfExactlyTheColumnLengthIsKeptAndOneCharacterMoreIsRefused() {
        when(rows.findById(7L)).thenReturn(Optional.empty());
        List<Long> fits = keyframesForTextOfLength(MediaKeyframes.MAX_CUTS_LENGTH);
        List<Long> over = keyframesForTextOfLength(MediaKeyframes.MAX_CUTS_LENGTH + 1);
        long duration = Long.MAX_VALUE / 2;
        assertThat(CutList.fromKeyframes(fits, duration).toText()).hasSize(MediaKeyframes.MAX_CUTS_LENGTH);
        assertThat(CutList.fromKeyframes(over, duration).toText()).hasSize(MediaKeyframes.MAX_CUTS_LENGTH + 1);

        when(indexer.index(realPath())).thenReturn(new KeyframeIndex(fits, null));
        assertThat(service.ensureIndexed(media("Heat.mp4", duration)).cuts().segmentCount()).isEqualTo(fits.size());
        verify(rows).save(ArgumentCaptor.forClass(MediaKeyframes.class).capture());

        when(indexer.index(realPath())).thenReturn(new KeyframeIndex(over, null));
        assertThatThrownBy(() -> service.ensureIndexed(media("Heat.mp4", duration)))
            .hasMessage("The film has too many segments to store");
    }

    @Test
    void indexerFailuresPropagate() {
        when(rows.findById(7L)).thenReturn(Optional.empty());
        when(indexer.index(realPath())).thenThrow(new KeyframeIndexException("ffprobe found no keyframes"));

        assertThatThrownBy(() -> service.ensureIndexed(media("Heat.mp4", DURATION)))
            .hasMessage("ffprobe found no keyframes");
    }
}
