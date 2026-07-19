package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteStabilityObservationLedgerLifecycleArchiveEvidenceVerifierTest {
    @Test
    void independentlyVerifiesGatewayLifecycleAndExternalArchiveProofs() {
        var fixture = TestSuiteStabilityObservationLedgerLifecycleArchiveTestFixtures
                .stableFixture();

        var result = verifier().verify(fixture.page(),
                Map.of(fixture.lifecycleKey().keyId(), fixture.lifecycleKey()),
                fixture.archivePolicy());

        assertThat(result.verified()).as(result.reasonCode()).isTrue();
        assertThat(result.verifiedRetirements()).isEqualTo(1);
        assertThat(result.verifiedObservations()).isEqualTo(2);
        assertThat(result.verifiedReceiptSets()).isEqualTo(1);
        assertThat(result.verifiedReceipts()).isEqualTo(1);
        assertThat(result.checkpoint().complete()).isTrue();
        assertThat(TestSuiteStabilityObservationLedgerLifecycleArchiveEvidenceVerifier
                .requiredLifecycleKeyIds(fixture.page()))
                .containsExactly(fixture.lifecycleKey().keyId());
        assertThat(TestSuiteStabilityObservationLedgerLifecycleArchiveEvidenceVerifier
                .requiredArchiveKeyIds(fixture.page()))
                .containsExactly(fixture.archiveKey().keyId());
    }

    @Test
    void verifiesPinnedLifecycleKeySetAndRejectsWrongExternalPin() {
        var fixture = TestSuiteStabilityObservationLedgerLifecycleArchiveTestFixtures
                .stableFixture();

        var verified = verifier().verify(fixture.page(), fixture.lifecycleKeySet(),
                fixture.lifecycleKeySet().snapshotFingerprint(), fixture.archivePolicy());
        var wrongPin = verifier().verify(fixture.page(), fixture.lifecycleKeySet(),
                "sha256:" + "f".repeat(64), fixture.archivePolicy());

        assertThat(verified.verified()).as(verified.reasonCode()).isTrue();
        assertThat(wrongPin.outcome()).isEqualTo(
                TestSuiteStabilityObservationLedgerLifecycleArchiveEvidenceVerifier.Outcome
                        .POLICY_REJECTED);
        assertThat(wrongPin.reasonCode()).isEqualTo("KEY_SET_PIN_MISMATCH");
    }

    @Test
    void advancesOneCheckpointAcrossReceiptAwarePages() {
        var fixture = TestSuiteStabilityObservationLedgerLifecycleArchiveTestFixtures
                .twoPageFixture();
        var keys = Map.of(fixture.lifecycleKey().keyId(), fixture.lifecycleKey());

        var first = verifier().verify(
                fixture.firstPage(), keys, fixture.archivePolicy());
        var continuation = fixture.firstPage().request().continueAfter(fixture.firstPage());
        var second = verifier().verify(
                fixture.secondPage(), first.checkpoint(), keys, fixture.archivePolicy());

        assertThat(first.verified()).as(first.reasonCode()).isTrue();
        assertThat(first.checkpoint().complete()).isFalse();
        assertThat(first.verifiedReceiptSets()).isEqualTo(1);
        assertThat(continuation).isEqualTo(fixture.secondPage().request());
        assertThat(second.verified()).as(second.reasonCode()).isTrue();
        assertThat(second.checkpoint().complete()).isTrue();
        assertThat(second.checkpoint().terminalRetirementGeneration()).isEqualTo(2);
    }

    @Test
    void reportsCallerPolicyRejectionsWithoutCollapsingThemIntoSignatureFailure() {
        var fixture = TestSuiteStabilityObservationLedgerLifecycleArchiveTestFixtures
                .stableFixture();
        var policy = fixture.archivePolicy();

        var wrongDomain = verifier().verify(fixture.page(), lifecycleKeys(fixture),
                policy(policy, "other.example", policy.archiveSetId(),
                        policy.acceptedRetentionPolicyFingerprints(), 1,
                        policy.requiredRetainUntil(), policy.authorities()));
        var wrongRetention = verifier().verify(fixture.page(), lifecycleKeys(fixture),
                policy(policy, policy.trustDomain(), policy.archiveSetId(),
                        Set.of("sha256:" + "f".repeat(64)), 1,
                        policy.requiredRetainUntil(), policy.authorities()));
        var lateHorizon = verifier().verify(fixture.page(), lifecycleKeys(fixture),
                policy(policy, policy.trustDomain(), policy.archiveSetId(),
                        policy.acceptedRetentionPolicyFingerprints(), 1,
                        fixture.page().externalArchiveReceiptSets().getFirst()
                                .retainUntil().plusSeconds(1), policy.authorities()));
        Map<String, TestSuiteStabilityObservationExternalArchiveTrustPolicy.TrustedAuthority>
                twoAuthorities = new LinkedHashMap<>(policy.authorities());
        twoAuthorities.put("archive-b",
                new TestSuiteStabilityObservationExternalArchiveTrustPolicy.TrustedAuthority(
                        "archive-b", "region-b",
                        Map.of(fixture.archiveKey().keyId(), fixture.archiveKey())));
        var tooManyCopies = verifier().verify(fixture.page(), lifecycleKeys(fixture),
                policy(policy, policy.trustDomain(), policy.archiveSetId(),
                        policy.acceptedRetentionPolicyFingerprints(), 2,
                        policy.requiredRetainUntil(), twoAuthorities));

        assertRejected(wrongDomain, "EXTERNAL_ARCHIVE_TRUST_DOMAIN_REJECTED");
        assertRejected(wrongRetention, "EXTERNAL_ARCHIVE_RETENTION_POLICY_REJECTED");
        assertRejected(lateHorizon, "EXTERNAL_ARCHIVE_RETENTION_HORIZON_REJECTED");
        assertRejected(tooManyCopies, "EXTERNAL_ARCHIVE_COPY_THRESHOLD_REJECTED");
    }

    @Test
    void distinguishesUnavailableExternalAuthorityKey() {
        var fixture = TestSuiteStabilityObservationLedgerLifecycleArchiveTestFixtures
                .stableFixture();
        var policy = fixture.archivePolicy();
        var authority = new TestSuiteStabilityObservationExternalArchiveTrustPolicy
                .TrustedAuthority("archive-a", "region-a",
                Map.of(fixture.lifecycleKey().keyId(), fixture.lifecycleKey()));
        var missingKey = policy(policy, policy.trustDomain(), policy.archiveSetId(),
                policy.acceptedRetentionPolicyFingerprints(), 1,
                policy.requiredRetainUntil(), Map.of(authority.authorityId(), authority));

        var result = verifier().verify(
                fixture.page(), lifecycleKeys(fixture), missingKey);

        assertThat(result.outcome()).isEqualTo(
                TestSuiteStabilityObservationLedgerLifecycleArchiveEvidenceVerifier.Outcome
                        .KEY_UNAVAILABLE);
        assertThat(result.reasonCode())
                .isEqualTo("EXTERNAL_ARCHIVE_VERIFICATION_KEY_UNAVAILABLE");
        assertThat(result.checkpoint()).isNull();
    }

    @Test
    void rejectsInvalidExternalSignatureEvenAfterGatewayResealsEveryLocalLayer() {
        var fixture = TestSuiteStabilityObservationLedgerLifecycleArchiveTestFixtures
                .stableFixture();
        ObjectNode changed = fixture.copyResponse();
        ((ObjectNode) changed.at("/page/externalArchiveReceiptSets/0/receipts/0"))
                .put("signature", Base64.getEncoder().encodeToString(new byte[64]));
        TestSuiteStabilityObservationLedgerLifecycleArchiveTestFixtures
                .resealGatewayMaterial(changed, fixture.lifecycleKeyPair());

        var result = verifier().verify(
                TestSuiteStabilityObservationLedgerLifecycleArchivePage.from(changed),
                lifecycleKeys(fixture), fixture.archivePolicy());

        assertThat(result.outcome()).isEqualTo(
                TestSuiteStabilityObservationLedgerLifecycleArchiveEvidenceVerifier.Outcome
                        .INVALID);
        assertThat(result.reasonCode()).isEqualTo("EXTERNAL_ARCHIVE_RECEIPT_SIGNATURE_INVALID");
        assertThat(result.checkpoint()).isNull();
    }

    @Test
    void rejectsReceiptRebindingHiddenBehindFreshGatewayHashesAndSignature() {
        var fixture = TestSuiteStabilityObservationLedgerLifecycleArchiveTestFixtures
                .stableFixture();
        ObjectNode changed = fixture.copyResponse();
        ObjectNode receipt = (ObjectNode) changed.at(
                "/page/externalArchiveReceiptSets/0/receipts/0");
        receipt.put("retainUntil", fixture.page().externalArchiveReceiptSets().getFirst()
                .retainUntil().plusSeconds(86_400).toString());
        receipt.put("receiptFingerprint",
                TestSuiteStabilityObservationLedgerLifecycleArchiveTestFixtures
                        .receiptFingerprint(receipt));
        TestSuiteStabilityObservationLedgerLifecycleArchiveTestFixtures
                .resealGatewayMaterial(changed, fixture.lifecycleKeyPair());

        var result = verifier().verify(
                TestSuiteStabilityObservationLedgerLifecycleArchivePage.from(changed),
                lifecycleKeys(fixture), fixture.archivePolicy());

        assertThat(result.reasonCode()).isEqualTo("EXTERNAL_ARCHIVE_RECEIPT_SIGNATURE_INVALID");
        assertThat(result.verifiedReceiptSets()).isZero();
        assertThat(result.verifiedReceipts()).isZero();
    }

    @Test
    void rejectsExpiredConfirmationAndUnknownFieldsBeforeTrustEvaluation() {
        var fixture = TestSuiteStabilityObservationLedgerLifecycleArchiveTestFixtures
                .stableFixture();
        ObjectNode expired = fixture.copyResponse();
        ObjectNode receiptSet = (ObjectNode) expired.at(
                "/page/externalArchiveReceiptSets/0");
        receiptSet.put("confirmedAt",
                receiptSet.at("/receipts/0/expiresAt").asText());
        TestSuiteStabilityObservationLedgerLifecycleArchiveTestFixtures
                .resealGatewayMaterial(expired, fixture.lifecycleKeyPair());
        ObjectNode unknown = fixture.copyResponse();
        ((ObjectNode) unknown.path("page")).put("tenantId", "tenant-a");

        assertThatThrownBy(() ->
                TestSuiteStabilityObservationLedgerLifecycleArchivePage.from(expired))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("receipt set");
        assertThatThrownBy(() ->
                TestSuiteStabilityObservationLedgerLifecycleArchivePage.from(unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authoritative schema");
    }

    private static TestSuiteStabilityObservationLedgerLifecycleArchiveEvidenceVerifier verifier() {
        return new TestSuiteStabilityObservationLedgerLifecycleArchiveEvidenceVerifier(
                Clock.fixed(EvidenceTrustTestFixtures.NOW.plusSeconds(300), ZoneOffset.UTC));
    }

    private static Map<String, EvidenceVerificationKey> lifecycleKeys(
            TestSuiteStabilityObservationLedgerLifecycleArchiveTestFixtures.Fixture fixture) {
        return Map.of(fixture.lifecycleKey().keyId(), fixture.lifecycleKey());
    }

    private static TestSuiteStabilityObservationExternalArchiveTrustPolicy policy(
            TestSuiteStabilityObservationExternalArchiveTrustPolicy base,
            String trustDomain,
            String archiveSetId,
            Set<String> retentionPolicies,
            int minimumCopies,
            java.time.Instant requiredRetainUntil,
            Map<String, TestSuiteStabilityObservationExternalArchiveTrustPolicy.TrustedAuthority>
                    authorities) {
        return new TestSuiteStabilityObservationExternalArchiveTrustPolicy(
                base.schemaVersion(), trustDomain, archiveSetId, retentionPolicies,
                minimumCopies, requiredRetainUntil, authorities);
    }

    private static void assertRejected(
            TestSuiteStabilityObservationLedgerLifecycleArchiveEvidenceVerifier
                    .VerificationResult result,
            String reasonCode) {
        assertThat(result.outcome()).isEqualTo(
                TestSuiteStabilityObservationLedgerLifecycleArchiveEvidenceVerifier.Outcome
                        .POLICY_REJECTED);
        assertThat(result.reasonCode()).isEqualTo(reasonCode);
        assertThat(result.checkpoint()).isNull();
    }
}
