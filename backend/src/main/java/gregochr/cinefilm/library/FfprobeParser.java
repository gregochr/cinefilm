package gregochr.cinefilm.library;

import gregochr.cinefilm.library.ProbeResult.Audio;
import gregochr.cinefilm.library.ProbeResult.Subtitle;
import gregochr.cinefilm.library.ProbeResult.Tags;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns ffprobe's JSON ({@code -print_format json -show_format -show_streams}) into a {@link ProbeResult}. The JSON
 * comes from a file we do not control, so every field is optional and nothing is trusted to be present.
 */
public final class FfprobeParser {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> DRM_TAGS = Set.of("drmi", "drms", "drmp");
    private static final Set<String> TEXT_SUBTITLES = Set.of("mov_text", "tx3g", "subrip", "srt", "ass", "ssa",
        "webvtt", "text");
    private static final Set<String> SAFARI_AUDIO = Set.of("aac", "ac3", "eac3", "alac", "mp3", "flac", "opus");
    private static final Map<String, String> AUDIO_NAMES = Map.of("aac", "AAC", "ac3", "AC-3", "eac3", "E-AC-3",
        "dts", "DTS", "truehd", "TrueHD", "alac", "ALAC", "mp3", "MP3");
    private static final Map<String, String> VIDEO_NAMES = Map.of("h264", "H.264", "hevc", "HEVC", "mpeg4",
        "MPEG-4", "mpeg2video", "MPEG-2", "vp9", "VP9", "av1", "AV1");
    private static final Map<String, String> LANGUAGES = languageNames();
    /** The "bibliographic" ISO 639-2 codes that differ from the terminologic ones Java knows. */
    private static final Map<String, String> BIBLIOGRAPHIC = Map.of("fre", "fra", "ger", "deu", "chi", "zho",
        "dut", "nld", "gre", "ell", "cze", "ces", "rum", "ron", "per", "fas", "ice", "isl", "wel", "cym");
    private static final Pattern YEAR = Pattern.compile("^\\s*(\\d{4})");

    private FfprobeParser() {
    }

