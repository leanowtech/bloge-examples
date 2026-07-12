package com.leanowtech.bloge.gateway.integration;

import java.util.List;

/** Append-only security audit sink for integration authentication decisions. */
public interface IntegrationAccessAuditRepository {
    IntegrationAccessAuditRecord append(IntegrationAccessAuditRecord record);

    List<IntegrationAccessAuditRecord> recent(int limit);
}
