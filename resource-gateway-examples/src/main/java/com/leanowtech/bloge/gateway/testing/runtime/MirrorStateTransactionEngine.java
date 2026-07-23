package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.BoundedStateExpression;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.integration.mirror.SessionStateSpace;
import com.leanowtech.bloge.gateway.integration.mirror.SessionStateSpaceIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.StateModel;
import com.leanowtech.bloge.gateway.integration.mirror.StateModelIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.WriteEffectSpec;
import com.leanowtech.bloge.gateway.integration.mirror.WriteEffectSpecIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.ProtocolJsonValue;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-process serializable transaction kernel for one isolated mirror session.
 *
 * <p>The engine owns exactly one immutable {@link SessionStateSpace} head and commits under a fair
 * mutex. It evaluates a sealed {@link WriteEffectSpec} against detached input and working copies,
 * validates entity schemas and business-key uniqueness, seals the candidate world and receipt,
 * invokes the storage guard, and only then swaps the head. A failure, interruption, timeout
 * adapter, or storage rejection before that swap leaves entities, journal, sequence, and logical
 * time unchanged.</p>
 *
 * <p>No operator registry, HTTP client, secret provider, production interceptor, or fallback is
 * accepted by this class. Historical copy-on-write can enter only through
 * {@link MirrorStateBaselineResolver}, whose closed source vocabulary excludes cluster and
 * synthesized baselines.</p>
 */
public final class MirrorStateTransactionEngine {
    private static final String SESSION_EXPIRED = "RG.MIRROR.STATE.SESSION_EXPIRED";
    private static final String IDEMPOTENCY_CONFLICT =
            "RG.MIRROR.STATE.IDEMPOTENCY_CONFLICT";
    private static final String BASELINE_ABSENT = "RG.MIRROR.STATE.BASELINE_ABSENT";
    private static final String COMMIT_FAILED = "RG.MIRROR.STATE.COMMIT_FAILED";
    private static final String CANCELLED = "RG.MIRROR.STATE.CANCELLED_BEFORE_COMMIT";
    private static final String ENTITY_EXISTS = "RG.MIRROR.STATE.ENTITY_ALREADY_EXISTS";
    private static final String ENTITY_TOMBSTONED = "RG.MIRROR.STATE.ENTITY_TOMBSTONED";
    private static final String ENTITY_SCHEMA_INVALID =
            "RG.MIRROR.STATE.ENTITY_SCHEMA_INVALID";
    private static final String WRITE_EFFECT_NOT_ACTIVE =
            "RG.MIRROR.STATE.WRITE_EFFECT_NOT_ACTIVE";
    private static final String BUSINESS_KEY_CONFLICT =
            "RG.MIRROR.STATE.BUSINESS_KEY_CONFLICT";
    private static final String EXPRESSION_INVALID =
            "RG.MIRROR.STATE.EXPRESSION_INVALID";
    private static final String EXPRESSION_MISSING =
            "RG.MIRROR.STATE.EXPRESSION_MISSING_VALUE";
    private static final int MAXIMUM_COMMAND_BYTES = 16 * 1024 * 1024;

    private final ObjectMapper mapper;
    private final StateModel stateModel;
    private final Map<String, StateModel.EntityType> entityTypes;
    private final MirrorStateBaselineResolver baselineResolver;
    private final Clock clock;
    private final CommitGuard commitGuard;
    private final ReentrantLock mutationLock = new ReentrantLock(true);
    private volatile SessionStateSpace head;

