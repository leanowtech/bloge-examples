package com.leanowtech.bloge.gateway.authoring.workspace;

import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Deterministic in-memory receipt store used by direct tests and lightweight hosts. */
public final class InMemoryWorkspaceForkReceiptRepository implements WorkspaceForkReceiptRepository {

    private final Map<String, StoredWorkspaceForkReceipt> receipts = new ConcurrentHashMap<>();

    @Override
    public Optional<StoredWorkspaceForkReceipt> find(
            ScenarioDraftSet.EnterpriseScope scope,
            String idempotencyKey) {
        return Optional.ofNullable(receipts.get(key(scope, idempotencyKey)));
    }

    @Override
    public StoredWorkspaceForkReceipt saveIfAbsent(
            ScenarioDraftSet.EnterpriseScope scope,
            String idempotencyKey,
            StoredWorkspaceForkReceipt receipt) {
        return receipts.computeIfAbsent(key(scope, idempotencyKey), ignored -> receipt);
    }

    private static String key(ScenarioDraftSet.EnterpriseScope scope, String idempotencyKey) {
        return String.join("\u001f", scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environment(), scope.region(), idempotencyKey);
    }
}
