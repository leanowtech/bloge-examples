package com.leanowtech.bloge.gateway.gateway;

import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.List;
import java.util.Map;

/**
 * Result for one resource graph contract-test case.
 *
 * @param caseName case display name
 * @param passed whether the case passed all gates and assertions
 * @param graphSuccess whether the graph execution itself succeeded
 * @param outputNode selected output node
 * @param output selected output payload
 * @param outputConformsToSchema whether output matched the graph output schema
 * @param mockedResourceInvocations observed mocked resource calls
 * @param statusMap graph node status map
 * @param diagnostics diagnostics emitted by validation, execution, and assertions
 * @param assertionCount assertions evaluated for this case
 * @param evidence payload-free execution-control evidence summary
 */
public record GatewayGraphContractTestCaseResult(
        String caseName,
        boolean passed,
        boolean graphSuccess,
        String outputNode,
        Object output,
        boolean outputConformsToSchema,
        List<GatewayGraphResourceInvocation> mockedResourceInvocations,
        Map<String, NodeStatus> statusMap,
        List<VisualDiagnostic> diagnostics,
        int assertionCount,
        EvidenceSummary evidence
) {
    /**
     * Creates a case result.
     */
    public GatewayGraphContractTestCaseResult {
        caseName = caseName == null ? "" : caseName;
        outputNode = outputNode == null ? "" : outputNode;
        mockedResourceInvocations = mockedResourceInvocations == null
                ? List.of()
                : List.copyOf(mockedResourceInvocations);
        statusMap = statusMap == null ? Map.of() : Map.copyOf(statusMap);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        evidence = evidence == null ? EvidenceSummary.empty() : evidence;
    }

    /**
     * Payload-free evidence projection that lets stored-suite gates inspect trust and fidelity
     * without exposing node input/output.
     *
     * @param runId execution-data-control run id
     * @param status normalized terminal status
     * @param evidenceClass exploratory or certifiable trust class
     * @param targetFingerprint frozen graph and descriptor fingerprint
     * @param fixtureBundleFingerprint exact fixture fingerprint
     * @param planFingerprint compiled control-plan fingerprint
     * @param nodes payload-free node execution observations
     */
    public record EvidenceSummary(
            String runId,
            TestRunEvidence.Status status,
            TestRunEvidence.EvidenceClass evidenceClass,
            String targetFingerprint,
            String fixtureBundleFingerprint,
            String planFingerprint,
            List<NodeObservation> nodes
    ) {
        /** Normalizes nullable protocol values and freezes node observations. */
        public EvidenceSummary {
            runId = normalized(runId);
            targetFingerprint = normalized(targetFingerprint);
            fixtureBundleFingerprint = normalized(fixtureBundleFingerprint);
            planFingerprint = normalized(planFingerprint);
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
        }

        /**
         * Creates the absence marker used when validation failed before controlled execution.
         *
         * @return empty exploratory evidence summary
         */
        public static EvidenceSummary empty() {
            return new EvidenceSummary("", null, TestRunEvidence.EvidenceClass.EXPLORATORY,
                    "", "", "", List.of());
        }

        /**
         * Projects sanitized kernel evidence without retaining business payloads.
         *
         * @param evidence kernel evidence
         * @return payload-free summary
         */
        public static EvidenceSummary from(TestRunEvidence evidence) {
            if (evidence == null) {
                return empty();
            }
            List<NodeObservation> nodes = evidence.nodeTrace().stream()
                    .map(NodeObservation::from)
                    .toList();
            return new EvidenceSummary(evidence.runId(), evidence.status(), evidence.evidenceClass(),
                    evidence.targetFingerprint(), evidence.fixtureBundleFingerprint(),
                    evidence.planFingerprint(), nodes);
        }

        private static String normalized(String value) {
            return value == null ? "" : value.trim();
        }
    }

    /**
     * Payload-free projection of one node trace.
     *
     * @param nodeId graph node id
     * @param operatorRef resolved operator reference
     * @param status normalized execution status
     * @param fidelity observed fixture fidelity
     * @param errorCode normalized error code, if any
     * @param durationMs observed duration
     */
    public record NodeObservation(
            String nodeId,
            String operatorRef,
            String status,
            String fidelity,
            String errorCode,
            long durationMs
    ) {
        /** Normalizes trace labels and rejects negative durations. */
        public NodeObservation {
            nodeId = normalized(nodeId);
            operatorRef = normalized(operatorRef);
            status = normalized(status);
            fidelity = normalized(fidelity);
            errorCode = normalized(errorCode);
            durationMs = Math.max(0, durationMs);
        }

        private static NodeObservation from(TestRunEvidence.NodeTrace trace) {
            return new NodeObservation(trace.nodeId(), trace.operatorRef(), trace.status(), trace.fidelity(),
                    trace.errorCode(), trace.durationMs());
        }

        private static String normalized(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
