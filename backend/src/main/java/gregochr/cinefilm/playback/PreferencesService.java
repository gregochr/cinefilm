package gregochr.cinefilm.playback;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.AudioStream;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmDtos.Preferences;
import gregochr.cinefilm.film.FilmPreference;
import gregochr.cinefilm.film.FilmPreferenceRepository;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.transcode.Ladder;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Remembers and resolves per-film choices: edition, quality, audio track and subtitles. */
@Service
public class PreferencesService {

    /** Quality identifiers the API accepts. */
    public static final List<String> QUALITIES = Ladder.QUALITY_IDS;

    private final FilmRepository films;
    private final FilmPreferenceRepository preferences;

    /** Creates the service over the film and preference repositories. */
    public PreferencesService(FilmRepository films, FilmPreferenceRepository preferences) {
        this.films = films;
        this.preferences = preferences;
    }

    /**
     * Resolves the stored choices, falling back to sensible defaults. The subtitle has none: null means the viewer
     * has never chosen (decision 68), which the player reads as Off unless the film needs full subtitles. Call inside
     * a transaction.
     */
    public Preferences resolve(Film film) {
        FilmPreference stored = preferences.findById(film.getId()).orElse(new FilmPreference(film.getId()));
        Edition edition = firstValid(film, stored.getLastEditionId(), stored.getDefaultEditionId());
        Long audio = stored.getAudioStreamId() != null ? stored.getAudioStreamId() : defaultAudio(edition);
        return new Preferences(edition == null ? null : edition.getId(),
            stored.getQuality() == null ? "auto" : stored.getQuality(), audio,
            stored.getSubtitle());
    }

    /** Applies the provided fields (null means unchanged) and returns the stored preferences. */
    @Transactional
    public Preferences update(Long filmId, PlaybackDtos.PreferencesRequest request) {
        Film film = films.findById(filmId).orElseThrow(() -> new NotFoundException("No such film"));
        FilmPreference stored = preferences.findById(filmId).orElse(new FilmPreference(filmId));
        if (request.editionId() != null) {
            Edition edition = film.getEditions().stream()
                .filter(e -> e.getId().equals(request.editionId())).findFirst()
                .orElseThrow(() -> new BadRequestException("Edition does not belong to this film"));
            stored.setLastEditionId(edition.getId());
            if (stored.getDefaultEditionId() == null) {
                stored.setDefaultEditionId(edition.getId());
            }
        }
        if (request.quality() != null) {
            String quality = request.quality().toLowerCase(Locale.ROOT);
            if (!QUALITIES.contains(quality)) {
                throw new BadRequestException("quality must be one of " + QUALITIES);
            }
            stored.setQuality(quality);
        }
        if (request.audioStreamId() != null) {
            stored.setAudioStreamId(request.audioStreamId());
        }
        if (request.subtitle() != null) {
            stored.setSubtitle(request.subtitle());
        }
        preferences.save(stored);
        return resolve(film);
    }

    /** Remembers the edition last played for a film. */
    @Transactional
    public void rememberLastEdition(Long filmId, Long editionId) {
        FilmPreference stored = preferences.findById(filmId).orElse(new FilmPreference(filmId));
        stored.setLastEditionId(editionId);
        preferences.save(stored);
    }

    private static Edition firstValid(Film film, Long... ids) {
        for (Long id : ids) {
            Optional<Edition> match = film.getEditions().stream().filter(e -> e.getId().equals(id)).findFirst();
            if (match.isPresent()) {
                return match.get();
            }
        }
        return film.getEditions().isEmpty() ? null : film.getEditions().get(0);
    }

    private static Long defaultAudio(Edition edition) {
        MediaFile original = edition == null ? null : edition.originalFile();
        if (original == null) {
            return null;
        }
        return original.getAudioStreams().stream().filter(AudioStream::isDefault).findFirst()
            .or(() -> original.getAudioStreams().stream().findFirst())
            .map(AudioStream::getId).orElse(null);
    }
}
