package gregochr.cinefilm.credits.learn;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * A half-open stretch of a file, written to JSON as {@code [fromMs, toMs]}.
 *
 * @param fromMs where it starts, in milliseconds from the start of the file
 * @param toMs where it ends, exclusive
 */
@JsonFormat(shape = JsonFormat.Shape.ARRAY)
@JsonPropertyOrder({"fromMs", "toMs"})
public record Span(long fromMs, long toMs) {
}
