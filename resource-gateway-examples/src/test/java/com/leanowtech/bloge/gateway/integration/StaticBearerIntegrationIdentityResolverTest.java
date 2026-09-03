package com.leanowtech.bloge.gateway.integration;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StaticBearerIntegrationIdentityResolverTest {

    @Test
    void resolvesOnlyTheConfiguredCredentialAndNeverUsesCallerClaims() {
        StaticBearerIntegrationIdentityResolver resolver = new StaticBearerIntegrationIdentityResolver(
                "server-secret", identity(Instant.parse("2026-07-13T00:00:00Z")), false,
                Clock.fixed(Instant.parse("2026-07-12T00:00:00Z"), ZoneOffset.UTC));

        assertThat(resolver.resolve("server-secret")).contains(identity(
                Instant.parse("2026-07-13T00:00:00Z")));
        assertThat(resolver.resolve("wrong-secret")).isEmpty();
        assertThat(resolver.resolve("")).isEmpty();
        assertThat(resolver.descriptor().claimsSource()).isEqualTo("SERVER_REGISTRY");
        assertThat(resolver.descriptor().demoMode()).isFalse();
    }

    @Test
    void rejectsAnExpiredOrDisabledServerIdentity() {
        Clock observedAt = Clock.fixed(Instant.parse("2026-07-12T00:00:00Z"), ZoneOffset.UTC);
        StaticBearerIntegrationIdentityResolver expired = new StaticBearerIntegrationIdentityResolver(
                "secret", identity(Instant.parse("2026-07-11T23:59:59Z")), false, observedAt);
        IntegrationWorkloadIdentity disabledIdentity = new IntegrationWorkloadIdentity("aneke", "tenant-a",
                "org-a", "project-a", "prod", "sg", "WORKLOAD", "sync", "", Set.of("CHANGE_SYNC"),
                Instant.parse("2026-07-13T00:00:00Z"), false);
        StaticBearerIntegrationIdentityResolver disabled = new StaticBearerIntegrationIdentityResolver(
                "secret", disabledIdentity, false, observedAt);

        assertThat(expired.resolve("secret")).isEmpty();
        assertThat(disabled.resolve("secret")).isEmpty();
    }

    @Test
    void resolvesAgentAndHumanDemoCredentialsToDifferentTrustedActors() {
        IntegrationWorkloadIdentity agent = identity(Instant.parse("2026-07-13T00:00:00Z"));
        IntegrationWorkloadIdentity reviewer = new IntegrationWorkloadIdentity(
                "reviewer", "tenant-a", "org-a", "project-a", "prod", "sg",
                "HUMAN", "business-reviewer", "", Set.of("AGENT_TDD_GOVERNANCE"),
                Instant.parse("2026-07-13T00:00:00Z"), true);
        StaticBearerIntegrationIdentityResolver resolver = new StaticBearerIntegrationIdentityResolver(
                Map.of("agent-secret", agent, "review-secret", reviewer), true,
                Clock.fixed(Instant.parse("2026-07-12T00:00:00Z"), ZoneOffset.UTC));

        assertThat(resolver.resolve("agent-secret")).get().extracting(IntegrationWorkloadIdentity::actorType)
                .isEqualTo("WORKLOAD");
        assertThat(resolver.resolve("review-secret")).get().satisfies(identity -> {
            assertThat(identity.actorType()).isEqualTo("HUMAN");
            assertThat(identity.actorId()).isEqualTo("business-reviewer");
        });
    }

    private static IntegrationWorkloadIdentity identity(Instant expiresAt) {
        return new IntegrationWorkloadIdentity("aneke", "tenant-a", "org-a", "project-a", "prod", "sg",
                "WORKLOAD", "sync", "", Set.of("CHANGE_SYNC"), expiresAt, true);
    }
}
