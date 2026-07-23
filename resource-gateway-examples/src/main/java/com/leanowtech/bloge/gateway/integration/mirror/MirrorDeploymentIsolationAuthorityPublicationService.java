package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Protected trusted-distribution application boundary for isolation-authority key sets.
 *
 * <p>Admission first binds the authenticated enterprise scope, then resolves independently
 * governed local trust, verifies canonical content, signatures, policy, validity, and the durable
 * floor, and finally appends the publication plus floor CAS in one transaction. Reads resolve the
 * same local policy and serve only a currently verified floor, never an arbitrary historical
 * generation. Every terminal path is represented by payload-free Mirror operation audit data.</p>
 */
@Service
@Profile("!production & (test | staging)")
@ConditionalOnProperty(prefix = "gateway.testing.mirror", name = "enabled", havingValue = "true")
public class MirrorDeploymentIsolationAuthorityPublicationService {
    /** Purpose required to append trusted authority material. */
    public static final String PUBLISH_PURPOSE = "MIRROR_TRUST_ADMIN";
    /** Dedicated machine purpose accepted for trusted distribution reads. */
    public static final String DISTRIBUTION_READ_PURPOSE = "MIRROR_TRUST_DISTRIBUTION";

    private final MirrorDeploymentIsolationAuthorityPublicationRepository publications;
    private final MirrorDeploymentIsolationAuthorityTrustPolicyProvider trustPolicies;
    private final MirrorDeploymentIsolationAuthorityKeySetIntegrity integrity;
    private final MirrorOperationObservability observations;
    private final Clock clock;

    /**
     * Creates the protected service using the server UTC clock.
     *
     * @param publications durable publication and trusted-floor repository
     * @param trustPolicies operator-owned local trust-policy snapshots
     * @param integrity canonical binding and signature verifier
     * @param observations payload-free audit and metric boundary
     */
    @Autowired
    public MirrorDeploymentIsolationAuthorityPublicationService(
            MirrorDeploymentIsolationAuthorityPublicationRepository publications,
            MirrorDeploymentIsolationAuthorityTrustPolicyProvider trustPolicies,
            MirrorDeploymentIsolationAuthorityKeySetIntegrity integrity,
            MirrorOperationObservability observations) {
        this(publications, trustPolicies, integrity, observations, Clock.systemUTC());
    }

