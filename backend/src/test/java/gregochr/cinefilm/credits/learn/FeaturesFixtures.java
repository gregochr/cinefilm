package gregochr.cinefilm.credits.learn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Small synthetic datasets for the credits features tool's tests: 32 by 32 frames as ffmpeg wrote them, so 16 by 16
 * grey frames, one flat grey level each, and 4 by 4 colour frames. The whole frame is the picture area, so the crop is
 * the whole grey frame.
 */
final class FeaturesFixtures {

    static final String FINGERPRINT = "1000:1";
    /** Offsets 100 000 to 135 000 ms, every 5 s. */
    static final List<Long> COARSE = steps(100_000, 5_000, 8);
    /** Levels of {@link #COARSE}: lit, dark, dark, lit, lit, lit, lit, lit. */
    static final List<Integer> COARSE_LEVELS = List.of(60, 2, 2, 60, 60, 60, 60, 60);
    /** 25 offsets, 98 000 ms to 110 000 ms, every 500 ms. */
    static final List<Long> DENSE = steps(98_000, 500, 25);
    static final long DURATION_MS = 140_000;
    static final long WINDOW_START_MS = 100_000;
    static final long TARGET_MS = 104_000;
    static final int GREY_SIDE = 16;

    private static final DatasetComplete.Settings SETTINGS = new DatasetComplete.Settings("filter", "rgb24", "gray",
        32, GREY_SIDE, 4, 0.95f, 5_000, 2, 30_000, 60_000, 19);

    private FeaturesFixtures() {
    }

    static List<Long> steps(long first, long step, int count) {
        List<Long> offsets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            offsets.add(first + i * step);
        }
        return offsets;
    }

    /** The levels {@code first}, {@code first + 1}, ... : frame {@code i} is {@code first + i}. */
    static List<Integer> ramp(int first, int count) {
        List<Integer> levels = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            levels.add(first + i);
        }
        return levels;
    }

    /** What {@code complete.json} says for a film of this fixture. */
    static DatasetComplete complete(long id, String fingerprint, List<Long> coarse, List<Long> dense) {
        return new DatasetComplete(DatasetComplete.FORMAT, id, fingerprint, DURATION_MS, TARGET_MS,
            new DatasetComplete.Window(WINDOW_START_MS, DURATION_MS), new DatasetComplete.Frame(32, 32),
            new DatasetComplete.Area(0, 0, 32, 32), List.of(1L), coarse, dense, "ffmpeg 7", "25.0.1", "abc", SETTINGS,
            "2026-11-10T12:10:00Z");
    }

    /** Writes a film's frames and its {@code complete.json}; returns the directory. */
    static Path film(Path frames, String dirName, DatasetComplete complete, List<Integer> coarseLevels,
                     List<Integer> denseLevels, int greyHeight) throws IOException {
        Path dir = Files.createDirectories(frames.resolve(dirName));
        write(dir, "coarse", complete.coarse(), coarseLevels, greyHeight);
        write(dir, "dense", complete.dense(), denseLevels, greyHeight);
        DatasetComplete.write(dir, complete);
        return dir;
    }

    /** The ok film of the smoke test: levels as the constants above, dense levels 10, 11, 12 ... */
    static Path okFilm(Path frames, String dirName, long id) throws IOException {
        return film(frames, dirName, complete(id, FINGERPRINT, COARSE, DENSE), COARSE_LEVELS, ramp(10, DENSE.size()),
            GREY_SIDE);
    }

    private static void write(Path dir, String kind, List<Long> offsets, List<Integer> levels, int greyHeight)
        throws IOException {
        Path folder = Files.createDirectories(dir.resolve(kind));
        for (int i = 0; i < offsets.size(); i++) {
            Files.write(folder.resolve(offsets.get(i) + ".png"),
                FramePrep.png(MeasureFixtures.grey(GREY_SIDE, greyHeight, levels.get(i))));
            Files.write(folder.resolve(offsets.get(i) + ".jpg"),
                FramePrep.jpeg(MeasureFixtures.colour(4, levels.get(i))));
        }
    }

    /**
     * A development label for the ok film: credits {@code [104 000, 140 000)}, film {@code [100 000, 104 000)} and an
     * empty {@code left_out}. With {@link #COARSE_LEVELS}, {@link FrameLabels} finds 105 s and 110 s dark and 115 s to
     * 135 s lit, so the stretch from 115 s is removed as {@code LIT_RUN} and the credits are {@code [104 000,
     * 115 000)}.
     *
     * @param id the media file id
     * @param fingerprint the fingerprint it holds
     */
    static LabelRecord label(long id, String fingerprint) {
        return new LabelRecord(id, fingerprint, "unit-" + id, Side.DEV, 2, TARGET_MS, 0L, false,
            List.of(new Span(TARGET_MS, DURATION_MS)), List.of(new Span(WINDOW_START_MS, TARGET_MS)), List.of(), null,
            null);
    }
}
