package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Double-observed online authority for one read-only Shadow execution window.
 *
 * <p>An admission joins three independently owned decisions: Data Governance sampling,
 * operational kill switch, and deployment egress isolation. Confirmation re-resolves the first
 * two and confirms the deployment decision after both observations. A caller-provided artifact
 * reference is only a lookup coordinate; it is not positive authority.</p>
 */
public interface ReadOnlyShadowAccessAuthority {
    /** @return whether all online authorities can currently attempt fresh decisions */
    boolean ready();

    /**
     * Captures the exact positive authority before any connector is invoked.
     *
     * @param permit immutable durable execution coordinates
     * @return frozen joined admission
     * @throws ReadOnlyShadowDataPlane.Failure on any unavailable or negative decision
     */
    Admission admit(ReadOnlyShadowDataPlane.Permit permit);

    /**
     * Re-observes the same decisions after paired execution.
     *
     * @param admission exact pre-execution admission
     * @param startedAt local execution start
     * @param completedAt latest source completion
     * @return terminal authority closure
     * @throws ReadOnlyShadowDataPlane.Failure when any decision changed or expired
     */
    Confirmation confirm(
            Admission admission,
            Instant startedAt,
            Instant completedAt);

    /**
     * Frozen pre-execution authority closure.
     *
     * @param admissionFingerprint domain-separated identity of the joined decision
     * @param accessProof exact zero-write proof coordinates admitted for this sample
     * @param guardLimits shared external pressure and circuit limits
     * @param samplingGrant verified sampling decision
     * @param killSwitch verified enabled switch decision
     * @param egressAdmission verified deployment isolation admission
     * @param admittedAt trusted joined-decision time
     * @param validUntil exclusive earliest authority/deadline expiry
     */
    record Admission(
            String admissionFingerprint,
            ReadOnlyShadowComparison.AccessProof accessProof,
            ReadOnlyShadowExecutionGuard.Limits guardLimits,
            ReadOnlyShadowSamplingGrantAuthority.Grant samplingGrant,
            ReadOnlyShadowKillSwitchAuthority.State killSwitch,
            MirrorDeploymentIsolationRunTrust.Admission egressAdmission,
            Instant admittedAt,
            Instant validUntil
    ) {
        private static final Pattern FINGERPRINT =
                Pattern.compile("sha256:[a-f0-9]{64}");

        /** Validates exact cross-authority references and a positive common validity window. */
        public Admission {
            admissionFingerprint = admissionFingerprint == null
                    ? "" : admissionFingerprint.trim();
            accessProof = Objects.requireNonNull(
                    accessProof, "accessProof");
            guardLimits = Objects.requireNonNull(
                    guardLimits, "guardLimits");
            samplingGrant = Objects.requireNonNull(
                    samplingGrant, "samplingGrant");
            killSwitch = Objects.requireNonNull(
                    killSwitch, "killSwitch");
            egressAdmission = Objects.requireNonNull(
                    egressAdmission, "egressAdmission");
            admittedAt = Objects.requireNonNull(
                    admittedAt, "admittedAt");
            validUntil = Objects.requireNonNull(
                    validUntil, "validUntil");
            if (!FINGERPRINT.matcher(
                    admissionFingerprint).matches()
                    || !samplingGrant.scope().equals(
                    killSwitch.scope())
                    || !samplingGrant.scope().equals(
                    egressAdmission.scope())
                    || !accessProof.samplingGrantRef().equals(
                    samplingGrant.grantRef())
                    || !accessProof.killSwitchRef().equals(
                    killSwitch.killSwitchRef())
                    || !accessProof.egressAuthorityRef().equals(
                    egressAdmission.attestationRef())
                    || accessProof.maximumSamples()
                    != samplingGrant.maximumSamples()
                    || !guardLimits.equals(
                    samplingGrant.limits())
                    || !killSwitch.enabled()
                    || !validUntil.isAfter(admittedAt)
                    || validUntil.isAfter(
                    samplingGrant.expiresAt())
                    || validUntil.isAfter(
                    killSwitch.expiresAt())
                    || validUntil.isAfter(
                    egressAdmission.validUntil())) {
                throw new IllegalArgumentException(
                        "read-only Shadow access admission is inconsistent");
            }
        }

        /** @return exact enterprise execution scope shared by all authorities */
        public CapabilitySnapshot.Scope scope() {
            return samplingGrant.scope();
        }
    }

    /**
     * Post-execution authority closure.
     *
     * @param admissionFingerprint exact joined admission identity
     * @param samplingGrant fresh observation of the same grant decision
     * @param killSwitch fresh observation of the same enabled switch generation
     * @param egressBinding deployment decision confirmed across the execution window
     * @param confirmedAt trusted terminal confirmation time
     */
    record Confirmation(
            String admissionFingerprint,
            ReadOnlyShadowSamplingGrantAuthority.Grant samplingGrant,
            ReadOnlyShadowKillSwitchAuthority.State killSwitch,
            MirrorDeploymentIsolationRunTrust.Binding egressBinding,
            Instant confirmedAt
    ) {
        private static final Pattern FINGERPRINT =
                Pattern.compile("sha256:[a-f0-9]{64}");

        /** Validates a complete payload-free terminal authority observation. */
        public Confirmation {
            admissionFingerprint = admissionFingerprint == null
                    ? "" : admissionFingerprint.trim();
            samplingGrant = Objects.requireNonNull(
                    samplingGrant, "samplingGrant");
            killSwitch = Objects.requireNonNull(
                    killSwitch, "killSwitch");
            egressBinding = Objects.requireNonNull(
                    egressBinding, "egressBinding");
            confirmedAt = Objects.requireNonNull(
                    confirmedAt, "confirmedAt");
            if (!FINGERPRINT.matcher(
                    admissionFingerprint).matches()
                    || !samplingGrant.scope().equals(
                    killSwitch.scope())
                    || !killSwitch.enabled()
                    || !confirmedAt.equals(
                    egressBinding.confirmedAt())
                    || confirmedAt.isBefore(
                    samplingGrant.observedAt())
                    || confirmedAt.isBefore(
                    killSwitch.observedAt())
                    || !samplingGrant.expiresAt()
                    .isAfter(confirmedAt)
                    || !killSwitch.expiresAt()
                    .isAfter(confirmedAt)) {
                throw new IllegalArgumentException(
                        "read-only Shadow access confirmation is inconsistent");
            }
        }
    }

    /** Creates a fail-closed placeholder. */
    static ReadOnlyShadowAccessAuthority unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Fail-closed singleton. */
    final class Unavailable
            implements ReadOnlyShadowAccessAuthority {
        private static final Unavailable INSTANCE =
                new Unavailable();

        private Unavailable() {
        }

        @Override
        public boolean ready() {
            return false;
        }

        @Override
        public Admission admit(
                ReadOnlyShadowDataPlane.Permit permit) {
            Objects.requireNonNull(permit, "permit");
            throw new ReadOnlyShadowDataPlane.Failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .ADMISSION_AUTHORITY_UNAVAILABLE);
        }

        @Override
        public Confirmation confirm(
                Admission admission,
                Instant startedAt,
                Instant completedAt) {
            Objects.requireNonNull(
                    admission, "admission");
            Objects.requireNonNull(
                    startedAt, "startedAt");
            Objects.requireNonNull(
                    completedAt, "completedAt");
            throw new ReadOnlyShadowDataPlane.Failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .ADMISSION_AUTHORITY_UNAVAILABLE);
        }
    }
}
