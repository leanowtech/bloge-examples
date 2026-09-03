package com.leanowtech.bloge.gateway.resource;

import com.leanowtech.bloge.core.operator.OperatorContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Request-scoped admission registry for executions that must use pre-approved resource descriptors.
 *
 * <p>Ordinary gateway runs have no entry and retain their existing behavior. A governed caller may
 * register an exact descriptor set under an internal execution capture id. {@code httpResource}
 * then compares the descriptor it resolved with that immutable set immediately before rendering
 * or sending the request. This closes the allowlist-check-to-use race without putting credentials,
 * URLs, or business payloads into persisted run evidence.</p>
 */
@Component
public final class ResourceExecutionAdmissionRegistry {
    /** Context key populated internally for one exact graph execution capture. */
    public static final String EXECUTION_CAPTURE_ID_CONTEXT_KEY = "_blogeEvidenceCaptureId";

    private final Map<String, Admission> active = new ConcurrentHashMap<>();

    /**
     * Reserves one internal execution capture id with an immutable exact descriptor set.
     *
     * @param requestId unpredictable runtime-owned execution capture id
     * @param descriptors descriptors admitted before execution, keyed by resource id
     * @return lease that removes the admission after the run finishes
     * @throws IllegalStateException if the request id is already active
     */
    public AdmissionLease register(String requestId, Map<String, ResourceDescriptor> descriptors) {
        String normalized = normalized(requestId);
        if (normalized.isBlank()) throw new IllegalArgumentException("requestId is required");
        Admission admission = new Admission(descriptors);
        if (active.putIfAbsent(normalized, admission) != null) {
            throw new IllegalStateException("Resource execution admission is already active.");
        }
        return new AdmissionLease(normalized, admission);
    }

    /**
     * Requires an exact descriptor match when the operator belongs to an admitted execution.
     *
     * @param context current operator context
     * @param descriptor descriptor resolved by the HTTP operator
     * @throws IllegalStateException if the resource was not admitted or changed after admission
     */
    public void requireCurrent(OperatorContext context, ResourceDescriptor descriptor) {
        if (context == null || descriptor == null) return;
        Object rawRequestId = context.graphContext().get(EXECUTION_CAPTURE_ID_CONTEXT_KEY);
        Admission admission = active.get(normalized(rawRequestId == null ? "" : rawRequestId.toString()));
        if (admission == null) return;
        admission.requireCurrent(descriptor);
    }

    /**
     * Records that an admitted HTTP request crossed the last application guard into its transport.
     *
     * <p>This observation is intentionally later than node invocation capture. Parameter rendering,
     * descriptor replacement, and other pre-transport failures therefore cannot be reported as a
     * real external call. A transport may still fail after accepting the request, so the event is
     * a conservative dispatch observation rather than a claim that the remote system replied.</p>
     *
     * @param context current operator context, including the runtime-owned capture id and node id
     * @param descriptor exact descriptor used to build the request
     */
    public void recordTransportDispatch(OperatorContext context, ResourceDescriptor descriptor) {
        if (context == null || descriptor == null) return;
        Object rawRequestId = context.graphContext().get(EXECUTION_CAPTURE_ID_CONTEXT_KEY);
        Admission admission = active.get(normalized(rawRequestId == null ? "" : rawRequestId.toString()));
        if (admission == null) return;
        admission.requireCurrent(descriptor);
        admission.record(context.nodeId(), context.retryAttempt());
    }

    /** Lease for one exact controlled execution; closing it cannot fail. */
    public final class AdmissionLease implements AutoCloseable {
        private final String requestId;
        private final Admission admission;
        private boolean closed;

        private AdmissionLease(String requestId, Admission admission) {
            this.requestId = requestId;
            this.admission = admission;
        }

        /** Removes this lease only if it still owns the exact registered admission. */
        @Override
        public void close() {
            if (!closed) {
                active.remove(requestId, admission);
                closed = true;
            }
        }

        /** Returns a payload-free snapshot of transport dispatches observed under this lease. */
        public Map<String, List<TransportDispatch>> transportDispatches() {
            return admission.transportDispatches();
        }
    }

    /** Low-cardinality observation produced immediately before the HTTP transport is invoked. */
    public record TransportDispatch(int retryAttempt, Instant observedAt) {
        public TransportDispatch {
            retryAttempt = Math.max(0, retryAttempt);
            observedAt = observedAt == null ? Instant.EPOCH : observedAt;
        }
    }

    private static final class Admission {
        private final Map<String, ResourceDescriptor> descriptors;
        private final ConcurrentHashMap<String, List<TransportDispatch>> dispatches = new ConcurrentHashMap<>();

        private Admission(Map<String, ResourceDescriptor> descriptors) {
            LinkedHashMap<String, ResourceDescriptor> copy = new LinkedHashMap<>();
            if (descriptors != null) {
                descriptors.forEach((resourceId, descriptor) -> {
                    String normalized = normalized(resourceId);
                    if (normalized.isBlank() || descriptor == null
                            || !normalized.equals(descriptor.resourceId())) {
                        throw new IllegalArgumentException(
                                "Admission descriptors require matching resource ids.");
                    }
                    copy.put(normalized, descriptor);
                });
            }
            this.descriptors = Map.copyOf(copy);
        }

        private void requireCurrent(ResourceDescriptor descriptor) {
            ResourceDescriptor approved = descriptors.get(descriptor.resourceId());
            if (!descriptor.equals(approved)) {
                throw new IllegalStateException("Resource descriptor changed after governed admission.");
            }
        }

        private void record(String nodeId, int retryAttempt) {
            dispatches.computeIfAbsent(normalized(nodeId), ignored ->
                    java.util.Collections.synchronizedList(new java.util.ArrayList<>()))
                    .add(new TransportDispatch(retryAttempt, Instant.now()));
        }

        private Map<String, List<TransportDispatch>> transportDispatches() {
            LinkedHashMap<String, List<TransportDispatch>> snapshot = new LinkedHashMap<>();
            dispatches.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                synchronized (entry.getValue()) {
                    snapshot.put(entry.getKey(), List.copyOf(entry.getValue()));
                }
            });
            return Map.copyOf(snapshot);
        }
    }

    private static String normalized(String value) {
        return Objects.toString(value, "").trim();
    }
}
