package com.leanowtech.bloge.gateway.integration.mirror;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/**
 * Commits a terminal Mirror failure audit independently from the failing business transaction.
 *
 * <p>A failure audit must survive the rollback that caused it. This writer therefore always uses
 * {@link TransactionDefinition#PROPAGATION_REQUIRES_NEW}; successful operation audits deliberately
 * bypass it so they remain atomic with the Plan or Run result they authorize for publication.</p>
 */
public final class MirrorOperationFailureAuditService {
    private final MirrorOperationAuditRepository audit;
    private final TransactionTemplate transactions;

    /**
     * Creates the isolated failure writer.
     *
     * @param audit payload-free durable operation audit
     * @param transactionManager local transaction manager used by Mirror persistence
     */
    public MirrorOperationFailureAuditService(
            MirrorOperationAuditRepository audit,
            PlatformTransactionManager transactionManager) {
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Commits one already classified payload-free failure fact in an independent transaction.
     *
     * @param event unpersisted terminal rejection or failure fact
     * @return event with database-assigned sequence and time
     */
    public MirrorOperationAuditEvent append(MirrorOperationAuditEvent event) {
        MirrorOperationAuditEvent persisted = transactions.execute(
                status -> audit.append(Objects.requireNonNull(event, "event")));
        return Objects.requireNonNull(persisted,
                "Mirror operation failure audit transaction returned no event");
    }
}