    /**
     * Creates one session-owned transaction engine.
     *
     * @param mapper canonical protocol mapper
     * @param stateModel exact sealed model
     * @param initialState exact sealed initial state
     * @param baselineResolver allowed copy-on-write source boundary
     * @param clock server clock used only for hard expiry admission
     * @param commitGuard storage/checkpoint guard invoked before the atomic head swap
     */
    public MirrorStateTransactionEngine(
            ObjectMapper mapper,
            StateModel stateModel,
            SessionStateSpace initialState,
            MirrorStateBaselineResolver baselineResolver,
            Clock clock,
            CommitGuard commitGuard) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.stateModel = Objects.requireNonNull(stateModel, "stateModel");
        StateModelIntegrity.verify(mapper, stateModel);
        if (stateModel.lifecycle() != CapabilitySnapshot.Lifecycle.ACTIVE
                || !stateModel.provenance().revocationRef().isBlank()
                || expired(stateModel.provenance().expiresAt())) {
            throw new IllegalArgumentException(
                    "state model must be active, unrevoked, and unexpired");
        }
        this.entityTypes = stateModel.entityTypes().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        StateModel.EntityType::entityType, value -> value));
        this.baselineResolver = Objects.requireNonNull(baselineResolver, "baselineResolver");
        this.commitGuard = Objects.requireNonNull(commitGuard, "commitGuard");
        SessionStateSpaceIntegrity.verify(mapper,
                Objects.requireNonNull(initialState, "initialState"));
        if (!initialState.scope().equals(stateModel.scope())
                || !initialState.stateModelRef().equals(
                StateModelIntegrity.reference(stateModel))) {
            throw new IllegalArgumentException(
                    "initial session state does not bind the supplied state model");
        }
        validateStateAgainstModel(initialState);
        this.head = initialState;
    }

    /**
     * Executes or exactly replays one keyed virtual write transaction.
     *
     * @param effect exact sealed write effect admitted by the session
     * @param input detached JSON command input
     * @return original or newly committed receipt
     * @throws MirrorStateException for stable fail-closed admission or transaction failures
     */
    public SessionStateSpace.TransactionReceipt execute(
            WriteEffectSpec effect, Map<String, ?> input) {
        return execute(effect, input, NewCommandGuard.noop());
    }

    /**
     * Executes or exactly replays one keyed virtual write transaction with a new-command fence.
     *
     * <p>The guard runs only after effect/idempotency validation proves that no exact prior receipt
     * exists, and before interruption checks, baseline resolution, expressions, or mutations. It
     * is therefore suitable for optimistic state fences without breaking ambiguous-request
     * replay.</p>
     *
     * @param effect exact sealed write effect admitted by the session
     * @param input detached JSON command input
     * @param newCommandGuard admission fence invoked only for a genuinely new command
     * @return original or newly committed receipt
     * @throws MirrorStateException for stable fail-closed admission or transaction failures
     */
    public SessionStateSpace.TransactionReceipt execute(
            WriteEffectSpec effect,
            Map<String, ?> input,
            NewCommandGuard newCommandGuard) {
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(newCommandGuard, "newCommandGuard");
        Map<String, Object> detachedInput = ProtocolJsonValue.freezeMap(input);
        mutationLock.lock();
        try {
            SessionStateSpace current = head;
            ensureActive(current);
            WriteEffectSpecIntegrity.verify(mapper, effect, stateModel);
            if (effect.lifecycle() != CapabilitySnapshot.Lifecycle.ACTIVE
                    || !effect.provenance().revocationRef().isBlank()
                    || expired(effect.provenance().expiresAt())) {
                throw reject(WRITE_EFFECT_NOT_ACTIVE);
            }
            MirrorArtifactRef effectRef = WriteEffectSpecIntegrity.reference(effect);
            if (!current.writeEffectRefs().contains(effectRef)) {
                throw reject("RG.MIRROR.STATE.WRITE_EFFECT_NOT_ADMITTED");
            }
            String idempotencyKey = scalarIdentity(
                    readPointer(detachedInput, effect.idempotency().keyPath()));
            String commandFingerprint = commandFingerprint(
                    current, effectRef, detachedInput, idempotencyKey);
            Optional<SessionStateSpace.TransactionReceipt> previous =
                    current.processedCommands().stream()
                            .filter(receipt -> receipt.idempotencyKey()
                                    .equals(idempotencyKey))
                            .findFirst();
            if (previous.isPresent()) {
                if (!previous.orElseThrow().commandFingerprint()
                        .equals(commandFingerprint)) {
                    throw reject(IDEMPOTENCY_CONFLICT);
                }
                return previous.orElseThrow();
            }
            newCommandGuard.beforeExecute(current);
            if (Thread.currentThread().isInterrupted()) {
                throw reject(CANCELLED);
            }
            return executeNew(current, effect, detachedInput,
                    idempotencyKey, commandFingerprint);
        } finally {
            mutationLock.unlock();
        }
    }

    /**
     * Returns the current immutable state head.
     *
     * @return content-addressed session snapshot
     */
    public SessionStateSpace snapshot() {
        return head;
    }

    private SessionStateSpace.TransactionReceipt executeNew(
            SessionStateSpace current,
            WriteEffectSpec effect,
            Map<String, Object> input,
            String idempotencyKey,
            String commandFingerprint) {
        long revisionAfter = Math.addExact(current.stateRevision(), 1);
        WorkingWorld world = WorkingWorld.from(current);
        EvaluationContext evaluation = new EvaluationContext(
                current, input, idempotencyKey, commandFingerprint,
                revisionAfter, new LinkedHashMap<>());
        List<SessionStateSpace.StateTransitionEvent> transactionEvents = new ArrayList<>();
        for (WriteEffectSpec.Mutation mutation : effect.mutations()) {
            applyMutation(world, evaluation, mutation, transactionEvents);
        }
        for (StateModel.Invariant invariant : stateModel.invariants()) {
            if (!booleanValue(evaluate(invariant.predicate(), evaluation))) {
                throw reject(invariant.errorCode());
            }
        }
        Object response = ProtocolJsonValue.freeze(
                evaluate(effect.responseProjection(), evaluation));
        Instant committedAt = current.logicalClock();
        List<SessionStateSpace.StateTransitionEvent> events =
                new ArrayList<>(current.committedEvents());
        events.addAll(transactionEvents);
        SessionStateSpace provisional = current.withWorld(
                revisionAfter,
                current.logicalClock().plusMillis(1),
                world.entities(),
                world.tombstones(),
                world.businessKeys(),
                events,
                current.processedCommands());
        String resultingWorldFingerprint =
                SessionStateSpaceIntegrity.fingerprintWorld(mapper, provisional);
        SessionStateSpace.TransactionReceipt receipt =
                SessionStateSpaceIntegrity.sealReceipt(mapper,
                        new SessionStateSpace.TransactionReceipt(
                                idempotencyKey,
                                commandFingerprint,
                                current.stateRevision(),
                                revisionAfter,
                                transactionEvents.stream()
                                        .map(SessionStateSpace.StateTransitionEvent::eventId)
                                        .toList(),
                                response,
                                ProtocolFingerprint.ofBounded(
                                        mapper, response,
                                        SessionStateSpaceIntegrity.MAXIMUM_RESPONSE_BYTES),
                                resultingWorldFingerprint,
                                committedAt,
                                ""));
        List<SessionStateSpace.TransactionReceipt> receipts =
                new ArrayList<>(current.processedCommands());
        receipts.add(receipt);
        SessionStateSpace candidate = SessionStateSpaceIntegrity.seal(mapper,
                provisional.withWorld(
                        revisionAfter,
                        provisional.logicalClock(),
                        provisional.entities(),
                        provisional.tombstones(),
                        provisional.businessKeyIndex(),
                        provisional.committedEvents(),
                        receipts));
        validateStateAgainstModel(candidate);
        if (Thread.currentThread().isInterrupted()) {
            throw reject(CANCELLED);
        }
        try {
            commitGuard.beforeCommit(current, candidate);
        } catch (MirrorStateException rejected) {
            throw rejected;
        } catch (RuntimeException storageFailure) {
            throw reject(COMMIT_FAILED);
        }
        head = candidate;
        return receipt;
    }

    private void applyMutation(
            WorkingWorld world,
            EvaluationContext evaluation,
            WriteEffectSpec.Mutation mutation,
            List<SessionStateSpace.StateTransitionEvent> events) {
        String entityId = scalarIdentity(evaluate(mutation.identity(), evaluation));
        SessionStateSpace.EntityKey key =
                new SessionStateSpace.EntityKey(mutation.entityType(), entityId);
        SessionStateSpace.EntitySnapshot existing = world.entity(key);
        boolean tombstoned = world.tombstone(key) != null;
        if (mutation.operation() == WriteEffectSpec.Operation.CREATE) {
            if (tombstoned) {
                throw reject(ENTITY_TOMBSTONED);
            }
            if (existing != null) {
                throw reject(ENTITY_EXISTS);
            }
        } else if (existing == null && tombstoned) {
            throw reject(ENTITY_TOMBSTONED);
        } else if (existing == null && mutation.operation() != WriteEffectSpec.Operation.UPSERT) {
            existing = copyInBaseline(world, evaluation, mutation, key, events);
        }

        Map<String, Object> value = existing == null
                ? new LinkedHashMap<>()
                : mutableMap(existing.value());
        evaluation.aliases().put(mutation.mutationId(), value);
        for (WriteEffectSpec.Precondition precondition : mutation.preconditions()) {
            if (!booleanValue(evaluate(precondition.predicate(), evaluation))) {
                throw reject(precondition.errorCode());
            }
        }
        String before = existing == null ? "" : existing.fingerprint();
        if (mutation.operation() == WriteEffectSpec.Operation.DELETE) {
            world.removeEntity(key);
            world.removeBusinessKeys(key);
            SessionStateSpace.EntityTombstone tombstone =
                    SessionStateSpaceIntegrity.sealTombstone(mapper,
                            new SessionStateSpace.EntityTombstone(
                                    key, evaluation.revisionAfter(), before,
                                    evaluation.state().logicalClock(), ""));
            world.putTombstone(tombstone);
            events.add(event(evaluation, mutation,
                    SessionStateSpace.TransitionOperation.DELETE, key, before, "", events.size()));
            return;
        }

        Map<String, Object> assignments = new LinkedHashMap<>();
        for (WriteEffectSpec.FieldEffect field : mutation.fieldEffects()) {
            assignments.put(field.path(), ProtocolJsonValue.freeze(
                    evaluate(field.value(), evaluation)));
        }
        assignments.forEach((path, assigned) -> setPointer(value, path, assigned));
        validateEntity(mutation.entityType(), value);
        SessionStateSpace.EntitySnapshot updated = SessionStateSpaceIntegrity.sealEntity(
                mapper,
                new SessionStateSpace.EntitySnapshot(
                        key, existing == null ? 1 : Math.addExact(existing.version(), 1),
                        value, ""));
        world.putEntity(updated);
        world.removeBusinessKeys(key);
        evaluation.aliases().put(mutation.mutationId(), value);
        for (WriteEffectSpec.BusinessKeyRule rule : mutation.businessKeys()) {
            List<Object> components = rule.components().stream()
                    .map(component -> businessKeyComponent(evaluate(component, evaluation)))
                    .toList();
            SessionStateSpace.BusinessKeyBinding binding =
                    SessionStateSpaceIntegrity.businessKey(
                            mapper, rule.name(), components, key);
            world.putBusinessKey(binding);
        }
        SessionStateSpace.TransitionOperation operation = switch (mutation.operation()) {
            case CREATE -> SessionStateSpace.TransitionOperation.CREATE;
            case UPDATE -> SessionStateSpace.TransitionOperation.UPDATE;
            case UPSERT -> SessionStateSpace.TransitionOperation.UPSERT;
            case DELETE -> throw new IllegalStateException("DELETE already handled");
        };
        events.add(event(evaluation, mutation, operation,
                key, before, updated.fingerprint(), events.size()));
    }

    private SessionStateSpace.EntitySnapshot copyInBaseline(
            WorkingWorld world,
            EvaluationContext evaluation,
            WriteEffectSpec.Mutation mutation,
            SessionStateSpace.EntityKey key,
            List<SessionStateSpace.StateTransitionEvent> events) {
        if (world.tombstone(key) != null || mutation.baselineReadCapabilityRef() == null) {
            throw reject(BASELINE_ABSENT);
        }
        MirrorStateBaselineResolver.Baseline baseline;
        try {
            baseline = baselineResolver.resolve(new MirrorStateBaselineResolver.Request(
                            evaluation.state().sessionId(),
                            evaluation.state().scope(),
                            mutation.baselineReadCapabilityRef(),
                            key))
                    .orElseThrow(() -> reject(BASELINE_ABSENT));
        } catch (MirrorStateException rejected) {
            throw rejected;
        } catch (RuntimeException unavailable) {
            throw reject("RG.MIRROR.STATE.BASELINE_AUTHORITY_UNAVAILABLE");
        }
        SessionStateSpace.EntitySnapshot copied =
                SessionStateSpaceIntegrity.sealEntity(mapper, baseline.entity());
        if (!key.equals(copied.key())) {
            throw reject("RG.MIRROR.STATE.BASELINE_IDENTITY_MISMATCH");
        }
        validateEntity(copied.key().entityType(), copied.value());
        world.putEntity(copied);
        for (SessionStateSpace.BusinessKeyBinding binding : baseline.businessKeys()) {
            world.putBusinessKey(binding);
        }
        events.add(event(evaluation, mutation,
                SessionStateSpace.TransitionOperation.COPY_IN,
                key, "", copied.fingerprint(), events.size()));
        return copied;
    }

    private SessionStateSpace.StateTransitionEvent event(
            EvaluationContext evaluation,
            WriteEffectSpec.Mutation mutation,
            SessionStateSpace.TransitionOperation operation,
            SessionStateSpace.EntityKey key,
            String before,
            String after,
            int ordinal) {
        String eventId = "event-" + evaluation.revisionAfter() + "-" + (ordinal + 1);
        return SessionStateSpaceIntegrity.sealEvent(mapper,
                new SessionStateSpace.StateTransitionEvent(
                        eventId,
                        evaluation.revisionAfter(),
                        mutation.mutationId(),
                        operation,
                        key,
                        before,
                        after,
                        evaluation.state().logicalClock(),
                        ""));
    }

    private Object evaluate(
            BoundedStateExpression expression, EvaluationContext context) {
        return switch (expression.operator()) {
            case LITERAL -> expression.literal();
            case INPUT_POINTER -> readPointer(context.input(), expression.path());
            case ENTITY_POINTER -> {
                Map<String, Object> entity = context.aliases().get(expression.reference());
                if (entity == null) {
                    throw reject(EXPRESSION_MISSING);
                }
                yield readPointer(entity, expression.path());
            }
            case LOGICAL_TIME -> context.state().logicalClock().toString();
            case DETERMINISTIC_ID -> deterministicId(context, expression.reference());
            case SEQUENCE -> context.revisionAfter();
            case ADD -> decimal(evaluate(argument(expression, 0), context))
                    .add(decimal(evaluate(argument(expression, 1), context)));
            case CONCAT -> text(evaluate(argument(expression, 0), context))
                    + text(evaluate(argument(expression, 1), context));
            case EQUALS -> equivalent(
                    evaluate(argument(expression, 0), context),
                    evaluate(argument(expression, 1), context));
            case GREATER_THAN_OR_EQUAL -> decimal(
                    evaluate(argument(expression, 0), context))
                    .compareTo(decimal(evaluate(argument(expression, 1), context))) >= 0;
            case NOT_NULL -> evaluate(argument(expression, 0), context) != null;
            case AND -> {
                boolean result = true;
                for (BoundedStateExpression argument : expression.arguments()) {
                    if (!booleanValue(evaluate(argument, context))) {
                        result = false;
                        break;
                    }
                }
                yield result;
            }
            case OBJECT -> {
                Map<String, Object> value = new LinkedHashMap<>();
                expression.fields().forEach((name, nested) ->
                        value.put(name, ProtocolJsonValue.freeze(evaluate(nested, context))));
                yield ProtocolJsonValue.freezeMap(value);
            }
        };
    }

    private void validateStateAgainstModel(SessionStateSpace state) {
        Map<String, List<SessionStateSpace.BusinessKeyBinding>> keysByEntity = new HashMap<>();
        for (SessionStateSpace.BusinessKeyBinding binding : state.businessKeyIndex()) {
            String coordinate = entityCoordinate(binding.entityKey());
            keysByEntity.computeIfAbsent(coordinate, ignored -> new ArrayList<>()).add(binding);
        }
        for (SessionStateSpace.EntitySnapshot entity : state.entities()) {
            StateModel.EntityType type = entityTypes.get(entity.key().entityType());
            if (type == null) {
                throw new IllegalArgumentException(
                        "session contains an entity type absent from its state model");
            }
            validateEntity(type.entityType(), entity.value());
            List<SessionStateSpace.BusinessKeyBinding> actual = keysByEntity.getOrDefault(
                    entityCoordinate(entity.key()), List.of());
            if (actual.size() != type.businessKeys().size()) {
                throw new IllegalArgumentException(
                        "session entity business-key closure is incomplete");
            }
            Map<String, SessionStateSpace.BusinessKeyBinding> byName = actual.stream()
                    .collect(java.util.stream.Collectors.toMap(
                            SessionStateSpace.BusinessKeyBinding::keyName, value -> value));
            for (StateModel.BusinessKeyDefinition definition : type.businessKeys()) {
                SessionStateSpace.BusinessKeyBinding binding = byName.get(definition.name());
                if (binding == null) {
                    throw new IllegalArgumentException(
                            "session entity business-key definition is missing");
                }
                List<Object> components = definition.fieldPaths().stream()
                        .map(path -> businessKeyComponent(readPointer(entity.value(), path)))
                        .toList();
                SessionStateSpace.BusinessKeyBinding expected =
                        SessionStateSpaceIntegrity.businessKey(
                                mapper, definition.name(), components, entity.key());
                if (!expected.equals(binding)) {
                    throw new IllegalArgumentException(
                            "session entity business-key binding has drifted");
                }
            }
        }
        for (SessionStateSpace.EntityTombstone tombstone : state.tombstones()) {
            if (!entityTypes.containsKey(tombstone.key().entityType())) {
                throw new IllegalArgumentException(
                        "session tombstone type is absent from its state model");
            }
        }
    }

    private void validateEntity(String entityType, Map<String, Object> value) {
        StateModel.EntityType type = entityTypes.get(entityType);
        if (type == null || !VisualSchemaValidator.validateValue(
                type.schema(), value, "/entity").isEmpty()) {
            throw reject(ENTITY_SCHEMA_INVALID);
        }
    }

    private void ensureActive(SessionStateSpace state) {
        if (!clock.instant().isBefore(state.expiresAt())) {
            throw reject(SESSION_EXPIRED);
        }
    }

    private boolean expired(Instant expiresAt) {
        return expiresAt != null && !clock.instant().isBefore(expiresAt);
    }

    private String commandFingerprint(
            SessionStateSpace state,
            MirrorArtifactRef effectRef,
            Map<String, Object> input,
            String idempotencyKey) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("sessionId", state.sessionId());
        material.put("planFingerprint", state.planFingerprint());
        material.put("stateModelRef", state.stateModelRef());
        material.put("writeEffectRef", effectRef);
        material.put("idempotencyKey", idempotencyKey);
        material.put("input", input);
        try {
            return ProtocolFingerprint.ofBounded(mapper, material, MAXIMUM_COMMAND_BYTES);
        } catch (IllegalArgumentException invalid) {
            throw reject("RG.MIRROR.STATE.COMMAND_TOO_LARGE");
        }
    }

    private String deterministicId(EvaluationContext context, String scope) {
        String material = context.state().sessionId() + "\0"
                + context.commandFingerprint() + "\0" + scope;
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static BoundedStateExpression argument(
            BoundedStateExpression expression, int index) {
        return expression.arguments().get(index);
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
                    throw reject(EXPRESSION_MISSING);
                }
                current = map.get(token);
            } else if (current instanceof List<?> list) {
                int index;
                try {
                    index = Integer.parseInt(token);
                } catch (NumberFormatException invalid) {
                    throw reject(EXPRESSION_INVALID);
                }
                if (index < 0 || index >= list.size()) {
                    throw reject(EXPRESSION_MISSING);
                }
                current = list.get(index);
            } else {
                throw reject(EXPRESSION_MISSING);
            }
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static void setPointer(
            Map<String, Object> root, String pointer, Object value) {
        String[] tokens = pointer.substring(1).split("/", -1);
        Map<String, Object> current = root;
        for (int index = 0; index < tokens.length - 1; index++) {
            String token = decodePointerToken(tokens[index]);
            Object nested = current.get(token);
            if (nested == null) {
                Map<String, Object> created = new LinkedHashMap<>();
                current.put(token, created);
                current = created;
            } else if (nested instanceof Map<?, ?> map) {
                current = (Map<String, Object>) map;
            } else {
                throw reject(EXPRESSION_INVALID);
            }
        }
        current.put(decodePointerToken(tokens[tokens.length - 1]),
                mutableValue(value));
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
                throw reject(EXPRESSION_INVALID);
            }
            char escaped = value.charAt(index);
            if (escaped == '0') {
                decoded.append('~');
            } else if (escaped == '1') {
                decoded.append('/');
            } else {
                throw reject(EXPRESSION_INVALID);
            }
        }
        return decoded.toString();
    }

    private static Object businessKeyComponent(Object value) {
        if (value == null || value instanceof Map<?, ?> || value instanceof Collection<?>
                || value.getClass().isArray()) {
            throw reject(EXPRESSION_INVALID);
        }
        return value;
    }

    private static String scalarIdentity(Object value) {
        Object scalar = businessKeyComponent(value);
        String text = String.valueOf(scalar).trim();
        if (text.isEmpty()) {
            throw reject(EXPRESSION_INVALID);
        }
        return text;
    }

    private static String text(Object value) {
        if (value == null || value instanceof Map<?, ?> || value instanceof Collection<?>) {
            throw reject(EXPRESSION_INVALID);
        }
        return String.valueOf(value);
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString());
            } catch (NumberFormatException invalid) {
                throw reject(EXPRESSION_INVALID);
            }
        }
        throw reject(EXPRESSION_INVALID);
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean result) {
            return result;
        }
        throw reject(EXPRESSION_INVALID);
    }

    private static boolean equivalent(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return decimal(left).compareTo(decimal(right)) == 0;
        }
        return Objects.equals(left, right);
    }

    private static String entityCoordinate(SessionStateSpace.EntityKey key) {
        return key.entityType() + "\0" + key.entityId();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutableMap(Map<String, ?> value) {
        return (Map<String, Object>) mutableValue(value);
    }

    private static Object mutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) ->
                    copy.put(String.valueOf(key), mutableValue(nested)));
            return copy;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> copy = new ArrayList<>(collection.size());
            collection.forEach(nested -> copy.add(mutableValue(nested)));
            return copy;
        }
        return value;
    }

    private static MirrorStateException reject(String code) {
        return new MirrorStateException(code);
    }

    /**
     * Admission fence invoked only when a command is not an exact idempotency replay.
     *
     * <p>Implementations must be payload-safe, bounded, and free of external side effects. Throwing
     * aborts before baseline resolution or candidate mutation.</p>
     */
    @FunctionalInterface
    public interface NewCommandGuard {
        /**
         * Admits one new command against the exact current state.
         *
         * @param current exact current session head
         */
        void beforeExecute(SessionStateSpace current);

        /** @return a no-op guard for callers without an additional admission fence */
        static NewCommandGuard noop() {
            return current -> {
            };
        }
    }

    /**
     * Atomic persistence boundary. Implementations must not mutate either supplied snapshot.
     *
     * <p>The implementation must compare the expected fingerprint and persist the candidate as
     * one all-or-nothing operation. Returning means the candidate is committed; interruption after
     * return cannot turn that fact into a cancellation. Throwing must mean no candidate became
     * externally visible.</p>
     */
    @FunctionalInterface
    public interface CommitGuard {
        /**
         * Atomically fences the expected head and persists the exact candidate.
         *
         * @param expected exact previously committed head
         * @param candidate sealed candidate state
         */
        void beforeCommit(SessionStateSpace expected, SessionStateSpace candidate);

        /** @return a no-op guard for isolated in-memory tests */
        static CommitGuard noop() {
            return (expected, candidate) -> {
            };
        }
    }

    private record EvaluationContext(
            SessionStateSpace state,
            Map<String, Object> input,
            String idempotencyKey,
            String commandFingerprint,
            long revisionAfter,
            Map<String, Map<String, Object>> aliases
    ) {
    }

    private static final class WorkingWorld {
        private final Map<SessionStateSpace.EntityKey, SessionStateSpace.EntitySnapshot> entities;
        private final Map<SessionStateSpace.EntityKey, SessionStateSpace.EntityTombstone> tombstones;
        private final Map<String, SessionStateSpace.BusinessKeyBinding> businessKeys;

        private WorkingWorld(
                Map<SessionStateSpace.EntityKey, SessionStateSpace.EntitySnapshot> entities,
                Map<SessionStateSpace.EntityKey, SessionStateSpace.EntityTombstone> tombstones,
                Map<String, SessionStateSpace.BusinessKeyBinding> businessKeys) {
            this.entities = entities;
            this.tombstones = tombstones;
            this.businessKeys = businessKeys;
        }

        static WorkingWorld from(SessionStateSpace state) {
            Map<SessionStateSpace.EntityKey, SessionStateSpace.EntitySnapshot> entities =
                    new LinkedHashMap<>();
            state.entities().forEach(entity -> entities.put(entity.key(), entity));
            Map<SessionStateSpace.EntityKey, SessionStateSpace.EntityTombstone> tombstones =
                    new LinkedHashMap<>();
            state.tombstones().forEach(tombstone ->
                    tombstones.put(tombstone.key(), tombstone));
            Map<String, SessionStateSpace.BusinessKeyBinding> businessKeys =
                    new LinkedHashMap<>();
            state.businessKeyIndex().forEach(binding ->
                    businessKeys.put(coordinate(binding), binding));
            return new WorkingWorld(entities, tombstones, businessKeys);
        }

        SessionStateSpace.EntitySnapshot entity(SessionStateSpace.EntityKey key) {
            return entities.get(key);
        }

        SessionStateSpace.EntityTombstone tombstone(SessionStateSpace.EntityKey key) {
            return tombstones.get(key);
        }

        void putEntity(SessionStateSpace.EntitySnapshot entity) {
            entities.put(entity.key(), entity);
        }

        void removeEntity(SessionStateSpace.EntityKey key) {
            entities.remove(key);
        }

        void putTombstone(SessionStateSpace.EntityTombstone tombstone) {
            tombstones.put(tombstone.key(), tombstone);
        }

        void removeBusinessKeys(SessionStateSpace.EntityKey key) {
            businessKeys.entrySet().removeIf(entry ->
                    entry.getValue().entityKey().equals(key));
        }

        void putBusinessKey(SessionStateSpace.BusinessKeyBinding binding) {
            String coordinate = coordinate(binding);
            SessionStateSpace.BusinessKeyBinding previous = businessKeys.get(coordinate);
            if (previous != null && !previous.entityKey().equals(binding.entityKey())) {
                throw reject(BUSINESS_KEY_CONFLICT);
            }
            businessKeys.put(coordinate, binding);
        }

        List<SessionStateSpace.EntitySnapshot> entities() {
            return entities.values().stream().sorted(
                    Comparator.comparing(SessionStateSpace.EntitySnapshot::key)).toList();
        }

        List<SessionStateSpace.EntityTombstone> tombstones() {
            return tombstones.values().stream().sorted(
                    Comparator.comparing(SessionStateSpace.EntityTombstone::key)).toList();
        }

        List<SessionStateSpace.BusinessKeyBinding> businessKeys() {
            return businessKeys.values().stream().sorted(
                    Comparator.comparing(SessionStateSpace.BusinessKeyBinding::keyName)
                            .thenComparing(
                                    SessionStateSpace.BusinessKeyBinding::valueFingerprint))
                    .toList();
        }

        private static String coordinate(SessionStateSpace.BusinessKeyBinding binding) {
            return binding.keyName() + "\0" + binding.valueFingerprint();
        }
    }
}
