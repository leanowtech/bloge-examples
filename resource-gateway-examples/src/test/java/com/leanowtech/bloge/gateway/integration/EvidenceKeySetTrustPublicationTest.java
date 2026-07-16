package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Instant;
import java.util.List;

import static com.leanowtech.bloge.gateway.integration.EvidenceKeySetTrustTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceKeySetTrustPublicationTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Authority security = new Authority("security-a", keyPair());
    private final Authority release = new Authority("release-b", keyPair());

    @Test
    void twoOfTwoExternalQuorumVerifiesCanonicalPublicationWithoutEmbeddingTrustKeys() {
        EvidenceKeySetTrustPublication publication = publication(mapper, 1, "", 0,
                PUBLISHED_AT, List.of(active(SNAPSHOT_A)), List.of(security, release));
        ConfiguredEvidenceKeySetTrustStore store = store(mapper, 2, List.of(security, release));

        EvidenceKeySetTrustStore.Verification verification = store.verify(
                publication, PUBLISHED_AT.plusSeconds(1));

        assertThat(verification.verified()).isTrue();
        assertThat(verification.validSignatureCount()).isEqualTo(2);
        assertThat(publication.fingerprintVerified(mapper)).isTrue();
        assertThat(mapper.valueToTree(publication).toString())
                .doesNotContain("publicKey", "privateKey");
    }

    @Test
    void missingQuorumTamperedMaterialWrongIdentityAndStalePolicyFailClosed() {
        EvidenceKeySetTrustPublication oneSignature = publication(mapper, 1, "", 0,
                PUBLISHED_AT, List.of(active(SNAPSHOT_A)), List.of(security));
        ConfiguredEvidenceKeySetTrustStore store = store(mapper, 2, List.of(security, release));
        assertThat(store.verify(oneSignature, PUBLISHED_AT.plusSeconds(1)).reasonCode())
                .isEqualTo("TRUST_AUTHORITY_QUORUM_NOT_MET");

        EvidenceKeySetTrustPublication tampered = new EvidenceKeySetTrustPublication(
                oneSignature.schemaVersion(), oneSignature.publicationFingerprint(),
                oneSignature.trustDomain(), oneSignature.logId(), oneSignature.sequence(),
                oneSignature.previousPublicationFingerprint(), oneSignature.recoveryEpoch(),
                oneSignature.publishedAt(), oneSignature.expiresAt(),
                List.of(active(SNAPSHOT_B)), oneSignature.signatures());
        assertThat(store.verify(tampered, PUBLISHED_AT.plusSeconds(1)).reasonCode())
                .isEqualTo("TRUST_PUBLICATION_MATERIAL_INVALID");

        ConfiguredEvidenceKeySetTrustStore wrongLog = new ConfiguredEvidenceKeySetTrustStore(
                mapper, TRUST_DOMAIN, "another-log", 1,
                List.of(new ConfiguredEvidenceKeySetTrustStore.AuthorityKey(
                        security.authorityId(), security.keyPair().getPublic(),
                        Instant.MIN, Instant.MAX, true, false)));
        assertThat(wrongLog.verify(oneSignature, PUBLISHED_AT.plusSeconds(1)).reasonCode())
                .isEqualTo("TRUST_LOG_IDENTITY_MISMATCH");
        assertThat(store.verify(oneSignature, PUBLISHED_AT.plusSeconds(601)).reasonCode())
                .isEqualTo("TRUST_PUBLICATION_TIME_INVALID");
    }

    @Test
    void localShapeRejectsDuplicatePinsSignersAndAmbiguousActivePolicy() {
        assertThatThrownBy(() -> publication(mapper, 1, "", 0, PUBLISHED_AT,
                List.of(active(SNAPSHOT_A), overlap(SNAPSHOT_A)), List.of(security, release)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pin policy");
        assertThatThrownBy(() -> publication(mapper, 1, "", 0, PUBLISHED_AT,
                List.of(active(SNAPSHOT_A), active(SNAPSHOT_B)), List.of(security, release)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Exactly one active");
        assertThatThrownBy(() -> publication(mapper, 1, "", 0, PUBLISHED_AT,
                List.of(active(SNAPSHOT_A)), List.of(security, security)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be unique");
    }

    @Test
    void capabilityAvailabilityReflectsCurrentlyUsableAuthorityQuorum() {
        ConfiguredEvidenceKeySetTrustStore expiredStore = new ConfiguredEvidenceKeySetTrustStore(
                mapper, TRUST_DOMAIN, LOG_ID, 1,
                List.of(new ConfiguredEvidenceKeySetTrustStore.AuthorityKey(
                        security.authorityId(), security.keyPair().getPublic(),
                        Instant.parse("1999-01-01T00:00:00Z"),
                        Instant.parse("2000-01-01T00:00:00Z"), true, false)));

        assertThat(expiredStore.descriptor().available()).isFalse();
        assertThat(expiredStore.descriptor().properties())
                .containsEntry("activeAuthorityCount", 0L);
        assertThat(store(mapper, 1, List.of(security)).descriptor().available()).isTrue();
    }
}
