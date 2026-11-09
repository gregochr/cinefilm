package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SourceFormatTest {

    @Test
    void onlyEightBitFourTwoZeroH264Qualifies() {
        assertThat(SourceFormat.isH264Yuv420("H.264", "yuv420p")).isTrue();
        assertThat(SourceFormat.isH264Yuv420("H.264", "yuvj420p")).isTrue();
        assertThat(SourceFormat.isH264Yuv420("h.264", "YUV420P")).isTrue();
    }

    @Test
    void tenBitFourTwoTwoAndFourFourFourDoNotQualify() {
        assertThat(SourceFormat.isH264Yuv420("H.264", "yuv420p10le")).isFalse();
        assertThat(SourceFormat.isH264Yuv420("H.264", "yuv422p")).isFalse();
        assertThat(SourceFormat.isH264Yuv420("H.264", "yuv444p")).isFalse();
        assertThat(SourceFormat.isH264Yuv420("H.264", "nv12")).isFalse();
    }

    @Test
    void otherCodecsAndUnknownValuesDoNotQualify() {
        assertThat(SourceFormat.isH264Yuv420("HEVC", "yuv420p")).isFalse();
        assertThat(SourceFormat.isH264Yuv420("AV1", "yuv420p")).isFalse();
        assertThat(SourceFormat.isH264Yuv420(null, "yuv420p")).isFalse();
        assertThat(SourceFormat.isH264Yuv420("H.264", null)).isFalse();
        assertThat(SourceFormat.isH264Yuv420(null, null)).isFalse();
    }
}
