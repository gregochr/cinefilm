package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.CreditsFrameReader;
import gregochr.cinefilm.library.ProcessCommandRunner;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.function.IntConsumer;
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
        String tmp = System.getProperty("java.io.tmpdir");
        AutoCheckJob job = new AutoCheckJob(Clock.system(ZoneOffset.UTC),
            new FfmpegStillSource(new CreditsFrameReader(new ProcessCommandRunner(), "ffmpeg", 0, tmp)));
        exitOnFailure(run(args, System.out, System.err, job), System::exit);
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
