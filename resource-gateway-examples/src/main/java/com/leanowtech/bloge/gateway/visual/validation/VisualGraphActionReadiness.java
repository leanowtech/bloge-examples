package com.leanowtech.bloge.gateway.visual.validation;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Server-derived action gate summary for visual graph authoring.
 *
 * <p>{@link VisualGraphReadiness} explains what kind of graph the draft is. This contract explains what the
 * control plane may do next: compile, run, publish a design artifact, or publish an executable artifact. It keeps
 * schema-only/design-only authoring explicit instead of treating non-executable graphs as generic failures.</p>
 *
 * @param schemaVersion action readiness contract schema version
 * @param valid whether validation has no blocking errors
 * @param state stable machine-readable action state
 * @param level UI severity
 * @param compileNow whether compile is currently allowed
 * @param runNow whether request-response run is currently allowed
 * @param publishDesignNow whether DESIGN publication is currently allowed without warning acknowledgement
 * @param publishDesignAfterReview whether DESIGN publication is allowed after warning acknowledgement
 * @param publishExecutableNow whether EXECUTABLE publication is currently allowed without warning acknowledgement
 * @param publishExecutableAfterReview whether EXECUTABLE publication is allowed after warning acknowledgement
 * @param requiresAckWarnings whether publication requires ackWarnings=true
 * @param requiresGovernanceEvidence whether publication warning acknowledgement requires actor/reason evidence
 * @param diagnosticCount number of diagnostics used to derive the action gates
 * @param errorCount number of blocking diagnostics
 * @param warningCount number of warning diagnostics
 * @param artifactKinds publishable artifact kinds from graph readiness
 * @param blockingCodes distinct blocking diagnostic codes
 * @param warningCodes distinct warning diagnostic codes
 * @param message human-readable action state message
 * @param recommendedAction human-readable next action
 */
