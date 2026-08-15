package com.leanowtech.bloge.gateway.testing.correctness.compilation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.FixtureBundleRegistrationRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRegistrationRequest;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.CompiledAssetSummary;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.Diagnostic;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.ExecutionRiskSummary;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.SourceMapping;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.CompilationCoordinate;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Canonical report and fingerprint phase for deterministic correctness compilation. */
final class CorrectnessCompilationCanonicalizer {

    private static final int MAX_PROTOCOL_BYTES = 16 * 1_048_576;

    private final ObjectMapper mapper;
    private final String compilerVersion;

    CorrectnessCompilationCanonicalizer(ObjectMapper mapper, String compilerVersion) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.compilerVersion = Objects.requireNonNull(compilerVersion, "compilerVersion");
    }

    CompiledCorrectnessPlan complete(
            CompilationCoordinate coordinate,
            List<FixtureBundleRegistrationRequest> fixtures,
            TestSuiteRegistrationRequest suite,
            ExactAssetRef suiteRef,
            List<SourceMapping> sourceMap,
            List<Diagnostic> diagnostics,
            ExecutionRiskSummary risk
    ) {
        List<CompiledAssetSummary> assets = compiledAssets(fixtures, suiteRef, sourceMap);
        CorrectnessCompilationReport template = new CorrectnessCompilationReport(
                "", true, compilerVersion, coordinate, zeroFingerprint(),
                sourceMap, assets, diagnostics, risk);
        String fingerprint = reportFingerprint(template, fixtures, suite);
        CorrectnessCompilationReport report = new CorrectnessCompilationReport(
                "", true, compilerVersion, coordinate, fingerprint,
                sourceMap, assets, diagnostics, risk);
        return new CompiledCorrectnessPlan(report, fixtures, suite);
    }

    CompiledCorrectnessPlan blocked(
            CompilationCoordinate coordinate,
            List<Diagnostic> diagnostics,
            ExecutionRiskSummary risk
    ) {
        List<Diagnostic> normalized = diagnostics.isEmpty()
                ? List.of(Diagnostic.error(
                "RG.CORRECTNESS.COMPILATION_BLOCKED", coordinate.definitionRef(), "",
                "correctness.compilation.blocked"))
                : List.copyOf(diagnostics);
        CorrectnessCompilationReport template = new CorrectnessCompilationReport(
                "", false, compilerVersion, coordinate, zeroFingerprint(),
                List.of(), List.of(), normalized, risk);
        String fingerprint = reportFingerprint(template, List.of(), null);
        return new CompiledCorrectnessPlan(
                new CorrectnessCompilationReport(
                        "", false, compilerVersion, coordinate, fingerprint,
                        List.of(), List.of(), normalized, risk),
                List.of(), null);
    }

    private String reportFingerprint(
            CorrectnessCompilationReport report,
            List<FixtureBundleRegistrationRequest> fixtures,
            TestSuiteRegistrationRequest suite
    ) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", report.schemaVersion());
        material.put("publishable", report.publishable());
        material.put("compilerVersion", report.compilerVersion());
        material.put("coordinate", report.coordinate());
        material.put("sourceMap", report.sourceMap());
        material.put("compiledAssets", report.compiledAssets());
        material.put("diagnostics", report.diagnostics());
        material.put("riskSummary", report.riskSummary());
        material.put("fixtureRegistrations", fixtures);
        material.put("suiteRegistration", suite);
        return ProtocolFingerprint.ofBounded(mapper, material, MAX_PROTOCOL_BYTES);
    }

    private List<CompiledAssetSummary> compiledAssets(
            List<FixtureBundleRegistrationRequest> fixtures,
            ExactAssetRef suiteRef,
            List<SourceMapping> sourceMap
    ) {
        List<ExactAssetRef> refs = new ArrayList<>();
        for (FixtureBundleRegistrationRequest registration : fixtures) {
            FixtureBundle bundle = registration.fixtureBundle();
            refs.add(new ExactAssetRef(
                    "FIXTURE_BUNDLE", bundle.fixtureBundleId(), bundle.revision(),
                    ProtocolFingerprint.ofBounded(mapper, bundle, MAX_PROTOCOL_BYTES)));
        }
        refs.add(suiteRef);
        return refs.stream().map(ref -> new CompiledAssetSummary(
                ref, (int) sourceMap.stream()
                .filter(mapping -> mapping.output().assetRef().equals(ref)).count()))
                .toList();
    }

    private static String zeroFingerprint() {
        return "sha256:" + "0".repeat(64);
    }
}
