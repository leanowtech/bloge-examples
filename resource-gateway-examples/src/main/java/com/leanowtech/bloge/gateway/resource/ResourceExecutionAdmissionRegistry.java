package com.leanowtech.bloge.gateway.resource;

import com.leanowtech.bloge.core.operator.OperatorContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
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
        ResourceDescriptor approved = admission.descriptors().get(descriptor.resourceId());
        if (!descriptor.equals(approved)) {
            throw new IllegalStateException("Resource descriptor changed after governed admission.");
        }
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
    }

    private record Admission(Map<String, ResourceDescriptor> descriptors) {
        private Admission {
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
            descriptors = Map.copyOf(copy);
        }
    }

    private static String normalized(String value) {
        return Objects.toString(value, "").trim();
    }
}