    /**
     * Full constructor for deterministic validity-window and policy tests.
     *
     * @param publications durable publication and trusted-floor repository
     * @param trustPolicies operator-owned local trust-policy snapshots
     * @param integrity canonical binding and signature verifier
     * @param observations payload-free audit and metric boundary
     * @param clock validity-window clock
     */
    public MirrorDeploymentIsolationAuthorityPublicationService(
            MirrorDeploymentIsolationAuthorityPublicationRepository publications,
            MirrorDeploymentIsolationAuthorityTrustPolicyProvider trustPolicies,
            MirrorDeploymentIsolationAuthorityKeySetIntegrity integrity,
            MirrorOperationObservability observations,
            Clock clock) {
        this.publications = Objects.requireNonNull(publications, "publications");
        this.trustPolicies = Objects.requireNonNull(trustPolicies, "trustPolicies");
        this.integrity = Objects.requireNonNull(integrity, "integrity");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Verifies and atomically appends one authority key-set generation.
     *
     * @param publication untrusted threshold-signed publication
     * @param identity authenticated publisher identity
     * @return committed or idempotently recovered publication
     */
    @Transactional
    public MirrorDeploymentIsolationAuthorityKeySetPublication publish(
            MirrorDeploymentIsolationAuthorityKeySetPublication publication,
            IntegrationRequestContext identity) {
        String keySetId = publication == null ? "" : publication.material().keySetId();
        String deploymentScopeId = publication == null ? ""
                : publication.material().deployment().deploymentScopeId();
        String fingerprint = publication == null ? "" : publication.publicationFingerprint();
        MirrorOperationObservability.Observation observation = observations.start(
                MirrorOperationAuditEvent.Operation.AUTHORITY_KEY_SET_PUBLISH, identity,
                keySetId, deploymentScopeId, fingerprint);
        try {
            MirrorDeploymentIsolationAuthorityKeySetPublication stored = publishObserved(
                    publication, identity);
            observation.succeeded(stored.publicationFingerprint());
            return stored;
        } catch (RuntimeException failure) {
            throw observation.failed(failure);
        }
    }

    private MirrorDeploymentIsolationAuthorityKeySetPublication publishObserved(
            MirrorDeploymentIsolationAuthorityKeySetPublication publication,
            IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope = requireIdentity(identity, true);
        if (publication == null || !scope.equals(publication.material().scope())) {
            throw forbidden(identity, "RG.MIRROR.AUTHORITY_PUBLICATION_SCOPE_MISMATCH",
                    "Authority publication scope does not match the authenticated scope.");
        }
        MirrorDeploymentIsolationAuthorityTrustPolicyProvider.TrustPolicy policy = policy(
                scope, publication.material().deployment().deploymentScopeId(),
                publication.material().keySetId(), identity);
        requirePolicyCoordinates(policy, scope, publication.material().deployment(),
                publication.material().deployment().deploymentScopeId(),
                publication.material().keySetId(), identity);
        var stream = new MirrorDeploymentIsolationAuthorityPublicationRepository.StreamIdentity(
                scope, policy.binding().deployment(), policy.binding().keySetId());
        var floor = publications.floor(stream).orElse(null);
        var verification = integrity.verify(publication, policy.binding(), policy.roots(), floor,
                clock.instant());
        if (!verification.verified()) {
            throw admissionFailure(verification, identity);
        }
        try {
            return publications.append(publication);
        } catch (MirrorDeploymentIsolationAuthorityPublicationRepository.Violation rejected) {
            throw repositoryFailure(rejected, identity);
        }
    }

    /**
     * Reads and re-verifies the current durable publication for one local stream.
     *
     * @param deploymentScopeId exact governed deployment scope
     * @param keySetId exact governed key-set stream
     * @param identity authenticated distribution reader
     * @return current, locally re-verified publication
     */
    @Transactional
    public MirrorDeploymentIsolationAuthorityKeySetPublication latest(
            String deploymentScopeId,
            String keySetId,
            IntegrationRequestContext identity) {
        return read(deploymentScopeId, keySetId, 0, "", false, identity);
    }

    /**
     * Reads a content-addressed generation only when it equals the current durable floor.
     *
     * @param deploymentScopeId exact governed deployment scope
     * @param keySetId exact governed key-set stream
     * @param generation exact expected floor generation
     * @param publicationFingerprint exact expected floor fingerprint
     * @param identity authenticated distribution reader
     * @return current, locally re-verified publication
     */
    @Transactional
    public MirrorDeploymentIsolationAuthorityKeySetPublication current(
            String deploymentScopeId,
            String keySetId,
            long generation,
            String publicationFingerprint,
            IntegrationRequestContext identity) {
        return read(deploymentScopeId, keySetId, generation, publicationFingerprint, true,
                identity);
    }

    private MirrorDeploymentIsolationAuthorityKeySetPublication read(
            String deploymentScopeId,
            String keySetId,
            long generation,
            String publicationFingerprint,
            boolean contentAddressed,
            IntegrationRequestContext identity) {
        MirrorOperationObservability.Observation observation = observations.start(
                MirrorOperationAuditEvent.Operation.AUTHORITY_KEY_SET_READ, identity,
                auditIdentifier(keySetId), auditIdentifier(deploymentScopeId),
                auditFingerprint(publicationFingerprint));
        try {
            CapabilitySnapshot.Scope scope = requireIdentity(identity, false);
            String deploymentId = identifier(deploymentScopeId, "deploymentScopeId", identity);
            String streamId = identifier(keySetId, "keySetId", identity);
            if (contentAddressed && (generation < 1
                    || !normalized(publicationFingerprint)
                    .matches("sha256:[a-f0-9]{64}"))) {
                throw badRequest(identity, "RG.MIRROR.AUTHORITY_PUBLICATION_REF_INVALID",
                        "A canonical current authority publication reference is required.");
            }
            var policy = policy(scope, deploymentId, streamId, identity);
            requirePolicyCoordinates(policy, scope, policy.binding().deployment(), deploymentId,
                    streamId, identity);
            var stream = new MirrorDeploymentIsolationAuthorityPublicationRepository.StreamIdentity(
                    scope, policy.binding().deployment(), streamId);
            Optional<MirrorDeploymentIsolationAuthorityKeySetPublication> found = contentAddressed
                    ? publications.current(stream, generation, normalized(publicationFingerprint))
                    : publications.latest(stream);
            MirrorDeploymentIsolationAuthorityKeySetPublication publication = found.orElseThrow(
                    () -> notFound(identity));
            var floor = publications.floor(stream).orElseThrow(() -> unavailable(identity,
                    "RG.MIRROR.AUTHORITY_PUBLICATION_STORE_UNAVAILABLE",
                    "The trusted authority publication floor is unavailable."));
            var verification = integrity.verify(publication, policy.binding(), policy.roots(),
                    floor, clock.instant());
            if (!verification.verified()) {
                throw servingFailure(verification, identity);
            }
            observation.succeeded(publication.publicationFingerprint());
            return publication;
        } catch (MirrorDeploymentIsolationAuthorityPublicationRepository.Violation rejected) {
            throw observation.failed(repositoryFailure(rejected, identity));
        } catch (RuntimeException failure) {
            throw observation.failed(failure);
        }
    }

    private MirrorDeploymentIsolationAuthorityTrustPolicyProvider.TrustPolicy policy(
            CapabilitySnapshot.Scope scope,
            String deploymentScopeId,
            String keySetId,
            IntegrationRequestContext identity) {
        try {
            if (!trustPolicies.available()) {
                throw unavailable(identity, "RG.MIRROR.AUTHORITY_TRUST_UNAVAILABLE",
                        "The local isolation-authority trust policy is unavailable.");
            }
            return trustPolicies.resolve(scope, deploymentScopeId, keySetId)
                    .orElseThrow(() -> notFound(identity));
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.MIRROR.AUTHORITY_TRUST_UNAVAILABLE",
                    "The local isolation-authority trust policy is unavailable.");
        }
    }

