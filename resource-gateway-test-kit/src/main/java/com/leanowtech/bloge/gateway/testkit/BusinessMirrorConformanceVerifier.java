package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Set;

/** Registry-free semantic verifier for exact implementation-conformance artifacts. */
public final class BusinessMirrorConformanceVerifier {
    private BusinessMirrorConformanceVerifier() {
    }

    /**
     * Verifies the exact immutable coordinates required to start conformance.
     *
     * @param request decoded implementation-conformance request
     * @throws IllegalArgumentException when the request violates the packaged protocol
     */
    public static void verifyRequest(JsonNode request) {
        BusinessMirrorSchemaValidator.require(request,
                BusinessMirrorProtocol.IMPLEMENTATION_CONFORMANCE_REQUEST_SCHEMA_RESOURCE,
                "RG.BUSINESS_MIRROR.CLIENT.IMPLEMENTATION_CONFORMANCE_REQUEST_INVALID");
    }

    /**
     * Verifies one embedded payload-free implementation evidence content address.
     *
     * @param evidence decoded implementation test evidence
     * @return verified evidence identity safe for governance indexing
     * @throws IllegalArgumentException when Schema, content address, or time ordering fails
     */
    public static VerifiedImplementationEvidence verifyImplementationEvidence(JsonNode evidence) {
        BusinessMirrorSchemaValidator.require(evidence,
                BusinessMirrorProtocol.IMPLEMENTATION_TEST_EVIDENCE_SCHEMA_RESOURCE,
                "RG.BUSINESS_MIRROR.CLIENT.IMPLEMENTATION_TEST_EVIDENCE_INVALID");
        String fingerprint = contentAddress(evidence,
                "RG.BUSINESS_MIRROR.CLIENT.IMPLEMENTATION_TEST_EVIDENCE_FINGERPRINT_MISMATCH");
        Instant startedAt = instant(evidence.path("startedAt").asText());
        Instant completedAt = instant(evidence.path("completedAt").asText());
        if (completedAt.isBefore(startedAt)) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.IMPLEMENTATION_TEST_EVIDENCE_TIME_INVALID");
        }
        return new VerifiedImplementationEvidence(evidence.path("runId").asText(), fingerprint,
                evidence.path("status").asText(),
                evidence.path("semanticResultFingerprint").asText());
    }

    /**
     * Verifies report content address, complete Suite coverage, order, and derived status.
     *
     * @param report decoded implementation-conformance report
     * @return verified report identity and exact implementation coordinate
     * @throws IllegalArgumentException when report integrity or coverage is inconsistent
     */
    public static VerifiedConformanceReport verifyReport(JsonNode report) {
        BusinessMirrorSchemaValidator.require(report,
                BusinessMirrorProtocol.IMPLEMENTATION_CONFORMANCE_REPORT_SCHEMA_RESOURCE,
                "RG.BUSINESS_MIRROR.CLIENT.IMPLEMENTATION_CONFORMANCE_REPORT_INVALID");
        String previous = null;
        Set<String> suites = new LinkedHashSet<>();
        Set<String> coordinates = new LinkedHashSet<>();
        boolean allMatch = true;
        for (JsonNode value : report.path("cases")) {
            verifyImplementationEvidence(value.path("implementationEvidence"));
            String suite = refCoordinate(value.path("suiteRef"));
            String coordinate = suite + "\u0000" + value.path("caseId").asText();
            if (previous != null && previous.compareTo(coordinate) >= 0
                    || !coordinates.add(coordinate)) {
                throw invalid("RG.BUSINESS_MIRROR.CLIENT.IMPLEMENTATION_CONFORMANCE_CASE_ORDER_INVALID");
            }
            previous = coordinate;
            suites.add(suite);
            boolean match = "MATCH".equals(value.path("comparison").asText());
            allMatch &= match;
            if (match && (!value.path("mismatchReasons").isEmpty()
                    || value.path("baselineTargetCallCount").asInt()
                    != value.path("implementationTargetCallCount").asInt()
                    || !value.path("baselineBehaviorFingerprint").asText().equals(
                    value.path("implementationBehaviorFingerprint").asText()))
                    || !match && value.path("mismatchReasons").isEmpty()) {
                throw invalid("RG.BUSINESS_MIRROR.CLIENT.IMPLEMENTATION_CONFORMANCE_CASE_INVALID");
            }
        }
        Set<String> declaredSuites = new LinkedHashSet<>();
        for (JsonNode value : report.path("acceptanceSuiteRefs")) {
            declaredSuites.add(refCoordinate(value));
        }
        if (!declaredSuites.equals(suites)
                || allMatch != "PASSED".equals(report.path("status").asText())) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.IMPLEMENTATION_CONFORMANCE_COVERAGE_INVALID");
        }
        Instant startedAt = instant(report.path("startedAt").asText());
        Instant completedAt = instant(report.path("completedAt").asText());
        if (completedAt.isBefore(startedAt)) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.IMPLEMENTATION_CONFORMANCE_TIME_INVALID");
        }
        return new VerifiedConformanceReport(report.path("conformanceId").asText(),
                contentAddress(report,
                        "RG.BUSINESS_MIRROR.CLIENT.IMPLEMENTATION_CONFORMANCE_FINGERPRINT_MISMATCH"),
                report.path("status").asText(),
                refCoordinate(report.path("implementationBindingRef")), completedAt);
    }

    /**
     * Verifies detached material binding and the resulting Proposal evidence-state closure.
     *
     * @param stored decoded durable conformance aggregate
     * @return verified embedded report identity
     * @throws IllegalArgumentException when report, attestation, or Proposal closure is inconsistent
     */
    public static VerifiedConformanceReport verifyStored(JsonNode stored) {
        BusinessMirrorSchemaValidator.require(stored,
                BusinessMirrorProtocol.STORED_IMPLEMENTATION_CONFORMANCE_SCHEMA_RESOURCE,
                "RG.BUSINESS_MIRROR.CLIENT.STORED_IMPLEMENTATION_CONFORMANCE_INVALID");
        VerifiedConformanceReport report = verifyReport(stored.path("report"));
        BusinessMirrorProtocol.requireProposalSnapshot(stored.path("proposalSnapshot"));
        JsonNode snapshot = stored.path("proposalSnapshot");
        contentAddress(snapshot,
                "RG.BUSINESS_MIRROR.CLIENT.PROPOSAL_SNAPSHOT_FINGERPRINT_MISMATCH");
        String expectedState = "PASSED".equals(report.status()) ? "CONFORMANT" : "IMPLEMENTED";
        boolean reportReferenced = false;
        for (JsonNode reference : snapshot.path("evidenceRefs")) {
            reportReferenced |= "IMPLEMENTATION_CONFORMANCE_REPORT".equals(
                    reference.path("kind").asText())
                    && report.conformanceId().equals(reference.path("id").asText())
                    && report.fingerprint().equals(reference.path("fingerprint").asText());
        }
        if (!report.fingerprint().equals(
                stored.path("attestation").path("materialFingerprint").asText())
                || !report.completedAt().equals(instant(stored.path("completedAt").asText()))
                || !expectedState.equals(snapshot.path("evidenceState").asText())
                || !report.implementationBindingCoordinate().equals(
                refCoordinate(snapshot.path("implementationBindingRef")))
                || !reportReferenced) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.STORED_IMPLEMENTATION_CONFORMANCE_INCONSISTENT");
        }
        return report;
    }

    private static String contentAddress(JsonNode value, String code) {
        ObjectNode material = ((ObjectNode) value).deepCopy();
        String attached = material.path("fingerprint").asText();
        material.put("fingerprint", "");
        String expected = BusinessMirrorCanonical.fingerprint(material,
                "RG.BUSINESS_MIRROR.CLIENT.IMPLEMENTATION_CONFORMANCE_TOO_LARGE",
                "RG.BUSINESS_MIRROR.CLIENT.IMPLEMENTATION_CONFORMANCE_CANONICALIZATION_FAILED");
        if (!expected.equals(attached)) {
            throw invalid(code);
        }
        return attached;
    }

    private static String refCoordinate(JsonNode ref) {
        return ref.path("kind").asText() + ':' + ref.path("id").asText() + ':'
                + ref.path("revision").asLong() + ':' + ref.path("fingerprint").asText();
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException failure) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.IMPLEMENTATION_CONFORMANCE_TIME_INVALID");
        }
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    /**
     * Verified payload-free implementation run identity.
     *
     * @param runId stable implementation test-run identity
     * @param fingerprint evidence content address
     * @param status terminal implementation run status
     * @param semanticResultFingerprint payload-free semantic result identity
     */
    public record VerifiedImplementationEvidence(
            String runId, String fingerprint, String status, String semanticResultFingerprint) {
    }

    /**
     * Verified aggregate report identity and exact implementation coordinate.
     *
     * @param conformanceId stable conformance execution identity
     * @param fingerprint report content address
     * @param status derived aggregate status
     * @param implementationBindingCoordinate exact tested binding coordinate
     * @param completedAt report completion time
     */
    public record VerifiedConformanceReport(
            String conformanceId,
            String fingerprint,
            String status,
            String implementationBindingCoordinate,
            Instant completedAt) {
    }
}
