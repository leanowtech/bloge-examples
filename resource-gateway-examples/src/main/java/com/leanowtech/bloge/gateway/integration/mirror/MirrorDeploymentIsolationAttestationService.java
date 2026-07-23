package com.leanowtech.bloge.gateway.integration.mirror;

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
 * Protected trust-control application boundary for deployment-isolation attestations.
 *
 * <p>Ingest resolves operator-owned bootstrap policy and the current trusted authority
 * publication before verifying the external signature. It then commits the immutable proof,
 * initial local status, durable floor, and success audit in one transaction. Revocation is a
 * denial-only path and deliberately remains available during authority outages or after proof
 * expiry. Reads always return one atomic bundle; an active bundle is re-verified against the same
 * current authority generation, while a revoked bundle may be distributed without that positive
 * dependency so denial propagation cannot be blocked.</p>
 */
@Service
@Profile("!production & (test | staging)")
@ConditionalOnProperty(prefix = "gateway.testing.mirror", name = "enabled", havingValue = "true")
public class MirrorDeploymentIsolationAttestationService {
    /** Purpose required for ingest and irreversible revocation. */
    public static final String ADMIN_PURPOSE = "MIRROR_TRUST_ADMIN";
    /** Dedicated machine purpose accepted for current bundle distribution. */
    public static final String DISTRIBUTION_READ_PURPOSE = "MIRROR_TRUST_DISTRIBUTION";

    private final MirrorDeploymentIsolationAttestationRepository attestations;
    private final MirrorDeploymentIsolationAttestationAdmissionPolicyProvider admissionPolicies;
    private final MirrorDeploymentIsolationAuthorityPublicationRepository authorityPublications;
    private final MirrorDeploymentIsolationAuthorityTrustPolicyProvider authorityPolicies;
    private final MirrorDeploymentIsolationAuthorityKeySetIntegrity authorityIntegrity;
    private final MirrorDeploymentIsolationAttestationIntegrity attestationIntegrity;
    private final MirrorDeploymentIsolationAttestationBundleIntegrity bundleIntegrity;
    private final MirrorOperationObservability observations;
    private final Clock clock;

    /**
     * Creates the protected service using the server UTC clock.
     *
     * @param attestations durable attestation body, status, and floor repository
     * @param admissionPolicies operator-owned first-revision policy
     * @param authorityPublications durable current authority publication repository
     * @param authorityPolicies operator-owned bootstrap-root and binding policy
     * @param authorityIntegrity current authority publication verifier
     * @param attestationIntegrity external attestation verifier
     * @param bundleIntegrity local status and bundle content-addressing boundary
     * @param observations payload-free audit and metric boundary
     */
    @Autowired
    public MirrorDeploymentIsolationAttestationService(
            MirrorDeploymentIsolationAttestationRepository attestations,
            MirrorDeploymentIsolationAttestationAdmissionPolicyProvider admissionPolicies,
            MirrorDeploymentIsolationAuthorityPublicationRepository authorityPublications,
            MirrorDeploymentIsolationAuthorityTrustPolicyProvider authorityPolicies,
            MirrorDeploymentIsolationAuthorityKeySetIntegrity authorityIntegrity,
            MirrorDeploymentIsolationAttestationIntegrity attestationIntegrity,
            MirrorDeploymentIsolationAttestationBundleIntegrity bundleIntegrity,
            MirrorOperationObservability observations) {
        this(attestations, admissionPolicies, authorityPublications, authorityPolicies,
                authorityIntegrity, attestationIntegrity, bundleIntegrity, observations,
                Clock.systemUTC());
    }

