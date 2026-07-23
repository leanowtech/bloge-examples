package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class StatefulMirrorProtocolTest {

    private static final String SHA_ZERO = "sha256:" + "0".repeat(64);
    private static final Instant NOW = Instant.parse("2026-07-24T02:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void sealsAndVerifiesARefundStateModelAndAtomicWriteEffect() {
        StateModel model = StateModelIntegrity.seal(mapper, stateModel());
        WriteEffectSpec effect = WriteEffectSpecIntegrity.seal(mapper, refundEffect(model));

        StateModelIntegrity.verify(mapper, model);
        WriteEffectSpecIntegrity.verify(mapper, effect, model);

        assertThat(model.fingerprint()).startsWith("sha256:");
        assertThat(effect.fingerprint()).startsWith("sha256:");
        assertThat(effect.mutations()).extracting(WriteEffectSpec.Mutation::mutationId)
                .containsExactly("create-refund", "update-order");
    }

    @Test
    void rejectsOpenEntitySchemasAndUndeclaredBusinessKeyPaths() {
        StateModel source = stateModel();
        StateModel open = new StateModel(
                source.schemaVersion(),
                source.stateModelId(),
                source.revision(),
                "",
                source.scope(),
                List.of(new StateModel.EntityType(
                        "open-order",
                        SchemaEnvelope.opaque(),
                        List.of(new StateModel.BusinessKeyDefinition(
                                "open-order-id", List.of("/orderId"))))),
                List.of(),
                source.provenance(),
                source.lifecycle(),
                source.createdAt());

        assertThatThrownBy(() -> StateModelIntegrity.seal(mapper, open))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("closed object");

        StateModel.EntityType order = source.entityTypes().stream()
                .filter(type -> "order".equals(type.entityType()))
                .findFirst()
                .orElseThrow();
        StateModel invalidKey = new StateModel(
                source.schemaVersion(),
                source.stateModelId(),
                source.revision(),
                "",
                source.scope(),
                List.of(new StateModel.EntityType(
                        order.entityType(),
                        order.schema(),
                        List.of(new StateModel.BusinessKeyDefinition(
                                "missing-key", List.of("/missing"))))),
                List.of(),
                source.provenance(),
                source.lifecycle(),
                source.createdAt());

        assertThatThrownBy(() -> StateModelIntegrity.seal(mapper, invalidKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("business-key path");
    }

    @Test
    void rejectsTamperingCrossScopeReferencesAndUnboundedExpressions() {
        StateModel model = StateModelIntegrity.seal(mapper, stateModel());
        StateModel tampered = model.withFingerprint(SHA_ZERO);

        assertThatThrownBy(() -> StateModelIntegrity.verify(mapper, tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("state model fingerprint mismatch");

        CapabilitySnapshot.Scope otherScope = new CapabilitySnapshot.Scope(
                "tenant-a", "org-b", "tool-studio", "test", "sg");
        WriteEffectSpec crossScope = WriteEffectSpecIntegrity.seal(
                mapper, refundEffect(model).withScope(otherScope));
        assertThatThrownBy(() -> WriteEffectSpecIntegrity.verify(mapper, crossScope, model))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope");

        BoundedStateExpression expression = BoundedStateExpression.input("/amount");
        for (int depth = 0; depth < BoundedStateExpression.MAXIMUM_DEPTH + 1; depth++) {
            expression = BoundedStateExpression.notNull(expression);
        }
        BoundedStateExpression tooDeep = expression;
        assertThatThrownBy(() -> BoundedStateExpression.validate(tooDeep))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("depth");
    }

    @Test
    void rejectsNestedStateTamperingAndUnknownMutationAliases() {
        StateModel model = StateModelIntegrity.seal(mapper, stateModel());
        WriteEffectSpec effect = WriteEffectSpecIntegrity.seal(mapper, refundEffect(model));
        SessionStateSpace state = initialState(mapper, model, effect);
        SessionStateSpace.EntitySnapshot original = state.entities().getFirst();
        SessionStateSpace.EntitySnapshot tamperedEntity =
                original.withFingerprint(SHA_ZERO);
        SessionStateSpace tamperedState = new SessionStateSpace(
                state.schemaVersion(),
                state.sessionId(),
                state.scope(),
                state.planFingerprint(),
                state.stateModelRef(),
                state.writeEffectRefs(),
                state.stateRevision(),
                state.logicalClock(),
                state.randomSeed(),
                List.of(tamperedEntity),
                state.tombstones(),
                state.businessKeyIndex(),
                state.committedEvents(),
                state.processedCommands(),
                state.expiresAt(),
                state.worldFingerprint(),
                state.fingerprint());

        assertThatThrownBy(() -> SessionStateSpaceIntegrity.verify(mapper, tamperedState))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("session entity fingerprint mismatch");

        WriteEffectSpec unknownAlias = new WriteEffectSpec(
                effect.schemaVersion(),
                effect.specId(),
                effect.revision(),
                "",
                effect.scope(),
                effect.targetCapabilityRef(),
                effect.stateModelRef(),
                effect.mutations(),
                BoundedStateExpression.entity("missing-alias", "/status"),
                effect.idempotency(),
                effect.provenance(),
                effect.lifecycle(),
                effect.createdAt());
        WriteEffectSpec sealedUnknownAlias =
                WriteEffectSpecIntegrity.seal(mapper, unknownAlias);
        assertThatThrownBy(() ->
                WriteEffectSpecIntegrity.verify(mapper, sealedUnknownAlias, model))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mutation alias");
    }

    @Test
    void canonicalRefundCompatibilityFixtureDoesNotDrift() throws Exception {
        StateModel model = StateModelIntegrity.seal(mapper, stateModel());
        WriteEffectSpec effect = WriteEffectSpecIntegrity.seal(mapper, refundEffect(model));
        SessionStateSpace initial = initialState(mapper, model, effect);
        ObjectNode fixture = mapper.createObjectNode();
        fixture.put("schemaVersion", "resourceGateway.statefulRefundFixture.v1");
        fixture.set("stateModel", mapper.valueToTree(model));
        fixture.set("writeEffect", mapper.valueToTree(effect));
        fixture.set("initialState", mapper.valueToTree(initial));
        ObjectNode command = fixture.putArray("commands").addObject();
        command.set("input", mapper.valueToTree(Map.of(
                "requestId", "REQ-1",
                "orderId", "O-100",
                "amount", 450)));
        command.set("expectedResponse", mapper.valueToTree(Map.of(
                "refundId", "R-1",
                "orderId", "O-100",
                "status", "CREATED")));
        command.put("expectedStateRevision", 1);
        command.put("expectedEntityCount", 2);

        ObjectNode stored = (ObjectNode) mapper.readTree(Files.readString(Path.of(
                "..", "docs", "schemas", "resource-gateway-mirror",
                "stateful-refund-stage3-v1.fixture.json")));
        assertThat(stored).isEqualTo(mapper.readTree(mapper.writeValueAsBytes(fixture)));
    }

    public static StateModel stateModel() {
        CapabilitySnapshot.Scope scope = scope();
        return new StateModel(
                StateModel.SCHEMA_VERSION,
                "refund-world",
                1,
                "",
                scope,
                List.of(
                        new StateModel.EntityType(
                                "order",
                                objectSchema(Map.of(
                                        "orderId", Map.of("type", "string"),
                                        "paidAmount", Map.of("type", "number"),
                                        "refundedAmount", Map.of("type", "number")),
                                        List.of("orderId", "paidAmount", "refundedAmount")),
                                List.of(new StateModel.BusinessKeyDefinition(
                                        "order-id", List.of("/orderId")))),
                        new StateModel.EntityType(
                                "refund",
                                objectSchema(Map.of(
                                        "refundId", Map.of("type", "string"),
                                        "orderId", Map.of("type", "string"),
                                        "amount", Map.of("type", "number"),
                                        "status", Map.of("type", "string"),
                                        "createdAt", Map.of("type", "string")),
                                        List.of("refundId", "orderId", "amount", "status", "createdAt")),
                                List.of(
                                        new StateModel.BusinessKeyDefinition(
                                                "refund-id", List.of("/refundId")),
                                        new StateModel.BusinessKeyDefinition(
                                                "refund-request", List.of("/orderId", "/refundId"))))),
                List.of(),
                ownerProvenance(),
                CapabilitySnapshot.Lifecycle.ACTIVE,
                NOW);
    }

    public static WriteEffectSpec refundEffect(StateModel model) {
        MirrorArtifactRef modelRef = StateModelIntegrity.reference(
                StateModelIntegrity.seal(new ObjectMapper().findAndRegisterModules()
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS), model));
        BoundedStateExpression refundId = BoundedStateExpression.concat(
                BoundedStateExpression.literal("R-"),
                BoundedStateExpression.sequence("refund"));
        WriteEffectSpec.Mutation createRefund = new WriteEffectSpec.Mutation(
                "create-refund",
                WriteEffectSpec.Operation.CREATE,
                "refund",
                refundId,
                null,
                List.of(),
                List.of(
                        new WriteEffectSpec.FieldEffect(
                                "/refundId", refundId),
                        new WriteEffectSpec.FieldEffect(
                                "/orderId", BoundedStateExpression.input("/orderId")),
                        new WriteEffectSpec.FieldEffect(
                                "/amount", BoundedStateExpression.input("/amount")),
                        new WriteEffectSpec.FieldEffect(
                                "/status", BoundedStateExpression.literal("CREATED")),
                        new WriteEffectSpec.FieldEffect(
                                "/createdAt", BoundedStateExpression.logicalTime())),
                List.of(
                        new WriteEffectSpec.BusinessKeyRule(
                                "refund-id", List.of(BoundedStateExpression.entity(
                                "create-refund", "/refundId"))),
                        new WriteEffectSpec.BusinessKeyRule(
                                "refund-request", List.of(
                                BoundedStateExpression.entity("create-refund", "/orderId"),
                                BoundedStateExpression.entity("create-refund", "/refundId")))));
        WriteEffectSpec.Mutation updateOrder = new WriteEffectSpec.Mutation(
                "update-order",
                WriteEffectSpec.Operation.UPDATE,
                "order",
                BoundedStateExpression.input("/orderId"),
                capabilityRef("query-order"),
                List.of(new WriteEffectSpec.Precondition(
                        "refund-does-not-exceed-paid",
                        BoundedStateExpression.greaterThanOrEqual(
                                BoundedStateExpression.entity("update-order", "/paidAmount"),
                                BoundedStateExpression.add(
                                        BoundedStateExpression.entity(
                                                "update-order", "/refundedAmount"),
                                        BoundedStateExpression.input("/amount"))),
                        "REFUND_EXCEEDS_PAID_AMOUNT")),
                List.of(new WriteEffectSpec.FieldEffect(
                        "/refundedAmount",
                        BoundedStateExpression.add(
                                BoundedStateExpression.entity(
                                        "update-order", "/refundedAmount"),
                                BoundedStateExpression.input("/amount")))),
                List.of(new WriteEffectSpec.BusinessKeyRule(
                        "order-id", List.of(BoundedStateExpression.entity(
                        "update-order", "/orderId")))));
        return new WriteEffectSpec(
                WriteEffectSpec.SCHEMA_VERSION,
                "create-refund",
                1,
                "",
                scope(),
                capabilityRef("create-refund"),
                modelRef,
                List.of(createRefund, updateOrder),
                BoundedStateExpression.object(Map.of(
                        "refundId", BoundedStateExpression.entity(
                                "create-refund", "/refundId"),
                        "orderId", BoundedStateExpression.entity(
                                "create-refund", "/orderId"),
                        "status", BoundedStateExpression.entity(
                                "create-refund", "/status"))),
                new WriteEffectSpec.Idempotency("/requestId", true),
                ownerProvenance(),
                CapabilitySnapshot.Lifecycle.ACTIVE,
                NOW);
    }

    public static CapabilitySnapshot.Scope scope() {
        return new CapabilitySnapshot.Scope(
                "tenant-a", "org-a", "tool-studio", "test", "sg");
    }

    public static ArtifactProvenance ownerProvenance() {
        return new ArtifactProvenance(
                ArtifactProvenance.SCHEMA_VERSION,
                ArtifactProvenance.SourceType.OWNER,
                List.of(),
                "tenant-a",
                "customer-support-simulation",
                null,
                null,
                null,
                null,
                List.of(),
                "refund-owner",
                NOW,
                NOW.plusSeconds(86_400),
                "");
    }

    public static MirrorArtifactRef capabilityRef(String id) {
        return new MirrorArtifactRef("CAPABILITY", id, 1, SHA_ZERO);
    }

    public static SessionStateSpace initialState(
            ObjectMapper mapper, StateModel model, WriteEffectSpec effect) {
        SessionStateSpace.EntitySnapshot order = SessionStateSpaceIntegrity.sealEntity(
                mapper,
                new SessionStateSpace.EntitySnapshot(
                        new SessionStateSpace.EntityKey("order", "O-100"),
                        1,
                        Map.of(
                                "orderId", "O-100",
                                "paidAmount", 1000,
                                "refundedAmount", 0),
                        ""));
        SessionStateSpace.BusinessKeyBinding orderKey =
                SessionStateSpaceIntegrity.businessKey(
                        mapper,
                        "order-id",
                        List.of("O-100"),
                        order.key());
        return SessionStateSpaceIntegrity.seal(mapper, new SessionStateSpace(
                SessionStateSpace.SCHEMA_VERSION,
                "refund-session-1",
                scope(),
                "sha256:" + "2".repeat(64),
                StateModelIntegrity.reference(model),
                List.of(WriteEffectSpecIntegrity.reference(effect)),
                0,
                NOW,
                7L,
                List.of(order),
                List.of(),
                List.of(orderKey),
                List.of(),
                List.of(),
                NOW.plusSeconds(3_600),
                "",
                ""));
    }

    private static SchemaEnvelope objectSchema(
            Map<String, Object> properties, List<String> required) {
        return SchemaEnvelope.object(properties, required);
    }
}
