package com.leanowtech.bloge.graphengine.service;

import com.leanowtech.bloge.core.checkpoint.CheckpointCodec;
import com.leanowtech.bloge.core.checkpoint.TaskStore;
import com.leanowtech.bloge.core.runtime.checkpoint.ExecutionCheckpointStore;
import com.leanowtech.bloge.core.runtime.event.EventMatcherStore;
import com.leanowtech.bloge.core.runtime.execution.ExecutionStore;
import com.leanowtech.bloge.core.runtime.registry.GraphRegistryStore;
import com.leanowtech.bloge.core.runtime.wait.WaitStore;
import com.leanowtech.bloge.core.runtime.work.WorkItemNotifier;
import com.leanowtech.bloge.core.runtime.work.WorkItemStore;
import com.leanowtech.bloge.core.spi.JsonCodec;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.core.spi.ScriptOperatorFactory;
import com.leanowtech.bloge.core.spi.SystemTimeSource;
import com.leanowtech.bloge.core.spi.TimeSource;
import com.leanowtech.bloge.durable.control.ControlPlaneService;
import com.leanowtech.bloge.runtime.audit.AuditJournalStore;
import com.leanowtech.bloge.runtime.engine.DurableGraphEngine;
import com.leanowtech.bloge.runtime.task.TaskInboxStore;
import com.leanowtech.bloge.runtime.timer.TimerService;

/**
 * Aggregate of lower-level durable runtime collaborators that the product-layer
 * graph-engine service needs in order to validate, publish, start, signal, and
 * project graph-engine operations.
 */
public final class GraphEngineRuntimeSupport {

    private final DurableGraphEngine durableGraphEngine;
    private final OperatorRegistry operatorRegistry;
    private final GraphRegistryStore graphRegistryStore;
    private final ExecutionStore executionStore;
    private final ExecutionCheckpointStore executionCheckpointStore;
    private final EventMatcherStore eventMatcherStore;
    private final WaitStore waitStore;
    private final TaskInboxStore taskInboxStore;
    private final TaskStore taskStore;
    private final AuditJournalStore auditJournalStore;
    private final WorkItemStore workItemStore;
    private final WorkItemNotifier workItemNotifier;
    private final CheckpointCodec checkpointCodec;
    private final ControlPlaneService controlPlaneService;
    private final TimerService timerService;
    private final ScriptOperatorFactory scriptOperatorFactory;
    private final JsonCodec jsonCodec;
    private final TimeSource timeSource;
    private final GraphEngineMetricsObserver metricsObserver;
    private final VersionCompilerCacheSettings versionCompilerCacheSettings;

    private GraphEngineRuntimeSupport(Builder builder) {
        this.durableGraphEngine = builder.durableGraphEngine;
        this.operatorRegistry = builder.operatorRegistry;
        this.graphRegistryStore = builder.graphRegistryStore;
        this.executionStore = builder.executionStore;
        this.executionCheckpointStore = builder.executionCheckpointStore;
        this.eventMatcherStore = builder.eventMatcherStore;
        this.waitStore = builder.waitStore;
        this.taskInboxStore = builder.taskInboxStore;
        this.taskStore = builder.taskStore;
        this.auditJournalStore = builder.auditJournalStore;
        this.workItemStore = builder.workItemStore;
        this.workItemNotifier = builder.workItemNotifier;
        this.checkpointCodec = builder.checkpointCodec;
        this.controlPlaneService = builder.controlPlaneService;
        this.timerService = builder.timerService;
        this.scriptOperatorFactory = builder.scriptOperatorFactory;
        this.jsonCodec = builder.jsonCodec == null ? JsonCodec.DEFAULT : builder.jsonCodec;
        this.timeSource = builder.timeSource == null ? SystemTimeSource.INSTANCE : builder.timeSource;
        this.metricsObserver = builder.metricsObserver == null
                ? GraphEngineMetricsObserver.NOOP : builder.metricsObserver;
        this.versionCompilerCacheSettings = builder.versionCompilerCacheSettings == null
                ? VersionCompilerCacheSettings.DEFAULT
                : builder.versionCompilerCacheSettings;
    }

