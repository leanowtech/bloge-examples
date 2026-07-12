package com.leanowtech.bloge.gateway.visual.runtime;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP control plane for caller-addressed visual runs. */
@RestController
@RequestMapping("/api/visual/run-controls")
public class VisualRunControlController {

    private final VisualGraphRunService runner;

    public VisualRunControlController(VisualGraphRunService runner) {
        this.runner = runner;
    }

    /** Returns lifecycle state only to a caller presenting the immutable run fence. */
    @GetMapping("/{requestId}")
    public ResponseEntity<VisualRunControlResult> get(
            @PathVariable String requestId,
            @RequestHeader("X-Run-Fencing-Token") String fencingToken) {
        return response(runner.runControl(requestId, fencingToken));
    }

    /** Requests cooperative cancellation; terminal status requires observed owner-thread exit. */
    @PostMapping("/{requestId}/cancel")
    public ResponseEntity<VisualRunControlResult> cancel(
            @PathVariable String requestId,
            @RequestBody VisualRunCancelRequest request) {
        return response(runner.cancel(new VisualRunControlCommand(requestId, request.fencingToken(),
                request.expectedRevision(), request.reason())));
    }

    private static ResponseEntity<VisualRunControlResult> response(VisualRunControlResult result) {
        if (result.accepted()) {
            return ResponseEntity.ok(result);
        }
        HttpStatus status = switch (result.code()) {
            case "RG.RUN_CONTROL.NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "RG.RUN_CONTROL.FENCE_MISMATCH" -> HttpStatus.FORBIDDEN;
            case "RG.RUN_CONTROL.INVALID_COMMAND" -> HttpStatus.BAD_REQUEST;
            case "RG.RUN_CONTROL.UNSUPPORTED" -> HttpStatus.NOT_IMPLEMENTED;
            default -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(result);
    }
}
