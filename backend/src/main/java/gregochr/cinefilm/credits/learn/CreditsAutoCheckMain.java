package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.CreditsFrameReader;
import gregochr.cinefilm.library.ProcessCommandRunner;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;

/**
 * Entry point of the sheet tool (G1 of {@code docs/engineering/credits-3c-auto-plan.md}), run on the server as a
 * throwaway container from the released image with
 * {@code -Dloader.main=gregochr.cinefilm.credits.learn.CreditsAutoCheckMain} through {@code PropertiesLauncher}
 * ({@code docs/engineering/operations.md}, "Credits AUTO"). It is a plain class, not a Spring bean, and nothing in
 * the server calls it. It reads the candidates file and the media files and writes only {@code --out}.
 *
 * <p>Exit codes: 0 when it ran (a file it could not read is listed, not fatal), 1 on a bad flag, an unreadable
 * candidates file, a run directory that already exists, or a {@code --full} that names no still it can write.
 */
public final class CreditsAutoCheckMain {

    private CreditsAutoCheckMain() {
    }

    /**
     * Runs the tool and exits non-zero, with a one-line message, when it could not run.
     *
     * @param args {@code --candidates --out [--media] [--full <editionId> <tile>]}
     */
    public static void main(String[] args) {
        int code = runInWorkDir(args, System.out, System.err, Path.of(System.getProperty("java.io.tmpdir")),
            work -> new AutoCheckJob(Clock.system(ZoneOffset.UTC),
                new FfmpegStillSource(new CreditsFrameReader(new ProcessCommandRunner(), "ffmpeg", 0,
                    work.toString()))));
        exitOnFailure(code, System::exit);
    }

    /**
     * Runs the tool with a private work directory for the frame reader, made with {@code createTempDirectory} (owner
     * only on POSIX, so nothing it holds is readable by other users) and deleted at the end, best effort.
     *
     * @param args the command line
     * @param out where the written paths go
     * @param err where the one-line failure message goes
     * @param parent where the work directory is made
     * @param jobFor makes the job, given the work directory
     * @return as {@link #run}, or 1 when the work directory cannot be made
     */
    static int runInWorkDir(String[] args, PrintStream out, PrintStream err, Path parent,
                            Function<Path, AutoCheckJob> jobFor) {
        Path work;
        try {
            work = Files.createTempDirectory(parent, "cinefilm-auto-check");
        } catch (IOException e) {
            err.println("auto-check: " + e.getClass().getSimpleName() + ": could not make a work directory");
            return 1;
        }
        try {
            return run(args, out, err, jobFor.apply(work));
        } finally {
            deleteTree(work);
        }
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> tree = Files.walk(root)) {
            for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | UncheckedIOException e) {
            // Best effort: the run's result stands.
        }
    }

    /** Calls {@code exit} with the code unless it is 0. */
    static void exitOnFailure(int code, IntConsumer exit) {
        if (code != 0) {
            exit.accept(code);
        }
    }

    /**
     * Runs the tool.
     *
     * @param args the command line
     * @param out where the written paths go, one per line
     * @param err where the one-line failure message goes
     * @param job the job to run
     * @return 0 when it ran, 1 on a bad flag, an unreadable input or a refused run directory
     */
    static int run(String[] args, PrintStream out, PrintStream err, AutoCheckJob job) {
        try {
            job.run(AutoCheckOptions.parse(args)).forEach(path -> out.println("wrote " + path));
            return 0;
        } catch (IllegalArgumentException | IOException | UncheckedIOException | JacksonException e) {
            err.println("auto-check: " + e.getClass().getSimpleName() + ": "
                + String.valueOf(e.getMessage()).replaceAll("\\s+", " "));
            return 1;
        }
    }
}
