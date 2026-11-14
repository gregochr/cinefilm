package gregochr.cinefilm.credits;

import gregochr.cinefilm.library.CommandRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;

/**
 * A stand-in for ffmpeg: records every command and its timeout, and writes the PGM files the command asks for, the
 * picture of each coming from a function of the offset it was asked to take.
 */
public final class FakeFfmpeg implements CommandRunner {

    public final List<List<String>> commands = new ArrayList<>();
    public final List<Duration> timeouts = new ArrayList<>();
    /** The directory the last command wrote into. */
    public Path lastDirectory;
    /** The picture at an offset in milliseconds. */
    public LongFunction<GreyFrame> frameAt = ms -> SyntheticFrames.picture();
    /** The 1-based call that fails (0 for none). */
    public int failOnCall;
    /** What the failing call does: throw this... */
    public IOException ioFailure;
    /** ...or return this. */
    public Result failure = new Result(1, "", false);
    /** When set, the files are not written. */
    public boolean writeNothing;
    /** When set, this is written instead of a picture. */
    public byte[] garbage;
    /** How many dense frames to write; -1 for as many as were asked for. */
    public int denseCount = -1;
    /** The index of the dense frame written as garbage (-1 for none). */
    public int corruptDenseIndex = -1;
    /** When set, the failing call writes its frames first, as ffmpeg may before it exits with an error. */
    public boolean writeBeforeFailing;

    /** The colour picture at an offset in milliseconds, for commands that write {@code .ppm} files. */
    public LongFunction<RgbFrame> rgbAt = ms -> flat(10, 20, 30);

    /** A 4x2 frame of one colour. */
    public static RgbFrame flat(int red, int green, int blue) {
        byte[] pixels = new byte[4 * 2 * 3];
        for (int i = 0; i < pixels.length; i += 3) {
            pixels[i] = (byte) red;
            pixels[i + 1] = (byte) green;
            pixels[i + 2] = (byte) blue;
        }
        return new RgbFrame(4, 2, pixels);
    }

    /** The bytes of a binary PPM, as ffmpeg writes one. */
    public static byte[] ppm(RgbFrame frame) {
        byte[] header = ("P6\n" + frame.width() + " " + frame.height() + "\n255\n").getBytes(StandardCharsets.US_ASCII);
        byte[] data = new byte[header.length + frame.width() * frame.height() * 3];
        System.arraycopy(header, 0, data, 0, header.length);
        int at = header.length;
        for (int y = 0; y < frame.height(); y++) {
            for (int x = 0; x < frame.width(); x++) {
                int rgb = frame.rgb(x, y);
                data[at++] = (byte) (rgb >> 16);
                data[at++] = (byte) (rgb >> 8);
                data[at++] = (byte) rgb;
            }
        }
        return data;
    }

    /** The bytes of a binary PGM, as ffmpeg writes one. */
    public static byte[] pgm(GreyFrame frame) {
        byte[] header = ("P5\n" + frame.width() + " " + frame.height() + "\n255\n").getBytes(StandardCharsets.US_ASCII);
        byte[] data = new byte[header.length + frame.width() * frame.height()];
        System.arraycopy(header, 0, data, 0, header.length);
        for (int y = 0; y < frame.height(); y++) {
            for (int x = 0; x < frame.width(); x++) {
                data[header.length + y * frame.width() + x] = (byte) frame.luma(x, y);
            }
        }
        return data;
    }

    @Override
    public Result run(List<String> command, Duration timeout) throws IOException {
        commands.add(command);
        timeouts.add(timeout);
        if (commands.size() == failOnCall) {
            if (ioFailure != null) {
                throw ioFailure;
            }
            if (writeBeforeFailing) {
                writeFrames(command);
            }
            return failure;
        }
        lastDirectory = Path.of(command.getLast()).getParent();
        if (!writeNothing) {
            writeFrames(command);
        }
        return new Result(0, "", false);
    }

    private void writeFrames(List<String> command) throws IOException {
        Path output = Path.of(command.getLast());
        long seekMs = Math.round(Double.parseDouble(command.get(command.indexOf("-ss") + 1)) * 1000);
        boolean colour = command.getLast().endsWith(".ppm");
        if (command.getLast().contains("%05d")) {
            int asked = Integer.parseInt(command.get(command.indexOf("-frames:v") + 1));
            int count = denseCount < 0 ? asked : denseCount;
            for (int i = 0; i < count; i++) {
                Path frame = output.getParent().resolve(output.getFileName().toString().formatted(i));
                long at = seekMs + 500L * i;
                Files.write(frame, i == corruptDenseIndex ? "not a picture".getBytes(StandardCharsets.US_ASCII)
                    : colour ? ppm(rgbAt.apply(at)) : pgm(frameAt.apply(at)));
            }
        } else {
            Files.write(output, garbage != null ? garbage
                : colour ? ppm(rgbAt.apply(seekMs)) : pgm(frameAt.apply(seekMs)));
        }
    }
}
