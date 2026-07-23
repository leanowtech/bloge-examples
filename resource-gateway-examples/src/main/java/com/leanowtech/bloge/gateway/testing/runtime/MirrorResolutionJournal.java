package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.exception.OperatorTimeoutException;
import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorResolution;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorResolutionIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.ReplayPayloadRef;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Collects mirror resolver outcomes during execution and seals them after the shared run id exists.
 *
 * <p>The journal never stores request payloads. Successful outputs are fingerprinted immediately
 * and retained only as hash-only evidence. Error messages are generated from stable control facts
 * rather than copying exception text, which may contain business values.</p>
 */
public final class MirrorResolutionJournal implements MirrorResolutionObserver {
    /** Maximum canonical request admitted to a mirror resolver. */
    public static final int MAXIMUM_REQUEST_BYTES = 16 * 1024 * 1024;

    private static final ArtifactProvenance.Confidence ABSTAINED_CONFIDENCE =
            new ArtifactProvenance.Confidence(0, 0, 0, "ABSTAINED_V1");

    private final ObjectMapper mapper;
    private final MirrorPlan plan;
    private final ResolvedReplayPayloads replayPayloads;
    private final Map<String, MirrorPlan.ExternalBinding> externalBindings;
    private final List<Draft> drafts = new ArrayList<>();
    private boolean completed;

