package gregochr.cinefilm.credits.learn;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * One file's entry in {@code labels-<date>.json}.
 *
 * @param mediaFileId the file
 * @param fingerprint its fingerprint now
 * @param unitKey its film's unit key
 * @param side its film's side (the hash side, for reference only, on a {@code SPLIT_CONFLICT} exclusion)
 * @param fold its fold (0 to 4) when the side is development, otherwise null
 * @param targetStartMs the start of the earliest merged credits interval, shifted; null when excluded
 * @param shiftMs the shift onto this copy; null when excluded
 * @param leadingIsland whether the first credits interval is a short island; null when excluded
 * @param credits {@code [from_ms, to_ms)} stretches that are credits
 * @param film stretches that are film
 * @param leftOut stretches left out, each with its reason
 * @param excluded why the whole file is left out, or null
 */
@JsonPropertyOrder({"media_file_id", "fingerprint", "unit_key", "side", "fold", "target_start_ms", "shift_ms",
    "leading_island", "credits", "film", "left_out", "excluded"})
public record LabelRecord(@JsonProperty("media_file_id") long mediaFileId,
                          @JsonProperty("fingerprint") String fingerprint,
                          @JsonProperty("unit_key") String unitKey, @JsonProperty("side") Side side,
                          @JsonProperty("fold") Integer fold, @JsonProperty("target_start_ms") Long targetStartMs,
                          @JsonProperty("shift_ms") Long shiftMs,
                          @JsonProperty("leading_island") Boolean leadingIsland,
                          @JsonProperty("credits") List<Span> credits, @JsonProperty("film") List<Span> film,
                          @JsonProperty("left_out") List<LeftOut> leftOut,
                          @JsonProperty("excluded") ExcludeReason excluded) {
}
