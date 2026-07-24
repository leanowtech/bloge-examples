package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioArtifactRegistryServiceTest {
    private static final Instant NOW =
            Instant.parse("2026-07-24T02:00:00Z");
    private static final CapabilitySnapshot.Scope SCOPE =
            new CapabilitySnapshot.Scope(
                    "tenant-a", "org-a", "support", "test", "sg");
    private static final String SHA_A = "sha256:" + "a".repeat(64);
    private static final String SHA_B = "sha256:" + "b".repeat(64);
    private static final String SHA_C = "sha256:" + "c".repeat(64);
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final InMemoryArtifacts artifacts = new InMemoryArtifacts();
    private final ScenarioArtifactRegistryService registry =
            new ScenarioArtifactRegistryService(
                    artifacts,
                    mapper,
                    new MirrorSessionCheckpointIntegrityService(
                            mapper,
                            VisualEvidenceSigner.unavailable(),
                            Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC)),
                    Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));

    @Test
    void registersOnlyInDependencyOrderAndMakesExactRetriesIdempotent() {
        CaseHandlingAssertion assertion = assertion();
        ScenarioCase scenarioCase = scenarioCase(assertion);
        ScenarioPack pack = pack(assertion, scenarioCase);

        assertProblem(
                () -> registry.register(scenarioCase, identity()),
                "RG.MIRROR.SCENARIO_ASSERTION_NOT_FOUND");
        assertThat(registry.register(assertion, identity()))
                .isEqualTo(assertion);
        assertThat(registry.register(scenarioCase, identity()))
                .isEqualTo(scenarioCase);
        assertThat(registry.register(pack, identity()))
                .isEqualTo(pack);
        assertThat(registry.register(pack, identity()))
                .isEqualTo(pack);
    }

    @Test
    void rejectsAPackWithAnUnregisteredCaseEvenWhenItsSealIsValid() {
        CaseHandlingAssertion assertion = assertion();
        registry.register(assertion, identity());
        ScenarioPack pack = ScenarioPackIntegrity.seal(
                mapper,
                new ScenarioPack(
                        "", "customer-rehearsal", 1, "", SCOPE,
                        ref("CAPABILITY", "customer-view", SHA_A),
                        List.of(ref(
                                "SCENARIO_CASE",
                                "missing-case",
                                SHA_B)),
                        List.of(ScenarioPackIntegrity.reference(assertion)),
                        List.of(),
                        null,
                        List.of(),
                        policy(),
                        provenance(),
                        CapabilitySnapshot.Lifecycle.ACTIVE,
                        NOW));

        assertProblem(
                () -> registry.register(pack, identity()),
                "RG.MIRROR.SCENARIO_CASE_NOT_FOUND");
    }

    @Test
    void refusesCrossScopeRegistrationWithoutLeakingArtifactExistence() {
        CaseHandlingAssertion assertion = assertion();
        IntegrationRequestContext otherProject =
                new IntegrationRequestContext(
                        "tenant-a",
                        "org-a",
                        "other",
                        "test",
                        "sg",
                        "SERVICE",
                        "scenario-test",
                        "",
                        MirrorPlanIntegrationService.AUTHORIZED_PURPOSE,
                        "corr-other",
                        java.util.Set.of(),
                        "CONFIDENTIAL",
                        "");

        assertProblem(
                () -> registry.register(assertion, otherProject),
                "RG.MIRROR.SCENARIO_ASSERTION_REGISTRATION_INVALID");
    }

    @Test
    void requiresExactFingerprintOnReads() {
        CaseHandlingAssertion assertion = assertion();
        ScenarioCase scenarioCase = scenarioCase(assertion);
        ScenarioPack pack = pack(assertion, scenarioCase);
        registry.register(assertion, identity());
        registry.register(scenarioCase, identity());
        registry.register(pack, identity());

        assertThat(registry.findPack(
                pack.packId(),
                pack.revision(),
                pack.fingerprint(),
                identity())).isEqualTo(pack);
        assertProblem(
                () -> registry.findPack(
                        pack.packId(),
                        pack.revision(),
                        SHA_C,
                        identity()),
                "RG.MIRROR.SCENARIO_ARTIFACT_STALE");
    }

    private CaseHandlingAssertion assertion() {
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
                        "RG.MIRROR.SCENARIO.NODE_FAILED",
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
                        policy(),
                        provenance(),
                        CapabilitySnapshot.Lifecycle.ACTIVE,
                        NOW));
    }

    private static ScenarioPack.RehearsalPolicy policy() {
        return new ScenarioPack.RehearsalPolicy(
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
                List.of("sg"));
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

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                SCOPE.tenantId(),
                SCOPE.organizationId(),
                SCOPE.projectId(),
                SCOPE.environmentId(),
                SCOPE.region(),
                "SERVICE",
                "scenario-test",
                "",
                MirrorPlanIntegrationService.AUTHORIZED_PURPOSE,
                "corr-scenario",
                java.util.Set.of(),
                "CONFIDENTIAL",
                "");
    }

    private static MirrorArtifactRef ref(
            String kind, String id, String fingerprint) {
        return new MirrorArtifactRef(kind, id, 1, fingerprint);
    }

    private static void assertProblem(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        problem -> assertThat(problem.problem().code())
                                .isEqualTo(code));
    }

    private static final class InMemoryArtifacts
            implements ScenarioArtifactRepository {
        private final Map<Key, Object> values = new HashMap<>();

        @Override
        public CaseHandlingAssertion create(
                CaseHandlingAssertion assertion) {
            return create(
                    assertion.scope(),
                    ScenarioPackIntegrity.reference(assertion),
                    assertion);
        }

        @Override
        public ScenarioCase create(ScenarioCase scenarioCase) {
            return create(
                    scenarioCase.scope(),
                    ScenarioPackIntegrity.reference(scenarioCase),
                    scenarioCase);
        }

        @Override
        public ScenarioPack create(ScenarioPack pack) {
            return create(
                    pack.scope(),
                    ScenarioPackIntegrity.reference(pack),
                    pack);
        }

        @Override
        public MirrorSessionCheckpointBundle create(
                MirrorSessionCheckpointBundle checkpoint) {
            return create(
                    checkpoint.checkpoint().scope(),
                    ScenarioPackIntegrity.reference(checkpoint),
                    checkpoint);
        }

        @Override
        public Optional<CaseHandlingAssertion> findAssertion(
                CapabilitySnapshot.Scope scope,
                String assertionId,
                long revision) {
            return find(
                    scope, "CASE_HANDLING_ASSERTION",
                    assertionId, revision,
                    CaseHandlingAssertion.class);
        }

        @Override
        public Optional<ScenarioCase> findCase(
                CapabilitySnapshot.Scope scope,
                String caseId,
                long revision) {
            return find(
                    scope, "SCENARIO_CASE",
                    caseId, revision,
                    ScenarioCase.class);
        }

        @Override
        public Optional<ScenarioPack> findPack(
                CapabilitySnapshot.Scope scope,
                String packId,
                long revision) {
            return find(
                    scope, "SCENARIO_PACK",
                    packId, revision,
                    ScenarioPack.class);
        }

        @Override
        public Optional<MirrorSessionCheckpointBundle> findCheckpoint(
                CapabilitySnapshot.Scope scope,
                String checkpointId,
                long revision) {
            return find(
                    scope, "MIRROR_SESSION_CHECKPOINT",
                    checkpointId, revision,
                    MirrorSessionCheckpointBundle.class);
        }

        private <T> T create(
                CapabilitySnapshot.Scope scope,
                MirrorArtifactRef ref,
                T value) {
            Key key = new Key(
                    scope, ref.kind(), ref.id(), ref.revision());
            Object existing = values.putIfAbsent(key, value);
            if (existing == null || existing.equals(value)) {
                return existing == null ? value : (T) existing;
            }
            throw new IllegalArgumentException("conflict");
        }

        private <T> Optional<T> find(
                CapabilitySnapshot.Scope scope,
                String kind,
                String id,
                long revision,
                Class<T> type) {
            return Optional.ofNullable(values.get(
                            new Key(scope, kind, id, revision)))
                    .map(type::cast);
        }

        private record Key(
                CapabilitySnapshot.Scope scope,
                String kind,
                String id,
                long revision) {
        }
    }
}