    /**
     * Creates a journal bound to one exact mirror generation.
     *
     * @param mapper canonical protocol mapper
     * @param plan sealed public mirror plan
     * @param replayPayloads exact replay closure frozen into the generation
     */
    public MirrorResolutionJournal(
            ObjectMapper mapper,
            MirrorPlan plan,
            ResolvedReplayPayloads replayPayloads) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.replayPayloads = replayPayloads == null
                ? ResolvedReplayPayloads.empty() : replayPayloads;
        Map<String, MirrorPlan.ExternalBinding> indexed = new LinkedHashMap<>();
        for (MirrorPlan.ExternalBinding binding : plan.externalBindings()) {
            if (indexed.putIfAbsent(binding.invocationSiteId(), binding) != null) {
                throw new IllegalArgumentException(
                        "mirror plan contains duplicate external invocation sites");
            }
        }
        this.externalBindings = Map.copyOf(indexed);
    }

    /**
     * Computes the canonical request identity without retaining the request.
     *
     * @param mapper canonical protocol mapper
     * @param input ephemeral invocation input
     * @return bounded canonical request fingerprint
     */
    public static String requestFingerprint(ObjectMapper mapper, Object input) {
        return ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"), input, MAXIMUM_REQUEST_BYTES);
    }

    @Override
    public void resolved(
            InvocationRecorder.InvocationBinding binding,
            int attempt,
            String requestFingerprint,
            MirrorResolverChain.Decision decision,
            Object output) {
        requireConcrete(decision);
        String outputFingerprint = ProtocolFingerprint.ofBounded(
                mapper, output, MirrorResolutionIntegrity.MAXIMUM_OUTPUT_BYTES);
        append(Draft.resolved(binding, attempt, requestFingerprint, decision,
                outputFingerprint));
    }

    @Override
    public void failed(
            InvocationRecorder.InvocationBinding binding,
            int attempt,
            String requestFingerprint,
            MirrorResolverChain.Decision decision,
            Exception failure) {
        requireConcrete(decision);
        Objects.requireNonNull(failure, "failure");
        FixtureRule.Behavior behavior = decision.match().rule().behavior();
        MirrorResolution.Status status = expectedBusinessFailure(behavior, failure)
                ? MirrorResolution.Status.RESOLVED : MirrorResolution.Status.REJECTED;
        append(Draft.failed(binding, attempt, requestFingerprint, decision, status,
                error(decision.match(), failure, status)));
    }

    @Override
    public void abstained(
            InvocationRecorder.InvocationBinding binding,
            int attempt,
            String requestFingerprint) {
        append(Draft.abstained(binding, attempt, requestFingerprint));
    }

    /**
     * Binds every draft to the shared test run id and seals its protocol fingerprints.
     *
     * @param runId exact run id from the shared test evidence
     * @return deterministic immutable resolution list
     */
    public synchronized List<MirrorResolution> complete(String runId) {
        String requiredRunId = required(runId, "runId");
        if (completed) {
            throw new IllegalStateException("mirror resolution journal is already complete");
        }
        completed = true;
        List<Draft> ordered = drafts.stream().sorted(Comparator
                .comparing((Draft draft) -> draft.binding().site().invocationSiteId())
                .thenComparing(draft -> draft.binding().site().correlationKey())
                .thenComparingInt(draft -> draft.binding().occurrence())
                .thenComparingInt(Draft::attempt)).toList();
        LinkedHashSet<String> coordinates = new LinkedHashSet<>();
        List<MirrorResolution> resolutions = new ArrayList<>(ordered.size());
        for (Draft draft : ordered) {
            String coordinate = draft.binding().site().invocationSiteId() + "\u0000"
                    + draft.binding().site().correlationKey() + "\u0000"
                    + draft.binding().occurrence() + "\u0000" + draft.attempt();
            if (!coordinates.add(coordinate)) {
                throw new IllegalStateException("duplicate mirror resolution coordinate");
            }
            resolutions.add(MirrorResolutionIntegrity.seal(
                    mapper, materialize(requiredRunId, draft)));
        }
        return List.copyOf(resolutions);
    }

    private MirrorResolution materialize(String runId, Draft draft) {
        MirrorPlan.ExternalBinding external = externalBindings.get(
                draft.binding().site().invocationSiteId());
        if (external == null) {
            throw new IllegalStateException(
                    "mirror resolution site is absent from the sealed external bindings");
        }
        if (draft.decision() == null) {
            return new MirrorResolution(
                    MirrorResolution.SCHEMA_VERSION, "", runId, plan.planFingerprint(),
                    external.capabilityRef(), draft.binding().site().invocationSiteId(),
                    draft.binding().site().graphPath(), draft.binding().site().correlationKey(),
                    draft.binding().occurrence(), draft.attempt(), draft.requestFingerprint(),
                    MirrorResolution.Status.ABSTAINED, MirrorPlan.MirrorSource.ABSTAINED,
                    MirrorResolution.PayloadVisibility.NONE, false, null, "", null,
                    List.of(), List.of(), ABSTAINED_CONFIDENCE, 0, List.of());
        }
        MirrorResolver.Match match = draft.decision().match();
        return new MirrorResolution(
                MirrorResolution.SCHEMA_VERSION, "", runId, plan.planFingerprint(),
                external.capabilityRef(), draft.binding().site().invocationSiteId(),
                draft.binding().site().graphPath(), draft.binding().site().correlationKey(),
                draft.binding().occurrence(), draft.attempt(), draft.requestFingerprint(),
                draft.status(), draft.decision().source(), draft.visibility(), false, null,
                    draft.outputFingerprint(), draft.error(), artifacts(match),
                ruleRefs(match), match.confidence(), match.freshness(),
                limitations(match));
    }

    private List<MirrorArtifactRef> artifacts(MirrorResolver.Match match) {
        if (!match.artifactRefs().isEmpty()) {
            return match.artifactRefs();
        }
        FixtureRule rule = match.rule();
        List<MirrorArtifactRef> result = new ArrayList<>();
        result.add(plan.fixtureBundleRef());
        if (rule.behavior().kind() == FixtureRule.BehaviorKind.REPLAY) {
            ReplayPayloadRef ref = ReplayPayloadRef.parse(rule.behavior().replayRef());
            result.add(new MirrorArtifactRef(
                    "REPLAY_PAYLOAD", ref.replayPayloadId(), ref.revision(), ref.fingerprint()));
        }
        return result;
    }

    private static List<String> ruleRefs(MirrorResolver.Match match) {
        return match.ruleRefs().isEmpty()
                ? List.of(match.rule().ruleId()) : match.ruleRefs();
    }

    private List<String> limitations(MirrorResolver.Match match) {
        LinkedHashSet<String> values = new LinkedHashSet<>(match.limitations());
        if (match.rule().behavior().kind() == FixtureRule.BehaviorKind.REPLAY) {
            ResolvedReplayPayloads.Payload payload = replayPayloads.require(
                    match.rule().behavior().replayRef());
            if (!payload.certificationEligible()) {
                values.add("REPLAY_NOT_CERTIFICATION_ELIGIBLE");
            }
            payload.certificationGaps().stream().map(gap -> "REPLAY_GAP:" + gap)
                    .forEach(values::add);
        }
        return values.stream().map(MirrorResolutionJournal::boundedLimitation)
                .filter(value -> !value.isBlank()).distinct().sorted()
                .limit(MirrorResolution.MAXIMUM_LIMITATIONS).toList();
    }

    private synchronized void append(Draft draft) {
        if (completed) {
            throw new IllegalStateException("mirror resolution journal is already complete");
        }
        drafts.add(Objects.requireNonNull(draft, "draft"));
    }

    private static boolean expectedBusinessFailure(
            FixtureRule.Behavior behavior, Exception failure) {
        if (behavior.kind() == FixtureRule.BehaviorKind.TIMEOUT) {
            return failure instanceof OperatorTimeoutException;
        }
        if (behavior.kind() != FixtureRule.BehaviorKind.THROW
                || !(failure instanceof TestOutcomeFailure controlled)) {
            return false;
        }
        String expectedCode = behavior.errorCode().isBlank()
                ? "TEST_THROW" : behavior.errorCode();
        return expectedCode.equals(controlled.code());
    }

    private static MirrorResolution.MirrorError error(
            MirrorResolver.Match match,
            Exception failure,
            MirrorResolution.Status status) {
        FixtureRule.Behavior behavior = match.rule().behavior();
        if (status == MirrorResolution.Status.RESOLVED) {
            String code = behavior.errorCode().isBlank()
                    ? behavior.kind() == FixtureRule.BehaviorKind.TIMEOUT
                    ? "TEST_TIMEOUT" : "TEST_THROW"
                    : behavior.errorCode();
            String type = behavior.errorType().isBlank()
                    ? behavior.kind().name() : behavior.errorType();
            return new MirrorResolution.MirrorError(
                    code, type, match.errorDetailsFingerprint());
        }
        if (failure instanceof TestOutcomeFailure controlled) {
            return new MirrorResolution.MirrorError(
                    controlled.code(), controlled.errorType(), "");
        }
        return new MirrorResolution.MirrorError(
                "MIRROR_RESOLUTION_EXECUTION_REJECTED", "MIRROR_RESOLUTION", "");
    }

    private static void requireConcrete(MirrorResolverChain.Decision decision) {
        if (decision == null || decision.abstained()) {
            throw new IllegalArgumentException("resolved mirror observation requires a source match");
        }
    }

    private static String boundedLimitation(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() <= 512) {
            return normalized;
        }
        return normalized.substring(0, 512);
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private record Draft(
            InvocationRecorder.InvocationBinding binding,
            int attempt,
            String requestFingerprint,
            MirrorResolverChain.Decision decision,
            MirrorResolution.Status status,
            MirrorResolution.PayloadVisibility visibility,
            String outputFingerprint,
            MirrorResolution.MirrorError error
    ) {
        private Draft {
            binding = Objects.requireNonNull(binding, "binding");
            if (attempt < 1) {
                throw new IllegalArgumentException("attempt must be positive");
            }
            requestFingerprint = required(requestFingerprint, "requestFingerprint");
            status = Objects.requireNonNull(status, "status");
            visibility = Objects.requireNonNull(visibility, "visibility");
            outputFingerprint = outputFingerprint == null ? "" : outputFingerprint;
        }

        private static Draft resolved(
                InvocationRecorder.InvocationBinding binding,
                int attempt,
                String requestFingerprint,
                MirrorResolverChain.Decision decision,
                String outputFingerprint) {
            return new Draft(binding, attempt, requestFingerprint, decision,
                    MirrorResolution.Status.RESOLVED,
                    MirrorResolution.PayloadVisibility.HASH_ONLY,
                    outputFingerprint, null);
        }

        private static Draft failed(
                InvocationRecorder.InvocationBinding binding,
                int attempt,
                String requestFingerprint,
                MirrorResolverChain.Decision decision,
                MirrorResolution.Status status,
                MirrorResolution.MirrorError error) {
            return new Draft(binding, attempt, requestFingerprint, decision, status,
                    MirrorResolution.PayloadVisibility.NONE, "", error);
        }

        private static Draft abstained(
                InvocationRecorder.InvocationBinding binding,
                int attempt,
                String requestFingerprint) {
            return new Draft(binding, attempt, requestFingerprint, null,
                    MirrorResolution.Status.ABSTAINED,
                    MirrorResolution.PayloadVisibility.NONE, "", null);
        }
    }
}
