package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationIdentityResolver;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DurableTestRecoveryAuthorityTest {

    @Test
    void excludesHealthyRefreshTelemetryButBindsAuthorizationPolicy() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        when(authenticator.descriptor()).thenReturn(
                descriptor(1, 0, 30), descriptor(27, 3, 30), descriptor(28, 3, 60));
        DurableTestRecoveryAuthority authority = new DurableTestRecoveryAuthority(
                authenticator, new ObjectMapper().findAndRegisterModules());

        DurableTestExecutionCheckpoint.AuthoritySnapshot first = authority.currentSnapshot();
        DurableTestExecutionCheckpoint.AuthoritySnapshot telemetryChanged =
                authority.currentSnapshot();
        DurableTestExecutionCheckpoint.AuthoritySnapshot policyChanged = authority.currentSnapshot();

        assertThat(telemetryChanged).isEqualTo(first);
        assertThat(policyChanged).isNotEqualTo(first);
    }

    @Test
    void rejectsUnavailableOrStaleOpenIdentityAuthorities() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        Map<String, Object> stale = new LinkedHashMap<>(descriptor(1, 0, 30).properties());
        stale.put("staleSnapshotAccepted", true);
        when(authenticator.descriptor()).thenReturn(
                new IntegrationIdentityResolver.Descriptor(
                        "SIGNED_JWT", "DYNAMIC_JWKS", false, false, true, Map.of()),
                new IntegrationIdentityResolver.Descriptor(
                        "SIGNED_JWT", "DYNAMIC_JWKS", true, false, true, stale));
        DurableTestRecoveryAuthority authority = new DurableTestRecoveryAuthority(
                authenticator, new ObjectMapper().findAndRegisterModules());

        assertThatThrownBy(authority::currentSnapshot)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unavailable");
        assertThatThrownBy(authority::currentSnapshot)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("fail-closed");
    }

    private static IntegrationIdentityResolver.Descriptor descriptor(
            long successes, long failures, long clockSkewSeconds) {
        return new IntegrationIdentityResolver.Descriptor(
                "SIGNED_JWT", "DYNAMIC_JWKS", true, false, true, Map.ofEntries(
                Map.entry("acceptedAlgorithms", java.util.List.of("RS256", "EdDSA")),
                Map.entry("clockSkewSeconds", clockSkewSeconds),
                Map.entry("maximumTokenLifetimeSeconds", 900),
                Map.entry("trustSourceType", "DYNAMIC_JWKS"),
                Map.entry("outageFailClosed", true),
                Map.entry("staleSnapshotAccepted", false),
                Map.entry("refreshSuccessCount", successes),
                Map.entry("refreshFailureCount", failures),
                Map.entry("lastSuccessfulRefreshAt", "2026-07-16T00:00:00Z")));
    }
}
