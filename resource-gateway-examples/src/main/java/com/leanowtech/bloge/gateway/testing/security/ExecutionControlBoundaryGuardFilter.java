package com.leanowtech.bloge.gateway.testing.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRecord;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Rejects caller-driven test controls on ordinary production execution protocols.
 *
 * <p>The guard lives before request DTO deserialization, so Jackson unknown-property settings and
 * future DTO changes cannot accidentally turn a production run endpoint into a fixture injection
 * path. Rejected attempts are committed to the production integration security audit. If the audit
 * sink is unavailable, the guard fails closed with 503.</p>
 */
public final class ExecutionControlBoundaryGuardFilter extends OncePerRequestFilter {

    private static final int MAX_INSPECTABLE_BODY_BYTES = 2 * 1024 * 1024;
    private static final Set<String> CONTROL_FIELDS = Set.of(
            "controlplan", "requestedcontrols", "fixturebundle", "fixturebundleref",
            "fixture", "fixtures", "fixtureref", "fixturerefs",
            "executionpurpose", "testmode", "behavioroverrides", "mocks", "mockrules",
            "stub", "stubs", "stubref", "stubrefs",
            "bindingoverride", "bindingoverrides", "bindingoverrideref", "bindingoverriderefs",
            "dependencybehavior", "dependencybehaviors", "dependencybehaviorref",
            "dependencybehaviorrefs", "scenariodataset", "scenariodatasetref",
            "scenariodatasetrefs",
            "mirror", "mirrorplan", "mirrorplanid", "mirrorrequest",
            "replay", "replaypayload", "replaypayloads",
            "replacementrule", "replacementrules", "resolveroverrides",
            "scenariopack", "scenariopackref");
    private static final Set<String> EXACT_EXECUTION_PATHS = Set.of(
            "/api/gateway/resources/execute",
            "/api/gateway/examples/compose/run",
            "/api/visual/drafts/run");
    private static final Pattern STORED_DRAFT_RUN = Pattern.compile("/api/visual/drafts/[^/]+/run");
    private static final Pattern PUBLICATION_RUN = Pattern.compile("/api/visual/publications/[^/]+/run");

    private final ObjectMapper objectMapper;
    private final IntegrationAccessAuditRepository audit;

    public ExecutionControlBoundaryGuardFilter(ObjectMapper objectMapper,
                                               IntegrationAccessAuditRepository audit) {
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
        this.audit = java.util.Objects.requireNonNull(audit, "audit");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return !(EXACT_EXECUTION_PATHS.contains(path)
                || STORED_DRAFT_RUN.matcher(path).matches()
                || PUBLICATION_RUN.matcher(path).matches());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!json(request.getContentType())) {
            filterChain.doFilter(request, response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_INSPECTABLE_BODY_BYTES + 1);
        CachedBodyRequest cached = new CachedBodyRequest(request, body);
        if (body.length > MAX_INSPECTABLE_BODY_BYTES) {
            writeProblem(response, new IntegrationProblem("",
                    "urn:bloge:problem:production-run-request-too-large",
                    "Production run request exceeds the inspectable security boundary.",
                    413, "RG.PRODUCTION.REQUEST_BODY_TOO_LARGE", false,
                    header(request, "X-Correlation-Id"),
                    Map.of("maximumBytes", MAX_INSPECTABLE_BODY_BYTES)));
            return;
        }
        String field = controlledField(body);
        if (field == null) {
            filterChain.doFilter(cached, response);
            return;
        }
        String correlationId = header(request, "X-Correlation-Id");
        if (correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        try {
            audit.append(new IntegrationAccessAuditRecord(0, Instant.now(), correlationId,
                    "", "", "", "", "", "", "", "PUBLIC", 0, "",
                    header(request, "X-Tenant-Id"), environment(request),
                    "PRODUCTION_RUN_CONTROL_GUARD", "PRODUCTION", "DENIED",
                    "RG.PRODUCTION.CONTROL_FIELD_FORBIDDEN"));
        } catch (RuntimeException auditFailure) {
            writeProblem(response, IntegrationProblem.serviceUnavailable(
                    "RG.INTEGRATION.SECURITY_AUDIT_UNAVAILABLE",
                    "Production execution is unavailable because the security audit sink cannot commit.",
                    correlationId, Map.of()));
            return;
        }
        writeProblem(response, IntegrationProblem.badRequest(
                "RG.PRODUCTION.CONTROL_FIELD_FORBIDDEN",
                "Fixture, stub, binding override, dependency behavior, scenario dataset, mirror, "
                        + "replay, and replacement-control fields are forbidden on "
                        + "production run protocols.",
                correlationId, Map.of("field", field, "testingEndpoint", "/api/testing/executions")));
    }

    private String controlledField(byte[] body) {
        if (body.length == 0) {
            return null;
        }
        try {
            return find(objectMapper.readTree(body));
        } catch (IOException invalidJson) {
            return null;
        }
    }

    private static String find(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (CONTROL_FIELDS.contains(compact(field.getKey()))) {
                    return field.getKey();
                }
                String nested = find(field.getValue());
                if (nested != null) {
                    return nested;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                String nested = find(item);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private void writeProblem(HttpServletResponse response, IntegrationProblem problem) throws IOException {
        response.setStatus(problem.status());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    private static boolean json(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        try {
            return MediaType.valueOf(contentType).isCompatibleWith(MediaType.APPLICATION_JSON);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static String environment(HttpServletRequest request) {
        String environment = header(request, "X-Environment-Id");
        return environment.isBlank() ? header(request, "X-Namespace") : environment;
    }

    private static String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null ? "" : value.trim();
    }

    private static String compact(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body.clone();
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { }
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] target, int offset, int length) {
                    return input.read(target, offset, length);
                }
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(new java.io.InputStreamReader(
                    getInputStream(), getCharacterEncoding() == null
                    ? java.nio.charset.StandardCharsets.UTF_8
                    : java.nio.charset.Charset.forName(getCharacterEncoding())));
        }
    }
}
