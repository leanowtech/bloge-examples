package com.leanowtech.bloge.gateway.testing.correctness.compilation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.CompilationCoordinate;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Payload-free result of compiling one exact correctness authoring closure. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CorrectnessCompilationReport(
        String schemaVersion,
        boolean publishable,
        String compilerVersion,
        CompilationCoordinate coordinate,
        String compilationFingerprint,
        List<SourceMapping> sourceMap,
        List<CompiledAssetSummary> compiledAssets,
        List<Diagnostic> diagnostics,
        ExecutionRiskSummary riskSummary
) {
    public static final String SCHEMA_VERSION = "bloge.correctnessCompilationReport.v1";

    public CorrectnessCompilationReport {
        schemaVersion = version(schemaVersion);
        compilerVersion = required(compilerVersion, "compilerVersion");
        if (coordinate == null) throw new IllegalArgumentException("coordinate is required");
        compilationFingerprint = fingerprint(
                compilationFingerprint, "compilationFingerprint");
        sourceMap = sourceMap == null ? List.of() : sourceMap.stream()
                .distinct().sorted(SourceMapping.ORDER).toList();
        compiledAssets = compiledAssets == null ? List.of() : compiledAssets.stream()
                .distinct().sorted(CompiledAssetSummary.ORDER).toList();
        diagnostics = diagnostics == null ? List.of() : diagnostics.stream()
                .distinct().sorted(Diagnostic.ORDER).toList();
        riskSummary = riskSummary == null ? ExecutionRiskSummary.none() : riskSummary;
        boolean hasErrors = diagnostics.stream().anyMatch(diagnostic ->
                diagnostic.severity() == DiagnosticSeverity.ERROR);
        if (publishable && (hasErrors || compiledAssets.isEmpty())) {
            throw new IllegalArgumentException(
                    "Publishable compilation requires compiled assets and no errors");
        }
        if (!publishable && !compiledAssets.isEmpty()) {
            throw new IllegalArgumentException(
                    "Blocked compilation must not expose partial compiled assets");
        }
    }

    public enum DiagnosticSeverity { INFO, WARNING, ERROR }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SourceCoordinate(
            ExactAssetRef assetRef,
            String elementKind,
            String elementId
    ) {
        public SourceCoordinate {
            if (assetRef == null) throw new IllegalArgumentException("source assetRef is required");
            elementKind = required(elementKind, "elementKind").toUpperCase(Locale.ROOT);
            elementId = required(elementId, "elementId");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OutputCoordinate(
            ExactAssetRef assetRef,
            String elementKind,
            String elementId
    ) {
        public OutputCoordinate {
            if (assetRef == null) throw new IllegalArgumentException("output assetRef is required");
            elementKind = required(elementKind, "elementKind").toUpperCase(Locale.ROOT);
            elementId = required(elementId, "elementId");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SourceMapping(SourceCoordinate source, OutputCoordinate output) {
        private static final Comparator<SourceMapping> ORDER = Comparator
                .comparing((SourceMapping value) -> value.source().assetRef().kind())
                .thenComparing(value -> value.source().assetRef().id())
                .thenComparingLong(value -> value.source().assetRef().revision())
                .thenComparing(value -> value.source().elementKind())
                .thenComparing(value -> value.source().elementId())
                .thenComparing(value -> value.output().assetRef().kind())
                .thenComparing(value -> value.output().assetRef().id())
                .thenComparing(value -> value.output().elementKind())
                .thenComparing(value -> value.output().elementId());

        public SourceMapping {
            if (source == null || output == null) {
                throw new IllegalArgumentException("Source and output coordinates are required");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CompiledAssetSummary(
            ExactAssetRef assetRef,
            int sourceElementCount
    ) {
        private static final Comparator<CompiledAssetSummary> ORDER = Comparator
                .comparing((CompiledAssetSummary value) -> value.assetRef().kind())
                .thenComparing(value -> value.assetRef().id())
                .thenComparingLong(value -> value.assetRef().revision());

        public CompiledAssetSummary {
            if (assetRef == null || sourceElementCount < 1) {
                throw new IllegalArgumentException(
                        "Compiled asset ref and positive sourceElementCount are required");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Diagnostic(
            DiagnosticSeverity severity,
            String code,
            ExactAssetRef assetRef,
            String fieldPath,
            String messageId
    ) {
        private static final Comparator<Diagnostic> ORDER = Comparator
                .comparing(Diagnostic::severity)
                .thenComparing(Diagnostic::code)
                .thenComparing(value -> value.assetRef() == null ? "" : value.assetRef().kind())
                .thenComparing(value -> value.assetRef() == null ? "" : value.assetRef().id())
                .thenComparing(Diagnostic::fieldPath);

        public Diagnostic {
            if (severity == null) throw new IllegalArgumentException("severity is required");
            code = required(code, "code");
            fieldPath = fieldPath == null ? "" : fieldPath.trim();
            messageId = required(messageId, "messageId");
        }

        public static Diagnostic error(
                String code, ExactAssetRef assetRef, String fieldPath, String messageId) {
            return new Diagnostic(DiagnosticSeverity.ERROR, code, assetRef, fieldPath, messageId);
        }

        public static Diagnostic warning(
                String code, ExactAssetRef assetRef, String fieldPath, String messageId) {
            return new Diagnostic(
                    DiagnosticSeverity.WARNING, code, assetRef, fieldPath, messageId);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExecutionRiskSummary(
            int realDependencyCount,
            int controlledDependencyCount,
            int faultDependencyCount,
            int deniedDependencyCount,
            int fallbackToRealCount,
            int transportBoundaryCount,
            boolean logicalClockRequired,
            List<String> riskCodes
    ) {
        public ExecutionRiskSummary {
            if (realDependencyCount < 0 || controlledDependencyCount < 0
                    || faultDependencyCount < 0 || deniedDependencyCount < 0
                    || fallbackToRealCount < 0 || transportBoundaryCount < 0) {
                throw new IllegalArgumentException("Execution risk counters must not be negative");
            }
            riskCodes = riskCodes == null ? List.of() : riskCodes.stream()
                    .map(value -> required(value, "riskCode"))
                    .distinct().sorted().toList();
        }

        public static ExecutionRiskSummary none() {
            return new ExecutionRiskSummary(0, 0, 0, 0, 0, 0, false, List.of());
        }
    }

    private static String version(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) return SCHEMA_VERSION;
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException("Unsupported compilation report schemaVersion");
        }
        return normalized;
    }

    private static String fingerprint(String value, String field) {
        String normalized = required(value, field);
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be an exact SHA-256 fingerprint");
        }
        return normalized;
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
