package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Protected application boundary for Domain Fidelity denominator and profile publication.
 *
 * <p>Inventory registration mints scope, provenance, owner identity, approval time, lifecycle,
 * and content address on the server. Profile publication is intentionally not an HTTP operation:
 * only a trusted source adapter with the dedicated projection role may provide already verified,
 * payload-free measurements. Both writes append immutable facts and commit their success audit in
 * the same transaction. Reads revalidate the complete stored artifact before publication.</p>
 */
public class DomainFidelityService {
    private final DomainFidelityRepository repository;
    private final DomainFidelityPolicy policy;
    private final DomainFidelityProfileIntegrity profileIntegrity;
    private final ObjectMapper mapper;
    private final MirrorOperationObservability observability;
    private final Clock clock;

    /**
     * Creates the production service using the UTC server clock.
     *
     * @param repository append-only full-scope Fidelity store
     * @param policy server-owned authorization and projection policy
     * @param profileIntegrity managed profile signing and verification
     * @param mapper canonical protocol mapper
     * @param observability mandatory payload-free audit and telemetry
     */
    public DomainFidelityService(
            DomainFidelityRepository repository,
            DomainFidelityPolicy policy,
            DomainFidelityProfileIntegrity profileIntegrity,
            ObjectMapper mapper,
            MirrorOperationObservability observability) {
        this(
                repository,
                policy,
                profileIntegrity,
                mapper,
                observability,
                Clock.systemUTC());
    }

