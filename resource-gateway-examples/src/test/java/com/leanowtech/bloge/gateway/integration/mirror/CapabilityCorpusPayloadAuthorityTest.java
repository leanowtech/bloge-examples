package com.leanowtech.bloge.gateway.integration.mirror;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityCorpusPayloadAuthorityTest {

    @Test
    void closeRejectsFurtherReadsWithoutRenderingPayload() {
        CapabilityCorpusPayloadAuthority.Materialization materialization =
                CapabilityCorpusPayloadAuthority.Materialization.materialized(
                        "{\"secret\":\"customer-value\"}"
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(materialization.canonicalJson()).isNotEmpty();

        materialization.close();
        materialization.close();

        assertThatThrownBy(materialization::canonicalJson)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("customer-value");
        assertThat(materialization.toString())
                .contains("closed=true")
                .doesNotContain("customer-value");
    }

    @Test
    void invalidOutcomeDoesNotAllocateReadableMaterial() {
        assertThatCode(() ->
                CapabilityCorpusPayloadAuthority.Materialization.rejected(
                        "POLICY_REJECTED"))
                .doesNotThrowAnyException();

        assertThatThrownBy(() ->
                CapabilityCorpusPayloadAuthority.Materialization.materialized(
                        new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
