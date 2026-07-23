package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorServingGenerationFence;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedCorpusPayloads;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Binds a materialized corpus payload owner to the external authority's current generation.
 *
 * <p>The service fingerprints only payload-free content-addressed dependencies, requests a current
 * signed token, independently verifies it against operator-owned trust, and installs a runtime
 * fence into the existing payload owner. It never mints fallback tokens. Authority outage,
 * rejection, unknown keys, invalid signatures, and coordinate drift reject the complete
 * generation before compilation.</p>
 */
public final class MirrorServingGenerationService {
    private final MirrorServingGenerationAuthority authority;
    private final MirrorServingGenerationTrustProvider trust;
    private final MirrorServingGenerationIntegrity integrity;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final MirrorServingGenerationTelemetry telemetry;

    /**
     * Creates a generation-binding boundary.
     *
     * @param authority external admission and current-floor authority
     * @param trust operator-owned pinned authority keys
     * @param integrity canonical token verifier
     * @param mapper canonical protocol mapper
     * @param clock trusted admission clock
     */
    public MirrorServingGenerationService(
            MirrorServingGenerationAuthority authority,
            MirrorServingGenerationTrustProvider trust,
            MirrorServingGenerationIntegrity integrity,
            ObjectMapper mapper,
            Clock clock) {
        this(authority, trust, integrity, mapper, clock,
                MirrorServingGenerationTelemetry.noop());
    }

    /**
     * Creates a generation-binding boundary with fixed-cardinality telemetry.
     *
     * @param authority external admission and current-floor authority
     * @param trust operator-owned pinned authority keys
     * @param integrity canonical token verifier
     * @param mapper canonical protocol mapper
     * @param clock trusted admission clock
     * @param telemetry bounded admission and floor-check metrics
     */
    public MirrorServingGenerationService(
            MirrorServingGenerationAuthority authority,
            MirrorServingGenerationTrustProvider trust,
            MirrorServingGenerationIntegrity integrity,
            ObjectMapper mapper,
            Clock clock,
            MirrorServingGenerationTelemetry telemetry) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.trust = Objects.requireNonNull(trust, "trust");
        this.integrity = Objects.requireNonNull(integrity, "integrity");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    /**
     * Admits and binds one non-empty recorded-corpus generation.
     *
     * <p>Ownership remains with the caller until this method returns. When an exception escapes,
     * the caller must close the supplied payload generation.</p>
     *
     * @param payloads exact materialized corpus owner
     * @param scope authenticated enterprise scope
     * @param authorizedPurpose server-minted mirror purpose
     * @param requiredUntil hard plan horizon
     * @return same payload owner with a verified current-floor fence
     * @throws AdmissionException when the authority or token cannot authorize compilation
     */
    public ResolvedCorpusPayloads bind(
            ResolvedCorpusPayloads payloads,
            CapabilitySnapshot.Scope scope,
            String authorizedPurpose,
            Instant requiredUntil) {
        ResolvedCorpusPayloads exact =
                Objects.requireNonNull(payloads, "payloads");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(requiredUntil, "requiredUntil");
        if (exact.isEmpty()) {
            return exact;
        }
        String dependencyFingerprint = ProtocolFingerprint.of(
                mapper, exact.generationDependencies());
        MirrorServingGenerationAuthority.Resolution result;
        try {
            result = authority.admit(
                    new MirrorServingGenerationAuthority.AdmissionRequest(
                            scope, authorizedPurpose, dependencyFingerprint,
                            requiredUntil));
        } catch (RuntimeException failure) {
            telemetry.record(
                    MirrorServingGenerationTelemetry.Check.MATERIALIZATION,
                    MirrorServingGenerationTelemetry.Outcome.UNAVAILABLE);
            throw new AdmissionException(
                    "SERVING_GENERATION_AUTHORITY_UNAVAILABLE",
                    "Serving-generation authority is unavailable.");
        }
        if (result == null
                || result.outcome()
                == MirrorServingGenerationAuthority.Outcome.UNAVAILABLE) {
            telemetry.record(
                    MirrorServingGenerationTelemetry.Check.MATERIALIZATION,
                    MirrorServingGenerationTelemetry.Outcome.UNAVAILABLE);
            throw new AdmissionException(
                    "SERVING_GENERATION_AUTHORITY_UNAVAILABLE",
                    "Serving-generation authority is unavailable.");
        }
        if (result.outcome()
                == MirrorServingGenerationAuthority.Outcome.REJECTED) {
            telemetry.record(
                    MirrorServingGenerationTelemetry.Check.MATERIALIZATION,
                    MirrorServingGenerationTelemetry.Outcome.REJECTED);
            throw new AdmissionException(
                    "SERVING_GENERATION_REJECTED",
                    "Serving-generation authority rejected the dependency closure.");
        }
        Instant now = clock.instant();
        try {
            integrity.verify(
                    result.token(),
                    trust,
                    new MirrorServingGenerationIntegrity.Expectation(
                            scope, authorizedPurpose, dependencyFingerprint,
                            requiredUntil),
                    now);
        } catch (MirrorServingGenerationIntegrity.TrustUnavailableException
                 unavailable) {
            telemetry.record(
                    MirrorServingGenerationTelemetry.Check.MATERIALIZATION,
                    MirrorServingGenerationTelemetry.Outcome.UNAVAILABLE);
            throw new AdmissionException(
                    "SERVING_GENERATION_AUTHORITY_UNAVAILABLE",
                    "Serving-generation trust policy is unavailable.");
        } catch (IllegalArgumentException invalid) {
            telemetry.record(
                    MirrorServingGenerationTelemetry.Check.MATERIALIZATION,
                    MirrorServingGenerationTelemetry.Outcome.INVALID);
            throw new AdmissionException(
                    "SERVING_GENERATION_TOKEN_INVALID",
                    "Serving-generation authority token failed local verification.");
        }
        telemetry.record(
                MirrorServingGenerationTelemetry.Check.MATERIALIZATION,
                MirrorServingGenerationTelemetry.Outcome.CURRENT);
        return exact.withServingGeneration(
                new MirrorServingGenerationFence(
                        result.token(), authority, trust, integrity, clock,
                        telemetry));
    }

    /** @return whether authority and pinned trust are currently available */
    public boolean ready() {
        try {
            return authority.available() && trust.available();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * Stable payload-free generation-admission failure.
     *
     * <p>Messages deliberately omit authority diagnostics, artifact ids, fingerprints, and
     * payload coordinates. Adapters may map {@link #code()} to fixed HTTP and telemetry reasons.</p>
     */
    public static final class AdmissionException extends RuntimeException {
        private final String code;

        /** Creates a bounded generation-admission failure. */
        public AdmissionException(String code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
        }

        /** @return stable machine-readable failure code */
        public String code() {
            return code;
        }
    }
}
