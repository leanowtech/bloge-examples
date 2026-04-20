package com.leanowtech.bloge.gateway.carrier;

import com.leanowtech.bloge.core.context.TenantContext;
import com.leanowtech.bloge.core.context.TenantContextHolder;
import com.leanowtech.bloge.core.spi.CallerContextCarrier;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * {@link CallerContextCarrier} that propagates tenant identity from the caller
 * thread into SLF4J MDC on virtual threads spawned by the graph engine.
 *
 * <h3>Why this matters for virtual-thread execution</h3>
 * <p>The bloge graph engine executes operator nodes on virtual threads. SLF4J MDC
 * is backed by a thread-local map, so MDC entries set on the caller thread are
 * <em>not</em> automatically visible on virtual threads. Without explicit propagation,
 * log lines from operator execution would lack tenant context — making multi-tenant
 * log correlation impossible.
 *
 * <h3>Contract</h3>
 * <ol>
 *   <li>{@link #capture()} — called on the caller (HTTP request) thread. Snapshots the
 *       current {@link TenantContext} from {@link TenantContextHolder}.</li>
 *   <li>{@link Snapshot#attach()} — called on each virtual thread. Sets {@code tenantId}
 *       and {@code namespace} in MDC, saving any prior values for restoration.</li>
 *   <li>{@link Scope#close()} — called when the virtual thread's work completes. Restores
 *       the previous MDC values (or removes the keys if none existed).</li>
 * </ol>
 */
@Component
public class TenantMdcCarrier implements CallerContextCarrier {

    private static final String MDC_TENANT_ID = "tenantId";
    private static final String MDC_NAMESPACE = "namespace";

    /**
     * Captures the current tenant context from the caller thread.
     *
     * @return a snapshot that can be attached on virtual threads
     */
    @Override
    public Snapshot capture() {
        TenantContext tenant = TenantContextHolder.currentIfBound().orElse(null);
        return new TenantSnapshot(tenant);
    }

    // ── Snapshot implementation ─────────────────────────────────────────

    private record TenantSnapshot(TenantContext tenant) implements Snapshot {

        /**
         * Attaches the captured tenant context to the current thread's MDC.
         *
         * @return a scope that restores the previous MDC state on close
         */
        @Override
        public Scope attach() {
            String previousTenantId = MDC.get(MDC_TENANT_ID);
            String previousNamespace = MDC.get(MDC_NAMESPACE);

            if (tenant != null) {
                MDC.put(MDC_TENANT_ID, tenant.tenantId());
                MDC.put(MDC_NAMESPACE, tenant.namespace());
            } else {
                MDC.remove(MDC_TENANT_ID);
                MDC.remove(MDC_NAMESPACE);
            }

            return new MdcRestoreScope(previousTenantId, previousNamespace);
        }
    }

    // ── Scope implementation ────────────────────────────────────────────

    private record MdcRestoreScope(String previousTenantId, String previousNamespace) implements Scope {

        /**
         * Restores the MDC keys to their values before this scope was opened.
         */
        @Override
        public void close() {
            restoreOrRemove(MDC_TENANT_ID, previousTenantId);
            restoreOrRemove(MDC_NAMESPACE, previousNamespace);
        }

        private static void restoreOrRemove(String key, String previousValue) {
            if (previousValue != null) {
                MDC.put(key, previousValue);
            } else {
                MDC.remove(key);
            }
        }
    }
}
