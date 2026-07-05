package com.leanowtech.bloge.graphengine.model;

import com.leanowtech.bloge.core.runtime.identity.ExecutionIdentity;
import com.leanowtech.bloge.core.runtime.work.WorkItemType;
import com.leanowtech.bloge.core.spi.SystemTimeSource;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tenant-scoped operations snapshot for the graph-engine control plane.
 *
 * <p>This is an operations control-plane projection, not a metrics backend. Counts are derived from
 * the bounded sample returned by the service layer and {@link #truncated()} tells callers when a
 * larger fleet should be inspected through paginated list APIs or external metrics.</p>
 *
 * @param tenantId tenant scope
 * @param namespace namespace scope
 * @param generatedAt snapshot timestamp
 * @param sampleLimit maximum scoped rows inspected per source
 * @param truncated whether at least one sampled source hit the sample limit
 * @param health overall operations health
 * @param instancesByStatus instance count by projected lifecycle status
 * @param instancesByExecutionMode instance count by runtime family
 * @param sampledInstanceCount number of instance rows inspected
 * @param activeInstanceCount running or suspended instance count
 * @param terminalInstanceCount completed, failed, cancelled, or terminated instance count
 * @param deploymentCount number of deployment rows inspected
 * @param activeDeploymentCount active deployment rows inspected
 * @param deadLetterCount number of dead-letter rows inspected
 * @param recentDeadLetters recent dead-letter samples
 * @param actionItems recommended operations actions
 * @param sloIndicators stable SLO/metric indicators derived from the same snapshot rules
 */
public record GraphOperationsSnapshot(
        String tenantId,
        String namespace,
        Instant generatedAt,
        int sampleLimit,
        boolean truncated,
        Health health,
        Map<GraphInstanceStatus, Integer> instancesByStatus,
        Map<GraphExecutionMode, Integer> instancesByExecutionMode,
        int sampledInstanceCount,
        int activeInstanceCount,
        int terminalInstanceCount,
        int deploymentCount,
        int activeDeploymentCount,
        int deadLetterCount,
        List<DeadLetterSample> recentDeadLetters,
        List<ActionItem> actionItems,
        List<SloIndicator> sloIndicators
) {
    /**
     * Creates a snapshot.
     */
    public GraphOperationsSnapshot {
        tenantId = resolveScopeValue(tenantId, ExecutionIdentity.DEFAULT_TENANT, "tenantId");
        namespace = resolveScopeValue(namespace, ExecutionIdentity.DEFAULT_NAMESPACE, "namespace");
        generatedAt = generatedAt == null ? SystemTimeSource.INSTANCE.now() : generatedAt;
        if (sampleLimit < 1) {
            throw new IllegalArgumentException("sampleLimit must be >= 1");
        }
        health = Objects.requireNonNullElse(health, Health.OK);
        instancesByStatus = copyStatusCounts(instancesByStatus);
        instancesByExecutionMode = copyModeCounts(instancesByExecutionMode);
        requireNonNegative(sampledInstanceCount, "sampledInstanceCount");
        requireNonNegative(activeInstanceCount, "activeInstanceCount");
        requireNonNegative(terminalInstanceCount, "terminalInstanceCount");
        requireNonNegative(deploymentCount, "deploymentCount");
        requireNonNegative(activeDeploymentCount, "activeDeploymentCount");
        requireNonNegative(deadLetterCount, "deadLetterCount");
        recentDeadLetters = recentDeadLetters == null ? List.of() : List.copyOf(recentDeadLetters);
        actionItems = actionItems == null ? List.of() : List.copyOf(actionItems);
        sloIndicators = sloIndicators == null ? List.of() : List.copyOf(sloIndicators);
    }

    /**
     * Operations health level.
     */
    public enum Health {
        OK,
        WARNING,
        CRITICAL
    }

    /**
     * One recommended operations action.
     *
     * @param code stable action code
     * @param severity action severity
     * @param message human-readable action message
     * @param targetType target resource kind
     * @param targetId target resource identifier or empty when aggregate-scoped
     */
    public record ActionItem(
            String code,
            Health severity,
            String message,
            String targetType,
            String targetId
    ) {
        public ActionItem {
            code = requireNonBlank(code, "code");
            severity = Objects.requireNonNullElse(severity, Health.WARNING);
            message = message == null ? "" : message;
            targetType = targetType == null ? "" : targetType;
            targetId = targetId == null ? "" : targetId;
        }
    }

    /**
     * Stable operations SLO indicator that can be rendered by the console and
     * bound to metrics/alerting systems.
     *
     * @param code stable indicator code
     * @param health current health for this indicator
     * @param metricName metric name that carries the same observed signal
     * @param observedValue value observed in the snapshot
     * @param warningThreshold warning threshold, or {@code null} when not applicable
     * @param criticalThreshold critical threshold, or {@code null} when not applicable
     * @param unit unit for the observed value
     * @param message human-readable interpretation
     * @param actionCode matching action item code, or empty when no action is emitted
     */
    public record SloIndicator(
            String code,
            Health health,
            String metricName,
            double observedValue,
            Double warningThreshold,
            Double criticalThreshold,
            String unit,
            String message,
            String actionCode
    ) {
        public SloIndicator {
            code = requireNonBlank(code, "code");
            health = Objects.requireNonNullElse(health, Health.OK);
            metricName = requireNonBlank(metricName, "metricName");
            requireFiniteNonNegative(observedValue, "observedValue");
            warningThreshold = optionalFiniteNonNegative(warningThreshold, "warningThreshold");
            criticalThreshold = optionalFiniteNonNegative(criticalThreshold, "criticalThreshold");
            unit = unit == null || unit.isBlank() ? "count" : unit;
            message = message == null ? "" : message;
            actionCode = actionCode == null ? "" : actionCode;
        }
    }

    /**
     * Compact dead-letter sample for operations dashboards.
     *
     * @param itemId dead-letter item identifier
     * @param instanceId owning instance identifier
     * @param definitionKey owning graph definition key
     * @param businessKey optional business correlation key
     * @param itemType work item type
     * @param nodeId node identifier
     * @param lastError last runtime error
     * @param deadLetterReason reason recorded for dead-lettering
     * @param deadLetteredAt dead-letter timestamp
     */
    public record DeadLetterSample(
            String itemId,
            String instanceId,
            String definitionKey,
            String businessKey,
            WorkItemType itemType,
            String nodeId,
            String lastError,
            String deadLetterReason,
            Instant deadLetteredAt
    ) {
        public DeadLetterSample {
            itemId = requireNonBlank(itemId, "itemId");
            instanceId = requireNonBlank(instanceId, "instanceId");
            definitionKey = definitionKey == null ? "" : definitionKey;
            businessKey = businessKey == null ? "" : businessKey;
            itemType = Objects.requireNonNull(itemType, "itemType");
            nodeId = nodeId == null ? "" : nodeId;
            lastError = lastError == null ? "" : lastError;
            deadLetterReason = deadLetterReason == null ? "" : deadLetterReason;
            deadLetteredAt = deadLetteredAt == null ? SystemTimeSource.INSTANCE.now() : deadLetteredAt;
        }

        /**
         * Creates a sample from a full dead-letter projection.
         *
         * @param deadLetter source dead-letter projection
         * @return compact sample
         */
        public static DeadLetterSample from(GraphDeadLetter deadLetter) {
            return new DeadLetterSample(
                    deadLetter.itemId(),
                    deadLetter.instanceId(),
                    deadLetter.definitionKey(),
                    deadLetter.businessKey(),
                    deadLetter.itemType(),
                    deadLetter.nodeId(),
                    deadLetter.lastError(),
                    deadLetter.deadLetterReason(),
                    deadLetter.deadLetteredAt()
            );
        }
    }

    private static Map<GraphInstanceStatus, Integer> copyStatusCounts(Map<GraphInstanceStatus, Integer> values) {
        EnumMap<GraphInstanceStatus, Integer> copy = new EnumMap<>(GraphInstanceStatus.class);
        if (values != null) {
            values.forEach((key, value) -> copy.put(Objects.requireNonNull(key, "status"), nonNegative(value)));
        }
        return Map.copyOf(copy);
    }

    private static Map<GraphExecutionMode, Integer> copyModeCounts(Map<GraphExecutionMode, Integer> values) {
        EnumMap<GraphExecutionMode, Integer> copy = new EnumMap<>(GraphExecutionMode.class);
        if (values != null) {
            values.forEach((key, value) -> copy.put(Objects.requireNonNull(key, "executionMode"), nonNegative(value)));
        }
        return Map.copyOf(copy);
    }

    private static int nonNegative(Integer value) {
        if (value == null || value < 0) {
            throw new IllegalArgumentException("count values must be >= 0");
        }
        return value;
    }

    private static void requireNonNegative(int value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + " must be >= 0");
        }
    }

    private static void requireFiniteNonNegative(double value, String fieldName) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(fieldName + " must be finite and >= 0");
        }
    }

    private static Double optionalFiniteNonNegative(Double value, String fieldName) {
        if (value == null) {
            return null;
        }
        requireFiniteNonNegative(value, fieldName);
        return value;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static String resolveScopeValue(String value, String fallback, String fieldName) {
        if (value == null) {
            return fallback;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