    /**
     * Returns a builder for assembling one runtime-support aggregate.
     *
     * @return new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the durable runtime facade used for graph execution and work-item dispatch.
     *
     * @return durable graph engine, or {@code null} when execution is unavailable
     */
    public DurableGraphEngine durableGraphEngine() {
        return durableGraphEngine;
    }

    /**
     * Returns the operator registry used for compilation and execution validation.
     *
     * @return operator registry, or {@code null} when not configured
     */
    public OperatorRegistry operatorRegistry() {
        return operatorRegistry;
    }

    /**
     * Returns the runtime graph registry used for published graph definitions.
     *
     * @return graph registry store, or {@code null}
     */
    public GraphRegistryStore graphRegistryStore() {
        return graphRegistryStore;
    }

    /**
     * Returns the durable execution store.
     *
     * @return execution store, or {@code null}
     */
    public ExecutionStore executionStore() {
        return executionStore;
    }

    /**
     * Returns the durable checkpoint store.
     *
     * @return execution checkpoint store, or {@code null}
     */
    public ExecutionCheckpointStore executionCheckpointStore() {
        return executionCheckpointStore;
    }

    /**
     * Returns the event-matcher store used for wait/await compilation support.
     *
     * @return event matcher store, or {@code null}
     */
    public EventMatcherStore eventMatcherStore() {
        return eventMatcherStore;
    }

    /**
     * Returns the durable wait store used to clean up suspended signal/timer waits.
     *
     * @return wait store, or {@code null}
     */
    public WaitStore waitStore() {
        return waitStore;
    }

    /**
     * Returns the durable human-task inbox store.
     *
     * @return task inbox store, or {@code null}
     */
    public TaskInboxStore taskInboxStore() {
        return taskInboxStore;
    }

    /**
     * Returns an optional task-store facade.
     *
     * @return task store, or {@code null}
     */
    public TaskStore taskStore() {
        return taskStore;
    }

    /**
     * Returns the optional execution audit journal store.
     *
     * @return audit journal store, or {@code null}
     */
    public AuditJournalStore auditJournalStore() {
        return auditJournalStore;
    }

    /**
     * Returns the durable work-item store.
     *
     * @return work-item store, or {@code null}
     */
    public WorkItemStore workItemStore() {
        return workItemStore;
    }

    /**
     * Returns the work-item notifier used when task completion enqueues resume items.
     *
     * @return notifier, or {@code null}
     */
    public WorkItemNotifier workItemNotifier() {
        return workItemNotifier;
    }

    /**
     * Returns the checkpoint codec used for resume payload serialization.
     *
     * @return checkpoint codec, or {@code null}
     */
    public CheckpointCodec checkpointCodec() {
        return checkpointCodec;
    }

    /**
     * Returns the optional control-plane query service.
     *
     * @return control-plane service, or {@code null}
     */
    public ControlPlaneService controlPlaneService() {
        return controlPlaneService;
    }

    /**
     * Returns the optional timer service used to cancel scheduled execution timers.
     *
     * @return timer service, or {@code null}
     */
    public TimerService timerService() {
        return timerService;
    }

    /**
     * Returns the optional script-operator factory used during compilation.
     *
     * @return script operator factory, or {@code null}
     */
    public ScriptOperatorFactory scriptOperatorFactory() {
        return scriptOperatorFactory;
    }

    /**
     * Returns the JSON codec used for source-definition encoding.
     *
     * @return JSON codec
     */
    public JsonCodec jsonCodec() {
        return jsonCodec;
    }

    /**
     * Returns the logical time source used by the product service.
     *
     * @return time source
     */
    public TimeSource timeSource() {
        return timeSource;
    }

    /**
     * Returns the product-layer metrics observer.
     *
     * <p>Never {@code null} — defaults to {@link GraphEngineMetricsObserver#NOOP}
     * when no observer was supplied at build time.</p>
     *
     * @return metrics observer
     */
    public GraphEngineMetricsObserver metricsObserver() {
        return metricsObserver;
    }

    /**
     * Returns the compile-cache policy used by {@link VersionCompiler}.
     *
     * @return compile-cache settings
     */
    public VersionCompilerCacheSettings versionCompilerCacheSettings() {
        return versionCompilerCacheSettings;
    }

