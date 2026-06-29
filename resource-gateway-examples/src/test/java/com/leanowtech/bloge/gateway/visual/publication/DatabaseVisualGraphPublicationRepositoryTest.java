package com.leanowtech.bloge.gateway.visual.publication;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

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
 * Tests for H2-backed visual graph publication persistence.
 */
class DatabaseVisualGraphPublicationRepositoryTest {

    private DatabaseVisualGraphPublicationRepository repository;
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
        repository = new DatabaseVisualGraphPublicationRepository(jdbc, objectMapper);
        repository.init();
    }

    @Test
    void createAssignsPublicationIdAndPersistsArtifact() {
        VisualGraphPublication stored = repository.create(publication(""));

        assertThat(stored.publicationId()).isNotBlank();
        assertThat(stored.createdAt()).isNotNull();
        assertThat(repository.find(stored.publicationId())).contains(stored);
    }

    @Test
    void persistenceSurvivesReInit() {
        VisualGraphPublication stored = repository.create(publication("publication-1"));

        DatabaseVisualGraphPublicationRepository reloaded =
                new DatabaseVisualGraphPublicationRepository(jdbc, objectMapper);
        reloaded.init();

        assertThat(reloaded.find("publication-1")).contains(stored);
        assertThat(reloaded.all()).hasSize(1);
    }

    @Test
    void createDoesNotOverwriteExistingPublication() {
        repository.create(publication("publication-1"));

        assertThatThrownBy(() -> repository.create(publication("publication-1")))
                .isInstanceOf(RuntimeException.class);
    }

    private static VisualGraphPublication publication(String publicationId) {
        OperatorDefinition operator = VisualCatalogTestSupport.eligibilityOperator("integer");
        GraphDraft draft = new GraphDraft(
                "",
                "draft-1",
                3,
                "visualPolicy",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "eligibility",
                        "risk:eligibility",
                        "",
                        Map.of(
                                "score", GraphDraft.Binding.contextPath("score"),
                                "amount", GraphDraft.Binding.contextPath("amount")
                        ),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", ""),
                Map.of("eligibility", operator.fingerprint())
        );
        return new VisualGraphPublication(
                "",
                publicationId,
                draft.draftId(),
                draft.revision(),
                draft.graphName(),
                draft.tenantId(),
                draft.namespace(),
                draft.environment(),
                null,
                draft,
                List.of(operator),
                draft.operatorFingerprints(),
                draft.visualLayout(),
                "graph visualPolicy {}",
                new VisualValidationResult(true, List.of()),
                new DslGenerationResult(true, "graph visualPolicy {}", List.of())
        );
    }
}
