package gregochr.cinefilm.journey;

/** The key of a {@link JourneyPlayback}: a journey and a playback record. */
public record JourneyPlaybackId(Long journeyId, Long playbackHealthId) {
}
