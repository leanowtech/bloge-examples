package com.leanowtech.bloge.gateway.visual.authoring.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestEvidenceProtocol.EvidenceRecord;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Canonical material hashing and detached-signature verification.
 */
final class AuthoringTestEvidenceIntegrity {

    private static final int MAXIMUM_MATERIAL_BYTES = 2 * 1024 * 1024;
    private static final Pattern SHA256 =
            Pattern.compile("^sha256:[a-f0-9]{64}$");

    private AuthoringTestEvidenceIntegrity() {
    }

    static EvidenceRecord attach(
            ObjectMapper objectMapper,
            VisualEvidenceSigner signer,
            EvidenceRecord candidate) {
        requireIdentity(candidate);
        String fingerprint = fingerprint(objectMapper, candidate);
        VisualRunEvidenceSeal seal;
        try {
            seal = Objects.requireNonNull(signer, "signer").seal(
                    fingerprint,
                    "visual-authoring-test-evidence:" + candidate.runId());
        } catch (RuntimeException failure) {
            throw new AuthoringTestEvidenceIntegrityException(failure);
        }
        EvidenceRecord signed = candidate.withIntegrity(fingerprint, seal);
        return verify(objectMapper, signer, signed);
    }

    static EvidenceRecord verify(
            ObjectMapper objectMapper,
            VisualEvidenceSigner signer,
            EvidenceRecord evidence) {
        requireIdentity(evidence);
        String fingerprint = fingerprint(objectMapper, evidence);
        VisualRunEvidenceSeal seal = evidence.seal();
        if (!fingerprint.equals(evidence.materialFingerprint())
                || seal == null
                || !seal.signed()
                || !fingerprint.equals(seal.materialFingerprint())) {
            throw new AuthoringTestEvidenceIntegrityException();
        }
        VisualEvidenceSigner.Verification verification;
        try {
            verification = Objects.requireNonNull(signer, "signer")
                    .verify(seal, fingerprint);
        } catch (RuntimeException failure) {
            throw new AuthoringTestEvidenceIntegrityException(failure);
        }
        if (verification == null || !verification.valid()) {
            throw new AuthoringTestEvidenceIntegrityException();
        }
        return evidence;
    }

    private static String fingerprint(
            ObjectMapper objectMapper,
            EvidenceRecord evidence) {
        try {
            return VisualBundleFingerprint.fromCanonicalValue(
                    Objects.requireNonNull(objectMapper, "objectMapper"),
                    material(evidence),
                    MAXIMUM_MATERIAL_BYTES);
        } catch (RuntimeException failure) {
            throw new AuthoringTestEvidenceIntegrityException(failure);
        }
    }

    private static Map<String, Object> material(EvidenceRecord value) {
        return Map.ofEntries(
                Map.entry("schemaVersion", value.schemaVersion()),
                Map.entry("scope", value.scope()),
                Map.entry("runId", value.runId()),
                Map.entry("assetKind", value.assetKind().name()),
                Map.entry("assetRef", value.assetRef()),
                Map.entry("draftId", value.draftId()),
                Map.entry("authoringRevision", value.authoringRevision()),
                Map.entry("authoringFingerprint", value.authoringFingerprint()),
                Map.entry("canonicalFingerprint", value.canonicalFingerprint()),
                Map.entry("artifactFingerprint", value.artifactFingerprint()),
                Map.entry("runtimeFingerprint", value.runtimeFingerprint()),
                Map.entry("executionProfile", value.executionProfile()),
                Map.entry("suiteFingerprint", value.suiteFingerprint()),
                Map.entry("sourceEvidenceFingerprint", value.sourceEvidenceFingerprint()),
                Map.entry("policyVersion", value.policyVersion()),
                Map.entry("proofMode", value.proofMode()),
                Map.entry("bindingStatus", value.bindingStatus()),
                Map.entry("passed", value.passed()),
                Map.entry("totalCases", value.totalCases()),
                Map.entry("passedCases", value.passedCases()),
                Map.entry("failedCases", value.failedCases()),
                Map.entry("requiredCaseCount", value.requiredCaseCount()),
                Map.entry("coverage", value.coverage()),
                Map.entry("cases", value.cases()),
                Map.entry("declaredTestRefs", value.declaredTestRefs()),
                Map.entry("diagnosticCodes", value.diagnosticCodes()),
                Map.entry("executedAt", value.executedAt()),
                Map.entry("actorId", value.actorId()),
                Map.entry("payloadPersisted", false));
    }

    private static void requireIdentity(EvidenceRecord evidence) {
        if (evidence == null
                || evidence.scope() == null
                || evidence.runId().isBlank()
                || evidence.assetRef().isBlank()
                || evidence.draftId().isBlank()
                || evidence.authoringRevision() <= 0
                || !fingerprint(evidence.authoringFingerprint())
                || !fingerprint(evidence.canonicalFingerprint())
                || !fingerprint(evidence.artifactFingerprint())
                || !fingerprint(evidence.suiteFingerprint())
                || !fingerprint(evidence.sourceEvidenceFingerprint())
                || evidence.executedAt().equals(java.time.Instant.EPOCH)
                || evidence.actorId().isBlank()
                || evidence.totalCases() < 1
                || evidence.totalCases() > AuthoringTestService.MAXIMUM_CASES
                || evidence.requiredCaseCount() > AuthoringTestService.MAXIMUM_CASES
                || evidence.totalCases() != evidence.cases().size()
                || evidence.passedCases() + evidence.failedCases() != evidence.totalCases()
                || evidence.passed() != (evidence.failedCases() == 0)
                || evidence.payloadPersisted()) {
            throw new AuthoringTestEvidenceIntegrityException();
        }
        if (evidence.assetKind() == AuthoringTestEvidenceProtocol.AssetKind.FUNCTION
                && (evidence.executionProfile().isBlank()
                || !"BOUND".equals(evidence.bindingStatus())
                && !"UNBOUND".equals(evidence.bindingStatus())
                && !"BLOCKED_BY_POLICY".equals(evidence.bindingStatus())
                || !"UNBOUND".equals(evidence.bindingStatus())
                && !fingerprint(evidence.runtimeFingerprint())
                || !evidence.runtimeFingerprint().isBlank()
                && !fingerprint(evidence.runtimeFingerprint()))) {
            throw new AuthoringTestEvidenceIntegrityException();
        }
        if (evidence.cases().stream().anyMatch(Objects::isNull)
                || evidence.declaredTestRefs().contains(null)
                || evidence.diagnosticCodes().contains(null)) {
            throw new AuthoringTestEvidenceIntegrityException();
        }
    }

    private static boolean fingerprint(String value) {
        return value != null && SHA256.matcher(value).matches();
    }
}
