package gregochr.cinefilm.credits.learn;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.function.IntConsumer;
import tools.jackson.core.JacksonException;

/**
 * Entry point of the dataset measurement tool, run on the server as a throwaway container from the released image with
 * {@code -Dloader.main=gregochr.cinefilm.credits.learn.DatasetMeasureMain} through {@code PropertiesLauncher}
 * ({@code docs/engineering/credits-classifier-plan.md}, "Measuring (L2b-2)"). It is a plain class, not a Spring bean,
 * and nothing in the server calls it. It reads only {@code --data} and writes only {@code --out}.
 *
 * <p>Exit codes: 0 when it ran (even if films are damaged: the report says so), 1 on a bad flag or an unreadable
 * {@code --data}, 2 when {@code frames/} is missing or holds no film directory.
 */
public final class DatasetMeasureMain {

    private DatasetMeasureMain() {
    }

    /**
     * Runs the tool and exits non-zero, with a one-line message, when it could not run.
     *
     * @param args {@code --data --out [--ids] [--stills] [--project]}
     */
    public static void main(String[] args) {
        DatasetMeasureJob job = new DatasetMeasureJob(Clock.system(ZoneOffset.UTC), new GitCommit());
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
    static int run(String[] args, PrintStream out, PrintStream err, DatasetMeasureJob job) {
        try {
            DatasetMeasureOptions options = DatasetMeasureOptions.parse(args);
            if (options.look() != null) {
                err.println("measure: LOOK " + options.look() + ": THE TEST FILMS ARE INCLUDED IN THIS RUN."
                    + " This is one of the two looks the plan allows (L5, the gate); summary.json records it.");
            }
            job.run(options).forEach(path -> out.println("wrote " + path));
            return 0;
        } catch (DatasetMeasureJob.NoFilmsException e) {
            err.println("measure: " + oneLine(e.getMessage()));
            return 2;
        } catch (IllegalArgumentException | IOException | UncheckedIOException | JacksonException e) {
            err.println("measure: " + e.getClass().getSimpleName() + ": " + oneLine(e.getMessage()));
            return 1;
        }
    }

    private static String oneLine(String message) {
        return String.valueOf(message).replaceAll("\\s+", " ");
    }
}
