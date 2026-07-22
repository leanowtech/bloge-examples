package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationCapabilities;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilitySnapshotIntegrationServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private EmbeddedDatabase database;
    private CapabilitySnapshotIntegrationService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).build();
        DatabaseCapabilitySnapshotRepository repository = new DatabaseCapabilitySnapshotRepository(
                new JdbcTemplate(database), mapper);
        repository.init();
        service = new CapabilitySnapshotIntegrationService(repository, mapper,
                Clock.fixed(Instant.parse("2026-07-22T03:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void createsAndReadsExactOrLatestSnapshotInsideAuthenticatedScope() {
        CapabilitySnapshot draft = draft();

        assertThat(service.create(draft.capabilityId(), 1, draft, context("org-a", "CONFIDENTIAL"))
                .payload()).isEqualTo(draft);
        assertThat(service.find(draft.capabilityId(), 1, context("org-a", "CONFIDENTIAL"))
                .payload()).isEqualTo(draft);
        assertThat(service.find(draft.capabilityId(), 0, context("org-a", "CONFIDENTIAL"))
                .payload()).isEqualTo(draft);
    }

    @Test
    void hidesCrossOrganizationAndInsufficientClearanceReadsBehindSameNotFoundProblem() {
        CapabilitySnapshot draft = draft();
        service.create(draft.capabilityId(), 1, draft, context("org-a", "CONFIDENTIAL"));

        assertNotFound(() -> service.find(draft.capabilityId(), 0,
                context("org-b", "RESTRICTED")));
        assertNotFound(() -> service.find(draft.capabilityId(), 0,
                context("org-a", "INTERNAL")));
    }

    @Test
    void rejectsPathMismatchAndConflictingExactRevisionWithStableProblems() {
        CapabilitySnapshot draft = draft();

        assertThatThrownBy(() -> service.create("resource:another", 1, draft,
                context("org-a", "CONFIDENTIAL")))
                .isInstanceOfSatisfying(IntegrationProblemException.class,
                        exception -> assertThat(exception.problem().code())
                                .isEqualTo("RG.MIRROR.SNAPSHOT_PATH_MISMATCH"));
        service.create(draft.capabilityId(), 1, draft, context("org-a", "CONFIDENTIAL"));
        CapabilitySnapshot changed = copyWithOwner(draft, "owner-b");
        assertThatThrownBy(() -> service.create(changed.capabilityId(), 1, changed,
                context("org-a", "CONFIDENTIAL")))
                .isInstanceOfSatisfying(IntegrationProblemException.class, exception -> {
                    assertThat(exception.problem().status()).isEqualTo(409);
                    assertThat(exception.problem().code()).isEqualTo("RG.MIRROR.SNAPSHOT_APPEND_REJECTED");
                });
    }

    @Test
    void appendsLifecycleRevisionsWithAuthenticatedActorAndOptimisticRevision() {
        CapabilitySnapshot draft = draft();
        service.create(draft.capabilityId(), 1, draft, context("org-a", "CONFIDENTIAL"));

        CapabilitySnapshot reviewed = service.transition(draft.capabilityId(),
                new CapabilityLifecycleTransitionRequest("", 1,
                        CapabilitySnapshot.Lifecycle.REVIEWED,
                        Instant.parse("2026-08-22T00:00:00Z"), ""),
                context("org-a", "CONFIDENTIAL")).payload();
        CapabilitySnapshot active = service.transition(draft.capabilityId(),
                new CapabilityLifecycleTransitionRequest("", 2,
                        CapabilitySnapshot.Lifecycle.ACTIVE,
                        Instant.parse("2026-08-22T00:00:00Z"), ""),
                context("org-a", "CONFIDENTIAL")).payload();

        assertThat(reviewed.revision()).isEqualTo(2);
        assertThat(reviewed.provenance().approvedBy()).isEqualTo("workload-a");
        assertThat(active.revision()).isEqualTo(3);
        assertThat(active.lifecycle()).isEqualTo(CapabilitySnapshot.Lifecycle.ACTIVE);
        assertThatThrownBy(() -> service.transition(draft.capabilityId(),
                new CapabilityLifecycleTransitionRequest("", 1,
                        CapabilitySnapshot.Lifecycle.REVOKED, null, "revocation:stale"),
                context("org-a", "CONFIDENTIAL")))
                .isInstanceOfSatisfying(IntegrationProblemException.class,
                        exception -> assertThat(exception.problem().code())
                                .isEqualTo("RG.MIRROR.LIFECYCLE_TRANSITION_REJECTED"));
    }

    @Test
    void capabilityProbeAdvertisesProtocolButKeepsMirrorServingDisabled() {
        IntegrationCapabilities capabilities = IntegrationCapabilities.current();

        assertThat(capabilities.supportedObjects()).containsKeys(
                "capabilitySnapshot", "capabilityClosure", "capabilityContract", "effectContract",
                "artifactProvenance", "capabilityLifecycleTransition");
        assertThat(capabilities.features())
                .containsEntry("capabilitySnapshotProtocol", true)
                .containsEntry("capabilityProjection", true)
                .containsEntry("capabilityClosureProtocol", true)
                .containsEntry("builtInCapabilityClosureProjection", true)
                .containsEntry("capabilitySnapshotApi", true)
                .containsEntry("capabilityLifecycleFencing", true)
                .containsEntry("mirrorPlanCompilation", false)
                .containsEntry("mirrorExternalLeafInterception", false)
                .containsEntry("mirrorServing", false);
        assertThat(capabilities.endpoints())
                .contains(new IntegrationCapabilities.Endpoint("GET",
                        "/api/integration/capability-snapshots/{capabilityId}"));
    }

    @Test
    void capabilityProbeSatisfiesTheSharedStageZeroCompatibilityFixture() throws Exception {
        JsonNode baseline = mapper.readTree(Files.readString(Path.of("..", "docs", "schemas",
                "resource-gateway-mirror", "capability-mirror-stage0-v1.fixture.json")));
        IntegrationCapabilities capabilities = IntegrationCapabilities.current();

        assertThat(capabilities.protocol()).isEqualTo(baseline.path("protocol").asText());
        assertThat(baseline.path("protocolVersions"))
                .extracting(JsonNode::asText)
                .contains(capabilities.protocolVersion());
        baseline.path("requiredObjects").fields().forEachRemaining(required ->
                assertThat(capabilities.supportedObjects().get(required.getKey()))
                        .as(required.getKey())
                        .containsAnyElementsOf(textValues(required.getValue())));
        baseline.path("requiredFeatures").forEach(required ->
                assertThat(capabilities.features().get(required.asText()))
                        .as(required.asText()).isTrue());
        baseline.path("deferredFeatures").forEach(deferred ->
                assertThat(capabilities.features().get(deferred.asText()))
                        .as(deferred.asText()).isFalse());
    }

    private static List<String> textValues(JsonNode array) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return List.copyOf(values);
    }

    private static void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(IntegrationProblemException.class, exception -> {
                    assertThat(exception.problem().status()).isEqualTo(404);
                    assertThat(exception.problem().code()).isEqualTo("RG.MIRROR.SNAPSHOT_NOT_FOUND");
                });
    }

    private CapabilitySnapshot copyWithOwner(CapabilitySnapshot source, String owner) {
        CapabilitySnapshot copy = new CapabilitySnapshot(source.schemaVersion(), source.capabilityId(),
                source.revision(), "", source.kind(), source.scope(), source.source(), source.contract(),
                source.runtime(), source.dependencies(),
                new CapabilitySnapshot.Ownership(owner, source.ownership().team(),
                        source.ownership().escalation()),
                source.lifecycle(), source.provenance(), source.createdAt());
        return CapabilitySnapshotIntegrity.seal(mapper, copy);
    }

    private CapabilitySnapshot draft() {
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
                List.of(), "tenant-a", "MIRROR_REHEARSAL", null, null, null, null,
                List.of(), "", null, null, "");
        CapabilitySnapshot snapshot = new CapabilitySnapshot("", "resource:orders.get", 1, "",
                CapabilitySnapshot.Kind.EXTERNAL,
                new CapabilitySnapshot.Scope("tenant-a", "org-a", "support", "test", "sg"),
                new CapabilitySnapshot.Source(CapabilitySnapshot.SourceKind.RESOURCE,
                        "orders.get", sourceFingerprint), contract,
                new CapabilitySnapshot.RuntimeBinding("HTTP_RESOURCE", "orders.get@1",
                        sourceFingerprint, true, List.of()), List.of(),
                new CapabilitySnapshot.Ownership("owner-a", "team-a", "pager-a"),
                CapabilitySnapshot.Lifecycle.DRAFT, provenance,
                Instant.parse("2026-07-22T00:00:00Z"));
        return CapabilitySnapshotIntegrity.seal(mapper, snapshot);
    }

    private static IntegrationRequestContext context(String organization, String clearance) {
        return new IntegrationRequestContext("tenant-a", organization, "support", "test", "sg",
                "WORKLOAD", "workload-a", "", "CAPABILITY_GOVERNANCE", "correlation-a",
                Set.of("capability-governors"), clearance, "");
    }
}
