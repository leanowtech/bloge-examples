package com.leanowtech.bloge.gateway.testing.world.draft;

/** Payload-free audit port for candidate and vault lifecycle operations. */
@FunctionalInterface
public interface WorldDraftAuditSink {
    void record(String tenantId, String candidateId, String operation, long revision, boolean success);

    static WorldDraftAuditSink noop() { return (tenant, candidate, operation, revision, success) -> { }; }
}
