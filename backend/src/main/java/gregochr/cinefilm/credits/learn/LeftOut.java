package gregochr.cinefilm.credits.learn;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * A stretch left out of the labels, with the reason.
 *
 * @param fromMs where it starts
 * @param toMs where it ends, exclusive
 * @param reason why it is left out
 */
@JsonPropertyOrder({"from_ms", "to_ms", "reason"})
public record LeftOut(@JsonProperty("from_ms") long fromMs, @JsonProperty("to_ms") long toMs,
                      @JsonProperty("reason") LeftOutReason reason) {
}
