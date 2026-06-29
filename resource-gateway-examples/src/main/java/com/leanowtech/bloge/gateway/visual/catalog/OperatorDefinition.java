package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Schema-aware visual operator definition exposed to the canvas.
 *
 * @param schemaVersion contract schema version
 * @param operatorRef stable operator reference
 * @param operatorVersion operator version
 * @param display display metadata
 * @param source implementation/source metadata
 * @param ports input/output ports
 * @param configSchema configuration schema
 * @param capabilities authoring and runtime capabilities
 * @param lowering lowering metadata used by code generation
 * @param diagnostics non-blocking projection diagnostics
 */
public record OperatorDefinition(
        String schemaVersion,
        String operatorRef,
        String operatorVersion,
        Display display,
        Source source,
        Ports ports,
        SchemaEnvelope configSchema,
        Capabilities capabilities,
        Lowering lowering,
        List<VisualDiagnostic> diagnostics
) {
    /**
     * Creates an operator definition.
     */
    public OperatorDefinition {
        operatorRef = operatorRef == null ? "" : operatorRef;
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? "bloge.visualOperator.v1"
                : schemaVersion;
        operatorVersion = operatorVersion == null || operatorVersion.isBlank() ? "1.0.0" : operatorVersion;
        display = display == null ? new Display(operatorRef, "", List.of()) : display;
        source = source == null ? Source.builtIn("bloge") : source;
        ports = ports == null ? new Ports(List.of(), List.of()) : ports;
        configSchema = configSchema == null ? SchemaEnvelope.opaque() : configSchema;
        capabilities = capabilities == null ? Capabilities.pure() : capabilities;
        lowering = lowering == null ? new Lowering("native", operatorRef, Map.of()) : lowering;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    /**
     * Display metadata.
     *
     * @param name display label
     * @param description description
     * @param tags search tags
     */
    public record Display(String name, String description, List<String> tags) {
        public Display {
            name = name == null || name.isBlank() ? "" : name;
            description = description == null ? "" : description;
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    /**
     * Source metadata for the operator.
     *
     * @param kind source kind
     * @param resourceId descriptor id for resource-backed virtual operators
     * @param method HTTP method when available
     * @param urlTemplate URL template when available
     * @param virtual whether the operator lowers into another executable operator
     */
    public record Source(
            String kind,
            String resourceId,
            String method,
            String urlTemplate,
            boolean virtual
    ) {
        public Source {
            kind = kind == null || kind.isBlank() ? "built-in" : kind;
            resourceId = resourceId == null ? "" : resourceId;
            method = method == null ? "" : method;
            urlTemplate = urlTemplate == null ? "" : urlTemplate;
        }

        public static Source builtIn(String kind) {
            return new Source(kind, "", "", "", false);
        }
    }

    /**
     * Input/output port collection.
     *
     * @param inputs input ports
     * @param outputs output ports
     */
    public record Ports(List<Port> inputs, List<Port> outputs) {
        public Ports {
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
            outputs = outputs == null ? List.of() : List.copyOf(outputs);
        }
    }

    /**
     * Operator port metadata.
     *
     * @param name port name
     * @param schema port schema
     * @param required whether this port is required
     * @param description port description
     */
    public record Port(String name, SchemaEnvelope schema, boolean required, String description) {
        public Port {
            name = name == null || name.isBlank() ? "default" : name;
            schema = schema == null ? SchemaEnvelope.opaque() : schema;
            description = description == null ? "" : description;
        }
    }

    /**
     * Runtime/authoring capabilities.
     *
     * @param effect pure/read/external effect label
     * @param idempotency idempotency label
     * @param streaming whether output can stream
     * @param requiresSecrets whether execution may require secrets
     */
    public record Capabilities(
            String effect,
            String idempotency,
            boolean streaming,
            boolean requiresSecrets
    ) {
        public Capabilities {
            effect = effect == null || effect.isBlank() ? "PURE" : effect;
            idempotency = idempotency == null || idempotency.isBlank() ? "UNKNOWN" : idempotency;
        }

        public static Capabilities pure() {
            return new Capabilities("PURE", "DETERMINISTIC", false, false);
        }
    }

    /**
     * Lowering metadata.
     *
     * @param mode lowering mode
     * @param operatorRef executable operator reference after lowering
     * @param parameters static lowering parameters
     */
    public record Lowering(
            String mode,
            String operatorRef,
            Map<String, Object> parameters
    ) {
        public Lowering {
            mode = mode == null || mode.isBlank() ? "native" : mode;
            operatorRef = operatorRef == null ? "" : operatorRef;
            parameters = parameters == null ? Map.of() : new LinkedHashMap<>(parameters);
        }
    }
}
