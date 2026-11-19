package gregochr.cinefilm.credits.learn;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.function.IntConsumer;
import tools.jackson.core.JacksonException;

/**
 * Entry point of model B's training tool, run on the server as a throwaway container from the released image with
 * {@code -Dloader.main=gregochr.cinefilm.credits.learn.CreditsTrainLrMain} through {@code PropertiesLauncher}
 * ({@code docs/engineering/credits-classifier-plan.md}, "{@code CreditsTrainLrMain}"). A plain class, not a Spring
 * bean. It reads {@code --features}, {@code --summary}, {@code --manifest} and {@code --embeddings}, and writes only
 * {@code --out}. It never reads test films and has no flag that allows it.
 *
 * <p>Exit codes: 0 when it ran, 1 on a bad flag, an unreadable or mismatched input, a refused row or a model that
 * cannot be fitted.
 */
public final class CreditsTrainLrMain {

    private CreditsTrainLrMain() {
    }

    /**
     * Runs the tool and exits non-zero, with a one-line message, when it could not run.
     *
     * @param args {@code --features --summary --manifest --embeddings --out [--l2] [--max-iterations]
     *     [--tolerance] [--with-dense] [--lit-text-variant]}
     */
    public static void main(String[] args) {
        CreditsTrainLrJob job = new CreditsTrainLrJob(Clock.system(ZoneOffset.UTC), new GitCommit());
        execute(args, System.out, System.err, job, System::exit);
    }

    /** Runs the tool and passes {@code exit} the code unless it is 0; the seam that lets a test see the exit. */
    static void execute(String[] args, PrintStream out, PrintStream err, CreditsTrainLrJob job, IntConsumer exit) {
        exitOnFailure(run(args, out, err, job), exit);
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
    static int run(String[] args, PrintStream out, PrintStream err, CreditsTrainLrJob job) {
        try {
            job.run(CreditsTrainLrOptions.parse(args)).forEach(path -> out.println("wrote " + path));
            return 0;
        } catch (IllegalArgumentException | IOException | UncheckedIOException | JacksonException e) {
            err.println("train-lr: " + e.getClass().getSimpleName() + ": " + oneLine(e.getMessage()));
            return 1;
        }
    }

    private static String oneLine(String message) {
        return String.valueOf(message).replaceAll("\\s+", " ");
    }
}
