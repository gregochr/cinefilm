package gregochr.cinefilm.journey;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Stores the kinds of laptop that logged journeys. */
public interface DeviceRepository extends JpaRepository<Device, Long> {

    /** The stored device of exactly this kind (the five columns are unique together), if there is one. */
    Optional<Device> findByModelAndCpuAndCoresAndThreadsAndMemoryBytes(
        String model, String cpu, int cores, int threads, long memoryBytes);
}
