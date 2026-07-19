package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityObservationExternalArchiveProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityObservationLifecycleProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveReceipt;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveReceiptSet;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveRequest;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteStabilityObservationExternalArchiveIntegrityTest {
    private ObjectMapper mapper;
    private TestSuiteStabilityObservationExternalArchiveReceiptSet receiptSet;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        var signer = new InMemoryVisualEvidenceSigner();
        var retirement = TestSuiteStabilityObservationLifecycleProtocolFixtures
                .page(mapper, signer).page().retirements().getFirst();
        receiptSet = TestSuiteStabilityObservationExternalArchiveProtocolFixtures.receiptSet(
                mapper, retirement, retirement.evidence().retiredAt().plus(Duration.ofDays(365)));
    }

    @Test
    void canonicalRequestReceiptAndSetCloseOnTheExactRetirement() {
        assertThat(receiptSet.request().challengeBytes()).hasSize(32);
        assertThat(receiptSet.request().fingerprintVerified(mapper)).isTrue();
        assertThat(receiptSet.receipts()).allMatch(receipt ->
                receipt.fingerprintVerified(mapper));
        assertThat(TestSuiteStabilityObservationExternalArchiveIntegrity.valid(
                mapper, receiptSet)).isTrue();
        assertThat(receiptSet.receipts().getFirst().objectId()).isEqualTo(
                TestSuiteStabilityObservationExternalArchiveIntegrity.objectId(
                        mapper, receiptSet.request().retirement()));
    }

    @Test
    void malformedChallengeAndShortenedRetentionAreRejected() {
        TestSuiteStabilityObservationExternalArchiveRequest request = receiptSet.request();
        assertThatThrownBy(() -> new TestSuiteStabilityObservationExternalArchiveRequest(
                request.schemaVersion(), request.requestFingerprint(), request.trustDomain(),
                request.archiveSetId(), request.retirement(), request.retainUntil(), "short",
                request.requestedAt(), request.expiresAt()))
                .isInstanceOf(IllegalArgumentException.class);

        TestSuiteStabilityObservationExternalArchiveRequest longerRequest =
                TestSuiteStabilityObservationExternalArchiveRequest.create(
                        mapper, request.trustDomain(), request.archiveSetId(), request.retirement(),
                        request.retainUntil().plusSeconds(1), request.challenge(),
                        request.requestedAt(), request.expiresAt());
        assertThatThrownBy(() -> new TestSuiteStabilityObservationExternalArchiveReceiptSet(
                receiptSet.schemaVersion(), receiptSet.receiptSetId(), longerRequest, 1,
                receiptSet.receipts(), receiptSet.confirmedAt(),
                receiptSet.receiptSetFingerprint()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void insufficientDuplicateAndUnsortedCopyTopologiesAreRejected() {
        TestSuiteStabilityObservationExternalArchiveReceipt receipt =
                receiptSet.receipts().getFirst();
        assertThatThrownBy(() -> copySet(2, List.of(receipt)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copySet(2, List.of(receipt, receipt)))
                .isInstanceOf(IllegalArgumentException.class);

        TestSuiteStabilityObservationExternalArchiveReceipt second = copyReceipt(
                receipt, "archive-b", "region-b");
        assertThatThrownBy(() -> copySet(2, List.of(second, receipt)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void confirmationMustRemainInsideEveryReceiptAdmissionWindow() {
        TestSuiteStabilityObservationExternalArchiveReceipt receipt =
                receiptSet.receipts().getFirst();

        assertThatThrownBy(() -> new TestSuiteStabilityObservationExternalArchiveReceiptSet(
                receiptSet.schemaVersion(), receiptSet.receiptSetId(), receiptSet.request(),
                receiptSet.requiredCopies(), receiptSet.receipts(), receipt.expiresAt(),
                receiptSet.receiptSetFingerprint()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid external observation-archive receipt set");
    }

    @Test
    void requestExpiryIsExclusiveEvenWhenTheReceiptRemainsLive() {
        TestSuiteStabilityObservationExternalArchiveReceipt receipt =
                receiptSet.receipts().getFirst();
        TestSuiteStabilityObservationExternalArchiveReceipt longerReceipt =
                new TestSuiteStabilityObservationExternalArchiveReceipt(
                        receipt.schemaVersion(), receipt.receiptFingerprint(),
                        receipt.requestFingerprint(), receipt.trustDomain(),
                        receipt.archiveSetId(), receipt.authorityId(), receipt.failureDomain(),
                        receipt.keyId(), receipt.objectId(), receipt.retirementId(),
                        receipt.retirementFingerprint(), receipt.segmentId(),
                        receipt.segmentFingerprint(), receipt.retentionPolicyFingerprint(),
                        receipt.retainUntil(), receipt.storedAt(), receipt.issuedAt(),
                        receipt.expiresAt().plusSeconds(1), receipt.retentionMode(),
                        receipt.externallyDurable(), receipt.writeOnce(),
                        receipt.deleteBeforeRetentionDenied(), receipt.algorithm(),
                        receipt.signature());

        assertThat(longerReceipt.expiresAt()).isAfter(receiptSet.request().expiresAt());
        assertThatThrownBy(() -> new TestSuiteStabilityObservationExternalArchiveReceiptSet(
                receiptSet.schemaVersion(), receiptSet.receiptSetId(), receiptSet.request(),
                receiptSet.requiredCopies(), List.of(longerReceipt),
                receiptSet.request().expiresAt(), receiptSet.receiptSetFingerprint()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid external observation-archive receipt set");
    }

    @Test
    void canonicalIntegrityRejectsReceiptMaterialRebinding() {
        TestSuiteStabilityObservationExternalArchiveReceipt original =
                receiptSet.receipts().getFirst();
        TestSuiteStabilityObservationExternalArchiveReceipt rebound = copyReceipt(
                original, "archive-b", original.failureDomain());
        TestSuiteStabilityObservationExternalArchiveReceiptSet reboundSet = copySet(
                1, List.of(rebound));

        assertThat(rebound.fingerprintVerified(mapper)).isFalse();
        assertThat(TestSuiteStabilityObservationExternalArchiveIntegrity.valid(
                mapper, reboundSet)).isFalse();
    }

    private TestSuiteStabilityObservationExternalArchiveReceiptSet copySet(
            int requiredCopies,
            List<TestSuiteStabilityObservationExternalArchiveReceipt> receipts) {
        return new TestSuiteStabilityObservationExternalArchiveReceiptSet(
                receiptSet.schemaVersion(), receiptSet.receiptSetId(), receiptSet.request(),
                requiredCopies, receipts, receiptSet.confirmedAt(),
                receiptSet.receiptSetFingerprint());
    }

    private static TestSuiteStabilityObservationExternalArchiveReceipt copyReceipt(
            TestSuiteStabilityObservationExternalArchiveReceipt receipt,
            String authorityId,
            String failureDomain) {
        return new TestSuiteStabilityObservationExternalArchiveReceipt(
                receipt.schemaVersion(), receipt.receiptFingerprint(),
                receipt.requestFingerprint(), receipt.trustDomain(), receipt.archiveSetId(),
                authorityId, failureDomain, receipt.keyId(), receipt.objectId(),
                receipt.retirementId(), receipt.retirementFingerprint(), receipt.segmentId(),
                receipt.segmentFingerprint(), receipt.retentionPolicyFingerprint(),
                receipt.retainUntil(), receipt.storedAt(), receipt.issuedAt(),
                receipt.expiresAt(), receipt.retentionMode(), receipt.externallyDurable(),
                receipt.writeOnce(), receipt.deleteBeforeRetentionDenied(), receipt.algorithm(),
                receipt.signature());
    }
}
