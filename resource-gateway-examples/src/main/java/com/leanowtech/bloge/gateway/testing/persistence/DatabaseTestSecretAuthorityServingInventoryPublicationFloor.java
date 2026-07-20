package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityServingInventoryPublicationFloor;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityServingInventoryPublicationFloor;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Namespaced test-secret adapter over the shared database publication-floor kernel.
 *
 * <p>The test-secret prefix prevents collisions with suite-stability inventory scopes while the
 * mature kernel supplies stable-scope locking, database time, whole-record integrity and exact
 * predecessor comparison.</p>
 */
public final class DatabaseTestSecretAuthorityServingInventoryPublicationFloor
        implements TestSecretAuthorityServingInventoryPublicationFloor {

    private static final String SCOPE_PREFIX = "test-secret/";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

    private final String scopeId;
    private final DatabaseTestSuiteStabilityServingInventoryPublicationFloor delegate;

    /**
     * Creates one durable test-secret publication floor.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param objectMapper canonical floor-record mapper
     * @param scopeId stable unprefixed test-secret serving-fleet scope
     * @param transactionManager manager for the same isolated datasource
     */
    public DatabaseTestSecretAuthorityServingInventoryPublicationFloor(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            String scopeId,
            PlatformTransactionManager transactionManager) {
        this.scopeId = normalized(scopeId);
        if (!IDENTIFIER.matcher(this.scopeId).matches()
                || SCOPE_PREFIX.length() + this.scopeId.length() > 255) {
            throw new IllegalArgumentException(
                    "Invalid test-secret inventory publication floor scope");
        }
        delegate = new DatabaseTestSuiteStabilityServingInventoryPublicationFloor(
                Objects.requireNonNull(jdbc, "jdbc"),
                Objects.requireNonNull(objectMapper, "objectMapper"),
                SCOPE_PREFIX + this.scopeId,
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    /** Initializes the shared additive publication-floor schema. */
    @PostConstruct
    public void init() {
        delegate.init();
    }

    /** {@inheritDoc} */
    @Override
    public void accept(Generation generation) {
        if (generation == null || !scopeId.equals(generation.scopeId())) {
            throw new IllegalArgumentException(
                    "Test-secret inventory publication floor scope does not match");
        }
        delegate.accept(new TestSuiteStabilityServingInventoryPublicationFloor.Generation(
                TestSuiteStabilityServingInventoryPublicationFloor.Generation.SCHEMA_VERSION,
                SCOPE_PREFIX + scopeId, generation.sequence(),
                generation.publicationMaterialFingerprint(),
                generation.witnessMaterialFingerprint(),
                generation.previousPublicationFingerprint(),
                generation.previousWitnessFingerprint()));
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
