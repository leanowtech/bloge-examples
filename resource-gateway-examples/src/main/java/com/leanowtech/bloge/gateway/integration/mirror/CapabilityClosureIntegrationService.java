package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.integration.IntegrationEnvelope;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Authenticated Tool Studio boundary for deterministic draft capability-closure projection.
 *
 * <p>The service overwrites all draft-provided enterprise coordinates with verified workload identity
 * coordinates and always emits a {@code DRAFT} lifecycle. It also prevents callers from projecting a
 * classification above their clearance or using a materially future creation time. Projection failures
 * retain stable machine codes while business payloads are never copied into diagnostics.</p>
 */
@Service
public class CapabilityClosureIntegrationService {
    private static final Duration MAXIMUM_CLOCK_SKEW = Duration.ofMinutes(5);

    private final GraphDraftCapabilityClosureService closures;
    private final Clock clock;

    /** Creates the production boundary with the system UTC clock. */
    @Autowired
    public CapabilityClosureIntegrationService(GraphDraftCapabilityClosureService closures) {
        this(closures, Clock.systemUTC());
    }

    /**
     * Creates a testable boundary with an explicit clock.
     *
     * @param closures graph-to-closure projection service
     * @param clock admission clock for creation-time skew validation
     */
    public CapabilityClosureIntegrationService(GraphDraftCapabilityClosureService closures, Clock clock) {
        this.closures = Objects.requireNonNull(closures, "closures");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Projects one graph using only authenticated scope and policy coordinates.
     *
     * @param request versioned portable graph projection request
     * @param context verified workload identity and purpose
     * @return versioned envelope containing a sealed independently verifiable closure
     */
    public IntegrationEnvelope<CapabilityClosure> project(CapabilityClosureProjectionRequest request,
                                                          IntegrationRequestContext context) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context").requireComplete();
        if (!context.hasClearanceAtLeast(request.classification().name())) {
            throw problem(IntegrationProblem.forbidden("RG.MIRROR.CLASSIFICATION_FORBIDDEN",
                    "Capability classification exceeds the authenticated workload clearance.",
                    context.correlationId(), Map.of("classification", request.classification().name())));
        }
        if (request.createdAt().isAfter(clock.instant().plus(MAXIMUM_CLOCK_SKEW))) {
            throw problem(IntegrationProblem.badRequest("RG.MIRROR.CREATED_AT_IN_FUTURE",
                    "Capability projection creation time exceeds the permitted clock skew.",
                    context.correlationId(), Map.of()));
        }
        GraphDraft scopedDraft = rescope(request.draft(), context);
        CapabilityProjectionContext projectionContext = new CapabilityProjectionContext(
                request.revision(), context.tenantId(), context.organizationId(), context.projectId(),
                context.environmentId(), context.region(), context.purpose(),
                new CapabilitySnapshot.Ownership(context.actorId(), ownerTeam(context), ""),
                CapabilitySnapshot.Lifecycle.DRAFT, request.classification(), allowedRegions(context),
                false, "", null, null, request.createdAt());
        try {
            CapabilityClosure closure = closures.project(scopedDraft, projectionContext);
            return IntegrationEnvelope.of("CAPABILITY_CLOSURE", CapabilityClosure.SCHEMA_VERSION, closure);
        } catch (CapabilityProjectionException.Failure failure) {
            throw problem(IntegrationProblem.badRequest(failure.problem().code(), failure.problem().message(),
                    context.correlationId(), failure.problem().details()));
        } catch (IllegalArgumentException failure) {
            throw problem(IntegrationProblem.badRequest("RG.MIRROR.CLOSURE_PROJECTION_REJECTED",
                    "Capability closure projection was rejected.", context.correlationId(),
                    Map.of("reason", bounded(failure.getMessage()))));
        }
    }

    private static GraphDraft rescope(GraphDraft draft, IntegrationRequestContext context) {
        String namespace = context.projectId().isBlank() ? context.organizationId() : context.projectId();
        return new GraphDraft(draft.schemaVersion(), draft.draftId(), draft.revision(), draft.graphName(),
                context.tenantId(), namespace, context.environmentId(), GraphDraft.STATUS_DRAFT,
                draft.inputSchema(), draft.outputSchema(), draft.nodes(), draft.edges(), draft.visualLayout(),
                draft.nodeFixtures(), draft.output(), draft.operatorFingerprints(), draft.operatorSnapshots(),
                draft.revisionMetadata());
    }

    private static List<String> allowedRegions(IntegrationRequestContext context) {
        return context.region().isBlank() ? List.of() : List.of(context.region());
    }

    private static String ownerTeam(IntegrationRequestContext context) {
        return context.projectId().isBlank() ? context.organizationId() : context.projectId();
    }

    private static IntegrationProblemException problem(IntegrationProblem problem) {
        return new IntegrationProblemException(problem);
    }

    private static String bounded(String value) {
        String normalized = value == null || value.isBlank() ? "projection rejected" : value.trim();
        return normalized.length() <= 512 ? normalized : normalized.substring(0, 512);
    }
}
