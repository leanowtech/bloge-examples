package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Composes independently probed Domain Fidelity source-adapter readiness.
 *
 * <p>Missing, duplicate, or throwing adapters fail closed. Projection can produce an explicitly
 * partial profile when at least one source authority is ready; individual source flags remain
 * visible so a consumer cannot mistake Scenario readiness for outcome calibration.</p>
 */
public final class DomainFidelitySourceAvailability {
    private final Map<DomainFidelityMeasurementSource.Type,
            DomainFidelityMeasurementSource> sources;

    /**
     * Creates one immutable adapter registry.
     *
     * @param sources assembled typed source adapters
     */
    public DomainFidelitySourceAvailability(
            List<DomainFidelityMeasurementSource> sources) {
        EnumMap<DomainFidelityMeasurementSource.Type,
                DomainFidelityMeasurementSource> indexed =
                new EnumMap<>(
                        DomainFidelityMeasurementSource.Type.class);
        for (DomainFidelityMeasurementSource source
                : sources == null ? List.<DomainFidelityMeasurementSource>of()
                : List.copyOf(sources)) {
            DomainFidelityMeasurementSource exact =
                    Objects.requireNonNull(source, "source");
            if (indexed.put(exact.type(), exact) != null) {
                throw new IllegalArgumentException(
                        "duplicate Domain Fidelity source adapter");
            }
        }
        this.sources = Map.copyOf(indexed);
    }

    /** @return whether one exact source authority is assembled and currently verifiable */
    public boolean ready(
            DomainFidelityMeasurementSource.Type type) {
        DomainFidelityMeasurementSource source =
                sources.get(Objects.requireNonNull(type, "type"));
        if (source == null) {
            return false;
        }
        try {
            return source.ready();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** @return whether at least one independently verified source can produce a partial profile */
    public boolean anyReady() {
        for (DomainFidelityMeasurementSource.Type type
                : DomainFidelityMeasurementSource.Type.values()) {
            if (ready(type)) {
                return true;
            }
        }
        return false;
    }
}
