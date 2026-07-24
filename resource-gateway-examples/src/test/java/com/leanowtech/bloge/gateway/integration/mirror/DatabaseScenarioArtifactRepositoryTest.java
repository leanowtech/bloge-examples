package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseScenarioArtifactRepositoryTest {
    private static final Instant NOW =
            Instant.parse("2026-07-24T02:00:00Z");
    private static final CapabilitySnapshot.Scope SCOPE =
            new CapabilitySnapshot.Scope(
                    "tenant-a", "org-a", "support", "test", "sg");
    private static final String SHA_A = "sha256:" + "a".repeat(64);
    private static final String SHA_B = "sha256:" + "b".repeat(64);
    private static final String SHA_C = "sha256:" + "c".repeat(64);
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final MirrorSessionCheckpointIntegrityService checkpointIntegrity =
            new MirrorSessionCheckpointIntegrityService(
                    mapper,
                    InMemoryVisualEvidenceSigner.usingClock(
                            Clock.fixed(NOW, ZoneOffset.UTC)),
                    Clock.fixed(NOW, ZoneOffset.UTC));
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DatabaseScenarioArtifactRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(database);
        repository = new DatabaseScenarioArtifactRepository(
                jdbc, mapper, checkpointIntegrity);
        repository.init();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void persistsAndRestoresAnExactScenarioClosureAcrossRepositoryInstances() {
        CaseHandlingAssertion assertion = assertion(
                "RG.MIRROR.SCENARIO.NODE_FAILED");
        ScenarioCase scenarioCase = scenarioCase(assertion);
        ScenarioPack pack = pack(assertion, scenarioCase);

        assertThat(repository.create(assertion)).isEqualTo(assertion);
        assertThat(repository.create(scenarioCase)).isEqualTo(scenarioCase);
        assertThat(repository.create(pack)).isEqualTo(pack);
        assertThat(repository.create(pack)).isEqualTo(pack);
        DatabaseScenarioArtifactRepository restarted =
                new DatabaseScenarioArtifactRepository(
                        jdbc, mapper, checkpointIntegrity);
        restarted.init();

        assertThat(restarted.findAssertion(
                SCOPE, assertion.assertionId(), assertion.revision()))
                .contains(assertion);
        assertThat(restarted.findCase(
                SCOPE, scenarioCase.caseId(), scenarioCase.revision()))
                .contains(scenarioCase);
        assertThat(restarted.findPack(
                SCOPE, pack.packId(), pack.revision()))
                .contains(pack);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM mirror_scenario_artifacts",
                Integer.class)).isEqualTo(3);
    }

    @Test
    void isolatesTheSameArtifactIdentityByCompleteEnterpriseScope() {
        CaseHandlingAssertion value = assertion(
                "RG.MIRROR.SCENARIO.NODE_FAILED");
        repository.create(value);

        assertThat(repository.findAssertion(
                new CapabilitySnapshot.Scope(
                        "tenant-a", "org-a", "other", "test", "sg"),
                value.assertionId(), value.revision())).isEmpty();
        assertThat(repository.findAssertion(
                new CapabilitySnapshot.Scope(
                        "tenant-a", "org-b", "support", "test", "sg"),
                value.assertionId(), value.revision())).isEmpty();
    }

    @Test
    void rejectsAConflictingRevisionAndRefusesIndexedIdentityTampering() {
        CaseHandlingAssertion first = assertion(
                "RG.MIRROR.SCENARIO.NODE_FAILED");
        CaseHandlingAssertion conflict = assertion(
                "RG.MIRROR.SCENARIO.NODE_UNEXPECTED");
        repository.create(first);

        assertThatThrownBy(() -> repository.create(conflict))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different content");

        jdbc.update("""
                UPDATE mirror_scenario_artifacts
                SET artifact_fingerprint = ?
                WHERE artifact_kind = 'ASSERTION' AND artifact_id = ?
                """, SHA_B, first.assertionId());
        assertThatThrownBy(() -> repository.findAssertion(
                SCOPE, first.assertionId(), first.revision()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed integrity validation");
    }

    @Test
    void persistsOnlyIndependentlyVerifiedSignedCheckpointBundles() {
        MirrorSessionCheckpointBundle checkpoint = checkpoint();
        MirrorArtifactRef ref = ScenarioPackIntegrity.reference(checkpoint);

        assertThat(repository.create(checkpoint)).isEqualTo(checkpoint);
        assertThat(repository.findCheckpoint(
                checkpoint.checkpoint().scope(),
                ref.id(),
                ref.revision())).contains(checkpoint);

        DatabaseScenarioArtifactRepository untrusted =
                new DatabaseScenarioArtifactRepository(
                        jdbc,
                        mapper,
                        new MirrorSessionCheckpointIntegrityService(
                                mapper,
                                InMemoryVisualEvidenceSigner.usingClock(
                                        Clock.fixed(NOW, ZoneOffset.UTC)),
                                Clock.fixed(NOW, ZoneOffset.UTC)));
        untrusted.init();
        assertThatThrownBy(() -> untrusted.findCheckpoint(
                checkpoint.checkpoint().scope(),
                ref.id(),
                ref.revision()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed integrity validation");
    }

    @Test
    void schemaContainsNoBusinessPayloadOrMutableLatestPointerColumns() {
        List<String> columns = jdbc.queryForList("""
                SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = 'MIRROR_SCENARIO_ARTIFACTS'
                ORDER BY ORDINAL_POSITION
                """, String.class);

        assertThat(columns).containsExactly(
                "TENANT_ID",
                "ORGANIZATION_ID",
                "PROJECT_ID",
                "ENVIRONMENT_ID",
                "REGION",
                "ARTIFACT_KIND",
                "ARTIFACT_ID",
                "ARTIFACT_REVISION",
                "ARTIFACT_FINGERPRINT",
                "SCHEMA_VERSION",
                "ARTIFACT_JSON");
        assertThat(columns).noneMatch(column ->
                column.contains("PAYLOAD")
                        || column.contains("REQUEST")
                        || column.contains("RESPONSE")
                        || column.contains("LATEST"));
    }

    private CaseHandlingAssertion assertion(String governanceCode) {
        return ScenarioPackIntegrity.sealAssertion(
                mapper,
                new CaseHandlingAssertion(
                        "", "customer-node-status", 1, "", SCOPE,
                        CaseHandlingAssertion.Observation.NODE_STATUS,
                        new CaseHandlingAssertion.Selector(
                                "loadCustomer", "", "", null, ""),
                        new CaseHandlingAssertion.Expectation(
                                List.of("SUCCESS"), "", "", "",
                                null, null, null, null),
                        CaseHandlingAssertion.Severity.BLOCKER,
                        governanceCode,
                        provenance(),
                        CapabilitySnapshot.Lifecycle.ACTIVE,
                        NOW));
    }

    private ScenarioCase scenarioCase(CaseHandlingAssertion assertion) {
        return ScenarioPackIntegrity.sealCase(
                mapper,
                new ScenarioCase(
                        "", "customer-found", 1, "", SCOPE,
                        ScenarioCase.CaseType.GOLDEN,
                        ref("CAPABILITY", "customer-view", SHA_A),
                        ref("TEST_SUITE", "customer-suite", SHA_B),
                        "customer-found",
                        ref("MIRROR_PLAN", "customer-plan", SHA_C),
                        ref("FIXTURE_BUNDLE", "customer-fixture", SHA_A),
                        null,
                        new MirrorPlan.ExecutionServices(
                                NOW, 42L, null, null),
                        List.of(),
                        List.of(ScenarioPackIntegrity.reference(assertion)),
                        provenance(),
                        CapabilitySnapshot.Lifecycle.ACTIVE,
                        NOW));
    }

    private ScenarioPack pack(
            CaseHandlingAssertion assertion, ScenarioCase scenarioCase) {
        return ScenarioPackIntegrity.seal(
                mapper,
                new ScenarioPack(
                        "", "customer-rehearsal", 1, "", SCOPE,
                        scenarioCase.targetCapabilityRef(),
                        List.of(ScenarioPackIntegrity.reference(scenarioCase)),
                        List.of(ScenarioPackIntegrity.reference(assertion)),
                        List.of(),
                        null,
                        List.of(),
                        new ScenarioPack.RehearsalPolicy(
                                ScenarioPack.Scheduling.SEQUENTIAL,
                                true,
                                false,
                                false,
                                false,
                                ScenarioPack.EvidenceMode.HASH_ONLY,
                                10,
                                100,
                                Duration.ofMinutes(5),
                                Duration.ofMinutes(30),
                                true,
                                CapabilityContract.DataClassification.CONFIDENTIAL,
                                List.of("sg")),
                        provenance(),
                        CapabilitySnapshot.Lifecycle.ACTIVE,
                        NOW));
    }

    private MirrorSessionCheckpointBundle checkpoint() {
        StateModel model = StateModelIntegrity.seal(
                mapper, StatefulMirrorProtocolTest.stateModel());
        WriteEffectSpec effect = WriteEffectSpecIntegrity.seal(
                mapper, StatefulMirrorProtocolTest.refundEffect(model));
        SessionStateSpace state = StatefulMirrorProtocolTest.initialState(
                mapper, model, effect);
        MirrorSessionPayload payload =
                MirrorSessionProtocolIntegrity.sealInitial(
                        mapper,
                        new MirrorSessionPayload(
                                "", model, List.of(), List.of(effect),
                                state, ""),
                        NOW);
        MirrorSessionDescriptor descriptor =
                MirrorSessionProtocolIntegrity.sealDescriptor(
                        mapper,
                        new MirrorSessionDescriptor(
                                "", state.sessionId(), state.scope(),
                                state.planFingerprint(),
                                state.stateModelRef(),
                                state.writeEffectRefs(),
                                state.stateRevision(),
                                MirrorSessionDescriptor.Status.ACTIVE,
                                state.worldFingerprint(),
                                state.fingerprint(),
                                NOW,
                                NOW,
                                state.expiresAt(),
                                null,
                                ""));
        MirrorSessionStoreGeneration generation =
                MirrorSessionStoreGenerationIntegrity.seal(
                        mapper,
                        new MirrorSessionStoreGeneration(
                                "", "store-generation-1", 1,
                                NOW.minusSeconds(60), ""));
        return checkpointIntegrity.seal(
                new MirrorSessionStateStore.CheckpointSnapshot(
                        generation,
                        new MirrorSessionStateStore.SessionSnapshot(
                                payload, descriptor)));
    }

    private static ArtifactProvenance provenance() {
        return new ArtifactProvenance(
                "", ArtifactProvenance.SourceType.OWNER, List.of(),
                SCOPE.tenantId(),
                MirrorPlanIntegrationService.AUTHORIZED_PURPOSE,
                null, null, null, null, List.of(),
                "support-owner", NOW,
                NOW.plus(Duration.ofDays(1)), "");
    }

    private static MirrorArtifactRef ref(
            String kind, String id, String fingerprint) {
        return new MirrorArtifactRef(kind, id, 1, fingerprint);
    }
}
