package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * H2-backed append-only Domain Fidelity inventory and signed-profile repository.
 *
 * <p>Every primary key contains the complete enterprise scope. Canonical protocol JSON is stored
 * with duplicated identity, lineage, denominator, assessment, time, and signature indexes. Reads
 * recompute the inventory/profile content address and profile signature, then compare every
 * duplicated index. A partial SQL mutation therefore becomes a stable corruption failure instead
 * of changing governance behavior.</p>
 */
public class DatabaseDomainFidelityRepository
        implements DomainFidelityRepository {
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");

    private static final String CREATE_INVENTORIES = """
            CREATE TABLE IF NOT EXISTS mirror_domain_fidelity_inventories (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                inventory_id VARCHAR(512) NOT NULL,
                revision BIGINT NOT NULL,
                inventory_fingerprint VARCHAR(71) NOT NULL,
                domain_id VARCHAR(512) NOT NULL,
                taxonomy_id VARCHAR(512) NOT NULL,
                taxonomy_revision BIGINT NOT NULL,
                taxonomy_fingerprint VARCHAR(71) NOT NULL,
                unit_count INTEGER NOT NULL,
                approved_by VARCHAR(512) NOT NULL,
                approved_at VARCHAR(64) NOT NULL,
                effective_at VARCHAR(64) NOT NULL,
                expires_at VARCHAR(64) NOT NULL,
                lifecycle VARCHAR(32) NOT NULL,
                inventory_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, inventory_id, revision
                )
            )
            """;
    private static final String CREATE_PROFILES = """
            CREATE TABLE IF NOT EXISTS mirror_domain_fidelity_profiles (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                profile_fingerprint VARCHAR(71) NOT NULL,
                domain_id VARCHAR(512) NOT NULL,
                inventory_id VARCHAR(512) NOT NULL,
                inventory_revision BIGINT NOT NULL,
                inventory_fingerprint VARCHAR(71) NOT NULL,
                taxonomy_fingerprint VARCHAR(71) NOT NULL,
                measured_at VARCHAR(64) NOT NULL,
                valid_until VARCHAR(64) NOT NULL,
                total_units INTEGER NOT NULL,
                total_obligations INTEGER NOT NULL,
                assessment VARCHAR(64) NOT NULL,
                seal_material_fingerprint VARCHAR(71) NOT NULL,
                seal_key_id VARCHAR(512) NOT NULL,
                seal_signed_at VARCHAR(64) NOT NULL,
                profile_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, profile_fingerprint
                ),
                CONSTRAINT uq_mirror_domain_fidelity_profile_cut UNIQUE (
                    tenant_id, organization_id, project_id,
                    environment_id, region, domain_id,
                    inventory_fingerprint, measured_at
                )
            )
            """;
    private static final String INVENTORY_COLUMNS = """
            inventory_fingerprint, domain_id, taxonomy_id,
            taxonomy_revision, taxonomy_fingerprint, unit_count,
            approved_by, approved_at, effective_at, expires_at,
            lifecycle, inventory_json
            """;
    private static final String PROFILE_COLUMNS = """
            profile_fingerprint, domain_id, inventory_id,
            inventory_revision, inventory_fingerprint,
            taxonomy_fingerprint, measured_at, valid_until,
            total_units, total_obligations, assessment,
            seal_material_fingerprint, seal_key_id, seal_signed_at,
            profile_json
            """;
    private static final String INSERT_INVENTORY = """
            INSERT INTO mirror_domain_fidelity_inventories (
                tenant_id, organization_id, project_id, environment_id, region,
                inventory_id, revision, inventory_fingerprint, domain_id,
                taxonomy_id, taxonomy_revision, taxonomy_fingerprint,
                unit_count, approved_by, approved_at, effective_at, expires_at,
                lifecycle, inventory_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_PROFILE = """
            INSERT INTO mirror_domain_fidelity_profiles (
                tenant_id, organization_id, project_id, environment_id, region,
                profile_fingerprint, domain_id, inventory_id,
                inventory_revision, inventory_fingerprint,
                taxonomy_fingerprint, measured_at, valid_until,
                total_units, total_obligations, assessment,
                seal_material_fingerprint, seal_key_id, seal_signed_at,
                profile_json
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            )
            """;
    private static final String SELECT_INVENTORY = """
            SELECT %s
            FROM mirror_domain_fidelity_inventories
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ?
              AND inventory_id = ? AND revision = ?
            """.formatted(INVENTORY_COLUMNS);
    private static final String SELECT_LATEST_INVENTORY = """
            SELECT %s
            FROM mirror_domain_fidelity_inventories
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ?
              AND inventory_id = ?
            ORDER BY revision DESC
            FETCH FIRST 1 ROW ONLY
            """.formatted(INVENTORY_COLUMNS);
    private static final String SELECT_PROFILE = """
            SELECT %s
            FROM mirror_domain_fidelity_profiles
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ?
              AND profile_fingerprint = ?
            """.formatted(PROFILE_COLUMNS);
    private static final String SELECT_PROFILE_CUT = """
            SELECT %s
            FROM mirror_domain_fidelity_profiles
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ?
              AND domain_id = ? AND inventory_fingerprint = ?
              AND measured_at = ?
            """.formatted(PROFILE_COLUMNS);
    private static final String SELECT_LATEST_PROFILE = """
            SELECT %s
            FROM mirror_domain_fidelity_profiles
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ?
              AND domain_id = ?
            ORDER BY measured_at DESC, profile_fingerprint DESC
            FETCH FIRST 1 ROW ONLY
            """.formatted(PROFILE_COLUMNS);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final DomainFidelityProfileIntegrity profileIntegrity;

    /**
     * Creates the durable repository.
     *
     * @param jdbc transaction-aware application JDBC boundary
     * @param mapper canonical protocol mapper
     * @param profileIntegrity managed profile signing and verification boundary
     */
    public DatabaseDomainFidelityRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            DomainFidelityProfileIntegrity profileIntegrity) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.profileIntegrity = Objects.requireNonNull(
                profileIntegrity, "profileIntegrity");
    }

    /** Creates both append-only tables when absent. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_INVENTORIES);
        jdbc.execute(CREATE_PROFILES);
    }

    @Override
    @Transactional
    public DomainFidelityInventory appendInventory(
            DomainFidelityInventory inventory,
            String expectedPredecessorFingerprint) {
        DomainFidelityInventory exact = verifyInventory(
                inventory, Reason.CANONICAL_INVALID);
        String expected = optionalFingerprint(
                expectedPredecessorFingerprint);
        Optional<DomainFidelityInventory> existing =
                findInventory(
                        exact.scope(),
                        exact.inventoryId(),
                        exact.revision());
        if (existing.isPresent()) {
            return sameInventoryOrConflict(
                    existing.get(), exact);
        }
        requireInventoryLineage(exact, expected);
        CapabilitySnapshot.Scope scope = exact.scope();
        try {
            jdbc.update(
                    INSERT_INVENTORY,
                    scope.tenantId(),
                    scope.organizationId(),
                    scope.projectId(),
                    scope.environmentId(),
                    scope.region(),
                    exact.inventoryId(),
                    exact.revision(),
                    exact.fingerprint(),
                    exact.domainId(),
                    exact.taxonomyRef().id(),
                    exact.taxonomyRef().revision(),
                    exact.taxonomyRef().fingerprint(),
                    exact.units().size(),
                    exact.provenance().approvedBy(),
                    exact.provenance().approvedAt().toString(),
                    exact.effectiveAt().toString(),
                    exact.expiresAt().toString(),
                    exact.lifecycle().name(),
                    mapper.writeValueAsString(exact));
            return exact;
        } catch (DuplicateKeyException concurrent) {
            DomainFidelityInventory stored =
                    findInventory(
                            scope,
                            exact.inventoryId(),
                            exact.revision())
                            .orElseThrow(() -> concurrent);
            return sameInventoryOrConflict(stored, exact);
        } catch (JsonProcessingException invalid) {
            throw new Violation(Reason.CANONICAL_INVALID);
        }
    }

    @Override
    public Optional<DomainFidelityInventory> findInventory(
            CapabilitySnapshot.Scope scope,
            String inventoryId,
            long revision) {
        if (revision < 1) {
            throw new IllegalArgumentException(
                    "inventory revision must be positive");
        }
        return queryInventory(
                SELECT_INVENTORY,
                Objects.requireNonNull(scope, "scope"),
                identifier(inventoryId),
                revision);
    }

    @Override
    public Optional<DomainFidelityInventory> findLatestInventory(
            CapabilitySnapshot.Scope scope,
            String inventoryId) {
        return queryInventory(
                SELECT_LATEST_INVENTORY,
                Objects.requireNonNull(scope, "scope"),
                identifier(inventoryId));
    }

    @Override
    @Transactional
    public DomainFidelityProfile appendProfile(
            DomainFidelityProfile profile) {
        DomainFidelityProfile exact = verifyProfile(
                profile, Reason.CANONICAL_INVALID);
        requireInventoryBinding(exact);
        Optional<DomainFidelityProfile> existing =
                findProfile(
                        exact.scope(),
                        exact.profileFingerprint());
        if (existing.isPresent()) {
            return sameProfileOrConflict(
                    existing.get(), exact);
        }
        Optional<DomainFidelityProfile> cut =
                findProfileCut(exact);
        if (cut.isPresent()) {
            throw new Violation(
                    Reason.PROFILE_COORDINATE_CONFLICT);
        }
        CapabilitySnapshot.Scope scope = exact.scope();
        try {
            jdbc.update(
                    INSERT_PROFILE,
                    scope.tenantId(),
                    scope.organizationId(),
                    scope.projectId(),
                    scope.environmentId(),
                    scope.region(),
                    exact.profileFingerprint(),
                    exact.domainId(),
                    exact.inventoryRef().id(),
                    exact.inventoryRef().revision(),
                    exact.inventoryRef().fingerprint(),
                    exact.taxonomyRef().fingerprint(),
                    exact.measuredAt().toString(),
                    exact.validUntil().toString(),
                    exact.denominator().totalUnits(),
                    exact.denominator().totalObligations(),
                    exact.assessment().name(),
                    exact.profileSeal().materialFingerprint(),
                    exact.profileSeal().keyId(),
                    exact.profileSeal().signedAt().toString(),
                    mapper.writeValueAsString(exact));
            return exact;
        } catch (DuplicateKeyException concurrent) {
            Optional<DomainFidelityProfile> stored =
                    findProfile(
                            scope,
                            exact.profileFingerprint());
            if (stored.isPresent()) {
                return sameProfileOrConflict(
                        stored.get(), exact);
            }
            if (findProfileCut(exact).isPresent()) {
                throw new Violation(
                        Reason.PROFILE_COORDINATE_CONFLICT);
            }
            throw concurrent;
        } catch (JsonProcessingException invalid) {
            throw new Violation(Reason.CANONICAL_INVALID);
        }
    }

    @Override
    public Optional<DomainFidelityProfile> findProfile(
            CapabilitySnapshot.Scope scope,
            String profileFingerprint) {
        return queryProfile(
                SELECT_PROFILE,
                Objects.requireNonNull(scope, "scope"),
                fingerprint(profileFingerprint));
    }

    @Override
    public Optional<DomainFidelityProfile> findLatestProfile(
            CapabilitySnapshot.Scope scope,
            String domainId) {
        return queryProfile(
                SELECT_LATEST_PROFILE,
                Objects.requireNonNull(scope, "scope"),
                identifier(domainId));
    }

    private Optional<DomainFidelityInventory> queryInventory(
            String sql,
            CapabilitySnapshot.Scope scope,
            String inventoryId,
            Object... trailing) {
        Object[] arguments = arguments(
                scope,
                new Object[]{inventoryId},
                trailing);
        List<DomainFidelityInventory> found = jdbc.query(
                sql,
                (result, rowNumber) ->
                        deserializeInventory(
                                scope, inventoryId, result),
                arguments);
        return found.stream().findFirst();
    }

    private Optional<DomainFidelityProfile> queryProfile(
            String sql,
            CapabilitySnapshot.Scope scope,
            Object... trailing) {
        List<DomainFidelityProfile> found = jdbc.query(
                sql,
                (result, rowNumber) ->
                        deserializeProfile(scope, result),
                arguments(scope, new Object[0], trailing));
        return found.stream().findFirst();
    }

    private Optional<DomainFidelityProfile> findProfileCut(
            DomainFidelityProfile profile) {
        return queryProfile(
                SELECT_PROFILE_CUT,
                profile.scope(),
                profile.domainId(),
                profile.inventoryRef().fingerprint(),
                profile.measuredAt().toString());
    }

    private DomainFidelityInventory deserializeInventory(
            CapabilitySnapshot.Scope expectedScope,
            String expectedInventoryId,
            ResultSet result) throws SQLException {
        try {
            DomainFidelityInventory value = verifyInventory(
                    mapper.readValue(
                            result.getString("inventory_json"),
                            DomainFidelityInventory.class),
                    Reason.STORED_STATE_CORRUPT);
            if (!expectedScope.equals(value.scope())
                    || !expectedInventoryId.equals(
                    value.inventoryId())
                    || !result.getString(
                    "inventory_fingerprint").equals(
                    value.fingerprint())
                    || !result.getString("domain_id").equals(
                    value.domainId())
                    || !result.getString("taxonomy_id").equals(
                    value.taxonomyRef().id())
                    || result.getLong("taxonomy_revision")
                    != value.taxonomyRef().revision()
                    || !result.getString(
                    "taxonomy_fingerprint").equals(
                    value.taxonomyRef().fingerprint())
                    || result.getInt("unit_count")
                    != value.units().size()
                    || !result.getString("approved_by").equals(
                    value.provenance().approvedBy())
                    || !result.getString("approved_at").equals(
                    value.provenance().approvedAt().toString())
                    || !result.getString("effective_at").equals(
                    value.effectiveAt().toString())
                    || !result.getString("expires_at").equals(
                    value.expiresAt().toString())
                    || !result.getString("lifecycle").equals(
                    value.lifecycle().name())) {
                throw new Violation(
                        Reason.STORED_STATE_CORRUPT);
            }
            return value;
        } catch (Violation expected) {
            throw expected;
        } catch (JsonProcessingException
                 | IllegalArgumentException invalid) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
    }

    private DomainFidelityProfile deserializeProfile(
            CapabilitySnapshot.Scope expectedScope,
            ResultSet result) throws SQLException {
        try {
            DomainFidelityProfile value = verifyProfile(
                    mapper.readValue(
                            result.getString("profile_json"),
                            DomainFidelityProfile.class),
                    Reason.STORED_STATE_CORRUPT);
            if (!expectedScope.equals(value.scope())
                    || !result.getString(
                    "profile_fingerprint").equals(
                    value.profileFingerprint())
                    || !result.getString("domain_id").equals(
                    value.domainId())
                    || !result.getString("inventory_id").equals(
                    value.inventoryRef().id())
                    || result.getLong("inventory_revision")
                    != value.inventoryRef().revision()
                    || !result.getString(
                    "inventory_fingerprint").equals(
                    value.inventoryRef().fingerprint())
                    || !result.getString(
                    "taxonomy_fingerprint").equals(
                    value.taxonomyRef().fingerprint())
                    || !result.getString("measured_at").equals(
                    value.measuredAt().toString())
                    || !result.getString("valid_until").equals(
                    value.validUntil().toString())
                    || result.getInt("total_units")
                    != value.denominator().totalUnits()
                    || result.getInt("total_obligations")
                    != value.denominator().totalObligations()
                    || !result.getString("assessment").equals(
                    value.assessment().name())
                    || !result.getString(
                    "seal_material_fingerprint").equals(
                    value.profileSeal().materialFingerprint())
                    || !result.getString("seal_key_id").equals(
                    value.profileSeal().keyId())
                    || !result.getString("seal_signed_at").equals(
                    value.profileSeal().signedAt().toString())) {
                throw new Violation(
                        Reason.STORED_STATE_CORRUPT);
            }
            return value;
        } catch (Violation expected) {
            throw expected;
        } catch (JsonProcessingException
                 | IllegalArgumentException invalid) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
    }

    private void requireInventoryLineage(
            DomainFidelityInventory candidate,
            String expectedPredecessorFingerprint) {
        Optional<DomainFidelityInventory> latest =
                findLatestInventory(
                        candidate.scope(),
                        candidate.inventoryId());
        if (latest.isEmpty()) {
            if (candidate.revision() != 1
                    || !expectedPredecessorFingerprint.isBlank()) {
                throw new Violation(
                        Reason.LINEAGE_CONFLICT);
            }
            return;
        }
        DomainFidelityInventory current = latest.get();
        if (candidate.revision()
                != current.revision() + 1
                || !current.fingerprint().equals(
                expectedPredecessorFingerprint)
                || !current.domainId().equals(
                candidate.domainId())) {
            throw new Violation(
                    Reason.LINEAGE_CONFLICT);
        }
    }

    private void requireInventoryBinding(
            DomainFidelityProfile profile) {
        DomainFidelityInventory inventory =
                findInventory(
                        profile.scope(),
                        profile.inventoryRef().id(),
                        profile.inventoryRef().revision())
                        .orElseThrow(() ->
                                new Violation(
                                        Reason.INVENTORY_NOT_FOUND));
        if (!inventory.fingerprint().equals(
                profile.inventoryRef().fingerprint())
                || !inventory.domainId().equals(
                profile.domainId())
                || !inventory.taxonomyRef().equals(
                profile.taxonomyRef())
                || profile.measuredAt().isBefore(
                inventory.effectiveAt())
                || !profile.measuredAt().isBefore(
                inventory.expiresAt())) {
            throw new Violation(
                    Reason.INVENTORY_MISMATCH);
        }
    }

    private DomainFidelityInventory verifyInventory(
            DomainFidelityInventory inventory,
            Reason reason) {
        try {
            DomainFidelityInventory exact =
                    Objects.requireNonNull(
                            inventory, "inventory");
            exact.verify(mapper);
            return exact;
        } catch (RuntimeException invalid) {
            throw new Violation(reason);
        }
    }

    private DomainFidelityProfile verifyProfile(
            DomainFidelityProfile profile,
            Reason invalidReason) {
        try {
            return profileIntegrity.verify(profile);
        } catch (DomainFidelityProfileIntegrity.Violation failure) {
            if (failure.reason()
                    == DomainFidelityProfileIntegrity
                    .Reason.KEY_UNAVAILABLE) {
                throw new Violation(
                        Reason.SIGNATURE_UNAVAILABLE);
            }
            throw new Violation(invalidReason);
        } catch (RuntimeException invalid) {
            throw new Violation(invalidReason);
        }
    }

    private static DomainFidelityInventory sameInventoryOrConflict(
            DomainFidelityInventory stored,
            DomainFidelityInventory candidate) {
        if (stored.fingerprint().equals(
                candidate.fingerprint())) {
            return stored;
        }
        throw new Violation(Reason.CONTENT_CONFLICT);
    }

    private static DomainFidelityProfile sameProfileOrConflict(
            DomainFidelityProfile stored,
            DomainFidelityProfile candidate) {
        if (stored.equals(candidate)) {
            return stored;
        }
        throw new Violation(Reason.CONTENT_CONFLICT);
    }

    private static Object[] arguments(
            CapabilitySnapshot.Scope scope,
            Object[] leading,
            Object[] trailing) {
        Object[] values =
                new Object[5 + leading.length + trailing.length];
        values[0] = scope.tenantId();
        values[1] = scope.organizationId();
        values[2] = scope.projectId();
        values[3] = scope.environmentId();
        values[4] = scope.region();
        System.arraycopy(
                leading, 0, values, 5, leading.length);
        System.arraycopy(
                trailing, 0, values,
                5 + leading.length, trailing.length);
        return values;
    }

    private static String identifier(String value) {
        String exact = value == null ? "" : value.trim();
        if (!IDENTIFIER.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    "Domain Fidelity identifier is invalid");
        }
        return exact;
    }

    private static String fingerprint(String value) {
        String exact = value == null ? "" : value.trim();
        if (!FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    "Domain Fidelity fingerprint is invalid");
        }
        return exact;
    }

    private static String optionalFingerprint(String value) {
        String exact = value == null ? "" : value.trim();
        if (!exact.isBlank()
                && !FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    "predecessor fingerprint is invalid");
        }
        return exact;
    }
}
