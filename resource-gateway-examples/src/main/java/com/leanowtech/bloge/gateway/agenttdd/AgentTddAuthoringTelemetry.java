package com.leanowtech.bloge.gateway.agenttdd;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Low-cardinality, payload-free telemetry for the Agent DSL authoring boundary.
 *
 * <p>Only server-owned states, registered diagnostic codes and catalog tool names may become
 * labels. Tenant, project, actor, source, fingerprints, operator references and exception text
 * are deliberately absent so metrics cannot become a second business-payload channel.</p>
 */
@Component
public final class AgentTddAuthoringTelemetry {
    private static final List<String> PHASES = List.of(
            "context", "parse", "resolve", "type_check", "semantic_compile", "lint",
            "project", "round_trip", "admission", "other");
    private final MeterRegistry registry;
    private final boolean enabled;

    /** Registers the production telemetry adapter. */
    public AgentTddAuthoringTelemetry(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.enabled = true;
    }

    private AgentTddAuthoringTelemetry() {
        this.registry = null;
        this.enabled = false;
    }

    /** Returns an inert adapter for focused tests and transport-neutral embedders. */
    static AgentTddAuthoringTelemetry noop() {
        return new AgentTddAuthoringTelemetry();
    }

    /** Records one successful bounded reference response. */
    void referenceSucceeded(int encodedBytes) {
        safely(() -> {
            registry.counter("rg.dsl.reference.requests", "result", "success").increment();
            DistributionSummary.builder("rg.dsl.reference.bytes")
                    .baseUnit("bytes").register(registry).record(Math.max(0, encodedBytes));
        });
    }

    /** Records one rejected or failed reference request without retaining the failure detail. */
    void referenceFailed(boolean rejected) {
        safely(() -> registry.counter("rg.dsl.reference.requests", "result",
                rejected ? "rejected" : "failed").increment());
    }

    /** Records one terminal preview receipt and its already-sanitized diagnostics. */
    void previewCompleted(DslPreviewReceipt receipt, long durationNanos) {
        Objects.requireNonNull(receipt, "receipt");
        String acceptance = acceptance(receipt.technicalAcceptance());
        String phase = terminalPhase(receipt.stages());
        safely(() -> {
            registry.counter("rg.dsl.preview.requests", "acceptance", acceptance,
                    "phase", phase).increment();
            Timer.builder("rg.dsl.preview.duration").tag("phase", phase).register(registry)
                    .record(Duration.ofNanos(Math.max(0, durationNanos)));
            for (DslAuthoringDiagnostic diagnostic : receipt.authoringDiagnostics()) {
                registry.counter("rg.dsl.diagnostics",
                        "code", diagnosticCode(diagnostic.code()),
                        "level", level(diagnostic.level()),
                        "phase", phase(diagnostic.phase())).increment();
            }
            String status = roundTripStatus(receipt.roundTrip().status());
            List<String> driftKinds = receipt.roundTrip().driftKinds().isEmpty()
                    ? List.of("none") : receipt.roundTrip().driftKinds().stream()
                    .map(AgentTddAuthoringTelemetry::driftKind).distinct().toList();
            for (String driftKind : driftKinds) {
                registry.counter("rg.dsl.round_trip", "status", status,
                        "driftKind", driftKind).increment();
            }
        });
    }

    /** Records a preview rejection that occurs before a receipt can be created. */
    void previewRejected(String stableCode, long durationNanos) {
        boolean stale = "DSL_AUTHORING_CONTEXT_STALE".equals(stableCode);
        String acceptance = stale || "DSL_AUTHORING_CONTEXT_REQUIRED".equals(stableCode)
                ? "refetch_reference" : "rejected";
        String phase = stale || "DSL_AUTHORING_CONTEXT_REQUIRED".equals(stableCode)
                ? "context" : "admission";
        safely(() -> {
            registry.counter("rg.dsl.preview.requests", "acceptance", acceptance,
                    "phase", phase).increment();
            Timer.builder("rg.dsl.preview.duration").tag("phase", phase).register(registry)
                    .record(Duration.ofNanos(Math.max(0, durationNanos)));
            if (stale) registry.counter("rg.dsl.context.stale").increment();
        });
    }

    /** Records one pre-dispatch MCP limit rejection with server-owned labels only. */
    void limitRejected(String toolName, String reason) {
        safely(() -> registry.counter("rg.mcp.limit.rejected",
                "tool", tool(toolName), "reason", limitReason(reason)).increment());
    }

    private void safely(Runnable measurement) {
        if (!enabled) return;
        try {
            measurement.run();
        } catch (RuntimeException ignored) {
            // Observability must never change an authoring or governance result.
        }
    }

    private static String terminalPhase(List<DslPreviewReceipt.Stage> stages) {
        return stages.stream().filter(stage -> "FAIL".equals(stage.status()))
                .map(DslPreviewReceipt.Stage::phase).findFirst()
                .map(AgentTddAuthoringTelemetry::phase).orElse("round_trip");
    }

    private static String acceptance(String value) {
        return switch (normalized(value)) {
            case "accepted", "revise", "refetch_reference", "platform_defect" -> normalized(value);
            default -> "other";
        };
    }

    private static String phase(String value) {
        String normalized = normalized(value);
        return PHASES.contains(normalized) ? normalized : "other";
    }

    private static String level(String value) {
        return switch (normalized(value)) {
            case "error", "warning", "info" -> normalized(value);
            default -> "other";
        };
    }

    private static String diagnosticCode(String value) {
        return switch (value == null ? "" : value) {
            case "DSL_PARSE_ERROR", "DSL_ROOT_UNSUPPORTED", "DSL_IDENTIFIER_RESERVED",
                    "DSL_OPERATOR_NOT_FOUND", "DSL_FUNCTION_NOT_FOUND", "DSL_SCHEMA_NOT_FOUND",
                    "DSL_INPUT_PORT_UNKNOWN", "DSL_OUTPUT_PORT_UNKNOWN", "DSL_REQUIRED_INPUT_MISSING",
                    "DSL_TYPE_MISMATCH", "DSL_EFFECT_NOT_ALLOWED", "DSL_DECISION_UNIQUE_OVERLAP",
                    "DSL_DECISION_OTHERWISE_REQUIRED", "DSL_LINT_RULE_FAILED",
                    "DSL_PROJECTION_UNSUPPORTED", "DSL_ROUND_TRIP_DRIFT",
                    "DSL_ROUND_TRIP_DEFERRED_DESIGN_ONLY", "DSL_DIAGNOSTIC_UNCLASSIFIED" -> normalized(value);
            default -> "other";
        };
    }

    private static String roundTripStatus(String value) {
        return switch (normalized(value)) {
            case "supported", "drift", "partial", "not_assessed", "deferred_design_only" -> normalized(value);
            default -> "other";
        };
    }

    private static String driftKind(String value) {
        return switch (normalized(value)) {
            case "none", "semantic_projection", "design_only_operator" -> normalized(value);
            default -> "other";
        };
    }

    private static String limitReason(String value) {
        return switch (normalized(value)) {
            case "rate", "rate_capacity", "concurrency", "concurrency_capacity" -> normalized(value);
            default -> "other";
        };
    }

    private static String tool(String value) {
        if (value == null || value.length() > 96 || !value.matches("rg\\.[a-zA-Z0-9._-]+")) return "other";
        return value;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
