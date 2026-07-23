package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Independent strict-schema and canonical-integrity verifier for stateful mirror artifacts.
 *
 * <p>The verifier links neither Resource Gateway nor Spring. It checks state models, write
 * effects, and payload-bearing session snapshots directly from JSON, making it suitable for TEE
 * ingress, ANEKE workbook import, deployment probes, and non-server compatibility tests.</p>
 */
public final class MirrorStateProtocolVerifier {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAXIMUM_EXPRESSION_DEPTH = 32;
    private static final int MAXIMUM_EXPRESSION_NODES = 1024;

    /** Creates one stateless verifier backed only by packaged protocol Schemas. */
    public MirrorStateProtocolVerifier() {
    }

    /**
     * Verifies one sealed state model.
     *
     * @param value state-model JSON
     * @return payload-free verified identity
     */
    public VerifiedStateModel verifyStateModel(JsonNode value) {
        CapabilityMirrorSchemaValidator.require(
                value,
                CapabilityMirrorProtocol.STATE_MODEL_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.STATE_MODEL_SCHEMA_INVALID");
        requireFingerprint(value, "fingerprint",
                "RG.MIRROR.CLIENT.STATE_MODEL_FINGERPRINT_MISMATCH");
        if (!value.at("/scope/tenantId").asText()
                .equals(value.at("/provenance/tenantId").asText())) {
            throw invalid("RG.MIRROR.CLIENT.STATE_MODEL_SCOPE_MISMATCH");
        }
        Set<String> entityTypes = new HashSet<>();
        Set<String> businessKeys = new HashSet<>();
        for (JsonNode entity : value.path("entityTypes")) {
            if (!entityTypes.add(entity.path("entityType").asText())) {
                throw invalid("RG.MIRROR.CLIENT.STATE_MODEL_ENTITY_DUPLICATE");
            }
            verifyEntitySchema(entity);
            for (JsonNode key : entity.path("businessKeys")) {
                if (!businessKeys.add(key.path("name").asText())) {
                    throw invalid("RG.MIRROR.CLIENT.STATE_MODEL_BUSINESS_KEY_DUPLICATE");
                }
            }
        }
        Set<String> invariants = new HashSet<>();
        for (JsonNode invariant : value.path("invariants")) {
            if (!invariants.add(invariant.path("invariantId").asText())) {
                throw invalid("RG.MIRROR.CLIENT.STATE_MODEL_INVARIANT_DUPLICATE");
            }
            verifyExpression(invariant.path("predicate"));
        }
        return new VerifiedStateModel(
                value.path("schemaVersion").asText(),
                value.path("stateModelId").asText(),
                value.path("revision").asLong(),
                value.path("fingerprint").asText(),
                value.at("/scope/tenantId").asText(),
                value.at("/scope/organizationId").asText(),
                entityTypes.size());
    }

    private static void verifyEntitySchema(JsonNode entity) {
        JsonNode schema = entity.at("/schema/schema");
        if (!"object".equals(schema.path("type").asText())
                || !schema.path("additionalProperties").isBoolean()
                || schema.path("additionalProperties").asBoolean()) {
            throw invalid("RG.MIRROR.CLIENT.STATE_MODEL_ENTITY_SCHEMA_UNSAFE");
        }
        for (JsonNode key : entity.path("businessKeys")) {
            for (JsonNode path : key.path("fieldPaths")) {
                JsonPointer pointer;
                try {
                    pointer = JsonPointer.compile(path.asText());
                } catch (IllegalArgumentException invalid) {
                    throw invalid("RG.MIRROR.CLIENT.STATE_MODEL_ENTITY_SCHEMA_UNSAFE");
                }
                JsonNode current = schema;
                while (!pointer.matches()) {
                    String property = pointer.getMatchingProperty();
                    if (property == null || !containsText(current.path("required"), property)) {
                        throw invalid(
                                "RG.MIRROR.CLIENT.STATE_MODEL_ENTITY_SCHEMA_UNSAFE");
                    }
                    current = current.path("properties").path(property);
                    if (!current.isObject()) {
                        throw invalid(
                                "RG.MIRROR.CLIENT.STATE_MODEL_ENTITY_SCHEMA_UNSAFE");
                    }
                    pointer = pointer.tail();
                }
                String type = current.path("type").asText();
                if (type.isBlank() || "object".equals(type) || "array".equals(type)
                        || "null".equals(type)) {
                    throw invalid("RG.MIRROR.CLIENT.STATE_MODEL_ENTITY_SCHEMA_UNSAFE");
                }
            }
        }
    }

    private static boolean containsText(JsonNode values, String expected) {
        for (JsonNode value : values) {
            if (expected.equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Verifies one sealed write effect against its exact state model.
     *
     * @param value write-effect JSON
     * @param stateModel exact state-model JSON
     * @return payload-free verified effect identity
     */
    public VerifiedWriteEffect verifyWriteEffect(JsonNode value, JsonNode stateModel) {
        VerifiedStateModel verifiedModel = verifyStateModel(stateModel);
        CapabilityMirrorSchemaValidator.require(
                value,
                CapabilityMirrorProtocol.WRITE_EFFECT_SPEC_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.WRITE_EFFECT_SCHEMA_INVALID");
        requireFingerprint(value, "fingerprint",
                "RG.MIRROR.CLIENT.WRITE_EFFECT_FINGERPRINT_MISMATCH");
        if (!value.path("scope").equals(stateModel.path("scope"))) {
            throw invalid("RG.MIRROR.CLIENT.WRITE_EFFECT_SCOPE_MISMATCH");
        }
        JsonNode modelRef = value.path("stateModelRef");
        if (!"STATE_MODEL".equals(modelRef.path("kind").asText())
                || !verifiedModel.stateModelId().equals(modelRef.path("id").asText())
                || verifiedModel.revision() != modelRef.path("revision").asLong()
                || !verifiedModel.fingerprint().equals(
                modelRef.path("fingerprint").asText())) {
            throw invalid("RG.MIRROR.CLIENT.WRITE_EFFECT_MODEL_MISMATCH");
        }
        Map<String, Map<String, Integer>> keysByEntity = new HashMap<>();
        for (JsonNode entity : stateModel.path("entityTypes")) {
            Map<String, Integer> keys = new HashMap<>();
            entity.path("businessKeys").forEach(key -> keys.put(
                    key.path("name").asText(), key.path("fieldPaths").size()));
            keysByEntity.put(entity.path("entityType").asText(), Map.copyOf(keys));
        }
        Set<String> mutations = new HashSet<>();
        Set<String> availableAliases = new HashSet<>();
        for (JsonNode mutation : value.path("mutations")) {
            if (!mutations.add(mutation.path("mutationId").asText())) {
                throw invalid("RG.MIRROR.CLIENT.WRITE_EFFECT_MUTATION_DUPLICATE");
            }
            Map<String, Integer> expectedKeys = keysByEntity.get(
                    mutation.path("entityType").asText());
            if (expectedKeys == null) {
                throw invalid("RG.MIRROR.CLIENT.WRITE_EFFECT_ENTITY_UNKNOWN");
            }
            String operation = mutation.path("operation").asText();
            Set<String> actualKeys = new HashSet<>();
            boolean keyArityMatches = true;
            for (JsonNode key : mutation.path("businessKeys")) {
                String name = key.path("name").asText();
                actualKeys.add(name);
                if (expectedKeys.getOrDefault(name, -1)
                        != key.path("components").size()) {
                    keyArityMatches = false;
                }
            }
            if ("DELETE".equals(operation) ? !actualKeys.isEmpty()
                    : !expectedKeys.keySet().equals(actualKeys) || !keyArityMatches) {
                throw invalid("RG.MIRROR.CLIENT.WRITE_EFFECT_BUSINESS_KEY_MISMATCH");
            }
            verifyExpression(mutation.path("identity"));
            verifyExpressionAliases(mutation.path("identity"), availableAliases);
            availableAliases.add(mutation.path("mutationId").asText());
            mutation.path("preconditions").forEach(precondition ->
                    verifyExpressionWithAliases(
                            precondition.path("predicate"), availableAliases));
            mutation.path("fieldEffects").forEach(field ->
                    verifyExpressionWithAliases(field.path("value"), availableAliases));
            mutation.path("businessKeys").forEach(key -> key.path("components")
                    .forEach(component ->
                            verifyExpressionWithAliases(component, availableAliases)));
        }
        verifyExpressionWithAliases(value.path("responseProjection"), availableAliases);
        stateModel.path("invariants").forEach(invariant ->
                verifyExpressionAliases(invariant.path("predicate"), availableAliases));
        return new VerifiedWriteEffect(
                value.path("schemaVersion").asText(),
                value.path("specId").asText(),
                value.path("revision").asLong(),
                value.path("fingerprint").asText(),
                verifiedModel.fingerprint(),
                mutations.size());
    }

    /**
     * Verifies a complete session snapshot and exact model/effect closure.
     *
     * @param value session-state JSON
     * @param stateModel exact state model
     * @param writeEffects exact admitted write effects
     * @return payload-free verified session identity
     */
    public VerifiedSession verifySession(
            JsonNode value, JsonNode stateModel, List<JsonNode> writeEffects) {
        VerifiedStateModel verifiedModel = verifyStateModel(stateModel);
        List<JsonNode> effects = writeEffects == null ? List.of() : List.copyOf(writeEffects);
        Map<String, VerifiedWriteEffect> verifiedEffects = new HashMap<>();
        for (JsonNode effect : effects) {
            VerifiedWriteEffect verified = verifyWriteEffect(effect, stateModel);
            verifiedEffects.put(referenceCoordinate(
                    "WRITE_EFFECT", verified.specId(), verified.revision(),
                    verified.fingerprint()), verified);
        }
        CapabilityMirrorSchemaValidator.require(
                value,
                CapabilityMirrorProtocol.SESSION_STATE_SPACE_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SESSION_STATE_SCHEMA_INVALID");
        JsonNode modelRef = value.path("stateModelRef");
        if (!referenceCoordinate(
                "STATE_MODEL", verifiedModel.stateModelId(), verifiedModel.revision(),
                verifiedModel.fingerprint()).equals(referenceCoordinate(modelRef))) {
            throw invalid("RG.MIRROR.CLIENT.SESSION_STATE_MODEL_MISMATCH");
        }
        if (!value.path("scope").equals(stateModel.path("scope"))) {
            throw invalid("RG.MIRROR.CLIENT.SESSION_SCOPE_MISMATCH");
        }
        Set<String> admittedEffects = new HashSet<>();
        for (JsonNode ref : value.path("writeEffectRefs")) {
            String coordinate = referenceCoordinate(ref);
            if (!verifiedEffects.containsKey(coordinate)
                    || !admittedEffects.add(coordinate)) {
                throw invalid("RG.MIRROR.CLIENT.SESSION_WRITE_EFFECT_CLOSURE_INVALID");
            }
        }
        if (!admittedEffects.equals(verifiedEffects.keySet())) {
            throw invalid("RG.MIRROR.CLIENT.SESSION_WRITE_EFFECT_CLOSURE_INVALID");
        }
        Set<String> entities = new HashSet<>();
        for (JsonNode entity : value.path("entities")) {
            requireFingerprint(entity, "fingerprint",
                    "RG.MIRROR.CLIENT.SESSION_ENTITY_FINGERPRINT_MISMATCH");
            if (!entities.add(entityCoordinate(entity.path("key")))) {
                throw invalid("RG.MIRROR.CLIENT.SESSION_ENTITY_DUPLICATE");
            }
        }
        Set<String> tombstones = new HashSet<>();
        for (JsonNode tombstone : value.path("tombstones")) {
            requireFingerprint(tombstone, "fingerprint",
                    "RG.MIRROR.CLIENT.SESSION_TOMBSTONE_FINGERPRINT_MISMATCH");
            String coordinate = entityCoordinate(tombstone.path("key"));
            if (!tombstones.add(coordinate) || entities.contains(coordinate)) {
                throw invalid("RG.MIRROR.CLIENT.SESSION_TOMBSTONE_CONFLICT");
            }
        }
        Set<String> businessKeys = new HashSet<>();
        for (JsonNode binding : value.path("businessKeyIndex")) {
            if (!EvidenceVerificationSupport.sha256(binding.path("components"))
                    .equals(binding.path("valueFingerprint").asText())) {
                throw invalid("RG.MIRROR.CLIENT.SESSION_BUSINESS_KEY_FINGERPRINT_MISMATCH");
            }
            String coordinate = binding.path("keyName").asText() + "\0"
                    + binding.path("valueFingerprint").asText();
            if (!businessKeys.add(coordinate)
                    || !entities.contains(entityCoordinate(binding.path("entityKey")))) {
                throw invalid("RG.MIRROR.CLIENT.SESSION_BUSINESS_KEY_INVALID");
            }
        }
        Map<String, Long> events = new HashMap<>();
        long previousEventRevision = 0;
        for (JsonNode event : value.path("committedEvents")) {
            requireFingerprint(event, "fingerprint",
                    "RG.MIRROR.CLIENT.SESSION_EVENT_FINGERPRINT_MISMATCH");
            long eventRevision = event.path("stateRevision").asLong();
            if (eventRevision < previousEventRevision
                    || eventRevision > value.path("stateRevision").asLong()
                    || events.put(event.path("eventId").asText(), eventRevision) != null) {
                throw invalid("RG.MIRROR.CLIENT.SESSION_EVENT_DUPLICATE");
            }
            previousEventRevision = eventRevision;
        }
        Set<String> commands = new HashSet<>();
        Set<String> coveredEvents = new HashSet<>();
        long expectedRevision = 1;
        for (JsonNode receipt : value.path("processedCommands")) {
            requireFingerprint(receipt, "fingerprint",
                    "RG.MIRROR.CLIENT.SESSION_RECEIPT_FINGERPRINT_MISMATCH");
            if (!EvidenceVerificationSupport.sha256(receipt.path("response"))
                    .equals(receipt.path("responseFingerprint").asText())) {
                throw invalid("RG.MIRROR.CLIENT.SESSION_RESPONSE_FINGERPRINT_MISMATCH");
            }
            if (!commands.add(receipt.path("idempotencyKey").asText())) {
                throw invalid("RG.MIRROR.CLIENT.SESSION_COMMAND_DUPLICATE");
            }
            if (receipt.path("revisionBefore").asLong() != expectedRevision - 1
                    || receipt.path("revisionAfter").asLong() != expectedRevision) {
                throw invalid("RG.MIRROR.CLIENT.SESSION_JOURNAL_CLOSURE_INVALID");
            }
            expectedRevision++;
            Set<String> receiptEvents = new HashSet<>();
            for (JsonNode eventId : receipt.path("eventIds")) {
                Long eventRevision = events.get(eventId.asText());
                if (eventRevision == null
                        || eventRevision != receipt.path("revisionAfter").asLong()
                        || !receiptEvents.add(eventId.asText())
                        || !coveredEvents.add(eventId.asText())) {
                    throw invalid("RG.MIRROR.CLIENT.SESSION_RECEIPT_EVENT_INVALID");
                }
            }
        }
        if (value.path("processedCommands").size() != value.path("stateRevision").asLong()
                || !coveredEvents.equals(events.keySet())) {
            throw invalid("RG.MIRROR.CLIENT.SESSION_JOURNAL_CLOSURE_INVALID");
        }
        ObjectNode world = worldMaterial(value);
        String worldFingerprint =
                EvidenceVerificationSupport.sha256Bounded(world, 256 * 1024 * 1024);
        if (!worldFingerprint.equals(value.path("worldFingerprint").asText())) {
            throw invalid("RG.MIRROR.CLIENT.SESSION_WORLD_FINGERPRINT_MISMATCH");
        }
        if (!value.path("processedCommands").isEmpty()
                && !worldFingerprint.equals(value.path("processedCommands").get(
                value.path("processedCommands").size() - 1)
                .path("resultingWorldFingerprint").asText())) {
            throw invalid("RG.MIRROR.CLIENT.SESSION_JOURNAL_CLOSURE_INVALID");
        }
        ObjectNode complete = value.deepCopy();
        complete.put("fingerprint", "");
        if (!EvidenceVerificationSupport.sha256Bounded(
                complete, 256 * 1024 * 1024)
                .equals(value.path("fingerprint").asText())) {
            throw invalid("RG.MIRROR.CLIENT.SESSION_FINGERPRINT_MISMATCH");
        }
        return new VerifiedSession(
                value.path("schemaVersion").asText(),
                value.path("sessionId").asText(),
                value.path("stateRevision").asLong(),
                value.path("worldFingerprint").asText(),
                value.path("fingerprint").asText(),
                value.path("entities").size(),
                value.path("processedCommands").size());
    }

    /**
     * Verifies one encrypted data-plane aggregate before create or after trusted decryption.
     *
     * <p>This method validates strict Schema, the complete model/effect/state closure, and the
     * aggregate fingerprint. It returns identities only; customer-shaped entities, command
     * inputs, and command responses are never copied into the result.</p>
     *
     * @param value complete session-payload JSON
     * @return payload-free verified aggregate identity
     */
    public VerifiedSessionPayload verifySessionPayload(JsonNode value) {
        CapabilityMirrorSchemaValidator.require(
                value,
                CapabilityMirrorProtocol.MIRROR_SESSION_PAYLOAD_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SESSION_PAYLOAD_SCHEMA_INVALID");
        List<JsonNode> writeEffects = new ArrayList<>();
        value.path("writeEffects").forEach(writeEffects::add);
        VerifiedStateModel stateModel = verifyStateModel(value.path("stateModel"));
        VerifiedSession session = verifySession(
                value.path("state"), value.path("stateModel"), writeEffects);
        requireFingerprint(value, "fingerprint",
                "RG.MIRROR.CLIENT.SESSION_PAYLOAD_FINGERPRINT_MISMATCH");
        return new VerifiedSessionPayload(
                value.path("schemaVersion").asText(),
                session.sessionId(),
                session.stateRevision(),
                value.path("fingerprint").asText(),
                stateModel.fingerprint(),
                writeEffects.size());
    }

    /**
     * Verifies one create command and its complete sealed aggregate.
     *
     * @param value session-create request JSON
     * @return payload-free verified command identity
     */
    public VerifiedSessionCreateRequest verifySessionCreateRequest(JsonNode value) {
        CapabilityMirrorSchemaValidator.require(
                value,
                CapabilityMirrorProtocol.MIRROR_SESSION_CREATE_REQUEST_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SESSION_CREATE_SCHEMA_INVALID");
        return new VerifiedSessionCreateRequest(
                value.path("schemaVersion").asText(),
                value.path("requestId").asText(),
                verifySessionPayload(value.path("payload")));
    }

    /**
     * Verifies one payload-free session descriptor, including lifecycle and time ordering.
     *
     * @param value descriptor JSON
     * @return verified payload-free descriptor identity
     */
    public VerifiedSessionDescriptor verifySessionDescriptor(JsonNode value) {
        CapabilityMirrorSchemaValidator.require(
                value,
                CapabilityMirrorProtocol.MIRROR_SESSION_DESCRIPTOR_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SESSION_DESCRIPTOR_SCHEMA_INVALID");
        requireFingerprint(value, "fingerprint",
                "RG.MIRROR.CLIENT.SESSION_DESCRIPTOR_FINGERPRINT_MISMATCH");
        JsonNode stateModelRef = value.path("stateModelRef");
        if (!"STATE_MODEL".equals(stateModelRef.path("kind").asText())) {
            throw invalid("RG.MIRROR.CLIENT.SESSION_DESCRIPTOR_CLOSURE_INVALID");
        }
        Set<String> admittedEffects = new HashSet<>();
        for (JsonNode ref : value.path("writeEffectRefs")) {
            if (!"WRITE_EFFECT".equals(ref.path("kind").asText())
                    || !admittedEffects.add(referenceCoordinate(ref))) {
                throw invalid("RG.MIRROR.CLIENT.SESSION_DESCRIPTOR_CLOSURE_INVALID");
            }
        }
        Instant createdAt = instant(
                value.path("createdAt"), "RG.MIRROR.CLIENT.SESSION_DESCRIPTOR_TIME_INVALID");
        Instant updatedAt = instant(
                value.path("updatedAt"), "RG.MIRROR.CLIENT.SESSION_DESCRIPTOR_TIME_INVALID");
        Instant expiresAt = instant(
                value.path("expiresAt"), "RG.MIRROR.CLIENT.SESSION_DESCRIPTOR_TIME_INVALID");
        JsonNode destroyedValue = value.path("destroyedAt");
        Instant destroyedAt = destroyedValue.isNull()
                ? null : instant(destroyedValue,
                "RG.MIRROR.CLIENT.SESSION_DESCRIPTOR_TIME_INVALID");
        String status = value.path("status").asText();
        if (updatedAt.isBefore(createdAt)
                || !expiresAt.isAfter(createdAt)
                || ("ACTIVE".equals(status) && destroyedAt != null)
                || (!"ACTIVE".equals(status) && destroyedAt == null)
                || (destroyedAt != null && destroyedAt.isBefore(createdAt))) {
            throw invalid("RG.MIRROR.CLIENT.SESSION_DESCRIPTOR_TIME_INVALID");
        }
        return new VerifiedSessionDescriptor(
                value.path("schemaVersion").asText(),
                value.path("sessionId").asText(),
                value.path("stateRevision").asLong(),
                status,
                value.path("worldFingerprint").asText(),
                value.path("stateFingerprint").asText(),
                value.path("fingerprint").asText(),
                referenceCoordinate(stateModelRef),
                Set.copyOf(admittedEffects),
                createdAt,
                updatedAt,
                expiresAt,
                destroyedAt);
    }

    /**
     * Verifies one state-transition command and exact write-effect reference.
     *
     * @param value command-request JSON
     * @return payload-free verified command coordinates
     */
    public VerifiedSessionCommandRequest verifySessionCommandRequest(JsonNode value) {
        CapabilityMirrorSchemaValidator.require(
                value,
                CapabilityMirrorProtocol.MIRROR_SESSION_COMMAND_REQUEST_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SESSION_COMMAND_SCHEMA_INVALID");
        JsonNode writeEffectRef = value.path("writeEffectRef");
        if (!"WRITE_EFFECT".equals(writeEffectRef.path("kind").asText())) {
            throw invalid("RG.MIRROR.CLIENT.SESSION_COMMAND_EFFECT_INVALID");
        }
        return new VerifiedSessionCommandRequest(
                value.path("schemaVersion").asText(),
                referenceCoordinate(writeEffectRef),
                value.path("expectedStateFingerprint").asText());
    }

    /**
     * Verifies one command against the effect closure advertised by a descriptor.
     *
     * @param value command-request JSON
     * @param descriptor payload-free descriptor JSON
     * @return verified command coordinates
     */
    public VerifiedSessionCommandRequest verifySessionCommandRequest(
            JsonNode value, JsonNode descriptor) {
        VerifiedSessionCommandRequest command = verifySessionCommandRequest(value);
        VerifiedSessionDescriptor session = verifySessionDescriptor(descriptor);
        if (!session.writeEffectCoordinates().contains(
                command.writeEffectCoordinate())) {
            throw invalid("RG.MIRROR.CLIENT.SESSION_COMMAND_EFFECT_INVALID");
        }
        return command;
    }

    /**
     * Verifies one newly committed or replayed command result.
     *
     * <p>The check covers descriptor integrity, receipt integrity, response fingerprinting,
     * monotonic revision semantics, and current-world closure without returning the receipt
     * response body.</p>
     *
     * @param value command-result JSON
     * @return payload-free verified result identity
     */
    public VerifiedSessionCommandResult verifySessionCommandResult(JsonNode value) {
        CapabilityMirrorSchemaValidator.require(
                value,
                CapabilityMirrorProtocol.MIRROR_SESSION_COMMAND_RESULT_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SESSION_COMMAND_RESULT_SCHEMA_INVALID");
        VerifiedSessionDescriptor descriptor =
                verifySessionDescriptor(value.path("descriptor"));
        JsonNode receipt = value.path("receipt");
        requireFingerprint(receipt, "fingerprint",
                "RG.MIRROR.CLIENT.SESSION_RECEIPT_FINGERPRINT_MISMATCH");
        if (!EvidenceVerificationSupport.sha256(receipt.path("response"))
                .equals(receipt.path("responseFingerprint").asText())) {
            throw invalid("RG.MIRROR.CLIENT.SESSION_RESPONSE_FINGERPRINT_MISMATCH");
        }
        long revisionBefore = receipt.path("revisionBefore").asLong();
        long revisionAfter = receipt.path("revisionAfter").asLong();
        boolean replayed = value.path("replayed").asBoolean();
        if (revisionAfter != revisionBefore + 1
                || (!replayed && revisionAfter != descriptor.stateRevision())
                || (replayed && revisionAfter > descriptor.stateRevision())
                || (revisionAfter == descriptor.stateRevision()
                && !receipt.path("resultingWorldFingerprint").asText()
                .equals(descriptor.worldFingerprint()))) {
            throw invalid("RG.MIRROR.CLIENT.SESSION_COMMAND_RESULT_CLOSURE_INVALID");
        }
        return new VerifiedSessionCommandResult(
                value.path("schemaVersion").asText(),
                descriptor,
                receipt.path("idempotencyKey").asText(),
                revisionBefore,
                revisionAfter,
                replayed,
                receipt.path("fingerprint").asText());
    }

    private void verifyExpression(JsonNode root) {
        ArrayDeque<ExpressionFrame> stack = new ArrayDeque<>();
        stack.push(new ExpressionFrame(root, 1));
        int nodes = 0;
        while (!stack.isEmpty()) {
            ExpressionFrame frame = stack.pop();
            if (++nodes > MAXIMUM_EXPRESSION_NODES
                    || frame.depth() > MAXIMUM_EXPRESSION_DEPTH) {
                throw invalid("RG.MIRROR.CLIENT.STATE_EXPRESSION_BOUNDS_EXCEEDED");
            }
            JsonNode value = frame.value();
            if (!value.isObject()) {
                throw invalid("RG.MIRROR.CLIENT.STATE_EXPRESSION_INVALID");
            }
            verifyExpressionShape(value);
            List<JsonNode> children = new ArrayList<>();
            value.path("arguments").forEach(children::add);
            value.path("fields").elements().forEachRemaining(children::add);
            for (int index = children.size() - 1; index >= 0; index--) {
                stack.push(new ExpressionFrame(children.get(index), frame.depth() + 1));
            }
        }
    }

    private void verifyExpressionWithAliases(
            JsonNode root, Set<String> availableAliases) {
        verifyExpression(root);
        verifyExpressionAliases(root, availableAliases);
    }

    private void verifyExpressionAliases(
            JsonNode root, Set<String> availableAliases) {
        ArrayDeque<JsonNode> remaining = new ArrayDeque<>();
        remaining.push(root);
        while (!remaining.isEmpty()) {
            JsonNode value = remaining.pop();
            if ("ENTITY_POINTER".equals(value.path("operator").asText())
                    && !availableAliases.contains(value.path("reference").asText())) {
                throw invalid("RG.MIRROR.CLIENT.STATE_EXPRESSION_ALIAS_INVALID");
            }
            value.path("arguments").forEach(remaining::push);
            value.path("fields").elements().forEachRemaining(remaining::push);
        }
    }

    private static void verifyExpressionShape(JsonNode value) {
        String operator = value.path("operator").asText();
        int arguments = value.path("arguments").size();
        int fields = value.path("fields").size();
        boolean path = !value.path("path").asText().isEmpty();
        boolean reference = !value.path("reference").asText().isEmpty();
        boolean literal = !value.path("literal").isNull();
        boolean valid = switch (operator) {
            case "LITERAL" -> !path && !reference && arguments == 0 && fields == 0;
            case "INPUT_POINTER" -> !reference && arguments == 0 && fields == 0;
            case "ENTITY_POINTER" -> reference && arguments == 0 && fields == 0;
            case "LOGICAL_TIME" -> !literal && !path && !reference
                    && arguments == 0 && fields == 0;
            case "DETERMINISTIC_ID", "SEQUENCE" -> !literal && !path && reference
                    && arguments == 0 && fields == 0;
            case "ADD", "CONCAT", "EQUALS", "GREATER_THAN_OR_EQUAL" ->
                    !literal && !path && !reference && arguments == 2 && fields == 0;
            case "NOT_NULL" -> !literal && !path && !reference
                    && arguments == 1 && fields == 0;
            case "AND" -> !literal && !path && !reference
                    && arguments >= 1 && arguments <= 64 && fields == 0;
            case "OBJECT" -> !literal && !path && !reference
                    && arguments == 0 && fields >= 1 && fields <= 128;
            default -> false;
        };
        if (!valid) {
            throw invalid("RG.MIRROR.CLIENT.STATE_EXPRESSION_INVALID");
        }
    }

    private static void requireFingerprint(
            JsonNode value, String field, String failureCode) {
        ObjectNode material = value.deepCopy();
        material.put(field, "");
        if (!EvidenceVerificationSupport.sha256(material)
                .equals(value.path(field).asText())) {
            throw invalid(failureCode);
        }
    }

    private static ObjectNode worldMaterial(JsonNode value) {
        ObjectNode world = JSON.createObjectNode();
        List.of(
                "schemaVersion",
                "sessionId",
                "scope",
                "planFingerprint",
                "stateModelRef",
                "writeEffectRefs",
                "stateRevision",
                "logicalClock",
                "randomSeed",
                "entities",
                "tombstones",
                "businessKeyIndex",
                "expiresAt").forEach(field ->
                world.set(field, value.path(field).deepCopy()));
        return world;
    }

    private static String referenceCoordinate(JsonNode reference) {
        return referenceCoordinate(
                reference.path("kind").asText(),
                reference.path("id").asText(),
                reference.path("revision").asLong(),
                reference.path("fingerprint").asText());
    }

    private static String referenceCoordinate(
            String kind, String id, long revision, String fingerprint) {
        return kind + "\0" + id + "\0" + revision + "\0" + fingerprint;
    }

    private static String entityCoordinate(JsonNode key) {
        return key.path("entityType").asText() + "\0" + key.path("entityId").asText();
    }

    private static Instant instant(JsonNode value, String failureCode) {
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException failure) {
            throw invalid(failureCode);
        }
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    private record ExpressionFrame(JsonNode value, int depth) {
    }

    /**
     * Payload-free identity of a verified state model.
     *
     * @param schemaVersion verified protocol version
     * @param stateModelId stable model id
     * @param revision exact revision
     * @param fingerprint verified content fingerprint
     * @param tenantId owning tenant
     * @param organizationId owning organization
     * @param entityTypeCount number of declared entity types
     */
    public record VerifiedStateModel(
            String schemaVersion,
            String stateModelId,
            long revision,
            String fingerprint,
            String tenantId,
            String organizationId,
            int entityTypeCount
    ) {
    }

    /**
     * Payload-free identity of a verified write effect.
     *
     * @param schemaVersion verified protocol version
     * @param specId stable effect id
     * @param revision exact revision
     * @param fingerprint verified content fingerprint
     * @param stateModelFingerprint exact model fingerprint
     * @param mutationCount atomic mutation count
     */
    public record VerifiedWriteEffect(
            String schemaVersion,
            String specId,
            long revision,
            String fingerprint,
            String stateModelFingerprint,
            int mutationCount
    ) {
    }

    /**
     * Payload-free identity and cardinality of a verified session snapshot.
     *
     * @param schemaVersion verified protocol version
     * @param sessionId isolated session identity
     * @param stateRevision committed revision
     * @param worldFingerprint verified current-world fingerprint
     * @param fingerprint verified complete state fingerprint
     * @param entityCount live entity count
     * @param processedCommandCount idempotent command count
     */
    public record VerifiedSession(
            String schemaVersion,
            String sessionId,
            long stateRevision,
            String worldFingerprint,
            String fingerprint,
            int entityCount,
            int processedCommandCount
    ) {
    }

    /**
     * Payload-free identity of a verified encrypted session aggregate.
     *
     * @param schemaVersion verified aggregate version
     * @param sessionId isolated session identity
     * @param stateRevision committed state revision
     * @param fingerprint verified aggregate fingerprint
     * @param stateModelFingerprint exact state-model fingerprint
     * @param writeEffectCount admitted write-effect count
     */
    public record VerifiedSessionPayload(
            String schemaVersion,
            String sessionId,
            long stateRevision,
            String fingerprint,
            String stateModelFingerprint,
            int writeEffectCount
    ) {
    }

    /**
     * Payload-free identity of a verified session-create request.
     *
     * @param schemaVersion verified command version
     * @param requestId caller-owned create idempotency key
     * @param payload verified aggregate identity
     */
    public record VerifiedSessionCreateRequest(
            String schemaVersion,
            String requestId,
            VerifiedSessionPayload payload
    ) {
    }

    /**
     * Payload-free identity and lifecycle facts from a verified descriptor.
     *
     * @param schemaVersion verified descriptor version
     * @param sessionId isolated session identity
     * @param stateRevision current committed revision
     * @param status lifecycle status
     * @param worldFingerprint current business-world fingerprint
     * @param stateFingerprint current state-and-journal fingerprint
     * @param fingerprint verified descriptor fingerprint
     * @param stateModelCoordinate exact state-model coordinate
     * @param writeEffectCoordinates exact admitted write-effect coordinates
     * @param createdAt durable creation time
     * @param updatedAt durable latest-transition time
     * @param expiresAt hard expiry
     * @param destroyedAt terminal transition time, otherwise {@code null}
     */
    public record VerifiedSessionDescriptor(
            String schemaVersion,
            String sessionId,
            long stateRevision,
            String status,
            String worldFingerprint,
            String stateFingerprint,
            String fingerprint,
            String stateModelCoordinate,
            Set<String> writeEffectCoordinates,
            Instant createdAt,
            Instant updatedAt,
            Instant expiresAt,
            Instant destroyedAt
    ) {
        /** Freezes the admitted-effect closure exposed to callers. */
        public VerifiedSessionDescriptor {
            writeEffectCoordinates = Set.copyOf(writeEffectCoordinates);
        }
    }

    /**
     * Payload-free coordinates from a verified session command.
     *
     * @param schemaVersion verified command version
     * @param writeEffectCoordinate exact requested write effect
     * @param expectedStateFingerprint optional optimistic state fence
     */
    public record VerifiedSessionCommandRequest(
            String schemaVersion,
            String writeEffectCoordinate,
            String expectedStateFingerprint
    ) {
    }

    /**
     * Payload-free identity of a verified command result.
     *
     * @param schemaVersion verified result version
     * @param descriptor verified current descriptor
     * @param idempotencyKey exact committed command key
     * @param revisionBefore previous revision
     * @param revisionAfter committed revision
     * @param replayed whether an existing receipt was replayed
     * @param receiptFingerprint verified receipt fingerprint
     */
    public record VerifiedSessionCommandResult(
            String schemaVersion,
            VerifiedSessionDescriptor descriptor,
            String idempotencyKey,
            long revisionBefore,
            long revisionAfter,
            boolean replayed,
            String receiptFingerprint
    ) {
    }
}
