package com.leanowtech.bloge.gateway.integration.mirror;

/**
 * Readiness contract for one independently verified Domain Fidelity measurement authority.
 *
 * <p>The contract deliberately exposes no generic untyped payload. Each authority keeps a
 * source-specific request API and returns the shared payload-free {@link
 * DomainFidelityProfileProjector.Measurement} protocol only after it has verified its own source
 * closure.</p>
 */
public interface DomainFidelityMeasurementSource {
    /** Closed source authorities admitted by the first projection runtime. */
    enum Type {
        SCENARIO_REHEARSAL,
        READ_ONLY_SHADOW,
        AUTHORITATIVE_OUTCOME
    }

    /** @return exact authority represented by this adapter */
    Type type();

    /** @return whether the adapter can currently verify its complete source trust chain */
    boolean ready();
}
