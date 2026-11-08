package gregochr.cinefilm.playback;

import gregochr.cinefilm.film.FilmDtos.Preferences;
import gregochr.cinefilm.playback.PlaybackDtos.PreferencesRequest;
import gregochr.cinefilm.playback.PlaybackDtos.ProgressRequest;
import gregochr.cinefilm.playback.PlaybackDtos.ProgressResponse;
import gregochr.cinefilm.playback.PlaybackDtos.WatchedRequest;
import gregochr.cinefilm.playback.PlaybackDtos.WatchedResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Progress, watched and preferences endpoints. */
@RestController
public class PlaybackController {

    private final PlaybackService playback;
    private final PreferencesService preferences;

    public PlaybackController(PlaybackService playback, PreferencesService preferences) {
        this.playback = playback;
        this.preferences = preferences;
    }

    @PutMapping("/api/editions/{id}/progress")
    ProgressResponse progress(@PathVariable Long id, @RequestBody ProgressRequest body) {
        return playback.reportProgress(id, body.positionMs());
    }

    @PostMapping("/api/editions/{id}/watched")
    WatchedResponse watched(@PathVariable Long id, @RequestBody WatchedRequest body) {
        return playback.setWatched(id, body.watched());
    }

    @PutMapping("/api/films/{id}/preferences")
    Preferences preferences(@PathVariable Long id, @RequestBody PreferencesRequest body) {
        return preferences.update(id, body);
    }
}
