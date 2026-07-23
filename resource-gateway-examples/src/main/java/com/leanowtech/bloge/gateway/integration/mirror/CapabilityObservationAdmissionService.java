package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Protected application boundary for signed capability-observation admission.
 *
 * <p>The service authenticates complete enterprise scope before any lookup, recovers exact
 * idempotent retries before consulting mutable providers, resolves operator-owned policy, verifies
 * the exact capability revision and producer signature, evaluates purpose/age/residency/retention
 * constraints, and delegates payload-vault proof checks without reading payload bytes. Governed
 * rejections become durable quarantine decisions. Provider or store uncertainty fails with 503 and
 * creates no decision, because unknown trust must not be mislabeled as a business rejection.</p>
 */
@Service
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public class CapabilityObservationAdmissionService {
    /** Dedicated purpose required by observation producers. */
    public static final String AUTHORIZED_PURPOSE = "MIRROR_CORPUS_INGESTION";

    private final CapabilityObservationRepository observations;
    private final CapabilitySnapshotRepository capabilities;
    private final CapabilityObservationAdmissionPolicyProvider policies;
    private final CapabilityObservationPayloadReferenceVerifier payloadReferences;
    private final CapabilityObservationIntegrity observationIntegrity;
    private final CapabilityObservationAdmissionIntegrity admissionIntegrity;
    private final MirrorOperationObservability operationObservability;
    private final ObjectMapper mapper;
    private final Clock clock;

    /**
     * Creates the protected service using the server UTC clock.
     *
     * @param observations append-only observation and decision store
     * @param capabilities exact capability snapshot store
     * @param policies operator-owned admission policy source
     * @param payloadReferences external vault and sanitization-proof verifier
     * @param observationIntegrity producer signature and content verifier
     * @param admissionIntegrity local decision content-addressing boundary
     * @param operationObservability mandatory payload-free operation audit and metrics
     * @param mapper canonical protocol mapper
     */
    @Autowired
    public CapabilityObservationAdmissionService(
            CapabilityObservationRepository observations,
            CapabilitySnapshotRepository capabilities,
            CapabilityObservationAdmissionPolicyProvider policies,
            CapabilityObservationPayloadReferenceVerifier payloadReferences,
            CapabilityObservationIntegrity observationIntegrity,
            CapabilityObservationAdmissionIntegrity admissionIntegrity,
            MirrorOperationObservability operationObservability,
            ObjectMapper mapper) {
        this(
                observations,
                capabilities,
                policies,
                payloadReferences,
                observationIntegrity,
                admissionIntegrity,
                operationObservability,
                mapper,
                Clock.systemUTC());
    }

    /**
     * Full constructor for deterministic admission-window tests.
     *
     * @param observations append-only observation and decision store
     * @param capabilities exact capability snapshot store
     * @param policies operator-owned admission policy source
     * @param payloadReferences external vault and sanitization-proof verifier
     * @param observationIntegrity producer signature and content verifier
     * @param admissionIntegrity local decision content-addressing boundary
     * @param operationObservability mandatory payload-free operation audit and metrics
     * @param mapper canonical protocol mapper
     * @param clock trusted admission clock
     */
    public CapabilityObservationAdmissionService(
            CapabilityObservationRepository observations,
            CapabilitySnapshotRepository capabilities,
            CapabilityObservationAdmissionPolicyProvider policies,
            CapabilityObservationPayloadReferenceVerifier payloadReferences,
            CapabilityObservationIntegrity observationIntegrity,
            CapabilityObservationAdmissionIntegrity admissionIntegrity,
            MirrorOperationObservability operationObservability,
            ObjectMapper mapper,
            Clock clock) {
        this.observations = Objects.requireNonNull(observations, "observations");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.payloadReferences = Objects.requireNonNull(
                payloadReferences, "payloadReferences");
        this.observationIntegrity = Objects.requireNonNull(
                observationIntegrity, "observationIntegrity");
        this.admissionIntegrity = Objects.requireNonNull(
                admissionIntegrity, "admissionIntegrity");
        this.operationObservability = Objects.requireNonNull(
                operationObservability, "operationObservability");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Atomically admits or quarantines one signed observation.
     *
     * @param envelope untrusted decoded payload-free observation
     * @param identity authenticated observation producer
     * @return committed or idempotently recovered observation and terminal decision
     */
    @Transactional
    public CapabilityObservationRepository.StoredObservation ingest(
            CapabilityObservationEnvelope envelope,
            IntegrationRequestContext identity) {
        var operation = operationObservability.start(
                MirrorOperationAuditEvent.Operation.OBSERVATION_INGEST,
                identity,
                auditObservationId(envelope),
                auditCapabilityId(envelope),
                auditFingerprint(envelope));
        try {
            CapabilitySnapshot.Scope scope = requireIdentity(identity);
            if (envelope == null) {
                throw badRequest(
                        identity,
                        "RG.MIRROR.OBSERVATION_INVALID",
                        "A canonical capability observation is required.");
            }
            if (!scope.equals(envelope.material().scope())) {
                throw forbidden(
                        identity,
                        "RG.MIRROR.OBSERVATION_SCOPE_MISMATCH",
                        "The observation does not match the authenticated enterprise scope.");
            }
            Optional<CapabilityObservationRepository.StoredObservation> existing =
                    find(scope, envelope.material().observationId(), identity);
            if (existing.isPresent()) {
                CapabilityObservationRepository.StoredObservation stored = existing.get();
                if (!stored.envelope().observationFingerprint().equals(
                        envelope.observationFingerprint())) {
                    throw conflict(
                            identity,
                            "RG.MIRROR.OBSERVATION_ID_CONFLICT",
                            "The observation id is already bound to different immutable content.");
                }
                operation.succeeded(stored.admission().admissionFingerprint());
                return stored;
            }

            Instant now = clock.instant();
            Optional<CapabilityObservationAdmissionPolicyProvider.AdmissionPolicy>
                    resolved = resolvePolicy(envelope, identity);
            if (resolved.isEmpty()) {
                return persist(
                        envelope,
                        admissionIntegrity.quarantined(
                                envelope,
                                null,
                                null,
                                CapabilityObservationAdmission.Reason
                                        .ADMISSION_POLICY_NOT_FOUND,
                                now),
                        operation,
                        identity);
            }
            CapabilityObservationAdmissionPolicyProvider.AdmissionPolicy policy =
                    resolved.get();
            requireConsistentPolicy(policy, envelope, identity);

            if (!eligibleCapability(envelope, identity)) {
                return quarantine(
                        envelope,
                        policy,
                        CapabilityObservationAdmission.Reason.CAPABILITY_NOT_ELIGIBLE,
                        now,
                        operation,
                        identity);
            }
            CapabilityObservationAdmission.Reason policyRejection =
                    policyRejection(envelope, policy, now);
            if (policyRejection != null) {
                return quarantine(
                        envelope,
                        policy,
                        policyRejection,
                        now,
                        operation,
                        identity);
            }
            CapabilityObservationIntegrity.VerificationResult integrity =
                    observationIntegrity.verify(envelope, policy.authorityKey());
            if (!integrity.verified()) {
                return quarantine(
                        envelope,
                        policy,
                        CapabilityObservationAdmission.Reason.INTEGRITY_REJECTED,
                        now,
                        operation,
                        identity);
            }
            CapabilityObservationPayloadReferenceVerifier.VerificationResult
                    payloadVerification = verifyPayloadReferences(
                    envelope, policy, now, identity);
            if (payloadVerification.outcome()
                    == CapabilityObservationPayloadReferenceVerifier.Outcome.REJECTED) {
                return quarantine(
                        envelope,
                        policy,
                        CapabilityObservationAdmission.Reason.PAYLOAD_REFERENCE_REJECTED,
                        now,
                        operation,
                        identity);
            }
            Instant usableUntil = useHorizon(envelope);
            CapabilityObservationAdmission admission = admissionIntegrity.admitted(
                    envelope,
                    policy.policyRef(),
                    policy.authorityKey().keyRef(),
                    now,
                    usableUntil);
            return persist(envelope, admission, operation, identity);
        } catch (RuntimeException failure) {
            throw operation.failed(failure);
        }
    }

    private CapabilityObservationRepository.StoredObservation quarantine(
            CapabilityObservationEnvelope envelope,
            CapabilityObservationAdmissionPolicyProvider.AdmissionPolicy policy,
            CapabilityObservationAdmission.Reason reason,
            Instant now,
            MirrorOperationObservability.Observation operation,
            IntegrationRequestContext identity) {
        CapabilityObservationAdmission admission = admissionIntegrity.quarantined(
                envelope,
                policy.policyRef(),
                policy.authorityKey().keyRef(),
                reason,
                now);
        return persist(envelope, admission, operation, identity);
    }

    private CapabilityObservationRepository.StoredObservation persist(
            CapabilityObservationEnvelope envelope,
            CapabilityObservationAdmission admission,
            MirrorOperationObservability.Observation operation,
            IntegrationRequestContext identity) {
        try {
            CapabilityObservationRepository.StoredObservation stored =
                    observations.append(
                            new CapabilityObservationRepository.StoredObservation(
                                    envelope, admission));
            operation.succeeded(stored.admission().admissionFingerprint());
            return stored;
        } catch (CapabilityObservationRepository.Violation rejected) {
            throw repositoryFailure(rejected, identity);
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw serviceUnavailable(
                    identity,
                    "RG.MIRROR.OBSERVATION_STORE_UNAVAILABLE",
                    "The capability observation store is unavailable.");
        }
    }

    private Optional<CapabilityObservationRepository.StoredObservation> find(
            CapabilitySnapshot.Scope scope,
            String observationId,
            IntegrationRequestContext identity) {
        try {
            return observations.find(scope, observationId);
        } catch (CapabilityObservationRepository.Violation corrupt) {
            throw repositoryFailure(corrupt, identity);
        } catch (RuntimeException unavailable) {
            throw serviceUnavailable(
                    identity,
                    "RG.MIRROR.OBSERVATION_STORE_UNAVAILABLE",
                    "The capability observation store is unavailable.");
        }
    }

    private Optional<CapabilityObservationAdmissionPolicyProvider.AdmissionPolicy>
            resolvePolicy(
            CapabilityObservationEnvelope envelope,
            IntegrationRequestContext identity) {
        try {
            if (!policies.available()) {
                throw serviceUnavailable(
                        identity,
                        "RG.MIRROR.OBSERVATION_POLICY_UNAVAILABLE",
                        "The governed observation admission policy is unavailable.");
            }
            return policies.resolve(
                    envelope.material().scope(),
                    envelope.material().capabilityRef(),
                    envelope.material().dataUseGrant().grantRef(),
                    envelope.seal().keyId());
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw serviceUnavailable(
                    identity,
                    "RG.MIRROR.OBSERVATION_POLICY_UNAVAILABLE",
                    "The governed observation admission policy is unavailable.");
        }
    }

    private void requireConsistentPolicy(
            CapabilityObservationAdmissionPolicyProvider.AdmissionPolicy policy,
            CapabilityObservationEnvelope envelope,
            IntegrationRequestContext identity) {
        if (!policy.scope().equals(envelope.material().scope())
                || !policy.capabilityRef().equals(envelope.material().capabilityRef())
                || !policy.grantRef().equals(
                envelope.material().dataUseGrant().grantRef())
                || !policy.authorityKey().keyRef().id().equals(
                envelope.seal().keyId())) {
            throw serviceUnavailable(
                    identity,
                    "RG.MIRROR.OBSERVATION_POLICY_INVALID",
                    "The governed observation admission policy is inconsistent.");
        }
    }

    private boolean eligibleCapability(
            CapabilityObservationEnvelope envelope,
            IntegrationRequestContext identity) {
        MirrorArtifactRef ref = envelope.material().capabilityRef();
        Optional<CapabilitySnapshot> found;
        try {
            found = capabilities.find(
                    envelope.material().scope(), ref.id(), ref.revision());
        } catch (RuntimeException unavailable) {
            throw serviceUnavailable(
                    identity,
                    "RG.MIRROR.OBSERVATION_CAPABILITY_STORE_UNAVAILABLE",
                    "The exact capability snapshot store is unavailable.");
        }
        if (found.isEmpty()) {
            return false;
        }
        CapabilitySnapshot snapshot = found.get();
        try {
            CapabilitySnapshotIntegrity.verify(mapper, snapshot);
        } catch (RuntimeException drifted) {
            return false;
        }
        return ref.fingerprint().equals(snapshot.fingerprint())
                && (snapshot.lifecycle() == CapabilitySnapshot.Lifecycle.ACTIVE
                || snapshot.lifecycle() == CapabilitySnapshot.Lifecycle.DEPRECATED);
    }

    private static CapabilityObservationAdmission.Reason policyRejection(
            CapabilityObservationEnvelope envelope,
            CapabilityObservationAdmissionPolicyProvider.AdmissionPolicy policy,
            Instant now) {
        CapabilityObservationEnvelope.DataUseGrant grant =
                envelope.material().dataUseGrant();
        if (!grant.grantRef().equals(policy.grantRef())
                || !grant.activeAt(now)
                || !grant.allowedUses().containsAll(policy.requiredUses())) {
            return CapabilityObservationAdmission.Reason.DATA_USE_GRANT_REJECTED;
        }
        Instant occurredAt = envelope.material().occurredAt();
        if (occurredAt.isBefore(now.minus(policy.maximumObservationAge()))
                || occurredAt.isAfter(now.plus(policy.maximumFutureSkew()))) {
            return CapabilityObservationAdmission.Reason.OBSERVATION_WINDOW_REJECTED;
        }
        Instant minimumRetention =
                now.plus(policy.minimumRemainingRetention());
        if (!payloadAllowed(envelope.material().request(), policy, minimumRetention)
                || envelope.material().response() != null
                && !payloadAllowed(
                envelope.material().response(), policy, minimumRetention)) {
            return CapabilityObservationAdmission.Reason.PAYLOAD_POLICY_REJECTED;
        }
        return null;
    }

    private static boolean payloadAllowed(
            CapabilityObservationEnvelope.PayloadReference payload,
            CapabilityObservationAdmissionPolicyProvider.AdmissionPolicy policy,
            Instant minimumRetention) {
        return payload.sizeBytes() <= policy.maximumPayloadBytes()
                && policy.allowedClassifications().contains(payload.classification())
                && policy.allowedVaultRegions().contains(payload.vaultRegion())
                && !payload.retentionUntil().isBefore(minimumRetention);
    }

    private CapabilityObservationPayloadReferenceVerifier.VerificationResult
            verifyPayloadReferences(
            CapabilityObservationEnvelope envelope,
            CapabilityObservationAdmissionPolicyProvider.AdmissionPolicy policy,
            Instant now,
            IntegrationRequestContext identity) {
        try {
            if (!payloadReferences.available()) {
                throw serviceUnavailable(
                        identity,
                        "RG.MIRROR.OBSERVATION_PAYLOAD_AUTHORITY_UNAVAILABLE",
                        "The sanitized payload reference authority is unavailable.");
            }
            CapabilityObservationPayloadReferenceVerifier.VerificationResult result =
                    payloadReferences.verify(envelope, policy, now);
            if (result == null
                    || result.outcome()
                    == CapabilityObservationPayloadReferenceVerifier.Outcome.UNAVAILABLE) {
                throw serviceUnavailable(
                        identity,
                        "RG.MIRROR.OBSERVATION_PAYLOAD_AUTHORITY_UNAVAILABLE",
                        "The sanitized payload reference authority is unavailable.");
            }
            return result;
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw serviceUnavailable(
                    identity,
                    "RG.MIRROR.OBSERVATION_PAYLOAD_AUTHORITY_UNAVAILABLE",
                    "The sanitized payload reference authority is unavailable.");
        }
    }

    private static Instant useHorizon(CapabilityObservationEnvelope envelope) {
        Instant horizon = envelope.material().dataUseGrant().expiresAt();
        if (envelope.material().request().retentionUntil().isBefore(horizon)) {
            horizon = envelope.material().request().retentionUntil();
        }
        if (envelope.material().response() != null
                && envelope.material().response().retentionUntil().isBefore(horizon)) {
            horizon = envelope.material().response().retentionUntil();
        }
        return horizon;
    }

    private static CapabilitySnapshot.Scope requireIdentity(
            IntegrationRequestContext identity) {
        IntegrationRequestContext exact = Objects.requireNonNull(identity, "identity");
        exact.requireComplete();
        if (!AUTHORIZED_PURPOSE.equals(exact.purpose())) {
            throw forbidden(
                    exact,
                    "RG.MIRROR.OBSERVATION_PURPOSE_REQUIRED",
                    "Capability observation ingestion requires its dedicated purpose.");
        }
        if (exact.projectId().isBlank() || exact.region().isBlank()) {
            throw badRequest(
                    exact,
                    "RG.MIRROR.OBSERVATION_SCOPE_INCOMPLETE",
                    "Capability observation ingestion requires complete enterprise scope.");
        }
        if (!("test".equalsIgnoreCase(exact.environmentId())
                || "staging".equalsIgnoreCase(exact.environmentId()))) {
            throw forbidden(
                    exact,
                    "RG.MIRROR.OBSERVATION_ENVIRONMENT_FORBIDDEN",
                    "Capability observation ingestion is restricted to test and staging.");
        }
        return new CapabilitySnapshot.Scope(
                exact.tenantId(),
                exact.organizationId(),
                exact.projectId(),
                exact.environmentId(),
                exact.region());
    }

    private static IntegrationProblemException repositoryFailure(
            CapabilityObservationRepository.Violation rejected,
            IntegrationRequestContext identity) {
        return switch (rejected.reason()) {
            case CANONICAL_INVALID, IDENTITY_MISMATCH -> badRequest(
                    identity,
                    "RG.MIRROR.OBSERVATION_INVALID",
                    "The capability observation decision is not canonical.");
            case OBSERVATION_ID_CONFLICT -> conflict(
                    identity,
                    "RG.MIRROR.OBSERVATION_ID_CONFLICT",
                    "The observation id is already bound to different immutable content.");
            case STORED_STATE_CORRUPT -> serviceUnavailable(
                    identity,
                    "RG.MIRROR.OBSERVATION_STORE_UNAVAILABLE",
                    "The capability observation store failed integrity validation.");
        };
    }

    private static IntegrationProblemException badRequest(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                code, title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException forbidden(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.forbidden(
                code, title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException conflict(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.conflict(
                code, title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException serviceUnavailable(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                code, title, identity.correlationId(), Map.of()));
    }

    private static String auditObservationId(CapabilityObservationEnvelope envelope) {
        return envelope == null ? "" : envelope.material().observationId();
    }

    private static String auditCapabilityId(CapabilityObservationEnvelope envelope) {
        return envelope == null ? "" : envelope.material().capabilityRef().id();
    }

    private static String auditFingerprint(CapabilityObservationEnvelope envelope) {
        return envelope == null ? "" : envelope.observationFingerprint();
    }
}
