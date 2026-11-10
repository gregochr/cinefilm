package gregochr.cinefilm.still;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StillCommandTest {

    @Test
    void theCommandIsTheExactArgumentListWithEachPathAsOneElement() {
        assertThat(StillCommand.of("/usr/bin/ffmpeg", Path.of("/media/a; rm -rf ~.m4v"), 12,
            Path.of("/cache/out.tmp"))).containsExactly("/usr/bin/ffmpeg", "-nostdin", "-hide_banner", "-v", "error",
                "-protocol_whitelist", "file", "-threads", "1", "-ss", "12", "-i",
                "/media/a; rm -rf ~.m4v", "-map", "0:v:0", "-frames:v", "1", "-an", "-sn", "-dn",
                "-vf",
                "scale='trunc(iw*sar/2)*2':ih,setsar=1,scale='min(1280,iw)':-2", "-q:v", "5", "-f", "image2", "-y",
                "/cache/out.tmp");
    }

    @Test
    void theSeekIsWholeSecondsWithNoDecimalPoint() {
        assertThat(StillCommand.of("ffmpeg", Path.of("/m/f.mkv"), 0, Path.of("/c/o.tmp"))).contains("-ss", "0");
        assertThat(StillCommand.of("ffmpeg", Path.of("/m/f.mkv"), 5_400, Path.of("/c/o.tmp"))).contains("-ss", "5400");
    }
}
