package gregochr.cinefilm.credits;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Persistence for {@link CreditsDetection}: one row per media file. */
public interface CreditsDetectionRepository extends JpaRepository<CreditsDetection, Long> {

    /**
     * The version of a file a stored result is for.
     *
     * @param mediaFileId the media file
     * @param fingerprint its fingerprint when analysed
     * @param analysisVersion the {@link CreditsTuning#ANALYSIS_VERSION} it was analysed with
     */
    record Stored(Long mediaFileId, String fingerprint, int analysisVersion) {
    }

    /** Every stored result's file, fingerprint and analysis version, without the reasons, in one query. */
    @Query("select new gregochr.cinefilm.credits.CreditsDetectionRepository$Stored(d.mediaFileId, d.fingerprint, "
        + "d.analysisVersion) from CreditsDetection d")
    List<Stored> findAllStored();
}
