package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;

/**
 * Registry-free semantic verifier for payload-free Capability Proposal simulation results.
 *
 * <p>This verifier proves Schema conformance, content addresses, exact identity closure, ordering,
 * and simulation-only evidence state. Signature authenticity remains the responsibility of the
 * deployment evidence-trust verifier because verification keys are deployment-owned.</p>
 */
public final class BusinessMirrorSimulationVerifier {
    private BusinessMirrorSimulationVerifier() {
    }

    /**
     * Verifies the strict payload-free simulation command.
     *
     * @param request decoded command
     * @throws IllegalArgumentException when the command violates the packaged protocol
     */
    public static void verifyRequest(JsonNode request) {
        BusinessMirrorSchemaValidator.require(request,
                BusinessMirrorProtocol.PROPOSAL_SIMULATION_REQUEST_SCHEMA_RESOURCE,
                "RG.BUSINESS_MIRROR.CLIENT.PROPOSAL_SIMULATION_REQUEST_INVALID");
    }

    /**
     * Verifies one content-addressed Proposal simulation aggregate.
     *
     * @param evidence decoded aggregate evidence
     * @return payload-free verified evidence identity
     * @throws IllegalArgumentException when Schema, fingerprint, order, coverage, or state fails
     */
    public static VerifiedSimulationEvidence verifyEvidence(JsonNode evidence) {
        BusinessMirrorSchemaValidator.require(evidence,
                BusinessMirrorProtocol.PROPOSAL_SIMULATION_EVIDENCE_SCHEMA_RESOURCE,
                "RG.BUSINESS_MIRROR.CLIENT.PROPOSAL_SIMULATION_EVIDENCE_INVALID");
        requireFingerprint(evidence, "fingerprint",
                "RG.BUSINESS_MIRROR.CLIENT.PROPOSAL_SIMULATION_EVIDENCE_FINGERPRINT_MISMATCH");
        Instant startedAt = instant(evidence.path("startedAt").asText());
        Instant completedAt = instant(evidence.path("completedAt").asText());
        if (completedAt.isBefore(startedAt)) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.PROPOSAL_SIMULATION_TIME_INVALID");
        }

        Set<String> acceptedSuites = new HashSet<>();
        RefOrder previousSuite = null;
        for (JsonNode ref : evidence.path("acceptanceSuiteRefs")) {
            RefOrder current = RefOrder.from(ref);
            if (!acceptedSuites.add(current.identity())
                    || previousSuite != null && previousSuite.compareTo(current) >= 0) {
                throw invalid("RG.BUSINESS_MIRROR.CLIENT.PROPOSAL_SIMULATION_ORDER_INVALID");
            }
            previousSuite = current;
        }

