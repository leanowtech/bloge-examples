package com.leanowtech.bloge.gateway.visual.catalog;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * @param runtimeReadiness server-derived request-response runtime readiness summary
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
        List<VisualDiagnostic> diagnostics,
        RuntimeReadiness runtimeReadiness
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
        source = source == null ? new Source("user-library", "", "", "", false) : source;
        ports = ports == null ? new Ports(List.of(), List.of()) : ports;
        configSchema = configSchema == null ? SchemaEnvelope.opaque() : configSchema;
        capabilities = capabilities == null ? Capabilities.pure() : capabilities;
        policy = policy == null ? Policy.unrestricted() : policy;
        lowering = lowering == null ? new Lowering("native", operatorRef, Map.of()) : lowering;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        fingerprint = computeFingerprint(operatorRef, operatorVersion, source, ports, configSchema, capabilities, policy,
                lowering);
        runtimeReadiness = RuntimeReadiness.derive(source, lowering, capabilities, diagnostics);
    }

    /**
     * Backward-compatible constructor for callers that let the server derive runtime readiness.
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
                              @JsonAlias("policies")
                              Policy policy,
                              Lowering lowering,
                              List<VisualDiagnostic> diagnostics) {
        this(schemaVersion, operatorRef, operatorVersion, fingerprint, display, source, ports, configSchema,
                capabilities, policy, lowering, diagnostics, null);
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
                capabilities, Policy.unrestricted(), lowering, diagnostics, null);
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
                capabilities, Policy.unrestricted(), lowering, diagnostics, null);
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
                capabilities, policy, lowering, diagnostics, null);
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
     * @param libraryId imported operator library owner id when available
     */
    public record Source(
            String kind,
            String resourceId,
            String method,
            String urlTemplate,
            boolean virtual,
            String libraryId
    ) {
        public Source(String kind,
                      String resourceId,
                      String method,
                      String urlTemplate,
                      boolean virtual) {
            this(kind, resourceId, method, urlTemplate, virtual, "");
        }

        public Source {
            kind = kind == null || kind.isBlank()
                    ? "built-in"
                    : kind.trim().toLowerCase(Locale.ROOT);
            resourceId = resourceId == null ? "" : resourceId;
            method = method == null ? "" : method;
            urlTemplate = urlTemplate == null ? "" : urlTemplate;
            libraryId = libraryId == null ? "" : libraryId.trim();
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
            inputs = nullableElementsCopy(inputs);
            outputs = nullableElementsCopy(outputs);
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
     * @param durable whether execution requires a durable/suspendable runtime
     * @param requiresSecrets whether execution may require secrets
     */
    public record Capabilities(
            String effect,
            String idempotency,
            boolean streaming,
            boolean durable,
            boolean requiresSecrets
    ) {
        public Capabilities {
            effect = normalizeLabel(effect, "PURE");
            idempotency = normalizeLabel(idempotency, "UNKNOWN");
        }

        public static Capabilities pure() {
            return new Capabilities("PURE", "DETERMINISTIC", false, false, false);
        }

        private static String normalizeLabel(String value, String fallback) {
            return value == null || value.isBlank()
                    ? fallback
                    : value.trim().toUpperCase(Locale.ROOT);
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
            mode = mode == null || mode.isBlank()
                    ? "native"
                    : mode.trim().toLowerCase(Locale.ROOT);
            operatorRef = operatorRef == null ? "" : operatorRef;
            parameters = parameters == null ? Map.of() : new LinkedHashMap<>(parameters);
        }
    }

    /**
     * Server-derived authoring and execution readiness for the current visual runtime.
     *
     * @param state stable machine-readable state
     * @param level UI severity: success/info/warning/error
     * @param executable whether request-response execution is available after catalog repair checks
     * @param artifactKinds artifact kinds that can be promoted from this operator surface
     * @param title short display title
     * @param summary human-readable readiness summary
     * @param details structured detail rows for browser and external control planes
     */
    public record RuntimeReadiness(
            String state,
            String level,
            boolean executable,
            List<String> artifactKinds,
            String title,
            String summary,
            List<ReadinessDetail> details
    ) {
        public RuntimeReadiness {
            state = state == null || state.isBlank()
                    ? "UNKNOWN"
                    : state.trim().toUpperCase(Locale.ROOT);
            level = level == null || level.isBlank()
                    ? "info"
                    : level.trim().toLowerCase(Locale.ROOT);
            artifactKinds = artifactKinds == null ? List.of() : artifactKinds.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.trim().toUpperCase(Locale.ROOT))
                    .distinct()
                    .toList();
            title = title == null ? "" : title;
            summary = summary == null ? "" : summary;
            details = nullableElementsCopy(details);
        }

        static RuntimeReadiness derive(Source source,
                                       Lowering lowering,
                                       Capabilities capabilities,
                                       List<VisualDiagnostic> diagnostics) {
            String sourceKind = normalizeFacetValue(source.kind());
            String loweringMode = normalizeFacetValue(lowering.mode());
            boolean streaming = capabilities.streaming() || "java-streaming-operator".equals(sourceKind);
            boolean durable = capabilities.durable() || "java-suspendable-operator".equals(sourceKind);
            boolean errors = diagnostics.stream().anyMatch(VisualDiagnostic::error);
            List<ReadinessDetail> details = new ArrayList<>();
            details.add(new ReadinessDetail("Authoring", errors
                    ? "Catalog diagnostics require repair"
                    : "Schema-constrained canvas ready"));
            details.add(new ReadinessDetail("Source", humanizeFacet(sourceKind.isBlank() ? "unknown" : sourceKind)));
            details.add(new ReadinessDetail("Lowering", humanizeFacet(loweringMode.isBlank() ? "native" : loweringMode)));
            if (errors) {
                return new RuntimeReadiness(
                        "CATALOG_REPAIR_REQUIRED",
                        "error",
                        false,
                        List.of(),
                        "Catalog repair required",
                        "The operator is visible for review, but catalog errors must be fixed before reliable authoring.",
                        details
                );
            }
            if ("design".equals(loweringMode)) {
                details.add(new ReadinessDetail("Publish", "DESIGN artifact only"));
                return new RuntimeReadiness(
                        "DESIGN_ONLY",
                        "info",
                        false,
                        List.of("DESIGN"),
                        "Design-only operator",
                        "Authorable as a schema contract; executable lowering is not bound yet.",
                        details
                );
            }
            if ("remote-worker".equals(sourceKind) || "remote-worker".equals(loweringMode)) {
                details.add(new ReadinessDetail("Execution", "Remote worker dispatch is not enabled"));
                details.add(new ReadinessDetail("Publish", "DESIGN artifact only until a worker runtime is bound"));
                return new RuntimeReadiness(
                        "RUNTIME_BLOCKED",
                        "warning",
                        false,
                        List.of("DESIGN"),
                        "Remote worker runtime blocked",
                        "The operator declares a remote worker binding, but this request-response visual runtime cannot dispatch worker jobs yet.",
                        details
                );
            }
            if ("ai-tool".equals(sourceKind) || "ai-tool".equals(loweringMode)) {
                details.add(new ReadinessDetail("Execution", "AI tool invocation runtime is not enabled"));
                details.add(new ReadinessDetail("Publish", "DESIGN artifact only until an AI tool runtime is bound"));
                return new RuntimeReadiness(
                        "RUNTIME_BLOCKED",
                        "warning",
                        false,
                        List.of("DESIGN"),
                        "AI tool runtime blocked",
                        "The operator declares an AI tool binding, but this request-response visual runtime cannot invoke AI tools yet.",
                        details
                );
            }
            if ("event-source".equals(sourceKind) || "event-source".equals(loweringMode)) {
                details.add(new ReadinessDetail("Execution", "External event source runtime is not enabled"));
                details.add(new ReadinessDetail("Publish", "DESIGN artifact only until an event runtime is bound"));
                return new RuntimeReadiness(
                        "RUNTIME_BLOCKED",
                        "warning",
                        false,
                        List.of("DESIGN"),
                        "Event source runtime blocked",
                        "The operator declares an external event source boundary, but this request-response visual runtime cannot subscribe to events yet.",
                        details
                );
            }
            if ("message-handler".equals(sourceKind) || "message-handler".equals(loweringMode)) {
                details.add(new ReadinessDetail("Execution", "Message handler runtime is not enabled"));
                details.add(new ReadinessDetail("Publish", "DESIGN artifact only until a message runtime is bound"));
                return new RuntimeReadiness(
                        "RUNTIME_BLOCKED",
                        "warning",
                        false,
                        List.of("DESIGN"),
                        "Message handler runtime blocked",
                        "The operator declares a message handling boundary, but this request-response visual runtime cannot consume message channels yet.",
                        details
                );
            }
            if ("webhook".equals(sourceKind) || "webhook".equals(loweringMode)) {
                details.add(new ReadinessDetail("Execution", "Webhook ingress runtime is not enabled"));
                details.add(new ReadinessDetail("Publish", "DESIGN artifact only until webhook ingress is bound"));
                return new RuntimeReadiness(
                        "RUNTIME_BLOCKED",
                        "warning",
                        false,
                        List.of("DESIGN"),
                        "Webhook runtime blocked",
                        "The operator declares a webhook ingress boundary, but this request-response visual runtime cannot expose inbound webhooks yet.",
                        details
                );
            }
            if (streaming || durable) {
                String blockers = String.join(" + ", List.of(
                                streaming ? "streaming runtime" : "",
                                durable ? "durable runtime" : ""
                        ).stream()
                        .filter(value -> !value.isBlank())
                        .toList());
                details.add(new ReadinessDetail("Execution",
                        blockers + " not supported by this request-response runtime"));
                return new RuntimeReadiness(
                        "RUNTIME_BLOCKED",
                        "warning",
                        false,
                        List.of(),
                        "Runtime blocked",
                        "The schema can be inspected, but this visual runtime cannot execute the required runtime mode.",
                        details
                );
            }
            List<String> governance = new ArrayList<>();
            if (capabilities.requiresSecrets()) {
                governance.add("secret binding");
            }
            if ("NON_IDEMPOTENT".equals(capabilities.idempotency())) {
                governance.add("non-idempotent side effect");
            }
            if (!"PURE".equals(capabilities.effect())) {
                governance.add("external effect");
            }
            if (!governance.isEmpty()) {
                details.add(new ReadinessDetail("Governance", String.join(" / ", governance)));
                return new RuntimeReadiness(
                        "GOVERNANCE_REVIEW",
                        "warning",
                        true,
                        List.of("EXECUTABLE"),
                        "Executable with governance review",
                        "Executable metadata is present; promotion should review runtime governance risks.",
                        details
                );
            }
            details.add(new ReadinessDetail("Execution", "Request-response executable"));
            return new RuntimeReadiness(
                    "RUNTIME_EXECUTABLE",
                    "success",
                    true,
                    List.of("EXECUTABLE"),
                    "Runtime executable",
                    "Executable lowering is present for this request-response visual runtime.",
                    details
            );
        }
    }

    /**
     * Runtime readiness detail row.
     *
     * @param label row label
     * @param value row value
     */
    public record ReadinessDetail(String label, String value) {
        public ReadinessDetail {
            label = label == null ? "" : label;
            value = value == null ? "" : value;
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
            // libraryId is catalog ownership metadata; excluding it avoids
            // behavior-drift fingerprints when the same definition is re-owned.
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
            body.put("durable", capabilities.durable());
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

    private static String normalizeFacetValue(String value) {
        return String.valueOf(value == null ? "" : value)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('_', '-');
    }

    private static String humanizeFacet(String value) {
        String normalized = normalizeFacetValue(value);
        if (normalized.isBlank()) {
            return "";
        }
        String[] words = normalized.split("-");
        List<String> titleWords = new ArrayList<>();
        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
            titleWords.add(word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1));
        }
        return String.join(" ", titleWords);
    }

    private static <T> List<T> nullableElementsCopy(List<T> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
