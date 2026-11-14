package gregochr.cinefilm.credits.learn;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.function.IntConsumer;
import tools.jackson.core.JacksonException;

/**
 * Entry point of the labels tool, run on the owner's Mac from the built jar with
 * {@code -Dloader.main=gregochr.cinefilm.credits.learn.LabelsMain} through {@code PropertiesLauncher}. It is a plain
 * class, not a Spring bean, and nothing in the server calls it.
 */
public final class LabelsMain {

    private LabelsMain() {
    }

    /**
     * Runs the tool and exits non-zero, with a one-line message, on a bad flag or an unreadable input.
     *
     * @param args {@code --archive --export --preseed --prefix --date --out [--previous-split] [--exclusions]}
     */
    public static void main(String[] args) {
        exitOnFailure(run(args, System.out, System.err), System::exit);
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
     * @param out where the written file names go
     * @param err where the one-line failure message goes
     * @return 0 when the three files were written, 1 otherwise
     */
    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            List<Path> written = LabelsJob.run(LabelsOptions.parse(args));
            written.forEach(path -> out.println("wrote " + path));
            return 0;
        } catch (IllegalArgumentException | IOException | JacksonException e) {
            err.println("labels: " + e.getClass().getSimpleName() + ": "
                + String.valueOf(e.getMessage()).replaceAll("\\s+", " "));
            return 1;
        }
    }
}
