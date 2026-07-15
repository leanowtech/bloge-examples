package com.leanowtech.bloge.gateway.testing.domain;

/**
 * Stable identity for one controllable operator, resource, function, subgraph, or compensation
 * invocation in a frozen graph artifact.
 *
 * <p>{@code graphPath + nodeId + invocationKind} is the primary identity. Attempt, correlation,
 * and occurrence are reserved runtime coordinates; v1 accepts them in the wire contract but the
 * planner rejects them until deterministic retry and foreach lineage are available.</p>
 *
 * @param schemaVersion invocation-site schema version
 * @param artifactFingerprint fingerprint of the graph artifact containing the site
 * @param graphPath stable path from the graph root, including nested graph bodies
 * @param nodeId node id within {@code graphPath}
 * @param operatorRef operator catalog reference, when the site invokes an operator
 * @param resourceRef resource descriptor reference, when the site invokes a resource
 * @param functionRef built-in function reference, when the site invokes a function
 * @param runtimeBindingFingerprint frozen implementation binding fingerprint
 * @param invocationKind semantic kind of invocation
 * @param attempt one-based retry attempt, reserved in v1
 * @param correlationKey foreach item or business correlation key
 * @param occurrence one-based occurrence at the same site, reserved in v1
 */
public record InvocationSite(
        String schemaVersion,
        String artifactFingerprint,
        String graphPath,
        String nodeId,
        String operatorRef,
        String resourceRef,
        String functionRef,
        String runtimeBindingFingerprint,
        InvocationKind invocationKind,
        Integer attempt,
        String correlationKey,
        Integer occurrence
) {
    /** Current invocation-site protocol version. */
    public static final String SCHEMA_VERSION = "bloge.invocationSite.v1";

    /** Supported semantic invocation identities. */
    public enum InvocationKind {
        PRIMARY,
        COMPENSATION,
        FUNCTION,
        RESOURCE,
        SUBGRAPH
    }

    /** Normalizes nullable wire values without weakening the stable identity fields. */
    public InvocationSite {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        artifactFingerprint = trimmed(artifactFingerprint);
        graphPath = normalizeGraphPath(graphPath);
        nodeId = trimmed(nodeId);
        operatorRef = trimmed(operatorRef);
        resourceRef = trimmed(resourceRef);
        functionRef = trimmed(functionRef);
        runtimeBindingFingerprint = trimmed(runtimeBindingFingerprint);
        invocationKind = invocationKind == null ? InvocationKind.PRIMARY : invocationKind;
        correlationKey = trimmed(correlationKey);
    }

    /**
     * Returns the human-readable primary site id used by plans and trace records.
     *
     * @return graph-path, node, and kind identity
     */
    public String invocationSiteId() {
        String prefix = "/".equals(graphPath) ? "" : graphPath;
        return prefix + "/" + nodeId + "#" + invocationKind;
    }

    private static String normalizeGraphPath(String value) {
        String path = trimmed(value);
        if (path.isEmpty() || "/".equals(path)) {
            return "/root";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private static String defaulted(String value, String fallback) {
        String normalized = trimmed(value);
        return normalized.isEmpty() ? fallback : normalized;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
