package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies durable overlay revisions and exact idempotency replay across repository restarts. */
class DatabaseAgentTddStateRepositoryTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private EmbeddedDatabase database;
    private DatabaseAgentTddStateRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).build();
        repository = new DatabaseAgentTddStateRepository(new JdbcTemplate(database), mapper);
        repository.init();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void retainsOverlayRevisionAndExactReplayAcrossRestart() {
        var first = repository.save("tenant-a|test", "CASE_SET", "cases-1",
                mapper.valueToTree(Map.of("rows", 1)));
        var second = repository.save("tenant-a|test", "CASE_SET", "cases-1",
                mapper.valueToTree(Map.of("rows", 2)));
        repository.record("tenant-a|test", "rg.scenario.upsertCases", "idem-1", "sha256:req",
                mapper.valueToTree(Map.of("revision", first.revision())));

        DatabaseAgentTddStateRepository restarted = new DatabaseAgentTddStateRepository(
                new JdbcTemplate(database), mapper);
        restarted.init();

        assertThat(second.revision()).isEqualTo(2);
        assertThat(restarted.find("tenant-a|test", "CASE_SET", "cases-1"))
                .hasValueSatisfying(value -> assertThat(value.data().path("rows").asInt()).isEqualTo(2));
        assertThat(restarted.replay("tenant-a|test", "rg.scenario.upsertCases", "idem-1", "sha256:req"))
                .hasValueSatisfying(value -> assertThat(value.path("revision").asLong()).isEqualTo(1));
    }

    @Test
    void rejectsReuseOfIdempotencyKeyForDifferentMaterial() {
        repository.record("tenant-a|test", "rg.tool.compose", "idem-1", "sha256:first",
                mapper.valueToTree(Map.of("toolRef", "tool-1")));

        assertThatThrownBy(() -> repository.replay(
                "tenant-a|test", "rg.tool.compose", "idem-1", "sha256:other"))
                .isInstanceOf(AgentTddToolException.class)
                .hasMessageContaining("different request material");
    }
}
