package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityObservationLifecycleArchiveProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerLifecycleArchivePage;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteStabilityObservationLedgerLifecycleArchivePageIntegrityTest {
    private ObjectMapper mapper;
    private TestSuiteStabilityObservationLifecycleArchiveProtocolFixtures.Fixture fixture;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        fixture = TestSuiteStabilityObservationLifecycleArchiveProtocolFixtures.page(
                mapper, new InMemoryVisualEvidenceSigner());
    }

    @Test
    void closesCanonicalPageIdentityTransitionsAndExternalProofRefs() {
        var response = fixture.v2Response();
        var page = response.page();

        assertThat(TestSuiteStabilityObservationLedgerLifecycleArchivePageIntegrity.valid(
                mapper, page)).isTrue();
        assertThat(TestSuiteStabilityObservationLedgerLifecycleArchivePageIntegrity
                .lifecyclePageId(mapper, page.requestFingerprint(), page.pageFingerprint()))
                .isEqualTo(response.lifecyclePageId());
        assertThat(TestSuiteStabilityObservationLedgerLifecycleArchivePageIntegrity.archiveRefs(
                page.retirements(), page.externalArchiveReceiptSets()))
                .isEqualTo(response.attestation().archiveRefs());
        assertThat(response.attestation().archiveRefs().getFirst().receiptSetId())
                .isEqualTo(fixture.receiptSet().receiptSetId());
    }

    @Test
    void rejectsMissingOrPairwiseDifferentReceiptMaterial() {
        var page = fixture.v2Response().page();
        assertThatThrownBy(() -> new TestSuiteStabilityObservationLedgerLifecycleArchivePage(
                page.schemaVersion(), page.requestFingerprint(), page.request(),
                page.scopeFingerprint(), page.startingFloor(), page.retirements(), List.of(),
                page.terminalFloor(), page.currentFloor(), page.head(), page.hasMore(),
                page.observedAt(), page.pageFingerprint()))
                .isInstanceOf(IllegalArgumentException.class);

        var another = TestSuiteStabilityObservationLifecycleArchiveProtocolFixtures.page(
                mapper, new InMemoryVisualEvidenceSigner()).receiptSet();
        assertThatThrownBy(() -> new TestSuiteStabilityObservationLedgerLifecycleArchivePage(
                page.schemaVersion(), page.requestFingerprint(), page.request(),
                page.scopeFingerprint(), page.startingFloor(), page.retirements(),
                List.of(another), page.terminalFloor(), page.currentFloor(), page.head(),
                page.hasMore(), page.observedAt(), page.pageFingerprint()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void detectsPageFingerprintTamperWithoutTrustingOuterSignature() {
        var page = fixture.v2Response().page();
        var forged = new TestSuiteStabilityObservationLedgerLifecycleArchivePage(
                page.schemaVersion(), page.requestFingerprint(), page.request(),
                page.scopeFingerprint(), page.startingFloor(), page.retirements(),
                page.externalArchiveReceiptSets(), page.terminalFloor(), page.currentFloor(),
                page.head(), page.hasMore(), page.observedAt(),
                TestSuiteStabilityProtocolFixtures.fingerprint('f'));

        assertThat(TestSuiteStabilityObservationLedgerLifecycleArchivePageIntegrity.valid(
                mapper, forged)).isFalse();
    }
}
