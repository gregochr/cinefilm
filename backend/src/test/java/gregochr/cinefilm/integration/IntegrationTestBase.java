package gregochr.cinefilm.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for Spring Boot integration tests that need a real PostgreSQL schema, built by Flyway exactly as
 * in production.
 *
 * <p>Why this exists:
 * <ul>
 *   <li>The default test profile runs on in-memory H2 ({@code MODE=PostgreSQL}) for speed and needs no Docker.
 *       H2 accepts things PostgreSQL rejects and differs in collation and case handling, so it cannot prove
 *       the migrations or the queries.</li>
 *   <li>Tests extending this class apply every migration to an empty PostgreSQL 17 container and run with
 *       Hibernate {@code ddl-auto=validate}, so any drift between the entities and the migrations fails here.</li>
 *   <li>These tests need Docker, which the dev Mac does not have. They live in the {@code integration} package,
 *       which the local gate excludes; CI runs them.</li>
 * </ul>
 *
 * <p>Activates two profiles via {@link ActiveProfiles}: {@code test} (the shared test settings: password, sample
 * video) and {@code integration-test} (a marker profile; {@code @DynamicPropertySource} below replaces the
 * H2-specific datasource settings with the container's).
 *
 * <p><strong>Why {@link DirtiesContext} is here, and why removing it breaks CI.</strong>
 * {@code @Testcontainers} plus a {@code static} {@code @Container} field means JUnit's
 * {@code TestcontainersExtension} owns the container's lifecycle: it stops the container in {@code afterAll} of
 * <em>every</em> test class and restarts it in the next class's {@code beforeAll} on a <b>new random host
 * port</b>. Spring's context cache does not restart with it. {@code @DynamicPropertySource} is evaluated only
 * when a context is <em>created</em>, so without this annotation a later class sharing the same context cache
 * key is handed the cached context, still holding the previous port in {@code spring.datasource.url}, and every
 * query fails with {@code Connection to localhost:NNNNN refused} after a 30 s Hikari timeout.
 *
 * <p>That was a real CI failure in goldenhour (2026-07-27). It presents as flakiness because it only fires when
 * two classes sharing a context cache key land in the same surefire fork. Evicting the context after each class
 * guarantees the next one re-reads the restarted container's port.
 *
 * <p>The obvious-looking alternative, the Testcontainers singleton pattern (dropping
 * {@code @Testcontainers}/{@code @Container} and starting the container once from a static initialiser), was
 * tried in goldenhour and <b>rejected on evidence</b>. The per-class restart is load-bearing: it hands each class
 * a virgin, freshly migrated database, so one class's committed rows never leak into another.
 */
@SpringBootTest
@ActiveProfiles({"test", "integration-test"})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class IntegrationTestBase {

    /**
     * Postgres 17-alpine container, the same major version as production. Static field, but note that this does
     * <em>not</em> mean one container per fork: the extension stops and restarts it around every test class,
     * which is what gives each class a clean database and why {@link DirtiesContext} above is mandatory.
     */
    @Container
    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("cinefilm_test")
            .withUsername("test")
            .withPassword("test");

    /**
     * Points the datasource at the container before the application context starts, turns Flyway on and switches
     * Hibernate to {@code validate} so the entities must agree with the migrations.
     *
     * @param registry the dynamic property registry supplied by Spring at boot
     */
    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }
}
