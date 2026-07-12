package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseIntegrationChangeEventOutboxTest {
    private JdbcTemplate jdbc;
    private ObjectMapper objectMapper;
    private DatabaseIntegrationChangeEventOutbox outbox;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build());
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        outbox = new DatabaseIntegrationChangeEventOutbox(jdbc, objectMapper);
        outbox.init();
    }

    @Test
    void persistsOrderedFactsAndFiltersTenantEnvironmentWithoutHidingGlobalAssets() {
        IntegrationChangeEvent tenantA = outbox.append(event("GRAPH_DRAFT_UPDATED", "tenant-a", "prod",
                "GRAPH_DRAFT", "draft-a", 2));
        outbox.append(event("GRAPH_DRAFT_UPDATED", "tenant-b", "prod",
                "GRAPH_DRAFT", "draft-b", 1));
        IntegrationChangeEvent global = outbox.append(event("OPERATOR_LIBRARY_UPDATED", "*", "*",
                "OPERATOR_LIBRARY", "shared-risk", 3));
        outbox.append(event("GRAPH_DRAFT_UPDATED", "tenant-a", "dev",
                "GRAPH_DRAFT", "draft-dev", 1));

        List<IntegrationChangeEvent> visible = outbox.read(0, outbox.highWaterSequence(),
                "tenant-a", "prod", 100);

        assertThat(visible).extracting(IntegrationChangeEvent::eventId)
                .containsExactly(tenantA.eventId(), global.eventId());
        assertThat(visible).extracting(IntegrationChangeEvent::streamSequence)
                .containsExactly(tenantA.streamSequence(), global.streamSequence());
        assertThat(visible).allSatisfy(event -> assertThat(event.fingerprintVerified()).isTrue());
        assertThat(outbox.hasAfter(tenantA.streamSequence(), outbox.highWaterSequence(),
                "tenant-a", "prod")).isTrue();
        assertThat(outbox.hasAfter(global.streamSequence(), outbox.highWaterSequence(),
                "tenant-a", "prod")).isFalse();
    }

    @Test
    void survivesRepositoryRestartAndRejectsTamperedStoredFacts() {
        IntegrationChangeEvent stored = outbox.append(event("RUN_COMPLETED", "tenant-a", "prod",
                "RUN", "run-1", 1));
        DatabaseIntegrationChangeEventOutbox reloaded = new DatabaseIntegrationChangeEventOutbox(jdbc,
                objectMapper);
        reloaded.init();

        assertThat(reloaded.read(0, reloaded.highWaterSequence(), "tenant-a", "prod", 10))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.eventId()).isEqualTo(stored.eventId());
                    assertThat(event.streamSequence()).isEqualTo(stored.streamSequence());
                });

        jdbc.update("UPDATE integration_change_outbox SET event_json = REPLACE(event_json, 'RUN_COMPLETED', 'RUN_TAMPERED')");

        assertThatThrownBy(() -> reloaded.read(0, reloaded.highWaterSequence(), "tenant-a", "prod", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fingerprint verification failed");
    }

    private static IntegrationChangeEvent event(String type,
                                                String tenant,
                                                String environment,
                                                String kind,
                                                String id,
                                                long sequence) {
        return IntegrationChangeEvent.pending(type, tenant, "knowledge", environment,
                new IntegrationChangeEvent.Aggregate(kind, id, sequence, "sha256:" + id),
                "/api/integration/assets/" + id, "trace-1");
    }
}
