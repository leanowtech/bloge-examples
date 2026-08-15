package com.leanowtech.bloge.gateway.testing.correctness.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;

import java.util.Comparator;
import java.util.List;

import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.exactFingerprint;
import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.required;
import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.protocolVersion;
import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.trimmed;

/** Immutable manifest joining business authoring truth to compiled testing assets. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CorrectnessPublication(
        String schemaVersion,
        String publicationId,
        EnterpriseScope scope,
        ExactTargetRef target,
        ExactAssetRef definitionRef,
        ExactAssetRef inventoryRef,
        ExactAssetRef scenarioDraftSetRef,
        List<ExactAssetRef> oracleRefs,
        List<ExactAssetRef> assertionSetRefs,
        List<ExactAssetRef> fixtureAssetRefs,
        List<ExactAssetRef> compiledFixtureBundleRefs,
        ExactAssetRef compiledTestSuiteRef,
        String compilerVersion,
        String compilationFingerprint,
        AuditMetadata metadata
) {
    public static final String SCHEMA_VERSION = "bloge.correctnessPublication.v1";

    public CorrectnessPublication {
        schemaVersion = protocolVersion(schemaVersion, SCHEMA_VERSION);
        publicationId = required(publicationId, "publicationId");
        scope = required(scope, "scope");
        target = required(target, "target");
        definitionRef = required(definitionRef, "definitionRef");
        inventoryRef = required(inventoryRef, "inventoryRef");
        scenarioDraftSetRef = required(scenarioDraftSetRef, "scenarioDraftSetRef");
        oracleRefs = refs(oracleRefs);
        assertionSetRefs = refs(assertionSetRefs);
        fixtureAssetRefs = refs(fixtureAssetRefs);
        compiledFixtureBundleRefs = refs(compiledFixtureBundleRefs);
        compiledTestSuiteRef = required(compiledTestSuiteRef, "compiledTestSuiteRef");
        compilerVersion = required(compilerVersion, "compilerVersion");
        compilationFingerprint = exactFingerprint(
                compilationFingerprint, "compilationFingerprint");
        metadata = required(metadata, "metadata");
        if (oracleRefs.isEmpty() || assertionSetRefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "Correctness Publication requires Oracle and Assertion Set closure");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PublicationAttempt(
            String schemaVersion,
            String attemptId,
            long stateVersion,
            String idempotencyKeyFingerprint,
            CompilationCoordinate coordinate,
            AttemptStage stage,
            List<ExactAssetRef> verifiedAssets,
            Failure failure,
            AuditMetadata metadata
    ) {
        public static final String SCHEMA_VERSION = "bloge.correctnessPublicationAttempt.v1";

        public PublicationAttempt {
            schemaVersion = protocolVersion(schemaVersion, SCHEMA_VERSION);
            attemptId = required(attemptId, "attemptId");
            if (stateVersion < 1) throw new IllegalArgumentException("stateVersion must be positive");
            idempotencyKeyFingerprint = exactFingerprint(
                    idempotencyKeyFingerprint, "idempotencyKeyFingerprint");
            coordinate = required(coordinate, "coordinate");
            stage = stage == null ? AttemptStage.PREPARING : stage;
            verifiedAssets = refs(verifiedAssets);
            failure = failure == null ? Failure.none() : failure;
            metadata = required(metadata, "metadata");
            if (stage == AttemptStage.FAILED && failure.code().isEmpty()) {
                throw new IllegalArgumentException("Failed publication attempt requires a failure code");
            }
            if (stage != AttemptStage.FAILED && !failure.code().isEmpty()) {
                throw new IllegalArgumentException("Only failed publication attempt may carry failure");
            }
        }
    }

    public enum AttemptStage { PREPARING, COMPILED, REGISTERING, COMMITTED, FAILED }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CompilationCoordinate(
            ExactAssetRef definitionRef,
            ExactAssetRef inventoryRef,
            ExactAssetRef scenarioDraftSetRef
    ) {
        public CompilationCoordinate {
            definitionRef = required(definitionRef, "definitionRef");
            inventoryRef = required(inventoryRef, "inventoryRef");
            scenarioDraftSetRef = required(scenarioDraftSetRef, "scenarioDraftSetRef");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Failure(String code, boolean retryable) {
        public Failure {
            code = trimmed(code);
        }

        public static Failure none() {
            return new Failure("", false);
        }
    }

    private static List<ExactAssetRef> refs(List<ExactAssetRef> values) {
        return values == null ? List.of() : values.stream()
                .distinct()
                .sorted(Comparator.comparing(ExactAssetRef::kind)
                        .thenComparing(ExactAssetRef::id)
                        .thenComparingLong(ExactAssetRef::revision))
                .toList();
    }
}