    private static void requirePolicyCoordinates(
            MirrorDeploymentIsolationAuthorityTrustPolicyProvider.TrustPolicy policy,
            CapabilitySnapshot.Scope scope,
            MirrorDeploymentIsolationAttestation.DeploymentIdentity deployment,
            String deploymentScopeId,
            String keySetId,
            IntegrationRequestContext identity) {
        var binding = policy.binding();
        if (!binding.scope().equals(scope) || !binding.deployment().equals(deployment)
                || !binding.deployment().deploymentScopeId().equals(deploymentScopeId)
                || !binding.keySetId().equals(keySetId) || policy.roots().isEmpty()) {
            throw unavailable(identity, "RG.MIRROR.AUTHORITY_TRUST_POLICY_INVALID",
                    "The local isolation-authority trust policy is inconsistent.");
        }
    }

    private static CapabilitySnapshot.Scope requireIdentity(
            IntegrationRequestContext identity, boolean publish) {
        IntegrationRequestContext required = Objects.requireNonNull(identity, "identity");
        required.requireComplete();
        boolean purposeAccepted = publish ? PUBLISH_PURPOSE.equals(required.purpose())
                : DISTRIBUTION_READ_PURPOSE.equals(required.purpose())
                || MirrorPlanIntegrationService.AUTHORIZED_PURPOSE.equals(required.purpose());
        if (!purposeAccepted) {
            throw forbidden(required, "RG.MIRROR.AUTHORITY_PURPOSE_REQUIRED",
                    "A dedicated isolation-authority trust purpose is required.");
        }
        if (required.projectId().isBlank() || required.region().isBlank()) {
            throw badRequest(required, "RG.MIRROR.AUTHORITY_SCOPE_INCOMPLETE",
                    "Authority publication operations require complete enterprise scope.");
        }
        if (!("test".equalsIgnoreCase(required.environmentId())
                || "staging".equalsIgnoreCase(required.environmentId()))) {
            throw forbidden(required, "RG.MIRROR.AUTHORITY_ENVIRONMENT_FORBIDDEN",
                    "Authority publication operations are restricted to test and staging.");
        }
        return new CapabilitySnapshot.Scope(required.tenantId(), required.organizationId(),
                required.projectId(), required.environmentId(), required.region());
    }

