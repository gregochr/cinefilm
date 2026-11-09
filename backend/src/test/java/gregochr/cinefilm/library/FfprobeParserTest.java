package gregochr.cinefilm.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.library.ProbeResult.Audio;
import gregochr.cinefilm.library.ProbeResult.Subtitle;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** ffprobe parsing from recorded-shape JSON fixtures in {@code src/test/resources/ffprobe}. */
class FfprobeParserTest {

    private static ProbeResult fixture(String name) throws IOException {
        try (InputStream in = FfprobeParserTest.class.getResourceAsStream("/ffprobe/" + name)) {
            return FfprobeParser.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void anItunesM4vWithAacAndAc3AndMovTextIsReadInFull() throws IOException {
        ProbeResult probe = fixture("itunes-m4v.json");

        assertThat(probe.durationMs()).isEqualTo(6_012_480L);
        assertThat(probe.container()).isEqualTo("mp4");
        assertThat(probe.videoCodec()).isEqualTo("H.264");
        assertThat(probe.width()).isEqualTo(1920);
        assertThat(probe.height()).isEqualTo(800);
        assertThat(probe.bitrate()).isEqualTo(9_342_000L);
        assertThat(probe.protectedContent()).isFalse();
        assertThat(probe.audio()).containsExactly(
            new Audio(1, "English", "Stereo", "AAC", null, true, true),
            new Audio(2, "English", "5.1", "AC-3", "Surround", false, true));
        assertThat(probe.subtitles()).containsExactly(
            new Subtitle(3, "English", false, null, "mov_text"),
            new Subtitle(4, "English", true, "English (forced)", "mov_text"));
        assertThat(probe.tags().title()).isEqualTo("10 Cloverfield Lane");
        assertThat(probe.tags().year()).isEqualTo(2016);
        assertThat(probe.tags().mediaType()).isEqualTo("9");
        assertThat(probe.tags().hdVideo()).isEqualTo("1");
        assertThat(probe.tags().description()).isEqualTo("A young woman wakes in a bunker after a car crash.");
    }

    @Test
    void aHandBrakeFileHasHevcMultichannelAudioAndNoTextSubtitles() throws IOException {
        ProbeResult probe = fixture("handbrake-mp4.json");

        assertThat(probe.videoCodec()).isEqualTo("HEVC");
        assertThat(probe.width()).isEqualTo(3840);
        assertThat(probe.height()).isEqualTo(1604);
        assertThat(probe.bitrate()).isEqualTo(21_000_000L);
        assertThat(probe.durationMs()).isEqualTo(5_280_000L);
        assertThat(probe.audio()).containsExactly(
            new Audio(1, "French", "7.1", "E-AC-3", "Surround 7.1", true, true),
            new Audio(2, "xxx", "3 ch", "TrueHD", null, false, false));
        assertThat(probe.subtitles()).as("bitmap PGS subtitles cannot become WebVTT").isEmpty();
        assertThat(probe.tags().title()).isNull();
        assertThat(probe.tags().year()).isNull();
        assertThat(probe.protectedContent()).isFalse();
    }

    @Test
    void anUndeterminedLanguageIsNullNotUnd() throws IOException {
        assertThat(fixture("handbrake-mp4.json").audio()).hasSize(2);
        ProbeResult probe = FfprobeParser.parse("""
            {"streams":[{"index":0,"codec_type":"audio","codec_name":"aac","channels":1,"tags":{"language":"und"}}],
             "format":{"format_name":"mov,mp4"}}""");

        assertThat(probe.audio().get(0).language()).isNull();
        assertThat(probe.audio().get(0).channels()).isEqualTo("Mono");
    }

    @Test
    void bibliographicAndTerminologicLanguageCodesBothGetEnglishNames() {
        ProbeResult probe = FfprobeParser.parse("""
            {"format":{"format_name":"mp4"},"streams":[
              {"index":1,"codec_type":"audio","codec_name":"aac","tags":{"language":"fre"}},
              {"index":2,"codec_type":"audio","codec_name":"aac","tags":{"language":"JPN"}}]}""");

        assertThat(probe.audio()).extracting(Audio::language).containsExactly("French", "Japanese");
    }

    @Test
    void aFairPlayFileIsProtectedAndKeepsItsTitleAndAppleYearTag() throws IOException {
        ProbeResult probe = fixture("fairplay-m4v.json");

        assertThat(probe.protectedContent()).isTrue();
        assertThat(probe.durationMs()).isEqualTo(5_400_100L);
        assertThat(probe.tags().title()).isEqualTo("Protected Film");
        assertThat(probe.tags().year()).isEqualTo(2008);
    }

    @Test
    void anEncryptedFlagOnAStreamOrItsTagsMarksTheFileProtected() throws IOException {
        assertThat(fixture("encrypted-flag.json").protectedContent()).isTrue();
        assertThat(FfprobeParser.parse("""
            {"streams":[{"index":0,"codec_type":"video","codec_name":"h264","encrypted":true}],
             "format":{"format_name":"mp4"}}""").protectedContent()).isTrue();
        assertThat(FfprobeParser.parse("""
            {"streams":[{"index":0,"codec_type":"video","codec_name":"h264","encrypted":0}],
             "format":{"format_name":"mp4"}}""").protectedContent()).isFalse();
    }

    @Test
    void anAviIsReadAsMpeg4WithMp3Audio() throws IOException {
        ProbeResult probe = fixture("avi.json");

        assertThat(probe.container()).isEqualTo("avi");
        assertThat(probe.videoCodec()).isEqualTo("MPEG-4");
        assertThat(probe.bitrate()).isEqualTo(1_400_000L);
        assertThat(probe.durationMs()).isEqualTo(5_760_040L);
        assertThat(probe.audio()).containsExactly(new Audio(1, null, "Stereo", "MP3", null, false, true));
    }

    @Test
    void aFileWithNoAudioHasNoAudioStreamsAndTakesDurationAndBitrateFromTheVideo() throws IOException {
        ProbeResult probe = fixture("no-audio.json");

        assertThat(probe.audio()).isEmpty();
        assertThat(probe.container()).isEqualTo("mkv");
        assertThat(probe.videoCodec()).isEqualTo("AV1");
        assertThat(probe.durationMs()).isEqualTo(600_500L);
        assertThat(probe.bitrate()).isEqualTo(4_000_000L);
        assertThat(probe.tags().title()).isEqualTo("Silent Film");
        assertThat(probe.tags().year()).isEqualTo(1925);
    }

    @Test
    void aFileWithNothingUsableHasZeroDurationAndNoVideo() {
        ProbeResult probe = FfprobeParser.parse("{\"format\":{\"duration\":\"abc\"},\"streams\":[]}");

        assertThat(probe.durationMs()).isZero();
        assertThat(probe.videoCodec()).isNull();
        assertThat(probe.container()).isNull();
        assertThat(probe.bitrate()).isNull();
        assertThat(probe.tags().title()).isNull();
    }

    @Test
    void absurdOrNegativeNumbersAreIgnoredRatherThanTrusted() {
        ProbeResult probe = FfprobeParser.parse("""
            {"format":{"format_name":"mp4","duration":"-5","bit_rate":"NaN"},
             "streams":[{"index":0,"codec_type":"video","codec_name":"h264","width":"1e12","height":"720"}]}""");

        assertThat(probe.durationMs()).isZero();
        assertThat(probe.bitrate()).isNull();
        assertThat(probe.height()).isEqualTo(720);
        assertThat(probe.width()).isNull();
    }

    @Test
    void channelLayoutsAreNamedForPeople() {
        String json = """
            {"format":{"format_name":"mp4"},"streams":[
              {"index":1,"codec_type":"audio","codec_name":"dts","channels":2},
              {"index":2,"codec_type":"audio","codec_name":"dts","channels":6},
              {"index":3,"codec_type":"audio","codec_name":"dts","channels":0}]}""";

        assertThat(FfprobeParser.parse(json).audio()).extracting(Audio::channels)
            .containsExactly("Stereo", "5.1", null);
    }

    @Test
    void aCorruptProbeIsRejectedWithAProbeException() {
        assertThatThrownBy(() -> FfprobeParser.parse("{\"streams\": [ {\"index\": 0, \"codec"))
            .isInstanceOf(ProbeException.class).hasMessage("ffprobe output is not JSON");
        assertThatThrownBy(() -> FfprobeParser.parse(""))
            .isInstanceOf(ProbeException.class);
        assertThatThrownBy(() -> FfprobeParser.parse("[1,2]"))
            .isInstanceOf(ProbeException.class).hasMessage("ffprobe output has no format section");
        assertThatThrownBy(() -> FfprobeParser.parse("{\"streams\":[]}"))
            .isInstanceOf(ProbeException.class).hasMessage("ffprobe output has no format section");
    }

    @Test
    void aYearThatIsNotFourDigitsIsNotAYear() {
        ProbeResult probe = FfprobeParser.parse("{\"format\":{\"tags\":{\"date\":\"sometime\"}},\"streams\":[]}");

        assertThat(probe.tags().year()).isNull();
    }

    @Test
    void textSubtitleCodecsOtherThanMovTextAreKept() {
        ProbeResult probe = FfprobeParser.parse("""
            {"format":{"format_name":"matroska"},"streams":[
              {"index":2,"codec_type":"subtitle","codec_name":"subrip","tags":{"language":"deu"}},
              {"index":3,"codec_type":"subtitle","codec_name":"dvd_subtitle"},
              {"index":4,"codec_type":"subtitle"}]}""");

        assertThat(probe.subtitles()).containsExactly(new Subtitle(2, "German", false, null, "subrip"));
    }

    @Test
    void onlyTheFirstRealVideoStreamDescribesTheFile() {
        ProbeResult probe = FfprobeParser.parse("""
            {"format":{"format_name":"mp4"},"streams":[
              {"index":0,"codec_type":"video","codec_name":"hevc","width":3840,"height":2160},
              {"index":1,"codec_type":"video","codec_name":"h264","width":640,"height":360}]}""");

        assertThat(probe.videoCodec()).isEqualTo("HEVC");
        assertThat(probe.width()).isEqualTo(3840);
    }

    @Test
    void streamsWithNoCodecNameAreReadWithoutGuessingOne() {
        ProbeResult probe = FfprobeParser.parse("""
            {"format":{"format_name":"mp4"},"streams":[
              {"index":0,"codec_type":"video","width":640},
              {"index":1,"codec_type":"audio","channels":2}]}""");

        assertThat(probe.videoCodec()).isNull();
        assertThat(probe.audio()).containsExactly(new Audio(1, null, "Stereo", null, null, false, false));
    }

    @Test
    void nullBlankAndStructuredTagValuesAreTreatedAsAbsent() {
        ProbeResult probe = FfprobeParser.parse("""
            {"format":{"tags":{"title":null,"description":"   ","hd_video":{"a":1},"media_type":"9"}},
             "streams":[{"index":0,"codec_type":"video","codec_name":"h264","encrypted":null}]}""");

        assertThat(probe.tags().title()).isNull();
        assertThat(probe.tags().description()).isNull();
        assertThat(probe.tags().hdVideo()).isNull();
        assertThat(probe.tags().mediaType()).isEqualTo("9");
        assertThat(probe.protectedContent()).isFalse();
    }

    @Test
    void aNumberFarBeyondAnyRealFileIsIgnored() {
        ProbeResult probe = FfprobeParser.parse("{\"format\":{\"format_name\":\"mp4\",\"duration\":\"1e13\"},"
            + "\"streams\":[]}");

        assertThat(probe.durationMs()).isZero();
    }

    private static boolean protectedBy(String stream) {
        return FfprobeParser.parse("{\"format\":{\"format_name\":\"mp4\"},\"streams\":[" + stream + "]}")
            .protectedContent();
    }

    @Test
    void eachWayOfMarkingAStreamProtectedIsEnoughOnItsOwn() {
        String video = "{\"index\":0,\"codec_type\":\"video\",\"codec_name\":\"h264\",";
        String audio = "{\"index\":1,\"codec_type\":\"audio\",\"codec_name\":\"aac\",";
        assertThat(protectedBy(video + "\"codec_tag_string\":\"drmi\"}")).as("video drm tag").isTrue();
        assertThat(protectedBy(audio + "\"codec_tag_string\":\"drms\"}")).as("audio drm tag").isTrue();
        assertThat(protectedBy(audio + "\"codec_tag_string\":\"DRMP\"}")).as("upper case tag").isTrue();
        assertThat(protectedBy(video + "\"encrypted\":1}")).as("numeric flag").isTrue();
        assertThat(protectedBy(video + "\"encrypted\":true}")).as("boolean flag").isTrue();
        assertThat(protectedBy(video + "\"encrypted\":\"true\"}")).as("string flag").isTrue();
        assertThat(protectedBy(audio + "\"tags\":{\"encrypted\":\"1\"}}")).as("tag flag").isTrue();
        assertThat(protectedBy(video + "\"encrypted\":false}")).as("false flag").isFalse();
        assertThat(protectedBy(video + "\"encrypted\":0}")).as("zero flag").isFalse();
        assertThat(protectedBy(audio + "\"tags\":{\"encrypted\":\"0\"}}")).as("zero tag").isFalse();
        assertThat(protectedBy(audio + "\"codec_tag_string\":\"mp4a\"}")).as("ordinary tag").isFalse();
    }

    @Test
    void aProtectedVideoMakesTheFileProtectedEvenWhenTheAudioIsClearAndTheOtherWayRound() {
        String clearAudio = "{\"index\":1,\"codec_type\":\"audio\",\"codec_name\":\"aac\"}";
        String clearVideo = "{\"index\":0,\"codec_type\":\"video\",\"codec_name\":\"h264\"}";
        String drmVideo = "{\"index\":0,\"codec_type\":\"video\",\"codec_name\":\"h264\","
            + "\"codec_tag_string\":\"drmi\"}";
        String drmAudio = "{\"index\":1,\"codec_type\":\"audio\",\"codec_name\":\"aac\","
            + "\"codec_tag_string\":\"drms\"}";

        assertThat(protectedBy(drmVideo + "," + clearAudio)).isTrue();
        assertThat(protectedBy(clearVideo + "," + drmAudio)).isTrue();
        assertThat(protectedBy(clearVideo + "," + clearAudio)).isFalse();
    }

    @Test
    void coverArtBeforeTheRealVideoIsSkipped() {
        ProbeResult probe = FfprobeParser.parse("""
            {"format":{"format_name":"mp4"},"streams":[
              {"index":0,"codec_type":"video","codec_name":"mjpeg","width":600,"height":900,
               "disposition":{"attached_pic":1}},
              {"index":1,"codec_type":"video","codec_name":"h264","width":1920,"height":800,
               "disposition":{"attached_pic":0}}]}""");

        assertThat(probe.videoCodec()).isEqualTo("H.264");
        assertThat(probe.width()).isEqualTo(1920);
    }

    @Test
    void theDurationComesFromTheFirstStreamThatHasOneWhenTheFormatHasNone() {
        ProbeResult probe = FfprobeParser.parse("""
            {"format":{"format_name":"mp4"},"streams":[
              {"index":0,"codec_type":"video","codec_name":"h264"},
              {"index":1,"codec_type":"audio","codec_name":"aac","duration":"2.0"},
              {"index":2,"codec_type":"audio","codec_name":"aac","duration":"3.0"}]}""");

        assertThat(probe.durationMs()).isEqualTo(2_000L);
    }

    @Test
    void theFirstRealVideoStreamsPixelFormatIsKeptAndCoverArtIsIgnored() {
        String json = "{\"format\":{},\"streams\":["
            + "{\"index\":0,\"codec_type\":\"video\",\"codec_name\":\"mjpeg\",\"pix_fmt\":\"yuvj444p\","
            + "\"disposition\":{\"attached_pic\":1}},"
            + "{\"index\":1,\"codec_type\":\"video\",\"codec_name\":\"h264\",\"pix_fmt\":\"yuv420p10le\"},"
            + "{\"index\":2,\"codec_type\":\"video\",\"codec_name\":\"h264\",\"pix_fmt\":\"yuv420p\"}]}";

        assertThat(FfprobeParser.parse(json).pixelFormat()).isEqualTo("yuv420p10le");
    }

    @Test
    void aMissingOrBlankPixelFormatIsNull() {
        assertThat(FfprobeParser.parse("{\"format\":{},\"streams\":[{\"index\":0,\"codec_type\":\"video\","
            + "\"codec_name\":\"h264\"}]}").pixelFormat()).isNull();
        assertThat(FfprobeParser.parse("{\"format\":{},\"streams\":[{\"index\":0,\"codec_type\":\"video\","
            + "\"codec_name\":\"h264\",\"pix_fmt\":\" \"}]}").pixelFormat()).isNull();
        assertThat(FfprobeParser.parse("{\"format\":{},\"streams\":[]}").pixelFormat()).isNull();
    }

    @Test
    void numbersAtTheEdgesOfWhatIsTrustedAreHandledExactly() {
        String video = "{\"index\":0,\"codec_type\":\"video\",\"codec_name\":\"h264\",\"width\":%s}";
        assertThat(FfprobeParser.parse("{\"format\":{},\"streams\":[" + video.formatted("2147483647") + "]}")
            .width()).isEqualTo(Integer.MAX_VALUE);
        assertThat(FfprobeParser.parse("{\"format\":{},\"streams\":[" + video.formatted("2147483648") + "]}")
            .width()).isNull();

        assertThat(FfprobeParser.parse("{\"format\":{\"bit_rate\":\"0\"},\"streams\":[]}").bitrate()).isZero();
        assertThat(FfprobeParser.parse("{\"format\":{\"bit_rate\":\"abc\"},\"streams\":[]}").bitrate()).isNull();
        assertThat(FfprobeParser.parse("{\"format\":{\"bit_rate\":\"2147483646999\"},\"streams\":[]}").bitrate())
            .isEqualTo(2_147_483_646_999L);
        assertThat(FfprobeParser.parse("{\"format\":{\"bit_rate\":\"2147483647000\"},\"streams\":[]}").bitrate())
            .isNull();
    }
}
