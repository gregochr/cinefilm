package gregochr.cinefilm.film;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Whether the parts of one edition are technically alike, so they can be joined into one stream without re-encoding:
 * the same video codec, size and pixel format, and the same audio and subtitle streams in the same order. Every part
 * is compared with the first. Pure: it only reads the files it is given.
 */
public final class PartsMatch {

    private PartsMatch() {
    }

    /**
     * The outcome of a comparison.
     *
     * @param matches true when no part differs from the first
     * @param differences one plain sentence per difference, "Part 2: ..."; empty when they match
     */
    public record Result(boolean matches, List<String> differences) {
    }

    /**
     * Compares the parts, given in play order, with the first one: video codec, width, height, pixel format; audio
     * streams in order (count, codec, channels, language); subtitle streams in order (count, format, language).
     *
     * @param parts the original files of one edition in play order
     * @return whether they match and, if not, how they differ
     */
    public static Result check(List<MediaFile> parts) {
        List<String> differences = new ArrayList<>();
        for (int i = 1; i < parts.size(); i++) {
            compare("Part " + (i + 1), parts.get(0), parts.get(i), differences);
        }
        return new Result(differences.isEmpty(), List.copyOf(differences));
    }

    private static void compare(String label, MediaFile first, MediaFile other, List<String> out) {
        field(label, "video codec", first.getVideoCodec(), other.getVideoCodec(), out);
        field(label, "width", first.getWidth(), other.getWidth(), out);
        field(label, "height", first.getHeight(), other.getHeight(), out);
        field(label, "pixel format", first.getPixelFormat(), other.getPixelFormat(), out);
        List<AudioStream> audioA = first.getAudioStreams().stream()
            .sorted(Comparator.comparingInt(AudioStream::getIndex)).toList();
        List<AudioStream> audioB = other.getAudioStreams().stream()
            .sorted(Comparator.comparingInt(AudioStream::getIndex)).toList();
        if (audioA.size() != audioB.size()) {
            out.add(label + ": " + audioB.size() + " audio streams, part 1 has " + audioA.size());
        } else {
            for (int i = 0; i < audioA.size(); i++) {
                String audio = "audio stream " + (i + 1);
                field(label, audio + " codec", audioA.get(i).getCodec(), audioB.get(i).getCodec(), out);
                field(label, audio + " channels", audioA.get(i).getChannels(), audioB.get(i).getChannels(), out);
                field(label, audio + " language", audioA.get(i).getLanguage(), audioB.get(i).getLanguage(), out);
            }
        }
        List<SubtitleStream> subsA = first.getSubtitleStreams().stream()
            .sorted(Comparator.comparingInt(SubtitleStream::getIndex)).toList();
        List<SubtitleStream> subsB = other.getSubtitleStreams().stream()
            .sorted(Comparator.comparingInt(SubtitleStream::getIndex)).toList();
        if (subsA.size() != subsB.size()) {
            out.add(label + ": " + subsB.size() + " subtitle streams, part 1 has " + subsA.size());
        } else {
            for (int i = 0; i < subsA.size(); i++) {
                String subtitle = "subtitle stream " + (i + 1);
                field(label, subtitle + " format", subsA.get(i).getFormat(), subsB.get(i).getFormat(), out);
                field(label, subtitle + " language", subsA.get(i).getLanguage(), subsB.get(i).getLanguage(), out);
            }
        }
    }

    private static void field(String label, String what, Object first, Object other, List<String> out) {
        if (!Objects.equals(first, other)) {
            out.add(label + ": " + what + " is " + other + ", part 1 has " + first);
        }
    }
}
