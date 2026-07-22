package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseCapabilitySnapshotRepositoryTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DatabaseCapabilitySnapshotRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).build();
        jdbc = new JdbcTemplate(database);
        repository = new DatabaseCapabilitySnapshotRepository(jdbc, mapper);
        repository.init();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void persistsExactRevisionsAcrossRepositoryInstances() {
        CapabilitySnapshot draft = draft(scope("org-a"), 1, Instant.parse("2026-07-22T00:00:00Z"));

        repository.create(draft);
        DatabaseCapabilitySnapshotRepository restarted = new DatabaseCapabilitySnapshotRepository(jdbc, mapper);
        restarted.init();

        assertThat(restarted.find(draft.scope(), draft.capabilityId(), 1)).contains(draft);
        assertThat(restarted.findLatest(draft.scope(), draft.capabilityId())).contains(draft);
    }

    @Test
    void isolatesIdenticalCapabilityIdsByCompleteEnterpriseScope() {
        CapabilitySnapshot orgA = draft(scope("org-a"), 1, Instant.parse("2026-07-22T00:00:00Z"));
        CapabilitySnapshot orgB = draft(scope("org-b"), 1, Instant.parse("2026-07-22T00:00:00Z"));

        repository.create(orgA);
        repository.create(orgB);

        assertThat(repository.find(scope("org-a"), orgA.capabilityId(), 1)).contains(orgA);
        assertThat(repository.find(scope("org-b"), orgB.capabilityId(), 1)).contains(orgB);
        assertThat(repository.find(new CapabilitySnapshot.Scope(
                "tenant-a", "org-a", "other-project", "test", "sg"), orgA.capabilityId(), 1))
                .isEmpty();
    }

    @Test
    void appendsReviewedAndActiveLifecycleRevisionsAndMakesExactRetriesIdempotent() {
        CapabilitySnapshot draft = draft(scope("org-a"), 1, Instant.parse("2026-07-22T00:00:00Z"));
        CapabilitySnapshot reviewed = CapabilitySnapshotLifecycle.transition(mapper, draft,
                CapabilitySnapshot.Lifecycle.REVIEWED, 2, "reviewer-a",
                Instant.parse("2026-07-22T01:00:00Z"), null, "",
                Instant.parse("2026-07-22T01:00:01Z"));
        CapabilitySnapshot active = CapabilitySnapshotLifecycle.transition(mapper, reviewed,
                CapabilitySnapshot.Lifecycle.ACTIVE, 3, "owner-a",
                Instant.parse("2026-07-22T02:00:00Z"), null, "",
                Instant.parse("2026-07-22T02:00:01Z"));

        assertThat(repository.create(draft)).isEqualTo(draft);
        assertThat(repository.create(draft)).isEqualTo(draft);
        repository.create(reviewed);
        repository.create(active);

        assertThat(repository.findLatest(draft.scope(), draft.capabilityId())).contains(active);
    }

    @Test
    void rejectsNonDraftFirstRevisionGapsAndConflictingExactRetries() {
        CapabilitySnapshot draft = draft(scope("org-a"), 1, Instant.parse("2026-07-22T00:00:00Z"));
        CapabilitySnapshot reviewed = CapabilitySnapshotLifecycle.transition(mapper, draft,
                CapabilitySnapshot.Lifecycle.REVIEWED, 2, "reviewer-a",
                Instant.parse("2026-07-22T01:00:00Z"), null, "",
                Instant.parse("2026-07-22T01:00:01Z"));

        assertThatThrownBy(() -> repository.create(reviewed))
                .hasMessage("first capability snapshot revision must be revision 1 in DRAFT lifecycle");
        repository.create(draft);

        CapabilitySnapshot gap = draft(scope("org-a"), 3, Instant.parse("2026-07-22T03:00:00Z"));
        assertThatThrownBy(() -> repository.create(gap))
                .hasMessage("snapshot revisions must be contiguous");

        CapabilitySnapshot conflicting = copyDraft(draft,
                new CapabilitySnapshot.Ownership("another-owner", "team-a", "pager-a"));
        assertThatThrownBy(() -> repository.create(conflicting))
                .hasMessage("Capability snapshot revision already exists with different content");
    }

    @Test
    void refusesCorruptStoredSnapshotInsteadOfServingIt() {
        CapabilitySnapshot draft = draft(scope("org-a"), 1, Instant.parse("2026-07-22T00:00:00Z"));
        repository.create(draft);
        jdbc.update("UPDATE capability_snapshots SET snapshot_json = '{}' WHERE capability_id = ?",
                draft.capabilityId());

        assertThatThrownBy(() -> repository.find(draft.scope(), draft.capabilityId(), 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stored capability snapshot failed integrity validation");
    }

    private CapabilitySnapshot copyDraft(CapabilitySnapshot source,
                                         CapabilitySnapshot.Ownership ownership) {
        CapabilitySnapshot copy = new CapabilitySnapshot(source.schemaVersion(), source.capabilityId(),
                source.revision(), "", source.kind(), source.scope(), source.source(), source.contract(),
                source.runtime(), source.dependencies(), ownership, source.lifecycle(), source.provenance(),
                source.createdAt());
        return CapabilitySnapshotIntegrity.seal(mapper, copy);
    }

    private CapabilitySnapshot draft(CapabilitySnapshot.Scope scope, long revision, Instant createdAt) {
        String sourceFingerprint = "sha256:" + "a".repeat(64);
        CapabilityContract contract = new CapabilityContract("", SchemaEnvelope.opaque(),
                SchemaEnvelope.opaque(), List.of(), EffectContract.readOnly(List.of("resource:orders")),
                CapabilityContract.Determinism.CONTROLLED_NONDETERMINISTIC,
                new CapabilityContract.IdempotencyContract(
                        CapabilityContract.IdempotencyMode.IDEMPOTENT, "", true), null,
                CapabilityContract.CompatibilityPolicy.conservative(),
                new CapabilityContract.SecurityContract(
                        CapabilityContract.DataClassification.CONFIDENTIAL, false, List.of("sg"), false),
                CapabilityContract.SloContract.unspecified());
        ArtifactProvenance provenance = new ArtifactProvenance("", ArtifactProvenance.SourceType.OWNER,
                List.of(), scope.tenantId(), "MIRROR_REHEARSAL", null, null, null, null,
                List.of(), "", null, null, "");
        CapabilitySnapshot snapshot = new CapabilitySnapshot("", "resource:orders.get", revision, "",
                CapabilitySnapshot.Kind.EXTERNAL, scope,
                new CapabilitySnapshot.Source(CapabilitySnapshot.SourceKind.RESOURCE,
                        "orders.get", sourceFingerprint), contract,
                new CapabilitySnapshot.RuntimeBinding("HTTP_RESOURCE", "orders.get@" + revision,
                        sourceFingerprint, true, List.of()), List.of(),
                new CapabilitySnapshot.Ownership("owner-a", "team-a", "pager-a"),
                CapabilitySnapshot.Lifecycle.DRAFT, provenance, createdAt);
        return CapabilitySnapshotIntegrity.seal(mapper, snapshot);
    }

    private static CapabilitySnapshot.Scope scope(String organization) {
        return new CapabilitySnapshot.Scope("tenant-a", organization, "support", "test", "sg");
    }
}
