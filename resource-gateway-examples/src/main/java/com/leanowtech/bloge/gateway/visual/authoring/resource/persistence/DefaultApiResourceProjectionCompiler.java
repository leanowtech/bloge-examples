package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.resource.ParameterMapping;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResponseProtocol;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceDescriptor;
import com.leanowtech.bloge.gateway.visualadapter.ResourceRegistryVisualAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Compiles the authoritative {@link ApiResourceSpec} into the three durable
 * read models consumed by the gateway, visual authoring and operator catalog.
 *
 * <p>The compiler is deliberately stateless: it performs no storage, registry
 * mutation or catalog lookup.  A caller either receives all three READY
 * documents bound to the same exact subject, or receives an exception and can
 * discard the attempted stage.  This is the projection seam used by the
 * opt-in authoring runtime.</p>
 */
@Component
@ConditionalOnBean(ApiResourceConnectionProjectionResolver.class)
public final class DefaultApiResourceProjectionCompiler implements ApiResourceProjectionCompiler {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Pattern RESOURCE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    private static final Pattern INPUT_PATH = Pattern.compile("^\\$\\.[A-Za-z0-9_-]+$");
    private static final Pattern HEADER = Pattern.compile("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$");
    private static final Set<String> RESERVED_HEADERS = Set.of(
            "authorization", "proxy-authorization", "proxy-authenticate", "cookie", "set-cookie",
            "host", "content-length", "connection", "keep-alive", "te", "trailer",
            "transfer-encoding", "upgrade", "forwarded");
    private final ApiResourceConnectionProjectionResolver connections;

