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
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationExecutionResult;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationIdentity;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationModule;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationRequest;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationRun;
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
import java.util.regex.Pattern;

/** Thin authenticated HTTP adapter for synchronous immutable Simulation runs. */
@RestController
@RequestMapping("/api/authoring/simulations")
@ConditionalOnProperty(prefix = "gateway.authoring.api-resource", name = "enabled", havingValue = "true")
public final class ApiSimulationController {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}");
    private final SimulationModule module;
    private final IntegrationRequestAuthenticator authenticator;
    private final ObjectMapper strictMapper;

    /** Creates the adapter over the single deep Simulation module. */
    public ApiSimulationController(SimulationModule module, IntegrationRequestAuthenticator authenticator,
                                   ObjectMapper mapper) {
        this.module = Objects.requireNonNull(module, "module");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.strictMapper = Objects.requireNonNull(mapper, "mapper").copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** Runs or exactly replays one fixture-backed Simulation command. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SimulationRun> execute(@RequestHeader HttpHeaders headers,
                                                  @RequestBody JsonNode wire,
                                                  HttpServletRequest request) {
        IntegrationRequestContext context = authenticate(headers,
                IntegrationOperation.AUTHORING_SIMULATION_EXECUTE, request);
        AuthoringScope scope = trustedScope(context);
        SimulationExecutionResult result = module.execute(scope, idempotencyKey(headers,
                context.correlationId()), command(wire, context.correlationId()), identity(scope, context));
        return response(result.run()).header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(result.run());
    }

    /** Authenticates before rejecting an unsupported content type. */
    @PostMapping(consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SimulationRun> unsupportedMedia(@RequestHeader HttpHeaders headers,
                                                           HttpServletRequest request) {
        IntegrationRequestContext context = authenticate(headers,
                IntegrationOperation.AUTHORING_SIMULATION_EXECUTE, request);
        throw invalid("RG.AUTHORING.SIMULATION.CONTENT_TYPE_REQUIRED",
                "Simulation commands must use application/json.", context.correlationId(), 415);
    }

    /** Reads one immutable completed run inside the verified scope. */
    @GetMapping(path = "/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SimulationRun> read(@PathVariable String runId,
                                               @RequestHeader HttpHeaders headers,
                                               HttpServletRequest request) {
        IntegrationRequestContext context = authenticate(headers, IntegrationOperation.AUTHORING_SIMULATION_READ,
                request);
        SimulationRun run = module.readRequired(trustedScope(context), runId);
        return response(run).body(run);
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

    private static ResponseEntity.BodyBuilder response(SimulationRun body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache").header("X-Simulation-Run-Id", body.runId());
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
