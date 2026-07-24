package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.BoundedStateExpression;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionPayload;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionProtocolIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorStateRunEvidence;
import com.leanowtech.bloge.gateway.integration.mirror.SessionStateSpace;
import com.leanowtech.bloge.gateway.integration.mirror.SessionStateSpaceIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.StateModelIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.StateReadSpec;
import com.leanowtech.bloge.gateway.integration.mirror.StateReadSpecIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.WriteEffectSpec;
import com.leanowtech.bloge.gateway.integration.mirror.WriteEffectSpecIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.ProtocolJsonValue;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves exact external reads and virtual writes from one run-scoped stateful-mirror Session.
 *
 * <p>A live entity produces a normal output-level fixture. An indexed tombstone produces a
 * governed non-retryable business failure and therefore terminates source precedence. A key absent
 * from both live and tombstoned state abstains so exact corpus or owner policy may provide the
 * initial observation. A capability with one exact {@link WriteEffectSpec} is executed only through
 * the run-scoped {@link MirrorStateRunSession}; that bridge delegates lease/CAS persistence to the
 * authenticated Session service and advances the state head visible to downstream nodes. The
 * resolver never receives a store, credential, or real external operator.</p>
 */
public final class MirrorSessionStateResolver implements MirrorResolver {
    /** Stable terminal error emitted when an exact session key is tombstoned. */
    public static final String ENTITY_TOMBSTONED =
            MirrorStateRunEvidence.MirrorSessionStateError
                    .ENTITY_TOMBSTONED;
    private static final int MAXIMUM_PROJECTED_OUTPUT_BYTES =
            16 * 1024 * 1024;
    private static final ArtifactProvenance.Confidence EXACT_STATE_CONFIDENCE =
            new ArtifactProvenance.Confidence(1, 1, 1, "SESSION_STATE_EXACT_V1");
    private static final ArtifactProvenance.Confidence EXACT_STATE_WRITE_CONFIDENCE =
            new ArtifactProvenance.Confidence(
                    1, 1, 1, "SESSION_STATE_WRITE_EXACT_V1");
    private static final FixtureRule.Consumption UNBOUNDED =
            new FixtureRule.Consumption(
                    false, 0, 0, FixtureRule.ExhaustedAction.FAIL,
                    FixtureRule.UnmatchedAction.FAIL);

    private final ObjectMapper mapper;

