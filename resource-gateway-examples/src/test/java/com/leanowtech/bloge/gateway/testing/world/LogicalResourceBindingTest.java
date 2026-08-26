package com.leanowtech.bloge.gateway.testing.world;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogicalResourceBindingTest {
    @Test
    void restoresOnlyAgainstTheExactContractAndValidatedPersistedIdentity() {
        LogicalResourceContract contract = contract("contract-1");
        LogicalResourceBinding binding = LogicalResourceBinding.restorePersisted(
                "provider", "v1", "resource-1", fingerprint('a'), fingerprint('b'),
                contract.contractId(), contract.contractFingerprint(), contract);

        assertThat(binding.provider()).isEqualTo("provider");
        assertThat(binding.resourceId()).isEqualTo("resource-1");
        assertThat(binding.contractFingerprint()).isEqualTo(contract.contractFingerprint());
    }

    @Test
    void rejectsInvalidPersistedFingerprintAndNonExactContract() {
        LogicalResourceContract contract = contract("contract-1");
        LogicalResourceContract other = contract("contract-2");

        assertThatThrownBy(() -> LogicalResourceBinding.restorePersisted(
                "provider", "v1", "resource-1", "not-a-sha256", fingerprint('b'),
                contract.contractId(), contract.contractFingerprint(), contract))
                .isInstanceOf(LogicalResourceContractException.class)
                .hasMessageNotContaining("not-a-sha256");
        assertThatThrownBy(() -> LogicalResourceBinding.restorePersisted(
                "provider", "v1", "resource-1", fingerprint('a'), fingerprint('b'),
                contract.contractId(), contract.contractFingerprint(), other))
                .isInstanceOf(LogicalResourceContractException.class);
    }

    private static LogicalResourceContract contract(String id) {
        return new LogicalResourceContract(id, SchemaEnvelope.object(
                Map.of("id", Map.of("type", "string")), List.of("id")), SchemaEnvelope.object(
                Map.of("result", Map.of("type", "string")), List.of("result")),
                ResponseSemantics.confirmed("http.status in 200..299", Map.of(),
                        ResponseSemantics.Idempotency.IDEMPOTENT,
                        ResponseSemantics.Retryability.CONDITIONAL));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
