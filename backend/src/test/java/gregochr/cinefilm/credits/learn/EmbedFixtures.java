package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.RgbFrame;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * A fixture dataset and features runs for the embeddings tool: each film has {@code coarse} frames at
 * {@code 100000 + 5000 i} and {@code dense} frames at {@code 98000 + 500 j}, every one a flat 224 by 224 colour that
 * {@link #colour} names, stored as a JPEG, with a {@code complete.json} beside them.
 */
final class EmbedFixtures {

    static final String FEATURES_RUN = "features-20240101T000000Z";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private EmbedFixtures() {
    }

    /** A film of the fixture. */
    record Film(long id, Side side, int coarse, int dense) {

        static Film dev(long id, int coarse, int dense) {
            return new Film(id, Side.DEV, coarse, dense);
        }

        static Film test(long id, int coarse, int dense) {
            return new Film(id, Side.TEST, coarse, dense);
        }

        List<Long> coarseOffsets() {
            return FeaturesFixtures.steps(100_000, 5_000, coarse);
        }

        List<Long> denseOffsets() {
            return FeaturesFixtures.steps(98_000, 500, dense);
        }
    }

    /** The red, green and blue of one frame, so that every film, kind and index differs. */
    static int[] colour(long id, boolean dense, int index) {
        return new int[] {(int) (id * 30 + index * 7) % 200 + 20, (dense ? 100 : 0) + (int) id * 10, 10 + index * 5};
    }

    /** Writes each film's directory under {@code data/frames}. */
    static void dataset(Path data, List<Film> films) throws IOException {
        for (Film film : films) {
            Path dir = Files.createDirectories(data.resolve("frames").resolve(Long.toString(film.id())));
            jpegs(dir, "coarse", film, film.coarseOffsets(), false);
            jpegs(dir, "dense", film, film.denseOffsets(), true);
            DatasetComplete.write(dir, FeaturesFixtures.complete(film.id(), FeaturesFixtures.FINGERPRINT,
                film.coarseOffsets(), film.denseOffsets()));
        }
    }

    private static void jpegs(Path dir, String kind, Film film, List<Long> offsets, boolean dense)
            throws IOException {
        Files.createDirectories(dir.resolve(kind));
        for (int i = 0; i < offsets.size(); i++) {
            int[] rgb = colour(film.id(), dense, i);
            byte[] pixels = new byte[FramePrep.COLOUR_SIZE * FramePrep.COLOUR_SIZE * 3];
            for (int p = 0; p < pixels.length; p += 3) {
                pixels[p] = (byte) rgb[0];
                pixels[p + 1] = (byte) rgb[1];
                pixels[p + 2] = (byte) rgb[2];
            }
            Files.write(dir.resolve(kind).resolve(offsets.get(i) + ".jpg"),
                FramePrep.jpeg(new RgbFrame(FramePrep.COLOUR_SIZE, FramePrep.COLOUR_SIZE, pixels)));
        }
    }

    /** Writes a features run (with {@code look.json} when {@code look}) for the films, in ascending id order. */
    static Path featuresRun(Path out, String name, Path data, List<Film> films, boolean look) throws IOException {
        Path run = Files.createDirectories(out.resolve(name));
        StringBuilder csv = new StringBuilder(FeatureRows.header());
        List<FrameLabelRecord> records = new ArrayList<>();
        MessageDigest dataset = digest();
        for (Film film : films) {
            Integer fold = film.side() == Side.DEV ? 2 : null;
            for (long offset : film.coarseOffsets()) {
                csv.append(FeatureRows.row(film.id(), fold, film.side(), "coarse", offset, "film",
                    TrainFixtures.lit(1, 10, 0)));
            }
            for (long offset : film.denseOffsets()) {
                csv.append(FeatureRows.row(film.id(), fold, film.side(), "dense", offset, "film",
                    TrainFixtures.noTime(TrainFixtures.lit(1, 10, 0))));
            }
            records.add(new FrameLabelRecord(film.id(), FeaturesFixtures.FINGERPRINT, "unit-" + film.id(),
                film.side(), fold, null, null, null, List.of(), List.of(), List.of(), null, null, null, null));
            if (film.side() == Side.DEV) {
                dataset.update(Files.readAllBytes(data.resolve("frames").resolve(Long.toString(film.id()))
                    .resolve("complete.json")));
            }
        }
        Files.writeString(run.resolve("features.csv"), csv);
        Files.write(run.resolve("frame-labels.json"), MAPPER.writeValueAsBytes(records));
        Files.writeString(run.resolve("summary.json"), "{\"featureVersion\": " + CreditsFeatures.FEATURE_VERSION
            + ", \"datasetSha256\": \"" + HexFormat.of().formatHex(dataset.digest())
            + "\", \"labelsSha256\": \"" + "ab".repeat(32) + "\"}");
        if (look) {
            Files.writeString(run.resolve("look.json"), "{}");
        }
        return run;
    }

    /** The SHA-256 of a file's bytes from {@code from} on. */
    static String sha256(Path file, int from) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        return HexFormat.of().formatHex(digest().digest(Arrays.copyOfRange(bytes, from, bytes.length)));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