    /** Deterministic constructor for authorization, expiry, and projection tests. */
    DomainFidelityService(
            DomainFidelityRepository repository,
            DomainFidelityPolicy policy,
            DomainFidelityProfileIntegrity profileIntegrity,
            ObjectMapper mapper,
            MirrorOperationObservability observability,
            Clock clock) {
        this.repository = Objects.requireNonNull(
                repository, "repository");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.profileIntegrity = Objects.requireNonNull(
                profileIntegrity, "profileIntegrity");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.observability = Objects.requireNonNull(
                observability, "observability");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Registers one immutable owner-approved denominator revision.
     *
     * @param request strict owner command without trusted fields
     * @param identity authenticated human owner context
     * @return committed or idempotently recovered inventory
     */
    @Transactional
    public DomainFidelityInventory registerInventory(
            DomainFidelityInventoryRegistrationRequest request,
            IntegrationRequestContext identity) {
        IntegrationRequestContext exactIdentity =
                requireIdentity(
                        identity,
                        DomainFidelityPolicy.GOVERNANCE_PURPOSE);
        DomainFidelityInventoryRegistrationRequest command =
                Objects.requireNonNull(request, "request");
        MirrorOperationObservability.Observation observation =
                observability.start(
                        MirrorOperationAuditEvent.Operation
                                .FIDELITY_INVENTORY_REGISTER,
                        exactIdentity,
                        command.inventoryId(),
                        command.domainId(),
                        "");
        try {
            if (!policy.mayOwn(exactIdentity)) {
                throw problem(
                        IntegrationProblem.forbidden(
                                "RG.MIRROR.FIDELITY.OWNER_FORBIDDEN",
                                "The authenticated human is not an authorized Fidelity owner.",
                                exactIdentity.correlationId(),
                                Map.of()));
            }
            Instant approvedAt = clock.instant();
            policy.requireInventoryWindow(
                    approvedAt,
                    command.effectiveAt(),
                    command.expiresAt());
            Instant effectiveAt =
                    command.effectiveAt().isBefore(approvedAt)
                            ? approvedAt : command.effectiveAt();
            ArtifactProvenance provenance =
                    new ArtifactProvenance(
                            "",
                            ArtifactProvenance.SourceType.OWNER,
                            List.of(),
                            exactIdentity.tenantId(),
                            DomainFidelityPolicy.GOVERNANCE_PURPOSE,
                            null,
                            null,
                            null,
                            null,
                            List.of(),
                            exactIdentity.actorId(),
                            approvedAt,
                            command.expiresAt(),
                            "");
            DomainFidelityInventory inventory =
                    new DomainFidelityInventory(
                            "",
                            command.inventoryId(),
                            command.revision(),
                            "",
                            scope(exactIdentity),
                            command.domainId(),
                            command.taxonomyRef(),
                            command.units(),
                            provenance,
                            CapabilitySnapshot.Lifecycle.ACTIVE,
                            effectiveAt,
                            command.expiresAt())
                            .seal(mapper);
            DomainFidelityInventory stored =
                    repository.appendInventory(
                            inventory,
                            command.expectedPredecessorFingerprint());
            observation.succeeded(stored.fingerprint());
            return stored;
        } catch (RuntimeException failure) {
            throw observation.failed(
                    mapFailure(failure, exactIdentity));
        }
    }

    /**
     * Publishes a profile from already independently verified source projections.
     *
     * <p>This method is an internal adapter boundary, not an HTTP endpoint. The authenticated
     * caller must be a service principal in the projector group. It must reference the current
     * inventory head, so an old denominator cannot be selected after an owner adds obligations.</p>
     *
     * @param inventoryRef exact current inventory head
     * @param measurements independently verified payload-free measurements
     * @param identity authenticated trusted source-adapter context
     * @return signed, committed, or idempotently recovered profile
     */
    @Transactional
    public DomainFidelityProfile projectVerified(
            MirrorArtifactRef inventoryRef,
            List<DomainFidelityProfileProjector.Measurement>
                    measurements,
            IntegrationRequestContext identity) {
        IntegrationRequestContext exactIdentity =
                requireIdentity(
                        identity,
                        DomainFidelityPolicy.PROJECTION_PURPOSE);
        MirrorArtifactRef reference = Objects.requireNonNull(
                inventoryRef, "inventoryRef");
        MirrorOperationObservability.Observation observation =
                observability.start(
                        MirrorOperationAuditEvent.Operation
                                .FIDELITY_PROFILE_PROJECT,
                        exactIdentity,
                        reference.id(),
                        "",
                        "");
        try {
            if (!policy.mayProject(exactIdentity)) {
                throw problem(
                        IntegrationProblem.forbidden(
                                "RG.MIRROR.FIDELITY.PROJECTOR_FORBIDDEN",
                                "The authenticated service is not an authorized Fidelity projector.",
                                exactIdentity.correlationId(),
                                Map.of()));
            }
            if (!DomainFidelityInventory.ARTIFACT_KIND.equals(
                    reference.kind())) {
                throw new IllegalArgumentException(
                        "inventoryRef kind is invalid");
            }
            CapabilitySnapshot.Scope scope =
                    scope(exactIdentity);
            DomainFidelityInventory inventory =
                    repository.findInventory(
                            scope,
                            reference.id(),
                            reference.revision())
                            .filter(value ->
                                    value.fingerprint().equals(
                                            reference.fingerprint()))
                            .orElseThrow(() ->
                                    problem(
                                            IntegrationProblem.notFound(
                                                    "RG.MIRROR.FIDELITY.INVENTORY_NOT_FOUND",
                                                    "Fidelity inventory was not found in the authorized scope.",
                                                    exactIdentity.correlationId(),
                                                    Map.of())));
            DomainFidelityInventory latest =
                    repository.findLatestInventory(
                            scope, reference.id())
                            .orElseThrow(() ->
                                    problem(
                                            IntegrationProblem.notFound(
                                                    "RG.MIRROR.FIDELITY.INVENTORY_NOT_FOUND",
                                                    "Fidelity inventory was not found in the authorized scope.",
                                                    exactIdentity.correlationId(),
                                                    Map.of())));
            if (!latest.fingerprint().equals(
                    inventory.fingerprint())) {
                throw problem(
                        IntegrationProblem.conflict(
                                "RG.MIRROR.FIDELITY.INVENTORY_NOT_CURRENT",
                                "Profile projection requires the current owner-approved inventory head.",
                                exactIdentity.correlationId(),
                                Map.of()));
            }
            Instant measuredAt = clock.instant();
            DomainFidelityProfile projected =
                    DomainFidelityProfileProjector.project(
                            mapper,
                            inventory,
                            measurements,
                            policy.projectionPolicy(),
                            measuredAt);
            DomainFidelityProfile signed =
                    profileIntegrity.sign(projected);
            DomainFidelityProfile stored =
                    repository.appendProfile(signed);
            observation.succeeded(
                    stored.profileFingerprint());
            return stored;
        } catch (RuntimeException failure) {
            throw observation.failed(
                    mapFailure(failure, exactIdentity));
        }
    }

    /** Reads one exact inventory revision after full repository verification. */
    @Transactional
    public DomainFidelityInventory findInventory(
            String inventoryId,
            long revision,
            IntegrationRequestContext identity) {
        IntegrationRequestContext exactIdentity =
                requireReadIdentity(identity);
        MirrorOperationObservability.Observation observation =
                observability.start(
                        MirrorOperationAuditEvent.Operation
                                .FIDELITY_INVENTORY_READ,
                        exactIdentity,
                        inventoryId,
                        "",
                        "");
        try {
            DomainFidelityInventory value =
                    repository.findInventory(
                            scope(exactIdentity),
                            inventoryId,
                            revision)
                            .orElseThrow(() ->
                                    notFoundInventory(
                                            exactIdentity));
            observation.succeeded(value.fingerprint());
            return value;
        } catch (RuntimeException failure) {
            throw observation.failed(
                    mapFailure(failure, exactIdentity));
        }
    }

    /** Reads the current inventory revision after full repository verification. */
    @Transactional
    public DomainFidelityInventory findLatestInventory(
            String inventoryId,
            IntegrationRequestContext identity) {
        IntegrationRequestContext exactIdentity =
                requireReadIdentity(identity);
        MirrorOperationObservability.Observation observation =
                observability.start(
                        MirrorOperationAuditEvent.Operation
                                .FIDELITY_INVENTORY_READ,
                        exactIdentity,
                        inventoryId,
                        "",
                        "");
        try {
            DomainFidelityInventory value =
                    repository.findLatestInventory(
                            scope(exactIdentity),
                            inventoryId)
                            .orElseThrow(() ->
                                    notFoundInventory(
                                            exactIdentity));
            observation.succeeded(value.fingerprint());
            return value;
        } catch (RuntimeException failure) {
            throw observation.failed(
                    mapFailure(failure, exactIdentity));
        }
    }

    /** Reads one exact signed profile after arithmetic and signature re-verification. */
    @Transactional
    public DomainFidelityProfile findProfile(
            String profileFingerprint,
            IntegrationRequestContext identity) {
        IntegrationRequestContext exactIdentity =
                requireReadIdentity(identity);
        MirrorOperationObservability.Observation observation =
                observability.start(
                        MirrorOperationAuditEvent.Operation
                                .FIDELITY_PROFILE_READ,
                        exactIdentity,
                        "",
                        "",
                        profileFingerprint);
        try {
            DomainFidelityProfile value =
                    repository.findProfile(
                            scope(exactIdentity),
                            profileFingerprint)
                            .orElseThrow(() ->
                                    notFoundProfile(
                                            exactIdentity));
            observation.succeeded(
                    value.profileFingerprint());
            return value;
        } catch (RuntimeException failure) {
            throw observation.failed(
                    mapFailure(failure, exactIdentity));
        }
    }

    /** Reads the newest signed profile for one domain after full re-verification. */
    @Transactional
    public DomainFidelityProfile findLatestProfile(
            String domainId,
            IntegrationRequestContext identity) {
        IntegrationRequestContext exactIdentity =
                requireReadIdentity(identity);
        MirrorOperationObservability.Observation observation =
                observability.start(
                        MirrorOperationAuditEvent.Operation
                                .FIDELITY_PROFILE_READ,
                        exactIdentity,
                        "",
                        domainId,
                        "");
        try {
            DomainFidelityProfile value =
                    repository.findLatestProfile(
                            scope(exactIdentity),
                            domainId)
                            .orElseThrow(() ->
                                    notFoundProfile(
                                            exactIdentity));
            observation.succeeded(
                    value.profileFingerprint());
            return value;
        } catch (RuntimeException failure) {
            throw observation.failed(
                    mapFailure(failure, exactIdentity));
        }
    }

    private IntegrationRequestContext requireReadIdentity(
            IntegrationRequestContext identity) {
        IntegrationRequestContext exact =
                Objects.requireNonNull(identity, "identity");
        exact.requireComplete();
        if (!DomainFidelityPolicy.GOVERNANCE_PURPOSE.equals(
                exact.purpose())
                && !"GOVERNANCE_EVIDENCE_INGESTION".equals(
                exact.purpose())) {
            throw problem(
                    IntegrationProblem.forbidden(
                            "RG.MIRROR.FIDELITY.PURPOSE_FORBIDDEN",
                            "The authenticated purpose cannot read Domain Fidelity artifacts.",
                            exact.correlationId(),
                            Map.of()));
        }
        return exact;
    }

    private static IntegrationRequestContext requireIdentity(
            IntegrationRequestContext identity,
            String purpose) {
        IntegrationRequestContext exact =
                Objects.requireNonNull(identity, "identity");
        exact.requireComplete();
        if (!purpose.equals(exact.purpose())) {
            throw problem(
                    IntegrationProblem.forbidden(
                            "RG.MIRROR.FIDELITY.PURPOSE_FORBIDDEN",
                            "The authenticated purpose cannot perform this Domain Fidelity operation.",
                            exact.correlationId(),
                            Map.of()));
        }
        return exact;
    }

    private static CapabilitySnapshot.Scope scope(
            IntegrationRequestContext identity) {
        return new CapabilitySnapshot.Scope(
                identity.tenantId(),
                identity.organizationId(),
                identity.projectId(),
                identity.environmentId(),
                identity.region());
    }

    private static IntegrationProblemException notFoundInventory(
            IntegrationRequestContext identity) {
        return problem(
                IntegrationProblem.notFound(
                        "RG.MIRROR.FIDELITY.INVENTORY_NOT_FOUND",
                        "Fidelity inventory was not found in the authorized scope.",
                        identity.correlationId(),
                        Map.of()));
    }

    private static IntegrationProblemException notFoundProfile(
            IntegrationRequestContext identity) {
        return problem(
                IntegrationProblem.notFound(
                        "RG.MIRROR.FIDELITY.PROFILE_NOT_FOUND",
                        "Fidelity profile was not found in the authorized scope.",
                        identity.correlationId(),
                        Map.of()));
    }

    private static RuntimeException mapFailure(
            RuntimeException failure,
            IntegrationRequestContext identity) {
        if (failure instanceof IntegrationProblemException) {
            return failure;
        }
        if (failure instanceof DomainFidelityRepository.Violation violation) {
            return switch (violation.reason()) {
                case LINEAGE_CONFLICT,
                     CONTENT_CONFLICT,
                     PROFILE_COORDINATE_CONFLICT ->
                        problem(
                                IntegrationProblem.conflict(
                                        "RG.MIRROR.FIDELITY.IMMUTABLE_CONFLICT",
                                        "The Domain Fidelity immutable coordinate or lineage conflicts with stored state.",
                                        identity.correlationId(),
                                        Map.of()));
                case INVENTORY_NOT_FOUND ->
                        notFoundInventory(identity);
                case INVENTORY_MISMATCH,
                     CANONICAL_INVALID ->
                        problem(
                                IntegrationProblem.badRequest(
                                        "RG.MIRROR.FIDELITY.INVALID",
                                        "The Domain Fidelity artifact violates the governed protocol.",
                                        identity.correlationId(),
                                        Map.of()));
                case SIGNATURE_UNAVAILABLE,
                     STORED_STATE_CORRUPT ->
                        problem(
                                IntegrationProblem.serviceUnavailable(
                                        "RG.MIRROR.FIDELITY.TRUST_UNAVAILABLE",
                                        "Domain Fidelity trust verification is temporarily unavailable.",
                                        identity.correlationId(),
                                        Map.of()));
            };
        }
        if (failure
                instanceof DomainFidelityProfileIntegrity.Violation) {
            return problem(
                    IntegrationProblem.serviceUnavailable(
                            "RG.MIRROR.FIDELITY.SIGNING_UNAVAILABLE",
                            "Domain Fidelity signing or verification is temporarily unavailable.",
                            identity.correlationId(),
                            Map.of()));
        }
        if (failure instanceof IllegalArgumentException
                || failure instanceof NullPointerException) {
            return problem(
                    IntegrationProblem.badRequest(
                            "RG.MIRROR.FIDELITY.INVALID",
                            "The Domain Fidelity request violates the governed protocol.",
                            identity.correlationId(),
                            Map.of()));
        }
        return problem(
                IntegrationProblem.serviceUnavailable(
                        "RG.MIRROR.FIDELITY.UNAVAILABLE",
                        "The Domain Fidelity service is temporarily unavailable.",
                        identity.correlationId(),
                        Map.of()));
    }

    private static IntegrationProblemException problem(
            IntegrationProblem value) {
        return new IntegrationProblemException(value);
    }
}
