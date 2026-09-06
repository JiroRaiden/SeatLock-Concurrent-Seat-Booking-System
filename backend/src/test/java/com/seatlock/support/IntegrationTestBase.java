package com.seatlock.support;

import com.redis.testcontainers.RedisContainer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for tests that need a real Postgres and a real Redis.
 *
 * <h2>Why containers and not H2 + an embedded Redis</h2>
 *
 * Because the things this project claims to guarantee are <em>database and Redis
 * behaviours</em>, and a substitute does not have them:
 *
 * <ul>
 *   <li><b>Partial unique indexes.</b> {@code CREATE UNIQUE INDEX ... WHERE active}
 *       is Postgres syntax. H2 cannot create it, so the single strongest oversell
 *       guarantee in the system would simply not exist under test - and a test
 *       suite that passes without the constraint it is meant to prove is worse
 *       than no test suite.</li>
 *   <li><b>MVCC and optimistic-lock timing.</b> The whole point of the
 *       concurrency test is what two transactions do to each other. H2's
 *       concurrency model is not Postgres's, so a green run would mean nothing
 *       about production.</li>
 *   <li><b>Lua script semantics.</b> Embedded Redis substitutes have historically
 *       stubbed or approximated EVAL. Our correctness argument rests on Redis
 *       executing a script atomically on a single thread. Testing that against a
 *       fake tests the fake.</li>
 *   <li><b>Flyway.</b> Running the real migrations against the real engine means
 *       the migrations themselves are under test. A syntax error in V1 fails the
 *       build rather than the deploy.</li>
 * </ul>
 *
 * <p>The cost is that these tests need Docker and take a few seconds to start.
 * That is why they are named {@code *IT} and run under Failsafe in
 * {@code mvn verify}, while the fast unit tests run under Surefire in
 * {@code mvn test}. The inner loop stays quick; CI runs everything.
 *
 * <h2>Why the containers are static</h2>
 *
 * A {@code static} field with a manual {@code start()} gives one Postgres and
 * one Redis for the entire test run, reused across every test class. Testcontainers
 * shuts them down through its Ryuk sidecar when the JVM exits. Starting a fresh
 * pair per class would add ~4 seconds per class for no isolation benefit - the
 * tests clean up their own data.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("seatlock_test")
                    .withUsername("test")
                    .withPassword("test")
                    // Postgres does not need to survive a crash in a test run, and
                    // turning fsync off makes the whole suite noticeably faster.
                    // Never, ever do this to a real database.
                    .withCommand("postgres", "-c", "fsync=off", "-c", "full_page_writes=off");

    protected static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    static {
        POSTGRES.start();
        REDIS.start();
    }

    /**
     * Feeds the container's randomly-assigned ports into Spring's environment.
     *
     * <p>Ports are random on purpose: a fixed 5432 would collide with a Postgres
     * already running on the developer's machine, and CI would fail in a way
     * that never reproduces locally.
     */
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
