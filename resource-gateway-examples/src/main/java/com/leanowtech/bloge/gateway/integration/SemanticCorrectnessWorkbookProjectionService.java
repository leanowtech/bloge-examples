package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRegistryService;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRunRecord;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRunRepository;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV2;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV2;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteProtocolCodec;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteRunAttestationService;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Projects exact semantic suite generations and verified terminal evidence for ANEKE.
 *
 * <p>The service exists only with the isolated test runtime. It deliberately resolves an exact
 * suite revision supplied by the caller instead of inferring a suite from a visual draft name.</p>
 */
@Service
@Profile("!production & (test | staging)")
public final class SemanticCorrectnessWorkbookProjectionService {
    private static final int MAX_EVIDENCE = 100;

    private final TestSuiteRegistryService suites;
    private final TestSuiteRunRepository runs;
    private final TestSuiteRunAttestationService attestations;
    private final TestSuiteProtocolCodec suiteCodec;

    /**
     * Creates the semantic workbook projection over governed suite and evidence stores.
     *
     * @param suites clearance-enforcing exact suite registry
     * @param runs tenant/environment-scoped suite-run history
     * @param attestations aggregate signature verifier
     * @param objectMapper canonical protocol mapper
     */
    public SemanticCorrectnessWorkbookProjectionService(
            TestSuiteRegistryService suites,
            TestSuiteRunRepository runs,
            TestSuiteRunAttestationService attestations,
            ObjectMapper objectMapper) {
        this.suites = Objects.requireNonNull(suites, "suites");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.attestations = Objects.requireNonNull(attestations, "attestations");
        this.suiteCodec = new TestSuiteProtocolCodec(Objects.requireNonNull(objectMapper, "objectMapper"));
    }

    /**
     * Projects one exact semantic suite revision without fixture or case input payloads.
     *
     * @param suiteId stable suite id
     * @param revision exact positive revision
     * @param identity verified Tool Studio workload scope
     * @return deterministic semantic workbook seed
     */
    public SemanticCorrectnessWorkbookBundle project(
            String suiteId, long revision, IntegrationRequestContext identity) {
        StoredTestSuite stored = suites.find(suiteId, revision, identity);
        if (!(stored.suite() instanceof TestSuiteV2 suite)
                || !TestSuiteV2.SCHEMA_VERSION.equals(suite.schemaVersion())) {
            throw new ProjectionException("SEMANTIC_SUITE_GENERATION_REQUIRED",
                    "Semantic workbook projection requires an exact bloge.testSuite.v2 revision.");
        }
        String actualSuiteFingerprint = suiteCodec.fingerprint(suite);
        if (!actualSuiteFingerprint.equals(stored.fingerprint())) {
            throw new ProjectionException("SUITE_FINGERPRINT_MISMATCH",
                    "Stored suite content no longer matches its immutable fingerprint.");
        }

        List<TestSuiteRunRecord> candidates;
        try {
            candidates = runs.findTerminalBySuite(identity.tenantId(), identity.environmentId(),
                    stored.suiteId(), stored.revision(), MAX_EVIDENCE + 1);
        } catch (RuntimeException unavailable) {
            throw new StoreUnavailableException("Semantic suite-run history is unavailable.", unavailable);
        }
        if (candidates == null || candidates.size() > MAX_EVIDENCE + 1) {
            throw new StoreUnavailableException(
                    "Semantic suite-run history violated the bounded query contract.", null);
        }
        boolean truncated = candidates.size() > MAX_EVIDENCE;
        List<SemanticCorrectnessWorkbookBundle.Evidence> evidence = new ArrayList<>();
        int unavailable = 0;
        for (int index = 0; index < candidates.size(); index++) {
            TestSuiteRunRecord record = candidates.get(index);
            TestSuiteRunEvidenceV2 aggregate = requireMatchingGeneration(stored, suite, record);
            TestSuiteRunAttestationService.Verification verification =
                    attestations.verify(aggregate, record.attestation());
            if (verification == TestSuiteRunAttestationService.Verification.INVALID
                    || verification == TestSuiteRunAttestationService.Verification.UNSIGNED) {
                throw new ProjectionException("TERMINAL_EVIDENCE_INVALID",
                        "Retained terminal suite evidence failed integrity verification.");
            }
            if (verification == TestSuiteRunAttestationService.Verification.UNAVAILABLE) {
                unavailable++;
                continue;
            }
            if (index < MAX_EVIDENCE) {
                evidence.add(projectEvidence(record, aggregate));
            }
        }

        SemanticCorrectnessWorkbookBundle.Suite projectedSuite = projectSuite(stored, suite);
        SemanticCorrectnessWorkbookBundle.Manifest manifest =
                SemanticCorrectnessWorkbookBundle.Manifest.from(
                        "OMITTED", projectedSuite, evidence, candidates.size(), unavailable, truncated);
        return new SemanticCorrectnessWorkbookBundle("", "OMITTED", projectedSuite,
                evidence, manifest);
    }