    /**
     * Builder for assembling one runtime-support aggregate.
     */
    public static final class Builder {
        private DurableGraphEngine durableGraphEngine;
        private OperatorRegistry operatorRegistry;
        private GraphRegistryStore graphRegistryStore;
        private ExecutionStore executionStore;
        private ExecutionCheckpointStore executionCheckpointStore;
        private EventMatcherStore eventMatcherStore;
        private WaitStore waitStore;
        private TaskInboxStore taskInboxStore;
        private TaskStore taskStore;
        private AuditJournalStore auditJournalStore;
        private WorkItemStore workItemStore;
        private WorkItemNotifier workItemNotifier;
        private CheckpointCodec checkpointCodec;
        private ControlPlaneService controlPlaneService;
        private TimerService timerService;
        private ScriptOperatorFactory scriptOperatorFactory;
        private JsonCodec jsonCodec;
        private TimeSource timeSource;
        private GraphEngineMetricsObserver metricsObserver;
        private VersionCompilerCacheSettings versionCompilerCacheSettings;

        private Builder() {
        }

        public Builder durableGraphEngine(DurableGraphEngine durableGraphEngine) {
            this.durableGraphEngine = durableGraphEngine;
            return this;
        }

        public Builder operatorRegistry(OperatorRegistry operatorRegistry) {
            this.operatorRegistry = operatorRegistry;
            return this;
        }

        public Builder graphRegistryStore(GraphRegistryStore graphRegistryStore) {
            this.graphRegistryStore = graphRegistryStore;
            return this;
        }

        public Builder executionStore(ExecutionStore executionStore) {
            this.executionStore = executionStore;
            return this;
        }

        public Builder executionCheckpointStore(ExecutionCheckpointStore executionCheckpointStore) {
            this.executionCheckpointStore = executionCheckpointStore;
            return this;
        }

        public Builder eventMatcherStore(EventMatcherStore eventMatcherStore) {
            this.eventMatcherStore = eventMatcherStore;
            return this;
        }

        public Builder waitStore(WaitStore waitStore) {
            this.waitStore = waitStore;
            return this;
        }

        public Builder taskInboxStore(TaskInboxStore taskInboxStore) {
            this.taskInboxStore = taskInboxStore;
            return this;
        }

        public Builder taskStore(TaskStore taskStore) {
            this.taskStore = taskStore;
            return this;
        }

        public Builder auditJournalStore(AuditJournalStore auditJournalStore) {
            this.auditJournalStore = auditJournalStore;
            return this;
        }

        public Builder workItemStore(WorkItemStore workItemStore) {
            this.workItemStore = workItemStore;
            return this;
        }

        public Builder workItemNotifier(WorkItemNotifier workItemNotifier) {
            this.workItemNotifier = workItemNotifier;
            return this;
        }

        public Builder checkpointCodec(CheckpointCodec checkpointCodec) {
            this.checkpointCodec = checkpointCodec;
            return this;
        }

        public Builder controlPlaneService(ControlPlaneService controlPlaneService) {
            this.controlPlaneService = controlPlaneService;
            return this;
        }

        public Builder timerService(TimerService timerService) {
            this.timerService = timerService;
            return this;
        }

        public Builder scriptOperatorFactory(ScriptOperatorFactory scriptOperatorFactory) {
            this.scriptOperatorFactory = scriptOperatorFactory;
            return this;
        }

        public Builder jsonCodec(JsonCodec jsonCodec) {
            this.jsonCodec = jsonCodec;
            return this;
        }

        public Builder timeSource(TimeSource timeSource) {
            this.timeSource = timeSource;
            return this;
        }

        public Builder metricsObserver(GraphEngineMetricsObserver metricsObserver) {
            this.metricsObserver = metricsObserver;
            return this;
        }

        public Builder versionCompilerCacheSettings(VersionCompilerCacheSettings versionCompilerCacheSettings) {
            this.versionCompilerCacheSettings = versionCompilerCacheSettings;
            return this;
        }

        /**
         * Builds the immutable runtime-support aggregate.
         *
         * @return configured runtime-support aggregate
         */
        public GraphEngineRuntimeSupport build() {
            return new GraphEngineRuntimeSupport(this);
        }
    }
}