    /**
     * Parses ffprobe output.
     *
     * @param json ffprobe's standard output
     * @return what was found
     * @throws ProbeException when the text is not ffprobe JSON
     */
    public static ProbeResult parse(String json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JacksonException e) {
            throw new ProbeException("ffprobe output is not JSON", e);
        }
        if (!root.isObject() || !root.has("format")) {
            throw new ProbeException("ffprobe output has no format section", null);
        }
        JsonNode format = root.path("format");
        List<Audio> audio = new ArrayList<>();
        List<Subtitle> subtitles = new ArrayList<>();
        String videoCodec = null;
        Integer width = null;
        Integer height = null;
        Long videoBitrate = null;
        boolean protectedContent = false;
        for (JsonNode stream : root.path("streams")) {
            String type = text(stream, "codec_type");
            String codec = text(stream, "codec_name");
            int index = stream.path("index").asInt(0);
            if ("video".equals(type) && !isCoverArt(stream)) {
                protectedContent |= isProtected(stream);
                if (videoCodec == null) {
                    videoCodec = codec == null ? null : VIDEO_NAMES.getOrDefault(codec, codec.toUpperCase(Locale.ROOT));
                    width = integer(stream, "width");
                    height = integer(stream, "height");
                    videoBitrate = number(stream, "bit_rate");
                }
            } else if ("audio".equals(type)) {
                protectedContent |= isProtected(stream);
                audio.add(audio(stream, index, codec));
            } else if ("subtitle".equals(type) && codec != null && TEXT_SUBTITLES.contains(codec)) {
                subtitles.add(new Subtitle(index, language(stream), flag(stream.path("disposition"), "forced"),
                    text(stream.path("tags"), "title"), codec));
            }
        }
        Long bitrate = number(format, "bit_rate");
        return new ProbeResult(durationMs(format, root), container(text(format, "format_name")), videoCodec, width,
            height, bitrate != null ? bitrate : videoBitrate, List.copyOf(audio), List.copyOf(subtitles),
            tags(format.path("tags")), protectedContent);
    }

    private static Audio audio(JsonNode stream, int index, String codec) {
        String name = codec == null ? null : AUDIO_NAMES.getOrDefault(codec, codec.toUpperCase(Locale.ROOT));
        return new Audio(index, language(stream), channels(stream.path("channels").asInt(0)), name,
            text(stream.path("tags"), "title"), flag(stream.path("disposition"), "default"),
            codec != null && SAFARI_AUDIO.contains(codec));
    }

    private static boolean isCoverArt(JsonNode stream) {
        return flag(stream.path("disposition"), "attached_pic");
    }

    private static boolean isProtected(JsonNode stream) {
        String tag = text(stream, "codec_tag_string");
        return tag != null && DRM_TAGS.contains(tag.toLowerCase(Locale.ROOT)) || truthy(stream.path("encrypted"))
            || truthy(stream.path("tags").path("encrypted"));
    }

    private static boolean truthy(JsonNode node) {
        String value = node.asString();
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    private static boolean flag(JsonNode disposition, String name) {
        return disposition.path(name).asInt(0) == 1;
    }

    private static String channels(int count) {
        return switch (count) {
            case 0 -> null;
            case 1 -> "Mono";
            case 2 -> "Stereo";
            case 6 -> "5.1";
            case 8 -> "7.1";
            default -> count + " ch";
        };
    }

    private static String language(JsonNode stream) {
        String code = text(stream.path("tags"), "language");
        if (code == null || Set.of("und", "zxx", "mis", "mul").contains(code.toLowerCase(Locale.ROOT))) {
            return null;
        }
        String lower = code.toLowerCase(Locale.ROOT);
        return LANGUAGES.getOrDefault(BIBLIOGRAPHIC.getOrDefault(lower, lower), code);
    }

    /** ISO 639-2 three-letter codes (as MP4 and Matroska carry them) to English names. */
    private static Map<String, String> languageNames() {
        Map<String, String> names = new HashMap<>();
        for (String iso2 : Locale.getISOLanguages()) {
            Locale locale = Locale.of(iso2);
            names.put(locale.getISO3Language(), locale.getDisplayLanguage(Locale.ENGLISH));
        }
        return Map.copyOf(names);
    }

    private static String container(String formatName) {
        if (formatName == null) {
            return null;
        }
        String lower = formatName.toLowerCase(Locale.ROOT);
        if (lower.contains("mp4")) {
            return "mp4";
        }
        if (lower.contains("matroska")) {
            return "mkv";
        }
        return lower.split(",")[0];
    }

    private static long durationMs(JsonNode format, JsonNode root) {
        Double seconds = decimal(format, "duration");
        if (seconds == null) {
            for (JsonNode stream : root.path("streams")) {
                seconds = decimal(stream, "duration");
                if (seconds != null) {
                    break;
                }
            }
        }
        return seconds == null ? 0 : Math.round(seconds * 1000);
    }

    private static Tags tags(JsonNode tags) {
        String date = first(tags, "date", "©day", "creation_time");
        Matcher matcher = date == null ? null : YEAR.matcher(date);
        Integer year = matcher != null && matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
        return new Tags(first(tags, "title", "©nam"), year, text(tags, "media_type"), text(tags, "hd_video"),
            first(tags, "description", "synopsis", "comment"));
    }

    private static String first(JsonNode node, String... names) {
        for (String name : names) {
            String value = text(node, name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (value.isMissingNode() || value.isNull() || !value.isValueNode()) {
            return null;
        }
        String text = value.asString().trim();
        return text.isEmpty() ? null : text;
    }

    private static Integer integer(JsonNode node, String name) {
        Long value = number(node, name);
        return value == null || value > Integer.MAX_VALUE ? null : Integer.valueOf(value.intValue());
    }

    private static Long number(JsonNode node, String name) {
        Double value = decimal(node, name);
        return value == null ? null : Long.valueOf(Math.round(value));
    }

    private static Double decimal(JsonNode node, String name) {
        String text = text(node, name);
        if (text == null) {
            return null;
        }
        try {
            double value = Double.parseDouble(text);
            return Double.isFinite(value) && value >= 0 && value < Integer.MAX_VALUE * 1000.0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
