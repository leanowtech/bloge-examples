package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ReplayPayloadConflictException;
import com.leanowtech.bloge.gateway.testing.api.ReplayPayloadDescriptor;
import com.leanowtech.bloge.gateway.testing.api.StoredReplayPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseReplayPayloadRepositoryTest {

    private DatabaseReplayPayloadRepository repository;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:test-replay-vault-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        repository = new DatabaseReplayPayloadRepository(
                new JdbcTemplate(dataSource), new ObjectMapper().findAndRegisterModules());
        repository.init();
    }

    @Test
    void immutableRevisionRoundTripsNullValueAndRejectsCrossScopeOrOverwrite() {
        Instant now = repository.currentTime();
        StoredReplayPayload stored = payload("tenant-a", "test", "replay-a", 2,
                fingerprint('a'), null, now, now.plusSeconds(30));

        assertThat(repository.create(stored)).isEqualTo(stored);
        assertThat(repository.find("tenant-a", "test", "replay-a", 2))
                .contains(stored);
        assertThat(repository.find("tenant-b", "test", "replay-a", 2)).isEmpty();
        assertThat(repository.find("tenant-a", "staging", "replay-a", 2)).isEmpty();
        assertThat(repository.create(stored)).isEqualTo(stored);

        StoredReplayPayload conflict = payload("tenant-a", "test", "replay-a", 2,
                fingerprint('b'), Map.of("decision", "different"), now, now.plusSeconds(30));
        assertThatThrownBy(() -> repository.create(conflict))
                .isInstanceOf(ReplayPayloadConflictException.class)
                .hasMessageContaining("different immutable content");
    }

    @Test
    void retentionSweepPhysicallyRemovesValueButPreservesPayloadFreeTombstone() throws Exception {
        Instant now = repository.currentTime();
        StoredReplayPayload stored = payload("tenant-a", "test", "replay-expiring", 1,
                fingerprint('c'), Map.of("secret", "already-redacted"),
                now.minusSeconds(1), now.plusMillis(120));
        repository.create(stored);

        Thread.sleep(180);

        assertThat(repository.purgeExpired(10)).isEqualTo(1);
        StoredReplayPayload tombstone = repository.find(
                "tenant-a", "test", "replay-expiring", 1).orElseThrow();
        assertThat(tombstone.state()).isEqualTo(StoredReplayPayload.EXPIRED);
        assertThat(tombstone.payloadAvailable()).isFalse();
        assertThat(tombstone.value()).isNull();
        assertThat(tombstone.descriptor()).isEqualTo(stored.descriptor());
        assertThat(repository.purgeExpired(10)).isZero();
    }

    @Test
    void lookupAlsoExpiresValueWithoutWaitingForScheduledSweep() throws Exception {
        Instant now = repository.currentTime();
        repository.create(payload("tenant-a", "test", "replay-read-expiry", 1,
                fingerprint('d'), List.of(1, 2, 3), now.minusSeconds(1), now.plusMillis(100)));

        Thread.sleep(160);

        StoredReplayPayload tombstone = repository.find(
                "tenant-a", "test", "replay-read-expiry", 1).orElseThrow();
        assertThat(tombstone.state()).isEqualTo(StoredReplayPayload.EXPIRED);
        assertThat(tombstone.readable()).isFalse();
    }

    private static StoredReplayPayload payload(String tenantId, String environmentId,
                                                String id, long revision, String fingerprint,
                                                Object value, Instant storedAt, Instant expiresAt) {
        ReplayPayloadDescriptor descriptor = new ReplayPayloadDescriptor("", id, revision,
                fingerprint, "INTERNAL", new ReplayPayloadDescriptor.Source(
                "GOVERNED_RUN_NODE_ATTEMPT", "run-a", "fetch", 1,
                fingerprint('e'), fingerprint('f'), environmentId),
                new ReplayPayloadDescriptor.Redaction("source@1", 1,
                        "capture@1", 0, false, List.of()), storedAt, expiresAt,
                true, List.of());
        return new StoredReplayPayload("", tenantId, environmentId, descriptor,
                StoredReplayPayload.AVAILABLE, true, value, storedAt, "runner");
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
