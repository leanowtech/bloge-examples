package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.util.List;
import java.util.Optional;

/**
 * Resolves exact Operator and built-in Function contracts for caller-directed simulation.
 *
 * <p>The authority accepts immutable coordinates only. Implementations must return empty when any
 * library/catalog revision, signature, contract or runtime fingerprint drifts. Runtime invocation
 * keys and Fixture material are deliberately outside this static authority.</p>
 */
public interface ComponentSimulationAuthorityV2 {
    /** Resolves one exact component contract in the trusted authoring scope. */
    Optional<ComponentContract> resolve(AuthoringScope scope, ExactFixtureSubjectRefV2 subject);

    /** Schema boundary plus compiler-owned stable function call sites. */
    record ComponentContract(SchemaEnvelope input, SchemaEnvelope output, List<CallSite> callSites) {
        public ComponentContract {
            if (input == null || output == null) {
                throw new IllegalArgumentException("component contract is incomplete");
            }
            callSites = callSites == null ? List.of() : List.copyOf(callSites);
            if (callSites.stream().map(CallSite::callSiteId).distinct().count() != callSites.size()) {
                throw new IllegalArgumentException("component call-site authority is ambiguous");
            }
        }
        @Override public List<CallSite> callSites() { return List.copyOf(callSites); }
    }

    /**
     * One compiler-owned static call site.
     *
     * <p>{@code callSiteId} is a persisted semantic identity. Source positions and AST indexes are
     * diagnostic metadata and must never be used as this wire identity.</p>
     */
    record CallSite(String callSiteId, ExactFixtureSubjectRefV2.BuiltinFunctionVersion callable,
                    SchemaEnvelope input, SchemaEnvelope output, String semanticFingerprint) {
        public CallSite {
            if (callSiteId == null || callSiteId.isBlank() || callable == null || input == null
                    || output == null || semanticFingerprint == null
                    || !ExactFixtureSubjectRefV2.FINGERPRINT.matcher(semanticFingerprint).matches()) {
                throw new IllegalArgumentException("component call site is incomplete");
            }
        }
    }
}
