package gregochr.cinefilm.credits;

import gregochr.cinefilm.credits.CreditsApply.AutoInputs;
import gregochr.cinefilm.credits.CreditsApply.Detection;
import gregochr.cinefilm.credits.CreditsApply.UnitInputs;
import gregochr.cinefilm.credits.CreditsDenyFile.DenyList;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.EditionRepository.EditionFilmRow;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmRepository.UnitRow;
import gregochr.cinefilm.film.MediaFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads what step 3 of {@link CreditsApply} needs beyond Plex's rows, for the pass and for Admin Clear alike, so
 * both go through this one code: the last parts' detection rows, each edition's film and unit key inputs, the
 * switch and the cards flag, and the current unit key of every film the deny list names. A batch costs a fixed
 * number of statements (one for the detection rows, one for the film rows) however many editions it holds, and none
 * when step 3 cannot apply (the switch is off or there is no deny list).
 */
@Component
public class CreditsAutoReader {

    /** How many listed films one statement asks for. */
    static final int FILM_CHUNK = 500;

    private final EditionRepository editions;
    private final FilmRepository films;
    private final CreditsDetectionRepository detections;
    private final boolean enabled;
    private final boolean cards;

    /** Creates the reader; the switch and the cards flag are {@code cinefilm.credits.auto.enabled}/{@code .cards}. */
    public CreditsAutoReader(EditionRepository editions, FilmRepository films,
                             CreditsDetectionRepository detections,
                             @Value("${cinefilm.credits.auto.enabled:false}") boolean enabled,
                             @Value("${cinefilm.credits.auto.cards:true}") boolean cards) {
        this.editions = editions;
        this.films = films;
        this.detections = detections;
        this.enabled = enabled;
        this.cards = cards;
    }

    /** Whether the switch is on. */
    public boolean enabled() {
        return enabled;
    }

    /** Whether step 3 can apply at all: the switch is on and a deny list is in force. */
    public boolean active(DenyList list) {
        return enabled && list != null;
    }

    /**
     * The current unit key of every film the list names (rule 6), by film id. Empty, with no statement, when step 3
     * cannot apply or the list names no film.
     */
    public Map<Long, String> listedUnitKeys(DenyList list) {
        return listedUnitKeys(list, enabled);
    }

    /** {@link #listedUnitKeys} as if the switch were on: for the candidates list. */
    public Map<Long, String> candidateListedUnitKeys(DenyList list) {
        return listedUnitKeys(list, true);
    }

    private Map<Long, String> listedUnitKeys(DenyList list, boolean on) {
        Map<Long, String> keys = new HashMap<>();
        if (!on || list == null) {
            return keys;
        }
        List<Long> ids = list.films().stream().sorted().toList();
        for (int from = 0; from < ids.size(); from += FILM_CHUNK) {
            for (UnitRow row : films.findUnitRowsByIdIn(ids.subList(from, Math.min(ids.size(), from + FILM_CHUNK)))) {
                keys.put(row.filmId(), new UnitInputs(row.collectionTmdbId(), row.collectionId(), row.tmdbId(),
                    row.title(), row.year()).unitKey());
            }
        }
        return keys;
    }

    /**
     * Reads the detection rows and film rows of these editions: two statements, however many editions.
     *
     * @param loaded the editions, with their files
     * @param list the deny list in force; null when there is none
     * @param listedUnitKeys {@link #listedUnitKeys} of that list
     * @return the lookup that answers each edition's {@link AutoInputs}
     */
    public Lookup lookup(List<Edition> loaded, DenyList list, Map<Long, String> listedUnitKeys) {
        return lookup(loaded, list, listedUnitKeys, enabled);
    }

    /** {@link #lookup} as if the switch were on (its inputs say so): for the candidates list. */
    public Lookup candidateLookup(List<Edition> loaded, DenyList list, Map<Long, String> listedUnitKeys) {
        return lookup(loaded, list, listedUnitKeys, true);
    }

    private Lookup lookup(List<Edition> loaded, DenyList list, Map<Long, String> listedUnitKeys, boolean on) {
        if (!on || list == null) {
            return new Lookup(this, on, list, listedUnitKeys, Map.of(), Map.of());
        }
        List<Long> lastPartIds = new ArrayList<>();
        List<Long> editionIds = new ArrayList<>();
        for (Edition edition : loaded) {
            List<MediaFile> originals = edition.originalParts();
            if (!originals.isEmpty()) {
                lastPartIds.add(originals.get(originals.size() - 1).getId());
            }
            editionIds.add(edition.getId());
        }
        Map<Long, Detection> rows = lastPartIds.isEmpty() ? Map.of()
            : detections.findAllById(lastPartIds).stream().collect(Collectors.toMap(CreditsDetection::getMediaFileId,
                d -> new Detection(d.getAnalysisVersion(), d.getVerdict(), d.getOffsetMs(), d.getFingerprint(),
                    d.getReasons())));
        Map<Long, EditionFilmRow> filmRows = editionIds.isEmpty() ? Map.of()
            : editions.findFilmRowsByIdIn(editionIds).stream()
                .collect(Collectors.toMap(EditionFilmRow::editionId, r -> r));
        return new Lookup(this, on, list, listedUnitKeys, rows, filmRows);
    }

    /** One batch's reads; answers each edition's step 3 inputs. */
    public static final class Lookup {

        private final CreditsAutoReader reader;
        private final boolean on;
        private final DenyList list;
        private final Map<Long, String> listedUnitKeys;
        private final Map<Long, Detection> detections;
        private final Map<Long, EditionFilmRow> filmRows;

        private Lookup(CreditsAutoReader reader, boolean on, DenyList list, Map<Long, String> listedUnitKeys,
                       Map<Long, Detection> detections, Map<Long, EditionFilmRow> filmRows) {
            this.reader = reader;
            this.on = on;
            this.list = list;
            this.listedUnitKeys = listedUnitKeys;
            this.detections = detections;
            this.filmRows = filmRows;
        }

        /**
         * The edition's step 3 inputs.
         *
         * @param edition an edition of the lookup's batch, with its files loaded
         * @return the inputs; null when step 3 cannot apply (the switch is off, no deny list in force, or the edition's
         *     film is gone), which {@link CreditsApply#apply} treats as step 3 off
         */
        public AutoInputs inputs(Edition edition) {
            EditionFilmRow film = filmRows.get(edition.getId());
            if (film == null) {
                return null;
            }
            List<MediaFile> originals = edition.originalParts();
            Detection detection = originals.isEmpty() ? null
                : detections.get(originals.get(originals.size() - 1).getId());
            return new AutoInputs(on, reader.cards, list, edition.getId(), film.filmId(),
                new UnitInputs(film.collectionTmdbId(), film.collectionId(), film.filmTmdbId(), film.title(),
                    film.year()), listedUnitKeys, detection);
        }
    }
}
