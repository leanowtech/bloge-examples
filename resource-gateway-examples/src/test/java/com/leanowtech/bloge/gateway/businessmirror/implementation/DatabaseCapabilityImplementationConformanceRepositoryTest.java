package com.leanowtech.bloge.gateway.businessmirror.implementation;

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
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseCapabilityImplementationConformanceRepositoryTest {
    private static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "tenant", "customer-service", "refund", "test", "sg");
    private static final Instant AT = Instant.now().minusSeconds(5);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private DatabaseCapabilityImplementationConformanceRepository repository;
    private DatabaseVisualEvidenceSigner signer;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:implementation-conformance-" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        repository = new DatabaseCapabilityImplementationConformanceRepository(jdbc, mapper);
        repository.init();
        signer = new DatabaseVisualEvidenceSigner(jdbc);
    }

    @Test
    void completesOnceAndExactlyReplaysTheSignedResult() {
        CapabilityImplementationConformanceRepository.Registration registration = registration('a');
        var acquired = repository.claim(registration, "worker-1", Duration.ofMinutes(5));
        StoredCapabilityImplementationConformance result = stored();

        assertThat(acquired.outcome())
                .isEqualTo(CapabilityImplementationConformanceRepository.Outcome.ACQUIRED);
        assertThat(repository.complete(acquired.lease(), result)).isTrue();
        var replay = repository.claim(registration, "worker-2", Duration.ofMinutes(5));

        assertThat(replay.outcome())
                .isEqualTo(CapabilityImplementationConformanceRepository.Outcome.COMPLETED);
        assertThat(replay.state().result()).isEqualTo(result);
        replay.state().result().verify(mapper, signer);
    }

    @Test
    void rejectsCommandDriftAndFencesAReleasedWorker() {
        var first = repository.claim(registration('a'), "worker-1", Duration.ofMinutes(5));
        assertThatThrownBy(() -> repository.claim(
                registration('b'), "worker-2", Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different material");

        assertThat(repository.release(first.lease(), "TEST_FAILURE")).isTrue();
        var takeover = repository.claim(registration('a'), "worker-2", Duration.ofMinutes(5));
        assertThat(takeover.lease().leaseEpoch()).isEqualTo(2);
        assertThat(repository.complete(first.lease(), stored())).isFalse();
        assertThat(repository.complete(takeover.lease(), stored())).isTrue();
    }

    @Test
    void isolatesTheCompleteEnterpriseScope() {
        repository.claim(registration('a'), "worker-1", Duration.ofMinutes(5));
        CapabilitySnapshot.Scope other = new CapabilitySnapshot.Scope(
                SCOPE.tenantId(), "other-org", SCOPE.projectId(),
                SCOPE.environmentId(), SCOPE.region());

        assertThat(repository.find(other, "binding-1", 1)).isEmpty();
        assertThat(repository.find(SCOPE, "binding-1", 1)).isPresent();
    }

    private CapabilityImplementationConformanceRepository.Registration registration(char value) {
        return new CapabilityImplementationConformanceRepository.Registration(SCOPE,
                "conformance-1", "proposal-1", 1,
                ref("PROPOSAL_IMPLEMENTATION_BINDING", "binding-1", '4'),
                fingerprint(value));
    }

    private StoredCapabilityImplementationConformance stored() {
        MirrorArtifactRef proposalRef = ref("CAPABILITY_PROPOSAL_DRAFT", "proposal-1", '1');
        MirrorArtifactRef simulationRef = ref(
                "PROPOSAL_SIMULATION_EVIDENCE", "simulation-1", '2');
        MirrorArtifactRef bindingRef = ref(
                "PROPOSAL_IMPLEMENTATION_BINDING", "binding-1", '4');
        MirrorArtifactRef suiteRef = ref("TEST_SUITE", "refund-suite", '5');
        CapabilityImplementationConformanceReport.ImplementationEvidence implementation =
                new CapabilityImplementationConformanceReport.ImplementationEvidence("",
                        "test-run-1", "", "PASSED", fingerprint('6'), fingerprint('7'),
                        fingerprint('8'), fingerprint('9'), fingerprint('a'), fingerprint('b'),
                        AT, AT.plusSeconds(1)).seal(mapper);
        CapabilityImplementationConformanceReport.CaseComparison comparison =
                new CapabilityImplementationConformanceReport.CaseComparison("case-1",
                        TestSuite.CaseType.GOLDEN, suiteRef,
                        ref("FIXTURE_BUNDLE", "refund-fixture", 'c'),
                        ref("MIRROR_EVIDENCE_BUNDLE", "mirror-run-1", 'd'),
                        implementation, "PASSED", fingerprint('6'), fingerprint('7'),
                        fingerprint('7'),
                        CapabilityImplementationConformanceReport.Comparison.MATCH,
                        1, 1, List.of("site-1"), List.of(), List.of());
        CapabilityImplementationConformanceReport report =
                new CapabilityImplementationConformanceReport("", "conformance-1", "", SCOPE,
                        proposalRef, simulationRef, bindingRef,
                        ref("CAPABILITY", "refund-lookup", 'e'),
                        ref("GRAPH_DRAFT", "refund-graph", 'f'), List.of(suiteRef),
                        CapabilityImplementationConformanceReport.Status.PASSED,
                        List.of(comparison), AT, AT.plusSeconds(2),
                        List.of("same-suite only"), List.of("production outcomes excluded"))
                        .seal(mapper);
        CapabilityProposalSnapshot snapshot = new CapabilityProposalSnapshot("", "proposal-1", 1,
                "", SCOPE, 1, proposalRef.fingerprint(), intent(), contract(),
                List.of(ref("FIXTURE_BUNDLE", "refund-fixture", 'c')), List.of(suiteRef),
                new CapabilityProposalDraft.SimulationRuntimeBinding(null,
                        ref("FIXTURE_RESOLVER_POLICY", "fixture-only", '0'),
                        false, false, false), bindingRef,
                CapabilityProposalSnapshot.EvidenceState.CONFORMANT,
                List.of(simulationRef, report.artifactRef()), List.of(), report.limitations(),
                AT.plus(Duration.ofDays(1)), provenance(), AT.plusSeconds(2)).seal(mapper);
        return new StoredCapabilityImplementationConformance("", fingerprint('a'), report,
                signer.seal(report.fingerprint(), "conformance-test"), snapshot,
                report.completedAt());
    }

    private static CapabilityProposalDraft.BusinessIntent intent() {
        return new CapabilityProposalDraft.BusinessIntent("missing refund lookup",
                "validate refund journey", List.of(ref("SCENARIO_CASE", "approved", '1')),
                List.of(ref("DOMAIN_CAPABILITY_PACKAGE", "refund", '2')),
                List.of(ref("GRAPH_DRAFT", "refund-graph", 'f')), "owner");
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
                SCOPE.tenantId(), "conformance-test", null, null, null, null,
                List.of(), "", null, null, "");
    }

    private static MirrorArtifactRef ref(String kind, String id, char value) {
        return new MirrorArtifactRef(kind, id, 1, fingerprint(value));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
