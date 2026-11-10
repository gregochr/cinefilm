package gregochr.cinefilm.ratings;

import java.math.BigDecimal;

/**
 * The ratings OMDb holds for one film. Every part may be null: the film may have no IMDb score, no Rotten Tomatoes
 * score or no Rotten Tomatoes page.
 *
 * @param imdbRating the IMDb score out of 10, one decimal
 * @param imdbVotes the number of IMDb votes
 * @param rtScore the Rotten Tomatoes percentage, 0 to 100
 * @param rtUrl the Rotten Tomatoes page, always an {@code http} or {@code https} URL
 */
public record OmdbRatings(BigDecimal imdbRating, Integer imdbVotes, Integer rtScore, String rtUrl) {
}
