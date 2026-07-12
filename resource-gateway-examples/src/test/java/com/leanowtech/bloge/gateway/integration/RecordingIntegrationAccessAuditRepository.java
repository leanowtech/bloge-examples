package com.leanowtech.bloge.gateway.integration;

import java.util.ArrayList;
import java.util.List;

final class RecordingIntegrationAccessAuditRepository implements IntegrationAccessAuditRepository {
    private final List<IntegrationAccessAuditRecord> records = new ArrayList<>();

    @Override
    public IntegrationAccessAuditRecord append(IntegrationAccessAuditRecord record) {
        IntegrationAccessAuditRecord stored = record.withSequence(records.size() + 1L);
        records.add(stored);
        return stored;
    }

    @Override
    public List<IntegrationAccessAuditRecord> recent(int limit) {
        int from = Math.max(0, records.size() - Math.max(1, limit));
        List<IntegrationAccessAuditRecord> result = new ArrayList<>(records.subList(from, records.size()));
        java.util.Collections.reverse(result);
        return List.copyOf(result);
    }
}
