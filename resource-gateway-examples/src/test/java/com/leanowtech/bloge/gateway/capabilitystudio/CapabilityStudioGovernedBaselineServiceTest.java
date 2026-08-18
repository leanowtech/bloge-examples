package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioGovernedRegistryGateway;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CapabilityStudioGovernedBaselineServiceTest {

    @Test
    void failsClosedWithoutInventingEvidenceWhenTheServerOwnedBatchIdentityIsInvalid() {
        CapabilityStudioGovernedBaselineService service = new CapabilityStudioGovernedBaselineService(
                mock(CapabilityStudioGoldenDemoPack.class),
                new ObjectMapper(),
                mock(OperatorRegistry.class),
                mock(CapabilityStudioFeatureRehearsalService.class),
                mock(CapabilityStudioScenarioDatasetProjector.class),
                mock(ScenarioGovernedRegistryGateway.class),
                mock(CapabilityStudioGovernedCandidateService.class),
                CapabilityStudioDeploymentCandidateAuthority.unbound(),
                () -> "  ");

        CapabilityStudioGovernedBaselineProjection result = service.run();

        assertThat(result.status())
                .isEqualTo(CapabilityStudioGovernedBaselineProjection.FAILED_CLOSED);
        assertThat(result.verificationLevel())
                .isEqualTo(CapabilityStudioGovernedBaselineProjection.NOT_VERIFIED);
        assertThat(result.suiteRunCount()).isZero();
        assertThat(result.childRunCount()).isZero();
        assertThat(result.oraclePassCount()).isZero();
        assertThat(result.businessCheckCount()).isZero();
        assertThat(result.businessCheckPassCount()).isZero();
        assertThat(result.realExternalCallCount()).isNull();
        assertThat(result.evidenceClass()).isNull();
        assertThat(result.compilationFingerprint()).isNull();
        assertThat(result.sourceMapFingerprint()).isNull();
        assertThat(result.provenanceFingerprint()).isNull();
        assertThat(result.candidateBuild()).isNull();
        assertThat(result.candidateIntentFingerprint()).isNull();
        assertThat(result.publication()).isNull();
        assertThat(result.rounds()).isEmpty();
        assertThat(result.cases()).isEmpty();
        assertThat(result.diagnostics()).containsExactly("BATCH_ID_MISSING");
    }
}
