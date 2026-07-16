package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualGraphRunRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.leanowtech.bloge.gateway.integration.EvidenceKeySetTrustTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolStudioEvidenceTrustIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void externallyAuthorizedPublicationBecomesBoundedKeySetTrustBundle() {
        Authority authority = new Authority("security-a", keyPair());
        ToolStudioIntegrationService service = service();
        ConfiguredEvidenceKeySetTrustStore trustStore = store(mapper, 1, List.of(authority));
        service.configureEvidenceTrust(trustStore,
                new InMemoryEvidenceKeySetTrustPublicationRepository());
        String currentSnapshot = service.evidenceKeySet().payload().snapshotFingerprint();
        Instant publishedAt = Instant.now().minusSeconds(1);
        EvidenceKeySetTrustPublication publication = publication(mapper, 1, "", 0,
                publishedAt, List.of(active(currentSnapshot)), List.of(authority));

        IntegrationEnvelope<EvidenceKeySetTrustPublication> stored =
                service.publishEvidenceKeySetTrust(publication, adminContext());
        IntegrationEnvelope<EvidenceKeySetTrustBundle> firstPage =
                service.evidenceKeySetTrustBundle(0, 64);
        IntegrationEnvelope<EvidenceKeySetTrustBundle> unchanged =
                service.evidenceKeySetTrustBundle(1, 64);

        assertThat(stored.payloadKind()).isEqualTo("EVIDENCE_KEY_SET_TRUST_PUBLICATION");
        assertThat(firstPage.payloadKind()).isEqualTo("EVIDENCE_KEY_SET_TRUST_BUNDLE");
        assertThat(firstPage.payload().publications()).containsExactly(publication);
        assertThat(firstPage.payload().hasMore()).isFalse();
        assertThat(firstPage.payload().headPublicationFingerprint())
                .isEqualTo(publication.publicationFingerprint());
        assertThat(firstPage.payload().keySet().snapshotFingerprint()).isEqualTo(currentSnapshot);
        assertThat(unchanged.payload().publications()).isEmpty();
        assertThat(unchanged.payload().throughSequence()).isEqualTo(1);
        assertThat(service.capabilities().payload().features())
                .containsEntry("trustedEvidenceKeySetPinDistribution", true)
                .containsEntry("evidenceKeySetTransparencyLog", true)
                .containsEntry("evidenceTrustAuthorityQuorum", true)
                .containsEntry("evidenceTrustRollbackAndForkDetection", true);
    }

    @Test
    void publicationMustBindCurrentSnapshotAndRequireDedicatedPurpose() {
        Authority authority = new Authority("security-a", keyPair());
        ToolStudioIntegrationService service = service();
        service.configureEvidenceTrust(store(mapper, 1, List.of(authority)),
                new InMemoryEvidenceKeySetTrustPublicationRepository());
        EvidenceKeySetTrustPublication wrongPin = publication(mapper, 1, "", 0,
                Instant.now().minusSeconds(1), List.of(active(SNAPSHOT_A)), List.of(authority));

        assertThatThrownBy(() -> service.publishEvidenceKeySetTrust(wrongPin, adminContext()))
                .isInstanceOfSatisfying(IntegrationProblemException.class,
                        failure -> assertThat(failure.problem().code())
                                .isEqualTo("RG.INTEGRATION.EVIDENCE_TRUST_KEY_SET_STALE"));
        IntegrationRequestContext wrongPurpose = new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "prod", "sg", "SERVICE",
                "aneke", "", "GOVERNANCE_EVIDENCE_INGESTION", "correlation-2");
        assertThatThrownBy(() -> service.publishEvidenceKeySetTrust(wrongPin, wrongPurpose))
                .isInstanceOfSatisfying(IntegrationProblemException.class,
                        failure -> assertThat(failure.problem().code())
                                .isEqualTo("RG.INTEGRATION.PURPOSE_NOT_ALLOWED"));
    }

    @Test
    void absentExternalTrustPolicyIsHonestAndFailsClosed() {
        ToolStudioIntegrationService service = service();

        assertThat(service.capabilities().payload().features())
                .containsEntry("trustedEvidenceKeySetPinDistribution", false)
                .containsEntry("evidenceKeySetTransparencyLog", false);
        assertThatThrownBy(() -> service.evidenceKeySetTrustBundle(0, 64))
                .isInstanceOfSatisfying(IntegrationProblemException.class,
                        failure -> assertThat(failure.problem().code())
                                .isEqualTo("RG.INTEGRATION.EVIDENCE_TRUST_UNAVAILABLE"));
    }

    private ToolStudioIntegrationService service() {
        return new ToolStudioIntegrationService(null, null, null,
                new InMemoryVisualGraphRunRepository(),
                new InMemoryGovernanceGateResultRepository(), mapper);
    }

    private static IntegrationRequestContext adminContext() {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "prod", "sg",
                "SERVICE", "security-automation", "", "EVIDENCE_TRUST_ADMIN", "correlation-1");
    }
}