    private static SemanticCorrectnessWorkbookBundle.Suite projectSuite(
            StoredTestSuite stored, TestSuiteV2 suite) {
        List<SemanticCorrectnessWorkbookBundle.CaseRef> cases = suite.cases().stream()
                .map(row -> new SemanticCorrectnessWorkbookBundle.CaseRef(
                        row.caseId(), row.caseType(), row.fixtureBundleRef(), row.tags()))
                .toList();
        return new SemanticCorrectnessWorkbookBundle.Suite(suite.schemaVersion(), suite.suiteId(),
                suite.revision(), stored.fingerprint(), suite.target(), suite.classification(), cases,
                suite.coveragePolicy(), suite.semanticCoveragePolicy(), suite.promotionPolicy(),
                VisualBundleFingerprint.fromMaterial(Map.of("metadata", suite.metadata())));
    }

    private static TestSuiteRunEvidenceV2 requireMatchingGeneration(
            StoredTestSuite stored, TestSuiteV2 suite, TestSuiteRunRecord record) {
        if (record == null
                || !stored.tenantId().equals(record.tenantId())
                || !stored.environmentId().equals(record.environmentId())
                || !(record.evidence() instanceof TestSuiteRunEvidenceV2 aggregate)
                || !TestSuiteRunEvidenceV2.SCHEMA_VERSION.equals(aggregate.schemaVersion())
                || record.attestation() == null
                || !TestSuiteRunAttestation.SCHEMA_VERSION_V2.equals(record.attestation().schemaVersion())
                || record.attestation().scope() != TestSuiteRunAttestation.Scope.TERMINAL
                || aggregate.status() == TestSuiteRunEvidence.Status.RUNNING
                || aggregate.completedAt() == null
                || aggregate.suiteRef() == null
                || !stored.suiteId().equals(aggregate.suiteRef().suiteId())
                || stored.revision() != aggregate.suiteRef().revision()
                || !stored.fingerprint().equals(aggregate.suiteRef().fingerprint())
                || !Objects.equals(suite.target(), aggregate.target())
                || record.evidenceFingerprint() == null
                || !record.evidenceFingerprint().equals(
                        record.attestation().aggregateEvidenceFingerprint())) {
            throw new ProjectionException("SUITE_EVIDENCE_GENERATION_MISMATCH",
                    "Suite, aggregate evidence, and terminal attestation generations must match.");
        }
        return aggregate;
    }

    private static SemanticCorrectnessWorkbookBundle.Evidence projectEvidence(
            TestSuiteRunRecord record, TestSuiteRunEvidenceV2 aggregate) {
        TestSuiteRunAttestation attestation = record.attestation();
        SemanticCorrectnessWorkbookBundle.AttestationRef attestationRef =
                new SemanticCorrectnessWorkbookBundle.AttestationRef(attestation.schemaVersion(),
                        attestation.signedAt(), attestation.keyId(), attestation.algorithm(),
                        attestation.childEvidenceRefs());
        List<SemanticCorrectnessWorkbookBundle.CaseResultRef> caseResults =
                aggregate.caseResults().stream().map(row ->
                        new SemanticCorrectnessWorkbookBundle.CaseResultRef(
                                row.caseId(), row.caseType(), row.fixtureBundleRef(), row.status(),
                                row.runId(), row.evidenceStatus(), row.evidenceClass(),
                                row.assertionsEvaluated(), row.assertionsPassed(), row.diagnosticCode()))
                        .toList();
        return new SemanticCorrectnessWorkbookBundle.Evidence(aggregate.suiteRunId(),
                aggregate.schemaVersion(), record.evidenceFingerprint(), aggregate.status(),
                caseResults, aggregate.coverage(), aggregate.semanticCoverage(),
                aggregate.promotion(), attestationRef, aggregate.completedAt(),
                "/api/testing/suite-executions/"
                        + UriUtils.encodePathSegment(aggregate.suiteRunId(), StandardCharsets.UTF_8)
                        + "/evidence-bundle");
    }

    /** Stable fail-closed source-integrity problem. */
    static final class ProjectionException extends RuntimeException {
        private final String code;

        ProjectionException(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    /** Stable independent-store availability problem. */
    static final class StoreUnavailableException extends RuntimeException {
        StoreUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
