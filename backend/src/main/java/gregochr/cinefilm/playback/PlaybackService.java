package gregochr.cinefilm.playback;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.playback.PlaybackDtos.HiddenResponse;
import gregochr.cinefilm.playback.PlaybackDtos.PlaybackSnapshot;
import gregochr.cinefilm.playback.PlaybackDtos.ProgressResponse;
import gregochr.cinefilm.playback.PlaybackDtos.WatchedResponse;
import gregochr.cinefilm.search.LibraryChanged;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records playback progress and applies the watched rule. */
@Service
public class PlaybackService {

    private final EditionRepository editions;
    private final PlaybackStateRepository states;
    private final PreferencesService preferences;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    /**
     * Creates the service over its repositories, the preferences service, the application clock and the event
     * publisher (every write tells the search index its copy of the playback state is out of date).
     */
    public PlaybackService(EditionRepository editions, PlaybackStateRepository states,
                           PreferencesService preferences, Clock clock, ApplicationEventPublisher events) {
        this.editions = editions;
        this.states = states;
        this.preferences = preferences;
        this.clock = clock;
        this.events = events;
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
        state.setLastPlayedAt(now);
        // Any playback brings a removed film back into Continue watching.
        state.setHiddenFromContinueWatching(false);
        state.setHiddenAt(null);
        if (WatchedRule.isWatched(positionMs, edition.getRuntimeMs(), edition.getCreditsMarkerMs())) {
            // Reaching the credits marks it watched and resets the position, so credits playing out never
            // puts the film back into Continue watching.
            if (!state.isWatched()) {
                state.setWatched(true);
                state.setWatchedAt(now);
            }
            state.setPositionMs(0);
        } else {
            state.setPositionMs(positionMs);
        }
        states.save(state);
        preferences.rememberLastEdition(edition.getFilm().getId(), editionId);
        events.publishEvent(new LibraryChanged());
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
        PlaybackSnapshot previous = snapshot(state);
        if (watched) {
            if (!state.isWatched()) {
                state.setWatched(true);
                state.setWatchedAt(clock.instant());
            }
            state.setPositionMs(0);
        } else {
            state.setWatched(false);
            state.setWatchedAt(null);
            state.setPositionMs(0);
        }
        states.save(state);
        events.publishEvent(new LibraryChanged());
        return new WatchedResponse(state.isWatched(), previous);
    }

    /**
     * Hides an edition's film from Continue watching, or shows it again. Only the hidden flag and its time change:
     * the resume position, watched state and last-played time are kept. An edition that has never been played has
     * no state row and nothing to hide: none is created and the answer is {@code hidden: false}.
     */
    @Transactional
    public HiddenResponse setHiddenFromContinueWatching(Long editionId, Boolean hidden) {
        if (hidden == null) {
            throw new BadRequestException("hidden must be true or false");
        }
        edition(editionId);
        Optional<PlaybackState> found = states.findById(editionId);
        if (found.isEmpty()) {
            return new HiddenResponse(false);
        }
        PlaybackState state = found.get();
        state.setHiddenFromContinueWatching(hidden);
        state.setHiddenAt(hidden ? clock.instant() : null);
        states.save(state);
        events.publishEvent(new LibraryChanged());
        return new HiddenResponse(hidden);
    }

    /**
     * Restores exactly the five snapshot fields (undo). {@code lastPlayedAt} is never touched. A missing state row
     * is created.
     */
    @Transactional
    public PlaybackSnapshot restore(Long editionId, PlaybackSnapshot snapshot) {
        if (snapshot.positionMs() < 0) {
            throw new BadRequestException("positionMs must be a non-negative number");
        }
        edition(editionId);
        PlaybackState state = states.findById(editionId).orElse(new PlaybackState(editionId));
        state.setPositionMs(snapshot.positionMs());
        state.setWatched(snapshot.watched());
        state.setWatchedAt(snapshot.watchedAt());
        state.setHiddenFromContinueWatching(snapshot.hiddenFromContinueWatching());
        state.setHiddenAt(snapshot.hiddenAt());
        states.save(state);
        events.publishEvent(new LibraryChanged());
        return snapshot(state);
    }

    private static PlaybackSnapshot snapshot(PlaybackState state) {
        return new PlaybackSnapshot(state.getPositionMs(), state.isWatched(), state.getWatchedAt(),
            state.isHiddenFromContinueWatching(), state.getHiddenAt());
    }

    private Edition edition(Long id) {
        return editions.findById(id).orElseThrow(() -> new NotFoundException("No such edition"));
    }
}
