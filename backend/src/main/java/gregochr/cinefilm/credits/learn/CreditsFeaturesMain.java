package gregochr.cinefilm.credits.learn;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.function.IntConsumer;
import tools.jackson.core.JacksonException;

/**
 * Entry point of the credits features tool, run on the server as a throwaway container from the released image with
 * {@code -Dloader.main=gregochr.cinefilm.credits.learn.CreditsFeaturesMain} through {@code PropertiesLauncher}
 * ({@code docs/engineering/credits-classifier-plan.md}, "{@code FrameLabels} and {@code CreditsFeaturesMain}"). A plain
 * class, not a Spring bean. It reads {@code --data} and {@code --labels} and writes only {@code --out}.
 *
 * <p>Exit codes: 0 when it ran, 1 on a bad flag, an unreadable input or an inconsistent film, 2 when
 * {@code frames/} is missing or holds no film directory.
 */
public final class CreditsFeaturesMain {

    private CreditsFeaturesMain() {
    }

    /**
     * Runs the tool and exits non-zero, with a one-line message, when it could not run.
     *
     * @param args {@code --data --labels --out [--ids]}
     */
    public static void main(String[] args) {
        CreditsFeaturesJob job = new CreditsFeaturesJob(Clock.system(ZoneOffset.UTC), new GitCommit(),
            FilmSource.disk());
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
     * @return 0 when it ran, 1 on a bad flag or an unreadable input, 2 when there were no films
     */
    static int run(String[] args, PrintStream out, PrintStream err, CreditsFeaturesJob job) {
        try {
            job.run(CreditsFeaturesOptions.parse(args)).forEach(path -> out.println("wrote " + path));
            return 0;
        } catch (DatasetMeasureJob.NoFilmsException e) {
            err.println("features: " + oneLine(e.getMessage()));
            return 2;
        } catch (IllegalArgumentException | IOException | UncheckedIOException | JacksonException e) {
            err.println("features: " + e.getClass().getSimpleName() + ": " + oneLine(e.getMessage()));
            return 1;
        }
    }

    private static String oneLine(String message) {
        return String.valueOf(message).replaceAll("\\s+", " ");
    }
}
