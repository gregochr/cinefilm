package gregochr.cinefilm.settings;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link ServerSetting}. */
public interface ServerSettingRepository extends JpaRepository<ServerSetting, Short> {
}
