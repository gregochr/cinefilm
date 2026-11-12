package gregochr.cinefilm.transcode;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.TestData.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmDtos.EditionDto;
import gregochr.cinefilm.film.FilmDtos.QualityDto;
import gregochr.cinefilm.film.FilmMapper;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.library.LibraryRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Film detail and a playback session read the same cut lists. Each part's cuts end at 56 s, its video at 60 s and its
 * container at 70 s: against the container the last gap is 14 s (no copy), against the video 4 s (copy). Detail must
 * offer exactly the qualities the session ladder has.
 */
class IndexDurationAgreementTest {

    private static final long VIDEO_MS = 60_000;
    private static final long CONTAINER_MS = 70_000;

    @TempDir
    Path library;

    private final MediaKeyframesRepository rows = mock(MediaKeyframesRepository.class);
    private final EditionRepository editions = mock(EditionRepository.class);

    private Edition split() throws IOException {
        Film film = film(1L, "Towers", 2002, NOW);
        Edition edition = edition(film, 10L, "Extended", 0, null);
        for (int number = 1; number <= 2; number++) {
            Files.writeString(library.resolve("pt" + number + ".m4v"), "x");
            MediaFile part = file(edition, "pt" + number + ".m4v");
            part.setId((long) number);
            part.setPartNumber(number);
            part.setVideoCodec("H.264");
            part.setPixelFormat("yuv420p");
            part.setHeight(800);
            part.setWidth(1920);
            part.setDurationMs(CONTAINER_MS);
            part.setVideoDurationMs(VIDEO_MS);
            part.getAudioStreams().get(0).setId(number * 10L);
            part.getAudioStreams().get(1).setId(number * 10L + 1);
            String cuts = IntStream.rangeClosed(0, 14).mapToObj(i -> String.valueOf(i * 4000))
                .collect(Collectors.joining(","));
            when(rows.findById((long) number)).thenReturn(Optional.of(
                new MediaKeyframes((long) number, cuts, 4000, true, 1000, 5000, CLOCK.instant(), 25.0)));
        }
        edition.recomputeRuntime();
        return edition;
    }

    private KeyframeIndexService indexes() {
        return new KeyframeIndexService(new LibraryRoot(library.toString()), mock(KeyframeIndexer.class), rows,
            file -> new FileStamps.Stamp(1000, 5000), CLOCK);
    }

    @Test
    void detailAndTheSessionAgreeThatTheCopyRungExistsWhenTheVideoEndsFourSecondsAfterTheLastCut() throws IOException {
        Edition edition = split();
        KeyframeIndexService indexes = indexes();
        when(editions.findById(10L)).thenReturn(Optional.of(edition));

        EditionDto detail = FilmMapper.detail(view(edition.getFilm()), null, false, EncoderMode.VAAPI,
            file -> indexes.cachedFor(file, true).map(KeyframeIndexService.Index::cuts).orElse(null))
            .editions().get(0);

        Transcoder transcoder = mock(Transcoder.class);
        SessionManager manager = mock(SessionManager.class);
        when(transcoder.mode()).thenReturn(EncoderMode.VAAPI);
        when(manager.create(any())).thenAnswer(call -> new SessionView("5b0f1c1e-6a52-4d5e-9a4c-0a7d3c1f2b11",
            List.of(), null, null, null, 1, 1));
        SessionService sessions = new SessionService(new SessionSources(editions, new LibraryRoot(library.toString())),
            indexes, transcoder, manager);
        List<String> ladder = sessions.create(10L, new SessionDtos.CreateRequest(null, null, "auto", null))
            .renditions().stream().map(SessionDtos.RenditionDto::id).toList();

        assertThat(ladder).containsExactly("original", "720p", "480p", "360p", "240p");
        assertThat(detail.qualities()).extracting(QualityDto::id).containsExactlyElementsOf(
            java.util.stream.Stream.concat(java.util.stream.Stream.of("auto"), ladder.stream()).toList());
        assertThat(detail.qualities()).filteredOn(q -> q.id().equals("original"))
            .extracting(QualityDto::available).containsExactly(true);
    }

    @Test
    void theIndexDurationIsTheVideoForAPartAndTheContainerForAFileInOnePiece() throws IOException {
        MediaFile part = split().originalParts().get(0);

        assertThat(KeyframeIndexService.indexDurationMs(part, true)).isEqualTo(VIDEO_MS);
        assertThat(KeyframeIndexService.indexDurationMs(part, false)).isEqualTo(CONTAINER_MS);
        assertThat(indexes().cachedFor(part, true).orElseThrow().cuts().totalDurationMs()).isEqualTo(VIDEO_MS);
        assertThat(indexes().cachedFor(part, false).orElseThrow().cuts().totalDurationMs()).isEqualTo(CONTAINER_MS);
    }

    @Test
    void ensureIndexedForUsesTheSameDuration() throws IOException {
        MediaFile part = split().originalParts().get(0);

        assertThat(indexes().ensureIndexedFor(part, true).cuts().totalDurationMs()).isEqualTo(VIDEO_MS);
    }
}
