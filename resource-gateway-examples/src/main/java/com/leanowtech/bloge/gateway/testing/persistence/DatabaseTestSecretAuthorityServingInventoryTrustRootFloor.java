package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityServingInventoryTrustRootFloor;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityServingInventoryTrustRootFloor;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Namespaced database floor for test-secret serving-inventory trust-root publications.
 *
 * <p>The adapter reuses the transactionally fenced dual-root floor kernel while prefixing the
 * scope. Test-secret and suite-stability roots therefore cannot collide even when both features
 * use the same isolated test-runtime database and the same caller-selected root-set id.</p>
 */
public final class DatabaseTestSecretAuthorityServingInventoryTrustRootFloor
        implements TestSecretAuthorityServingInventoryTrustRootFloor {

    private static final String SCOPE_PREFIX = "test-secret/";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

    private final String scopeId;
    private final String trustRootSetId;
    private final DatabaseTestSuiteStabilityServingInventoryTrustRootFloor delegate;

    /**
     * Creates one durable floor for an exact test-secret fleet and dual-root set.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param objectMapper canonical whole-record mapper
     * @param scopeId stable unprefixed test-secret fleet scope
     * @param trustRootSetId stable managed dual-root set identity
     * @param transactionManager manager for the same isolated datasource
     */
    public DatabaseTestSecretAuthorityServingInventoryTrustRootFloor(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            String scopeId,
            String trustRootSetId,
            PlatformTransactionManager transactionManager) {
        this.scopeId = normalized(scopeId);
        this.trustRootSetId = normalized(trustRootSetId);
        if (!IDENTIFIER.matcher(this.scopeId).matches()
                || !IDENTIFIER.matcher(this.trustRootSetId).matches()
                || SCOPE_PREFIX.length() + this.scopeId.length() > 255) {
            throw new IllegalArgumentException(
                    "Invalid test-secret inventory trust-root floor identity");
        }
        delegate = new DatabaseTestSuiteStabilityServingInventoryTrustRootFloor(
                Objects.requireNonNull(jdbc, "jdbc"),
                Objects.requireNonNull(objectMapper, "objectMapper"),
                SCOPE_PREFIX + this.scopeId, this.trustRootSetId,
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    /** Initializes the shared additive trust-root floor schema. */
    @PostConstruct
    public void init() {
        delegate.init();
    }

    /** {@inheritDoc} */
    @Override
    public void accept(Generation generation) {
        if (generation == null || !scopeId.equals(generation.scopeId())
                || !trustRootSetId.equals(generation.trustRootSetId())) {
            throw new IllegalArgumentException(
                    "Test-secret inventory trust-root floor identity does not match");
        }
        delegate.accept(new TestSuiteStabilityServingInventoryTrustRootFloor.Generation(
                TestSuiteStabilityServingInventoryTrustRootFloor.Generation.SCHEMA_VERSION,
                SCOPE_PREFIX + scopeId, trustRootSetId, generation.sequence(),
                generation.materialFingerprint(), generation.previousMaterialFingerprint()));
    }

    /** {@inheritDoc} */
    @Override
    public boolean durable() {
        return delegate.durable();
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
