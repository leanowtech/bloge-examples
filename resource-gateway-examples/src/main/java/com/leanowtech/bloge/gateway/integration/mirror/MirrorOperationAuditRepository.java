package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.List;

/** Append-only payload-free audit boundary for protected Mirror operation outcomes. */
public interface MirrorOperationAuditRepository {
    /**
     * Commits one terminal operation fact.
     *
     * @param event unpersisted payload-free event
     * @return event with database sequence and database-authoritative time
     */
    MirrorOperationAuditEvent append(MirrorOperationAuditEvent event);

    /**
     * Returns the newest events inside one exact enterprise scope.
     *
     * @param scope complete authenticated enterprise scope
     * @param limit requested result bound; implementations clamp unsafe values
     * @return newest-first immutable audit facts visible in the exact scope
     */
    List<MirrorOperationAuditEvent> recent(CapabilitySnapshot.Scope scope, int limit);
}
