package com.leanowtech.bloge.gateway.testing.world;

/** Immutable occurrence coordinate for one concrete stateful invocation. */
public record WorldInvocationCoordinate(
        String graphPath,
        String nodeId,
        int graphOccurrence,
        int occurrence,
        int attempt,
        String structuralInvocationSiteId
) {
    public static final String SCHEMA_VERSION = "bloge.worldInvocationCoordinate.v1";
    private static final int MAX_STRING_LENGTH = 4_096;
    public WorldInvocationCoordinate {
        graphPath = required(graphPath);
        nodeId = required(nodeId);
        structuralInvocationSiteId = required(structuralInvocationSiteId);
        if (!graphPath.startsWith("/") || graphOccurrence < 1 || occurrence < 1 || attempt < 1) {
            throw new WorldModelException(WorldModelException.Code.STATE_TRANSACTION_INVALID);
        }
    }

    /** Stable collision-resistant key used by observations and snapshot ordering. */
    public String canonicalKey() {
        return SCHEMA_VERSION
                + "|g=" + encoded(graphPath)
                + "|n=" + encoded(nodeId)
                + "|go=" + graphOccurrence
                + "|o=" + occurrence
                + "|a=" + attempt
                + "|s=" + encoded(structuralInvocationSiteId);
    }

    boolean matchesStructuralSite() {
        return structuralInvocationSiteId.startsWith(
                graphPath + "/" + escape(nodeId) + "#");
    }

    private static String encoded(String value) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static String required(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_STRING_LENGTH
                || !value.equals(value.trim())) {
            throw new WorldModelException(WorldModelException.Code.STATE_TRANSACTION_INVALID);
        }
        return value.trim();
    }
}
