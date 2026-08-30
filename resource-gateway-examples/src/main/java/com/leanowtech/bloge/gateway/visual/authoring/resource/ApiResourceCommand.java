package com.leanowtech.bloge.gateway.visual.authoring.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.util.List;

/**
 * Validatable, connection-independent authoring content for one API Resource.
 * The command deliberately models the wire contract rather than a runtime
 * descriptor or a projection adapter.
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
        String description,
        Operation operation,
        Contract contract,
        Response response,
        Effect effect,
        List<Example> examples
) {

    /** HTTP operation and explicit input bindings. */
    public record Operation(String method, String path, List<Binding> bindings) {
        public Operation {
            bindings = bindings == null ? List.of() : List.copyOf(bindings);
        }
    }

    /** Maps one input JSON path to a transport location. */
    public record Binding(String from, Location to) {
    }

    /** Transport location for an input binding. */
    public record Location(String location, String name) {
    }

    /** Input and output JSON Schema envelopes. */
    public record Contract(SchemaEnvelope input, SchemaEnvelope output) {
    }

    /** Response success protocol and optional extracted output path. */
    public record Response(Success success, String outputPath) {
    }

    /** Supported response-success forms. */
    public sealed interface Success permits HttpStatus, BodyMatch {
    }

    /** Success when the response status is one of the listed codes. */
    public record HttpStatus(List<Integer> codes) implements Success {
        public HttpStatus {
            codes = codes == null ? List.of() : List.copyOf(codes);
        }
    }

    /** Success when a response body path equals one of the listed values. */
    public record BodyMatch(String path, List<JsonNode> values) implements Success {
        public BodyMatch {
            values = values == null ? List.<JsonNode>of() : values.stream()
                    .<JsonNode>map(value -> value == null ? null : value.deepCopy())
                    .toList();
        }
    }

    /** Side-effect policy permitted for this resource. */
    public enum Effect {
        READ_ONLY,
        FIXTURE_ONLY_WRITE,
        MANAGED_WRITE
    }

    /** One named request/response example. */
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

    public ApiResourceCommand {
        examples = examples == null ? List.of() : examples.stream()
                .map(example -> new Example(example.name(), example.input(), example.output()))
                .toList();
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
    public ApiResourceCommand withInputSchema(java.util.Map<String, Object> schema) {
        return withSchemas(schema, contract.output().schema());
    }

    /** @param input input schema @param output output schema @return command copy */
    public ApiResourceCommand withSchemas(java.util.Map<String, Object> input,
                                          java.util.Map<String, Object> output) {
        return new ApiResourceCommand(displayName, description, operation,
                new Contract(new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", input),
                        new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", output)),
                response, effect, examples);
    }
}
