package com.leanowtech.bloge.gateway.testkit.acceptance;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable compilation result containing compiled plan bytes and Domain2 fingerprint.
 * <p>
 * Does not retain input references.
 * Does NOT contain PASS/ACCEPTED/formalPassCount/producer/runtime fields.
 */
public final class CompilationResult {

    private final byte[] compiledPlanBytes;
    private final String compiledPlanFingerprint;

    CompilationResult(byte[] compiledPlanBytes, String compiledPlanFingerprint) {
        this.compiledPlanBytes      = clone(Objects.requireNonNull(compiledPlanBytes,    "compiledPlanBytes"));
        this.compiledPlanFingerprint = Objects.requireNonNull(compiledPlanFingerprint, "compiledPlanFingerprint");
    }

    /**
     * Returns a fresh copy of the compiled plan IR bytes.
     *
     * @return canonical compiled plan bytes (never null)
     */
    public byte[] compiledPlanBytes()     { return clone(compiledPlanBytes); }

    /**
     * Returns the Domain2 fingerprint of the compiled plan.
     *
     * @return "sha256:" + 64 hex digits
     */
    public String compiledPlanFingerprint() { return compiledPlanFingerprint; }

    private static byte[] clone(byte[] original) {
        if (original == null) return null;
        byte[] copy = new byte[original.length];
        System.arraycopy(original, 0, copy, 0, original.length);
        return copy;
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CompilationResult that = (CompilationResult) o;
        return Arrays.equals(compiledPlanBytes, that.compiledPlanBytes)
            && compiledPlanFingerprint.equals(that.compiledPlanFingerprint);
    }

    @Override public int hashCode() {
        int r = 31 * Arrays.hashCode(compiledPlanBytes);
        return r + compiledPlanFingerprint.hashCode();
    }

    @Override public String toString() {
        return "CompilationResult{fingerprint=" + compiledPlanFingerprint +
               ", size=" + compiledPlanBytes.length + '}';
    }
}
