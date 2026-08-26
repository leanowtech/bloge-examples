package com.leanowtech.bloge.gateway.testing.world.draft;

import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.List;
import java.util.Map;

/** Payload-free result of schema-guided redaction and residual scanning. */
public record WorldDraftRedactionReport(boolean schemaValid, int unknownFieldCount,
                                        int transformedFieldCount, List<Finding> findings,
                                        String fingerprint) {
    public enum Finding { CREDENTIAL, IDENTITY, CONTACT, GEOLOCATION, FREE_TEXT, SCHEMA_INVALID }

    public WorldDraftRedactionReport {
        if (unknownFieldCount < 0 || transformedFieldCount < 0 || findings == null
                || findings.size() > 32 || fingerprint == null
                || !fingerprint.matches("sha256:[a-f0-9]{64}")) throw invalid();
        findings = findings.stream().distinct().sorted(java.util.Comparator.comparing(Enum::name)).toList();
    }

    public static WorldDraftRedactionReport of(boolean schemaValid, int unknown, int transformed,
                                               List<Finding> findings) {
        List<Finding> exact = findings == null ? List.of() : findings.stream().distinct()
                .sorted(java.util.Comparator.comparing(Enum::name)).toList();
        String fingerprint = VisualBundleFingerprint.fromMaterial(Map.of("schemaValid", schemaValid,
                "unknownFieldCount", unknown, "transformedFieldCount", transformed,
                "findings", exact.stream().map(Enum::name).toList()));
        return new WorldDraftRedactionReport(schemaValid, unknown, transformed, exact, fingerprint);
    }

    public boolean safe() { return schemaValid && findings.isEmpty(); }

    public static WorldDraftRedactionReport notProcessed() {
        return of(false, 0, 0, List.of(Finding.SCHEMA_INVALID));
    }

    private static WorldDraftCandidateException invalid() {
        return new WorldDraftCandidateException(WorldDraftCandidateException.Code.INVALID_INPUT);
    }
}
