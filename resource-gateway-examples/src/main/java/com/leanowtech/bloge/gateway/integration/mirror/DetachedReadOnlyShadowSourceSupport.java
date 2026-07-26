package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;

/** Shared exact-binding checks for detached baseline and candidate connectors. */
final class DetachedReadOnlyShadowSourceSupport {
    private DetachedReadOnlyShadowSourceSupport() {
    }

    static ReadOnlyShadowSourceBinding resolve(
            ReadOnlyShadowConnectorInvocation invocation,
            ReadOnlyShadowSourceBindingService bindings,
            PayloadFreeEqualityReadOnlyShadowPolicy policy,
            Instant resolvedAt) {
        ReadOnlyShadowConnectorInvocation exact =
                Objects.requireNonNull(invocation, "invocation");
        Instant terminal =
                Objects.requireNonNull(resolvedAt, "resolvedAt");
        ReadOnlyShadowJobRequest request = exact.request();
        if (!ReadOnlyShadowJobRequest.V2_SCHEMA_VERSION.equals(
                request.schemaVersion())
                || request.effectiveSourceMode()
                != ReadOnlyShadowJobRequest.SourceMode
                .DETACHED_EVIDENCE
                || request.sourceBindingRef() == null
                || terminal.isBefore(exact.startedAt())
                || !exact.deadlineAt().isAfter(terminal)) {
            throw new IllegalArgumentException(
                    "detached Shadow connector invocation is invalid");
        }
        policy.requirePolicy(
                request.comparisonPolicyRef());
        ReadOnlyShadowSourceBinding binding =
                bindings.resolve(
                        request.scope(),
                        request.sourceBindingRef(),
                        terminal);
        if (!request.scope().equals(binding.scope())
                || !request.scenarioCaseRef().equals(
                binding.scenarioCaseRef())
                || !request.targetCapabilityRef().equals(
                binding.targetCapabilityRef())
                || !request.candidatePlanRef().equals(
                binding.candidatePlanRef())
                || !request.baselineBindingRef().equals(
                binding.baselineBindingRef())
                || !request.comparisonPolicyRef().equals(
                binding.comparisonPolicyRef())) {
            throw new IllegalArgumentException(
                    "detached source binding differs from the durable request");
        }
        return binding;
    }
}
