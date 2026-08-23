package com.leanowtech.bloge.gateway.testkit.acceptance;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable result of a compiled plan verification.
 *
 * <p>All byte-returning accessors return fresh defensive copies.
 * No mutable {@code JsonNode} or other stateful object is exposed.</p>
 *
 * <p>Instances are produced exclusively by
 * {@link CapabilityStudioCompiledPlanVerifier#verify(byte[], byte[], byte[])}.</p>
 */
public final class CompiledPlanVerificationResult {

    /** Verification status. */
    public enum Status {
        /** The compiled plan IR is consistent with independently-recomputed static facts. */
        VERIFIED,
        /** The compiled plan IR failed one or more static checks. */
        INVALID,
        /** A required packaged resource was genuinely absent from the classpath. */
        UNAVAILABLE
    }

    private final byte[] verificationResultBytes;
    private final Status status;
    private final boolean verified;
    private final String planId;
    private final Integer revision;
    private final String verificationFingerprint;
    private final String expectedCompiledPlanFingerprint;
    private final String recomputedCompiledPlanFingerprint;
    private final String reasonCode;
    private final String reasonField;
    private final boolean catalogRefVerified;
    private final boolean catalogRawFingerprintVerified;
    private final boolean catalogSemanticFingerprintVerified;
    private final boolean planFingerprintVerified;
    private final boolean phaseBarrierVerified;
    private final boolean dependencyDagVerified;
    private final boolean effectBarrierVerified;
    private final boolean canonicalMatrixCellCountVerified;
    private final boolean stageExitContractCountVerified;

    /**
     * Constructs an immutable result snapshot from the given builder.
     * @param b populated builder; must not be null
     */
    CompiledPlanVerificationResult(Builder b) {
        this.verificationResultBytes            = b.verificationResultBytes.clone();
        this.status                             = b.status;
        this.verified                          = b.verified;
        this.planId                            = b.planId;
        this.revision                          = b.revision;
        this.verificationFingerprint            = b.verificationFingerprint;
        this.expectedCompiledPlanFingerprint    = b.expectedCompiledPlanFingerprint;
        this.recomputedCompiledPlanFingerprint  = b.recomputedCompiledPlanFingerprint;
        this.reasonCode                        = b.reasonCode;
        this.reasonField                       = b.reasonField;
        this.catalogRefVerified                 = b.catalogRefVerified;
        this.catalogRawFingerprintVerified      = b.catalogRawFingerprintVerified;
        this.catalogSemanticFingerprintVerified  = b.catalogSemanticFingerprintVerified;
        this.planFingerprintVerified            = b.planFingerprintVerified;
        this.phaseBarrierVerified              = b.phaseBarrierVerified;
        this.dependencyDagVerified              = b.dependencyDagVerified;
        this.effectBarrierVerified             = b.effectBarrierVerified;
        this.canonicalMatrixCellCountVerified  = b.canonicalMatrixCellCountVerified;
        this.stageExitContractCountVerified     = b.stageExitContractCountVerified;
    }

    /**
     * Returns the canonical JSON result bytes.
     * @return a fresh copy of the serialized result body; never null
     */
    public byte[] verificationResultBytes() { return verificationResultBytes.clone(); }

    /**
     * Returns the verification outcome status.
     * @return one of {@link Status#VERIFIED}, {@link Status#INVALID}, or {@link Status#UNAVAILABLE}; never null
     */
    public Status status()              { return status; }
    /**
     * Convenience predicate: true iff {@link #status()} is {@link Status#VERIFIED}.
     * @return true when the compiled plan passed all static checks
     */
    public boolean verified()            { return verified; }
    /**
     * The plan identifier from the compiled plan wire document.
     * @return the planId, or null when the compiled plan could not be parsed
     */
    public String planId()             { return planId; }
    /**
     * The plan revision number from the compiled plan wire document.
     * @return the revision, or null when the compiled plan could not be parsed
     */
    public Integer revision()           { return revision; }
    /**
     * The canonical JSON Domain2 fingerprint of this verification result body.
     * Recomputed independently by the verifier on every call; never null.
     * @return the verification fingerprint in the form {@code sha256:…}
     */
    public String verificationFingerprint()            { return verificationFingerprint; }
    /**
     * The compiled plan fingerprint declared in the supplied wire document.
     * Populated when the compiled plan was successfully parsed (always for VERIFIED and INVALID,
     * absent for UNAVAILABLE when the compiled plan could not be read).
     * @return the declared compiled plan fingerprint, or null when the wire could not be parsed
     */
    public String expectedCompiledPlanFingerprint()   { return expectedCompiledPlanFingerprint; }
    /**
     * The verifier's independently recomputed compiled-plan fingerprint.
     * @return the recomputed fingerprint, or null when verification failed before recomputation
     */
    public String recomputedCompiledPlanFingerprint() { return recomputedCompiledPlanFingerprint; }
    /**
     * Machine-readable failure code. Null for {@link Status#VERIFIED}.
     * @return one of the defined reason codes, or null
     */
    public String reasonCode()          { return reasonCode; }
    /**
     * RFC 6901 JSON Pointer to the first detected field that caused failure.
     * Null for {@link Status#VERIFIED} and always absent for {@link Status#UNAVAILABLE}.
     * @return the failure pointer, or null
     */
    public String reasonField()         { return reasonField; }
    /**
     * True when the plan's {@code catalogRef} binding matched {@code catalogId + "@" + catalogRawFingerprint}.
     * Always true for {@link Status#VERIFIED}; may be true or false for {@link Status#INVALID}.
     * @return true when the catalog reference binding is consistent
     */
    public boolean catalogRefVerified()                     { return catalogRefVerified; }
    /**
     * True when the compiled plan's {@code catalogRawFingerprint} matches the independently computed raw fingerprint of the supplied catalog bytes.
     * @return true when the catalog raw fingerprint is consistent
     */
    public boolean catalogRawFingerprintVerified()          { return catalogRawFingerprintVerified; }
    /**
     * True when the compiled plan's {@code catalogSemanticFingerprint} matches the independently computed semantic fingerprint of the catalog.
     * @return true when the catalog semantic fingerprint is consistent
     */
    public boolean catalogSemanticFingerprintVerified()     { return catalogSemanticFingerprintVerified; }
    /**
     * True when the compiled plan's {@code planSourceSemanticFingerprint} matches the independently computed semantic fingerprint of the plan.
     * @return true when the plan semantic fingerprint is consistent
     */
    public boolean planFingerprintVerified()              { return planFingerprintVerified; }
    /**
     * True when the compiled plan's {@code phaseBarriers} array exactly matches the profile-defined barriers.
     * @return true when all phase barriers are semantically correct
     */
    public boolean phaseBarrierVerified()                { return phaseBarrierVerified; }
    /**
     * True when the compiled plan's {@code primitiveContracts.dependsOn} graph is acyclic and all referenced nodes exist.
     * @return true when the dependency DAG is valid
     */
    public boolean dependencyDagVerified()                { return dependencyDagVerified; }
    /**
     * True when every effect-class transition in the primitive DAG respects the profile-defined phase barriers.
     * @return true when all effect-class barrier constraints are satisfied
     */
    public boolean effectBarrierVerified()                { return effectBarrierVerified; }
    /**
     * True when the compiled plan's {@code canonicalMatrixCellCount} equals the profile-expected count (27 for FELT).
     * @return true when the canonical matrix cell count is correct
     */
    public boolean canonicalMatrixCellCountVerified()    { return canonicalMatrixCellCountVerified; }
    /**
     * True when the compiled plan's {@code stageExitContractCount} equals the profile-expected count (27 for FELT).
     * @return true when the stage-exit contract count is correct
     */
    public boolean stageExitContractCountVerified()       { return stageExitContractCountVerified; }

    /**
     * Structural equality: two results are equal when all field values match.
     * Verification fingerprints are compared by value; no identity dependence.
     * @param o the object to compare
     * @return true when every field of this result equals the corresponding field of {@code o}
     */
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CompiledPlanVerificationResult that = (CompiledPlanVerificationResult) o;
        return verified == that.verified
                && status == that.status
                && catalogRefVerified == that.catalogRefVerified
                && catalogRawFingerprintVerified == that.catalogRawFingerprintVerified
                && catalogSemanticFingerprintVerified == that.catalogSemanticFingerprintVerified
                && planFingerprintVerified == that.planFingerprintVerified
                && phaseBarrierVerified == that.phaseBarrierVerified
                && dependencyDagVerified == that.dependencyDagVerified
                && effectBarrierVerified == that.effectBarrierVerified
                && canonicalMatrixCellCountVerified == that.canonicalMatrixCellCountVerified
                && stageExitContractCountVerified == that.stageExitContractCountVerified
                && Arrays.equals(verificationResultBytes, that.verificationResultBytes)
                && Objects.equals(planId, that.planId)
                && Objects.equals(revision, that.revision)
                && Objects.equals(verificationFingerprint, that.verificationFingerprint)
                && Objects.equals(expectedCompiledPlanFingerprint, that.expectedCompiledPlanFingerprint)
                && Objects.equals(recomputedCompiledPlanFingerprint, that.recomputedCompiledPlanFingerprint)
                && Objects.equals(reasonCode, that.reasonCode)
                && Objects.equals(reasonField, that.reasonField);
    }

    @Override public int hashCode() {
        int r = 31 * Boolean.hashCode(verified) + status.hashCode();
        r = 31 * r + Arrays.hashCode(verificationResultBytes);
        r = 31 * r + Objects.hashCode(planId);
        r = 31 * r + Objects.hashCode(revision);
        r = 31 * r + Objects.hashCode(verificationFingerprint);
        r = 31 * r + Objects.hashCode(expectedCompiledPlanFingerprint);
        r = 31 * r + Objects.hashCode(recomputedCompiledPlanFingerprint);
        r = 31 * r + Objects.hashCode(reasonCode);
        r = 31 * r + Objects.hashCode(reasonField);
        return r;
    }

    /**
     * Returns a concise string representation for debugging and logging.
     * Includes the verification fingerprint for traceability.
     * @return a string in the form {@code CompiledPlanVerificationResult{status=..., verified=..., verificationFingerprint=..., reasonCode=..., reasonField=...}}
     */
    @Override public String toString() {
        return "CompiledPlanVerificationResult{status=" + status + ", verified=" + verified
                + ", verificationFingerprint=" + verificationFingerprint
                + ", reasonCode=" + reasonCode + ", reasonField=" + reasonField + '}';
    }


    /** Mutable builder — internal to CapabilityStudioCompiledPlanVerifier. */
    static final class Builder {
        byte[] verificationResultBytes            = new byte[0];
        Status  status;
        boolean  verified;
        String   planId;
        Integer  revision;
        String   verificationFingerprint;
        String   expectedCompiledPlanFingerprint;
        String   recomputedCompiledPlanFingerprint;
        String   reasonCode;
        String   reasonField;
        boolean  catalogRefVerified;
        boolean  catalogRawFingerprintVerified;
        boolean  catalogSemanticFingerprintVerified;
        boolean  planFingerprintVerified;
        boolean  phaseBarrierVerified;
        boolean  dependencyDagVerified;
        boolean  effectBarrierVerified;
        boolean  canonicalMatrixCellCountVerified;
        boolean  stageExitContractCountVerified;

        CompiledPlanVerificationResult build() { return new CompiledPlanVerificationResult(this); }
    }
}
