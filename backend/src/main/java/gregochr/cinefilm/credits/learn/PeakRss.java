package gregochr.cinefilm.credits.learn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The process's peak resident set size, {@code VmHWM} in Linux's {@code /proc/self/status}. */
final class PeakRss {

    private static final Pattern HWM = Pattern.compile("^VmHWM:\\s*(\\d+)\\s*kB\\s*$", Pattern.MULTILINE);

    private PeakRss() {
    }

    /**
     * Reads the peak from the text of a status file.
     *
     * @param status the file's text
     * @return the {@code VmHWM} value in kB, or empty when there is no such line or its value is not a number of kB
     */
    static OptionalLong parse(String status) {
        Matcher matcher = HWM.matcher(status);
        if (!matcher.find()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(matcher.group(1)));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /**
     * Reads the peak from a status file.
     *
     * @param status the file, normally {@code /proc/self/status}
     * @return the {@code VmHWM} value in kB, or empty when the file is missing or unreadable (the Mac has none)
     */
    static OptionalLong read(Path status) {
        try {
            return parse(Files.readString(status));
        } catch (IOException e) {
            return OptionalLong.empty();
        }
    }
}
