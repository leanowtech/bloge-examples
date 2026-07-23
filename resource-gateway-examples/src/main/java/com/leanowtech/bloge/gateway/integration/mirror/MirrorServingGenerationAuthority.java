package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * External current-floor authority for governed serving generations.
 *
 * <p>The admission call returns a current token for one exact dependency closure. The floor call
 * returns the stream's current token even when its dependency closure differs, allowing replicas
 * holding an older plan to detect revocation or replacement. Implementations are expected to use
 * authenticated regional transport and a linearizable or otherwise explicitly bounded floor; the
 * returned token is still independently verified against
 * {@link MirrorServingGenerationTrustProvider}.</p>
 */
public interface MirrorServingGenerationAuthority {
    /** @return whether the authority endpoint is currently usable */
    boolean available();

    /**
     * Admits one newly materialized generation only if it is the current authority floor.
     *
     * @param request exact scope, purpose, dependency closure, and required horizon
     * @return current, rejected, or unavailable result
     */
    Resolution admit(AdmissionRequest request);

    /**
     * Reads the current floor for a previously admitted stream.
     *
     * @param request exact stream, scope, and purpose
     * @return current, rejected, or unavailable result
     */
    Resolution currentFloor(FloorRequest request);

    /** Returns an explicit fail-closed authority. */
    static MirrorServingGenerationAuthority unavailable() {
        return new MirrorServingGenerationAuthority() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public Resolution admit(AdmissionRequest request) {
                return Resolution.unavailable(
                        "SERVING_GENERATION_AUTHORITY_UNAVAILABLE");
            }

            @Override
            public Resolution currentFloor(FloorRequest request) {
                return Resolution.unavailable(
                        "SERVING_GENERATION_AUTHORITY_UNAVAILABLE");
            }
        };
    }

    /** Closed authority outcomes. */
    enum Outcome {
        CURRENT,
        REJECTED,
        UNAVAILABLE
    }

    /**
     * Initial generation admission request.
     *
     * @param scope exact enterprise scope
     * @param authorizedPurpose exact mirror purpose
     * @param dependencyClosureFingerprint payload-free materialized dependency closure
     * @param requiredUntil plan horizon the token must cover
     */
    record AdmissionRequest(
            CapabilitySnapshot.Scope scope,
            String authorizedPurpose,
            String dependencyClosureFingerprint,
            Instant requiredUntil
    ) {
        /** Validates complete bounded admission coordinates. */
        public AdmissionRequest {
            scope = Objects.requireNonNull(scope, "scope");
            authorizedPurpose = required(
                    authorizedPurpose, "authorizedPurpose");
            dependencyClosureFingerprint = fingerprint(
                    dependencyClosureFingerprint,
                    "dependencyClosureFingerprint");
            requiredUntil = Objects.requireNonNull(requiredUntil, "requiredUntil");
        }
    }

    /**
     * Runtime floor lookup.
     *
     * @param streamId exact authority stream
     * @param scope exact enterprise scope
     * @param authorizedPurpose exact mirror purpose
     */
    record FloorRequest(
            String streamId,
            CapabilitySnapshot.Scope scope,
            String authorizedPurpose
    ) {
        /** Validates complete bounded floor coordinates. */
        public FloorRequest {
            streamId = required(streamId, "streamId");
            scope = Objects.requireNonNull(scope, "scope");
            authorizedPurpose = required(
                    authorizedPurpose, "authorizedPurpose");
        }
    }

    /**
     * Authority response without unbounded diagnostics.
     *
     * @param outcome closed response outcome
     * @param token signed token only for CURRENT
     * @param reasonCode bounded stable provider reason
     */
    record Resolution(
            Outcome outcome,
            MirrorServingGenerationToken token,
            String reasonCode
    ) {
        /** Enforces one token only for a current response. */
        public Resolution {
            outcome = Objects.requireNonNull(outcome, "outcome");
            reasonCode = normalized(reasonCode);
            if ((outcome == Outcome.CURRENT) != (token != null)
                    || reasonCode.length() > 128) {
                throw new IllegalArgumentException(
                        "serving-generation authority result is invalid");
            }
        }

        /** @return current signed floor */
        public static Resolution current(MirrorServingGenerationToken token) {
            return new Resolution(
                    Outcome.CURRENT, Objects.requireNonNull(token, "token"), "");
        }

        /** @return policy or coordinate rejection */
        public static Resolution rejected(String reasonCode) {
            return new Resolution(Outcome.REJECTED, null, reasonCode);
        }

        /** @return authority outage */
        public static Resolution unavailable(String reasonCode) {
            return new Resolution(Outcome.UNAVAILABLE, null, reasonCode);
        }
    }

    private static String fingerprint(String value, String field) {
        String exact = required(value, field);
        if (!Pattern.matches("sha256:[a-f0-9]{64}", exact)) {
            throw new IllegalArgumentException(
                    field + " must be a canonical SHA-256 fingerprint");
        }
        return exact;
    }

    private static String required(String value, String field) {
        String exact = normalized(value);
        if (exact.isBlank() || exact.length() > 512) {
            throw new IllegalArgumentException(
                    field + " must be non-blank and bounded");
        }
        return exact;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
