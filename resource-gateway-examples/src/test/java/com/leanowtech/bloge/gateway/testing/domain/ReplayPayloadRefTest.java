package com.leanowtech.bloge.gateway.testing.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplayPayloadRefTest {

    @Test
    void exactReferenceRoundTripsWithoutLatestOrPartialForms() {
        String fingerprint = "sha256:" + "a".repeat(64);
        ReplayPayloadRef reference = new ReplayPayloadRef("orders.approved", 7, fingerprint);

        assertThat(ReplayPayloadRef.parse(reference.canonical())).isEqualTo(reference);
        assertThatThrownBy(() -> ReplayPayloadRef.parse("orders.approved@7#" + fingerprint))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReplayPayloadRef.parse(
                "bloge-replay:orders.approved@latest#" + fingerprint))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReplayPayloadRef.parse(
                "bloge-replay:orders.approved@7#sha256:abc"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