    /**
     * @param connections non-secret Connection metadata resolver
     */
    public DefaultApiResourceProjectionCompiler(ApiResourceConnectionProjectionResolver connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /**
     * Creates runtime, visual-contract and catalog-operator projections.
     *
     * @param scope authoring boundary; required to make the call site explicit
     * @param resource validated authoritative resource revision
     * @return three READY, exact-subject documents
     * @throws IllegalArgumentException when the input cannot be projected safely
     */
    @Override
    public ReadyApiResourceProjections compile(AuthoringScope scope, ApiResourceSpec resource) {
        Objects.requireNonNull(scope, "scope");
        validate(resource);
        ResourceDescriptor descriptor = descriptor(scope, resource);
        VisualResourceDescriptor visual = ResourceRegistryVisualAdapter.toVisual(descriptor);
        ResourceDesignContract contract = designContract(resource);
        OperatorDefinition operator = operator(resource, visual, contract);
        return new ReadyApiResourceProjections(
                document(ProjectionDocument.Kind.DESCRIPTOR, resource, descriptor),
                document(ProjectionDocument.Kind.DESIGN_CONTRACT, resource, contract),
                document(ProjectionDocument.Kind.OPERATOR, resource, operator));
    }

    private ResourceDescriptor descriptor(AuthoringScope scope, ApiResourceSpec resource) {
        ApiResourceCommand.Operation operation = resource.operation();
        ApiResourceConnectionProjectionResolver.ConnectionMetadata connection = connections
                .resolve(scope, resource.connectionId())
                .orElseThrow(() -> new IllegalArgumentException("connection projection is unavailable"));
        return new ResourceDescriptor(resource.resourceId(), join(connection.baseUrl(), operation.path()), operation.method(),
                connection.defaultHeaders(), null, connection.timeout(), mapping(operation.bindings()),
                response(resource.response()), resource.response().outputPath(),
                null);
    }

    private static String join(String baseUrl, String path) {
        return baseUrl.replaceAll("/+$", "") + "/" + path.replaceFirst("^/+", "");
    }

    private static ParameterMapping mapping(List<ApiResourceCommand.Binding> bindings) {
        Map<String, String> path = new LinkedHashMap<>();
        Map<String, String> query = new LinkedHashMap<>();
        Map<String, String> headers = new LinkedHashMap<>();
        String body = null;
        Set<String> targets = new HashSet<>();
        for (ApiResourceCommand.Binding binding : bindings) {
            String location = binding.to().location();
            String name = binding.to().name();
            if (!targets.add(location + "\u0000" + name)) {
                throw new IllegalArgumentException("duplicate binding target");
            }
            String expression = inputExpression(binding.from());
            switch (location) {
                case "PATH" -> path.put(name, expression);
                case "QUERY" -> query.put(name, expression);
                case "HEADER" -> headers.put(name, expression);
                case "BODY" -> body = expression;
                default -> throw new IllegalArgumentException("unsupported binding location: " + location);
            }
        }
        return new ParameterMapping(path, query, headers, Map.of(), body);
    }

    private static String inputExpression(String path) {
        if (!INPUT_PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("binding input path is not a first-level JSON path");
        }
        return "ctx.params." + path.substring(2);
    }

    private static ResponseProtocol response(ApiResourceCommand.Response response) {
        ApiResourceCommand.Success success = response.success();
        if (success instanceof ApiResourceCommand.HttpStatus http) {
            return new ResponseProtocol.StatusCodes(Set.copyOf(http.codes()));
        }
        if (success instanceof ApiResourceCommand.BodyMatch body) {
            Set<Object> values = body.values().stream().map(DefaultApiResourceProjectionCompiler::javaValue)
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            if (values.size() == 1 && Boolean.TRUE.equals(values.iterator().next())) {
                return new ResponseProtocol.BodyFlag(body.path().substring(2));
            }
            return new ResponseProtocol.BodyCode(body.path().substring(2), values, "");
        }
        throw new IllegalArgumentException("unsupported response success shape");
    }

    private static Object javaValue(JsonNode value) {
        return JSON.convertValue(value, Object.class);
    }

    private static ResourceDesignContract designContract(ApiResourceSpec resource) {
        Map<String, Object> examples = new LinkedHashMap<>();
        for (ApiResourceCommand.Example example : resource.examples()) {
            examples.put(example.name(), Map.of("input", example.input(), "output", example.output()));
        }
        return new ResourceDesignContract("contract:" + resource.resourceId(), resource.resourceId(),
                resource.displayName(), resource.description(), List.of("resource"),
                resource.contract().input(), resource.contract().output(), examples,
                ResourceDesignContract.STATUS_ACTIVE);
    }

    private static OperatorDefinition operator(ApiResourceSpec resource, VisualResourceDescriptor descriptor,
                                               ResourceDesignContract contract) {
        String effect = resource.effect() instanceof ApiResourceCommand.Effect.ReadOnly
                ? "READ_EXTERNAL" : "WRITE_EXTERNAL";
        OperatorDefinition.Capabilities capabilities = new OperatorDefinition.Capabilities(
                effect, idempotency(resource.operation().method()), false, false,
                false, sideEffectProtocol(descriptor));
        return new OperatorDefinition("bloge.visualOperator.v1", "resource:" + resource.resourceId(), "1.0.0",
                new OperatorDefinition.Display(contract.displayName(), contract.description(), contract.tags()),
                new OperatorDefinition.Source("resource-descriptor", resource.resourceId(),
                        resource.operation().method(), descriptor.urlTemplate(), true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("params", contract.requestSchema(), true,
                                "Parameters passed to the resource descriptor.")),
                        List.of(new OperatorDefinition.Port("payload", contract.responseSchema(), true,
                                "Payload extracted by the resource response protocol."))),
                configSchema(), capabilities, OperatorDefinition.Policy.unrestricted(),
                new OperatorDefinition.Lowering("resource-descriptor", "httpResource",
                        Map.of("resourceId", resource.resourceId(), "payloadPath",
                                resource.response().outputPath() == null ? "" : resource.response().outputPath(),
                                "method", resource.operation().method())), List.of());
    }

    private static OperatorDefinition.SideEffectProtocol sideEffectProtocol(VisualResourceDescriptor descriptor) {
        VisualResourceDescriptor.ExternalWriteContract write = descriptor.externalWriteContract();
        if (write == null || !write.conformant()) return null;
        return OperatorDefinition.SideEffectProtocol.journaled(write.reconcilerRef(),
                "params." + write.idempotencyKeyParam(), "params." + write.reconciliationLookupParam(),
                "response.headers." + write.receiptIdHeader());
    }