    /**
     * Full constructor for deterministic time-window and transaction tests.
     *
     * @param attestations durable attestation body, status, and floor repository
     * @param admissionPolicies operator-owned first-revision policy
     * @param authorityPublications durable current authority publication repository
     * @param authorityPolicies operator-owned bootstrap-root and binding policy
     * @param authorityIntegrity current authority publication verifier
     * @param attestationIntegrity external attestation verifier
     * @param bundleIntegrity local status and bundle content-addressing boundary
     * @param observations payload-free audit and metric boundary
     * @param clock trusted validity and status-transition clock
     */
    public MirrorDeploymentIsolationAttestationService(
            MirrorDeploymentIsolationAttestationRepository attestations,
            MirrorDeploymentIsolationAttestationAdmissionPolicyProvider admissionPolicies,
            MirrorDeploymentIsolationAuthorityPublicationRepository authorityPublications,
            MirrorDeploymentIsolationAuthorityTrustPolicyProvider authorityPolicies,
            MirrorDeploymentIsolationAuthorityKeySetIntegrity authorityIntegrity,
            MirrorDeploymentIsolationAttestationIntegrity attestationIntegrity,
            MirrorDeploymentIsolationAttestationBundleIntegrity bundleIntegrity,
            MirrorOperationObservability observations,
            Clock clock) {
        this.attestations = Objects.requireNonNull(attestations, "attestations");
        this.admissionPolicies = Objects.requireNonNull(admissionPolicies, "admissionPolicies");
        this.authorityPublications = Objects.requireNonNull(
                authorityPublications, "authorityPublications");
        this.authorityPolicies = Objects.requireNonNull(authorityPolicies, "authorityPolicies");
        this.authorityIntegrity = Objects.requireNonNull(
                authorityIntegrity, "authorityIntegrity");
        this.attestationIntegrity = Objects.requireNonNull(
                attestationIntegrity, "attestationIntegrity");
        this.bundleIntegrity = Objects.requireNonNull(bundleIntegrity, "bundleIntegrity");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Verifies and atomically appends one trusted attestation revision and active status.
     *
     * @param deploymentScopeId exact governed deployment scope
     * @param keySetId exact current authority publication stream
     * @param attestation untrusted externally signed attestation
     * @param identity authenticated trust administrator
     * @return committed or idempotently recovered atomic bundle
     */
    @Transactional
    public MirrorDeploymentIsolationAttestationBundle ingest(
            String deploymentScopeId,
            String keySetId,
            MirrorDeploymentIsolationAttestation attestation,
            IntegrationRequestContext identity) {
        String attestationId = attestation == null ? ""
                : attestation.material().attestationId();
        String fingerprint = attestation == null ? ""
                : attestation.attestationFingerprint();
        var observation = observations.start(
                MirrorOperationAuditEvent.Operation.ISOLATION_ATTESTATION_INGEST, identity,
                auditIdentifier(attestationId), auditIdentifier(deploymentScopeId),
                auditFingerprint(fingerprint));
        try {
            CapabilitySnapshot.Scope scope = requireIdentity(identity, true);
            String deploymentId = identifier(deploymentScopeId, identity);
            String streamId = identifier(keySetId, identity);
            if (attestation == null) {
                throw badRequest(identity, "RG.MIRROR.ISOLATION_ATTESTATION_INVALID",
                        "A canonical deployment-isolation attestation is required.");
            }
            var admission = admissionPolicy(scope, deploymentId, streamId,
                    attestation.material().attestationId(), identity);
            requireAdmissionCoordinates(admission, scope, deploymentId, streamId,
                    attestation.material().attestationId(), identity);
            if (!admission.deployment().equals(attestation.material().deployment())) {
                throw forbidden(identity, "RG.MIRROR.ISOLATION_ATTESTATION_SCOPE_MISMATCH",
                        "The attestation deployment does not match local admission policy.");
            }
            Instant verificationTime = clock.instant();
            ResolvedAuthority authority = resolveCurrentAuthority(
                    scope, deploymentId, streamId, verificationTime, identity);
            var key = authority.verification().attestationKey(attestation.seal().keyId())
                    .orElseThrow(() -> conflict(identity,
                            "RG.MIRROR.ISOLATION_ATTESTATION_KEY_NOT_CURRENT",
                            "The attestation signing key is absent or revoked in current authority policy."));
            var verification = attestationIntegrity.verifyAt(attestation, key,
                    admission.deployment(), verificationTime);
            if (!verification.verified()) {
                throw attestationAdmissionFailure(verification, identity);
            }
            var status = bundleIntegrity.activeStatus(scope,
                    authority.publication().artifactRef(), attestation, verificationTime);
            var candidate = bundleIntegrity.bundle(scope,
                    authority.publication().artifactRef(), attestation, status);
            MirrorDeploymentIsolationAttestationBundle stored;
            try {
                stored = attestations.append(candidate, admission.bootstrapRevision());
            } catch (MirrorDeploymentIsolationAttestationRepository.Violation rejected) {
                throw repositoryFailure(rejected, identity);
            }
            observation.succeeded(stored.bundleFingerprint());
            return stored;
        } catch (RuntimeException failure) {
            throw observation.failed(failure);
        }
    }

    /**
     * Reads and re-verifies the atomic current bundle for one governed stream.
     *
     * @param deploymentScopeId exact governed deployment scope
     * @param keySetId exact authority publication stream
     * @param attestationId exact external attestation stream
     * @param identity authenticated distribution reader
     * @return current active or revoked atomic bundle
     */
    @Transactional
    public MirrorDeploymentIsolationAttestationBundle current(
            String deploymentScopeId,
            String keySetId,
            String attestationId,
            IntegrationRequestContext identity) {
        return read(deploymentScopeId, keySetId, attestationId, null, identity);
    }

    /**
     * Reads exact coordinates only while they still equal the durable current floor.
     *
     * @param deploymentScopeId exact governed deployment scope
     * @param keySetId exact authority publication stream
     * @param attestationId exact external attestation stream
     * @param expected exact current attestation and status coordinates
     * @param identity authenticated distribution reader
     * @return current active or revoked atomic bundle
     */
    @Transactional
    public MirrorDeploymentIsolationAttestationBundle current(
            String deploymentScopeId,
            String keySetId,
            String attestationId,
            MirrorDeploymentIsolationAttestationRepository.CurrentExpectation expected,
            IntegrationRequestContext identity) {
        return read(deploymentScopeId, keySetId, attestationId,
                Objects.requireNonNull(expected, "expected"), identity);
    }

    private MirrorDeploymentIsolationAttestationBundle read(
            String deploymentScopeId,
            String keySetId,
            String attestationId,
            MirrorDeploymentIsolationAttestationRepository.CurrentExpectation expected,
            IntegrationRequestContext identity) {
        var observation = observations.start(
                MirrorOperationAuditEvent.Operation.ISOLATION_ATTESTATION_READ, identity,
                auditIdentifier(attestationId), auditIdentifier(deploymentScopeId),
                expected == null ? "" : expected.attestationFingerprint());
        try {
            CapabilitySnapshot.Scope scope = requireIdentity(identity, false);
            String deploymentId = identifier(deploymentScopeId, identity);
            String streamId = identifier(keySetId, identity);
            String proofId = identifier(attestationId, identity);
            var admission = admissionPolicy(
                    scope, deploymentId, streamId, proofId, identity);
            requireAdmissionCoordinates(
                    admission, scope, deploymentId, streamId, proofId, identity);
            var stream = stream(admission);
            Optional<MirrorDeploymentIsolationAttestationBundle> found;
            try {
                found = expected == null
                        ? attestations.current(stream) : attestations.current(stream, expected);
            } catch (MirrorDeploymentIsolationAttestationRepository.Violation rejected) {
                throw repositoryFailure(rejected, identity);
            }
            MirrorDeploymentIsolationAttestationBundle bundle = found.orElseThrow(
                    () -> notFound(identity));
            if (bundle.active()) {
                Instant verificationTime = clock.instant();
                ResolvedAuthority authority = resolveCurrentAuthority(
                        scope, deploymentId, streamId, verificationTime, identity);
                if (!bundle.authorityKeySetRef().equals(authority.publication().artifactRef())) {
                    throw conflict(identity,
                            "RG.MIRROR.ISOLATION_ATTESTATION_AUTHORITY_SUPERSEDED",
                            "The active attestation is not bound to the current authority generation.");
                }
                var key = authority.verification().attestationKey(
                        bundle.attestation().seal().keyId()).orElseThrow(() -> gone(identity,
                                "RG.MIRROR.ISOLATION_ATTESTATION_AUTHORITY_REVOKED",
                                "The active attestation signing key is no longer trusted."));
                var verification = attestationIntegrity.verifyAt(bundle.attestation(), key,
                        admission.deployment(), verificationTime);
                if (!verification.verified()) {
                    throw attestationServingFailure(verification, identity);
                }
            }
            observation.succeeded(bundle.bundleFingerprint());
            return bundle;
        } catch (RuntimeException failure) {
            throw observation.failed(failure);
        }
    }

    /**
     * Irreversibly revokes the exact current attestation without requiring positive authority
     * availability.
     *
     * @param deploymentScopeId exact governed deployment scope
     * @param keySetId exact authority publication stream
     * @param attestationId exact external attestation stream
     * @param request optimistic exact-current revocation command
     * @param identity authenticated trust administrator
     * @return current revoked atomic bundle
     */
    @Transactional
    public MirrorDeploymentIsolationAttestationBundle revoke(
            String deploymentScopeId,
            String keySetId,
            String attestationId,
            MirrorDeploymentIsolationAttestationRevocationRequest request,
            IntegrationRequestContext identity) {
        var observation = observations.start(
                MirrorOperationAuditEvent.Operation.ISOLATION_ATTESTATION_REVOKE, identity,
                auditIdentifier(attestationId), auditIdentifier(deploymentScopeId),
                request == null ? "" : auditFingerprint(request.attestationFingerprint()));
        try {
            CapabilitySnapshot.Scope scope = requireIdentity(identity, true);
            String deploymentId = identifier(deploymentScopeId, identity);
            String streamId = identifier(keySetId, identity);
            String proofId = identifier(attestationId, identity);
            if (request == null) {
                throw badRequest(identity,
                        "RG.MIRROR.ISOLATION_ATTESTATION_REVOCATION_INVALID",
                        "An exact irreversible revocation command is required.");
            }
            var admission = admissionPolicy(
                    scope, deploymentId, streamId, proofId, identity);
            requireAdmissionCoordinates(
                    admission, scope, deploymentId, streamId, proofId, identity);
            MirrorDeploymentIsolationAttestationBundle revoked;
            try {
                revoked = attestations.revoke(stream(admission), request.expectation(),
                        request.reason(), clock.instant());
            } catch (MirrorDeploymentIsolationAttestationRepository.Violation rejected) {
                throw repositoryFailure(rejected, identity);
            }
            observation.succeeded(revoked.bundleFingerprint());
            return revoked;
        } catch (RuntimeException failure) {
            throw observation.failed(failure);
        }
    }

    private MirrorDeploymentIsolationAttestationAdmissionPolicyProvider.AdmissionPolicy
    admissionPolicy(
            CapabilitySnapshot.Scope scope,
            String deploymentScopeId,
            String keySetId,
            String attestationId,
            IntegrationRequestContext identity) {
        try {
            if (!admissionPolicies.available()) {
                throw unavailable(identity,
                        "RG.MIRROR.ISOLATION_ATTESTATION_POLICY_UNAVAILABLE",
                        "The local isolation-attestation admission policy is unavailable.");
            }
            return admissionPolicies.resolve(
                    scope, deploymentScopeId, keySetId, attestationId)
                    .orElseThrow(() -> notFound(identity));
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity,
                    "RG.MIRROR.ISOLATION_ATTESTATION_POLICY_UNAVAILABLE",
                    "The local isolation-attestation admission policy is unavailable.");
        }
    }

