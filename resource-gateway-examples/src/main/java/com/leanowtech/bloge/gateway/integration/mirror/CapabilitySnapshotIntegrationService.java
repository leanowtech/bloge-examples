package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.integration.IntegrationEnvelope;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Scope- and clearance-enforcing Tool Studio boundary for capability snapshots.
 *
 * <p>The service deliberately returns the same not-found problem for absent, cross-scope, and
 * insufficient-clearance reads. This prevents organization, environment, or classification metadata
 * from becoming an existence oracle. Writes are append-only and retain repository lifecycle fencing.</p>
 */
@Service
public class CapabilitySnapshotIntegrationService {
    private final CapabilitySnapshotRepository repository;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final Clock clock;

    /** Creates the production service using the system UTC clock. */
    @Autowired
    public CapabilitySnapshotIntegrationService(CapabilitySnapshotRepository repository,
                                                com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this(repository, mapper, Clock.systemUTC());
    }

    /**
     * Creates a testable service with an explicit clock.
     *
     * @param repository append-only snapshot repository
     * @param mapper canonical fingerprint mapper
     * @param clock lifecycle decision clock
     */
    public CapabilitySnapshotIntegrationService(CapabilitySnapshotRepository repository,
                                                com.fasterxml.jackson.databind.ObjectMapper mapper,
                                                Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Appends one exact sealed snapshot after path, scope, and clearance validation.
     *
     * @param capabilityId path capability id
     * @param revision path revision
     * @param snapshot sealed request body
     * @param context authenticated workload context
     * @return versioned integration envelope
     */
    public IntegrationEnvelope<CapabilitySnapshot> create(String capabilityId,
                                                          long revision,
                                                          CapabilitySnapshot snapshot,
                                                          IntegrationRequestContext context) {
        Objects.requireNonNull(snapshot, "snapshot");
        requireContext(context);
        if (!snapshot.capabilityId().equals(capabilityId) || snapshot.revision() != revision) {
            throw problem(IntegrationProblem.badRequest("RG.MIRROR.SNAPSHOT_PATH_MISMATCH",
                    "Capability snapshot path does not match the immutable request body.",
                    context.correlationId(), Map.of()));
        }
        requireVisible(snapshot, context);
        try {
            CapabilitySnapshot stored = repository.create(snapshot);
            return envelope(stored);
        } catch (IllegalArgumentException exception) {
            throw problem(IntegrationProblem.conflict("RG.MIRROR.SNAPSHOT_APPEND_REJECTED",
                    "Capability snapshot append was rejected by revision or lifecycle fencing.",
                    context.correlationId(), Map.of("reason", bounded(exception.getMessage()))));
        }
    }

    /**
     * Reads an exact revision, or the latest revision when {@code revision == 0}.
     *
     * @param capabilityId capability id inside the authenticated scope
     * @param revision exact revision or zero for latest
     * @param context authenticated workload context
     * @return versioned integration envelope
     */
    public IntegrationEnvelope<CapabilitySnapshot> find(String capabilityId,
                                                        long revision,
                                                        IntegrationRequestContext context) {
        requireContext(context);
        if (revision < 0) {
            throw problem(IntegrationProblem.badRequest("RG.MIRROR.REVISION_INVALID",
                    "Capability snapshot revision must be zero or positive.",
                    context.correlationId(), Map.of()));
        }
        CapabilitySnapshot.Scope scope = scope(context);
        CapabilitySnapshot snapshot = (revision == 0
                ? repository.findLatest(scope, capabilityId)
                : repository.find(scope, capabilityId, revision)).orElseThrow(() -> notFound(context));
        requireVisible(snapshot, context);
        return envelope(snapshot);
    }

    /**
     * Appends a lifecycle-only revision using the authenticated actor as the approval principal.
     *
     * @param capabilityId capability id inside the authenticated scope
     * @param request optimistic lifecycle command
     * @param context authenticated workload context
     * @return newly sealed lifecycle revision
     */
    public IntegrationEnvelope<CapabilitySnapshot> transition(
            String capabilityId,
            CapabilityLifecycleTransitionRequest request,
            IntegrationRequestContext context) {
        Objects.requireNonNull(request, "request");
        requireContext(context);
        CapabilitySnapshot current = repository.find(scope(context), capabilityId,
                        request.expectedRevision())
                .orElseThrow(() -> notFound(context));
        requireVisible(current, context);
        Instant clockNow = clock.instant();
        Instant now = clockNow.isAfter(current.createdAt())
                ? clockNow : current.createdAt().plusNanos(1);
        try {
            CapabilitySnapshot next = CapabilitySnapshotLifecycle.transition(mapper, current,
                    request.target(), current.revision() + 1, context.actorId(), now,
                    request.expiresAt(), request.revocationRef(), now.plusNanos(1));
            return envelope(repository.create(next));
        } catch (IllegalArgumentException exception) {
            throw problem(IntegrationProblem.conflict("RG.MIRROR.LIFECYCLE_TRANSITION_REJECTED",
                    "Capability lifecycle transition was rejected.", context.correlationId(),
                    Map.of("reason", bounded(exception.getMessage()))));
        }
    }

    private static void requireContext(IntegrationRequestContext context) {
        Objects.requireNonNull(context, "context").requireComplete();
    }

    private static void requireVisible(CapabilitySnapshot snapshot, IntegrationRequestContext context) {
        if (!snapshot.scope().equals(scope(context))
                || !context.hasClearanceAtLeast(snapshot.contract().security().classification().name())) {
            throw notFound(context);
        }
    }

    private static CapabilitySnapshot.Scope scope(IntegrationRequestContext context) {
        return new CapabilitySnapshot.Scope(context.tenantId(), context.organizationId(), context.projectId(),
                context.environmentId(), context.region());
    }

    private static IntegrationEnvelope<CapabilitySnapshot> envelope(CapabilitySnapshot snapshot) {
        return IntegrationEnvelope.of("CAPABILITY_SNAPSHOT", CapabilitySnapshot.SCHEMA_VERSION, snapshot);
    }

    private static IntegrationProblemException notFound(IntegrationRequestContext context) {
        return problem(IntegrationProblem.notFound("RG.MIRROR.SNAPSHOT_NOT_FOUND",
                "Capability snapshot was not found in the authorized integration scope.",
                context.correlationId(), Map.of()));
    }

    private static IntegrationProblemException problem(IntegrationProblem problem) {
        return new IntegrationProblemException(problem);
    }

    private static String bounded(String value) {
        String normalized = value == null || value.isBlank() ? "append rejected" : value.trim();
        return normalized.length() <= 512 ? normalized : normalized.substring(0, 512);
    }
}
