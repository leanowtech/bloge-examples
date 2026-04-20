package com.leanowtech.bloge.graphengine.server.rest;

import com.leanowtech.bloge.graphengine.model.GraphRemoteWorkerJob;
import com.leanowtech.bloge.graphengine.model.GraphRemoteWorkerRegistration;
import com.leanowtech.bloge.graphengine.server.rest.dto.CompleteRemoteWorkerJobRequest;
import com.leanowtech.bloge.graphengine.server.rest.dto.FailRemoteWorkerJobRequest;
import com.leanowtech.bloge.graphengine.server.rest.dto.HeartbeatRemoteWorkerJobRequest;
import com.leanowtech.bloge.graphengine.server.rest.dto.PollRemoteWorkerJobsRequest;
import com.leanowtech.bloge.graphengine.server.rest.dto.RegisterRemoteWorkerRequest;
import com.leanowtech.bloge.graphengine.service.GraphEngineService;
import com.leanowtech.bloge.graphengine.service.command.CompleteRemoteWorkerJobCommand;
import com.leanowtech.bloge.graphengine.service.command.FailRemoteWorkerJobCommand;
import com.leanowtech.bloge.graphengine.service.command.HeartbeatRemoteWorkerJobCommand;
import com.leanowtech.bloge.graphengine.service.command.PollRemoteWorkerJobsCommand;
import com.leanowtech.bloge.graphengine.service.command.RegisterRemoteWorkerCommand;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for remote-worker discovery and execution callbacks.
 */
@RestController
@Validated
@RequestMapping("/api/v1/remote-workers")
public class GraphRemoteWorkerController {

    private final GraphEngineService graphEngineService;

    /**
     * Creates one controller instance.
     *
     * @param graphEngineService product control-plane service
     */
    public GraphRemoteWorkerController(GraphEngineService graphEngineService) {
        this.graphEngineService = graphEngineService;
    }

    /**
     * Resolves active deployment bindings for one remote worker.
     *
     * @param request registration request
     * @return matching registration view
     */
    @PostMapping("/register")
    public GraphRemoteWorkerRegistration registerRemoteWorker(@Valid @RequestBody RegisterRemoteWorkerRequest request) {
        return graphEngineService.registerRemoteWorker(new RegisterRemoteWorkerCommand(
                request.workerId(),
                request.workerTopic()
        ));
    }

    /**
     * Polls and claims ready jobs for one worker topic.
     *
     * @param workerTopic logical worker topic
     * @param request poll request
     * @return claimed remote-worker jobs
     */
    @PostMapping("/{workerTopic}/poll")
    public List<GraphRemoteWorkerJob> pollRemoteWorkerJobs(@PathVariable String workerTopic,
                                                           @Valid @RequestBody PollRemoteWorkerJobsRequest request) {
        return graphEngineService.pollRemoteWorkerJobs(new PollRemoteWorkerJobsCommand(
                request.workerId(),
                workerTopic,
                request.limit() == null ? 0 : request.limit(),
                request.leaseDuration()
        ));
    }

    /**
     * Renews the lease for one claimed remote-worker job.
     *
     * @param itemId work-item identifier
     * @param request heartbeat request
     * @return refreshed claimed job
     */
    @PostMapping("/items/{itemId}/heartbeat")
    public GraphRemoteWorkerJob heartbeatRemoteWorkerJob(@PathVariable String itemId,
                                                         @Valid @RequestBody HeartbeatRemoteWorkerJobRequest request) {
        return graphEngineService.heartbeatRemoteWorkerJob(new HeartbeatRemoteWorkerJobCommand(
                itemId,
                request.leaseToken(),
                request.leaseDuration()
        ));
    }

    /**
     * Completes one claimed remote-worker job.
     *
     * @param itemId work-item identifier
     * @param request completion request
     * @return empty response when the completion has been accepted
     */
    @PostMapping("/items/{itemId}/complete")
    public ResponseEntity<Void> completeRemoteWorkerJob(@PathVariable String itemId,
                                                        @Valid @RequestBody CompleteRemoteWorkerJobRequest request) {
        graphEngineService.completeRemoteWorkerJob(new CompleteRemoteWorkerJobCommand(
                itemId,
                request.leaseToken(),
                request.expectedRevision(),
                request.output()
        ));
        return ResponseEntity.noContent().build();
    }

    /**
     * Reports one claimed remote-worker job as failed.
     *
     * @param itemId work-item identifier
     * @param request failure request
     * @return empty response when the failure has been accepted
     */
    @PostMapping("/items/{itemId}/fail")
    public ResponseEntity<Void> failRemoteWorkerJob(@PathVariable String itemId,
                                                    @Valid @RequestBody FailRemoteWorkerJobRequest request) {
        graphEngineService.failRemoteWorkerJob(new FailRemoteWorkerJobCommand(
                itemId,
                request.leaseToken(),
                request.expectedRevision(),
                request.error()
        ));
        return ResponseEntity.noContent().build();
    }
}
