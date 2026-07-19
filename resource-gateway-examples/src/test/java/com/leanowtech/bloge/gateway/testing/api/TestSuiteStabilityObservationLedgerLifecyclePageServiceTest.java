package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityObservationLifecycleProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationFloorRetirementAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationLedgerLifecycleAttestationService;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSuiteStabilityObservationLedgerLifecyclePageServiceTest {
    private ObjectMapper mapper;
    private TestSuiteRegistryService suites;
    private TestSuiteStabilityRunRepository repository;
    private InMemoryVisualEvidenceSigner signer;
    private TestSuiteStabilityObservationLifecycleProtocolFixtures.Fixture fixture;
    private TestSuiteStabilityObservationFloorRetirementAttestationService
            retirementAttestations;
    private TestSuiteStabilityObservationLedgerLifecycleAttestationService
            lifecycleAttestations;
    private TestSuiteStabilityObservationLedgerLifecyclePageService service;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        suites = mock(TestSuiteRegistryService.class);
        repository = mock(TestSuiteStabilityRunRepository.class);
        signer = new InMemoryVisualEvidenceSigner();
        fixture = TestSuiteStabilityObservationLifecycleProtocolFixtures.page(mapper, signer);
        retirementAttestations = fixture.retirementAttestations();
        lifecycleAttestations =
                new TestSuiteStabilityObservationLedgerLifecycleAttestationService(
                        mapper, signer);
        service = new TestSuiteStabilityObservationLedgerLifecyclePageService(
                suites, repository, mapper, retirementAttestations, lifecycleAttestations);
        when(suites.find(eq("suite-a"), eq(3L), any())).thenReturn(storedSuite());
        when(repository.observationLedgerLifecyclePage(
                "tenant-a", "test", fixture.page().request()))
                .thenReturn(Optional.of(fixture.page()));
    }

    @Test
    void verifiesEveryRetirementAndReturnsASignedSnapshotClosure() {
        var response = service.read("suite-a", fixture.page().request(), identity());

        assertThat(response.page()).isEqualTo(fixture.page());
        assertThat(response.pageFingerprint()).isEqualTo(fixture.page().pageFingerprint());
        assertThat(response.attestation().retirementRefs()).hasSize(1);
        assertThat(lifecycleAttestations.verify(
                response.page(), response.attestation()))
                .isEqualTo(TestSuiteStabilityObservationLedgerLifecycleAttestationService
                        .Verification.VERIFIED);
    }

    @Test
    void staleContinuationPinsAreRejectedBeforeSigning() {
        var request = new TestSuiteStabilityObservationLedgerLifecyclePageRequest(
                TestSuiteStabilityObservationLedgerLifecyclePageRequest.SCHEMA_VERSION,
                TestSuiteStabilityProtocolFixtures.SUITE_REF, 1, 10,
                TestSuiteStabilityProtocolFixtures.fingerprint('f'),
                fixture.page().head().headFingerprint());
        when(repository.observationLedgerLifecyclePage("tenant-a", "test", request))
                .thenReturn(Optional.of(fixture.page()));

        assertCode(() -> service.read("suite-a", request, identity()),
                "RG.TEST.STABILITY_LIFECYCLE_SNAPSHOT_CHANGED");
    }

    @Test
    void invalidPageAndMissingOrInvalidCursorFailClosed() {
        var page = fixture.page();
        var forged = new TestSuiteStabilityObservationLedgerLifecyclePage(
                page.schemaVersion(), page.requestFingerprint(), page.request(),
                page.scopeFingerprint(), page.startingFloor(), page.retirements(),
                page.terminalFloor(), page.currentFloor(), page.head(), page.hasMore(),
                page.observedAt(), TestSuiteStabilityProtocolFixtures.fingerprint('f'));
        when(repository.observationLedgerLifecyclePage(
                "tenant-a", "test", page.request())).thenReturn(Optional.of(forged));
        assertCode(() -> service.read("suite-a", page.request(), identity()),
                "RG.TEST.STABILITY_LIFECYCLE_PAGE_INVALID");

        when(repository.observationLedgerLifecyclePage(
                "tenant-a", "test", page.request())).thenReturn(Optional.empty());
        assertCode(() -> service.read("suite-a", page.request(), identity()),
                "RG.TEST.STABILITY_OBSERVATION_LEDGER_NOT_FOUND");

        when(repository.observationLedgerLifecyclePage(
                "tenant-a", "test", page.request()))
                .thenThrow(new IllegalArgumentException("cursor"));
        assertCode(() -> service.read("suite-a", page.request(), identity()),
                "RG.TEST.STABILITY_LIFECYCLE_CURSOR_INVALID");
    }

    @Test
    void retirementOrLifecycleSignerOutageCannotProducePublicEvidence() {
        var unavailableRetirementVerifier =
                new TestSuiteStabilityObservationFloorRetirementAttestationService(
                        mapper, VisualEvidenceSigner.unavailable());
        var retirementUnavailable =
                new TestSuiteStabilityObservationLedgerLifecyclePageService(
                        suites, repository, mapper, unavailableRetirementVerifier,
                        lifecycleAttestations);
        assertCode(() -> retirementUnavailable.read(
                        "suite-a", fixture.page().request(), identity()),
                "RG.TEST.STABILITY_RETIREMENT_VERIFICATION_UNAVAILABLE");

        var lifecycleUnavailable =
                new TestSuiteStabilityObservationLedgerLifecyclePageService(
                        suites, repository, mapper, retirementAttestations,
                        new TestSuiteStabilityObservationLedgerLifecycleAttestationService(
                                mapper, VisualEvidenceSigner.unavailable()));
        assertCode(() -> lifecycleUnavailable.read(
                        "suite-a", fixture.page().request(), identity()),
                "RG.TEST.STABILITY_LIFECYCLE_ATTESTATION_UNAVAILABLE");
    }

    @Test
    void enforcesPathPurposeEnvironmentAndClearanceBeforeLedgerUse() {
        assertCode(() -> service.read("other", fixture.page().request(), identity()),
                "RG.TEST.STABILITY_LIFECYCLE_REQUEST_INVALID");
        assertCode(() -> service.read("suite-a", fixture.page().request(),
                        identity("TEST_SUITE_READ", "test", "INTERNAL")),
                "RG.TEST.STABILITY_LIFECYCLE_PURPOSE_FORBIDDEN");
        assertCode(() -> service.read("suite-a", fixture.page().request(),
                        identity("TEST_EXECUTION", "production", "INTERNAL")),
                "RG.TEST.ENVIRONMENT_FORBIDDEN");
        assertCode(() -> service.read("suite-a", fixture.page().request(),
                        identity("TEST_EXECUTION", "test", "PUBLIC")),
                "RG.TEST.SUITE_CLEARANCE_FORBIDDEN");
    }

    @Test
    void invalidPathStopsBeforeLifecycleRepositoryAccess() {
        assertCode(() -> service.read("other", fixture.page().request(), identity()),
                "RG.TEST.STABILITY_LIFECYCLE_REQUEST_INVALID");
        verify(repository, never()).observationLedgerLifecyclePage(any(), any(), any());
    }

    @Test
    void requestRequiresBlankFirstPagePinsAndCompleteContinuationPins() {
        assertThatThrownBy(() -> new TestSuiteStabilityObservationLedgerLifecyclePageRequest(
                TestSuiteStabilityObservationLedgerLifecyclePageRequest.SCHEMA_VERSION,
                TestSuiteStabilityProtocolFixtures.SUITE_REF, 0, 1,
                TestSuiteStabilityProtocolFixtures.fingerprint('a'), ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestSuiteStabilityObservationLedgerLifecyclePageRequest(
                TestSuiteStabilityObservationLedgerLifecyclePageRequest.SCHEMA_VERSION,
                TestSuiteStabilityProtocolFixtures.SUITE_REF, 1, 1,
                TestSuiteStabilityProtocolFixtures.fingerprint('a'), ""))
                .isInstanceOf(IllegalArgumentException.class);
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
