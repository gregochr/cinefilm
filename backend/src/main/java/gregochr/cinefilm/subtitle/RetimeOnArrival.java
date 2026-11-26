package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.subtitle.SubtitleRetimeService.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Puts a subtitle on the film's timeline as it is stored (docs/engineering/subtitle-frame-rate-plan.md, "When it
 * runs"), only while {@code cinefilm.subtitles.retime} is {@code on}: with {@code off} or {@code restore} nothing
 * happens on arrival. It uses the same entries as {@link SubtitleRetimeJob}
 * ({@link SubtitleRetimeService#process}, {@link ReferenceRetimeService#process}), never a copy of them. A failure is
 * logged and leaves the file as stored: the job's next run retimes it. Nothing here throws to its caller.
 */
@Component
public class RetimeOnArrival {

    private static final Logger LOG = LoggerFactory.getLogger(RetimeOnArrival.class);

    private final SubtitleRetimeService downloads;
    private final ReferenceRetimeService references;
    private final RetimeMode mode;

    /** Creates the hook. */
    public RetimeOnArrival(SubtitleRetimeService downloads, ReferenceRetimeService references,
                           @Value("${cinefilm.subtitles.retime:on}") String setting) {
        this.downloads = downloads;
        this.references = references;
        this.mode = RetimeMode.parse(setting);
    }

    /**
     * A download was stored and its transaction has committed and the library lock been given back (call it after
     * both: a failure inside the store's own transaction would roll the download back). It is retimed by the job's own
     * entry, {@link SubtitleRetimeService#process}, which takes its own lock and transaction. A busy library or a
     * failure leaves the file as stored, for the job. A forced download on a film with an English track waits for the
     * timing fit, as for the job: it is left as stored and the job decides it once the fit is made.
     *
     * @param sidecarId the new download's row id
     */
    public void downloadArrived(Long sidecarId) {
        if (mode != RetimeMode.ON) {
            return;
        }
        try {
            downloads.process(sidecarId, RetimeMode.ON);
        } catch (RuntimeException e) {
            LOG.warn("Could not retime the new download {}: {}", sidecarId, e.getClass().getSimpleName());
        }
    }

    /**
     * A reference was just stored: its row is committed ({@code saveAndFlush}) and no caller of the fetcher holds a
     * transaction or the library lock. This takes the library lock and a transaction itself, and a busy library leaves
     * the reference for the job's next run.
     *
     * @param referenceId the new reference's row id
     * @return true when it was rewritten, so the caller should read it again
     */
    public boolean referenceArrived(long referenceId) {
        if (mode != RetimeMode.ON) {
            return false;
        }
        try {
            return references.process(referenceId, RetimeMode.ON) == Outcome.REWRITTEN;
        } catch (RuntimeException e) {
            LOG.warn("Could not retime the new reference {}: {}", referenceId, e.getClass().getSimpleName());
            return false;
        }
    }
}
