package gregochr.cinefilm.journey;

/** The key of a {@link JourneyModemSample}: its journey, its round and its modem index. */
public record JourneyModemSampleId(Long journeyId, Integer roundNo, Integer modemIndex) {
}
