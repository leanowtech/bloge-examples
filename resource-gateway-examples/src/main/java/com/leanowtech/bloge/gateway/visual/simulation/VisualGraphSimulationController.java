package com.leanowtech.bloge.gateway.visual.simulation;

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

    /**
     * @param simulationService visual graph simulation service
     */
    public VisualGraphSimulationController(VisualGraphSimulationService simulationService) {
        this.simulationService = simulationService;
    }

    /**
     * Simulates a transient visual graph draft.
     *
     * @param request the simulation request
     * @return the simulation result
     */
    @PostMapping("/simulate")
    public VisualGraphSimulationResponse simulate(@RequestBody VisualGraphSimulationRequest request) {
        return simulationService.simulate(
                request.draft(), request.context(), request.outputNode(), request.fixtures());
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
