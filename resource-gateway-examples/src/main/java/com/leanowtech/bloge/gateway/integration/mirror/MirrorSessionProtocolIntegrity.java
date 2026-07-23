package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Canonical closure and fingerprint boundary for stateful mirror session protocols.
 */
public final class MirrorSessionProtocolIntegrity {
    /** Maximum lifetime admitted by the public create protocol. */
    public static final Duration MAXIMUM_SESSION_LIFETIME = Duration.ofHours(24);
    /** Maximum canonical encrypted aggregate before encryption overhead. */
    public static final int MAXIMUM_PAYLOAD_BYTES = 256 * 1024 * 1024;

    private MirrorSessionProtocolIntegrity() {
    }

    /**
     * Seals and verifies one initial encrypted aggregate.
     *
     * @param mapper canonical protocol mapper
     * @param payload unsealed aggregate
     * @param now server admission time
     * @return sealed aggregate
     */
    public static MirrorSessionPayload sealInitial(
            ObjectMapper mapper, MirrorSessionPayload payload, Instant now) {
        Objects.requireNonNull(payload, "payload");
        MirrorSessionPayload sealed = payload.withFingerprint(
                ProtocolFingerprint.ofBounded(mapper, payload.withFingerprint(""),
                        MAXIMUM_PAYLOAD_BYTES));
        verifyInitial(mapper, sealed, now);
        return sealed;
    }

    /**
     * Verifies a sealed initial aggregate and all exact dependency closure.
     *
     * @param mapper canonical protocol mapper
     * @param payload sealed aggregate
     * @param now server admission time
     */
    public static void verifyInitial(
            ObjectMapper mapper, MirrorSessionPayload payload, Instant now) {
        verify(mapper, payload);
        SessionStateSpace state = payload.state();
        Instant admission = Objects.requireNonNull(now, "now");
        if (state.stateRevision() != 0
                || !state.committedEvents().isEmpty()
                || !state.processedCommands().isEmpty()) {
            throw new IllegalArgumentException(
                    "new session payload must start at revision zero with empty journals");
        }
        if (!state.expiresAt().isAfter(admission)
                || state.expiresAt().isAfter(
                admission.plus(MAXIMUM_SESSION_LIFETIME))) {
            throw new IllegalArgumentException(
                    "new session expiry must be in the next 24 hours");
        }
    }

    /**
     * Seals one updated aggregate after the transaction kernel has sealed its state.
     *
     * @param mapper canonical protocol mapper
     * @param payload unsealed aggregate carrying a sealed state
     * @return sealed aggregate
     */
    public static MirrorSessionPayload seal(
            ObjectMapper mapper, MirrorSessionPayload payload) {
        Objects.requireNonNull(payload, "payload");
        MirrorSessionPayload sealed = payload.withFingerprint(
                ProtocolFingerprint.ofBounded(mapper, payload.withFingerprint(""),
                        MAXIMUM_PAYLOAD_BYTES));
        verify(mapper, sealed);
        return sealed;
    }

    /**
     * Verifies aggregate fingerprint, model/effect closure, and state dependency closure.
     *
     * @param mapper canonical protocol mapper
     * @param payload sealed aggregate
     */
    public static void verify(ObjectMapper mapper, MirrorSessionPayload payload) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(payload, "payload");
        StateModelIntegrity.verify(mapper, payload.stateModel());
        for (StateReadSpec spec : payload.stateReadSpecs()) {
            StateReadSpecIntegrity.verify(mapper, spec, payload.stateModel());
        }
        for (WriteEffectSpec effect : payload.writeEffects()) {
            WriteEffectSpecIntegrity.verify(mapper, effect, payload.stateModel());
        }
        SessionStateSpaceIntegrity.verify(mapper, payload.state());
        SessionStateSpace state = payload.state();
        if (!state.scope().equals(payload.stateModel().scope())
                || !state.stateModelRef().equals(
                StateModelIntegrity.reference(payload.stateModel()))) {
            throw new IllegalArgumentException(
                    "session payload state does not bind its state model");
        }
        List<MirrorArtifactRef> effects = payload.writeEffects().stream()
                .map(WriteEffectSpecIntegrity::reference)
                .sorted(java.util.Comparator.comparing(MirrorArtifactRef::id)
                        .thenComparingLong(MirrorArtifactRef::revision))
                .toList();
        if (!state.writeEffectRefs().equals(effects)) {
            throw new IllegalArgumentException(
                    "session payload state does not bind the exact write-effect closure");
        }
        String expected = ProtocolFingerprint.ofBounded(
                mapper, payload.withFingerprint(""), MAXIMUM_PAYLOAD_BYTES);
        if (!expected.equals(payload.fingerprint())) {
            throw new IllegalArgumentException(
                    "session payload fingerprint mismatch");
        }
    }

    /**
     * Seals a payload-free descriptor.
     *
     * @param mapper canonical protocol mapper
     * @param descriptor unsealed descriptor
     * @return sealed descriptor
     */
    public static MirrorSessionDescriptor sealDescriptor(
            ObjectMapper mapper, MirrorSessionDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        MirrorSessionDescriptor sealed = descriptor.withFingerprint(
                ProtocolFingerprint.of(mapper, descriptor.withFingerprint("")));
        verifyDescriptor(mapper, sealed);
        return sealed;
    }

    /**
     * Verifies descriptor fingerprint and terminal-time semantics.
     *
     * @param mapper canonical protocol mapper
     * @param descriptor sealed descriptor
     */
    public static void verifyDescriptor(
            ObjectMapper mapper, MirrorSessionDescriptor descriptor) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(descriptor, "descriptor");
        String expected = ProtocolFingerprint.of(
                mapper, descriptor.withFingerprint(""));
        if (!expected.equals(descriptor.fingerprint())) {
            throw new IllegalArgumentException(
                    "mirror session descriptor fingerprint mismatch");
        }
    }

    /**
     * Computes the exact create idempotency fingerprint.
     *
     * @param mapper canonical protocol mapper
     * @param request strict create command
     * @return canonical request fingerprint
     */
    public static String createFingerprint(
            ObjectMapper mapper, MirrorSessionCreateRequest request) {
        return ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                Objects.requireNonNull(request, "request"),
                MAXIMUM_PAYLOAD_BYTES);
    }
}
