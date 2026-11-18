package gregochr.cinefilm.credits.learn;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.function.IntConsumer;
import tools.jackson.core.JacksonException;

/**
 * Entry point of the credits training tool, run on the server as a throwaway container from the released image with
 * {@code -Dloader.main=gregochr.cinefilm.credits.learn.CreditsTrainMain} through {@code PropertiesLauncher}
 * ({@code docs/engineering/credits-classifier-plan.md}, "L3b specification"). A plain class, not a Spring bean. It
 * reads {@code --features}, {@code --summary} and {@code --manifest}, and writes only {@code --out}. It never reads
 * test films and has no flag that allows it.
 *
 * <p>Exit codes: 0 when it ran, 1 on a bad flag, an unreadable input, a refused row or a model that cannot be fitted.
 */
public final class CreditsTrainMain {

    private CreditsTrainMain() {
    }

    /**
     * Runs the tool and exits non-zero, with a one-line message, when it could not run.
     *
     * @param args {@code --features --summary --manifest --out [--trees] [--depth] [--learning-rate] [--bins]
     *     [--min-leaf] [--lambda] [--with-dense] [--lit-text-variant]}
     */
    public static void main(String[] args) {
        CreditsTrainJob job = new CreditsTrainJob(Clock.system(ZoneOffset.UTC), new GitCommit());
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
     * @return 0 when it ran, 1 on a bad flag, an unreadable input or a refused row
     */
    static int run(String[] args, PrintStream out, PrintStream err, CreditsTrainJob job) {
        try {
            job.run(CreditsTrainOptions.parse(args)).forEach(path -> out.println("wrote " + path));
            return 0;
        } catch (IllegalArgumentException | IOException | UncheckedIOException | JacksonException e) {
            err.println("train: " + e.getClass().getSimpleName() + ": " + oneLine(e.getMessage()));
            return 1;
        }
    }

    private static String oneLine(String message) {
        return String.valueOf(message).replaceAll("\\s+", " ");
    }
}
