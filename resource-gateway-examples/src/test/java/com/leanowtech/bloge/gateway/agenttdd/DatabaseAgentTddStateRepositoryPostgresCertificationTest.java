package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Native PostgreSQL certification for Agent TDD idempotency transactions.
 *
 * <p>The competing transaction must wait for the original reservation and then return its exact
 * committed response. This proves that PostgreSQL's unique-key conflict does not abort the replay
 * transaction and that the protected business action runs once across replicas.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
class DatabaseAgentTddStateRepositoryPostgresCertificationTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private EmbeddedPostgres postgres;

    @BeforeAll
    void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder()
                .setServerConfig("fsync", "on")
                .setServerConfig("synchronous_commit", "on")
                .setServerConfig("lock_timeout", "5s")
                .start();
    }

    @AfterAll
    void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @Test
    void concurrentSameKeyReturnsTheExactCommittedReplayWithoutAbortingTheTransaction()
            throws Exception {
        DataSource dataSource = postgres.getPostgresDatabase();
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/postgresql/V20260903_020__agent_tdd_runtime.sql")).execute(dataSource);
        Replica first = replica(dataSource);
        Replica second = replica(dataSource);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstActionStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstAction = new CountDownLatch(1);
        CountDownLatch secondTransactionStarted = new CountDownLatch(1);
        AtomicInteger actions = new AtomicInteger();

        try {
            Future<JsonNode> original = executor.submit(() -> first.transactions().execute(status ->
                    first.repository().executeOnce("tenant|project|test", "rg.tool.publish",
                            "publish-once", "sha256:request", () -> {
                                actions.incrementAndGet();
                                firstActionStarted.countDown();
                                await(releaseFirstAction);
                                return mapper.valueToTree(Map.of("publicationId", "pg-pub-1"));
                            })));
            assertThat(firstActionStarted.await(10, TimeUnit.SECONDS)).isTrue();

            Future<JsonNode> replay = executor.submit(() -> {
                secondTransactionStarted.countDown();
                return second.transactions().execute(status -> second.repository().executeOnce(
                        "tenant|project|test", "rg.tool.publish", "publish-once",
                        "sha256:request", () -> {
                            actions.incrementAndGet();
                            return mapper.valueToTree(Map.of("publicationId", "must-not-run"));
                        }));
            });
            assertThat(secondTransactionStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> replay.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseFirstAction.countDown();
            JsonNode committed = original.get(15, TimeUnit.SECONDS);
            JsonNode repeated = replay.get(15, TimeUnit.SECONDS);

            assertThat(repeated).isEqualTo(committed);
            assertThat(repeated.path("publicationId").asText()).isEqualTo("pg-pub-1");
            assertThat(actions).hasValue(1);
            assertThat(new JdbcTemplate(dataSource).queryForObject("""
                    SELECT COUNT(*) FROM agent_tdd_idempotency
                     WHERE scope_key = ? AND operation = ? AND idempotency_key = ?
                       AND completed = TRUE
                    """, Long.class, "tenant|project|test", "rg.tool.publish", "publish-once"))
                    .isEqualTo(1L);
        } finally {
            releaseFirstAction.countDown();
            executor.shutdownNow();
        }
    }

    private Replica replica(DataSource dataSource) {
        DatabaseAgentTddStateRepository repository = new DatabaseAgentTddStateRepository(
                new JdbcTemplate(dataSource), mapper);
        repository.init();
        return new Replica(repository,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out while holding the idempotency reservation.");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding the idempotency reservation.", failure);
        }
    }

    private record Replica(
            DatabaseAgentTddStateRepository repository,
            TransactionTemplate transactions) {
    }
}
