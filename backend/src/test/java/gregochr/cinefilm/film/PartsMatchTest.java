package gregochr.cinefilm.film;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.PartsMatch.Result;
import java.util.List;
import org.junit.jupiter.api.Test;

class PartsMatchTest {

    private static MediaFile part(String codec, int width, int height, String pixelFormat) {
        MediaFile file = new MediaFile();
        file.setVideoCodec(codec);
        file.setWidth(width);
        file.setHeight(height);
        file.setPixelFormat(pixelFormat);
        return file;
    }

    private static MediaFile standard() {
        MediaFile file = part("hevc", 1920, 1080, "yuv420p");
        audio(file, 1, "eng", "5.1", "ac3");
        audio(file, 2, "fra", "Stereo", "aac");
        subtitle(file, 3, "eng", "mov_text");
        return file;
    }

    private static void audio(MediaFile file, int index, String language, String channels, String codec) {
        AudioStream stream = new AudioStream();
        stream.setMediaFile(file);
        stream.setIndex(index);
        stream.setLanguage(language);
        stream.setChannels(channels);
        stream.setCodec(codec);
        file.getAudioStreams().add(stream);
    }

    private static void subtitle(MediaFile file, int index, String language, String format) {
        SubtitleStream stream = new SubtitleStream();
        stream.setMediaFile(file);
        stream.setIndex(index);
        stream.setLanguage(language);
        stream.setFormat(format);
        file.getSubtitleStreams().add(stream);
    }

    @Test
    void identicalPartsMatchWithNoDifferences() {
        Result result = PartsMatch.check(List.of(standard(), standard(), standard()));

        assertThat(result.matches()).isTrue();
        assertThat(result.differences()).isEmpty();
    }

    @Test
    void oneOrNoPartsTriviallyMatch() {
        assertThat(PartsMatch.check(List.of(standard())).matches()).isTrue();
        assertThat(PartsMatch.check(List.of()).matches()).isTrue();
    }

    @Test
    void aDifferentVideoCodecIsReported() {
        MediaFile other = standard();
        other.setVideoCodec("h264");

        Result result = PartsMatch.check(List.of(standard(), other));

        assertThat(result.matches()).isFalse();
        assertThat(result.differences()).containsExactly("Part 2: video codec is h264, part 1 has hevc");
    }

    @Test
    void aDifferentWidthIsReported() {
        MediaFile other = standard();
        other.setWidth(1280);

        assertThat(PartsMatch.check(List.of(standard(), other)).differences())
            .containsExactly("Part 2: width is 1280, part 1 has 1920");
    }

    @Test
    void aDifferentHeightIsReported() {
        MediaFile other = standard();
        other.setHeight(800);

        assertThat(PartsMatch.check(List.of(standard(), other)).differences())
            .containsExactly("Part 2: height is 800, part 1 has 1080");
    }

    @Test
    void aDifferentPixelFormatIsReported() {
        MediaFile other = standard();
        other.setPixelFormat("yuv420p10le");

        assertThat(PartsMatch.check(List.of(standard(), other)).differences())
            .containsExactly("Part 2: pixel format is yuv420p10le, part 1 has yuv420p");
    }

    @Test
    void anUnprobedFieldOnOnePartDiffersFromAProbedOne() {
        MediaFile other = standard();
        other.setPixelFormat(null);

        assertThat(PartsMatch.check(List.of(standard(), other)).differences())
            .containsExactly("Part 2: pixel format is null, part 1 has yuv420p");
    }

    @Test
    void aDifferentAudioStreamCountIsReportedOnceWithoutPerStreamNoise() {
        MediaFile other = standard();
        other.getAudioStreams().remove(1);

        assertThat(PartsMatch.check(List.of(standard(), other)).differences())
            .containsExactly("Part 2: 1 audio streams, part 1 has 2");
    }

    @Test
    void audioCodecChannelsAndLanguageAreComparedStreamByStream() {
        MediaFile other = standard();
        other.getAudioStreams().get(0).setCodec("eac3");
        other.getAudioStreams().get(1).setChannels("Mono");
        other.getAudioStreams().get(1).setLanguage("deu");

        assertThat(PartsMatch.check(List.of(standard(), other)).differences()).containsExactly(
            "Part 2: audio stream 1 codec is eac3, part 1 has ac3",
            "Part 2: audio stream 2 channels is Mono, part 1 has Stereo",
            "Part 2: audio stream 2 language is deu, part 1 has fra");
    }

    @Test
    void audioStreamsAreComparedInStreamIndexOrderNotListOrder() {
        MediaFile other = standard();
        AudioStream moved = other.getAudioStreams().remove(0);
        other.getAudioStreams().add(moved);

        assertThat(PartsMatch.check(List.of(standard(), other)).matches()).isTrue();
    }

    @Test
    void aDifferentSubtitleCountIsReported() {
        MediaFile other = standard();
        other.getSubtitleStreams().clear();

        assertThat(PartsMatch.check(List.of(standard(), other)).differences())
            .containsExactly("Part 2: 0 subtitle streams, part 1 has 1");
    }

    @Test
    void subtitleFormatAndLanguageAreComparedStreamByStream() {
        MediaFile other = standard();
        other.getSubtitleStreams().get(0).setFormat("subrip");
        other.getSubtitleStreams().get(0).setLanguage("fra");

        assertThat(PartsMatch.check(List.of(standard(), other)).differences()).containsExactly(
            "Part 2: subtitle stream 1 format is subrip, part 1 has mov_text",
            "Part 2: subtitle stream 1 language is fra, part 1 has eng");
    }

    @Test
    void everyLaterPartIsComparedWithTheFirstAndNamedByItsPosition() {
        MediaFile second = standard();
        MediaFile third = standard();
        third.setHeight(720);

        Result result = PartsMatch.check(List.of(standard(), second, third));

        assertThat(result.matches()).isFalse();
        assertThat(result.differences()).containsExactly("Part 3: height is 720, part 1 has 1080");
    }
}
