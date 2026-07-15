package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.testing.domain.SemanticCoveragePolicy;
import com.leanowtech.bloge.gateway.testing.domain.SemanticCoverageVerdict;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Payload-free ANEKE workbook seed for one immutable semantic test-suite generation.
 *
 * <p>This protocol starts at v1 independently from the historical draft workbook. It accepts only
 * {@code bloge.testSuite.v2} and verified terminal {@code bloge.testSuiteRunEvidence.v2} facts, so
 * a consumer cannot mistake structural v1 evidence for an empty semantic verdict.</p>
 *
 * @param schemaVersion exact semantic workbook protocol version
 * @param payloadPolicy fixed {@code OMITTED}; case inputs and fixture payloads never cross this API
 * @param suite exact immutable suite projection
 * @param evidence newest-first verified terminal aggregate projections
 * @param manifest deterministic source/trust summary and bundle fingerprint
 */
public record SemanticCorrectnessWorkbookBundle(
        String schemaVersion,
        String payloadPolicy,
        Suite suite,
        List<Evidence> evidence,
        Manifest manifest
) {
    /** Current semantic correctness workbook protocol version. */
    public static final String SCHEMA_VERSION =
            "toolStudio.resourceGateway.semanticCorrectnessWorkbookBundle.v1";

    /** Normalizes protocol defaults and derives the manifest when omitted. */
    public SemanticCorrectnessWorkbookBundle {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        payloadPolicy = defaulted(payloadPolicy, "OMITTED");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        manifest = manifest == null
                ? Manifest.from(payloadPolicy, suite, evidence, evidence.size(), 0, false) : manifest;
    }

    /** @return whether the manifest still commits the exact projected material */
    public boolean fingerprintVerified() {
        return manifest.bundleFingerprint().equals(Manifest.fingerprint(
                payloadPolicy, suite, evidence, manifest.candidateEvidenceCount(),
                manifest.unavailableEvidenceCount(),
                manifest.evidenceTruncated()));
    }

    /**
     * Exact suite definition without case input or fixture payload values.
     *
     * @param suiteSchemaVersion exact source suite generation
     * @param suiteId stable suite id
     * @param revision immutable suite revision
     * @param suiteFingerprint canonical source suite fingerprint
     * @param target frozen graph or operator target
     * @param classification maximum governed data classification
     * @param cases payload-free case and fixture identities
     * @param coveragePolicy structural coverage requirements
     * @param semanticCoveragePolicy typed orchestration-semantic requirements
     * @param promotionPolicy server-owned promotion policy
     * @param metadataFingerprint fingerprint of omitted suite metadata
     */
    public record Suite(
            String suiteSchemaVersion,
            String suiteId,
            long revision,
            String suiteFingerprint,
            TestSuite.Target target,
            String classification,
            List<CaseRef> cases,
            TestSuite.CoveragePolicy coveragePolicy,
            SemanticCoveragePolicy semanticCoveragePolicy,
            TestSuite.PromotionPolicy promotionPolicy,
            String metadataFingerprint
    ) {
        /** Freezes the ordered case projection. */
        public Suite {
            suiteSchemaVersion = normalized(suiteSchemaVersion);
            suiteId = normalized(suiteId);
            suiteFingerprint = normalized(suiteFingerprint);
            classification = normalized(classification);
            cases = cases == null ? List.of() : List.copyOf(cases);
            semanticCoveragePolicy = semanticCoveragePolicy == null
                    ? SemanticCoveragePolicy.empty() : semanticCoveragePolicy;
            metadataFingerprint = normalized(metadataFingerprint);
        }
    }

    /**
     * Payload-free suite case identity.
     *
     * @param caseId suite-local stable id
     * @param caseType governance intent
     * @param fixtureBundleRef exact governed fixture dependency
     * @param tags bounded query labels
     */
    public record CaseRef(String caseId, TestSuite.CaseType caseType,
                          TestSuite.FixtureBundleRef fixtureBundleRef, List<String> tags) {
        /** Freezes tags while preserving suite case order. */
        public CaseRef {
            caseId = normalized(caseId);
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    /**
     * Verified terminal aggregate copied from signed v2 evidence.
     *
     * @param suiteRunId durable aggregate run id
     * @param evidenceSchemaVersion exact semantic evidence generation
     * @param evidenceFingerprint canonical signed aggregate fingerprint
     * @param status aggregate execution result
     * @param caseResults payload-free case outcomes without free-text diagnostics
     * @param coverage structural coverage verdict
     * @param semanticCoverage typed semantic coverage verdict
     * @param promotion promotion eligibility verdict
     * @param attestation detached signature reference and ordered child closure
     * @param completedAt terminal completion time
     * @param endpoint portable evidence-bundle endpoint
     */
    public record Evidence(
            String suiteRunId,
            String evidenceSchemaVersion,
            String evidenceFingerprint,
            TestSuiteRunEvidence.Status status,
            List<CaseResultRef> caseResults,
            TestSuiteRunEvidence.CoverageVerdict coverage,
            SemanticCoverageVerdict semanticCoverage,
            TestSuiteRunEvidence.PromotionVerdict promotion,
            AttestationRef attestation,
            Instant completedAt,
            String endpoint
    ) {
        /** Freezes case results and normalizes machine identities. */
        public Evidence {
            suiteRunId = normalized(suiteRunId);
            evidenceSchemaVersion = normalized(evidenceSchemaVersion);
            evidenceFingerprint = normalized(evidenceFingerprint);
            caseResults = caseResults == null ? List.of() : List.copyOf(caseResults);
            endpoint = normalized(endpoint);
        }
    }

    /**
     * Payload-free case outcome copied from signed aggregate evidence.
     *
     * @param caseId suite-local case id
     * @param caseType declared case intent
     * @param fixtureBundleRef exact governed fixture identity
     * @param status terminal case status
     * @param runId child run id when produced
     * @param evidenceStatus child evidence status
     * @param evidenceClass child evidence trust class
     * @param assertionsEvaluated assertion count
     * @param assertionsPassed passing assertion count
     * @param diagnosticCode bounded stable diagnostic code; free text is deliberately omitted
     */
    public record CaseResultRef(
            String caseId,
            TestSuite.CaseType caseType,
            TestSuite.FixtureBundleRef fixtureBundleRef,
            TestSuiteRunEvidence.CaseStatus status,
            String runId,
            TestRunEvidence.Status evidenceStatus,
            TestRunEvidence.EvidenceClass evidenceClass,
            int assertionsEvaluated,
            int assertionsPassed,
            String diagnosticCode
    ) {
        /** Normalizes machine identities and validates counters. */
        public CaseResultRef {
            caseId = normalized(caseId);
            runId = normalized(runId);
            diagnosticCode = normalized(diagnosticCode);
            if (assertionsEvaluated < 0 || assertionsPassed < 0
                    || assertionsPassed > assertionsEvaluated) {
                throw new IllegalArgumentException("Invalid projected assertion counters");
            }
        }
    }

    /**
     * Payload-free detached signature reference.
     *
     * @param schemaVersion exact v2 attestation generation
     * @param signedAt signature creation time
     * @param keyId verification-key id
     * @param algorithm detached signature algorithm
     * @param childEvidenceRefs ordered signed child closure
     */
    public record AttestationRef(
            String schemaVersion,
            Instant signedAt,
            String keyId,
            String algorithm,
            List<TestSuiteRunAttestation.ChildEvidenceRef> childEvidenceRefs
    ) {
        /** Freezes the ordered closure and normalizes signer identities. */
        public AttestationRef {
            schemaVersion = normalized(schemaVersion);
            signedAt = signedAt == null ? Instant.EPOCH : signedAt;
            keyId = normalized(keyId);
            algorithm = normalized(algorithm);
            childEvidenceRefs = childEvidenceRefs == null ? List.of() : List.copyOf(childEvidenceRefs);
        }
    }

    /**
     * Deterministic projection and trust summary.
     *
     * @param schemaVersion manifest protocol version
     * @param bundleFingerprint fingerprint of all projected material and truncation facts
     * @param projectionStatus bounded governance status
     * @param caseCount source suite case count
     * @param semanticRequirementCount source semantic requirement count
     * @param candidateEvidenceCount retained terminal evidence candidates inspected
     * @param verifiedEvidenceCount cryptographically verified terminal evidence projected
     * @param unavailableEvidenceCount candidates whose verification authority was unavailable
     * @param eligibleEvidenceCount verified evidence with satisfied semantic coverage and promotion
     * @param evidenceTruncated whether older retained candidates were omitted by the protocol bound
     * @param gateReady whether at least one verified semantic and promotion-eligible result exists
     */
    public record Manifest(
            String schemaVersion,
            String bundleFingerprint,
            String projectionStatus,
            int caseCount,
            int semanticRequirementCount,
            int candidateEvidenceCount,
            int verifiedEvidenceCount,
            int unavailableEvidenceCount,
            int eligibleEvidenceCount,
            boolean evidenceTruncated,
            boolean gateReady
    ) {
        /** Current semantic workbook manifest protocol version. */
        public static final String SCHEMA_VERSION =
                "toolStudio.resourceGateway.semanticCorrectnessWorkbookManifest.v1";

        /** Normalizes counters and protocol defaults. */
        public Manifest {
            schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
            bundleFingerprint = normalized(bundleFingerprint);
            projectionStatus = defaulted(projectionStatus, "NO_TERMINAL_EVIDENCE");
            caseCount = Math.max(0, caseCount);
            semanticRequirementCount = Math.max(0, semanticRequirementCount);
            candidateEvidenceCount = Math.max(0, candidateEvidenceCount);
            verifiedEvidenceCount = Math.max(0, verifiedEvidenceCount);
            unavailableEvidenceCount = Math.max(0, unavailableEvidenceCount);
            eligibleEvidenceCount = Math.max(0, eligibleEvidenceCount);
        }

        static Manifest from(String payloadPolicy, Suite suite, List<Evidence> evidence,
                             int candidateEvidenceCount, int unavailableEvidenceCount,
                             boolean evidenceTruncated) {
            List<Evidence> safeEvidence = evidence == null ? List.of() : evidence;
            int eligible = (int) safeEvidence.stream().filter(Manifest::eligible).count();
            int requirements = suite == null ? 0
                    : suite.semanticCoveragePolicy().requirements().size();
            int candidates = Math.max(safeEvidence.size() + Math.max(0, unavailableEvidenceCount),
                    candidateEvidenceCount);
            String status = candidates == 0 ? "NO_TERMINAL_EVIDENCE"
                    : unavailableEvidenceCount > 0 ? "VERIFICATION_UNAVAILABLE"
                    : eligible > 0 ? "READY" : "NO_ELIGIBLE_EVIDENCE";
            return new Manifest("", fingerprint(payloadPolicy, suite, safeEvidence, candidates,
                    unavailableEvidenceCount, evidenceTruncated), status,
                    suite == null ? 0 : suite.cases().size(), requirements, candidates,
                    safeEvidence.size(), unavailableEvidenceCount, eligible, evidenceTruncated,
                    eligible > 0 && unavailableEvidenceCount == 0);
        }

        static String fingerprint(String payloadPolicy, Suite suite, List<Evidence> evidence,
                                  int candidateEvidenceCount, int unavailableEvidenceCount,
                                  boolean evidenceTruncated) {
            Map<String, Object> material = new LinkedHashMap<>();
            material.put("schemaVersion", SemanticCorrectnessWorkbookBundle.SCHEMA_VERSION);
            material.put("payloadPolicy", payloadPolicy);
            material.put("suite", suite);
            material.put("evidence", evidence == null ? List.of() : evidence);
            material.put("candidateEvidenceCount", Math.max(0, candidateEvidenceCount));
            material.put("unavailableEvidenceCount", Math.max(0, unavailableEvidenceCount));
            material.put("evidenceTruncated", evidenceTruncated);
            return VisualBundleFingerprint.fromMaterial(material);
        }

        private static boolean eligible(Evidence evidence) {
            return evidence != null
                    && evidence.status() == TestSuiteRunEvidence.Status.PASSED
                    && evidence.semanticCoverage() != null
                    && evidence.semanticCoverage().status() == SemanticCoverageVerdict.Status.SATISFIED
                    && evidence.promotion() != null
                    && evidence.promotion().status() == TestSuiteRunEvidence.PromotionStatus.ELIGIBLE;
        }
    }

    private static String defaulted(String value, String fallback) {
        String safe = normalized(value);
        return safe.isBlank() ? fallback : safe;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
