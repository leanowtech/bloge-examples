package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureExecutionServices;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedTestSecrets;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSecretResolutionServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");
    private static final String TARGET = "sha256:" + "a".repeat(64);

    @Test
    void bindsExactEnterpriseAndFixtureContextBeforeReturningRunScopedValues() {
        AtomicReference<TestSecretResolutionContext> requested = new AtomicReference<>();
        TestSecretAuthority authority = context -> {
            requested.set(context);
            return resolved(context, "run-only-secret");
        };
        RecordingEvents events = new RecordingEvents();
        TestSecretResolutionService service = service(authority, events);

        ResolvedTestSecrets secrets = service.resolve(
                fixture(), TARGET, TARGET, "GRAPH_CONTRACT_TEST", identity());

        assertThat(secrets.resolve("payment-key")).isEqualTo("run-only-secret");
        assertThat(requested.get().tenantId()).isEqualTo("tenant-a");
        assertThat(requested.get().fixtureBundleId()).isEqualTo("fixture-a");
        assertThat(requested.get().secretRefs()).containsOnlyKeys("payment-key");
        assertThat(events.events).isEmpty();
    }

    @Test
    void failsClosedAndAuditsSubstitutedResponseWithoutEchoingSensitiveMaterial() {
        TestSecretAuthority authority = context -> new ResolvedTestSecrets("",
                "sha256:" + "f".repeat(64), "authority-a", "generation-1",
                NOW, NOW.plusSeconds(60), Map.of("payment-key",
                new ResolvedTestSecrets.Secret("payment-key",
                        "vault://test/payments/key@v3", "version-3",
                        "sha256:" + "d".repeat(64), "run-only-secret")));
        RecordingEvents events = new RecordingEvents();

        assertThatThrownBy(() -> service(authority, events).resolve(
                fixture(), TARGET, TARGET, "GRAPH_CONTRACT_TEST", identity()))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(error -> assertThat(((IntegrationProblemException) error)
                        .problem().status()).isEqualTo(503))
                .hasMessageNotContaining("run-only-secret")
                .hasMessageNotContaining("payment-key");
        assertThat(events.events).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("TEST_SECRET_AUTHORITY");
            assertThat(event.reasonCode()).isEqualTo("AUTHORITY_RESPONSE_INVALID");
            assertThat(event.toString()).doesNotContain(
                    "run-only-secret", "payment-key", "vault://test/payments/key@v3");
        });
    }

    @Test
    void doesNotContactAuthorityWhenFixtureHasNoSecretReferences() {
        TestSecretAuthority authority = context -> {
            throw new AssertionError("authority must not be contacted");
        };
        FixtureBundle fixture = new FixtureBundle(FixtureBundle.SCHEMA_VERSION, "fixture-a", 1,
                TARGET, "INTERNAL", null, null, List.of(), List.of(), Map.of());

        assertThat(service(authority, new RecordingEvents()).resolve(
                fixture, TARGET, TARGET, "GRAPH_CONTRACT_TEST", identity()).isEmpty()).isTrue();
    }

    private static TestSecretResolutionService service(TestSecretAuthority authority,
                                                       RecordingEvents events) {
        return new TestSecretResolutionService(MAPPER, authority, events,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ResolvedTestSecrets resolved(TestSecretResolutionContext context, String value) {
        String alias = "payment-key";
        String reference = "vault://test/payments/key@v3";
        String version = "version-3";
        return new ResolvedTestSecrets("", context.fingerprint(MAPPER),
                "authority-a", "generation-1", NOW, NOW.plusSeconds(60),
                Map.of(alias, new ResolvedTestSecrets.Secret(alias, reference, version,
                        ResolvedTestSecrets.bindingFingerprint(MAPPER,
                                context.fingerprint(MAPPER), "authority-a", "generation-1",
                                alias, reference, version), value)));
    }

    private static FixtureBundle fixture() {
        return new FixtureBundle(FixtureBundle.SCHEMA_VERSION, "fixture-a", 3, TARGET,
                "RESTRICTED", null, null, List.of(), List.of(), Map.of(
                FixtureExecutionServices.METADATA_KEY, Map.of(
                        "schemaVersion", FixtureExecutionServices.SCHEMA_VERSION_V2,
                        "identityAttributes", Map.of(), "featureFlags", Map.of(),
                        "secretRefs", Map.of(
                                "payment-key", "vault://test/payments/key@v3"))));
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test", "sg",
                "SERVICE", "runner", "", "TEST_EXECUTION", "correlation-a",
                Set.of("test.suite.execute"), "RESTRICTED", "grant-a");
    }

    private static final class RecordingEvents implements TestSecurityEventRepository {
        private final List<TestSecurityEvent> events = new ArrayList<>();

        @Override
        public TestSecurityEvent append(TestSecurityEvent event) {
            events.add(event);
            return event;
        }

        @Override
        public List<TestSecurityEvent> recent(int limit) {
            return List.copyOf(events);
        }
    }
}