    /**
     * @param mapper canonical protocol mapper used for aggregate and business-key verification
     */
    public MirrorSessionStateResolver(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public MirrorPlan.MirrorSource source() {
        return MirrorPlan.MirrorSource.SESSION_STATE;
    }

    @Override
    public Optional<Match> resolve(Request request) {
        Objects.requireNonNull(request, "request");
        SessionContext context = request.sessionContext();
        if (context == null) {
            return Optional.empty();
        }
        MirrorSessionPayload payload = context.currentPayload();
        MirrorSessionProtocolIntegrity.verify(mapper, payload);
        SessionStateSpace state = payload.state();
        if (!state.planFingerprint().equals(context.planFingerprint())) {
            throw rejected(
                    "MIRROR_SESSION_PLAN_MISMATCH",
                    "Session state belongs to another mirror plan generation.");
        }
        MirrorArtifactRef capability = context.capabilitiesBySite().get(
                request.site().invocationSiteId());
        if (capability == null) {
            throw rejected(
                    "MIRROR_SESSION_CAPABILITY_BINDING_MISSING",
                    "Session resolver site is absent from the exact plan binding.");
        }
        List<StateReadSpec> candidates = payload.stateReadSpecs().stream()
                .filter(spec -> spec.targetCapabilityRef().equals(capability))
                .toList();
        List<WriteEffectSpec> writeCandidates =
                payload.writeEffects().stream()
                        .filter(effect -> effect.targetCapabilityRef()
                                .equals(capability))
                        .toList();
        if (!candidates.isEmpty() && !writeCandidates.isEmpty()) {
            throw rejected(
                    "MIRROR_SESSION_INTERACTION_AMBIGUOUS",
                    "The capability is bound to both a state read and a virtual write.");
        }
        if (!writeCandidates.isEmpty()) {
            return resolveWrite(
                    request, context, payload, capability,
                    writeCandidates);
        }
        if (candidates.isEmpty()) {
            throw rejected(
                    "MIRROR_SESSION_READ_SPEC_MISSING",
                    "The exact session has no read specification for this capability.");
        }
        if (candidates.size() != 1) {
            throw rejected(
                    "MIRROR_SESSION_READ_SPEC_AMBIGUOUS",
                    "More than one state read specification targets the capability.");
        }
        StateReadSpec spec = candidates.getFirst();
        if (spec.lifecycle() != CapabilitySnapshot.Lifecycle.ACTIVE) {
            throw rejected(
                    "MIRROR_SESSION_READ_SPEC_INACTIVE",
                    "The exact state read specification is not active.");
        }
        StateReadSpecIntegrity.verify(mapper, spec, payload.stateModel());
        List<Object> components;
        try {
            components = spec.keyComponents().stream()
                    .map(expression -> scalar(evaluate(
                            expression, request.input(), Map.of(),
                            state.logicalClock().toString())))
                    .toList();
        } catch (RuntimeException invalid) {
            throw rejected(
                    "MIRROR_SESSION_LOOKUP_INVALID",
                    "State read lookup could not be evaluated from invocation input.");
        }
        String keyFingerprint =
                SessionStateSpaceIntegrity.businessKeyFingerprint(
                        mapper, components);
        Optional<SessionStateSpace.BusinessKeyBinding> indexed =
                state.businessKeyIndex().stream()
                        .filter(binding -> binding.keyName().equals(
                                spec.businessKeyName())
                                && binding.valueFingerprint().equals(
                                keyFingerprint))
                        .findFirst();
        if (indexed.isEmpty()) {
            request.stateAccessObserver().observedAt(
                    request, spec, stateRef(state),
                    state.stateRevision(),
                    state.worldFingerprint(),
                    state.logicalClock(), keyFingerprint,
                    MirrorStateRunEvidence.AccessOutcome.ABSENT,
                    "", "");
            return Optional.empty();
        }
        SessionStateSpace.EntityKey entityKey = indexed.orElseThrow().entityKey();
        if (!entityKey.entityType().equals(spec.entityType())) {
            throw rejected(
                    "MIRROR_SESSION_INDEX_CORRUPT",
                    "State business-key index targets an unexpected entity type.");
        }
        List<MirrorArtifactRef> artifacts = artifactRefs(payload, spec);
        List<String> ruleRefs = List.of(
                "state-read-spec:" + spec.specId() + ":" + spec.revision(),
                "state-business-key:" + spec.businessKeyName() + ":"
                        + keyFingerprint);
        Optional<SessionStateSpace.EntitySnapshot> entity =
                state.entities().stream()
                        .filter(value -> value.key().equals(entityKey))
                        .findFirst();
        if (entity.isPresent()) {
            Object output;
            try {
                output = ProtocolJsonValue.freeze(evaluate(
                        spec.responseProjection(), request.input(),
                        Map.of(StateReadSpec.RESULT_ALIAS,
                                entity.orElseThrow().value()),
                        state.logicalClock().toString()));
            } catch (RuntimeException invalid) {
                throw rejected(
                        "MIRROR_SESSION_PROJECTION_INVALID",
                        "State read response projection could not be evaluated.");
            }
            String outputFingerprint = ProtocolFingerprint.ofBounded(
                    mapper, output, MAXIMUM_PROJECTED_OUTPUT_BYTES);
            request.stateAccessObserver().observedAt(
                    request, spec, stateRef(state),
                    state.stateRevision(),
                    state.worldFingerprint(),
                    state.logicalClock(), keyFingerprint,
                    MirrorStateRunEvidence.AccessOutcome.LIVE_ENTITY,
                    entity.orElseThrow().fingerprint(),
                    outputFingerprint);
            return Optional.of(new Match(
                    rule(spec, state, FixtureRule.Behavior.returning(output)),
                    EXACT_STATE_CONFIDENCE, 1, List.of(),
                    artifacts, ruleRefs));
        }
        Optional<SessionStateSpace.EntityTombstone> tombstone =
                state.tombstones().stream()
                        .filter(value -> value.key().equals(entityKey))
                        .findFirst();
        if (tombstone.isEmpty()) {
            throw rejected(
                    "MIRROR_SESSION_INDEX_CORRUPT",
                    "State business-key index targets no live entity or tombstone.");
        }
        request.stateAccessObserver().observedAt(
                request, spec, stateRef(state),
                state.stateRevision(),
                state.worldFingerprint(),
                state.logicalClock(), keyFingerprint,
                MirrorStateRunEvidence.AccessOutcome.TOMBSTONED,
                tombstone.orElseThrow().fingerprint(), "");
        return Optional.of(new Match(
                rule(spec, state, FixtureRule.Behavior.throwing(
                        ENTITY_TOMBSTONED, "NOT_FOUND",
                        "The exact session entity was deleted.")),
                EXACT_STATE_CONFIDENCE, 1,
                List.of("TOMBSTONE_TERMINAL"), artifacts, ruleRefs));
    }

    private Optional<Match> resolveWrite(
            Request request,
            SessionContext context,
            MirrorSessionPayload payload,
            MirrorArtifactRef capability,
            List<WriteEffectSpec> candidates) {
        if (candidates.size() != 1) {
            throw rejected(
                    "MIRROR_SESSION_WRITE_EFFECT_AMBIGUOUS",
                    "More than one write effect targets the capability.");
        }
        WriteEffectSpec effect = candidates.getFirst();
        if (effect.lifecycle()
                != CapabilitySnapshot.Lifecycle.ACTIVE) {
            throw rejected(
                    "MIRROR_SESSION_WRITE_EFFECT_INACTIVE",
                    "The exact write effect is not active.");
        }
        WriteEffectSpecIntegrity.verify(
                mapper, effect, payload.stateModel());
        if (context.runSession() == null) {
            throw rejected(
                    "MIRROR_SESSION_WRITE_RUNTIME_UNAVAILABLE",
                    "The graph run did not admit a serializable Session write boundary.");
        }
        Map<String, Object> input;
        if (request.input() instanceof Map<?, ?> raw) {
            input = new LinkedHashMap<>();
            raw.forEach((key, value) -> {
                if (!(key instanceof String name)) {
                    throw rejected(
                            "MIRROR_SESSION_WRITE_INPUT_INVALID",
                            "Virtual-write input must be a JSON object.");
                }
                input.put(name, value);
            });
        } else {
            throw rejected(
                    "MIRROR_SESSION_WRITE_INPUT_INVALID",
                    "Virtual-write input must be a JSON object.");
        }
        MirrorStateRunSession.Execution execution;
        try {
            execution = context.runSession().execute(
                    WriteEffectSpecIntegrity.reference(effect),
                    input);
        } catch (TestControlException expected) {
            throw expected;
        } catch (RuntimeException failure) {
            throw rejected(
                    "MIRROR_SESSION_WRITE_FAILED",
                    "The virtual-write transaction did not produce a durable result.");
        }
        Object output = execution.receipt().response();
        String outputFingerprint = ProtocolFingerprint.ofBounded(
                mapper, output, MAXIMUM_PROJECTED_OUTPUT_BYTES);
        request.stateAccessObserver().transitioned(
                request, effect,
                MirrorStateTransitionObservation.project(
                        mapper, effect, execution,
                        outputFingerprint));
        return Optional.of(new Match(
                rule(effect, execution.after().state(),
                        FixtureRule.Behavior.returning(output)),
                EXACT_STATE_WRITE_CONFIDENCE, 1,
                execution.replayed()
                        ? List.of("IDEMPOTENT_REPLAY")
                        : List.of(),
                artifactRefs(execution.after(), effect),
                List.of(
                        "write-effect:" + effect.specId()
                                + ":" + effect.revision(),
                        "transaction-receipt:"
                                + execution.receipt().fingerprint())));
    }

    private FixtureRule rule(
            StateReadSpec spec,
            SessionStateSpace state,
            FixtureRule.Behavior behavior) {
        return new FixtureRule(
                FixtureRule.SCHEMA_VERSION,
                "session-state:" + spec.specId() + ":"
                        + state.stateRevision(),
                FixtureRule.Selector.any(), behavior, UNBOUNDED,
                FixtureRule.SchemaCheck.strict());
    }

    private FixtureRule rule(
            WriteEffectSpec effect,
            SessionStateSpace state,
            FixtureRule.Behavior behavior) {
        return new FixtureRule(
                FixtureRule.SCHEMA_VERSION,
                "session-write:" + effect.specId() + ":"
                        + state.stateRevision(),
                FixtureRule.Selector.any(), behavior, UNBOUNDED,
                FixtureRule.SchemaCheck.strict());
    }

    private static List<MirrorArtifactRef> artifactRefs(
            MirrorSessionPayload payload, StateReadSpec spec) {
        SessionStateSpace state = payload.state();
        return List.of(
                new MirrorArtifactRef(
                        "SESSION_STATE", state.sessionId(),
                        Math.addExact(state.stateRevision(), 1),
                        state.fingerprint()),
                StateModelIntegrity.reference(payload.stateModel()),
                StateReadSpecIntegrity.reference(spec));
    }

    private static MirrorArtifactRef stateRef(
            SessionStateSpace state) {
        return new MirrorArtifactRef(
                "SESSION_STATE", state.sessionId(),
                Math.addExact(state.stateRevision(), 1),
                state.fingerprint());
    }

    private static List<MirrorArtifactRef> artifactRefs(
            MirrorSessionPayload payload,
            WriteEffectSpec effect) {
        SessionStateSpace state = payload.state();
        return List.of(
                new MirrorArtifactRef(
                        "SESSION_STATE", state.sessionId(),
                        Math.addExact(state.stateRevision(), 1),
                        state.fingerprint()),
                StateModelIntegrity.reference(payload.stateModel()),
                WriteEffectSpecIntegrity.reference(effect));
    }

    private static Object evaluate(
            BoundedStateExpression expression,
            Object input,
            Map<String, Map<String, Object>> entities,
            String logicalTime) {
        return switch (expression.operator()) {
            case LITERAL -> expression.literal();
            case INPUT_POINTER -> readPointer(input, expression.path());
            case ENTITY_POINTER -> {
                Map<String, Object> entity = entities.get(expression.reference());
                if (entity == null) {
                    throw new IllegalArgumentException("entity alias is unavailable");
                }
                yield readPointer(entity, expression.path());
            }
            case LOGICAL_TIME -> logicalTime;
            case DETERMINISTIC_ID, SEQUENCE ->
                    throw new IllegalArgumentException(
                            "state reads cannot allocate identity or sequence");
            case ADD -> decimal(evaluate(
                    argument(expression, 0), input, entities, logicalTime))
                    .add(decimal(evaluate(
                            argument(expression, 1), input, entities, logicalTime)));
            case CONCAT -> text(evaluate(
                    argument(expression, 0), input, entities, logicalTime))
                    + text(evaluate(
                            argument(expression, 1), input, entities, logicalTime));
            case EQUALS -> equivalent(
                    evaluate(argument(expression, 0), input, entities, logicalTime),
                    evaluate(argument(expression, 1), input, entities, logicalTime));
            case GREATER_THAN_OR_EQUAL -> decimal(evaluate(
                    argument(expression, 0), input, entities, logicalTime))
                    .compareTo(decimal(evaluate(
                            argument(expression, 1), input, entities, logicalTime))) >= 0;
            case NOT_NULL -> evaluate(
                    argument(expression, 0), input, entities, logicalTime) != null;
            case AND -> {
                boolean result = true;
                for (BoundedStateExpression child : expression.arguments()) {
                    if (!booleanValue(evaluate(
                            child, input, entities, logicalTime))) {
                        result = false;
                        break;
                    }
                }
                yield result;
            }
            case OBJECT -> {
                Map<String, Object> value = new LinkedHashMap<>();
                expression.fields().forEach((name, child) ->
                        value.put(name, ProtocolJsonValue.freeze(evaluate(
                                child, input, entities, logicalTime))));
                yield ProtocolJsonValue.freezeMap(value);
            }
        };
    }

    private static Object readPointer(Object root, String pointer) {
        if (pointer == null || pointer.isEmpty()) {
            return root;
        }
        Object current = root;
        String[] tokens = pointer.substring(1).split("/", -1);
        for (String raw : tokens) {
            String token = decodePointerToken(raw);
            if (current instanceof Map<?, ?> map) {
                if (!map.containsKey(token)) {
                    throw new IllegalArgumentException("JSON Pointer is absent");
                }
                current = map.get(token);
            } else if (current instanceof List<?> list) {
                int index = Integer.parseInt(token);
                if (index < 0 || index >= list.size()) {
                    throw new IllegalArgumentException("JSON Pointer is absent");
                }
                current = list.get(index);
            } else {
                throw new IllegalArgumentException("JSON Pointer is absent");
            }
        }
        return current;
    }

    private static String decodePointerToken(String value) {
        StringBuilder decoded = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '~') {
                decoded.append(current);
                continue;
            }
            if (++index >= value.length()) {
                throw new IllegalArgumentException("invalid JSON Pointer escape");
            }
            char escaped = value.charAt(index);
            if (escaped == '0') {
                decoded.append('~');
            } else if (escaped == '1') {
                decoded.append('/');
            } else {
                throw new IllegalArgumentException("invalid JSON Pointer escape");
            }
        }
        return decoded.toString();
    }

    private static BoundedStateExpression argument(
            BoundedStateExpression expression, int index) {
        return expression.arguments().get(index);
    }

    private static Object scalar(Object value) {
        if (value == null || value instanceof Map<?, ?>
                || value instanceof Collection<?>
                || value.getClass().isArray()) {
            throw new IllegalArgumentException(
                    "business key components must be non-null JSON scalars");
        }
        return value;
    }

    private static String text(Object value) {
        return String.valueOf(scalar(value));
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        throw new IllegalArgumentException("expression value is not numeric");
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean result) {
            return result;
        }
        throw new IllegalArgumentException("expression value is not boolean");
    }

    private static boolean equivalent(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return decimal(left).compareTo(decimal(right)) == 0;
        }
        return Objects.equals(left, right);
    }

    private static TestControlException rejected(String code, String title) {
        return new TestControlException(code, "MIRROR_SESSION_STATE", title);
    }
}
