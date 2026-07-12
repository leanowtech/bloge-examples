package com.leanowtech.bloge.gateway.visual.persistence;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Keeps visual read-cache mutations behind the database commit boundary. */
public final class TransactionCommitActions {
    private TransactionCommitActions() {
    }

    public static void afterCommitOrNow(Runnable action) {
        if (action == null) {
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