    private ResolvedAuthority resolveCurrentAuthority(
            CapabilitySnapshot.Scope scope,
            String deploymentScopeId,
            String keySetId,
            Instant verificationTime,
            IntegrationRequestContext identity) {
        MirrorDeploymentIsolationAuthorityTrustPolicyProvider.TrustPolicy policy;
        try {
            if (!authorityPolicies.available()) {
                throw unavailable(identity,
                        "RG.MIRROR.ISOLATION_ATTESTATION_AUTHORITY_UNAVAILABLE",
                        "The local isolation-authority trust policy is unavailable.");
            }
            policy = authorityPolicies.resolve(scope, deploymentScopeId, keySetId)
                    .orElseThrow(() -> unavailable(identity,
                            "RG.MIRROR.ISOLATION_ATTESTATION_AUTHORITY_UNAVAILABLE",
                            "No current isolation-authority policy is available for this stream."));
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity,
                    "RG.MIRROR.ISOLATION_ATTESTATION_AUTHORITY_UNAVAILABLE",
                    "The local isolation-authority trust policy is unavailable.");
        }
        var binding = policy.binding();
        if (!binding.scope().equals(scope)
                || !binding.deployment().deploymentScopeId().equals(deploymentScopeId)
                || !binding.keySetId().equals(keySetId) || policy.roots().isEmpty()) {
            throw unavailable(identity,
                    "RG.MIRROR.ISOLATION_ATTESTATION_AUTHORITY_UNAVAILABLE",
                    "The local isolation-authority trust policy is inconsistent.");
        }
        var stream = new MirrorDeploymentIsolationAuthorityPublicationRepository.StreamIdentity(
                scope, binding.deployment(), keySetId);
        MirrorDeploymentIsolationAuthorityKeySetPublication publication;
        MirrorDeploymentIsolationAuthorityKeySetIntegrity.TrustedFloor floor;
        try {
            publication = authorityPublications.latest(stream).orElseThrow(
                    () -> unavailable(identity,
                            "RG.MIRROR.ISOLATION_ATTESTATION_AUTHORITY_UNAVAILABLE",
                            "No current isolation-authority publication is available."));
            floor = authorityPublications.floor(stream).orElseThrow(
                    () -> unavailable(identity,
                            "RG.MIRROR.ISOLATION_ATTESTATION_AUTHORITY_UNAVAILABLE",
                            "The current isolation-authority floor is unavailable."));
        } catch (MirrorDeploymentIsolationAuthorityPublicationRepository.Violation corrupted) {
            throw unavailable(identity,
                    "RG.MIRROR.ISOLATION_ATTESTATION_AUTHORITY_UNAVAILABLE",
                    "The current isolation-authority store failed integrity validation.");
        }
        var verification = authorityIntegrity.verify(
                publication, binding, policy.roots(), floor, verificationTime);
        if (!verification.verified()) {
            if (verification.outcome()
                    == MirrorDeploymentIsolationAuthorityKeySetIntegrity.Outcome.WINDOW_REJECTED) {
                throw gone(identity, "RG.MIRROR.ISOLATION_ATTESTATION_AUTHORITY_EXPIRED",
                        "The current isolation-authority publication is no longer active.");
            }
            throw unavailable(identity,
                    "RG.MIRROR.ISOLATION_ATTESTATION_AUTHORITY_UNAVAILABLE",
                    "The current isolation-authority publication could not be re-verified.");
        }
        return new ResolvedAuthority(publication, verification);
    }

    private static void requireAdmissionCoordinates(
            MirrorDeploymentIsolationAttestationAdmissionPolicyProvider.AdmissionPolicy policy,
            CapabilitySnapshot.Scope scope,
            String deploymentScopeId,
            String keySetId,
            String attestationId,
            IntegrationRequestContext identity) {
        if (!policy.scope().equals(scope)
                || !policy.deployment().deploymentScopeId().equals(deploymentScopeId)
                || !policy.keySetId().equals(keySetId)
                || !policy.attestationId().equals(attestationId)) {
            throw unavailable(identity,
                    "RG.MIRROR.ISOLATION_ATTESTATION_POLICY_INVALID",
                    "The local isolation-attestation admission policy is inconsistent.");
        }
    }

    private static MirrorDeploymentIsolationAttestationRepository.StreamIdentity stream(
            MirrorDeploymentIsolationAttestationAdmissionPolicyProvider.AdmissionPolicy policy) {
        return new MirrorDeploymentIsolationAttestationRepository.StreamIdentity(
                policy.scope(), policy.deployment(), policy.keySetId(), policy.attestationId());
    }

    private static CapabilitySnapshot.Scope requireIdentity(
            IntegrationRequestContext identity, boolean admin) {
        IntegrationRequestContext required = Objects.requireNonNull(identity, "identity");
        required.requireComplete();
        boolean purposeAccepted = admin ? ADMIN_PURPOSE.equals(required.purpose())
                : DISTRIBUTION_READ_PURPOSE.equals(required.purpose())
                || MirrorPlanIntegrationService.AUTHORIZED_PURPOSE.equals(required.purpose());
        if (!purposeAccepted) {
            throw forbidden(required, "RG.MIRROR.ISOLATION_ATTESTATION_PURPOSE_REQUIRED",
                    "A dedicated deployment-isolation trust purpose is required.");
        }
        if (required.projectId().isBlank() || required.region().isBlank()) {
            throw badRequest(required, "RG.MIRROR.ISOLATION_ATTESTATION_SCOPE_INCOMPLETE",
                    "Isolation-attestation operations require complete enterprise scope.");
        }
        if (!("test".equalsIgnoreCase(required.environmentId())
                || "staging".equalsIgnoreCase(required.environmentId()))) {
            throw forbidden(required,
                    "RG.MIRROR.ISOLATION_ATTESTATION_ENVIRONMENT_FORBIDDEN",
                    "Isolation-attestation operations are restricted to test and staging.");
        }
        return new CapabilitySnapshot.Scope(required.tenantId(), required.organizationId(),
                required.projectId(), required.environmentId(), required.region());
    }

    private static IntegrationProblemException attestationAdmissionFailure(
            MirrorDeploymentIsolationAttestationIntegrity.VerificationResult verification,
            IntegrationRequestContext identity) {
        return switch (verification.outcome()) {
            case INVALID -> badRequest(identity,
                    "RG.MIRROR.ISOLATION_ATTESTATION_INVALID",
                    "The deployment-isolation attestation failed canonical signature validation.");
            case KEY_UNAVAILABLE -> conflict(identity,
                    "RG.MIRROR.ISOLATION_ATTESTATION_KEY_NOT_CURRENT",
                    "The attestation key is absent from the current authority publication.");
            case POLICY_REJECTED, IDENTITY_MISMATCH -> forbidden(identity,
                    "RG.MIRROR.ISOLATION_ATTESTATION_POLICY_REJECTED",
                    "The deployment-isolation attestation violates local trust policy.");
            case WINDOW_REJECTED -> gone(identity,
                    "RG.MIRROR.ISOLATION_ATTESTATION_EXPIRED",
                    "The deployment-isolation attestation is not active at admission time.");
            case VERIFIED -> throw new IllegalArgumentException(
                    "verified attestation cannot produce an admission failure");
        };
    }

    private static IntegrationProblemException attestationServingFailure(
            MirrorDeploymentIsolationAttestationIntegrity.VerificationResult verification,
            IntegrationRequestContext identity) {
        if (verification.outcome()
                == MirrorDeploymentIsolationAttestationIntegrity.Outcome.WINDOW_REJECTED) {
            return gone(identity, "RG.MIRROR.ISOLATION_ATTESTATION_EXPIRED",
                    "The current deployment-isolation attestation is no longer active.");
        }
        if (verification.outcome()
                == MirrorDeploymentIsolationAttestationIntegrity.Outcome.POLICY_REJECTED) {
            return gone(identity, "RG.MIRROR.ISOLATION_ATTESTATION_AUTHORITY_REVOKED",
                    "The current deployment-isolation attestation authority is no longer trusted.");
        }
        return unavailable(identity, "RG.MIRROR.ISOLATION_ATTESTATION_NOT_TRUSTED",
                "The current deployment-isolation attestation could not be re-verified.");
    }

    private static IntegrationProblemException repositoryFailure(
            MirrorDeploymentIsolationAttestationRepository.Violation rejected,
            IntegrationRequestContext identity) {
        return switch (rejected.reason()) {
            case CANONICAL_INVALID -> badRequest(identity,
                    "RG.MIRROR.ISOLATION_ATTESTATION_INVALID",
                    "The deployment-isolation attestation bundle is not canonical.");
            case STORED_STATE_CORRUPT -> unavailable(identity,
                    "RG.MIRROR.ISOLATION_ATTESTATION_STORE_UNAVAILABLE",
                    "The deployment-isolation attestation store failed integrity validation.");
            case IDENTITY_MISMATCH -> forbidden(identity,
                    "RG.MIRROR.ISOLATION_ATTESTATION_SCOPE_MISMATCH",
                    "The deployment-isolation attestation stream identity does not match.");
            case BOOTSTRAP_REVISION_MISMATCH, REVISION_ROLLBACK, REVISION_FORK,
                    REVISION_GAP, CONTENT_ADDRESS_CONFLICT -> conflict(identity,
                    "RG.MIRROR.ISOLATION_ATTESTATION_REVISION_CONFLICT",
                    "The attestation revision conflicts with the durable current floor.");
            case STATUS_CONFLICT -> conflict(identity,
                    "RG.MIRROR.ISOLATION_ATTESTATION_STATUS_CONFLICT",
                    "The revocation command does not match the current attestation status.");
        };
    }

    private static String identifier(String value, IntegrationRequestContext identity) {
        String exact = normalized(value);
        if (!exact.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}")) {
            throw badRequest(identity, "RG.MIRROR.ISOLATION_ATTESTATION_REF_INVALID",
                    "The deployment-isolation attestation reference is invalid.");
        }
        return exact;
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

    private static IntegrationProblemException gone(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.gone(
                code, title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                code, title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException notFound(IntegrationRequestContext identity) {
        return new IntegrationProblemException(IntegrationProblem.notFound(
                "RG.MIRROR.ISOLATION_ATTESTATION_NOT_FOUND",
                "Deployment-isolation attestation was not found in the authorized scope.",
                identity.correlationId(), Map.of()));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static String auditIdentifier(String value) {
        String exact = normalized(value);
        return exact.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}") ? exact : "";
    }

    private static String auditFingerprint(String value) {
        String exact = normalized(value);
        return exact.matches("sha256:[a-f0-9]{64}") ? exact : "";
    }

    private record ResolvedAuthority(
            MirrorDeploymentIsolationAuthorityKeySetPublication publication,
            MirrorDeploymentIsolationAuthorityKeySetIntegrity.VerificationResult verification) {
    }
}
