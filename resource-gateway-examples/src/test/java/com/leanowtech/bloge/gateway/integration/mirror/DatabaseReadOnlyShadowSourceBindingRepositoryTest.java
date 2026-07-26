package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Clock;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseReadOnlyShadowSourceBindingRepositoryTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final ReadOnlyShadowSourceBindingIntegrity integrity =
            new ReadOnlyShadowSourceBindingIntegrity(
                    mapper,
                    InMemoryVisualEvidenceSigner.usingClock(
                            Clock.fixed(
                                    ReadOnlyShadowJobTestFixtures.NOW,
                                    ZoneOffset.UTC)),
                    Clock.fixed(
                            ReadOnlyShadowJobTestFixtures.NOW,
                            ZoneOffset.UTC));
    private JdbcTemplate jdbc;
    private DatabaseReadOnlyShadowSourceBindingRepository repository;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(
                new EmbeddedDatabaseBuilder()
                        .setType(EmbeddedDatabaseType.H2)
                        .generateUniqueName(true)
                        .build());
        repository =
                new DatabaseReadOnlyShadowSourceBindingRepository(
                        jdbc, mapper, integrity);
        repository.init();
    }

    @Test
    void persistsExactRevisionsIdempotentlyAcrossRepositoryInstances() {
        ReadOnlyShadowSourceBinding signed =
                integrity.sign(
                        ReadOnlyShadowJobTestFixtures.sourceBinding(
                                "refund-pair",
                                "candidate-run"));

        assertThat(repository.create(signed)).isEqualTo(signed);
        assertThat(repository.create(signed)).isEqualTo(signed);
        var restarted =
                new DatabaseReadOnlyShadowSourceBindingRepository(
                        jdbc, mapper, integrity);
        restarted.init();
        assertThat(restarted.find(
                signed.scope(),
                signed.bindingId(),
                signed.revision()))
                .contains(signed);
        assertThat(restarted.find(
                ReadOnlyShadowJobTestFixtures.scope("other"),
                signed.bindingId(),
                signed.revision()))
                .isEmpty();
    }

    @Test
    void rejectsRevisionForksAndIndexedIdentityTamper() {
        ReadOnlyShadowSourceBinding signed =
                integrity.sign(
                        ReadOnlyShadowJobTestFixtures.sourceBinding(
                                "refund-pair",
                                "candidate-run"));
        repository.create(signed);
        ReadOnlyShadowSourceBinding fork =
                integrity.sign(
                        ReadOnlyShadowJobTestFixtures.sourceBinding(
                                "refund-pair",
                                "other-run"));

        assertThatThrownBy(() -> repository.create(fork))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different content");

        jdbc.update("""
                UPDATE read_only_shadow_source_binding
                SET baseline_fingerprint = ?
                WHERE binding_id = ?
                """,
                ReadOnlyShadowJobTestFixtures.fingerprint('f'),
                signed.bindingId());

        assertThatThrownBy(() -> repository.find(
                signed.scope(),
                signed.bindingId(),
                signed.revision()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integrity validation");
    }
}
