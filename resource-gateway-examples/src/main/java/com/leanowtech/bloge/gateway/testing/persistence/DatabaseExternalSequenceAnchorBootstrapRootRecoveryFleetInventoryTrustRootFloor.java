package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityServingInventoryTrustRootFloor;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Namespaced database floor for recovery-fleet inventory dual trust-root publications.
 *
 * <p>The adapter reuses the transactionally fenced serving-inventory root-floor kernel. A
 * length-prefixed deployment scope plus fleet id prevents tuple-collision ambiguity while keeping
 * recovery, test-secret, and suite-stability root generations isolated in one test-runtime
 * database.</p>
 */
public final class
        DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor
        implements ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor {

    private static final String SCOPE_PREFIX = "bootstrap-root-recovery-fleet/";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

    private final String deploymentScopeId;
    private final String fleetId;
    private final String trustRootSetId;
    private final String storedScopeId;
    private final DatabaseTestSuiteStabilityServingInventoryTrustRootFloor delegate;

    /**
     * Creates one durable floor for an exact deployment, fleet, and dual-root set.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param objectMapper canonical whole-record mapper
     * @param deploymentScopeId stable tenant and environment deployment scope
     * @param fleetId stable recovery-fleet identity
     * @param trustRootSetId stable managed dual-root set identity
     * @param transactionManager manager for the same isolated datasource
     */
    public DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            String deploymentScopeId,
            String fleetId,
            String trustRootSetId,
            PlatformTransactionManager transactionManager) {
        this.deploymentScopeId = normalized(deploymentScopeId);
        this.fleetId = normalized(fleetId);
        this.trustRootSetId = normalized(trustRootSetId);
        if (!IDENTIFIER.matcher(this.deploymentScopeId).matches()
                || !IDENTIFIER.matcher(this.fleetId).matches()
                || !IDENTIFIER.matcher(this.trustRootSetId).matches()) {
            throw new IllegalArgumentException(
                    "Invalid recovery-fleet inventory trust-root floor identity");
        }
        this.storedScopeId = storedScope(this.deploymentScopeId, this.fleetId);
        if (this.storedScopeId.length() > 255) {
            throw new IllegalArgumentException(
                    "Recovery-fleet inventory trust-root floor scope is too long");
        }
        this.delegate = new DatabaseTestSuiteStabilityServingInventoryTrustRootFloor(
                Objects.requireNonNull(jdbc, "jdbc"),
                Objects.requireNonNull(objectMapper, "objectMapper"),
                storedScopeId, this.trustRootSetId,
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    /** Initializes the shared additive atomic dual-root floor schema. */
    @PostConstruct
    public void init() {
        delegate.init();
    }

    /** {@inheritDoc} */
    @Override
    public void accept(Generation generation) {
        if (generation == null
                || !deploymentScopeId.equals(generation.deploymentScopeId())
                || !fleetId.equals(generation.fleetId())
                || !trustRootSetId.equals(generation.trustRootSetId())) {
            throw new IllegalArgumentException(
                    "Recovery-fleet inventory trust-root floor identity does not match");
        }
        delegate.accept(new TestSuiteStabilityServingInventoryTrustRootFloor.Generation(
                TestSuiteStabilityServingInventoryTrustRootFloor.Generation.SCHEMA_VERSION,
                storedScopeId, trustRootSetId, generation.sequence(),
                generation.materialFingerprint(), generation.previousMaterialFingerprint()));
    }

    /** {@inheritDoc} */
    @Override
    public boolean durable() {
        return delegate.durable();
    }

    static String storedScope(String deploymentScopeId, String fleetId) {
        return SCOPE_PREFIX + deploymentScopeId.length() + ':' + deploymentScopeId + '/'
                + fleetId;
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
