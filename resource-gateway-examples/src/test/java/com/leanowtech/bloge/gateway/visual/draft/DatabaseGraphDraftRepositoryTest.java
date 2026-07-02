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
    void revisionsReturnNewestSnapshotsFirst() {
        GraphDraft first = repository.save(simpleDraft("draft-1", 0));
        GraphDraft second = repository.save(simpleDraft("draft-1", first.revision(), Map.of("mode", "review")));

        assertThat(repository.revisions("draft-1"))
                .extracting(GraphDraft::revision)
                .containsExactly(second.revision(), first.revision());
        assertThat(repository.findRevision("draft-1", first.revision()))
                .contains(first);
        assertThat(repository.findRevision("draft-1", second.revision()))
                .contains(second);
    }

    @Test
    void revisionsPersistAuditMetadata() {
        GraphDraft first = repository.save(simpleDraft("draft-1", 0)
                .withRevisionMetadata(GraphDraft.RevisionMetadata.patch(
                        "alice@example.com",
                        "browser-create",
                        "Initial canvas draft",
                        List.of("/")
                )));
        GraphDraft second = repository.saveIfRevision("draft-1", first.revision(),
                first.withRevisionMetadata(GraphDraft.RevisionMetadata.patch(
                        "bob@example.com",
                        "browser-save",
                        "Tune policy node",
                        List.of("/nodes/0/config/mode")
                ))).orElseThrow();

        DatabaseGraphDraftRepository reloaded = new DatabaseGraphDraftRepository(jdbc, objectMapper);
        reloaded.init();

        GraphDraft reloadedSecond = reloaded.findRevision("draft-1", second.revision()).orElseThrow();
        assertThat(reloadedSecond.revisionMetadata().createdAt()).isEqualTo(first.revisionMetadata().createdAt());
        assertThat(reloadedSecond.revisionMetadata().createdBy()).isEqualTo("alice@example.com");
        assertThat(reloadedSecond.revisionMetadata().updatedBy()).isEqualTo("bob@example.com");
        assertThat(reloadedSecond.revisionMetadata().changeSource()).isEqualTo("browser-save");
        assertThat(reloadedSecond.revisionMetadata().changeSummary()).isEqualTo("Tune policy node");
        assertThat(reloadedSecond.revisionMetadata().changedPaths()).containsExactly("/nodes/0/config/mode");
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
        assertThat(reloaded.findRevision("draft-1", stored.revision())).contains(stored);
        assertThat(reloaded.all()).hasSize(1);
    }

    @Test
    void deleteRemovesCurrentDraftButKeepsRevisionHistory() {
        GraphDraft stored = repository.save(simpleDraft("draft-1", 0));

        repository.delete(stored.draftId(), GraphDraft.RevisionMetadata.patch(
                "reviewer",
                "retention-test",
                "Deleted during audit test.",
                List.of("/")
        ));

        assertThat(repository.find(stored.draftId())).isEmpty();
        assertThat(repository.revisions(stored.draftId()))
                .extracting(GraphDraft::revision)
                .containsExactly(stored.revision() + 1, stored.revision());
        GraphDraft deleteRevision = repository.findRevision(stored.draftId(), stored.revision() + 1).orElseThrow();
        assertThat(deleteRevision.revisionMetadata().updatedBy()).isEqualTo("reviewer");
        assertThat(deleteRevision.revisionMetadata().changeSource()).isEqualTo("retention-test");
        assertThat(deleteRevision.revisionMetadata().changeSummary()).isEqualTo("Deleted during audit test.");
    }

    @Test
    void saveAfterDeleteAdvancesPastPreservedRevisionHistory() {
        GraphDraft stored = repository.save(simpleDraft("draft-1", 0));
        repository.delete(stored.draftId());

        GraphDraft recreated = repository.save(simpleDraft("draft-1", 0));

        assertThat(recreated.revision()).isEqualTo(stored.revision() + 2);
        assertThat(repository.revisions(stored.draftId()))
                .extracting(GraphDraft::revision)
                .containsExactly(recreated.revision(), stored.revision() + 1, stored.revision());
    }

    @Test
    void historySummarizesActiveAndDeletedDrafts() {
        GraphDraft active = repository.save(simpleDraft("active-draft", 0));
        GraphDraft deleted = repository.save(simpleDraft("deleted-draft", 0));
        repository.delete(deleted.draftId(), GraphDraft.RevisionMetadata.patch(
                "auditor",
                "history-index-test",
                "Retain deleted draft history.",
                List.of("/")
        ));

        List<GraphDraftHistorySummary> history = repository.history();

        assertThat(history)
                .extracting(GraphDraftHistorySummary::draftId)
                .contains("active-draft", "deleted-draft");
        assertThat(history)
                .filteredOn(summary -> summary.draftId().equals(active.draftId()))
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.active()).isTrue();
                    assertThat(summary.currentRevision()).isEqualTo(active.revision());
                    assertThat(summary.latestRevision()).isEqualTo(active.revision());
                });
        assertThat(history)
                .filteredOn(summary -> summary.draftId().equals(deleted.draftId()))
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.active()).isFalse();
                    assertThat(summary.currentRevision()).isZero();
                    assertThat(summary.latestRevision()).isEqualTo(deleted.revision() + 1);
                    assertThat(summary.revisionCount()).isEqualTo(2);
                    assertThat(summary.updatedBy()).isEqualTo("auditor");
                    assertThat(summary.changeSource()).isEqualTo("history-index-test");
                });
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
