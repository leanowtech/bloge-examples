package com.leanowtech.bloge.graphengine.server.rest;

import com.leanowtech.bloge.graphengine.model.GraphTask;
import com.leanowtech.bloge.graphengine.server.rest.dto.CancelTaskRequest;
import com.leanowtech.bloge.graphengine.server.rest.dto.ClaimTaskRequest;
import com.leanowtech.bloge.graphengine.server.rest.dto.CompleteTaskRequest;
import com.leanowtech.bloge.graphengine.server.rest.dto.ReassignTaskRequest;
import com.leanowtech.bloge.graphengine.service.GraphEngineService;
import com.leanowtech.bloge.graphengine.service.command.CancelTaskCommand;
import com.leanowtech.bloge.graphengine.service.command.ClaimTaskCommand;
import com.leanowtech.bloge.graphengine.service.command.CompleteTaskCommand;
import com.leanowtech.bloge.graphengine.service.command.ReassignTaskCommand;
import com.leanowtech.bloge.runtime.task.TaskInboxQuery;
import com.leanowtech.bloge.runtime.task.TaskInboxStatus;

import jakarta.validation.Valid;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * REST controller for durable human-task inbox operations.
 */
@RestController
@Validated
@RequestMapping("/api/v1/tasks")
public class GraphTaskController {

    private final GraphEngineService graphEngineService;

    /**
     * Creates one controller instance.
     *
     * @param graphEngineService product control-plane service
     */
    public GraphTaskController(GraphEngineService graphEngineService) {
        this.graphEngineService = graphEngineService;
    }

    /**
     * Lists human tasks visible through the durable inbox query surface.
     *
     * @param assignee optional assignee filter
     * @param candidateType optional candidate-dimension filter
     * @param candidateValue optional candidate value paired with {@code candidateType}
     * @param statuses optional task-state filters
     * @param minPriority optional minimum priority
     * @param maxPriority optional maximum priority
     * @param taskType optional task-type filter
     * @param executionId optional execution identifier filter
     * @param page zero-based page index
     * @param size requested page size
     * @return matching task projections
     */
    @GetMapping
    public List<GraphTask> queryTasks(@RequestParam(required = false) String assignee,
                                      @RequestParam(required = false) String candidateType,
                                      @RequestParam(required = false) String candidateValue,
                                      @RequestParam(required = false) Set<TaskInboxStatus> statuses,
                                      @RequestParam(required = false) Integer minPriority,
                                      @RequestParam(required = false) Integer maxPriority,
                                      @RequestParam(required = false) String taskType,
                                      @RequestParam(required = false) String executionId,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "50") int size) {
        return graphEngineService.queryTasks(new TaskInboxQuery(
                assignee,
                candidateType,
                candidateValue,
                statuses,
                minPriority,
                maxPriority,
                taskType,
                executionId,
                page,
                size
        ));
    }

    /**
     * Loads one human task projection.
     *
     * @param taskId task identifier
     * @return matching task projection
     */
    @GetMapping("/{taskId}")
    public GraphTask getTask(@PathVariable String taskId) {
        return graphEngineService.getTask(taskId);
    }

    /**
     * Claims one human task for a concrete user.
     *
     * @param taskId task identifier
     * @param request claim request
     * @return refreshed task projection
     */
    @PostMapping("/{taskId}/claim")
    public GraphTask claimTask(@PathVariable String taskId,
                               @Valid @RequestBody ClaimTaskRequest request) {
        return graphEngineService.claimTask(new ClaimTaskCommand(taskId, request.userId()));
    }

    /**
     * Completes one human task.
     *
     * @param taskId task identifier
     * @param request completion request
     * @return refreshed task projection
     */
    @PostMapping("/{taskId}/complete")
    public GraphTask completeTask(@PathVariable String taskId,
                                  @Valid @RequestBody CompleteTaskRequest request) {
        return graphEngineService.completeTask(new CompleteTaskCommand(taskId, request.output(), request.userId()));
    }

    /**
     * Reassigns one human task to another owner.
     *
     * @param taskId task identifier
     * @param request reassignment request
     * @return refreshed task projection
     */
    @PostMapping("/{taskId}/reassign")
    public GraphTask reassignTask(@PathVariable String taskId,
                                  @Valid @RequestBody ReassignTaskRequest request) {
        return graphEngineService.reassignTask(new ReassignTaskCommand(taskId, request.newAssignee()));
    }

    /**
     * Cancels one human task.
     *
     * @param taskId task identifier
     * @param request cancellation request
     * @return refreshed task projection
     */
    @PostMapping("/{taskId}/cancel")
    public GraphTask cancelTask(@PathVariable String taskId,
                                @Valid @RequestBody CancelTaskRequest request) {
        return graphEngineService.cancelTask(new CancelTaskCommand(taskId, request.reason()));
    }
}
