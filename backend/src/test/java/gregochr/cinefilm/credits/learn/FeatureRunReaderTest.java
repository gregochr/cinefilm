package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.credits.learn.FeatureRunReader.FilmRows;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FeatureRunReaderTest {

    @TempDir
    Path temp;

    private static String dev(long id, int fold, String kind, long offset) {
        return TrainFixtures.row(id, fold, kind, offset, "film", TrainFixtures.lit(1, 10, 0));
    }

    private List<FilmRows> read(String rows, boolean look) throws IOException {
        return FeatureRunReader.read(Files.writeString(temp.resolve("features.csv"), FeatureRows.header() + rows),
            look);
    }

    @Test
    void rowsAreGroupedByFilmWithTheirKindsAndTheDenseRowsKeepTheirMissingTimeFeatures() throws IOException {
        String test = FeatureRows.row(3, null, Side.TEST, "coarse", 0, "film", TrainFixtures.plate(1, 0));
        String noTime = TrainFixtures.row(1, 2, "dense", 5, "film", TrainFixtures.noTime(TrainFixtures.lit(1, 0)));

        List<FilmRows> films = read(dev(1, 2, "coarse", 0) + dev(1, 2, "coarse", 5)
            + TrainFixtures.row(1, 2, "coarse", 9, "film", TrainFixtures.dark(1, 0)) + dev(1, 2, "dense", 1) + noTime
            + dev(2, 4, "coarse", 0) + test, true);

        assertThat(films).extracting(FilmRows::id).containsExactly(1L, 2L, 3L);
        assertThat(films).extracting(FilmRows::side).containsExactly(Side.DEV, Side.DEV, Side.TEST);
        assertThat(films).extracting(FilmRows::fold).containsExactly(2, 4, null);
        assertThat(films.get(0).coarseOffsets()).containsExactly(0L, 5L, 9L);
        assertThat(films.get(0).kinds()).containsExactly(FrameKind.LIT, FrameKind.LIT, FrameKind.DARK);
        assertThat(films.get(0).denseOffsets()).containsExactly(1L, 5L);
        assertThat(CreditsFeatures.hasTimeFeatures(films.get(0).dense().get(1))).isFalse();
        assertThat(films.get(2).kinds()).containsExactly(FrameKind.PLATE);
    }

    static Stream<Arguments> refusals() {
        String test = FeatureRows.row(9, null, Side.TEST, "coarse", 0, "film", TrainFixtures.lit(1, 0));
        return Stream.of(
            Arguments.of(test, false, "line 2: a test row is refused without --look=L5; nothing was written"),
            Arguments.of(FeatureRows.row(1, null, Side.DEV, "coarse", 0, "film", TrainFixtures.lit(1, 0)), true,
                "line 2: a development row with no fold is refused; nothing was written"),
            Arguments.of(dev(1, 5, "coarse", 0), false, "line 2: fold must be 0 to 4: 5"),
            Arguments.of(dev(1, -1, "coarse", 0), false, "line 2: fold must be 0 to 4: -1"),
            Arguments.of(dev(1, 0, "coarse", 5) + dev(1, 0, "coarse", 5), false,
                "line 3: rows are not in (mediaFileId, kind, offsetMs) order"),
            Arguments.of(dev(1, 0, "dense", 5) + dev(1, 0, "coarse", 9), false,
                "line 3: rows are not in (mediaFileId, kind, offsetMs) order"),
            Arguments.of(dev(1, 0, "coarse", 0) + dev(2, 1, "coarse", 0).replace("dev", "other"), false,
                "line 3: side must be dev or test: other"),
            Arguments.of(dev(1, 0, "coarse", 0) + dev(1, 1, "coarse", 5), false,
                "line 3: film 1 changes side or fold"),
            Arguments.of(dev(1, 0, "coarse", 0)
                + FeatureRows.row(1, null, Side.TEST, "coarse", 5, "film", TrainFixtures.lit(1, 0)), true,
                "line 3: film 1 changes side or fold"),
            Arguments.of("1,0,dev\n", false, "line 2: expected 39 fields, found 3"));
    }

    @ParameterizedTest
    @MethodSource("refusals")
    void aBadRowIsRefusedWithItsLine(String rows, boolean look, String message) {
        assertThatThrownBy(() -> read(rows, look)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("features.csv " + message);
    }

    @ParameterizedTest
    @MethodSource("refusals")
    void forEachFilmRefusesWhatReadRefusesWithTheSameMessage(String rows, boolean look, String message) {
        assertThatThrownBy(() -> FeatureRunReader.forEachFilm(
            write(rows), look, film -> {
            })).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("features.csv " + message);
    }

    private Path write(String rows) throws IOException {
        return Files.writeString(temp.resolve("features.csv"), FeatureRows.header() + rows);
    }

    @Test
    void forEachFilmHandsEachFilmOverOnceInFileOrderWithTheRowsThatReadGives() throws IOException {
        String test = FeatureRows.row(3, null, Side.TEST, "coarse", 0, "film", TrainFixtures.plate(1, 0));
        String rows = dev(1, 2, "coarse", 0) + dev(1, 2, "coarse", 5) + dev(1, 2, "dense", 1)
            + dev(2, 4, "coarse", 0) + test;
        List<FilmRows> seen = new java.util.ArrayList<>();

        FeatureRunReader.forEachFilm(write(rows), true, seen::add);

        assertThat(seen).extracting(FilmRows::id).containsExactly(1L, 2L, 3L);
        List<FilmRows> read = FeatureRunReader.read(write(rows), true);
        assertThat(read).extracting(FilmRows::coarseOffsets).isEqualTo(seen.stream().map(FilmRows::coarseOffsets)
            .toList());
        assertThat(read).extracting(FilmRows::denseOffsets).isEqualTo(seen.stream().map(FilmRows::denseOffsets)
            .toList());
        assertThat(seen).extracting(FilmRows::side).containsExactly(Side.DEV, Side.DEV, Side.TEST);
        assertThat(seen.getFirst().coarseOffsets()).containsExactly(0L, 5L);
        assertThat(seen.getFirst().denseOffsets()).containsExactly(1L);
    }

    @Test
    void forEachFilmHandsAFilmOverBeforeTheNextFilmsLaterRowsAreRead() throws IOException {
        // Film 2's second row is malformed. If the sink were called only once the whole file was read, the film 1
        // delivery would never happen and the malformed row's message would surface instead of the sink's.
        String rows = dev(1, 0, "coarse", 0) + dev(2, 1, "coarse", 0) + "2,1,dev\n";
        List<Long> seen = new java.util.ArrayList<>();

        assertThatThrownBy(() -> FeatureRunReader.forEachFilm(write(rows), false, film -> {
            seen.add(film.id());
            throw new IOException("sink stopped at film " + film.id());
        })).isInstanceOf(IOException.class).hasMessage("sink stopped at film 1");
        assertThat(seen).containsExactly(1L);
        assertThatThrownBy(() -> FeatureRunReader.forEachFilm(write(rows), false, film -> {
        })).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("features.csv line 4: expected 39 fields, found 3");
    }

    @Test
    void forEachFilmHandsTheLastFilmOverAtTheEndOfTheFileAndAnEmptyFileHandsNothing() throws IOException {
        List<Long> seen = new java.util.ArrayList<>();

        FeatureRunReader.forEachFilm(write(dev(1, 0, "coarse", 0) + dev(2, 1, "coarse", 0)), false,
            film -> seen.add(film.id()));
        FeatureRunReader.forEachFilm(write(""), false, film -> seen.add(-1L));

        assertThat(seen).containsExactly(1L, 2L);
    }

    @Test
    void aFileThatDoesNotStartWithTheFeaturesHeaderIsRefused() throws IOException {
        Path file = Files.writeString(temp.resolve("bad.csv"), "a,b\n");

        assertThatThrownBy(() -> FeatureRunReader.read(file, false)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("features.csv does not start with the features header");
    }

    @Test
    void aFilmOfOneCoarseFrameWithNoTimeFeaturesIsRead() throws IOException {
        List<FilmRows> films = read(EvaluateFixtures.lonely(), false);

        assertThat(films).hasSize(1);
        assertThat(films.getFirst().coarseOffsets()).containsExactly(100_000L);
        assertThat(CreditsFeatures.hasTimeFeatures(films.getFirst().coarse().getFirst())).isFalse();
    }

    @Test
    void blankLinesAreSkippedAndAnEmptyFileGivesNoFilms() throws IOException {
        assertThat(read("\n" + dev(1, 0, "coarse", 0) + "\n", false)).hasSize(1);
        assertThat(read("", false)).isEmpty();
    }

    @Test
    void labelsAreReadByMediaFileIdAndARepeatOrNullIsRefused() throws IOException {
        Path file = Files.writeString(temp.resolve("frame-labels.json"), EvaluateFixtures.labels(2));

        Map<Long, FrameLabelRecord> labels = FeatureRunReader.labels(file);

        assertThat(labels).containsOnlyKeys(0L, 1L, 2L);
        assertThat(labels.get(1L).openingType()).isEqualTo(FrameKind.DARK);
        Path twice = Files.writeString(temp.resolve("twice.json"), "[null]");
        assertThatThrownBy(() -> FeatureRunReader.labels(twice)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("null entry or repeats");
        String one = EvaluateFixtures.labels(1);
        Path repeat = Files.writeString(temp.resolve("repeat.json"), "[" + one.substring(1, one.length() - 1) + ","
            + one.substring(1, one.length() - 1) + "]");
        assertThatThrownBy(() -> FeatureRunReader.labels(repeat)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("null entry or repeats");
    }

    @Test
    void keysAreTheSideAndTheOffsetsOfEachKindInFileOrderWithoutParsingTheFeatures() throws IOException {
        String test = FeatureRows.row(3, null, Side.TEST, "coarse", 0, "film", TrainFixtures.plate(1, 0));
        Path file = Files.writeString(temp.resolve("features.csv"), FeatureRows.header() + dev(1, 2, "coarse", 0)
            + dev(1, 2, "coarse", 5) + dev(1, 2, "dense", 1) + dev(2, 4, "coarse", 7) + test);

        List<FeatureRunReader.FilmKeys> films = FeatureRunReader.keys(file, true);

        assertThat(films).containsExactly(
            new FeatureRunReader.FilmKeys(1, Side.DEV, List.of(0L, 5L), List.of(1L)),
            new FeatureRunReader.FilmKeys(2, Side.DEV, List.of(7L), List.of()),
            new FeatureRunReader.FilmKeys(3, Side.TEST, List.of(0L), List.of()));
    }
}
