package com.leanowtech.bloge.gateway.integration;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class DomainFidelityRuntimeAvailabilityTest {

    @Test
    void projectionRequiresRoutesSignerAndVerifiedSourceAdapters() {
        AtomicBoolean signer = new AtomicBoolean();
        AtomicBoolean sources = new AtomicBoolean();
        DomainFidelityRuntimeAvailability availability =
                new DomainFidelityRuntimeAvailability(
                        true,
                        true,
                        signer::get,
                        sources::get);

        assertThat(availability.inventoryApi()).isTrue();
        assertThat(availability.profileReadApi()).isTrue();
        assertThat(availability.signingReady()).isFalse();
        assertThat(availability.projectionReady()).isFalse();

        signer.set(true);
        assertThat(availability.signingReady()).isTrue();
        assertThat(availability.projectionReady()).isFalse();

        sources.set(true);
        assertThat(availability.projectionReady()).isTrue();
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
                        () -> true);
        DomainFidelityRuntimeAvailability missingRoute =
                new DomainFidelityRuntimeAvailability(
                        true,
                        false,
                        () -> true,
                        () -> true);

        assertThat(unavailable.signingReady()).isFalse();
        assertThat(unavailable.projectionReady()).isFalse();
        assertThat(missingRoute.projectionReady()).isFalse();
    }
}
