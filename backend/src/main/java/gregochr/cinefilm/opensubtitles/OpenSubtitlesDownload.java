package gregochr.cinefilm.opensubtitles;

import gregochr.cinefilm.subtitle.SrtParser;
import java.time.Instant;

/**
 * One downloaded subtitle: the text as read by {@link SrtParser} (untrusted: it may have no cues) and what
 * OpenSubtitles said about the allowance.
 *
 * @param parsed the file read as SRT
 * @param remaining downloads left for the account today, or null when OpenSubtitles did not say
 * @param resetTimeUtc when the allowance resets, or null when OpenSubtitles did not say
 */
public record OpenSubtitlesDownload(SrtParser.Parsed parsed, Integer remaining, Instant resetTimeUtc) {
}
