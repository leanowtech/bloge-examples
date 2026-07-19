package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityTrendProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityTrendProtocolFixtures.CaseMode;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityTrendEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityTrendAttestationService;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSuiteStabilityTrendAnalysisServiceTest {
    private static final Instant FROM = Instant.parse("2026-07-18T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-07-19T00:00:00Z");

    private ObjectMapper mapper;
    private TestSuiteRegistryService suites;
    private TestSuiteStabilityRunRepository repository;
    private InMemoryVisualEvidenceSigner signer;
    private TestSuiteStabilityAttestationService sourceAttestations;
    private TestSuiteStabilityTrendAttestationService trendAttestations;
    private TestSuiteStabilityTrendAnalysisService service;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        suites = mock(TestSuiteRegistryService.class);
        repository = mock(TestSuiteStabilityRunRepository.class);
        signer = new InMemoryVisualEvidenceSigner();
        sourceAttestations = new TestSuiteStabilityAttestationService(mapper, signer);
        trendAttestations = new TestSuiteStabilityTrendAttestationService(mapper, signer);
        service = new TestSuiteStabilityTrendAnalysisService(
                suites, repository, mapper, sourceAttestations, trendAttestations,
                Duration.ofDays(30));
        when(suites.find(org.mockito.ArgumentMatchers.eq("suite-a"),
                org.mockito.ArgumentMatchers.eq(3L),
                org.mockito.ArgumentMatchers.any())).thenReturn(storedSuite());
        when(repository.currentTime()).thenReturn(TO.plusSeconds(60));
    }

    @Test
    void verifiesEverySourceAndReturnsSignedStableTrendEvidence() {
        List<TestSuiteStabilityRunRecord> sources = List.of(
                source('1', FROM.plusSeconds(100)),
                source('2', FROM.plusSeconds(200)));
        when(repository.history("tenant-a", "test",
                TestSuiteStabilityProtocolFixtures.SUITE_REF, FROM, TO, 10))
                .thenReturn(new TestSuiteStabilityHistoryWindow(sources, 0, false, TO));

        TestSuiteStabilityTrendAnalysisResponse response = service.analyze(
                "suite-a", request(), identity());

        assertThat(response.evidence().status()).isEqualTo(
                TestSuiteStabilityTrendEvidence.Status.STABLE_PASS);
        assertThat(response.attestation().terminallyVerifiable()).isTrue();
        assertThat(trendAttestations.verify(response.evidence(), response.attestation()))
                .isEqualTo(TestSuiteStabilityTrendAttestationService.Verification.VERIFIED);
        verify(repository).history("tenant-a", "test",
                TestSuiteStabilityProtocolFixtures.SUITE_REF, FROM, TO, 10);
    }

    @Test
    void retentionGapProducesSignedInconclusiveEvidenceInsteadOfSubsetConfidence() {
        List<TestSuiteStabilityRunRecord> sources = List.of(source('2', FROM.plusSeconds(200)));
        when(repository.history("tenant-a", "test",
                TestSuiteStabilityProtocolFixtures.SUITE_REF, FROM, TO, 10))
                .thenReturn(new TestSuiteStabilityHistoryWindow(sources, 1, false, TO));

        TestSuiteStabilityTrendAnalysisResponse response = service.analyze(
                "suite-a", request(), identity());

        assertThat(response.evidence().status()).isEqualTo(
                TestSuiteStabilityTrendEvidence.Status.INCONCLUSIVE);
        assertThat(response.evidence().diagnostics())
                .contains("SOURCE_RETENTION_GAP", "MINIMUM_RUNS_NOT_MET");
        assertThat(response.attestation().terminallyVerifiable()).isTrue();
    }

    @Test
    void rejectsFutureAndOversizedWindowsBeforeHistoryAccess() {
        TestSuiteStabilityTrendAnalysisRequest future = new TestSuiteStabilityTrendAnalysisRequest(
                "", TestSuiteStabilityProtocolFixtures.SUITE_REF,
                FROM, TO.plusSeconds(120), 2, 10);
        TestSuiteStabilityTrendAnalysisRequest oversized =
                new TestSuiteStabilityTrendAnalysisRequest(
                        "", TestSuiteStabilityProtocolFixtures.SUITE_REF,
                        FROM.minus(Duration.ofDays(31)), TO, 2, 10);

        assertCode(() -> service.analyze("suite-a", future, identity()),
                "RG.TEST.STABILITY_TREND_FUTURE_WINDOW");
        assertCode(() -> service.analyze("suite-a", oversized, identity()),
                "RG.TEST.STABILITY_TREND_WINDOW_TOO_LARGE");
        verify(repository, never()).history(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void rejectsPathFingerprintPurposeEnvironmentAndClearanceDrift() {
        TestSuiteStabilityTrendAnalysisRequest forged =
                new TestSuiteStabilityTrendAnalysisRequest("",
                        new TestSuiteExecutionRequest.SuiteRef(
                                "suite-a", 3,
                                TestSuiteStabilityProtocolFixtures.fingerprint('9')),
                        FROM, TO, 2, 10);
        assertCode(() -> service.analyze("other", request(), identity()),
                "RG.TEST.STABILITY_TREND_REQUEST_INVALID");
        assertCode(() -> service.analyze("suite-a", forged, identity()),
                "RG.TEST.STABILITY_TREND_SUITE_FINGERPRINT_CONFLICT");
        assertCode(() -> service.analyze("suite-a", request(),
                        identity("TEST_SUITE_READ", "test", "INTERNAL")),
                "RG.TEST.STABILITY_TREND_PURPOSE_FORBIDDEN");
        assertCode(() -> service.analyze("suite-a", request(),
                        identity("TEST_EXECUTION", "production", "INTERNAL")),
                "RG.TEST.ENVIRONMENT_FORBIDDEN");
        assertCode(() -> service.analyze("suite-a", request(),
                        identity("TEST_EXECUTION", "test", "PUBLIC")),
                "RG.TEST.SUITE_CLEARANCE_FORBIDDEN");
    }

    @Test
    void rejectsTamperedSourceAndUnavailableTrendSigner() {
        TestSuiteStabilityRunRecord valid = source('1', FROM.plusSeconds(100));
        TestSuiteStabilityRunRecord tampered = new TestSuiteStabilityRunRecord(
                valid.stabilityRunId(), valid.clientRequestId(), valid.requestFingerprint(),
                valid.tenantId(), valid.organizationId(), valid.projectId(),
                valid.environmentId(), valid.actorId(), valid.classification(),
                TestSuiteStabilityProtocolFixtures.fingerprint('9'), valid.evidence(),
                valid.attestation(), valid.createdAt(), valid.expiresAt());
        when(repository.history("tenant-a", "test",
                TestSuiteStabilityProtocolFixtures.SUITE_REF, FROM, TO, 10))
                .thenReturn(new TestSuiteStabilityHistoryWindow(
                        List.of(tampered, source('2', FROM.plusSeconds(200))), 0, false, TO));

        assertCode(() -> service.analyze("suite-a", request(), identity()),
                "RG.TEST.STABILITY_TREND_SOURCE_INVALID");

        TestSuiteStabilityTrendAnalysisService unsigned =
                new TestSuiteStabilityTrendAnalysisService(
                        suites, repository, mapper, sourceAttestations,
                        new TestSuiteStabilityTrendAttestationService(
                                mapper, VisualEvidenceSigner.unavailable()),
                        Duration.ofDays(30));
        List<TestSuiteStabilityRunRecord> validSources = List.of(
                valid, source('2', FROM.plusSeconds(200)));
        when(repository.history("tenant-a", "test",
                TestSuiteStabilityProtocolFixtures.SUITE_REF, FROM, TO, 10))
                .thenReturn(new TestSuiteStabilityHistoryWindow(validSources, 0, false, TO));

        assertCode(() -> unsigned.analyze("suite-a", request(), identity()),
                "RG.TEST.STABILITY_TREND_ATTESTATION_UNAVAILABLE");
    }

    private TestSuiteStabilityRunRecord source(char id, Instant createdAt) {
        return TestSuiteStabilityTrendProtocolFixtures.record(
                mapper, sourceAttestations, id, createdAt, TO.plusSeconds(3_600),
                TestSuiteStabilityProtocolFixtures.PLAN_FINGERPRINT,
                CaseMode.STABLE, CaseMode.STABLE, '1', '2');
    }

    private static TestSuiteStabilityTrendAnalysisRequest request() {
        return new TestSuiteStabilityTrendAnalysisRequest("",
                TestSuiteStabilityProtocolFixtures.SUITE_REF, FROM, TO, 2, 10);
    }

    private static StoredTestSuite storedSuite() {
        TestSuite suite = new TestSuite("", "suite-a", 3,
                TestSuiteStabilityProtocolFixtures.TARGET, "INTERNAL", List.of(),
                TestSuite.CoveragePolicy.defaults(), TestSuite.PromotionPolicy.defaults(), Map.of());
        return new StoredTestSuite("", "tenant-a", "test", "suite-a", 3,
                TestSuiteStabilityProtocolFixtures.SUITE_FINGERPRINT,
                suite, FROM.minusSeconds(60), "actor-a");
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
