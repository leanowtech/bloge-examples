package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Admission and exact-resolution service for detached Shadow source bindings.
 *
 * <p>Publication does not trust a candidate evidence reference supplied by the caller. It
 * independently loads the signed bundle and closes scope, run, plan, target, request context,
 * completion time, and content address before the source-binding authority signs anything.</p>
 */
public final class ReadOnlyShadowSourceBindingService {
    private static final Duration MAXIMUM_CLOCK_SKEW =
            Duration.ofMinutes(2);

    private final ReadOnlyShadowSourceBindingRepository bindings;
    private final MirrorEvidenceRepository evidence;
    private final ReadOnlyShadowSourceBindingIntegrity integrity;
    private final Clock clock;

    /**
     * Creates a detached source-binding service.
     *
     * @param bindings append-only signed binding repository
     * @param evidence independently verified candidate evidence repository
     * @param integrity source-binding signing and verification boundary
     * @param clock trusted service clock
     */
    public ReadOnlyShadowSourceBindingService(
            ReadOnlyShadowSourceBindingRepository bindings,
            MirrorEvidenceRepository evidence,
            ReadOnlyShadowSourceBindingIntegrity integrity,
            Clock clock) {
        this.bindings = Objects.requireNonNull(
                bindings, "bindings");
        this.evidence = Objects.requireNonNull(
                evidence, "evidence");
        this.integrity = Objects.requireNonNull(
                integrity, "integrity");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Verifies the referenced candidate, signs the binding, and appends its exact revision.
     *
     * @param binding caller-supplied unsigned or already signed binding
     * @return canonical persisted binding
     */
    public ReadOnlyShadowSourceBinding publish(
            ReadOnlyShadowSourceBinding binding) {
        ReadOnlyShadowSourceBinding exact =
                Objects.requireNonNull(binding, "binding");
        Instant now = clock.instant();
        if (!integrity.available()) {
            throw new Failure(Reason.AUTHORITY_UNAVAILABLE);
        }
        requireWindow(exact, now, true);
        MirrorEvidenceBundle candidate =
                evidence.find(
                        exact.scope(),
                        exact.candidateEvidenceRef().id())
                        .orElseThrow(() ->
                                new Failure(
                                        Reason.CANDIDATE_NOT_FOUND));
        requireCandidate(exact, candidate);
        try {
            return bindings.create(integrity.sign(exact));
        } catch (ReadOnlyShadowSourceBindingIntegrity.Violation unavailable) {
            throw new Failure(
                    unavailable.reason()
                            == ReadOnlyShadowSourceBindingIntegrity
                            .Reason.KEY_UNAVAILABLE
                            ? Reason.AUTHORITY_UNAVAILABLE
                            : Reason.BINDING_INVALID);
        } catch (IllegalArgumentException conflict) {
            throw new Failure(Reason.REVISION_CONFLICT);
        }
    }

    /**
     * Resolves one exact currently valid binding without latest-revision fallback.
     *
     * @param scope complete enterprise namespace
     * @param reference exact source-binding coordinates
     * @param observedAt trusted current connector time
     * @return verified current binding
     */
    public ReadOnlyShadowSourceBinding resolve(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef reference,
            Instant observedAt) {
        CapabilitySnapshot.Scope exactScope =
                Objects.requireNonNull(scope, "scope");
        MirrorArtifactRef exactRef =
                Objects.requireNonNull(reference, "reference");
        if (!ReadOnlyShadowSourceBinding.ARTIFACT_KIND.equals(
                exactRef.kind())) {
            throw new Failure(Reason.REFERENCE_MISMATCH);
        }
        ReadOnlyShadowSourceBinding binding =
                bindings.find(
                        exactScope,
                        exactRef.id(),
                        exactRef.revision())
                        .orElseThrow(() ->
                                new Failure(Reason.BINDING_NOT_FOUND));
        if (!exactRef.equals(binding.artifactRef())) {
            throw new Failure(Reason.REFERENCE_MISMATCH);
        }
        requireWindow(
                binding,
                Objects.requireNonNull(observedAt, "observedAt"),
                false);
        return binding;
    }

    /**
     * Resolves one exact binding against the service clock.
     *
     * @param scope complete enterprise namespace
     * @param reference exact source-binding coordinates
     * @return verified currently valid binding
     */
    public ReadOnlyShadowSourceBinding resolve(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef reference) {
        return resolve(scope, reference, clock.instant());
    }

    /**
     * Probes the exact source-binding integrity authority.
     *
     * @return whether source-binding signing and verification authority is usable
     */
    public boolean ready() {
        return integrity.available();
    }

    private static void requireCandidate(
            ReadOnlyShadowSourceBinding binding,
            MirrorEvidenceBundle bundle) {
        MirrorRunEvidence candidate = bundle.evidence();
        MirrorArtifactRef exactRef = new MirrorArtifactRef(
                "MIRROR_EVIDENCE_BUNDLE",
                candidate.runId(),
                1,
                bundle.bundleFingerprint());
        if (!exactRef.equals(
                binding.candidateEvidenceRef())
                || !binding.scope().equals(candidate.scope())
                || !binding.candidatePlanRef().id().equals(
                candidate.planId())
                || !binding.candidatePlanRef().fingerprint().equals(
                candidate.planFingerprint())
                || !binding.targetCapabilityRef().equals(
                candidate.rootCapability())
                || !binding.requestContextFingerprint().equals(
                candidate.requestContextFingerprint())
                || candidate.completedAt().isAfter(
                binding.issuedAt())) {
            throw new Failure(Reason.CANDIDATE_MISMATCH);
        }
    }

    private static void requireWindow(
            ReadOnlyShadowSourceBinding binding,
            Instant observedAt,
            boolean publication) {
        if (observedAt.isBefore(
                binding.issuedAt().minus(MAXIMUM_CLOCK_SKEW))
                || !binding.expiresAt().isAfter(observedAt)
                || !publication
                && observedAt.isBefore(binding.validFrom())) {
            throw new Failure(Reason.WINDOW_REJECTED);
        }
    }

    /** Closed source-binding admission and resolution rejection vocabulary. */
    public enum Reason {
        /** Source-binding signing or verification authority is unavailable. */
        AUTHORITY_UNAVAILABLE,
        /** Binding structure, content address, or seal is invalid. */
        BINDING_INVALID,
        /** Exact binding revision does not exist. */
        BINDING_NOT_FOUND,
        /** Candidate evidence does not close over the registered binding coordinates. */
        CANDIDATE_MISMATCH,
        /** Exact candidate evidence bundle does not exist. */
        CANDIDATE_NOT_FOUND,
        /** Requested artifact reference differs from the stored binding. */
        REFERENCE_MISMATCH,
        /** Append-only binding identity and revision already exist. */
        REVISION_CONFLICT,
        /** Publication or resolution falls outside the binding validity window. */
        WINDOW_REJECTED
    }

    /** Stable payload-free source-binding service rejection. */
    public static final class Failure extends RuntimeException {
        /** Closed reason retained without source payloads. */
        private final Reason reason;

        /**
         * Creates one stable source-binding failure.
         *
         * @param reason closed rejection reason
         */
        public Failure(Reason reason) {
            super("Read-only Shadow source binding failed: "
                    + Objects.requireNonNull(reason, "reason").name());
            this.reason = reason;
        }

        /**
         * Returns the stable rejection reason.
         *
         * @return closed rejection reason
         */
        public Reason reason() {
            return reason;
        }
    }
}
