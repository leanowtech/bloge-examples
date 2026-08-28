package com.leanowtech.bloge.gateway.visual.simulation;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint exposing the visual graph mock-run (simulate) capability.
 *
 * <p>Unlike {@code POST /api/visual/drafts/run}, which blocks graphs that reference unimplemented
 * (design-only) operators, this endpoint synthesizes schema-conforming stand-ins for such operators so
 * the whole graph can be executed for runtime-correctness validation. The response marks which nodes
 * were mocked versus executed for real.</p>
 */
@RestController
@RequestMapping("/api/visual/graphs")
public class VisualGraphSimulationController {

    private final VisualGraphSimulationService simulationService;
    private final VisualGovernedFixtureSimulationPort governedSimulation;

    /**
     * @param simulationService visual graph simulation service
     */
    public VisualGraphSimulationController(VisualGraphSimulationService simulationService) {
        this(simulationService, null);
    }

    /** Spring-owned constructor enabling the optional gateway governed-simulation adapter. */
    @org.springframework.beans.factory.annotation.Autowired
    public VisualGraphSimulationController(
            VisualGraphSimulationService simulationService,
            ObjectProvider<VisualGovernedFixtureSimulationPort> governedSimulation) {
        this.simulationService = simulationService;
        this.governedSimulation = governedSimulation == null
                ? null : governedSimulation.getIfAvailable();
    }

    /**
     * Simulates a transient visual graph draft.
     *
     * @param request the simulation request
     * @return the simulation result
     */
    public VisualGraphSimulationResponse simulate(VisualGraphSimulationRequest request) {
        return simulate(request, null);
    }

    /**
     * Simulates a request while optionally resolving authenticated governed Fixtures.
     *
     * <p>Governed material is resolved to a protected output before simulation. The request's
     * exact Fixture coordinate, expected-input assertion, and caller-selected evidence fidelity
     * remain intact, so material resolution cannot silently downgrade the server's evidence
     * boundary.</p>
     *
     * @param request transient graph and request-scoped Fixture references
     * @param headers authenticated request headers used for governed material reads
     * @return server-authoritative simulation result
     */
    @PostMapping("/simulate")
    public VisualGraphSimulationResponse simulate(
            @RequestBody VisualGraphSimulationRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(required = false) HttpHeaders headers) {
        return governedSimulation == null
                ? simulationService.simulate(request.draft(), request.context(), request.outputNode(), request.fixtures())
                : governedSimulation.simulate(request, headers);
    }

    /** Maps fail-closed governed Fixture resolution failures to a payload-free problem response. */
    @ExceptionHandler(VisualGovernedFixtureResolutionException.class)
    public ResponseEntity<ProblemDetail> handleGovernedFixture(
            VisualGovernedFixtureResolutionException failure) {
        ProblemDetail problem = ProblemDetail.forStatus(failure.status());
        problem.setTitle("Governed Fixture cannot be used for simulation");
        problem.setDetail(failure.getMessage());
        problem.setProperty("code", "VISUAL_GOVERNED_FIXTURE_BLOCKED");
        return ResponseEntity.status(failure.status())
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }

    /** Maps the visual-owned admission failure to Spring's standard problem-details contract. */
    @ExceptionHandler(VisualSimulationProductionAdmissionException.class)
    public ResponseEntity<ProblemDetail> handleProductionAdmission(
            VisualSimulationProductionAdmissionException failure) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle(VisualSimulationProductionAdmissionException.TITLE);
        problem.setDetail(VisualSimulationProductionAdmissionException.TITLE);
        problem.setProperty("code", VisualSimulationProductionAdmissionException.CODE);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(problem);
    }
}
