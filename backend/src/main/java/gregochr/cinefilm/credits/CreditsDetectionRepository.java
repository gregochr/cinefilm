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
     */
    record Stored(Long mediaFileId, String fingerprint) {
    }

    /** Every stored result's file and fingerprint, without the reasons, in one query. */
    @Query("select new gregochr.cinefilm.credits.CreditsDetectionRepository$Stored(d.mediaFileId, d.fingerprint) "
        + "from CreditsDetection d")
    List<Stored> findAllStored();
}
