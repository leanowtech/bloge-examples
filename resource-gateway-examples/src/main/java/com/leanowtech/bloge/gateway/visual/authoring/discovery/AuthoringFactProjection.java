package com.leanowtech.bloge.gateway.visual.authoring.discovery;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.leanowtech.bloge.gateway.visual.authoring.model.VisualLibraryAuthoringDocument;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Source-neutral, fingerprinted facts discovered from an existing business asset.
 *
 * <p>The projection deliberately separates source facts from runtime parity. A DSL usage
 * observation can therefore be rendered without pretending that its schemas or runtime binding
 * are known, while a capability catalog can carry declared contracts and still fail the runtime
 * gate independently.</p>
 */
public record AuthoringFactProjection(
        String schemaVersion,
        String sourceKind,
        String sourceId,
        String sourceFingerprint,
        String projectionFingerprint,
        boolean accepted,
        Summary summary,
        List<Fact> facts,
        List<RuntimeParity> runtimeParity,
        List<ReviewItem> reviewItems,
        List<VisualDiagnostic> diagnostics,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        VisualLibraryAuthoringDocument authoringDocument
) {
    public static final String SCHEMA_VERSION = "bloge.visualAuthoringFactProjection.v1";

    public AuthoringFactProjection {
        schemaVersion = normalized(schemaVersion, SCHEMA_VERSION);
        sourceKind = normalized(sourceKind, "UNKNOWN").toUpperCase();
        sourceId = normalized(sourceId, "");
        sourceFingerprint = normalized(sourceFingerprint, "");
        projectionFingerprint = normalized(projectionFingerprint, "");
        summary = summary == null ? Summary.empty() : summary;
        facts = immutable(facts);
        runtimeParity = immutable(runtimeParity);
        reviewItems = immutable(reviewItems);
        diagnostics = immutable(diagnostics);
    }

    /** Returns a copy carrying the canonical projection fingerprint. */
    public AuthoringFactProjection withProjectionFingerprint(String fingerprint) {
        return new AuthoringFactProjection(
                schemaVersion,
                sourceKind,
                sourceId,
                sourceFingerprint,
                fingerprint,
                accepted,
                summary,
                facts,
                runtimeParity,
                reviewItems,
                diagnostics,
                authoringDocument
        );
    }

    /**
     * Aggregate counts for fast UI rendering and integration gating.
     */
    public record Summary(
            int operatorFactCount,
            int functionFactCount,
            int graphFactCount,
            int boundCount,
            int driftedCount,
            int unresolvedCount,
            boolean runtimeReady
    ) {
        public Summary {
            operatorFactCount = nonNegative(operatorFactCount);
            functionFactCount = nonNegative(functionFactCount);
            graphFactCount = nonNegative(graphFactCount);
            boundCount = nonNegative(boundCount);
            driftedCount = nonNegative(driftedCount);
            unresolvedCount = nonNegative(unresolvedCount);
            runtimeReady = runtimeReady
                    && unresolvedCount == 0
                    && driftedCount == 0
                    && boundCount > 0;
        }

        public static Summary empty() {
            return new Summary(0, 0, 0, 0, 0, 0, false);
        }
    }

    /**
     * One normalized declaration, usage observation, or runtime inventory fact.
     */
    public record Fact(
            String factId,
            String assetKind,
            String assetRef,
            String factKind,
            String evidenceLevel,
            String contractFingerprint,
            String sourcePath,
            int occurrences,
            List<String> dependencies,
            Map<String, Object> attributes
    ) {
        public Fact {
            factId = normalized(factId, "");
            assetKind = normalized(assetKind, "UNKNOWN").toUpperCase();
            assetRef = normalized(assetRef, "");
            factKind = normalized(factKind, "DECLARATION").toUpperCase();
            evidenceLevel = normalized(evidenceLevel, "UNKNOWN").toUpperCase();
            contractFingerprint = normalized(contractFingerprint, "");
            sourcePath = normalized(sourcePath, "/");
            occurrences = Math.max(1, occurrences);
            dependencies = immutableStrings(dependencies);
            attributes = attributes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(attributes));
        }
    }

    /**
     * Comparison between one declared/observed asset and the target runtime inventory.
     */
    public record RuntimeParity(
            String assetKind,
            String assetRef,
            String runtimeProfile,
            String state,
            boolean executableReady,
            String declaredFingerprint,
            String runtimeFingerprint,
            String reasonCode,
            String message
    ) {
        public RuntimeParity {
            assetKind = normalized(assetKind, "UNKNOWN").toUpperCase();
            assetRef = normalized(assetRef, "");
            runtimeProfile = normalized(runtimeProfile, "");
            state = normalized(state, "UNKNOWN").toUpperCase();
            declaredFingerprint = normalized(declaredFingerprint, "");
            runtimeFingerprint = normalized(runtimeFingerprint, "");
            reasonCode = normalized(reasonCode, "");
            message = normalized(message, "");
            executableReady = executableReady && "BOUND".equals(state);
        }

        public boolean unresolved() {
            return !executableReady;
        }
    }

    /**
     * Human action produced by a source adapter or parity gate.
     */
    public record ReviewItem(
            String code,
            String level,
            String assetKind,
            String assetRef,
            String message,
            String action
    ) {
        public ReviewItem {
            code = normalized(code, "");
            level = normalized(level, "INFO").toUpperCase();
            assetKind = normalized(assetKind, "UNKNOWN").toUpperCase();
            assetRef = normalized(assetRef, "");
            message = normalized(message, "");
            action = normalized(action, "");
        }
    }

    private static int nonNegative(int value) {
        return Math.max(0, value);
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static List<String> immutableStrings(List<String> values) {
        return values == null
                ? List.of()
                : values.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(String::trim)
                        .distinct()
                        .sorted()
                        .toList();
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
