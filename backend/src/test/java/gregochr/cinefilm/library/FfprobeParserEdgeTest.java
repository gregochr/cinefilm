package gregochr.cinefilm.library;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The edges of ffprobe parsing: what marks a stream protected, which numbers are trusted, which video counts. */
class FfprobeParserEdgeTest {

    private static final String VIDEO = "{\"index\":0,\"codec_type\":\"video\",\"codec_name\":\"h264\",";
    private static final String AUDIO = "{\"index\":1,\"codec_type\":\"audio\",\"codec_name\":\"aac\",";

    private static ProbeResult parse(String format, String... streams) {
        return FfprobeParser.parse("{\"format\":" + format + ",\"streams\":[" + String.join(",", streams) + "]}");
    }

    private static boolean protectedBy(String stream) {
        return parse("{\"format_name\":\"mp4\"}", stream).protectedContent();
    }

    @Test
    void eachWayOfMarkingAStreamProtectedIsEnoughOnItsOwn() {
        assertThat(protectedBy(VIDEO + "\"codec_tag_string\":\"drmi\"}")).as("video drm tag").isTrue();
        assertThat(protectedBy(AUDIO + "\"codec_tag_string\":\"drms\"}")).as("audio drm tag").isTrue();
        assertThat(protectedBy(AUDIO + "\"codec_tag_string\":\"DRMP\"}")).as("upper case tag").isTrue();
        assertThat(protectedBy(VIDEO + "\"encrypted\":1}")).as("numeric flag").isTrue();
        assertThat(protectedBy(VIDEO + "\"encrypted\":true}")).as("boolean flag").isTrue();
        assertThat(protectedBy(VIDEO + "\"encrypted\":\"true\"}")).as("string flag").isTrue();
        assertThat(protectedBy(AUDIO + "\"tags\":{\"encrypted\":\"1\"}}")).as("tag flag").isTrue();
        assertThat(protectedBy(VIDEO + "\"encrypted\":false}")).as("false flag").isFalse();
        assertThat(protectedBy(VIDEO + "\"encrypted\":0}")).as("zero flag").isFalse();
        assertThat(protectedBy(AUDIO + "\"tags\":{\"encrypted\":\"0\"}}")).as("zero tag").isFalse();
        assertThat(protectedBy(AUDIO + "\"codec_tag_string\":\"mp4a\"}")).as("ordinary tag").isFalse();
    }

    @Test
    void oneProtectedStreamProtectsTheFileWhicheverKindItIs() {
        String clearAudio = AUDIO.substring(0, AUDIO.length() - 1) + "}";
        String clearVideo = VIDEO.substring(0, VIDEO.length() - 1) + "}";
        String drmVideo = VIDEO + "\"codec_tag_string\":\"drmi\"}";
        String drmAudio = AUDIO + "\"codec_tag_string\":\"drms\"}";

        assertThat(parse("{}", drmVideo, clearAudio).protectedContent()).isTrue();
        assertThat(parse("{}", clearVideo, drmAudio).protectedContent()).isTrue();
        assertThat(parse("{}", clearVideo, clearAudio).protectedContent()).isFalse();
    }

    @Test
    void coverArtBeforeTheRealVideoIsSkipped() {
        ProbeResult probe = parse("{}",
            "{\"index\":0,\"codec_type\":\"video\",\"codec_name\":\"mjpeg\",\"width\":600,\"height\":900,"
                + "\"disposition\":{\"attached_pic\":1}}",
            "{\"index\":1,\"codec_type\":\"video\",\"codec_name\":\"h264\",\"width\":1920,\"height\":800,"
                + "\"disposition\":{\"attached_pic\":0}}");

        assertThat(probe.videoCodec()).isEqualTo("H.264");
        assertThat(probe.width()).isEqualTo(1920);
    }

    @Test
    void theDurationComesFromTheFirstStreamThatHasOneWhenTheFormatHasNone() {
        ProbeResult probe = parse("{}", VIDEO.substring(0, VIDEO.length() - 1) + "}",
            AUDIO + "\"duration\":\"2.0\"}", AUDIO + "\"duration\":\"3.0\"}");

        assertThat(probe.durationMs()).isEqualTo(2_000L);
    }

    @Test
    void aWidthIsTrustedUpToTheLargestInteger() {
        assertThat(parse("{}", VIDEO + "\"width\":2147483647}").width()).isEqualTo(Integer.MAX_VALUE);
        assertThat(parse("{}", VIDEO + "\"width\":2147483648}").width()).isNull();
    }

    @Test
    void aBitRateIsTrustedFromZeroUpToButNotIncludingTheLimit() {
        assertThat(parse("{\"bit_rate\":\"0\"}").bitrate()).isZero();
        assertThat(parse("{\"bit_rate\":\"abc\"}").bitrate()).isNull();
        assertThat(parse("{\"bit_rate\":\"2147483646999\"}").bitrate()).isEqualTo(2_147_483_646_999L);
        assertThat(parse("{\"bit_rate\":\"2147483647000\"}").bitrate()).isNull();
    }

    @Test
    void aRipDateIsNeverTakenForTheReleaseYear() throws IOException {
        ProbeResult probe;
        try (InputStream in = FfprobeParserEdgeTest.class.getResourceAsStream("/ffprobe/rip-date-only.json")) {
            probe = FfprobeParser.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }

        assertThat(probe.tags().year()).as("creation_time 2014 on a 1973 film").isNull();
        assertThat(TitleGuess.of(null, "The Wicker Man.m4v", probe.tags().year()))
            .isEqualTo(new TitleGuess("The Wicker Man", null));
    }

    @Test
    void aDateTagYearIsBelievedOnlyBetweenTheFirstFilmAndNextYear() {
        String format = "{\"tags\":{\"date\":\"%s\"}}";
        assertThat(parseYear(format.formatted("1888"), 2025)).isEqualTo(1888);
        assertThat(parseYear(format.formatted("1887"), 2025)).isNull();
        assertThat(parseYear(format.formatted("2025-06-01"), 2025)).isEqualTo(2025);
        assertThat(parseYear(format.formatted("2026"), 2025)).isNull();
        assertThat(parseYear("{\"tags\":{\"\u00a9day\":\"1973\"}}", 2025)).isEqualTo(1973);
        assertThat(parseYear("{\"tags\":{\"date\":\"0000\",\"creation_time\":\"2014-01-01\"}}", 2025)).isNull();
    }

    private static Integer parseYear(String format, int latestYear) {
        return FfprobeParser.parse("{\"format\":" + format + ",\"streams\":[]}", latestYear).tags().year();
    }
}
