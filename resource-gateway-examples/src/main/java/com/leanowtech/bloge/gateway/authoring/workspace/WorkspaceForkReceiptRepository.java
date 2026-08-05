package com.leanowtech.bloge.gateway.authoring.workspace;

import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet;

import java.util.Optional;

/** Durable idempotency boundary for Workspace fork receipts. */
public interface WorkspaceForkReceiptRepository {

    Optional<StoredWorkspaceForkReceipt> find(
            ScenarioDraftSet.EnterpriseScope scope,
            String idempotencyKey);

    StoredWorkspaceForkReceipt saveIfAbsent(
            ScenarioDraftSet.EnterpriseScope scope,
            String idempotencyKey,
            StoredWorkspaceForkReceipt receipt);
}
