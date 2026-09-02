package com.leanowtech.bloge.gateway.visualadapter.authoring.simulation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationCommandV2;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationExecutionResult;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationExecutionResultV2;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationFailure;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationIdentity;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationModule;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationModuleV2;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationRequest;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationRun;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationRunV2;
import com.leanowtech.bloge.gateway.visualadapter.authoring.AuthoringRequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Thin authenticated HTTP adapter for synchronous immutable Simulation runs. */
@RestController
@RequestMapping("/api/authoring/simulations")
@ConditionalOnProperty(prefix = "gateway.authoring.api-resource", name = "enabled", havingValue = "true")
public final class ApiSimulationController {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}");
    private final SimulationModule module;
    private final SimulationModuleV2 moduleV2;
    private final IntegrationRequestAuthenticator authenticator;
    private final ObjectMapper strictMapper;

    /** Creates the adapter over the single deep Simulation module. */
    public ApiSimulationController(SimulationModule module, SimulationModuleV2 moduleV2,
                                   IntegrationRequestAuthenticator authenticator, ObjectMapper mapper) {
        this.module = Objects.requireNonNull(module, "module");
        this.moduleV2 = Objects.requireNonNull(moduleV2, "moduleV2");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.strictMapper = Objects.requireNonNull(mapper, "mapper").copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * Preserves v1-only embedded test hosts; v2 requests fail closed until the v2 module is supplied.
     */
    public ApiSimulationController(SimulationModule module, IntegrationRequestAuthenticator authenticator,
                                   ObjectMapper mapper) {
        this.module = Objects.requireNonNull(module, "module");
        this.moduleV2 = null;
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.strictMapper = Objects.requireNonNull(mapper, "mapper").copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** Runs or exactly replays one fixture-backed Simulation command. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> execute(@RequestHeader HttpHeaders headers,
                                     @RequestBody JsonNode wire,
                                     HttpServletRequest request) {
        IntegrationRequestContext context = authenticate(headers,
                IntegrationOperation.AUTHORING_SIMULATION_EXECUTE, request);
        AuthoringScope scope = trustedScope(context);
        String key = idempotencyKey(headers, context.correlationId());
        SimulationIdentity identity = identity(scope, context);
        String schemaVersion = wire.path("schemaVersion").asText();
        if (SimulationCommandV2.SCHEMA_VERSION.equals(schemaVersion)) {
            if (moduleV2 == null) throw new SimulationFailure(SimulationFailure.Code.UNSUPPORTED);
            SimulationExecutionResultV2 result = moduleV2.execute(
                    scope, key, commandV2(wire, context.correlationId()), identity);
            return response(result.run().runId())
                    .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                    .body(result.run());
        }
        if (!SimulationRequest.SCHEMA_VERSION.equals(schemaVersion)) throw invalidRequest(context.correlationId());
        SimulationExecutionResult result = module.execute(
                scope, key, command(wire, context.correlationId()), identity);
        return response(result.run().runId())
                .header("Idempotency-Replayed", Boolean.toString(result.replayed())).body(result.run());
    }

    /** Authenticates before rejecting an unsupported content type. */
    @PostMapping(consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> unsupportedMedia(@RequestHeader HttpHeaders headers,
                                              HttpServletRequest request) {
        IntegrationRequestContext context = authenticate(headers,
                IntegrationOperation.AUTHORING_SIMULATION_EXECUTE, request);
        throw invalid("RG.AUTHORING.SIMULATION.CONTENT_TYPE_REQUIRED",
                "Simulation commands must use application/json.", context.correlationId(), 415);
    }

    /** Reads one immutable completed run inside the verified scope. */
    @GetMapping(path = "/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> read(@PathVariable String runId,
                                  @RequestHeader HttpHeaders headers,
                                  HttpServletRequest request) {
        IntegrationRequestContext context = authenticate(headers, IntegrationOperation.AUTHORING_SIMULATION_READ,
                request);
        AuthoringScope scope = trustedScope(context);
        Optional<SimulationRunV2> v2 = moduleV2 == null ? Optional.empty() : moduleV2.read(scope, runId);
        if (v2.isPresent()) return response(v2.get().runId()).body(v2.get());
        SimulationRun run = module.readRequired(scope, runId);
        return response(run.runId()).body(run);
    }

    private IntegrationRequestContext authenticate(HttpHeaders headers, IntegrationOperation operation,
                                                     HttpServletRequest request) {
        IntegrationRequestContext context = authenticator.authenticate(headers, operation);
        request.setAttribute(AuthoringRequestAttributes.CORRELATION_ID, context.correlationId());
        return context;
    }

    private SimulationRequest command(JsonNode wire, String correlationId) {
        try {
            SimulationRequest command = strictMapper.treeToValue(wire, SimulationRequest.class);
            if (command == null) throw invalidRequest(correlationId);
            return command;
        } catch (IntegrationProblemException failure) {
            throw failure;
        } catch (RuntimeException | java.io.IOException failure) {
            throw invalidRequest(correlationId);
        }
    }

    private SimulationCommandV2 commandV2(JsonNode wire, String correlationId) {
        try {
            SimulationCommandV2 command = strictMapper.treeToValue(wire, SimulationCommandV2.class);
            if (command == null) throw invalidRequest(correlationId);
            return command;
        } catch (IntegrationProblemException failure) {
            throw failure;
        } catch (RuntimeException | java.io.IOException failure) {
            throw invalidRequest(correlationId);
        }
    }

    private static String idempotencyKey(HttpHeaders headers, String correlationId) {
        List<String> values = headers.get("Idempotency-Key");
        if (values == null || values.size() != 1 || !KEY.matcher(values.getFirst()).matches()) {
            throw invalid("RG.AUTHORING.SIMULATION.IDEMPOTENCY_KEY_REQUIRED",
                    "One valid Idempotency-Key header is required.", correlationId, 400);
        }
        return values.getFirst();
    }

    private static AuthoringScope trustedScope(IntegrationRequestContext context) {
        try {
            return new AuthoringScope(context.tenantId(), context.projectId(), context.environmentId());
        } catch (IllegalArgumentException failure) {
            throw invalidRequest(context.correlationId());
        }
    }

    private static SimulationIdentity identity(AuthoringScope scope, IntegrationRequestContext context) {
        try {
            return new SimulationIdentity(scope, context.organizationId(), context.region(),
                    context.actorType(), context.actorId(), context.clearance(), context.correlationId());
        } catch (IllegalArgumentException failure) {
            throw invalidRequest(context.correlationId());
        }
    }

    private static ResponseEntity.BodyBuilder response(String runId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache").header("X-Simulation-Run-Id", runId);
    }

    private static IntegrationProblemException invalidRequest(String correlationId) {
        return invalid("RG.AUTHORING.SIMULATION.REQUEST_INVALID",
                "Simulation request is malformed or incomplete.", correlationId, 400);
    }

    private static IntegrationProblemException invalid(String code, String title,
                                                        String correlationId, int status) {
        return new IntegrationProblemException(new IntegrationProblem(IntegrationProblem.SCHEMA_VERSION,
                "urn:bloge:problem:bad-authoring-request", title, status, code, false,
                correlationId, java.util.Map.of()));
    }
}
