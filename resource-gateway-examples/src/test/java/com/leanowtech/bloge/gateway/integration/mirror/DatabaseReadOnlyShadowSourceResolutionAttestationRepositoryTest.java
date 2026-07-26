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
    void persistsOnlineV2CommandIndexesWithoutADetachedBinding() {
        ReadOnlyShadowSourceResolutionAttestation signed =
                integrity.sign(
                        ReadOnlyShadowSourceResolutionTestFixtures
                                .unsignedOnline(
                                        policy.reference()));

        assertThat(repository.create(signed))
                .isEqualTo(signed);
        assertThat(repository.find(
                signed.scope(),
                signed.attestationId(),
                signed.revision()))
                .contains(signed);
        assertThat(jdbc.queryForMap("""
                SELECT source_binding_fingerprint, source_mode,
                       baseline_command_fingerprint,
                       candidate_command_fingerprint
                FROM read_only_shadow_source_resolution_attestation
                WHERE attestation_id = ?
                """, signed.attestationId()))
                .containsEntry(
                        "SOURCE_BINDING_FINGERPRINT",
                        "")
                .containsEntry(
                        "SOURCE_MODE",
                        ReadOnlyShadowJobRequest.SourceMode
                                .ONLINE_EXECUTION.name())
                .containsEntry(
                        "BASELINE_COMMAND_FINGERPRINT",
                        signed.baselineCommandFingerprint())
                .containsEntry(
                        "CANDIDATE_COMMAND_FINGERPRINT",
                        signed.candidateCommandFingerprint());

        jdbc.update("""
                UPDATE read_only_shadow_source_resolution_attestation
                SET baseline_command_fingerprint = ?
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

    @Test
    void upgradesLegacyV1TableWithoutRewritingSignedJson()
            throws Exception {
        ReadOnlyShadowSourceResolutionAttestation signed =
                integrity.sign(
                        ReadOnlyShadowSourceResolutionTestFixtures
                                .unsigned(policy.reference()));
        jdbc.execute("""
                DROP TABLE read_only_shadow_source_resolution_attestation
                """);
        jdbc.execute("""
                CREATE TABLE read_only_shadow_source_resolution_attestation (
                    tenant_id VARCHAR(255) NOT NULL,
                    organization_id VARCHAR(255) NOT NULL,
                    project_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(255) NOT NULL,
                    region VARCHAR(64) NOT NULL,
                    attestation_id VARCHAR(512) NOT NULL,
                    attestation_revision BIGINT NOT NULL,
                    attestation_fingerprint VARCHAR(71) NOT NULL,
                    source_binding_fingerprint VARCHAR(71) NOT NULL,
                    baseline_source_fingerprint VARCHAR(71) NOT NULL,
                    candidate_source_fingerprint VARCHAR(71) NOT NULL,
                    admission_fingerprint VARCHAR(71) NOT NULL,
                    schema_version VARCHAR(128) NOT NULL,
                    attestation_json CLOB NOT NULL,
                    PRIMARY KEY (
                        tenant_id, organization_id, project_id,
                        environment_id, region,
                        attestation_id, attestation_revision
                    )
                )
                """);
        CapabilitySnapshot.Scope scope = signed.scope();
        jdbc.update("""
                INSERT INTO read_only_shadow_source_resolution_attestation (
                    tenant_id, organization_id, project_id,
                    environment_id, region,
                    attestation_id, attestation_revision,
                    attestation_fingerprint,
                    source_binding_fingerprint,
                    baseline_source_fingerprint,
                    candidate_source_fingerprint,
                    admission_fingerprint, schema_version,
                    attestation_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                signed.attestationId(),
                signed.revision(),
                signed.attestationFingerprint(),
                signed.sourceBindingRef().fingerprint(),
                signed.baseline().artifactRef().fingerprint(),
                signed.candidate().artifactRef().fingerprint(),
                signed.admissionFingerprint(),
                signed.schemaVersion(),
                mapper.writeValueAsString(signed));

        var restarted =
                new DatabaseReadOnlyShadowSourceResolutionAttestationRepository(
                        jdbc,
                        mapper,
                        integrity);
        restarted.init();

        assertThat(restarted.find(
                scope,
                signed.attestationId(),
                signed.revision()))
                .contains(signed);
        assertThat(jdbc.queryForMap("""
                SELECT source_mode, baseline_command_fingerprint,
                       candidate_command_fingerprint
                FROM read_only_shadow_source_resolution_attestation
                WHERE attestation_id = ?
                """, signed.attestationId()))
                .containsEntry("SOURCE_MODE", "")
                .containsEntry(
                        "BASELINE_COMMAND_FINGERPRINT",
                        "")
                .containsEntry(
                        "CANDIDATE_COMMAND_FINGERPRINT",
                        "");
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
