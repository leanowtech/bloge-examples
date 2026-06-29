package com.leanowtech.bloge.gateway.visual.catalog;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Schema-aware visual operator definition exposed to the canvas.
 *
 * @param schemaVersion contract schema version
 * @param operatorRef stable operator reference
 * @param operatorVersion operator version
 * @param fingerprint stable hash of executable/schema-relevant operator metadata
 * @param display display metadata
 * @param source implementation/source metadata
 * @param ports input/output ports
 * @param configSchema configuration schema
 * @param capabilities authoring and runtime capabilities
 * @param policy tenant, namespace, and environment availability policy
 * @param lowering lowering metadata used by code generation
 * @param diagnostics non-blocking projection diagnostics
 */
public record OperatorDefinition(
        String schemaVersion,
        String operatorRef,
        String operatorVersion,
        String fingerprint,
        Display display,
        Source source,
        Ports ports,
        SchemaEnvelope configSchema,
        Capabilities capabilities,
        @JsonAlias("policies")
        Policy policy,
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
        policy = policy == null ? Policy.unrestricted() : policy;
        lowering = lowering == null ? new Lowering("native", operatorRef, Map.of()) : lowering;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        fingerprint = computeFingerprint(operatorRef, operatorVersion, source, ports, configSchema, capabilities, policy,
                lowering);
    }

    /**
     * Backward-compatible constructor for callers that supply a fingerprint but no policy.
     */
    public OperatorDefinition(String schemaVersion,
                              String operatorRef,
                              String operatorVersion,
                              String fingerprint,
                              Display display,
                              Source source,
                              Ports ports,
                              SchemaEnvelope configSchema,
                              Capabilities capabilities,
                              Lowering lowering,
                              List<VisualDiagnostic> diagnostics) {
        this(schemaVersion, operatorRef, operatorVersion, fingerprint, display, source, ports, configSchema,
                capabilities, Policy.unrestricted(), lowering, diagnostics);
    }

    /**
     * Backward-compatible constructor for callers that let the server compute the fingerprint.
     */
    public OperatorDefinition(String schemaVersion,
                              String operatorRef,
                              String operatorVersion,
                              Display display,
                              Source source,
                              Ports ports,
                              SchemaEnvelope configSchema,
                              Capabilities capabilities,
                              Lowering lowering,
                              List<VisualDiagnostic> diagnostics) {
        this(schemaVersion, operatorRef, operatorVersion, "", display, source, ports, configSchema,
                capabilities, Policy.unrestricted(), lowering, diagnostics);
    }

    /**
     * Creates an operator definition with an explicit availability policy.
     */
    public OperatorDefinition(String schemaVersion,
                              String operatorRef,
                              String operatorVersion,
                              Display display,
                              Source source,
                              Ports ports,
                              SchemaEnvelope configSchema,
                              Capabilities capabilities,
                              Policy policy,
                              Lowering lowering,
                              List<VisualDiagnostic> diagnostics) {
        this(schemaVersion, operatorRef, operatorVersion, "", display, source, ports, configSchema,
                capabilities, policy, lowering, diagnostics);
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
     * Availability policy for authoring and publishing.
     *
     * @param tenants allowed tenant ids; empty or "*" means any tenant
     * @param namespaces allowed namespaces; empty or "*" means any namespace
     * @param environments allowed environments; empty or "*" means any environment
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Policy(
            @JsonAlias("allowedTenants")
            List<String> tenants,
            @JsonAlias("allowedNamespaces")
            List<String> namespaces,
            @JsonAlias("allowedEnvironments")
            List<String> environments
    ) {
        public Policy {
            tenants = normalizeScope(tenants);
            namespaces = normalizeScope(namespaces);
            environments = normalizeScope(environments);
        }

        public static Policy unrestricted() {
            return new Policy(List.of(), List.of(), List.of());
        }

        public boolean allowsTenant(String tenantId) {
            return matches(tenants, tenantId);
        }

        public boolean allowsNamespace(String namespace) {
            return matches(namespaces, namespace);
        }

        public boolean allowsEnvironment(String environment) {
            return matches(environments, environment);
        }

        public boolean allows(String tenantId, String namespace, String environment) {
            return allowsTenant(tenantId)
                    && allowsNamespace(namespace)
                    && allowsEnvironment(environment);
        }

        public List<String> violations(String tenantId, String namespace, String environment) {
            List<String> violations = new java.util.ArrayList<>();
            if (!allowsTenant(tenantId)) {
                violations.add("tenant '%s' is not in %s".formatted(tenantId, tenants));
            }
            if (!allowsNamespace(namespace)) {
                violations.add("namespace '%s' is not in %s".formatted(namespace, namespaces));
            }
            if (!allowsEnvironment(environment)) {
                violations.add("environment '%s' is not in %s".formatted(environment, environments));
            }
            return violations;
        }

        private static boolean matches(List<String> allowed, String actual) {
            return allowed.isEmpty()
                    || allowed.contains("*")
                    || (actual != null && !actual.isBlank() && allowed.contains(actual));
        }

        private static List<String> normalizeScope(List<String> values) {
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            return values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .distinct()
                    .toList();
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

    private static String computeFingerprint(String operatorRef,
                                             String operatorVersion,
                                             Source source,
                                             Ports ports,
                                             SchemaEnvelope configSchema,
                                             Capabilities capabilities,
                                             Policy policy,
                                             Lowering lowering) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("operatorRef", operatorRef);
        material.put("operatorVersion", operatorVersion);
        material.put("source", source);
        material.put("ports", ports);
        material.put("configSchema", configSchema);
        material.put("capabilities", capabilities);
        material.put("policy", policy);
        material.put("lowering", lowering);
        byte[] digest = sha256(canonicalize(material).getBytes(StandardCharsets.UTF_8));
        return "sha256:" + HexFormat.of().formatHex(digest);
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String canonicalize(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String string) {
            return quote(string);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof SchemaEnvelope envelope) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("format", envelope.format());
            body.put("version", envelope.version());
            body.put("schema", envelope.schema());
            return canonicalize(body);
        }
        if (value instanceof Source source) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("kind", source.kind());
            body.put("resourceId", source.resourceId());
            body.put("method", source.method());
            body.put("urlTemplate", source.urlTemplate());
            body.put("virtual", source.virtual());
            return canonicalize(body);
        }
        if (value instanceof Ports ports) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("inputs", ports.inputs());
            body.put("outputs", ports.outputs());
            return canonicalize(body);
        }
        if (value instanceof Port port) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", port.name());
            body.put("schema", port.schema());
            body.put("required", port.required());
            return canonicalize(body);
        }
        if (value instanceof Capabilities capabilities) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("effect", capabilities.effect());
            body.put("idempotency", capabilities.idempotency());
            body.put("streaming", capabilities.streaming());
            body.put("requiresSecrets", capabilities.requiresSecrets());
            return canonicalize(body);
        }
        if (value instanceof Policy policy) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tenants", policy.tenants());
            body.put("namespaces", policy.namespaces());
            body.put("environments", policy.environments());
            return canonicalize(body);
        }
        if (value instanceof Lowering lowering) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("mode", lowering.mode());
            body.put("operatorRef", lowering.operatorRef());
            body.put("parameters", lowering.parameters());
            return canonicalize(body);
        }
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> sorted = new TreeMap<>();
            rawMap.forEach((key, item) -> sorted.put(String.valueOf(key), item));
            StringBuilder builder = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                if (!first) {
                    builder.append(",");
                }
                builder.append(quote(entry.getKey())).append(":").append(canonicalize(entry.getValue()));
                first = false;
            }
            return builder.append("}").toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder builder = new StringBuilder("[");
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    builder.append(",");
                }
                builder.append(canonicalize(item));
                first = false;
            }
            return builder.append("]").toString();
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            StringBuilder builder = new StringBuilder("[");
            for (int i = 0; i < length; i++) {
                if (i > 0) {
                    builder.append(",");
                }
                builder.append(canonicalize(java.lang.reflect.Array.get(value, i)));
            }
            return builder.append("]").toString();
        }
        return quote(String.valueOf(value));
    }

    private static String quote(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            switch (current) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> builder.append(current);
            }
        }
        return builder.append('"').toString();
    }
}
