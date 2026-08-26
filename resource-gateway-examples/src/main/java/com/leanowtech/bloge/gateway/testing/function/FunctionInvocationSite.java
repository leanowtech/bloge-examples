package com.leanowtech.bloge.gateway.testing.function;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/** Immutable structural identity of one statically compiled BLOGE function call. */
public record FunctionInvocationSite(
        String graphPath,
        String nodeId,
        String functionName,
        int line,
        int column
) implements Comparable<FunctionInvocationSite> {

    public static final String SCHEMA_VERSION = "bloge.functionInvocationSite.v1";
    private static final int MAX_COORDINATE = 1_000_000;

    public FunctionInvocationSite {
        graphPath = normalizeGraphPath(graphPath);
        nodeId = FunctionValueSupport.text(nodeId, true,
                FunctionControlException.Code.SITE_INVALID);
        functionName = FunctionValueSupport.text(functionName, true,
                FunctionControlException.Code.SITE_INVALID);
        if (line < 0 || line > MAX_COORDINATE || column < 0 || column > MAX_COORDINATE) {
            throw new FunctionControlException(FunctionControlException.Code.SITE_INVALID);
        }
    }

    /** Collision-free canonical identity with encoded segments and an explicit schema version. */
    public String structuralKey() {
        return SCHEMA_VERSION + ":" + segment(graphPath) + "." + segment(nodeId) + "."
                + segment(functionName) + "." + line + "." + column;
    }

    @Override
    public int compareTo(FunctionInvocationSite other) {
        Objects.requireNonNull(other, "other");
        return structuralKey().compareTo(other.structuralKey());
    }

    @Override
    public String toString() {
        return "FunctionInvocationSite[" + structuralKey() + "]";
    }

    private static String normalizeGraphPath(String value) {
        String path = FunctionValueSupport.text(value, true,
                FunctionControlException.Code.SITE_INVALID);
        if (!path.startsWith("/") || path.length() > FunctionValueSupport.MAX_STRING_LENGTH
                || path.contains("//") || path.endsWith("/")) {
            throw new FunctionControlException(FunctionControlException.Code.SITE_INVALID);
        }
        for (String segment : path.substring(1).split("/", -1)) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new FunctionControlException(FunctionControlException.Code.SITE_INVALID);
            }
        }
        return path;
    }

    private static String segment(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
