package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.DynamicJwksTestSecretAuthorityTrustStore;
import com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityServingInventoryAuthority;
import com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityTrustCohortPolicy;
import com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityTrustCohortRepository;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAuthorityCohortPolicy;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAuthorityCohortRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Objects;

/**
 * Test-secret domain adapter over the database-clock exact trust-cohort authority.
 *
 * <p>The delegate already supplies transaction-serialized active-cohort election, process-start
 * leases, exact configured membership, immutable record fingerprints, corruption detection and
 * bounded stale-row deletion. This adapter owns the domain namespace and translates only
 * aggregate, payload-free protocol objects; no secret reference, key or plaintext enters the
 * database.</p>
 */
public final class DatabaseTestSecretAuthorityTrustCohortRepository
        implements TestSecretAuthorityTrustCohortRepository {

    private final TestSuiteStabilityAuthorityCohortPolicy databasePolicy;
    private final String policyFingerprint;
    private final DatabaseTestSuiteStabilityAuthorityCohortRepository delegate;

    /**
     * Creates one isolated test-secret trust cohort registry.
     *
     * @param jdbc test-runtime JDBC facade
     * @param objectMapper canonical fingerprint mapper
     * @param policy exact deployment-owned test-secret cohort policy
     * @param transactionManager manager for the same isolated datasource
     */
    public DatabaseTestSecretAuthorityTrustCohortRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            TestSecretAuthorityTrustCohortPolicy policy,
            PlatformTransactionManager transactionManager) {
        this.databasePolicy = Objects.requireNonNull(policy, "policy").asDatabasePolicy();
        ObjectMapper canonicalMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.policyFingerprint = databasePolicy.cohortFingerprint(canonicalMapper);
        this.delegate = new DatabaseTestSuiteStabilityAuthorityCohortRepository(
                Objects.requireNonNull(jdbc, "jdbc"),
                canonicalMapper, databasePolicy,
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    /** Initializes the shared additive registry schema through the proven database authority. */
    @PostConstruct
    public void init() {
        delegate.init();
    }

    /** Publishes one local generation under the namespaced exact deployment policy. */
    @Override
    public Snapshot heartbeat(
            DynamicJwksTestSecretAuthorityTrustStore.CohortObservation trustObservation,
            TestSecretAuthorityServingInventoryAuthority.Observation inventoryObservation) {
        Objects.requireNonNull(trustObservation, "trustObservation");
        Objects.requireNonNull(inventoryObservation, "inventoryObservation");
        return snapshot(delegate.heartbeat(new TestSuiteStabilityAuthorityCohortRepository.Member(
                TestSuiteStabilityAuthorityCohortRepository.Member.SCHEMA_VERSION,
                databasePolicy.scopeId(), databasePolicy.cohortId(), databasePolicy.instanceId(),
                databasePolicy.startupId(), databasePolicy.artifactFingerprint(),
                policyFingerprint,
                databasePolicy.protocolVersion(), databasePolicy.authorityId(),
                "DYNAMIC_JWKS_ED25519", trustObservation.available(),
                trustObservation.refreshState(), trustObservation.snapshotFingerprint(),
                inventoryObservation.sourceSequence(),
                inventoryObservation.sourceGenerationFingerprint(),
                trustObservation.activeKeyCount(),
                trustObservation.lastSuccessfulRefreshAt())));
    }

    /** Returns the current database-authoritative aggregate projection. */
    @Override
    public Snapshot snapshot() {
        return snapshot(delegate.snapshot());
    }

    /** Withdraws only the exact local process start represented by this adapter. */
    @Override
    public void withdraw(String instanceId, String startupId) {
        delegate.withdraw(instanceId, startupId);
    }

    private static Snapshot snapshot(TestSuiteStabilityAuthorityCohortRepository.Snapshot value) {
        return new Snapshot(Snapshot.SCHEMA_VERSION, value.converged(), value.status(),
                value.expectedReplicaCount(), value.liveReplicaCount(),
                value.healthyReplicaCount(), value.distinctSnapshotCount(),
                value.distinctServingInventoryGenerationCount(),
                value.missingReplicaCount(), value.unexpectedReplicaCount(),
                value.duplicateReplicaCount(), value.divergentArtifactCount(),
                value.divergentPolicyCount(), value.divergentProtocolCount(),
                value.divergentAuthorityCount(), value.observedAt(), value.nextLeaseExpiryAt(),
                value.blockers());
    }

}
