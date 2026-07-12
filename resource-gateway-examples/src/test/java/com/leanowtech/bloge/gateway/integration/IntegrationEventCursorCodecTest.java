package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leanowtech.bloge.gateway.visual.runtime.DatabaseVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntegrationEventCursorCodecTest {
    private ObjectMapper objectMapper;
    private VisualEvidenceSigner signer;
    private Instant now;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build());
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        signer = new DatabaseVisualEvidenceSigner(jdbc);
        now = Instant.parse("2026-07-12T00:00:00Z");
    }

    @Test
    void roundTripsScopeBoundCursorWithStableEncoding() {
        IntegrationEventCursorCodec codec = codec(now, Duration.ofHours(1));
        IntegrationEventCursorCodec.CursorPayload cursor = codec.issue("tenant-a", "prod", 3, 9);
        String token = codec.encode(cursor);

        assertThat(codec.decode(token, context("tenant-a", "prod"))).isEqualTo(cursor);
        assertThat(codec.encode(cursor)).isEqualTo(token);
    }

    @Test
    void rejectsTamperingAndCrossScopeReuseWithoutDisclosingCursorContents() {
        IntegrationEventCursorCodec codec = codec(now, Duration.ofHours(1));
        String token = codec.encode(codec.issue("tenant-a", "prod", 3, 9));
        int index = Math.max(1, token.indexOf('.') / 2);
        char replacement = token.charAt(index) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, index) + replacement + token.substring(index + 1);

        assertInvalid(() -> codec.decode(tampered, context("tenant-a", "prod")));
        assertInvalid(() -> codec.decode(token, context("tenant-b", "prod")));
        assertInvalid(() -> codec.decode(token, context("tenant-a", "dev")));
    }

    @Test
    void returnsGoneAfterExpiryAndPointsConsumerToReconciliation() {
        IntegrationEventCursorCodec issuer = codec(now, Duration.ofMinutes(30));
        String token = issuer.encode(issuer.issue("tenant-a", "prod", 3, 9));
        IntegrationEventCursorCodec expiredReader = codec(now.plus(Duration.ofHours(1)), Duration.ofMinutes(30));

        assertThatThrownBy(() -> expiredReader.decode(token, context("tenant-a", "prod")))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(error -> {
                    IntegrationProblem problem = ((IntegrationProblemException) error).problem();
                    assertThat(problem.status()).isEqualTo(410);
                    assertThat(problem.code()).isEqualTo("RG.INTEGRATION.CURSOR_EXPIRED");
                    assertThat(problem.details()).containsEntry("recovery", "/api/integration/reconciliation");
                });
    }

    @Test
    void cursorRemainsVerifiableAfterSigningProviderRestart() {
        IntegrationEventCursorCodec issuer = codec(now, Duration.ofHours(1));
        IntegrationEventCursorCodec.CursorPayload cursor = issuer.issue("tenant-a", "prod", 7, 12);
        String token = issuer.encode(cursor);
        VisualEvidenceSigner reloadedSigner = new DatabaseVisualEvidenceSigner(jdbc);
        IntegrationEventCursorCodec reloaded = new IntegrationEventCursorCodec(objectMapper, reloadedSigner,
                Clock.fixed(now.plusSeconds(10), ZoneOffset.UTC), Duration.ofHours(1));

        assertThat(reloaded.decode(token, context("tenant-a", "prod"))).isEqualTo(cursor);
    }

    private IntegrationEventCursorCodec codec(Instant instant, Duration ttl) {
        return new IntegrationEventCursorCodec(objectMapper, signer,
                Clock.fixed(instant, ZoneOffset.UTC), ttl);
    }

    private static IntegrationRequestContext context(String tenant, String environment) {
        return new IntegrationRequestContext(tenant, "org", "project", environment, "region", "WORKLOAD",
                "aneke-sync", "", "CHANGE_SYNC", "corr-1");
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(error -> assertThat(((IntegrationProblemException) error).problem().code())
                        .isEqualTo("RG.INTEGRATION.CURSOR_INVALID"));
    }
}
