package gregochr.cinefilm.credits.learn;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.function.IntConsumer;
import tools.jackson.core.JacksonException;

/**
 * Entry point of the embeddings tool, run on the server as a throwaway container from the released image with
 * {@code -Dloader.main=gregochr.cinefilm.credits.learn.CreditsEmbedMain} through {@code PropertiesLauncher}
 * ({@code docs/engineering/credits-classifier-plan.md}, "{@code CreditsEmbedMain}"). A plain class, not a Spring
 * bean. It reads {@code --data}, {@code --features} and {@code --encoder} and writes only {@code --out}. Test films
 * are embedded only with {@code --look=L5}.
 *
 * <p>Exit codes: 0 when it ran, 1 on a bad flag, a refused input or an encoder that fails; nothing is left behind.
 */
public final class CreditsEmbedMain {

    private static final String HELP = "--help";
    private static final String USAGE = "usage: CreditsEmbedMain --data <dir> --features <features run> "
        + "--encoder <dir> --out <dir> [--look=L5]";

    private CreditsEmbedMain() {
    }

    /**
     * Runs the tool and exits non-zero, with a one-line message, when it could not run.
     *
     * @param args {@code --data --features --encoder --out [--look=L5]}
     */
    public static void main(String[] args) {
        exitOnFailure(run(args, System.out, System.err, job()), System::exit);
    }

    /** The real job: the ONNX encoder, the machine's clocks and the disk. */
    static CreditsEmbedJob job() {
        return new CreditsEmbedJob(Clock.system(ZoneOffset.UTC), new GitCommit(), OnnxImageEncoder::new,
            BenchProbe.system(), FrameSource.disk());
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
     * @return 0 when it ran or {@code --help} was the only argument (the usage goes to {@code out}), 1 when it was
     *     refused or could not run
     */
    static int run(String[] args, PrintStream out, PrintStream err, CreditsEmbedJob job) {
        if (args.length == 1 && HELP.equals(args[0])) {
            out.println(USAGE);
            return 0;
        }
        try {
            job.run(CreditsEmbedOptions.parse(args)).forEach(path -> out.println("wrote " + path));
            return 0;
        } catch (IllegalArgumentException | IllegalStateException | IOException | UncheckedIOException
                 | JacksonException e) {
            err.println("embed: " + e.getClass().getSimpleName() + ": " + oneLine(e.getMessage()));
            return 1;
        }
    }

    private static String oneLine(String message) {
        return String.valueOf(message).replaceAll("\\s+", " ");
    }
}
