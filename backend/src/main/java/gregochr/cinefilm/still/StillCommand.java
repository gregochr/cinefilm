package gregochr.cinefilm.still;

import java.nio.file.Path;
import java.util.List;

/** Builds the ffmpeg argument list that grabs one frame. Pure: no process is started here. */
final class StillCommand {

    private StillCommand() {
    }

    /**
     * The exact command: every path is one element (never a shell string), the seek is whole seconds, the input is
     * restricted to plain files, decoding uses one thread, and the frame is scaled to at most 1280 wide after
     * un-squeezing a non-square pixel aspect ratio.
     *
     * @param binary the ffmpeg executable
     * @param source the media file
     * @param seconds the second inside the file to grab
     * @param output the JPEG file to write
     * @return the argument list
     */
    static List<String> of(String binary, Path source, long seconds, Path output) {
        return List.of(binary, "-nostdin", "-hide_banner", "-v", "error", "-protocol_whitelist", "file",
            "-threads", "1", "-ss", Long.toString(seconds), "-i", source.toString(), "-map", "0:v:0",
            "-frames:v", "1", "-an", "-sn", "-dn", "-vf", "scale='min(1280,iw*sar)':-2,setsar=1", "-q:v", "5",
            "-f", "image2", "-y", output.toString());
    }
}
