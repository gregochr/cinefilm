package gregochr.cinefilm.transcode;

import static gregochr.cinefilm.TestData.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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

/**
 * A part of a split film is indexed to the end of its video, not to the container's duration (which can be the
 * audio's): the cut list covers the duration it is asked for.
 */
class KeyframeIndexServiceDurationTest {

    private static final long CONTAINER = 21_040;
    private static final long VIDEO = 20_000;

    @TempDir
    Path library;

    private final KeyframeIndexer indexer = mock(KeyframeIndexer.class);
    private final MediaKeyframesRepository rows = mock(MediaKeyframesRepository.class);
    private KeyframeIndexService service;
    private Path path;

    @BeforeEach
    void setUp() throws IOException {
        path = Files.writeString(library.resolve("pt1.m4v"), "x").toRealPath();
        service = new KeyframeIndexService(new LibraryRoot(library.toString()), indexer, rows,
            file -> new FileStamps.Stamp(1000, 5000), CLOCK);
    }

    private static MediaFile media() {
        MediaFile file = new MediaFile();
        file.setId(7L);
        file.setPath("pt1.m4v");
        file.setDurationMs(CONTAINER);
        file.setVideoDurationMs(VIDEO);
        return file;
    }

    @Test
    void theCutListEndsAtTheGivenDurationNotTheContainers() {
        when(rows.findById(7L)).thenReturn(Optional.empty());
        when(indexer.index(path)).thenReturn(new KeyframeIndex(List.of(0L, 4000L, 20_500L), 25.0));

        CutList cuts = service.ensureIndexed(media(), VIDEO).cuts();

        assertThat(cuts.totalDurationMs()).isEqualTo(VIDEO);
        assertThat(cuts.toText()).isEqualTo("0,4000"); // the keyframe at 20 500 is after the video's end
    }

    @Test
    void aStoredRowIsReadBackToTheGivenDuration() {
        when(rows.findById(7L)).thenReturn(Optional.of(
            new MediaKeyframes(7L, "0,4000", 8000, true, 1000, 5000, CLOCK.instant(), 25.0)));

        Optional<KeyframeIndexService.Index> cached = service.cached(media(), VIDEO);

        assertThat(cached).isPresent();
        assertThat(cached.get().cuts().totalDurationMs()).isEqualTo(VIDEO);
        assertThat(service.cached(media()).orElseThrow().cuts().totalDurationMs()).isEqualTo(CONTAINER);
    }

    @Test
    void aStoredRowWhoseLastCutIsNotBeforeTheGivenDurationIsReindexed() {
        when(rows.findById(7L)).thenReturn(Optional.of(
            new MediaKeyframes(7L, "0,4000,20500", 8000, true, 1000, 5000, CLOCK.instant(), 25.0)));
        when(indexer.index(path)).thenReturn(new KeyframeIndex(List.of(0L, 4000L), 25.0));

        CutList cuts = service.ensureIndexed(media(), VIDEO).cuts();

        assertThat(cuts.toText()).isEqualTo("0,4000");
        verify(rows).save(org.mockito.ArgumentMatchers.any(MediaKeyframes.class));
    }

    @Test
    void anUnknownDurationIsNotCachedAndCannotBeIndexed() {
        when(rows.findById(7L)).thenReturn(Optional.empty());

        assertThat(service.cached(media(), null)).isEmpty();
        assertThat(service.cached(media(), 0L)).isEmpty();
        assertThatThrownBy(() -> service.ensureIndexed(media(), null)).isInstanceOf(KeyframeIndexException.class)
            .hasMessage("The file has no known duration");
    }
}
