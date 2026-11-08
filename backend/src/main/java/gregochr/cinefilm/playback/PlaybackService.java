package gregochr.cinefilm.playback;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.playback.PlaybackDtos.ProgressResponse;
import gregochr.cinefilm.playback.PlaybackDtos.WatchedResponse;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records playback progress and applies the watched rule. */
@Service
public class PlaybackService {

    private final EditionRepository editions;
    private final PlaybackStateRepository states;
    private final PreferencesService preferences;
    private final Clock clock;

    public PlaybackService(EditionRepository editions, PlaybackStateRepository states,
                           PreferencesService preferences, Clock clock) {
        this.editions = editions;
        this.states = states;
        this.preferences = preferences;
        this.clock = clock;
    }

    /** Saves a progress report; the edition becomes watched once the rule says so (and stays watched). */
    @Transactional
    public ProgressResponse reportProgress(Long editionId, Long positionMs) {
        if (positionMs == null || positionMs < 0) {
            throw new BadRequestException("positionMs must be a non-negative number");
        }
        Edition edition = edition(editionId);
        Instant now = clock.instant();
        PlaybackState state = states.findById(editionId).orElse(new PlaybackState(editionId));
        state.setPositionMs(positionMs);
        state.setLastPlayedAt(now);
        if (!state.isWatched()
            && WatchedRule.isWatched(positionMs, edition.getRuntimeMs(), edition.getCreditsMarkerMs())) {
            state.setWatched(true);
            state.setWatchedAt(now);
        }
        states.save(state);
        preferences.rememberLastEdition(edition.getFilm().getId(), editionId);
        return new ProgressResponse(state.getPositionMs(), state.isWatched());
    }

    /** Marks an edition watched or not watched; unwatching also resets the position to 0. */
    @Transactional
    public WatchedResponse setWatched(Long editionId, Boolean watched) {
        if (watched == null) {
            throw new BadRequestException("watched must be true or false");
        }
        edition(editionId);
        PlaybackState state = states.findById(editionId).orElse(new PlaybackState(editionId));
        if (watched) {
            if (!state.isWatched()) {
                state.setWatched(true);
                state.setWatchedAt(clock.instant());
            }
        } else {
            state.setWatched(false);
            state.setWatchedAt(null);
            state.setPositionMs(0);
        }
        states.save(state);
        return new WatchedResponse(state.isWatched());
    }

    private Edition edition(Long id) {
        return editions.findById(id).orElseThrow(() -> new NotFoundException("No such edition"));
    }
}
