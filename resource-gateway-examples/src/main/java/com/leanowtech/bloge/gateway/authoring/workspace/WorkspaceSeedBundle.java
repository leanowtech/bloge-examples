package com.leanowtech.bloge.gateway.authoring.workspace;

import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import java.util.List;
import java.util.Locale;

/**
 * Versioned, self-contained starting point for a runnable visual authoring workspace.
 *
 * <p>The v1 aggregate carries one Graph and one primary Scenario suite. Fixtures remain embedded
 * in Graph node fixtures or Scenario dependency behaviors, while {@code fixtureRefs} provides a
 * stable inventory for capability previews and fork receipts.</p>
 */
public record WorkspaceSeedBundle(
        String schemaVersion,
        TemplateIdentity template,
        GraphDraft graphDraft,
        List<ScenarioDraftSet> scenarioDraftSets,
        List<String> fixtureRefs,
        RuntimeProfile runtimeProfile,
        String proofStrength,
        List<String> capabilities,
        List<String> missingCapabilities
) {
    /** Current seed protocol version. */
    public static final String SCHEMA_VERSION = "bloge.workspaceSeedBundle.v1";

    /** Normalizes optional collections while retaining fail-closed capability information. */
    public WorkspaceSeedBundle {
        schemaVersion = normalized(schemaVersion, SCHEMA_VERSION);
        template = template == null ? new TemplateIdentity("", "", "") : template;
        scenarioDraftSets = scenarioDraftSets == null ? List.of() : List.copyOf(scenarioDraftSets);
        fixtureRefs = normalizedList(fixtureRefs);
        runtimeProfile = runtimeProfile == null ? RuntimeProfile.empty() : runtimeProfile;
        proofStrength = normalized(proofStrength, "EXPLORATORY").toUpperCase(Locale.ROOT);
        capabilities = normalizedList(capabilities);
        missingCapabilities = normalizedList(missingCapabilities);
    }

    /** Human-readable template identity independent from durable workspace coordinates. */
    public record TemplateIdentity(String templateId, String version, String label) {
        public TemplateIdentity {
            templateId = normalized(templateId, "");
            version = normalized(version, "1.0.0");
            label = normalized(label, templateId);
        }
    }

    /** Runtime and mock posture advertised before a user loads or forks the seed. */
    public record RuntimeProfile(
            String mode,
            boolean sandboxRunnable,
            boolean liveDependencies,
            List<String> mockedOperatorRefs
    ) {
        public RuntimeProfile {
            mode = normalized(mode, "SANDBOX_MOCK").toUpperCase(Locale.ROOT);
            mockedOperatorRefs = normalizedList(mockedOperatorRefs);
        }

        public static RuntimeProfile empty() {
            return new RuntimeProfile("SANDBOX_MOCK", false, false, List.of());
        }
    }

    private static List<String> normalizedList(List<String> values) {
        return values == null ? List.of() : values.stream()
                .map(value -> normalized(value, ""))
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