    private static IntegrationProblemException admissionFailure(
            MirrorDeploymentIsolationAuthorityKeySetIntegrity.VerificationResult verification,
            IntegrationRequestContext identity) {
        return switch (verification.outcome()) {
            case INVALID -> badRequest(identity, "RG.MIRROR.AUTHORITY_PUBLICATION_INVALID",
                    "The authority publication failed canonical signature validation.");
            case ROOTS_UNAVAILABLE -> unavailable(identity,
                    "RG.MIRROR.AUTHORITY_TRUST_UNAVAILABLE",
                    "The local isolation-authority trust policy is unavailable.");
            case POLICY_REJECTED, IDENTITY_MISMATCH -> forbidden(identity,
                    "RG.MIRROR.AUTHORITY_PUBLICATION_POLICY_REJECTED",
                    "The authority publication was rejected by local trust policy.");
            case WINDOW_REJECTED -> conflict(identity,
                    "RG.MIRROR.AUTHORITY_PUBLICATION_WINDOW_REJECTED",
                    "The authority publication is outside its accepted validity window.");
            case CHAIN_REJECTED -> conflict(identity,
                    "RG.MIRROR.AUTHORITY_PUBLICATION_CHAIN_CONFLICT",
                    "The authority publication conflicts with the durable trusted floor.");
            case VERIFIED -> throw new IllegalArgumentException(
                    "verified publication cannot produce an admission failure");
        };
    }

    private static IntegrationProblemException servingFailure(
            MirrorDeploymentIsolationAuthorityKeySetIntegrity.VerificationResult verification,
            IntegrationRequestContext identity) {
        if (verification.outcome()
                == MirrorDeploymentIsolationAuthorityKeySetIntegrity.Outcome.WINDOW_REJECTED) {
            return new IntegrationProblemException(IntegrationProblem.gone(
                    "RG.MIRROR.AUTHORITY_PUBLICATION_EXPIRED",
                    "The current authority publication is no longer active.",
                    identity.correlationId(), Map.of()));
        }
        return unavailable(identity, "RG.MIRROR.AUTHORITY_PUBLICATION_NOT_TRUSTED",
                "The current authority publication could not be locally re-verified.");
    }

    private static IntegrationProblemException repositoryFailure(
            MirrorDeploymentIsolationAuthorityPublicationRepository.Violation rejected,
            IntegrationRequestContext identity) {
        return switch (rejected.reason()) {
            case CANONICAL_INVALID -> badRequest(identity,
                    "RG.MIRROR.AUTHORITY_PUBLICATION_INVALID",
                    "The authority publication is not canonically content-addressed.");
            case STORED_STATE_CORRUPT -> unavailable(identity,
                    "RG.MIRROR.AUTHORITY_PUBLICATION_STORE_UNAVAILABLE",
                    "The trusted authority publication store failed integrity validation.");
            case IDENTITY_MISMATCH, BOOTSTRAP_GENERATION_INVALID, GENERATION_ROLLBACK,
                    GENERATION_FORK, GENERATION_GAP, PREDECESSOR_MISMATCH,
                    CONTENT_ADDRESS_CONFLICT -> conflict(identity,
                    "RG.MIRROR.AUTHORITY_PUBLICATION_CHAIN_CONFLICT",
                    "The authority publication conflicts with the durable trusted floor.");
        };
    }

    private static String identifier(
            String value, String field, IntegrationRequestContext identity) {
        String exact = normalized(value);
        if (!exact.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}")) {
            throw badRequest(identity, "RG.MIRROR.AUTHORITY_PUBLICATION_REF_INVALID",
                    "The authority publication reference is invalid.");
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

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                code, title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException notFound(IntegrationRequestContext identity) {
        return new IntegrationProblemException(IntegrationProblem.notFound(
                "RG.MIRROR.AUTHORITY_PUBLICATION_NOT_FOUND",
                "Authority publication was not found in the authorized scope.",
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
}
