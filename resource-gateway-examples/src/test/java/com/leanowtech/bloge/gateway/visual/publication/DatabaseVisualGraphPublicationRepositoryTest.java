package com.leanowtech.bloge.gateway.visual.publication;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDependencyReport;
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
    void persistsDesignPublicationArtifactKind() {
        VisualGraphPublication executable = publication("design-publication-1");
        VisualGraphPublication design = VisualGraphPublication.design(
                executable.draft(),
                executable.operatorSnapshots(),
                executable.validation(),
                new DslGenerationResult(false, "", List.of())
        ).withIdentity(executable.publicationId(), null);

        VisualGraphPublication stored = repository.create(design);

        DatabaseVisualGraphPublicationRepository reloaded =
                new DatabaseVisualGraphPublicationRepository(jdbc, objectMapper);
        reloaded.init();

        assertThat(stored.artifactKind()).isEqualTo(VisualGraphPublication.ARTIFACT_DESIGN);
        assertThat(stored.designArtifact()).isTrue();
        assertThat(reloaded.find(stored.publicationId()))
                .get()
                .extracting(VisualGraphPublication::artifactKind)
                .isEqualTo(VisualGraphPublication.ARTIFACT_DESIGN);
    }

    @Test
    void persistsFrozenDependencyReport() {
        VisualGraphPublication stored = repository.create(publication("publication-with-dependencies"));

        DatabaseVisualGraphPublicationRepository reloaded =
                new DatabaseVisualGraphPublicationRepository(jdbc, objectMapper);
        reloaded.init();

        assertThat(reloaded.find(stored.publicationId()))
                .get()
                .extracting(VisualGraphPublication::dependencyReport)
                .satisfies(report -> {
                    assertThat(report.draftId()).isEqualTo("draft-1");
                    assertThat(report.operatorDependencyCount()).isEqualTo(1);
                    assertThat(report.runtimeReadinessStateCounts())
                            .containsEntry("RUNTIME_EXECUTABLE", 1);
                });
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
        return VisualGraphPublication.from(
                draft,
                List.of(operator),
                new VisualValidationResult(true, List.of()),
                new DslGenerationResult(true, "graph visualPolicy {}", List.of()),
                GraphDraftDependencyReport.from(draft, VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")))
        ).withIdentity(publicationId, null);
    }
}
