package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Independent resolver and signature verifier for both terminal Shadow source artifacts.
 *
 * <p>The verifier must fetch each exact artifact revision, recheck its content address and
 * signature against current trusted keys, bind both artifacts to the same request context and
 * authority admission, and publish a signed payload-free resolution attestation. Merely checking
 * that two references are syntactically present is insufficient.</p>
 */
public interface ReadOnlyShadowSourceResolutionVerifier {
    /** @return whether both source registries, trust roots, and attestation signer are ready */
    boolean ready();

    /**
     * Re-resolves both source artifacts and publishes their exact trust closure.
     *
     * @param verification complete paired source and authority coordinates
     * @return signed source-resolution attestation reference
     */
    MirrorArtifactRef verify(Verification verification);

    /**
     * Complete payload-free source verification command.
     *
     * @param executionId stable connector idempotency identity
     * @param request immutable durable request
     * @param admission pre-execution online authority closure
     * @param confirmation post-execution authority closure
     * @param baseline baseline connector result
     * @param candidate candidate connector result
     */
    record Verification(
            String executionId,
            ReadOnlyShadowJobRequest request,
            ReadOnlyShadowAccessAuthority.Admission admission,
            ReadOnlyShadowAccessAuthority.Confirmation confirmation,
            ReadOnlyShadowConnectorObservation baseline,
            ReadOnlyShadowConnectorObservation candidate
    ) {
        private static final Pattern IDENTIFIER =
                Pattern.compile(
                        "[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");

        /** Validates exact policy, scope, role, and paired request-context closure. */
        public Verification {
            executionId = executionId == null
                    ? "" : executionId.trim();
            request = Objects.requireNonNull(
                    request, "request");
            admission = Objects.requireNonNull(
                    admission, "admission");
            confirmation = Objects.requireNonNull(
                    confirmation, "confirmation");
            baseline = Objects.requireNonNull(
                    baseline, "baseline");
            candidate = Objects.requireNonNull(
                    candidate, "candidate");
            if (!IDENTIFIER.matcher(executionId).matches()
                    || !request.scope().equals(
                    admission.scope())
                    || !admission.admissionFingerprint()
                    .equals(confirmation
                            .admissionFingerprint())
                    || !sameGrantDecision(
                    admission.samplingGrant(),
                    confirmation.samplingGrant())
                    || !sameKillSwitchDecision(
                    admission.killSwitch(),
                    confirmation.killSwitch())
                    || !sameEgressDecision(
                    admission.egressAdmission(),
                    confirmation.egressBinding())
                    || !admission.validUntil().isAfter(
                    confirmation.confirmedAt())
                    || baseline.source().role()
                    != ReadOnlyShadowComparison
                    .SourceRole.BASELINE
                    || candidate.source().role()
                    != ReadOnlyShadowComparison
                    .SourceRole.CANDIDATE
                    || !request.comparisonPolicyRef()
                    .equals(baseline
                            .comparisonPolicyRef())
                    || !request.comparisonPolicyRef()
                    .equals(candidate
                            .comparisonPolicyRef())
                    || !baseline.source().scope()
                    .equals(request.scope())
                    || !candidate.source().scope()
                    .equals(request.scope())
                    || !baseline.source()
                    .requestContextFingerprint()
                    .equals(candidate.source()
                            .requestContextFingerprint())
                    || !request.targetCapabilityRef()
                    .equals(baseline.source()
                            .targetCapabilityRef())
                    || !request.targetCapabilityRef()
                    .equals(candidate.source()
                            .targetCapabilityRef())
                    || baseline.source().completedAt()
                    .isBefore(admission.admittedAt())
                    || candidate.source().completedAt()
                    .isBefore(admission.admittedAt())
                    || confirmation.confirmedAt().isBefore(
                    baseline.source().completedAt())
                    || confirmation.confirmedAt().isBefore(
                    candidate.source().completedAt())) {
                throw new IllegalArgumentException(
                        "read-only Shadow source verification is inconsistent");
            }
        }

        private static boolean sameGrantDecision(
                ReadOnlyShadowSamplingGrantAuthority.Grant admitted,
                ReadOnlyShadowSamplingGrantAuthority.Grant confirmed) {
            return admitted.scope().equals(confirmed.scope())
                    && admitted.guardScope().equals(
                    confirmed.guardScope())
                    && admitted.grantRef().equals(
                    confirmed.grantRef())
                    && admitted.maximumSamples()
                    == confirmed.maximumSamples()
                    && admitted.validFrom().equals(
                    confirmed.validFrom())
                    && admitted.expiresAt().equals(
                    confirmed.expiresAt())
                    && admitted.guardPolicyRef().equals(
                    confirmed.guardPolicyRef())
                    && admitted.limits().equals(
                    confirmed.limits())
                    && admitted.authorityAttestationRef().equals(
                    confirmed.authorityAttestationRef())
                    && admitted.guardPolicyAttestationRef().equals(
                    confirmed.guardPolicyAttestationRef());
        }

        private static boolean sameKillSwitchDecision(
                ReadOnlyShadowKillSwitchAuthority.State admitted,
                ReadOnlyShadowKillSwitchAuthority.State confirmed) {
            return admitted.scope().equals(confirmed.scope())
                    && admitted.killSwitchRef().equals(
                    confirmed.killSwitchRef())
                    && confirmed.enabled()
                    && admitted.effectiveAt().equals(
                    confirmed.effectiveAt())
                    && admitted.expiresAt().equals(
                    confirmed.expiresAt())
                    && admitted.authorityAttestationRef().equals(
                    confirmed.authorityAttestationRef());
        }

        private static boolean sameEgressDecision(
                MirrorDeploymentIsolationRunTrust.Admission admitted,
                MirrorDeploymentIsolationRunTrust.Binding confirmed) {
            return admitted.decisionRef().equals(
                    confirmed.decisionRef())
                    && admitted.authorityKeySetRef().equals(
                    confirmed.authorityKeySetRef())
                    && admitted.attestationRef().equals(
                    confirmed.attestationRef())
                    && admitted.statusRef().equals(
                    confirmed.statusRef())
                    && admitted.admittedSnapshotRef().equals(
                    confirmed.admittedSnapshotRef())
                    && admitted.admittedAt().equals(
                    confirmed.admittedAt());
        }
    }

    /** Creates a fail-closed placeholder. */
    static ReadOnlyShadowSourceResolutionVerifier unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Fail-closed singleton. */
    final class Unavailable
            implements ReadOnlyShadowSourceResolutionVerifier {
        private static final Unavailable INSTANCE =
                new Unavailable();

        private Unavailable() {
        }

        @Override
        public boolean ready() {
            return false;
        }

        @Override
        public MirrorArtifactRef verify(
                Verification verification) {
            Objects.requireNonNull(
                    verification, "verification");
            throw new ReadOnlyShadowDataPlane.Failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .SOURCE_VERIFICATION_FAILED);
        }
    }
}
