package gregochr.cinefilm.credits;

import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.credits.CreditsAdminDtos.AutoCandidate;
import gregochr.cinefilm.credits.CreditsApply.AutoInputs;
import gregochr.cinefilm.credits.CreditsApply.AutoMarker;
import gregochr.cinefilm.credits.CreditsDenyFile.DenyList;
import gregochr.cinefilm.credits.CreditsDenyFile.Parsed;
import gregochr.cinefilm.film.CreditsMarkerSource;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.plex.PlexCreditsMarker;
import gregochr.cinefilm.plex.PlexCreditsMarkerRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The editions step 3 of {@link CreditsApply} would mark {@code AUTO}, for the owner's G1 stills check and the G2
 * comparison. It runs the pass's own step 3 over the pass's own editions (every edition, hidden ones included, in
 * the pass's batches, through {@link CreditsAutoReader} and {@link CreditsApplier#plexRows}), as if the switch were
 * on and as if each edition's source were null, so an owner's marker does not hide an edition. Plex's marker still
 * wins, as in the pass, so an edition with a usable Plex row is never a candidate: every candidate not flagged
 * {@code owner} is exactly what a pass sets {@code AUTO}. It uses the deny list the latest pass kept in force and
 * never reads the file, and it writes nothing and takes no library lock.
 */
@Service
public class CreditsAutoCandidates {

    private final EditionRepository editions;
    private final PlexCreditsMarkerRepository markers;
    private final CreditsDenyListKeeper denyList;
    private final CreditsAutoReader auto;
    private final TransactionTemplate readTx;

    /** Creates the service. */
    public CreditsAutoCandidates(EditionRepository editions, PlexCreditsMarkerRepository markers,
                                 CreditsDenyListKeeper denyList, CreditsAutoReader auto,
                                 PlatformTransactionManager transactions) {
        this.editions = editions;
        this.markers = markers;
        this.denyList = denyList;
        this.auto = auto;
        this.readTx = new TransactionTemplate(transactions);
        this.readTx.setReadOnly(true);
    }

    /**
     * Every candidate, in edition id order.
     *
     * @return the candidates
     * @throws ConflictException ({@code No deny list}) when no pass has a deny list in force
     */
    public List<AutoCandidate> candidates() {
        // One snapshot of the list in force, whole; the file is never read here.
        Parsed inForce = denyList.current();
        if (inForce == null) {
            throw new ConflictException("No deny list");
        }
        DenyList list = inForce.list();
        Map<Long, String> listedUnitKeys = auto.candidateListedUnitKeys(list);
        List<Long> ids = editions.findAllIds();
        List<AutoCandidate> found = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += CreditsApplier.BATCH_SIZE) {
            List<Long> batch = ids.subList(from, Math.min(ids.size(), from + CreditsApplier.BATCH_SIZE));
            found.addAll(readTx.execute(status -> batch(batch, list, listedUnitKeys)));
        }
        found.sort(Comparator.comparingLong(AutoCandidate::editionId));
        return found;
    }

    private List<AutoCandidate> batch(List<Long> batch, DenyList list, Map<Long, String> listedUnitKeys) {
        List<Edition> loaded = editions.findWithFilesByIdIn(batch);
        Map<Long, PlexCreditsMarker> rows = CreditsApplier.plexRows(markers, loaded);
        CreditsAutoReader.Lookup lookup = auto.candidateLookup(loaded, list, listedUnitKeys);
        List<AutoCandidate> found = new ArrayList<>();
        for (Edition edition : loaded) {
            AutoInputs inputs = lookup.inputs(edition);
            AutoMarker marker = CreditsApplier.candidateOf(edition, rows, inputs);
            if (marker == null) {
                continue;
            }
            List<MediaFile> originals = edition.originalParts();
            MediaFile last = originals.get(originals.size() - 1);
            found.add(new AutoCandidate(edition.getId(), inputs.filmId(), last.getId(), last.getPath(),
                marker.offsetMs(), marker.kind(), marker.startMs(), marker.markerMs(),
                edition.getCreditsMarkerSource() == CreditsMarkerSource.OWNER));
        }
        return found;
    }
}
