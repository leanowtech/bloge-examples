package com.leanowtech.bloge.gateway.integration.mirror;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegionalDataPlaneCertifiedRunTrustAuthorityTest {
    private final RegionalDataPlaneCertificationTestFixtures fixtures =
            new RegionalDataPlaneCertificationTestFixtures();

    @Test
    void checksSameRegionalDecisionAtAdmissionConfirmationAndCommit() {
        AtomicInteger regionalReads = new AtomicInteger();
        AtomicInteger permitCloses = new AtomicInteger();
        RegionalDataPlaneCertificationAuthority regional = regional(regionalReads, true);
        MirrorDeploymentIsolationRunTrustAuthority base = base(permitCloses);
        var composite = new RegionalDataPlaneCertifiedRunTrustAuthority(base, regional);

        var admission = composite.admit(fixtures.scope);
        var binding = composite.confirm(admission,
                fixtures.now.plusSeconds(1), fixtures.now.plusSeconds(2));
        try (var ignored = composite.acquireCommitPermit(fixtures.scope, binding)) {
            assertThat(composite.available()).isTrue();
        }

        assertThat(regionalReads).hasValue(3);
        assertThat(permitCloses).hasValue(1);
        assertThat(binding.decisionRef()).isEqualTo(fixtures.isolationV2.artifactRef());
    }

    @Test
    void missingRegionalAuthorityMakesCompositeUnavailableAndFailsClosed() {
        var composite = new RegionalDataPlaneCertifiedRunTrustAuthority(
                base(new AtomicInteger()), RegionalDataPlaneCertificationAuthority.unavailable());

        assertThat(composite.available()).isFalse();
        assertThatThrownBy(() -> composite.admit(fixtures.scope))
                .isInstanceOf(MirrorDeploymentIsolationRunTrustAuthority.TrustException.class)
                .extracting(failure -> ((MirrorDeploymentIsolationRunTrustAuthority.TrustException)
                        failure).reasonCode())
                .isEqualTo("REGIONAL_CERTIFICATION_AUTHORITY_UNAVAILABLE");
    }

    @Test
    void commitClosesIsolationPermitWhenRegionalDecisionDrifts() {
        AtomicInteger permitCloses = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        var composite = new RegionalDataPlaneCertifiedRunTrustAuthority(
                base(permitCloses), regional(reads, false));
        var admission = composite.admit(fixtures.scope);
        var binding = composite.confirm(admission,
                fixtures.now.plusSeconds(1), fixtures.now.plusSeconds(2));

        assertThatThrownBy(() -> composite.acquireCommitPermit(fixtures.scope, binding))
                .isInstanceOf(MirrorDeploymentIsolationRunTrustAuthority.TrustException.class)
                .hasMessageContaining("REGIONAL_ISOLATION_DECISION_DRIFTED");
        assertThat(permitCloses).hasValue(1);
    }

    private RegionalDataPlaneCertificationAuthority regional(
            AtomicInteger reads, boolean stable) {
        RegionalDataPlaneCertificationMaterialSource source =
                new RegionalDataPlaneCertificationMaterialSource() {
                    @Override
                    public Current current(CapabilitySnapshot.Scope scope) {
                        int read = reads.incrementAndGet();
                        MirrorDeploymentIsolationAttestationBundle decision =
                                !stable && read >= 3 ? fixtures.isolationV1 : fixtures.isolationV2;
                        return new Current(decision, fixtures.contract, fixtures.certification,
                                fixtures.authorityKey, fixtures.deployment);
                    }

                    @Override
                    public boolean available() {
                        return true;
                    }
                };
        return new VerifiedRegionalDataPlaneCertificationAuthority(source, fixtures.integrity);
    }

    private MirrorDeploymentIsolationRunTrustAuthority base(AtomicInteger permitCloses) {
        Instant admittedAt = fixtures.now.plusSeconds(1);
        MirrorArtifactRef snapshot = RegionalDataPlaneCertificationTestFixtures.ref(
                MirrorDeploymentIsolationAgentSnapshot.ARTIFACT_KIND,
                "agent-snapshot", 5, 'd');
        var admission = new MirrorDeploymentIsolationRunTrust.Admission(
                fixtures.scope, fixtures.isolationV2.artifactRef(),
                fixtures.isolationV2.authorityKeySetRef(),
                fixtures.isolationV2.attestation().artifactRef(),
                fixtures.isolationV2.status().artifactRef(), snapshot,
                admittedAt, admittedAt.plusSeconds(100));
        var binding = new MirrorDeploymentIsolationRunTrust.Binding("",
                admission.decisionRef(), admission.authorityKeySetRef(),
                admission.attestationRef(), admission.statusRef(), snapshot,
                new MirrorArtifactRef(snapshot.kind(), snapshot.id(), 6,
                        RegionalDataPlaneCertificationTestFixtures.fingerprint('e')),
                admittedAt, admittedAt.plusSeconds(1));
        return new MirrorDeploymentIsolationRunTrustAuthority() {
            @Override
            public MirrorDeploymentIsolationRunTrust.Admission admit(
                    CapabilitySnapshot.Scope scope) {
                return admission;
            }

            @Override
            public MirrorDeploymentIsolationRunTrust.Binding confirm(
                    MirrorDeploymentIsolationRunTrust.Admission admission,
                    Instant startedAt,
                    Instant completedAt) {
                return binding;
            }

            @Override
            public CommitPermit acquireCommitPermit(
                    CapabilitySnapshot.Scope scope,
                    MirrorDeploymentIsolationRunTrust.Binding binding) {
                return permitCloses::incrementAndGet;
            }

            @Override
            public boolean available() {
                return true;
            }
        };
    }
}
