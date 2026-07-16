package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DurableTestRecoveryPrincipalTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void excludesRetryCorrelationButBindsEveryAuthorizationFact() {
        IntegrationRequestContext original = identity("sg", "correlation-a", Set.of("quality", "ops"));
        IntegrationRequestContext retry = identity("sg", "correlation-b", Set.of("ops", "quality"));
        IntegrationRequestContext anotherRegion = identity(
                "us-east", "correlation-a", Set.of("quality", "ops"));
        IntegrationRequestContext fewerGroups = identity(
                "sg", "correlation-a", Set.of("quality"));

        String originalFingerprint = DurableTestRecoveryPrincipal.fingerprint(
                objectMapper, original);

        assertThat(DurableTestRecoveryPrincipal.fingerprint(objectMapper, retry))
                .isEqualTo(originalFingerprint);
        assertThat(DurableTestRecoveryPrincipal.fingerprint(objectMapper, anotherRegion))
                .isNotEqualTo(originalFingerprint);
        assertThat(DurableTestRecoveryPrincipal.fingerprint(objectMapper, fewerGroups))
                .isNotEqualTo(originalFingerprint);
    }

    private static IntegrationRequestContext identity(
            String region, String correlationId, Set<String> groups) {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", region,
                "WORKLOAD", "worker-a", "dispatcher-a", "TEST_EXECUTION", correlationId,
                groups, "CONFIDENTIAL", "grant-a");
    }
}
