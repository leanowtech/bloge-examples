package com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Immutable all-slots staging request. A batch is the atomic recovery unit. */
public record PendingSecretBatch(PendingSecretLease lease, List<PendingSecretOperation> operations) {
    public PendingSecretBatch {
        Objects.requireNonNull(lease, "lease");
        operations = Objects.requireNonNull(operations, "operations").stream()
                .sorted(java.util.Comparator.comparing(PendingSecretOperation::slot))
                .toList();
        if (operations.isEmpty()) throw new IllegalArgumentException("operations are required");
        Set<String> slots = operations.stream().map(PendingSecretOperation::slot).collect(Collectors.toUnmodifiableSet());
        if (slots.size() != operations.size()) throw new IllegalArgumentException("duplicate secret slot");
    }

    /** Internal slot lookup without exposing a mutable collection. */
    @JsonIgnore public PendingSecretOperation operation(String slot) {
        return operations.stream().filter(operation -> operation.slot().equals(slot)).findFirst().orElse(null);
    }

    @Override public String toString() {
        return "PendingSecretBatch[lease=" + lease + ", slots="
                + operations.stream().map(PendingSecretOperation::slot).sorted().toList() + "]";
    }
}
