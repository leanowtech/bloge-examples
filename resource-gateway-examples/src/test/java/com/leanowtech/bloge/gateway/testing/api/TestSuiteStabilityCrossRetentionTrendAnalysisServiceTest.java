package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityCrossRetentionTrendProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityCrossRetentionTrendEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityTrendEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityCrossRetentionTrendAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationLedgerEntryIntegrity;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationLedgerRangeIntegrity;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSuiteStabilityCrossRetentionTrendAnalysisServiceTest {
    private ObjectMapper mapper;
    private TestSuiteRegistryService suites;
    private TestSuiteStabilityRunRepository repository;
    private InMemoryVisualEvidenceSigner signer;
    private TestSuiteStabilityAttestationService sourceAttestations;
    private TestSuiteStabilityObservationAttestationService observationAttestations;
    private TestSuiteStabilityCrossRetentionTrendAttestationService trendAttestations;
    private TestSuiteStabilityCrossRetentionTrendProtocolFixtures.Fixture fixture;
    private TestSuiteStabilityCrossRetentionTrendAnalysisService service;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        suites = mock(TestSuiteRegistryService.class);
        repository = mock(TestSuiteStabilityRunRepository.class);
        signer = new InMemoryVisualEvidenceSigner();
        sourceAttestations = new TestSuiteStabilityAttestationService(mapper, signer);
        observationAttestations = new TestSuiteStabilityObservationAttestationService(
                mapper, signer, sourceAttestations);
        trendAttestations =
                new TestSuiteStabilityCrossRetentionTrendAttestationService(mapper, signer);
        fixture = TestSuiteStabilityCrossRetentionTrendProtocolFixtures.range(
                mapper, sourceAttestations, observationAttestations, '1', '2');
        service = new TestSuiteStabilityCrossRetentionTrendAnalysisService(
                suites, repository, mapper, observationAttestations, trendAttestations);
        when(suites.find(eq("suite-a"), eq(3L), any())).thenReturn(storedSuite());
        when(repository.observationRange(
                "tenant-a", "test", TestSuiteStabilityProtocolFixtures.SUITE_REF, 0, 10))
                .thenReturn(Optional.of(fixture.range()));
        when(repository.observationRange(
                "tenant-a", "test", TestSuiteStabilityProtocolFixtures.SUITE_REF, 1, 10))
                .thenReturn(Optional.of(fixture.range()));
    }

    @Test
    void verifiesEveryObservationAndReturnsSignedRangeClosedTrend() {
        TestSuiteStabilityCrossRetentionTrendAnalysisResponse response = service.analyze(
                "suite-a", request(""), identity());

        assertThat(response.evidence().status()).isEqualTo(
                TestSuiteStabilityTrendEvidence.Status.STABLE_PASS);
        assertThat(response.evidence().observedRuns()).isEqualTo(2);
        assertThat(response.evidence().sourceOrder()).isEqualTo(
                TestSuiteStabilityCrossRetentionTrendEvidence.SourceOrder
                        .SOURCE_CREATED_AT_THEN_STABILITY_RUN_ID);
        assertThat(response.evidence().range()).isEqualTo(fixture.range());
        assertThat(response.attestation().observationRefs()).hasSize(2);
        assertThat(response.attestation().rangeFingerprint()).isEqualTo(
                fixture.range().rangeFingerprint());
        assertThat(trendAttestations.verify(
                response.evidence(), response.attestation()))
                .isEqualTo(TestSuiteStabilityCrossRetentionTrendAttestationService
                        .Verification.VERIFIED);
    }

    @Test
    void enforcesPinnedHeadPathPurposeEnvironmentAndClearance() {
        assertCode(() -> service.analyze("suite-a",
                        request(TestSuiteStabilityProtocolFixtures.fingerprint('f')), identity()),
                "RG.TEST.STABILITY_OBSERVATION_HEAD_CHANGED");
        assertCode(() -> service.analyze("other", request(""), identity()),
                "RG.TEST.STABILITY_CROSS_RETENTION_REQUEST_INVALID");
        assertCode(() -> service.analyze("suite-a", request(""),
                        identity("TEST_SUITE_READ", "test", "INTERNAL")),
                "RG.TEST.STABILITY_CROSS_RETENTION_PURPOSE_FORBIDDEN");
        assertCode(() -> service.analyze("suite-a", request(""),
                        identity("TEST_EXECUTION", "production", "INTERNAL")),
                "RG.TEST.ENVIRONMENT_FORBIDDEN");
        assertCode(() -> service.analyze("suite-a", request(""),
                        identity("TEST_EXECUTION", "test", "PUBLIC")),
                "RG.TEST.SUITE_CLEARANCE_FORBIDDEN");
    }

    @Test
    void rejectsPinnedFirstPageAndUnpinnedContinuationBeforeLedgerAccess() {
        assertThatThrownBy(() -> new TestSuiteStabilityCrossRetentionTrendAnalysisRequest(
                TestSuiteStabilityCrossRetentionTrendAnalysisRequest.SCHEMA_VERSION,
                TestSuiteStabilityProtocolFixtures.SUITE_REF, 0, 2, 10,
                TestSuiteStabilityProtocolFixtures.fingerprint('f')))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestSuiteStabilityCrossRetentionTrendAnalysisRequest(
                TestSuiteStabilityCrossRetentionTrendAnalysisRequest.SCHEMA_VERSION,
                TestSuiteStabilityProtocolFixtures.SUITE_REF, 1, 2, 10, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failsClosedForMissingLedgerInvalidCursorAndForgedRangeFingerprint() {
        when(repository.observationRange(
                "tenant-a", "test", TestSuiteStabilityProtocolFixtures.SUITE_REF, 0, 10))
                .thenReturn(Optional.empty());
        assertCode(() -> service.analyze("suite-a", request(""), identity()),
                "RG.TEST.STABILITY_OBSERVATION_LEDGER_NOT_FOUND");

        when(repository.observationRange(
                "tenant-a", "test", TestSuiteStabilityProtocolFixtures.SUITE_REF, 0, 10))
                .thenThrow(new IllegalArgumentException("cursor"));
        assertCode(() -> service.analyze("suite-a", request(""), identity()),
                "RG.TEST.STABILITY_OBSERVATION_CURSOR_INVALID");

        var range = fixture.range();
        var forged = new TestSuiteStabilityObservationLedgerRange(
                range.schemaVersion(), range.scopeFingerprint(), range.suiteRef(),
                range.floorSequence(), range.floorPreviousObservationId(),
                range.floorPreviousEntryFingerprint(), range.floorObservationId(),
                range.floorEntryFingerprint(), range.head(), range.afterSequence(),
                range.previousObservationId(), range.previousEntryFingerprint(),
                range.entries(), range.hasMore(), range.observedAt(),
                TestSuiteStabilityProtocolFixtures.fingerprint('f'));
        doReturn(Optional.of(forged)).when(repository).observationRange(
                "tenant-a", "test", TestSuiteStabilityProtocolFixtures.SUITE_REF, 0, 10);
        assertCode(() -> service.analyze("suite-a", request(""), identity()),
                "RG.TEST.STABILITY_OBSERVATION_RANGE_INVALID");
    }

    @Test
    void rejectsCanonicalEntryContainingAnInvalidObservationSignature() {
        var range = fixture.range();
        var first = range.entries().getFirst();
        TestSuiteStabilityObservation forgedObservation = new TestSuiteStabilityObservation(
                first.observation().evidenceFingerprint(), first.observation().evidence(),
                TestSuiteStabilityProtocolFixtures.fingerprint('f'),
                first.observation().attestation());
        TestSuiteStabilityObservationLedgerEntry unsigned =
                new TestSuiteStabilityObservationLedgerEntry(
                        first.schemaVersion(), first.scopeFingerprint(), first.sequence(),
                        first.previousObservationId(), forgedObservation, first.appendedAt(),
                        TestSuiteStabilityProtocolFixtures.fingerprint('0'));
        TestSuiteStabilityObservationLedgerEntry forgedEntry =
                new TestSuiteStabilityObservationLedgerEntry(
                        unsigned.schemaVersion(), unsigned.scopeFingerprint(), unsigned.sequence(),
                        unsigned.previousObservationId(), unsigned.observation(),
                        unsigned.appendedAt(),
                        TestSuiteStabilityObservationLedgerEntryIntegrity.fingerprint(
                                mapper, unsigned));
        List<TestSuiteStabilityObservationLedgerEntry> entries =
                new ArrayList<>(range.entries());
        entries.set(0, forgedEntry);
        TestSuiteStabilityObservationLedgerRange unsignedRange =
                new TestSuiteStabilityObservationLedgerRange(
                        range.schemaVersion(), range.scopeFingerprint(), range.suiteRef(),
                        range.floorSequence(), range.floorPreviousObservationId(),
                        range.floorPreviousEntryFingerprint(), range.floorObservationId(),
                        forgedEntry.entryFingerprint(), range.head(), range.afterSequence(),
                        range.previousObservationId(), range.previousEntryFingerprint(),
                        entries, range.hasMore(), range.observedAt(),
                        TestSuiteStabilityProtocolFixtures.fingerprint('0'));
        TestSuiteStabilityObservationLedgerRange forgedRange = copyWithFingerprint(
                unsignedRange,
                TestSuiteStabilityObservationLedgerRangeIntegrity.fingerprint(
                        mapper, unsignedRange));
        when(repository.observationRange(
                "tenant-a", "test", TestSuiteStabilityProtocolFixtures.SUITE_REF, 0, 10))
                .thenReturn(Optional.of(forgedRange));

        assertCode(() -> service.analyze("suite-a", request(""), identity()),
                "RG.TEST.STABILITY_OBSERVATION_INVALID");
    }

    @Test
    void observationOrRangeSignerOutageCannotProducePublicEvidence() {
        var unavailableObservationVerifier =
                new TestSuiteStabilityObservationAttestationService(
                        mapper, VisualEvidenceSigner.unavailable(), sourceAttestations);
        var observationUnavailable =
                new TestSuiteStabilityCrossRetentionTrendAnalysisService(
                        suites, repository, mapper, unavailableObservationVerifier,
                        trendAttestations);
        assertCode(() -> observationUnavailable.analyze(
                        "suite-a", request(""), identity()),
                "RG.TEST.STABILITY_OBSERVATION_VERIFICATION_UNAVAILABLE");

        var trendUnavailable = new TestSuiteStabilityCrossRetentionTrendAnalysisService(
                suites, repository, mapper, observationAttestations,
                new TestSuiteStabilityCrossRetentionTrendAttestationService(
                        mapper, VisualEvidenceSigner.unavailable()));
        assertCode(() -> trendUnavailable.analyze("suite-a", request(""), identity()),
                "RG.TEST.STABILITY_CROSS_RETENTION_ATTESTATION_UNAVAILABLE");
    }

    @Test
    void invalidPathStopsBeforeAnyLedgerRead() {
        assertCode(() -> service.analyze("other", request(""), identity()),
                "RG.TEST.STABILITY_CROSS_RETENTION_REQUEST_INVALID");
        verify(repository, never()).observationRange(
                any(), any(), any(), anyLong(), anyInt());
    }

    private static TestSuiteStabilityObservationLedgerRange copyWithFingerprint(
            TestSuiteStabilityObservationLedgerRange range,
            String fingerprint) {
        return new TestSuiteStabilityObservationLedgerRange(
                range.schemaVersion(), range.scopeFingerprint(), range.suiteRef(),
                range.floorSequence(), range.floorPreviousObservationId(),
                range.floorPreviousEntryFingerprint(), range.floorObservationId(),
                range.floorEntryFingerprint(), range.head(), range.afterSequence(),
                range.previousObservationId(), range.previousEntryFingerprint(),
                range.entries(), range.hasMore(), range.observedAt(), fingerprint);
    }

    private static TestSuiteStabilityCrossRetentionTrendAnalysisRequest request(
            String expectedHeadFingerprint) {
        long afterSequence = expectedHeadFingerprint == null
                || expectedHeadFingerprint.isBlank() ? 0 : 1;
        return new TestSuiteStabilityCrossRetentionTrendAnalysisRequest(
                TestSuiteStabilityCrossRetentionTrendAnalysisRequest.SCHEMA_VERSION,
                TestSuiteStabilityProtocolFixtures.SUITE_REF, afterSequence, 2, 10,
                expectedHeadFingerprint);
    }

    private static StoredTestSuite storedSuite() {
        TestSuite suite = new TestSuite("", "suite-a", 3,
                TestSuiteStabilityProtocolFixtures.TARGET, "INTERNAL", List.of(),
                TestSuite.CoveragePolicy.defaults(), TestSuite.PromotionPolicy.defaults(),
                Map.of());
        return new StoredTestSuite("", "tenant-a", "test", "suite-a", 3,
                TestSuiteStabilityProtocolFixtures.SUITE_FINGERPRINT,
                suite, Instant.parse("2026-07-18T23:00:00Z"), "actor-a");
    }

    private static IntegrationRequestContext identity() {
        return identity("TEST_EXECUTION", "test", "INTERNAL");
    }

    private static IntegrationRequestContext identity(
            String purpose,
            String environment,
            String clearance) {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", environment, "sg",
                "SERVICE", "actor-a", "", purpose, "corr-a",
                Set.of(), clearance, "");
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(IntegrationProblemException.class,
                        failure -> assertThat(failure.problem().code()).isEqualTo(code));
    }
}
