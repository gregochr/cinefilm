package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.learn.FilmScore.Film;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a features run for the evaluation tool ({@code docs/engineering/credits-classifier-plan.md}, "L3c
 * specification", {@code CreditsEvaluateMain}): {@code features.csv} grouped by film, and {@code frame-labels.json}.
 * Every row is kept, whatever its label. Nothing is scored here.
 */
final class FeatureRunReader {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final int P10 = 1;
    private static final int P50 = 2;
    private static final int P90 = 3;

    private FeatureRunReader() {
    }

    /** One film's rows, filled in as they are read. */
    record FilmRows(long id, Side side, Integer fold, List<Long> coarseOffsets, List<FrameKind> kinds,
                    List<double[]> coarse, List<Long> denseOffsets, List<double[]> dense) {

        private static FilmRows start(long id, Side side, Integer fold) {
            return new FilmRows(id, side, fold, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>());
        }

        /** The stored facts the score needs, given Plex's target and opening type. */
        Film film(long target, Optional<FrameKind> plexOpening) {
            return new Film(coarseOffsets.stream().mapToLong(Long::longValue).toArray(),
                kinds.toArray(FrameKind[]::new), denseOffsets.stream().mapToLong(Long::longValue).toArray(), target,
                plexOpening);
        }
    }

    /** One film's frame keys and nothing else: the side, and each row's offset by kind, in file order. */
    record FilmKeys(long id, Side side, List<Long> coarseOffsets, List<Long> denseOffsets) {
    }

    /** Receives what {@link #scan} finds, in file order. */
    private interface Visitor {
        /** A row of a film that has not been seen yet in this file. */
        void film(long id, Side side, Integer fold);

        /** A row of the current film. */
        void row(String kind, long offset, List<String> fields, String where);
    }

    /** Reads {@code features.csv}, one line at a time, and groups its rows by film. */
    static List<FilmRows> read(Path features, boolean look) throws IOException {
        List<FilmRows> films = new ArrayList<>();
        scan(features, look, new Visitor() {
            @Override
            public void film(long id, Side side, Integer fold) {
                films.add(FilmRows.start(id, side, fold));
            }

            @Override
            public void row(String kind, long offset, List<String> fields, String where) {
                add(films.getLast(), kind, offset, TrainRows.features(fields, where));
            }
        });
        return films;
    }

    /** Reads only each row's key, under {@link #read}'s checks, so hundreds of thousands of rows hold just offsets. */
    static List<FilmKeys> keys(Path features, boolean look) throws IOException {
        List<FilmKeys> films = new ArrayList<>();
        scan(features, look, new Visitor() {
            @Override
            public void film(long id, Side side, Integer fold) {
                films.add(new FilmKeys(id, side, new ArrayList<>(), new ArrayList<>()));
            }

            @Override
            public void row(String kind, long offset, List<String> fields, String where) {
                FilmKeys film = films.getLast();
                (kind.equals(FilmCheck.DENSE) ? film.denseOffsets() : film.coarseOffsets()).add(offset);
            }
        });
        return films;
    }

    private static void scan(Path features, boolean look, Visitor visitor) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(features)) {
            if (!FeatureRows.COLUMNS.equals(TrainRows.fields(reader.readLine()))) {
                throw new IllegalArgumentException("features.csv does not start with the features header");
            }
            long[] previous = null;
            long film = 0;
            Side filmSide = null;
            Integer filmFold = null;
            boolean any = false;
            String line;
            int number = 1;
            while ((line = reader.readLine()) != null) {
                number++;
                List<String> row = TrainRows.fields(line);
                if (row.isEmpty()) {
                    continue;
                }
                String where = "features.csv line " + number + ": ";
                if (row.size() != FeatureRows.COLUMNS.size()) {
                    throw new IllegalArgumentException(where + "expected " + FeatureRows.COLUMNS.size()
                        + " fields, found " + row.size());
                }
                Side side = side(row.get(2), look, where);
                Integer fold = fold(row.get(1), side, where);
                previous = TrainRows.checkOrder(row, previous, where);
                if (!any || film != previous[0]) {
                    visitor.film(previous[0], side, fold);
                    film = previous[0];
                    filmSide = side;
                    filmFold = fold;
                    any = true;
                } else if (filmSide != side || !Objects.equals(filmFold, fold)) {
                    throw new IllegalArgumentException(where + "film " + previous[0] + " changes side or fold");
                }
                visitor.row(row.get(3), previous[2], row, where);
            }
        }
    }

    private static Side side(String text, boolean look, String where) {
        if (text.equals(Side.TEST.json())) {
            if (!look) {
                throw new IllegalArgumentException(where
                    + "a test row is refused without --look=L5; nothing was written");
            }
            return Side.TEST;
        }
        if (!text.equals(Side.DEV.json())) {
            throw new IllegalArgumentException(where + "side must be dev or test: " + text);
        }
        return Side.DEV;
    }

    /** A development row's fold, 0 to 4; null for a test row. */
    private static Integer fold(String text, Side side, String where) {
        if (side == Side.TEST) {
            return null;
        }
        if (text.isEmpty()) {
            throw new IllegalArgumentException(where + "a development row with no fold is refused; nothing was "
                + "written");
        }
        long fold = TrainRows.number(text, "fold", where);
        if (fold < 0 || fold >= CreditsTrainJob.FOLDS) {
            throw new IllegalArgumentException(where + "fold must be 0 to " + (CreditsTrainJob.FOLDS - 1) + ": "
                + fold);
        }
        return (int) fold;
    }

    private static void add(FilmRows film, String kind, long offset, double[] features) {
        if (kind.equals(FilmCheck.DENSE)) {
            film.denseOffsets().add(offset);
            film.dense().add(features);
            return;
        }
        film.coarseOffsets().add(offset);
        film.kinds().add(OpeningType.frameKind(features[0], (int) features[P10], (int) features[P50],
            (int) features[P90]));
        film.coarse().add(features);
    }

    /** Reads {@code frame-labels.json}. */
    static Map<Long, FrameLabelRecord> labels(Path file) throws IOException {
        List<FrameLabelRecord> records = MAPPER.readValue(Files.readAllBytes(file),
            new TypeReference<List<FrameLabelRecord>>() {
            });
        Map<Long, FrameLabelRecord> byId = new TreeMap<>();
        for (FrameLabelRecord record : records) {
            if (record == null || byId.put(record.mediaFileId(), record) != null) {
                throw new IllegalArgumentException("frame-labels.json has a null entry or repeats a media file");
            }
        }
        return byId;
    }
}
