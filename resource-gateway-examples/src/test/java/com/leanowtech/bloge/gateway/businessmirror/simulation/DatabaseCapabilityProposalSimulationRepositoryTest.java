package com.leanowtech.bloge.gateway.businessmirror.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityProposalDraft;
import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityProposalSnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityContract;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.EffectContract;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.runtime.DatabaseVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseCapabilityProposalSimulationRepositoryTest {
    private static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "tenant", "customer-service", "refund", "test", "sg");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private JdbcDataSource dataSource;
    private DatabaseCapabilityProposalSimulationRepository repository;
    private VisualEvidenceSigner signer;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:proposal-simulation-" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        repository = new DatabaseCapabilityProposalSimulationRepository(jdbc, mapper);
        repository.init();
        signer = new DatabaseVisualEvidenceSigner(jdbc);
    }

    @Test
    void coordinatesExactCompletionAndRestartSafeReplay() {
        CapabilityProposalSimulationRepository.Registration registration = registration('a');

        CapabilityProposalSimulationRepository.Claim first = repository.claim(
                registration, "worker-a", Duration.ofMinutes(5));
        CapabilityProposalSimulationRepository.Claim concurrent = repository.claim(
                registration, "worker-b", Duration.ofMinutes(5));

        assertThat(first.outcome())
                .isEqualTo(CapabilityProposalSimulationRepository.Outcome.ACQUIRED);
        assertThat(concurrent.outcome())
                .isEqualTo(CapabilityProposalSimulationRepository.Outcome.IN_PROGRESS);
        StoredCapabilityProposalSimulation result = result(registration.requestFingerprint());
        assertThat(repository.complete(first.lease(), result)).isTrue();

        DatabaseCapabilityProposalSimulationRepository restarted =
                new DatabaseCapabilityProposalSimulationRepository(
                        new JdbcTemplate(dataSource), mapper);
        restarted.init();
        CapabilityProposalSimulationRepository.Claim replay = restarted.claim(
                registration, "worker-c", Duration.ofMinutes(5));
        assertThat(replay.outcome())
                .isEqualTo(CapabilityProposalSimulationRepository.Outcome.COMPLETED);
        assertThat(replay.state().result()).isEqualTo(result);
    }

    @Test
    void returnsCompletedResultAndRejectsCommandDrift() {
        CapabilityProposalSimulationRepository.Registration registration = registration('a');
        CapabilityProposalSimulationRepository.Claim claim = repository.claim(
                registration, "worker-a", Duration.ofMinutes(5));
        StoredCapabilityProposalSimulation result = result(registration.requestFingerprint());
        assertThat(repository.complete(claim.lease(), result)).isTrue();

        CapabilityProposalSimulationRepository.Claim replay = repository.claim(
                registration, "worker-b", Duration.ofMinutes(5));
        assertThat(replay.outcome())
                .isEqualTo(CapabilityProposalSimulationRepository.Outcome.COMPLETED);
        assertThat(replay.state().result()).isEqualTo(result);
        replay.state().result().verify(mapper, signer);

        CapabilityProposalSimulationRepository.Registration drift =
                new CapabilityProposalSimulationRepository.Registration(
                        SCOPE, registration.simulationId(), registration.proposalId(),
                        registration.proposalRevision(), fingerprint('b'));
        assertThatThrownBy(() -> repository.claim(
                drift, "worker-c", Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different command material");
    }

    @Test
    void releaseAllowsEpochFencedRecovery() {
        CapabilityProposalSimulationRepository.Registration registration = registration('a');
        CapabilityProposalSimulationRepository.Claim first = repository.claim(
                registration, "worker-a", Duration.ofMinutes(5));

        assertThat(repository.release(first.lease(), "RG.TEST.FAILURE")).isTrue();
        CapabilityProposalSimulationRepository.Claim recovered = repository.claim(
                registration, "worker-b", Duration.ofMinutes(5));

        assertThat(recovered.outcome())
                .isEqualTo(CapabilityProposalSimulationRepository.Outcome.ACQUIRED);
        assertThat(recovered.lease().leaseEpoch()).isEqualTo(2);
        assertThat(repository.complete(first.lease(), result(registration.requestFingerprint())))
                .isFalse();
        assertThat(repository.complete(recovered.lease(),
                result(registration.requestFingerprint()))).isTrue();
    }

    @Test
    void renewsOnlyTheCurrentUnexpiredEpoch() {
        CapabilityProposalSimulationRepository.Registration registration = registration('a');
        CapabilityProposalSimulationRepository.Claim first = repository.claim(
                registration, "worker-a", Duration.ofMinutes(5));
        Instant originalExpiry = first.state().leaseExpiresAt();

        assertThat(repository.renew(first.lease(), Duration.ofMinutes(10))).isTrue();
        CapabilityProposalSimulationRepository.State renewed = repository.find(
                SCOPE, registration.proposalId(), registration.proposalRevision()).orElseThrow();
        assertThat(renewed.leaseExpiresAt()).isAfter(originalExpiry);

        CapabilityProposalSimulationRepository.Lease stale =
                new CapabilityProposalSimulationRepository.Lease(SCOPE,
                        registration.proposalId(), registration.proposalRevision(),
                        registration.simulationId(), "worker-a", 0);
        assertThat(repository.renew(stale, Duration.ofMinutes(10))).isFalse();
    }

    @Test
    void keepsIdenticalProposalCoordinatesIsolatedByFullScope() {
        CapabilityProposalSimulationRepository.Registration registration = registration('a');
        repository.claim(registration, "worker-a", Duration.ofMinutes(5));
        CapabilitySnapshot.Scope other = new CapabilitySnapshot.Scope(
                SCOPE.tenantId(), "other-org", SCOPE.projectId(),
                SCOPE.environmentId(), SCOPE.region());

        assertThat(repository.find(other, registration.proposalId(),
                registration.proposalRevision())).isEmpty();
    }

    private CapabilityProposalSimulationRepository.Registration registration(char value) {
        return new CapabilityProposalSimulationRepository.Registration(
                SCOPE, "simulation-1", "refund-proposal", 1, fingerprint(value));
    }

    private StoredCapabilityProposalSimulation result(String requestFingerprint) {
        Instant startedAt = Instant.parse("2026-08-14T10:00:00Z");
        Instant completedAt = startedAt.plusSeconds(2);
        MirrorArtifactRef draftRef = ref(
                "CAPABILITY_PROPOSAL_DRAFT", "refund-proposal", '1');
        MirrorArtifactRef packageRef = ref(
                "DOMAIN_CAPABILITY_PACKAGE", "refund-package", '2');
        MirrorArtifactRef graphRef = ref("GRAPH_DRAFT", "built-in:refundGraph", '3');
        MirrorArtifactRef closureRef = ref("CAPABILITY_CLOSURE", "graph:refundGraph", '4');
        MirrorArtifactRef simulatedClosureRef = ref(
                "CAPABILITY_CLOSURE", "graph:refundGraph", '5');
        MirrorArtifactRef targetRef = ref("CAPABILITY", "operator:refundLookup", '6');
        MirrorArtifactRef temporaryRef = ref(
                "CAPABILITY", "proposal:refund-proposal:r1", '7');
        MirrorArtifactRef suiteRef = ref("TEST_SUITE", "refund-suite", '8');
        MirrorArtifactRef fixtureRef = ref("FIXTURE_BUNDLE", "refund-fixture", '9');
        CapabilityProposalSimulationEvidence.CaseEvidence caseEvidence =
                new CapabilityProposalSimulationEvidence.CaseEvidence(
                        "case-001", TestSuite.CaseType.GOLDEN, suiteRef, fixtureRef,
                        ref("MIRROR_PLAN", "plan-1", 'a'),
                        ref("MIRROR_EVIDENCE_BUNDLE", "run-1", 'b'),
                        "PASSED", List.of("OWNER_SPECIFIED"), List.of("rule-1"), 1, List.of());
        CapabilityProposalSimulationEvidence evidence =
                new CapabilityProposalSimulationEvidence("", "simulation-1", "", SCOPE,
                        draftRef, packageRef, graphRef, closureRef, simulatedClosureRef,
                        targetRef, temporaryRef, List.of(suiteRef),
                        CapabilityProposalSimulationEvidence.Status.PASSED,
                        List.of(caseEvidence), startedAt, completedAt,
                        List.of("SIMULATION_ONLY"), List.of("No production conformance"))
                        .seal(mapper);
        VisualRunEvidenceSeal seal = signer.seal(
                evidence.fingerprint(), "proposal-simulation:test");
        CapabilityProposalDraft.SimulationRuntimeBinding binding =
                new CapabilityProposalDraft.SimulationRuntimeBinding(null,
                        ref("FIXTURE_RESOLVER_POLICY", "fixture-only", 'c'),
                        false, false, false);
        CapabilityProposalSnapshot snapshot = new CapabilityProposalSnapshot("",
                "refund-proposal", 1, "", SCOPE, 1, draftRef.fingerprint(),
                new CapabilityProposalDraft.BusinessIntent(
                        "Refund lookup is missing", "Validate the refund journey",
                        List.of(ref("SCENARIO_CASE", "refund-approved", 'd')),
                        List.of(packageRef), List.of(graphRef), "owner"),
                contract(), List.of(fixtureRef), List.of(suiteRef), binding, null,
                CapabilityProposalSnapshot.EvidenceState.SIMULATED,
                List.of(evidence.artifactRef()), List.of(), List.of("SIMULATION_ONLY"),
                completedAt.plus(Duration.ofDays(1)), provenance(), completedAt).seal(mapper);
        return new StoredCapabilityProposalSimulation("", requestFingerprint,
                evidence, seal, snapshot, completedAt);
    }

    private static CapabilityContract contract() {
        return new CapabilityContract("", SchemaEnvelope.opaque(), SchemaEnvelope.opaque(),
                List.of(), EffectContract.readOnly(List.of("refund/*")),
                CapabilityContract.Determinism.DETERMINISTIC,
                new CapabilityContract.IdempotencyContract(
                        CapabilityContract.IdempotencyMode.IDEMPOTENT, "", true), null,
                CapabilityContract.CompatibilityPolicy.conservative(),
                new CapabilityContract.SecurityContract(
                        CapabilityContract.DataClassification.INTERNAL, false,
                        List.of("sg"), false),
                new CapabilityContract.SloContract(
                        Duration.ofSeconds(2), 0.99, 500L, "owner"));
    }

    private static ArtifactProvenance provenance() {
        return new ArtifactProvenance("", ArtifactProvenance.SourceType.OWNER, List.of(),
                SCOPE.tenantId(), "proposal-test", null, null, null, null,
                List.of(), "", null, null, "");
    }

    private static MirrorArtifactRef ref(String kind, String id, char value) {
        return new MirrorArtifactRef(kind, id, 1, fingerprint(value));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