    private static String idempotency(String method) {
        return switch (method.toUpperCase(Locale.ROOT)) {
            case "GET", "HEAD", "PUT", "DELETE" -> "IDEMPOTENT";
            default -> "UNKNOWN";
        };
    }

    private static SchemaEnvelope configSchema() {
        return SchemaEnvelope.object(Map.of(
                "timeout", Map.of("type", "string"),
                "retryAttempts", Map.of("type", "integer"),
                "fallback", Map.of("type", "boolean")), List.of());
    }

    private static ProjectionDocument document(ProjectionDocument.Kind kind, ApiResourceSpec resource, Object value) {
        JsonNode body = JSON.valueToTree(value);
        return new ProjectionDocument(kind, resource.ref(), body, AuthoringFingerprints.of(body),
                ProjectionDocument.State.READY);
    }

    private static void validate(ApiResourceSpec resource) {
        if (resource == null || !RESOURCE_ID.matcher(String.valueOf(resource.resourceId())).matches()
                || resource.revision() < 1 || !String.valueOf(resource.fingerprint()).matches("sha256:[0-9a-f]{64}")
                || resource.connectionId() == null || resource.connectionId().isBlank()) {
            throw new IllegalArgumentException("resource identity is invalid");
        }
        ApiResourceCommand.Operation operation = resource.operation();
        ApiResourceCommand.Contract contract = resource.contract();
        ApiResourceCommand.Response response = resource.response();
        if (operation == null || contract == null || response == null || resource.effect() == null
                || operation.method() == null || operation.path() == null
                || !Set.of("GET", "POST", "PUT", "DELETE").contains(operation.method().toUpperCase(Locale.ROOT))
                || !operation.path().startsWith("/") || operation.path().contains("://")) {
            throw new IllegalArgumentException("resource projection input is invalid");
        }
        if (contract.input() == null || contract.output() == null || response.success() == null) {
            throw new IllegalArgumentException("resource contract is incomplete");
        }
        Set<String> inputNames = contract.input().properties().keySet();
        Set<String> targets = new HashSet<>();
        int bodies = 0;
        for (ApiResourceCommand.Binding binding : operation.bindings()) {
            if (binding == null || binding.from() == null || !INPUT_PATH.matcher(binding.from()).matches()
                    || !inputNames.contains(binding.from().substring(2)) || binding.to() == null
                    || binding.to().location() == null || binding.to().name() == null) {
                throw new IllegalArgumentException("resource binding is invalid");
            }
            String location = binding.to().location();
            String name = binding.to().name();
            if (!Set.of("PATH", "QUERY", "HEADER", "BODY").contains(location)
                    || !targets.add(location + "\u0000" + name) || name.isBlank()) {
                throw new IllegalArgumentException("resource binding target is invalid");
            }
            if ("BODY".equals(location) && ++bodies > 1) throw new IllegalArgumentException("multiple BODY bindings");
            if ("HEADER".equals(location) && (!HEADER.matcher(name).matches()
                    || RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT))
                    || name.toLowerCase(Locale.ROOT).startsWith("x-forwarded-"))) {
                throw new IllegalArgumentException("resource binding header is reserved");
            }
        }
        if (response.success() instanceof ApiResourceCommand.HttpStatus http
                && (http.codes() == null || http.codes().isEmpty())) {
            throw new IllegalArgumentException("HTTP status success codes are required");
        }
        if (response.success() instanceof ApiResourceCommand.BodyMatch body
                && (body.path() == null || !body.path().matches("^\\$\\.[A-Za-z0-9._~-]+$")
                || body.values() == null || body.values().isEmpty()
                || body.values().stream().anyMatch(Objects::isNull))) {
            throw new IllegalArgumentException("body match success is incomplete");
        }
        if (resource.effect() instanceof ApiResourceCommand.Effect.ManagedWrite) {
            throw new IllegalArgumentException(
                    "MANAGED_WRITE projection is unsupported until the runtime side-effect contract is lossless");
        }
    }
}
