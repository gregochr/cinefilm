package gregochr.cinefilm.credits.learn;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.function.IntConsumer;
import tools.jackson.core.JacksonException;

/**
 * Entry point of the credits evaluation tool, run on the server as a throwaway container from the released image with
 * {@code -Dloader.main=gregochr.cinefilm.credits.learn.CreditsEvaluateMain} through {@code PropertiesLauncher}
 * ({@code docs/engineering/credits-classifier-plan.md}, "L3c specification"). A plain class, not a Spring bean. It
 * reads a features run and one or more training runs, and writes only {@code --out}. It refuses test rows;
 * {@code --look} and {@code --baseline} are not supported yet (L3c-eval-2b).
 *
 * <p>Exit codes: 0 when it ran, 1 on a bad flag, an unreadable input or a refused one (nothing is written).
 */
public final class CreditsEvaluateMain {

    private CreditsEvaluateMain() {
    }

    /** Runs the tool and exits non-zero, with a one-line message, when it could not run. */
    public static void main(String[] args) {
        CreditsEvaluateJob job = new CreditsEvaluateJob(Clock.system(ZoneOffset.UTC), new GitCommit());
        exitOnFailure(run(args, System.out, System.err, job), System::exit);
    }

    /** Calls {@code exit} with the code unless it is 0. */
    static void exitOnFailure(int code, IntConsumer exit) {
        if (code != 0) {
            exit.accept(code);
        }
    }

    /** Runs the tool. */
    static int run(String[] args, PrintStream out, PrintStream err, CreditsEvaluateJob job) {
        try {
            job.run(CreditsEvaluateOptions.parse(args)).forEach(path -> out.println("wrote " + path));
            return 0;
        } catch (IllegalArgumentException | IOException | UncheckedIOException | JacksonException e) {
            err.println("evaluate: " + e.getClass().getSimpleName() + ": " + oneLine(e.getMessage()));
            return 1;
        }
    }

    private static String oneLine(String message) {
        return String.valueOf(message).replaceAll("\\s+", " ");
    }
}
