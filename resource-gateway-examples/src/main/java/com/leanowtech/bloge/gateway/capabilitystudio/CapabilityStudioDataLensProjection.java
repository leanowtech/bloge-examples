package com.leanowtech.bloge.gateway.capabilitystudio;

import com.leanowtech.bloge.gateway.testing.domain.ProtocolJsonValue;

import java.util.List;

/**
 * Bounded, permission-aware projection of the existing TestRunEvidence trace.
 *
 * <p>This is a read model only. It does not represent an executable plan and must never be used
 * as a second source of runtime truth.</p>
 */
public record CapabilityStudioDataLensProjection(
        String schemaVersion,
        String runId,
        String runStatus,
        PermissionMode permissionMode,
        List<Node> nodes,
        List<Edge> edges,
        FirstDifference firstDifference,
        Truncation truncation,
        String fingerprint
) {
    public static final String SCHEMA_VERSION = "resource-gateway.capability-studio.data-lens.v1";

    public CapabilityStudioDataLensProjection {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported Capability Studio Data Lens schema version");
        }
        runId = runId == null ? "" : runId.trim();
        runStatus = runStatus == null ? "" : runStatus.trim();
        permissionMode = permissionMode == null ? PermissionMode.STRUCTURE_ONLY : permissionMode;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        truncation = truncation == null ? Truncation.none() : truncation;
        fingerprint = fingerprint == null ? "" : fingerprint.trim();
    }

    public enum PermissionMode {
        STRUCTURE_ONLY,
        PAYLOAD_VISIBLE
    }

    public record Node(
            String nodeId,
            String operatorRef,
            String status,
            String fidelity,
            String graphPath,
            String invocationSite,
            String correlation,
            int occurrence,
            int graphOccurrence,
            Object input,
            String inputFingerprint,
            Object output,
            String outputFingerprint,
            String errorCode,
            long durationMs,
            List<Attempt> attempts,
            int retryCount,
            String fallbackStatus
    ) {
        public Node {
            nodeId = safe(nodeId);
            operatorRef = safe(operatorRef);
            status = safe(status);
            fidelity = safe(fidelity);
            graphPath = safe(graphPath);
            invocationSite = safe(invocationSite);
            correlation = safe(correlation);
            occurrence = Math.max(0, occurrence);
            graphOccurrence = Math.max(0, graphOccurrence);
            inputFingerprint = safe(inputFingerprint);
            outputFingerprint = safe(outputFingerprint);
            errorCode = safe(errorCode);
            durationMs = Math.max(0, durationMs);
            attempts = attempts == null ? List.of() : List.copyOf(attempts);
            retryCount = Math.max(0, retryCount);
            fallbackStatus = fallbackStatus == null || fallbackStatus.isBlank()
                    ? null : fallbackStatus.trim();
            input = ProtocolJsonValue.freeze(input);
            output = ProtocolJsonValue.freeze(output);
        }
    }

    public record Attempt(
            int attempt,
            String status,
            String fidelity,
            Object input,
            String inputFingerprint,
            Object output,
            String outputFingerprint,
            String errorCode,
            long durationMs
    ) {
        public Attempt {
            attempt = Math.max(0, attempt);
            status = safe(status);
            fidelity = safe(fidelity);
            inputFingerprint = safe(inputFingerprint);
            outputFingerprint = safe(outputFingerprint);
            errorCode = safe(errorCode);
            durationMs = Math.max(0, durationMs);
            input = ProtocolJsonValue.freeze(input);
            output = ProtocolJsonValue.freeze(output);
        }
    }

    public record Edge(
            String edgeId,
            String status,
            String graphPath,
            String correlation,
            int graphOccurrence,
            String fromInvocationSite,
            String toInvocationSite,
            Object value,
            String valueFingerprint
    ) {
        public Edge {
            edgeId = safe(edgeId);
            status = safe(status);
            graphPath = safe(graphPath);
            correlation = safe(correlation);
            graphOccurrence = Math.max(0, graphOccurrence);
            fromInvocationSite = safe(fromInvocationSite);
            toInvocationSite = safe(toInvocationSite);
            valueFingerprint = safe(valueFingerprint);
            value = ProtocolJsonValue.freeze(value);
        }
    }

    public record FirstDifference(
            String source,
            String locator,
            String scope,
            String path,
            Object expected,
            String expectedFingerprint,
            Object actual,
            String actualFingerprint
    ) {
        public FirstDifference {
            source = safe(source);
            locator = safe(locator);
            scope = safe(scope);
            path = safe(path);
            expectedFingerprint = safe(expectedFingerprint);
            actualFingerprint = safe(actualFingerprint);
            expected = ProtocolJsonValue.freeze(expected);
            actual = ProtocolJsonValue.freeze(actual);
        }
    }

    public record Truncation(
            boolean nodesTruncated,
            int omittedNodes,
            boolean edgesTruncated,
            int omittedEdges,
            boolean attemptsTruncated,
            int omittedAttempts
    ) {
        public Truncation {
            omittedNodes = Math.max(0, omittedNodes);
            omittedEdges = Math.max(0, omittedEdges);
            omittedAttempts = Math.max(0, omittedAttempts);
        }

        public static Truncation none() {
            return new Truncation(false, 0, false, 0, false, 0);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