        Set<String> coveredSuites = new HashSet<>();
        Set<String> caseCoordinates = new HashSet<>();
        CaseOrder previousCase = null;
        int proposalCalls = 0;
        boolean allPassed = true;
        for (JsonNode item : evidence.path("cases")) {
            RefOrder suite = RefOrder.from(item.path("suiteRef"));
            CaseOrder current = new CaseOrder(suite, item.path("caseId").asText());
            if (!acceptedSuites.contains(suite.identity())
                    || !caseCoordinates.add(suite.identity() + "\u0000" + current.caseId())
                    || previousCase != null && previousCase.compareTo(current) >= 0
                    || !sortedUniqueText(item.path("resolverSources"))
                    || !sortedUniqueText(item.path("matchedRuleRefs"))
                    || !sortedUniqueText(item.path("limitations"))) {
                throw invalid("RG.BUSINESS_MIRROR.CLIENT.PROPOSAL_SIMULATION_ORDER_INVALID");
            }
            coveredSuites.add(suite.identity());
            proposalCalls = Math.addExact(
                    proposalCalls, item.path("proposalCallCount").asInt());
            allPassed &= "PASSED".equals(item.path("runStatus").asText());
            previousCase = current;
        }
        if (!acceptedSuites.equals(coveredSuites)) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.PROPOSAL_SIMULATION_COVERAGE_INVALID");
        }
        if ("PASSED".equals(evidence.path("status").asText())
                && (!allPassed || proposalCalls == 0)) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.PROPOSAL_SIMULATION_STATUS_INVALID");
        }
        if (!sortedUniqueText(evidence.path("limitations"))
                || !sortedUniqueText(evidence.path("uncertainties"))) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.PROPOSAL_SIMULATION_ORDER_INVALID");
        }
        return new VerifiedSimulationEvidence(evidence.path("simulationId").asText(),
                evidence.path("fingerprint").asText(),
                evidence.path("proposalDraftRef").path("id").asText(),
                evidence.path("proposalDraftRef").path("revision").asLong(),
                evidence.path("status").asText(), evidence.path("cases").size(),
                proposalCalls, completedAt);
    }

    /**
     * Verifies a durable simulation result and its complete evidence-to-Proposal identity closure.
     *
     * @param stored decoded durable result
     * @return payload-free verified result identity
     * @throws IllegalArgumentException when Schema, content, state, or identity closure fails
     */
    public static VerifiedStoredSimulation verifyStoredSimulation(JsonNode stored) {
        BusinessMirrorSchemaValidator.require(stored,
                BusinessMirrorProtocol.STORED_PROPOSAL_SIMULATION_SCHEMA_RESOURCE,
                "RG.BUSINESS_MIRROR.CLIENT.STORED_PROPOSAL_SIMULATION_INVALID");
        VerifiedSimulationEvidence evidence = verifyEvidence(stored.path("evidence"));
        JsonNode snapshot = stored.path("proposalSnapshot");
        requireFingerprint(snapshot, "fingerprint",
                "RG.BUSINESS_MIRROR.CLIENT.PROPOSAL_SNAPSHOT_FINGERPRINT_MISMATCH");
        JsonNode seal = stored.path("attestation");
        JsonNode evidenceRef = findEvidenceRef(snapshot.path("evidenceRefs"));
        Instant completedAt = instant(stored.path("completedAt").asText());
        if (!stored.path("requestFingerprint").asText().matches("sha256:[a-f0-9]{64}")
                || !evidence.evidenceFingerprint().equals(
                seal.path("materialFingerprint").asText())
                || !evidence.proposalId().equals(snapshot.path("proposalId").asText())
                || evidence.proposalRevision() != snapshot.path("sourceDraftRevision").asLong()
                || !evidence.evidenceFingerprint().equals(
                evidenceRef == null ? "" : evidenceRef.path("fingerprint").asText())
                || !evidence.simulationId().equals(
                evidenceRef == null ? "" : evidenceRef.path("id").asText())
                || !"SIMULATED".equals(snapshot.path("evidenceState").asText())
                || !snapshot.path("implementationBindingRef").isNull()
                || !completedAt.equals(evidence.completedAt())
                || !completedAt.equals(instant(snapshot.path("createdAt").asText()))) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.STORED_PROPOSAL_SIMULATION_INCONSISTENT");
        }
        return new VerifiedStoredSimulation(stored.path("requestFingerprint").asText(),
                evidence.simulationId(), evidence.proposalId(), evidence.proposalRevision(),
                evidence.status(), evidence.caseCount(), evidence.proposalCallCount(),
                completedAt);
    }

    private static JsonNode findEvidenceRef(JsonNode refs) {
        for (JsonNode ref : refs) {
            if ("PROPOSAL_SIMULATION_EVIDENCE".equals(ref.path("kind").asText())) {
                return ref;
            }
        }
        return null;
    }

    private static void requireFingerprint(JsonNode value, String field, String code) {
        ObjectNode material = ((ObjectNode) value).deepCopy();
        String attached = material.path(field).asText();
        material.put(field, "");
        String expected = BusinessMirrorCanonical.fingerprint(material,
                "RG.BUSINESS_MIRROR.CLIENT.PROPOSAL_SIMULATION_TOO_LARGE",
                "RG.BUSINESS_MIRROR.CLIENT.PROPOSAL_SIMULATION_CANONICALIZATION_FAILED");
        if (!expected.equals(attached)) {
            throw invalid(code);
        }
    }

    private static boolean sortedUniqueText(JsonNode values) {
        String previous = null;
        for (JsonNode value : values) {
            String current = value.asText();
            if (previous != null && previous.compareTo(current) >= 0) {
                return false;
            }
            previous = current;
        }
        return true;
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException failure) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.PROPOSAL_SIMULATION_TIME_INVALID");
        }
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    private record RefOrder(String id, long revision, String fingerprint)
            implements Comparable<RefOrder> {
        private static RefOrder from(JsonNode ref) {
            return new RefOrder(ref.path("id").asText(), ref.path("revision").asLong(),
                    ref.path("fingerprint").asText());
        }

        private String identity() {
            return id + "\u0000" + revision + "\u0000" + fingerprint;
        }

        @Override
        public int compareTo(RefOrder other) {
            int byId = id.compareTo(other.id);
            if (byId != 0) {
                return byId;
            }
            int byRevision = Long.compare(revision, other.revision);
            return byRevision != 0 ? byRevision : fingerprint.compareTo(other.fingerprint);
        }
    }

    private record CaseOrder(RefOrder suite, String caseId)
            implements Comparable<CaseOrder> {
        @Override
        public int compareTo(CaseOrder other) {
            int bySuite = suite.compareTo(other.suite);
            return bySuite != 0 ? bySuite : caseId.compareTo(other.caseId);
        }
    }

    /**
     * Payload-free identity of verified aggregate evidence.
     *
     * @param simulationId stable idempotency identity
     * @param evidenceFingerprint verified aggregate content address
     * @param proposalId source Proposal id
     * @param proposalRevision exact source Proposal revision
     * @param status aggregate simulation status
     * @param caseCount number of acceptance cases
     * @param proposalCallCount number of calls resolved through the temporary capability
     * @param completedAt terminal evidence time
     */
    public record VerifiedSimulationEvidence(
            String simulationId,
            String evidenceFingerprint,
            String proposalId,
            long proposalRevision,
            String status,
            int caseCount,
            int proposalCallCount,
            Instant completedAt) {
    }

    /**
     * Payload-free identity of a verified durable Proposal simulation result.
     *
     * @param requestFingerprint exact idempotent command fingerprint
     * @param simulationId stable idempotency identity
     * @param proposalId source Proposal id
     * @param proposalRevision exact source Proposal revision
     * @param status aggregate simulation status
     * @param caseCount number of acceptance cases
     * @param proposalCallCount number of calls resolved through the temporary capability
     * @param completedAt durable terminal time
     */
    public record VerifiedStoredSimulation(
            String requestFingerprint,
            String simulationId,
            String proposalId,
            long proposalRevision,
            String status,
            int caseCount,
            int proposalCallCount,
            Instant completedAt) {
    }
}
