package gregochr.cinefilm.credits.learn;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.function.IntConsumer;
import tools.jackson.core.JacksonException;

/**
 * Entry point of the encoder benchmark, run on the server as a throwaway container from the released image with
 * {@code -Dloader.main=gregochr.cinefilm.credits.learn.CreditsEncoderBenchMain} through {@code PropertiesLauncher}
 * and {@code java -Xmx512m} ({@code docs/engineering/credits-classifier-plan.md}, "The benchmark"). A plain class,
 * not a Spring bean. It reads {@code --encoder} and writes only {@code --out}.
 *
 * <p>Exit codes: 0 when it ran, 1 on a bad flag, a refused encoder description, an encoder that fails to open or
 * {@code --out} inside the encoder's directory.
 */
public final class CreditsEncoderBenchMain {

    private static final String HELP = "--help";
    private static final String USAGE = "usage: CreditsEncoderBenchMain --encoder <dir> --out <dir> "
        + "[--frames 1-10000, default 520] [--warmup 0-1000, default 16]";

    private CreditsEncoderBenchMain() {
    }

    /**
     * Runs the tool and exits non-zero, with a one-line message, when it could not run.
     *
     * @param args {@code --encoder --out [--frames] [--warmup]}
     */
    public static void main(String[] args) {
        exitOnFailure(run(args, System.out, System.err, job()), System::exit);
    }

    /** The real job: the ONNX encoder, the machine's clocks and Linux's {@code /proc/self/status}. */
    static CreditsEncoderBenchJob job() {
        return new CreditsEncoderBenchJob(Clock.system(ZoneOffset.UTC), new GitCommit(), OnnxImageEncoder::new,
            BenchProbe.system(), Path.of("/proc/self/status"));
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
    static int run(String[] args, PrintStream out, PrintStream err, CreditsEncoderBenchJob job) {
        if (args.length == 1 && HELP.equals(args[0])) {
            out.println(USAGE);
            return 0;
        }
        try {
            job.run(CreditsEncoderBenchOptions.parse(args)).forEach(path -> out.println("wrote " + path));
            return 0;
        } catch (IllegalArgumentException | IllegalStateException | IOException | UncheckedIOException
                 | JacksonException e) {
            err.println("bench: " + e.getClass().getSimpleName() + ": " + oneLine(e.getMessage()));
            return 1;
        }
    }

    private static String oneLine(String message) {
        return String.valueOf(message).replaceAll("\\s+", " ");
    }
}
