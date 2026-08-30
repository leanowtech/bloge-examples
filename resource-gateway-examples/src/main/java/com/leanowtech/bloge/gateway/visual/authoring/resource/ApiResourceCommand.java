package com.leanowtech.bloge.gateway.visual.authoring.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validatable, connection-independent authoring content for one API Resource.
 * The command is an input value object; a committed {@link ApiResourceSpec}
 * is the flattened authority returned by the module.
 *
 * @param displayName human-readable resource name
 * @param description optional authoring description
 * @param operation HTTP operation and input bindings
 * @param contract first-level input and output contracts
 * @param response response success and output extraction contract
 * @param effect permitted external side-effect policy
 * @param examples named input/output examples
 */
public record ApiResourceCommand(
        String displayName,
        @JsonInclude(JsonInclude.Include.NON_NULL) String description,
        Operation operation,
        Contract contract,
        Response response,
        Effect effect,
        List<Example> examples
) {

    public ApiResourceCommand {
        operation = copyOperation(operation);
        contract = copyContract(contract);
        response = copyResponse(response);
        effect = copyEffect(effect);
        examples = copyExamples(examples);
    }

    /**
     * HTTP operation and explicit input bindings.
     * @param method supported HTTP method
     * @param path relative operation path
     * @param bindings input-to-transport mappings
     */
    public record Operation(String method, String path, List<Binding> bindings) {
        public Operation {
            bindings = bindings == null ? List.of() : List.copyOf(bindings);
        }
    }

    /**
     * Maps one input JSON path to a transport location.
     * @param from first-level input JSON path
     * @param to transport target
     */
    public record Binding(String from, Location to) {
    }

    /**
     * Transport location for an input binding.
     * @param location PATH, QUERY, HEADER, or BODY
     * @param name target name
     */
    public record Location(String location, String name) {
    }

    /**
     * Input and output JSON Schema envelopes.
     * @param input input JSON Schema envelope
     * @param output output JSON Schema envelope
     */
    public record Contract(SchemaEnvelope input, SchemaEnvelope output) {
        public Contract {
            input = copySchema(input);
            output = copySchema(output);
        }

        @Override
        public SchemaEnvelope input() {
            return copySchema(input);
        }

        @Override
        public SchemaEnvelope output() {
            return copySchema(output);
        }
    }

    /**
     * Response success protocol and optional extracted output path.
     * @param success response success matcher
     * @param outputPath optional response output path
     */
    public record Response(Success success, @JsonInclude(JsonInclude.Include.NON_NULL) String outputPath) {
        public Response {
            success = copySuccess(success);
        }

        @Override
        public Success success() {
            return copySuccess(success);
        }
    }

    /** Supported response-success forms, discriminated on the wire by {@code kind}. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = HttpStatus.class, name = "HTTP_STATUS"),
            @JsonSubTypes.Type(value = BodyMatch.class, name = "BODY_MATCH")
    })
    public sealed interface Success permits HttpStatus, BodyMatch {
    }

    /**
     * Success when the response status is one of the listed codes.
     * @param codes accepted HTTP status codes
     */
    public record HttpStatus(List<Integer> codes) implements Success {
        public HttpStatus {
            codes = codes == null ? List.of() : List.copyOf(codes);
        }
    }

    /**
     * Success when a response body path equals one of the listed values.
     * @param path response body JSON path
     * @param values accepted JSON values
     */
    public record BodyMatch(String path, List<JsonNode> values) implements Success {
        public BodyMatch {
            values = copyNodes(values);
        }

        @Override
        public List<JsonNode> values() {
            return copyNodes(values);
        }
    }

    /** Side-effect policy permitted for this resource, discriminated on the wire by {@code kind}. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Effect.ReadOnly.class, name = "READ_ONLY"),
            @JsonSubTypes.Type(value = Effect.FixtureOnlyWrite.class, name = "FIXTURE_ONLY_WRITE"),
            @JsonSubTypes.Type(value = Effect.ManagedWrite.class, name = "MANAGED_WRITE")
    })
    public sealed interface Effect permits Effect.ReadOnly, Effect.FixtureOnlyWrite, Effect.ManagedWrite {
        /** Compatibility constants for callers that only need marker effects. */
        Effect READ_ONLY = new ReadOnly();
        Effect FIXTURE_ONLY_WRITE = new FixtureOnlyWrite();

        /** @return a read-only effect value */
        static Effect readOnly() {
            return new ReadOnly();
        }

        /** @return a fixture-only write effect value */
        static Effect fixtureOnlyWrite() {
            return new FixtureOnlyWrite();
        }

        /**
         * @param idempotencyHeader safe transport header carrying the idempotency key
         * @param receipt response receipt extraction contract
         * @param reconciliation optional exact API Resource reconciliation contract
         * @return managed-write effect value
         */
        static Effect managedWrite(String idempotencyHeader, Receipt receipt, Reconciliation reconciliation) {
            return new ManagedWrite(idempotencyHeader, receipt, reconciliation);
        }

        /** Read-only external operation. */
        record ReadOnly() implements Effect {
        }

        /** Write operation that can only be exercised through fixtures. */
        record FixtureOnlyWrite() implements Effect {
        }

        /**
         * Write operation with an idempotency and receipt contract.
         * @param idempotencyHeader header used to carry the idempotency key
         * @param receipt required response receipt contract
         * @param reconciliation optional exact reconciliation target
         */
        record ManagedWrite(String idempotencyHeader, Receipt receipt,
                            @JsonInclude(JsonInclude.Include.NON_NULL) Reconciliation reconciliation)
                implements Effect {
            public ManagedWrite {
                receipt = copyReceipt(receipt);
                reconciliation = copyReconciliation(reconciliation);
            }

            @Override
            public Receipt receipt() {
                return copyReceipt(receipt);
            }

            @Override
            public Reconciliation reconciliation() {
                return copyReconciliation(reconciliation);
            }
        }

        /**
         * Receipt fields used to classify the managed write response.
         * @param idPath response path containing the provider receipt id
         * @param statusPath response path containing the provider status
         * @param succeededValues status values classified as success
         * @param failedValues status values classified as failure
         */
        record Receipt(String idPath, String statusPath, List<JsonNode> succeededValues,
                       List<JsonNode> failedValues) {
            public Receipt {
                succeededValues = copyNodes(succeededValues);
                failedValues = copyNodes(failedValues);
            }

            @Override
            public List<JsonNode> succeededValues() {
                return copyNodes(succeededValues);
            }

            @Override
            public List<JsonNode> failedValues() {
                return copyNodes(failedValues);
            }
        }

        /**
         * Optional reconciliation against an exact API Resource revision.
         * @param resource exact API Resource reference to reconcile
         * @param receiptIdInputPath input path receiving the receipt id
         */
        record Reconciliation(ApiResourceSpec.ResourceRef resource, String receiptIdInputPath) {
        }
    }

    /**
     * One named request/response example.
     * @param name unique example identifier
     * @param input request input object
     * @param output response output object
     */
    public record Example(String name, JsonNode input, JsonNode output) {
        public Example {
            input = input == null ? null : input.deepCopy();
            output = output == null ? null : output.deepCopy();
        }

        @Override
        public JsonNode input() {
            return input == null ? null : input.deepCopy();
        }

        @Override
        public JsonNode output() {
            return output == null ? null : output.deepCopy();
        }
    }

    /** @return an independent defensive copy of this command */
    public ApiResourceCommand copy() {
        return new ApiResourceCommand(displayName, description, operation, contract, response, effect, examples);
    }

    /** @param path replacement relative path @return command copy */
    public ApiResourceCommand withPath(String path) {
        return new ApiResourceCommand(displayName, description,
                new Operation(operation.method(), path, operation.bindings()), contract, response, effect, examples);
    }

    /** @param bindings replacement bindings @return command copy */
    public ApiResourceCommand withBindings(List<Binding> bindings) {
        return new ApiResourceCommand(displayName, description,
                new Operation(operation.method(), operation.path(), bindings), contract, response, effect, examples);
    }

    /** @param description replacement description @return command copy */
    public ApiResourceCommand withDescription(String description) {
        return new ApiResourceCommand(displayName, description, operation, contract, response, effect, examples);
    }

    /** @param effect replacement effect @return command copy */
    public ApiResourceCommand withEffect(Effect effect) {
        return new ApiResourceCommand(displayName, description, operation, contract, response, effect, examples);
    }

    /** @param method replacement HTTP method @param effect replacement effect @return command copy */
    public ApiResourceCommand withMethodEffect(String method, Effect effect) {
        return new ApiResourceCommand(displayName, description,
                new Operation(method, operation.path(), operation.bindings()), contract, response, effect, examples);
    }

    /** @param examples replacement examples @return command copy */
    public ApiResourceCommand withExamples(List<Example> examples) {
        return new ApiResourceCommand(displayName, description, operation, contract, response, effect, examples);
    }

    /** @param schema replacement input schema @return command copy */
    public ApiResourceCommand withInputSchema(Map<String, Object> schema) {
        return withSchemas(schema, contract.output().schema());
    }

    /** @param input input schema @param output output schema @return command copy */
    public ApiResourceCommand withSchemas(Map<String, Object> input, Map<String, Object> output) {
        return new ApiResourceCommand(displayName, description, operation,
                new Contract(new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", input),
                        new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", output)),
                response, effect, examples);
    }

    private static Operation copyOperation(Operation value) {
        return value == null ? null : new Operation(value.method(), value.path(), value.bindings());
    }

    private static Contract copyContract(Contract value) {
        return value == null ? null : new Contract(value.input(), value.output());
    }

    private static Response copyResponse(Response value) {
        return value == null ? null : new Response(value.success(), value.outputPath());
    }

    private static Success copySuccess(Success value) {
        if (value instanceof HttpStatus status) return new HttpStatus(status.codes());
        if (value instanceof BodyMatch bodyMatch) return new BodyMatch(bodyMatch.path(), bodyMatch.values());
        return value;
    }

    private static Effect copyEffect(Effect value) {
        if (value instanceof Effect.ReadOnly) return new Effect.ReadOnly();
        if (value instanceof Effect.FixtureOnlyWrite) return new Effect.FixtureOnlyWrite();
        if (value instanceof Effect.ManagedWrite managed) {
            return new Effect.ManagedWrite(managed.idempotencyHeader(), managed.receipt(), managed.reconciliation());
        }
        return value;
    }

    private static Effect.Receipt copyReceipt(Effect.Receipt value) {
        return value == null ? null : new Effect.Receipt(value.idPath(), value.statusPath(),
                value.succeededValues(), value.failedValues());
    }

    private static Effect.Reconciliation copyReconciliation(Effect.Reconciliation value) {
        return value == null ? null : new Effect.Reconciliation(value.resource(), value.receiptIdInputPath());
    }

    private static List<Example> copyExamples(List<Example> values) {
        if (values == null) return List.<Example>of();
        return values.stream().map(value -> value == null ? null
                : new Example(value.name(), value.input(), value.output())).toList();
    }

    private static List<JsonNode> copyNodes(List<JsonNode> values) {
        if (values == null) return List.<JsonNode>of();
        return values.stream().<JsonNode>map(value -> value == null ? null : value.deepCopy()).toList();
    }

    private static SchemaEnvelope copySchema(SchemaEnvelope value) {
        if (value == null) return null;
        return new SchemaEnvelope(value.format(), value.version(), copyMap(value.schema()));
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source != null) source.forEach((key, value) -> copy.put(key, copyValue(value)));
        return copy;
    }

    private static Object copyValue(Object value) {
        if (value instanceof JsonNode node) return node.deepCopy();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), copyValue(item)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(copyValue(item)));
            return copy;
        }
        return value;
    }
}