public record VisualGraphActionReadiness(
        String schemaVersion,
        boolean valid,
        String state,
        String level,
        boolean compileNow,
        boolean runNow,
        boolean publishDesignNow,
        boolean publishDesignAfterReview,
        boolean publishExecutableNow,
        boolean publishExecutableAfterReview,
        boolean requiresAckWarnings,
        boolean requiresGovernanceEvidence,
        int diagnosticCount,
        int errorCount,
        int warningCount,
        List<String> artifactKinds,
        List<String> blockingCodes,
        List<String> warningCodes,
        String message,
        String recommendedAction
) {
    public static final String SCHEMA_VERSION = "bloge.visualGraphActionReadiness.v1";
    public static final String RUNTIME_EXECUTABLE_READY = "runtime-executable-ready";
    public static final String DESIGN_ARTIFACT_READY = "design-artifact-ready";
    public static final String GOVERNANCE_REVIEW_REQUIRED = "governance-review-required";
    public static final String WARNING_ACK_REQUIRED = "warning-ack-required";
    public static final String RUNTIME_BINDING_REQUIRED = "runtime-binding-required";
    public static final String DRAFT_REPAIR_REQUIRED = "draft-repair-required";
    public static final String NOT_ASSESSED = "not-assessed";

    /**
     * Creates an action readiness payload.
     */
    public VisualGraphActionReadiness {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        state = normalizeState(state);
        level = level == null || level.isBlank() ? "info" : level.trim().toLowerCase(Locale.ROOT);
        diagnosticCount = Math.max(0, diagnosticCount);
        errorCount = Math.max(0, errorCount);
        warningCount = Math.max(0, warningCount);
        artifactKinds = artifactKinds == null ? List.of() : artifactKinds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        blockingCodes = normalizedCodes(blockingCodes);
        warningCodes = normalizedCodes(warningCodes);
        message = message == null ? "" : message;
        recommendedAction = recommendedAction == null ? "" : recommendedAction;
    }

    /**
     * Builds action gates from validation diagnostics and graph readiness.
     *
     * @param valid validation result validity
     * @param diagnostics validation diagnostics
     * @param readiness graph readiness
     * @return action readiness
     */
    public static VisualGraphActionReadiness from(boolean valid,
                                                  List<VisualDiagnostic> diagnostics,
                                                  VisualGraphReadiness readiness) {
        if (readiness == null || VisualGraphReadiness.NOT_ASSESSED.equals(readiness.state())) {
            return notAssessed(diagnostics);
        }
        List<VisualDiagnostic> safeDiagnostics = diagnostics == null ? List.of() : diagnostics;
        int errorCount = Math.toIntExact(safeDiagnostics.stream().filter(VisualDiagnostic::error).count());
        int warningCount = Math.toIntExact(safeDiagnostics.stream()
                .filter(diagnostic -> "WARNING".equalsIgnoreCase(diagnostic.level()))
                .count());
        boolean validationValid = valid && errorCount == 0;
        List<String> artifactKinds = readiness.artifactKinds();
        boolean designArtifact = containsArtifactKind(artifactKinds, "DESIGN");
        boolean executableArtifact = containsArtifactKind(artifactKinds, "EXECUTABLE") && readiness.executable();
        boolean warningsBlockPublication = validationValid
                && warningCount > 0
                && (designArtifact || executableArtifact);
        String state = state(validationValid, warningCount, readiness, designArtifact);
        String level = level(state, readiness, warningCount);
        return new VisualGraphActionReadiness(
                SCHEMA_VERSION,
                validationValid,
                state,
                level,
                validationValid && readiness.executable(),
                validationValid && readiness.executable(),
                validationValid && designArtifact && warningCount == 0,
                validationValid && designArtifact && warningCount > 0,
                validationValid && executableArtifact && warningCount == 0,
                validationValid && executableArtifact && warningCount > 0,
                warningsBlockPublication,
                warningsBlockPublication,
                safeDiagnostics.size(),
                errorCount,
                warningCount,
                artifactKinds,
                diagnosticCodes(safeDiagnostics, true),
                diagnosticCodes(safeDiagnostics, false),
                message(state),
                recommendedAction(state)
        );
    }

    private static VisualGraphActionReadiness notAssessed(List<VisualDiagnostic> diagnostics) {
        List<VisualDiagnostic> safeDiagnostics = diagnostics == null ? List.of() : diagnostics;
        int errorCount = Math.toIntExact(safeDiagnostics.stream().filter(VisualDiagnostic::error).count());
        int warningCount = Math.toIntExact(safeDiagnostics.stream()
                .filter(diagnostic -> "WARNING".equalsIgnoreCase(diagnostic.level()))
                .count());
        return new VisualGraphActionReadiness(
                SCHEMA_VERSION,
                errorCount == 0,
                NOT_ASSESSED,
                "info",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                safeDiagnostics.size(),
                errorCount,
                warningCount,
                List.of(),
                diagnosticCodes(safeDiagnostics, true),
                diagnosticCodes(safeDiagnostics, false),
                "Graph action readiness was not assessed.",
                "Run server validation to derive compile, run, and publication gates."
        );
    }

    private static String state(boolean valid,
                                int warningCount,
                                VisualGraphReadiness readiness,
                                boolean designArtifact) {
        if (!valid) {
            if (VisualGraphReadiness.RUNTIME_BLOCKED.equals(readiness.state())) {
                return RUNTIME_BINDING_REQUIRED;
            }
            return DRAFT_REPAIR_REQUIRED;
        }
        if (warningCount > 0) {
            if (readiness.governanceReviewNodeCount() > 0) {
                return GOVERNANCE_REVIEW_REQUIRED;
            }
            return WARNING_ACK_REQUIRED;
        }
        if (readiness.executable()) {
            return RUNTIME_EXECUTABLE_READY;
        }
        if (designArtifact) {
            return DESIGN_ARTIFACT_READY;
        }
        return RUNTIME_BINDING_REQUIRED;
    }

    private static String level(String state, VisualGraphReadiness readiness, int warningCount) {
        return switch (state) {
            case RUNTIME_EXECUTABLE_READY -> "success";
            case DESIGN_ARTIFACT_READY -> "info";
            case GOVERNANCE_REVIEW_REQUIRED, WARNING_ACK_REQUIRED -> "warning";
            case RUNTIME_BINDING_REQUIRED -> warningCount > 0 ? "warning" : readiness.level();
            case DRAFT_REPAIR_REQUIRED -> "error";
            default -> "info";
        };
    }

    private static String message(String state) {
        return switch (state) {
            case RUNTIME_EXECUTABLE_READY ->
                    "Graph is executable and can be published as EXECUTABLE or DESIGN.";
            case DESIGN_ARTIFACT_READY ->
                    "Graph is schema-valid and can be published as DESIGN; runtime execution is blocked until executable lowerings are bound.";
            case GOVERNANCE_REVIEW_REQUIRED ->
                    "Graph is executable, but publication requires warning acknowledgement and governance evidence.";
            case WARNING_ACK_REQUIRED ->
                    "Graph is publishable, but publication requires warning acknowledgement and governance evidence.";
            case RUNTIME_BINDING_REQUIRED ->
                    "Graph cannot be compiled or run by the current runtime until missing runtime bindings are resolved.";
            case DRAFT_REPAIR_REQUIRED ->
                    "Graph has blocking validation errors that must be repaired before compile, run, or publication.";
            default -> "Graph action readiness was not assessed.";
        };
    }

    private static String recommendedAction(String state) {
        return switch (state) {
            case RUNTIME_EXECUTABLE_READY ->
                    "Compile, run, or publish an executable artifact.";
            case DESIGN_ARTIFACT_READY ->
                    "Publish a DESIGN artifact now, then bind executable lowerings before runtime promotion.";
            case GOVERNANCE_REVIEW_REQUIRED ->
                    "Review warning diagnostics, then publish with ackWarnings=true plus actor and reason.";
            case WARNING_ACK_REQUIRED ->
                    "Acknowledge warnings with actor and reason before publication.";
            case RUNTIME_BINDING_REQUIRED ->
                    "Bind missing runtime implementations or replace unsupported operators before promotion.";
            case DRAFT_REPAIR_REQUIRED ->
                    "Repair blocking validation diagnostics and validate again.";
            default -> "Run server validation to derive action gates.";
        };
    }

    private static boolean containsArtifactKind(List<String> artifactKinds, String expected) {
        return artifactKinds != null && artifactKinds.stream()
                .anyMatch(kind -> expected.equalsIgnoreCase(kind));
    }

    private static List<String> diagnosticCodes(List<VisualDiagnostic> diagnostics, boolean errors) {
        Set<String> codes = new LinkedHashSet<>();
        for (VisualDiagnostic diagnostic : diagnostics == null ? List.<VisualDiagnostic>of() : diagnostics) {
            if (diagnostic == null || diagnostic.code().isBlank()) {
                continue;
            }
            boolean matches = errors
                    ? diagnostic.error()
                    : "WARNING".equalsIgnoreCase(diagnostic.level());
            if (matches) {
                codes.add(diagnostic.code());
            }
        }
        return List.copyOf(codes);
    }

    private static List<String> normalizedCodes(List<String> codes) {
        if (codes == null) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String code : codes) {
            if (code != null && !code.isBlank() && !normalized.contains(code.trim())) {
                normalized.add(code.trim());
            }
        }
        return List.copyOf(normalized);
    }

    private static String normalizeState(String value) {
        return String.valueOf(value == null ? "" : value)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('_', '-');
    }
}
