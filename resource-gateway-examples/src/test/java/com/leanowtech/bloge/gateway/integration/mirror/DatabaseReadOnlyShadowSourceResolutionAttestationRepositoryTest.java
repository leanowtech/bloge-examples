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

class DatabaseReadOnlyShadowSourceResolutionAttestationRepositoryTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final PayloadFreeEqualityReadOnlyShadowPolicy policy =
            new PayloadFreeEqualityReadOnlyShadowPolicy(mapper);
    private final ReadOnlyShadowSourceResolutionAttestationIntegrity
            integrity =
            new ReadOnlyShadowSourceResolutionAttestationIntegrity(
                    mapper,
                    InMemoryVisualEvidenceSigner.usingClock(
                            clock()),
                    clock());
    private JdbcTemplate jdbc;
    private DatabaseReadOnlyShadowSourceResolutionAttestationRepository
            repository;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(
                new EmbeddedDatabaseBuilder()
                        .setType(EmbeddedDatabaseType.H2)
                        .generateUniqueName(true)
                        .build());
        repository =
                new DatabaseReadOnlyShadowSourceResolutionAttestationRepository(
                        jdbc,
                        mapper,
                        integrity);
        repository.init();
    }

    @Test
    void persistsExactAttestationIdempotentlyAcrossRepositoryInstances() {
        ReadOnlyShadowSourceResolutionAttestation signed =
                integrity.sign(
                        ReadOnlyShadowSourceResolutionTestFixtures
                                .unsigned(policy.reference()));

        assertThat(repository.create(signed))
                .isEqualTo(signed);
        assertThat(repository.create(signed))
                .isEqualTo(signed);
        var restarted =
                new DatabaseReadOnlyShadowSourceResolutionAttestationRepository(
                        jdbc,
                        mapper,
                        integrity);
        restarted.init();
        assertThat(restarted.find(
                signed.scope(),
                signed.attestationId(),
                signed.revision()))
                .contains(signed);
        assertThat(restarted.find(
                ReadOnlyShadowJobTestFixtures.scope("other"),
                signed.attestationId(),
                signed.revision()))
                .isEmpty();
    }

    @Test
    void rejectsIndexedIdentityTamper() {
        ReadOnlyShadowSourceResolutionAttestation signed =
                integrity.sign(
                        ReadOnlyShadowSourceResolutionTestFixtures
                                .unsigned(policy.reference()));
        repository.create(signed);
        jdbc.update("""
                UPDATE read_only_shadow_source_resolution_attestation
                SET candidate_source_fingerprint = ?
                WHERE attestation_id = ?
                """,
                ReadOnlyShadowSourceResolutionTestFixtures
                        .fingerprint('f'),
                signed.attestationId());

        assertThatThrownBy(() -> repository.find(
                signed.scope(),
                signed.attestationId(),
                signed.revision()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integrity validation");
    }

    private static Clock clock() {
        return Clock.fixed(
                ReadOnlyShadowSourceResolutionTestFixtures
                        .NOW.plusSeconds(4),
                ZoneOffset.UTC);
    }
}
