package gregochr.cinefilm.plex;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link PlexCreditsMarker}: one row per media file. */
public interface PlexCreditsMarkerRepository extends JpaRepository<PlexCreditsMarker, Long> {
}
