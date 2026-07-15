package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRegistryService;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRunRecord;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRunRepository;
import com.leanowtech.bloge.gateway.testing.domain.SemanticCoveragePolicy;
import com.leanowtech.bloge.gateway.testing.domain.SemanticCoverageVerdict;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV2;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV2;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteProtocolCodec;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteRunAttestationService;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SemanticCorrectnessWorkbookProjectionServiceTest {
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final Instant STARTED = Instant.parse("2026-07-16T01:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final IntegrationRequestContext identity = new IntegrationRequestContext(
            "tenant-a", "org-a", "project-a", "test", "local", "WORKLOAD", "aneke", "",
            "WORKBOOK_SYNC", "corr-semantic-workbook", Set.of("governance"), "RESTRICTED", "");

    @Test
    void projectsPayloadFreeVerifiedSemanticFactsAndMatchesSchemaFields() throws Exception {
        TestSuiteV2 suite = semanticSuite();
        StoredTestSuite stored = stored(suite);
        InMemoryVisualEvidenceSigner signer = new InMemoryVisualEvidenceSigner();
        TestSuiteRunAttestationService attestationService =
                new TestSuiteRunAttestationService(mapper, signer);
        TestSuiteRunRecord record = record(semanticEvidence(stored, true), attestationService);
        SemanticCorrectnessWorkbookProjectionService service = service(stored, List.of(record),
                attestationService);

        SemanticCorrectnessWorkbookBundle workbook = service.project("suite-risk", 2, identity);

        assertThat(workbook.fingerprintVerified()).isTrue();
        assertThat(workbook.payloadPolicy()).isEqualTo("OMITTED");
        assertThat(workbook.suite().suiteSchemaVersion()).isEqualTo(TestSuiteV2.SCHEMA_VERSION);
        assertThat(workbook.suite().cases()).singleElement().satisfies(row -> {
            assertThat(row.caseId()).isEqualTo("golden");
            assertThat(mapper.valueToTree(row).has("input")).isFalse();
        });
        assertThat(workbook.suite().semanticCoveragePolicy().requirements())
                .extracting(SemanticCoveragePolicy.Requirement::requirementId)
                .containsExactly("timeout");
        assertThat(workbook.evidence()).singleElement().satisfies(projected -> {
            assertThat(projected.evidenceSchemaVersion())
                    .isEqualTo(TestSuiteRunEvidenceV2.SCHEMA_VERSION);
            assertThat(projected.semanticCoverage().status())
                    .isEqualTo(SemanticCoverageVerdict.Status.SATISFIED);
            assertThat(projected.attestation().schemaVersion())
                    .isEqualTo(TestSuiteRunAttestation.SCHEMA_VERSION_V2);
            assertThat(projected.endpoint()).endsWith("/evidence-bundle");
            assertThat(projected.endpoint()).contains("suite-run%2F42");
            assertThat(mapper.valueToTree(projected.caseResults().getFirst()).has("diagnostic"))
                    .isFalse();
        });
        assertThat(workbook.manifest().projectionStatus()).isEqualTo("READY");
        assertThat(workbook.manifest().gateReady()).isTrue();
        assertThat(workbook.manifest().semanticRequirementCount()).isOne();

        JsonNode schema = mapper.readTree(schemaPath().toFile());
        assertFields(mapper.valueToTree(workbook), schema.path("properties"));
        assertFields(mapper.valueToTree(workbook.suite()), schema.at("/$defs/suite/properties"));
        assertFields(mapper.valueToTree(workbook.evidence().getFirst()),
                schema.at("/$defs/evidence/properties"));
        assertFields(mapper.valueToTree(workbook.manifest()), schema.at("/$defs/manifest/properties"));
    }

    @Test
    void rejectsStructuralSuiteInsteadOfTreatingSemanticCoverageAsEmpty() {
        TestSuite structural = new TestSuite("", "suite-risk", 2,
                new TestSuite.Target("GRAPH", "risk", FINGERPRINT), "INTERNAL", List.of(),
                TestSuite.CoveragePolicy.defaults(), TestSuite.PromotionPolicy.defaults(), Map.of());
        StoredTestSuite stored = stored(structural);
        TestSuiteRunAttestationService attestations = new TestSuiteRunAttestationService(
                mapper, new InMemoryVisualEvidenceSigner());

        assertThatThrownBy(() -> service(stored, List.of(), attestations)
                .project("suite-risk", 2, identity))
                .isInstanceOfSatisfying(
                        SemanticCorrectnessWorkbookProjectionService.ProjectionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("SEMANTIC_SUITE_GENERATION_REQUIRED"));
    }

    @Test
    void rejectsMixedSuiteEvidenceAndAttestationGeneration() {
        TestSuiteV2 suite = semanticSuite();
        StoredTestSuite stored = stored(suite);
        TestSuiteRunAttestationService attestations = new TestSuiteRunAttestationService(
                mapper, new InMemoryVisualEvidenceSigner());
        TestSuiteRunEvidence structural = structuralEvidence(stored);
        TestSuiteRunRecord record = record(structural, attestations);

        assertThatThrownBy(() -> service(stored, List.of(record), attestations)
                .project("suite-risk", 2, identity))
                .isInstanceOfSatisfying(
                        SemanticCorrectnessWorkbookProjectionService.ProjectionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("SUITE_EVIDENCE_GENERATION_MISMATCH"));
    }

    @Test
    void rejectsMissingRecordFingerprintAsAStableGenerationMismatch() {
        TestSuiteV2 suite = semanticSuite();
        StoredTestSuite stored = stored(suite);
        TestSuiteRunAttestationService attestations = new TestSuiteRunAttestationService(
                mapper, new InMemoryVisualEvidenceSigner());
        TestSuiteRunRecord valid = record(semanticEvidence(stored, true), attestations);
        TestSuiteRunRecord missingFingerprint = new TestSuiteRunRecord(
                valid.suiteRunId(), valid.clientRequestId(), valid.requestFingerprint(),
                valid.tenantId(), valid.organizationId(), valid.projectId(), valid.environmentId(),
                valid.actorId(), valid.classification(), null, valid.evidence(), valid.attestation(),
                valid.createdAt(), valid.expiresAt());

        assertThatThrownBy(() -> service(stored, List.of(missingFingerprint), attestations)
                .project("suite-risk", 2, identity))
                .isInstanceOfSatisfying(
                        SemanticCorrectnessWorkbookProjectionService.ProjectionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("SUITE_EVIDENCE_GENERATION_MISMATCH"));
    }

    @Test
    void distinguishesVerificationAuthorityOutageFromMissingEvidence() {
        TestSuiteV2 suite = semanticSuite();
        StoredTestSuite stored = stored(suite);
        TestSuiteRunAttestationService unavailable = new TestSuiteRunAttestationService(
                mapper, VisualEvidenceSigner.unavailable());
        TestSuiteRunEvidenceV2 incomplete = semanticEvidence(stored, false);
        TestSuiteRunRecord record = record(incomplete, unavailable);

        SemanticCorrectnessWorkbookBundle workbook = service(stored, List.of(record), unavailable)
                .project("suite-risk", 2, identity);

        assertThat(workbook.evidence()).isEmpty();
        assertThat(workbook.manifest().projectionStatus()).isEqualTo("VERIFICATION_UNAVAILABLE");
        assertThat(workbook.manifest().candidateEvidenceCount()).isOne();
        assertThat(workbook.manifest().unavailableEvidenceCount()).isOne();
        assertThat(workbook.manifest().gateReady()).isFalse();
        assertThat(workbook.fingerprintVerified()).isTrue();
    }

    @Test
    void bindsEvidenceTruncationAndCandidateCardinalityIntoManifestFingerprint() {
        TestSuiteV2 suite = semanticSuite();
        StoredTestSuite stored = stored(suite);
        TestSuiteRunAttestationService attestations = new TestSuiteRunAttestationService(
                mapper, new InMemoryVisualEvidenceSigner());
        TestSuiteRunRecord record = record(semanticEvidence(stored, true), attestations);
        List<TestSuiteRunRecord> records = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            records.add(record);
        }

        SemanticCorrectnessWorkbookBundle workbook = service(stored, records, attestations)
                .project("suite-risk", 2, identity);

        assertThat(workbook.evidence()).hasSize(100);
        assertThat(workbook.manifest().candidateEvidenceCount()).isEqualTo(101);
        assertThat(workbook.manifest().verifiedEvidenceCount()).isEqualTo(100);
        assertThat(workbook.manifest().evidenceTruncated()).isTrue();
        assertThat(workbook.fingerprintVerified()).isTrue();
    }

    @Test
    void rejectsRepositoryThatIgnoresTheBoundedHistoryContract() {
        TestSuiteV2 suite = semanticSuite();
        StoredTestSuite stored = stored(suite);
        TestSuiteRunAttestationService attestations = new TestSuiteRunAttestationService(
                mapper, new InMemoryVisualEvidenceSigner());
        TestSuiteRunRecord record = record(semanticEvidence(stored, true), attestations);
        List<TestSuiteRunRecord> records = new ArrayList<>();
        for (int index = 0; index < 102; index++) {
            records.add(record);
        }

        assertThatThrownBy(() -> service(stored, records, attestations)
                .project("suite-risk", 2, identity))
                .isInstanceOf(SemanticCorrectnessWorkbookProjectionService.StoreUnavailableException.class)
                .hasMessageContaining("bounded query contract");
    }

    private SemanticCorrectnessWorkbookProjectionService service(
            StoredTestSuite stored, List<TestSuiteRunRecord> records,
            TestSuiteRunAttestationService attestations) {
        TestSuiteRegistryService registry = mock(TestSuiteRegistryService.class);
        when(registry.find(eq(stored.suiteId()), eq(stored.revision()), any()))
                .thenReturn(stored);
        TestSuiteRunRepository runs = mock(TestSuiteRunRepository.class);
        when(runs.findTerminalBySuite(eq(stored.tenantId()), eq(stored.environmentId()),
                eq(stored.suiteId()), eq(stored.revision()), eq(101))).thenReturn(records);
        return new SemanticCorrectnessWorkbookProjectionService(registry, runs, attestations, mapper);
    }

    private StoredTestSuite stored(com.leanowtech.bloge.gateway.testing.domain.TestSuiteProtocol suite) {
        String fingerprint = new TestSuiteProtocolCodec(mapper).fingerprint(suite);
        return new StoredTestSuite("", "tenant-a", "test", suite.suiteId(), suite.revision(),
                fingerprint, suite, STARTED.minus(1, ChronoUnit.HOURS), "author");
    }

    private TestSuiteV2 semanticSuite() {
        TestSuite.FixtureBundleRef fixture = new TestSuite.FixtureBundleRef("fixture-risk", 3, FINGERPRINT);
        TestSuite.TestCase testCase = new TestSuite.TestCase("golden", TestSuite.CaseType.GOLDEN,
                Map.of("apiToken", "must-not-be-exported"), fixture, List.of("release"),
                Map.of("private", "metadata"));
        SemanticCoveragePolicy.Requirement timeout = new SemanticCoveragePolicy.SiteRequirement(
                "timeout", SemanticCoveragePolicy.Kind.TIMEOUT, "/root/risk#PRIMARY", "UPSTREAM_TIMEOUT");
        return new TestSuiteV2("", "suite-risk", 2,
                new TestSuite.Target("GRAPH", "risk", FINGERPRINT), "RESTRICTED", List.of(testCase),
                new TestSuite.CoveragePolicy(1, List.of(TestSuite.CaseType.GOLDEN),
                        List.of("/root/risk#PRIMARY"), List.of(), 1, true),
                new SemanticCoveragePolicy(List.of(timeout)),
                new TestSuite.PromotionPolicy(true, 1, true), Map.of("owner", "risk-team"));
    }

    private TestSuiteRunEvidenceV2 semanticEvidence(StoredTestSuite stored, boolean passed) {
        TestSuiteV2 suite = (TestSuiteV2) stored.suite();
        TestSuiteRunEvidence.CaseResult result = caseResult(suite,
                passed ? TestSuiteRunEvidence.CaseStatus.PASSED
                        : TestSuiteRunEvidence.CaseStatus.EVIDENCE_INCOMPLETE);
        SemanticCoverageVerdict semantic = passed
                ? new SemanticCoverageVerdict(SemanticCoverageVerdict.Status.SATISFIED,
                        suite.semanticCoveragePolicy().requirements(),
                        List.of(new SemanticCoverageVerdict.Observation("timeout",
                                SemanticCoveragePolicy.Kind.TIMEOUT, List.of("golden"))),
                        List.of(), List.of())
                : new SemanticCoverageVerdict(SemanticCoverageVerdict.Status.INCOMPLETE,
                        suite.semanticCoveragePolicy().requirements(), List.of(), List.of(),
                        List.of(new SemanticCoverageVerdict.Unavailable(
                                "timeout", "SIGNER_UNAVAILABLE")));
        TestSuiteRunEvidence.CoverageVerdict coverage = new TestSuiteRunEvidence.CoverageVerdict(
                passed ? TestSuiteRunEvidence.CoverageStatus.SATISFIED
                        : TestSuiteRunEvidence.CoverageStatus.INCOMPLETE,
                1, passed ? 1 : 0, List.of(TestSuite.CaseType.GOLDEN),
                passed ? List.of(TestSuite.CaseType.GOLDEN) : List.of(),
                passed ? List.of() : List.of(TestSuite.CaseType.GOLDEN),
                List.of("/root/risk#PRIMARY"), passed ? List.of("/root/risk#PRIMARY") : List.of(),
                passed ? List.of() : List.of("/root/risk#PRIMARY"), List.of(), List.of(), List.of(),
                1, List.of(), List.of(), passed);
        TestSuiteRunEvidence.PromotionVerdict promotion = new TestSuiteRunEvidence.PromotionVerdict(
                passed ? TestSuiteRunEvidence.PromotionStatus.ELIGIBLE
                        : TestSuiteRunEvidence.PromotionStatus.BLOCKED,
                passed ? List.of() : List.of("EVIDENCE_INCOMPLETE"), passed, passed ? 1 : 0,
                1, true, passed, passed);
        return new TestSuiteRunEvidenceV2("", "suite-run/42", "request-semantic",
                passed ? TestSuiteRunEvidence.Status.PASSED
                        : TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE,
                "TEST_EXECUTION", suiteRef(stored), suite.target(), STARTED,
                STARTED.plusSeconds(5), List.of(result), coverage, semantic, promotion,
                List.of(), Map.of("tenantId", "tenant-a"));
    }

    private TestSuiteRunEvidence structuralEvidence(StoredTestSuite stored) {
        TestSuiteV2 suite = (TestSuiteV2) stored.suite();
        return new TestSuiteRunEvidence("", "suite-run-structural", "request-structural",
                TestSuiteRunEvidence.Status.PASSED, "TEST_EXECUTION", suiteRef(stored), suite.target(),
                STARTED, STARTED.plusSeconds(5), List.of(caseResult(suite,
                TestSuiteRunEvidence.CaseStatus.PASSED)),
                TestSuiteRunEvidence.CoverageVerdict.notEvaluated(),
                TestSuiteRunEvidence.PromotionVerdict.notEvaluated(), List.of(), Map.of());
    }

    private static TestSuiteRunEvidence.CaseResult caseResult(
            TestSuiteV2 suite, TestSuiteRunEvidence.CaseStatus status) {
        boolean passed = status == TestSuiteRunEvidence.CaseStatus.PASSED;
        return new TestSuiteRunEvidence.CaseResult("golden", TestSuite.CaseType.GOLDEN,
                suite.cases().getFirst().fixtureBundleRef(), status, passed ? "child-run" : "",
                passed ? TestRunEvidence.Status.PASSED : null,
                passed ? TestRunEvidence.EvidenceClass.CERTIFIABLE : null,
                passed ? 1 : 0, passed ? 1 : 0, passed ? "" : "EVIDENCE_INCOMPLETE", "");
    }

    private TestSuiteRunRecord record(
            com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceProtocol evidence,
            TestSuiteRunAttestationService attestations) {
        TestSuiteRunAttestationService.SealResult sealed = attestations.seal(evidence,
                FINGERPRINT, evidence.status() == TestSuiteRunEvidence.Status.PASSED
                        ? List.of(new TestSuiteRunAttestation.ChildEvidenceRef(
                        "golden", "child-run", FINGERPRINT)) : List.of(),
                TestSuiteRunAttestation.Scope.TERMINAL);
        return new TestSuiteRunRecord(evidence.suiteRunId(), evidence.clientRequestId(), FINGERPRINT,
                "tenant-a", "org-a", "project-a", "test", "aneke", "RESTRICTED",
                sealed.attestation().aggregateEvidenceFingerprint(), evidence, sealed.attestation(),
                STARTED, STARTED.plus(30, ChronoUnit.DAYS));
    }

    private static TestSuiteExecutionRequest.SuiteRef suiteRef(StoredTestSuite stored) {
        return new TestSuiteExecutionRequest.SuiteRef(
                stored.suiteId(), stored.revision(), stored.fingerprint());
    }

    private Path schemaPath() {
        Path path = Path.of("..", "docs", "schemas", "tool-studio-resource-gateway",
                "semantic-correctness-workbook-bundle-v1.schema.json");
        return Files.exists(path) ? path : Path.of("docs", "schemas", "tool-studio-resource-gateway",
                "semantic-correctness-workbook-bundle-v1.schema.json");
    }

    private static void assertFields(JsonNode value, JsonNode schemaProperties) {
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        Set<String> expected = new HashSet<>();
        schemaProperties.fieldNames().forEachRemaining(expected::add);
        assertThat(actual).isEqualTo(expected);
    }
}
