package com.leanowtech.bloge.gateway.visual.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for H2-backed visual graph run history persistence.
 */
class DatabaseVisualGraphRunRepositoryTest {

    private DatabaseVisualGraphRunRepository repository;
    private JdbcTemplate jdbc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(dataSource);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        repository = new DatabaseVisualGraphRunRepository(jdbc, objectMapper);
        repository.init();
    }

    @Test
    void createAssignsRunIdAndPersistsRecord() {
        VisualGraphRunRecord stored = repository.create(record(""));

        assertThat(stored.runId()).isNotBlank();
        assertThat(stored.createdAt()).isNotNull();
        assertThat(repository.find(stored.runId())).contains(stored);
        assertThat(stored.contextSummary().toString()).doesNotContain("secret-token");
    }

    @Test
    void persistenceSurvivesReInit() {
        VisualGraphRunRecord stored = repository.create(record("run-1"));

        DatabaseVisualGraphRunRepository reloaded = new DatabaseVisualGraphRunRepository(jdbc, objectMapper);
        reloaded.init();

        assertThat(reloaded.find("run-1")).contains(stored);
        assertThat(reloaded.all()).containsExactly(stored);
    }

    @Test
    void queryFiltersRecordsByRunMetadata() {
        VisualGraphRunRecord storedDraft = repository.create(record("run-stored", "draft-1",
                VisualGraphRunRecord.SOURCE_STORED_DRAFT, "", true));
        repository.create(record("run-failed", "draft-1", VisualGraphRunRecord.SOURCE_STORED_DRAFT, "",
                false));
        VisualGraphRunRecord publication = repository.create(record("run-publication", "draft-1",
                VisualGraphRunRecord.SOURCE_PUBLICATION, "publication-1", true));

        assertThat(repository.query(new VisualGraphRunQuery("stored_draft", "draft-1", "", "visualPolicy",
                true, 10)))
                .containsExactly(storedDraft);
        assertThat(repository.query(new VisualGraphRunQuery("PUBLICATION", "", "publication-1", "",
                true, 1)))
                .containsExactly(publication);
    }

    @Test
    void createDoesNotOverwriteExistingRun() {
        repository.create(record("run-1"));

        assertThatThrownBy(() -> repository.create(record("run-1")))
                .isInstanceOf(RuntimeException.class);
    }

    private static VisualGraphRunRecord record(String runId) {
        return record(runId, "draft-1", VisualGraphRunRecord.SOURCE_STORED_DRAFT, "", true);
    }

    private static VisualGraphRunRecord record(String runId,
                                               String draftId,
                                               String sourceKind,
                                               String publicationId,
                                               boolean success) {
        GraphDraft draft = new GraphDraft(
                "",
                draftId,
                7,
                "visualPolicy",
                "tenant-a",
                "local",
                "prod",
                "",
                SchemaEnvelope.opaque(),
                List.of(),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("response", "")
        );
        VisualGraphRunResponse response = new VisualGraphRunResponse(
                true,
                true,
                success,
                "visualPolicy",
                "response",
                Map.of("decision", "approved"),
                Map.of("response", Map.of("decision", "approved")),
                Map.of("response", "COMPLETED"),
                19,
                List.of(),
                List.of(),
                null,
                null,
                "graph visualPolicy {}"
        );
        if (VisualGraphRunRecord.SOURCE_PUBLICATION.equals(sourceKind)) {
            return new VisualGraphRunRecord("", runId, sourceKind, draft.draftId(), draft.revision(),
                    publicationId, response.graphName(), draft.tenantId(), draft.namespace(), draft.environment(),
                    response.outputNode(), null, response.validated(), response.compiled(), response.success(),
                    response.elapsedMs(), response.statusMap(), response.diagnostics(), response.errors(),
                    Map.of("score", Map.of("type", "integer")), Map.of("type", "object"), Map.of(),
                    response.generatedDsl());
        }
        return VisualGraphRunRecord.storedDraft(draft, Map.of("score", 720, "apiToken", "secret-token"),
                response).withIdentity(runId, null);
    }
}
