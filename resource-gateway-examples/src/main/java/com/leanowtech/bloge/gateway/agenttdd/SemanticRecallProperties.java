package com.leanowtech.bloge.gateway.agenttdd;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binds the independently reversible rollout controls for the business semantic-recall surface.
 *
 * <p>The checked-in defaults represent the completed v1.4.6 deployment: business discovery,
 * journey enforcement and controlled tests are active, headerless callers retain their temporary
 * compatibility path, legacy Feature contracts remain readable and writable, and the optional
 * semantic ranker remains unavailable. Each flag can still be rolled back without deleting
 * governed assets.</p>
 */
@Component
@ConfigurationProperties(prefix = "gateway.agent-tdd.semantic-recall", ignoreUnknownFields = false)
public final class SemanticRecallProperties {
    private boolean enabled = true;
    private boolean requireSurface;
    private boolean enforceJourneyActions = true;
    private boolean controlledBusinessTestsEnabled = true;
    private boolean allowLegacyFeatureContract = true;
    private boolean semanticRankerEnabled;

    /** @return whether the v1.4.6 business discovery and journey tools are published */
    public boolean isEnabled() {
        return enabled;
    }

    /** @param enabled whether the v1.4.6 business discovery and journey tools are published */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** @return whether requests that omit {@code X-RG-Surface} fail closed */
    public boolean isRequireSurface() {
        return requireSurface;
    }

    /** @param requireSurface whether requests that omit {@code X-RG-Surface} fail closed */
    public void setRequireSurface(boolean requireSurface) {
        this.requireSurface = requireSurface;
    }

    /** @return whether business mutations require a current journey action envelope */
    public boolean isEnforceJourneyActions() {
        return enforceJourneyActions;
    }

    /** @param enforceJourneyActions whether business mutations require a journey action envelope */
    public void setEnforceJourneyActions(boolean enforceJourneyActions) {
        this.enforceJourneyActions = enforceJourneyActions;
    }

    /** @return whether controlled business GOLDEN execution is available on the business surface */
    public boolean isControlledBusinessTestsEnabled() {
        return controlledBusinessTestsEnabled;
    }

    /** @param enabled whether controlled business GOLDEN execution is available */
    public void setControlledBusinessTestsEnabled(boolean enabled) {
        this.controlledBusinessTestsEnabled = enabled;
    }

    /** @return whether a new Feature may omit its structured business definition */
    public boolean isAllowLegacyFeatureContract() {
        return allowLegacyFeatureContract;
    }

    /** @param allowed whether a new Feature may omit its structured business definition */
    public void setAllowLegacyFeatureContract(boolean allowed) {
        this.allowLegacyFeatureContract = allowed;
    }

    /** @return whether the optional semantic ranker was requested */
    public boolean isSemanticRankerEnabled() {
        return semanticRankerEnabled;
    }

    /** @param enabled whether the optional semantic ranker is requested */
    public void setSemanticRankerEnabled(boolean enabled) {
        this.semanticRankerEnabled = enabled;
    }

    /**
     * Returns the payload-free effective rollout state advertised through MCP initialization.
     *
     * <p>No independent semantic ranker exists in v1.4.6. Its configured value is therefore
     * reported separately from its effective state instead of claiming that deterministic contract
     * matching changed implementation.</p>
     *
     * @return stable, low-cardinality rollout readiness fields
     */
    public Map<String, Object> readiness() {
        LinkedHashMap<String, Object> state = new LinkedHashMap<>();
        state.put("enabled", enabled);
        state.put("requireSurface", requireSurface);
        state.put("enforceJourneyActions", enforceJourneyActions);
        state.put("controlledBusinessTestsEnabled", controlledBusinessTestsEnabled);
        state.put("allowLegacyFeatureContract", allowLegacyFeatureContract);
        state.put("semanticRankerConfigured", semanticRankerEnabled);
        state.put("semanticRankerEffective", false);
        state.put("semanticRankerState", semanticRankerEnabled ? "NOT_AVAILABLE" : "DISABLED");
        return Map.copyOf(state);
    }
}
