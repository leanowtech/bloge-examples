package com.leanowtech.bloge.gateway.visual.draft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

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
 * Tests for H2-backed graph draft persistence.
 */
class DatabaseGraphDraftRepositoryTest {

    private DatabaseGraphDraftRepository repository;
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
        repository = new DatabaseGraphDraftRepository(jdbc, objectMapper);
        repository.init();
    }

    @Test
    void saveAssignsDraftIdAndRevision() {
        GraphDraft stored = repository.save(simpleDraft("", 0));

        assertThat(stored.draftId()).isNotBlank();
        assertThat(stored.revision()).isEqualTo(1);
        assertThat(repository.find(stored.draftId())).contains(stored);
    }

    @Test
    void saveExistingDraftIncrementsRevision() {
        GraphDraft first = repository.save(simpleDraft("draft-1", 0));

        GraphDraft second = repository.save(first);

        assertThat(second.draftId()).isEqualTo("draft-1");
        assertThat(second.revision()).isEqualTo(2);
    }

    @Test
    void saveIfRevisionUpdatesOnlyMatchingRevision() {
        GraphDraft first = repository.save(simpleDraft("draft-1", 0));

        GraphDraft updated = repository.saveIfRevision("draft-1", first.revision(),
                first.withIdentity("draft-1", first.revision()))
                .orElseThrow();

        assertThat(updated.revision()).isEqualTo(2);
        assertThat(repository.find("draft-1")).contains(updated);
    }

    @Test
    void saveIfRevisionRejectsStaleRevision() {
        GraphDraft first = repository.save(simpleDraft("draft-1", 0));

        assertThat(repository.saveIfRevision("draft-1", first.revision() + 1, first)).isEmpty();
        assertThat(repository.find("draft-1")).contains(first);
    }

    @Test
    void persistenceSurvivesReInit() {
        GraphDraft stored = repository.save(simpleDraft("draft-1", 0));

        DatabaseGraphDraftRepository reloaded = new DatabaseGraphDraftRepository(jdbc, objectMapper);
        reloaded.init();

        assertThat(reloaded.find("draft-1")).contains(stored);
        assertThat(reloaded.all()).hasSize(1);
    }

    @Test
    void saveRejectsRawSecretMaterial() {
        assertThatThrownBy(() -> repository.save(simpleDraft("draft-1", 0, Map.of(
                "apiKey", "sk-repositorySecret123456"
        ))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Raw secret material")
                .hasMessageNotContaining("sk-repositorySecret123456");
    }

    @Test
    void saveAllowsSecretReference() {
        GraphDraft stored = repository.save(simpleDraft("draft-1", 0, Map.of(
                "secretRef", "vault://gateway/service-token"
        )));

        assertThat(repository.find(stored.draftId())).contains(stored);
    }

    @Test
    void deleteRemovesDraft() {
        repository.save(simpleDraft("draft-1", 0));

        repository.delete("draft-1");

        assertThat(repository.find("draft-1")).isEmpty();
    }

    private static GraphDraft simpleDraft(String draftId, long revision) {
        return simpleDraft(draftId, revision, Map.of());
    }

    private static GraphDraft simpleDraft(String draftId, long revision, Map<String, Object> config) {
        return new GraphDraft(
                "",
                draftId,
                revision,
                "visualPolicy",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "response",
                        "bloge:transform",
                        "",
                        Map.of("score", GraphDraft.Binding.contextPath("score")),
                        config,
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("response", "")
        );
    }
}
