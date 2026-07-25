package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityMeasurementSource;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelitySourceAvailability;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class DomainFidelityRuntimeAvailabilityTest {

    @Test
    void projectionRequiresRoutesSignerAndVerifiedSourceAdapters() {
        AtomicBoolean signer = new AtomicBoolean();
        AtomicBoolean scenario = new AtomicBoolean();
        DomainFidelityRuntimeAvailability availability =
                new DomainFidelityRuntimeAvailability(
                        true,
                        true,
                        signer::get,
                        sources(source(
                                DomainFidelityMeasurementSource.Type
                                        .SCENARIO_REHEARSAL,
                                scenario)));

        assertThat(availability.inventoryApi()).isTrue();
        assertThat(availability.profileReadApi()).isTrue();
        assertThat(availability.signingReady()).isFalse();
        assertThat(availability.projectionReady()).isFalse();
        assertThat(availability.scenarioAdapterReady()).isFalse();
        assertThat(availability.shadowAdapterReady()).isFalse();
        assertThat(availability.outcomeAdapterReady()).isFalse();

        signer.set(true);
        assertThat(availability.signingReady()).isTrue();
        assertThat(availability.projectionReady()).isFalse();

        scenario.set(true);
        assertThat(availability.projectionReady()).isTrue();
        assertThat(availability.scenarioAdapterReady()).isTrue();
    }

    @Test
    void readinessProbesFailClosedWhenAProviderThrows() {
        DomainFidelityRuntimeAvailability unavailable =
                new DomainFidelityRuntimeAvailability(
                        true,
                        true,
                        () -> {
                            throw new IllegalStateException(
                                    "signer unavailable");
                        },
                        sources(source(
                                DomainFidelityMeasurementSource.Type
                                        .SCENARIO_REHEARSAL,
                                new AtomicBoolean(true))));
        DomainFidelityRuntimeAvailability missingRoute =
                new DomainFidelityRuntimeAvailability(
                        true,
                        false,
                        () -> true,
                        sources(source(
                                DomainFidelityMeasurementSource.Type
                                        .SCENARIO_REHEARSAL,
                                new AtomicBoolean(true))));

        assertThat(unavailable.signingReady()).isFalse();
        assertThat(unavailable.projectionReady()).isFalse();
        assertThat(missingRoute.projectionReady()).isFalse();
    }

    @Test
    void duplicateOrThrowingTypedSourcesFailClosed() {
        DomainFidelityMeasurementSource throwing =
                new DomainFidelityMeasurementSource() {
                    @Override
                    public Type type() {
                        return Type.AUTHORITATIVE_OUTCOME;
                    }

                    @Override
                    public boolean ready() {
                        throw new IllegalStateException(
                                "provider unavailable");
                    }
                };
        DomainFidelitySourceAvailability availability =
                sources(throwing);

        assertThat(availability.anyReady()).isFalse();
        assertThat(availability.ready(
                DomainFidelityMeasurementSource.Type
                        .AUTHORITATIVE_OUTCOME)).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new DomainFidelitySourceAvailability(
                        List.of(throwing, throwing)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    private static DomainFidelitySourceAvailability sources(
            DomainFidelityMeasurementSource... sources) {
        return new DomainFidelitySourceAvailability(
                List.of(sources));
    }

    private static DomainFidelityMeasurementSource source(
            DomainFidelityMeasurementSource.Type type,
            AtomicBoolean ready) {
        return new DomainFidelityMeasurementSource() {
            @Override
            public Type type() {
                return type;
            }

            @Override
            public boolean ready() {
                return ready.get();
            }
        };
    }
}
