package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseDomainFidelityRepositoryTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DomainFidelityProfileIntegrity integrity;
    private DatabaseDomainFidelityRepository repository;
    private DomainFidelityInventory inventory;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(database);
        integrity =
                DomainFidelityTestFixtures.integrity(mapper);
        repository =
                new DatabaseDomainFidelityRepository(
                        jdbc, mapper, integrity);
        repository.init();
        inventory = DomainFidelityTestFixtures.inventory(
                mapper,
                DomainFidelityTestFixtures.scope("support"),
                1,
                DomainFidelityTestFixtures.units());
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void survivesRestartAndRecoversExactInventoryAndProfileRetries() {
        repository.appendInventory(inventory, "");
        DomainFidelityProfile profile =
                DomainFidelityTestFixtures.signedProfile(
                        mapper,
                        integrity,
                        inventory,
                        DomainFidelityTestFixtures
                                .passingMeasurements(inventory));
        repository.appendProfile(profile);

        DatabaseDomainFidelityRepository restarted =
                new DatabaseDomainFidelityRepository(
                        jdbc, mapper, integrity);
        restarted.init();

        assertThat(restarted.findInventory(
                inventory.scope(),
                inventory.inventoryId(),
                inventory.revision()))
                .contains(inventory);
        assertThat(restarted.findLatestInventory(
                inventory.scope(),
                inventory.inventoryId()))
                .contains(inventory);
        assertThat(restarted.appendInventory(inventory, ""))
                .isEqualTo(inventory);
        assertThat(restarted.findProfile(
                profile.scope(),
                profile.profileFingerprint()))
                .contains(profile);
        assertThat(restarted.findLatestProfile(
                profile.scope(),
                profile.domainId()))
                .contains(profile);
        assertThat(restarted.appendProfile(profile))
                .isEqualTo(profile);
    }

    @Test
    void enforcesInventoryCasLineageAndExactCoordinateImmutability() {
        repository.appendInventory(inventory, "");
        DomainFidelityInventory differentRevisionOne =
                DomainFidelityTestFixtures.inventory(
                        mapper,
                        inventory.scope(),
                        1,
                        List.of(
                                DomainFidelityTestFixtures.unit(
                                        "refund-only",
                                        'd',
                                        ScenarioCase.CaseType.GOLDEN)));
        assertReason(
                () -> repository.appendInventory(
                        differentRevisionOne, ""),
                DomainFidelityRepository.Reason
                        .CONTENT_CONFLICT);

        DomainFidelityInventory revisionTwo =
                DomainFidelityTestFixtures.inventory(
                        mapper,
                        inventory.scope(),
                        2,
                        DomainFidelityTestFixtures.units());
        assertReason(
                () -> repository.appendInventory(
                        revisionTwo,
                        "sha256:" + "e".repeat(64)),
                DomainFidelityRepository.Reason
                        .LINEAGE_CONFLICT);
        assertThat(repository.appendInventory(
                revisionTwo, inventory.fingerprint()))
                .isEqualTo(revisionTwo);
    }

    @Test
    void rejectsTwoDifferentProfilesForOneInventoryCut() {
        repository.appendInventory(inventory, "");
        DomainFidelityProfile passing =
                DomainFidelityTestFixtures.signedProfile(
                        mapper,
                        integrity,
                        inventory,
                        DomainFidelityTestFixtures
                                .passingMeasurements(inventory));
        DomainFidelityProfile failing =
                DomainFidelityTestFixtures.signedProfile(
                        mapper,
                        integrity,
                        inventory,
                        DomainFidelityTestFixtures
                                .failingMeasurements(inventory));
        repository.appendProfile(passing);

        assertThat(failing.profileFingerprint())
                .isNotEqualTo(passing.profileFingerprint());
        assertReason(
                () -> repository.appendProfile(failing),
                DomainFidelityRepository.Reason
                        .PROFILE_COORDINATE_CONFLICT);
    }

    @Test
    void hidesCrossScopeRowsAndRejectsProfileWithoutExactInventory() {
        repository.appendInventory(inventory, "");
        DomainFidelityInventory otherInventory =
                DomainFidelityTestFixtures.inventory(
                        mapper,
                        DomainFidelityTestFixtures.scope("other"),
                        1,
                        DomainFidelityTestFixtures.units());
        DomainFidelityProfile profile =
                DomainFidelityTestFixtures.signedProfile(
                        mapper,
                        integrity,
                        otherInventory,
                        DomainFidelityTestFixtures
                                .passingMeasurements(otherInventory));

        assertThat(repository.findInventory(
                DomainFidelityTestFixtures.scope("other"),
                inventory.inventoryId(),
                inventory.revision())).isEmpty();
        assertReason(
                () -> repository.appendProfile(profile),
                DomainFidelityRepository.Reason
                        .INVENTORY_NOT_FOUND);
    }

    @Test
    void detectsTamperedDuplicatedIndexesAndStoresNoBusinessPayloadColumns() {
        repository.appendInventory(inventory, "");
        DomainFidelityProfile profile =
                DomainFidelityTestFixtures.signedProfile(
                        mapper,
                        integrity,
                        inventory,
                        DomainFidelityTestFixtures
                                .passingMeasurements(inventory));
        repository.appendProfile(profile);
        jdbc.update("""
                UPDATE mirror_domain_fidelity_profiles
                SET total_units = total_units + 1
                WHERE profile_fingerprint = ?
                """, profile.profileFingerprint());

        assertReason(
                () -> repository.findProfile(
                        profile.scope(),
                        profile.profileFingerprint()),
                DomainFidelityRepository.Reason
                        .STORED_STATE_CORRUPT);

        List<String> columns = jdbc.queryForList("""
                SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME IN (
                    'MIRROR_DOMAIN_FIDELITY_INVENTORIES',
                    'MIRROR_DOMAIN_FIDELITY_PROFILES'
                )
                ORDER BY TABLE_NAME, ORDINAL_POSITION
                """, String.class);
        assertThat(columns).noneMatch(column ->
                column.equals("REQUEST")
                        || column.equals("RESPONSE")
                        || column.contains("RAW_PAYLOAD")
                        || column.contains("SECRET")
                        || column.contains("FIXTURE_VALUE"));
        assertThat(columns).contains(
                "INVENTORY_FINGERPRINT",
                "PROFILE_FINGERPRINT",
                "TOTAL_OBLIGATIONS",
                "SEAL_MATERIAL_FINGERPRINT");
    }

    private static void assertReason(
            Runnable action,
            DomainFidelityRepository.Reason reason) {
        assertThatThrownBy(action::run)
                .isInstanceOf(
                        DomainFidelityRepository
                                .Violation.class)
                .extracting(failure ->
                        ((DomainFidelityRepository
                                .Violation) failure)
                                .reason())
                .isEqualTo(reason);
    }
}
