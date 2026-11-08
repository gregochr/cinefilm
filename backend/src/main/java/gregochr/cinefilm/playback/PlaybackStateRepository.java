package gregochr.cinefilm.playback;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link PlaybackState}. */
public interface PlaybackStateRepository extends JpaRepository<PlaybackState, Long> {
}
