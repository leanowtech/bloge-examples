package com.leanowtech.bloge.gateway.testing.correctness.oracle;

import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.AssertionLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.CompilationCompatibility;
import com.leanowtech.bloge.gateway.testing.correctness.domain.BusinessOracle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.BusinessOracle.OracleLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.AssertionSetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.BusinessOracleRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredAssertionSet;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredBusinessOracle;

import java.util.Objects;

/** Owns Assertion Set draft normalization, exact Oracle closure, and validation. */
public final class AssertionSetService {

    private final AssertionSetRepository assertionSets;
    private final BusinessOracleRepository oracles;
    private final AssertionSetCompiler compiler;
    private final AssertionEvaluatorProfile evaluatorProfile;

    public AssertionSetService(
            AssertionSetRepository assertionSets,
            BusinessOracleRepository oracles,
            AssertionSetCompiler compiler,
            AssertionEvaluatorProfile evaluatorProfile
    ) {
        this.assertionSets = Objects.requireNonNull(assertionSets, "assertionSets");
        this.oracles = Objects.requireNonNull(oracles, "oracles");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.evaluatorProfile = Objects.requireNonNull(evaluatorProfile, "evaluatorProfile");
    }

    public StoredAssertionSet saveDraft(
            EnterpriseScope scope,
            long expectedRevision,
            AssertionSet candidate,
            PrincipalRef actor
    ) {
        requireActor(actor);
        if (scope == null || candidate == null || expectedRevision < 0
                || candidate.revision() != expectedRevision
                || candidate.lifecycle() != AssertionLifecycle.DRAFT) {
            throw failure("RG.CORRECTNESS.ASSERTION_DRAFT_INVALID",
                    "Assertion Set save requires a matching DRAFT revision.");
        }
        StoredAssertionSet current = assertionSets.findHead(
                scope, candidate.assertionSetId()).orElse(null);
        if (current != null && current.assertionSet().lifecycle() != AssertionLifecycle.DRAFT) {
            throw failure("RG.CORRECTNESS.ASSERTION_SET_IMMUTABLE",
                    "Valid or stale Assertion Set revisions cannot be edited.");
        }
        resolveOracle(scope, candidate, false);
        AssertionSet normalized = new AssertionSet(
                candidate.schemaVersion(), candidate.assertionSetId(), candidate.revision(),
                candidate.target(), candidate.oracleRef(), AssertionLifecycle.DRAFT,
                candidate.assertions(), CompilationCompatibility.unsupported(
                        "RG.CORRECTNESS.NOT_VALIDATED"), candidate.metadata());
        return assertionSets.saveIfRevision(scope, expectedRevision, normalized, actor)
                .orElseThrow(AssertionSetService::conflict);
    }

    public AssertionCompilationReport compilePreview(
            EnterpriseScope scope,
            AssertionSet candidate
    ) {
        if (scope == null || candidate == null) {
            throw failure("RG.CORRECTNESS.ASSERTION_DRAFT_INVALID",
                    "A scoped Assertion Set is required for compilation.");
        }
        resolveOracle(scope, candidate, false);
        return compiler.compile(candidate, evaluatorProfile);
    }

    public ValidationResult validate(
            EnterpriseScope scope,
            String assertionSetId,
            long expectedRevision,
            PrincipalRef actor
    ) {
        requireActor(actor);
        if (scope == null || assertionSetId == null || assertionSetId.isBlank()
                || expectedRevision < 1) {
            throw failure("RG.CORRECTNESS.ASSERTION_VALIDATION_INVALID",
                    "Assertion validation requires one exact scoped draft revision.");
        }
        StoredAssertionSet stored = assertionSets.findHead(scope, assertionSetId.trim())
                .orElseThrow(() -> failure("RG.CORRECTNESS.ASSERTION_SET_NOT_FOUND",
                        "Assertion Set was not found in the authorized scope."));
        AssertionSet current = stored.assertionSet();
        if (current.revision() != expectedRevision) throw conflict();
        if (current.lifecycle() != AssertionLifecycle.DRAFT) {
            throw failure("RG.CORRECTNESS.ASSERTION_SET_IMMUTABLE",
                    "Only a draft Assertion Set can be validated.");
        }
        resolveOracle(scope, current, true);
        AssertionCompilationReport report = compiler.compile(current, evaluatorProfile);
        if (!report.compatibility().supported()) {
            throw failure(report.compatibility().reasonCode(),
                    "Assertion Set cannot be validated by the advertised evaluator profile.");
        }
        AssertionSet valid = new AssertionSet(
                current.schemaVersion(), current.assertionSetId(), current.revision(),
                current.target(), current.oracleRef(), AssertionLifecycle.VALID,
                current.assertions(), report.compatibility(), current.metadata());
        StoredAssertionSet result = assertionSets.saveIfRevision(
                scope, expectedRevision, valid, actor)
                .orElseThrow(AssertionSetService::conflict);
        return new ValidationResult(result, report);
    }

    private StoredBusinessOracle resolveOracle(
            EnterpriseScope scope,
            AssertionSet assertionSet,
            boolean requireApproved
    ) {
        ExactAssetRef ref = assertionSet.oracleRef();
        if (!"ORACLE".equals(ref.kind())) {
            throw failure("RG.CORRECTNESS.ORACLE_REFERENCE_INVALID",
                    "Assertion Set must reference an exact Business Oracle revision.");
        }
        StoredBusinessOracle stored = oracles.findRevision(scope, ref.id(), ref.revision())
                .filter(value -> value.oracleFingerprint().equals(ref.fingerprint()))
                .orElseThrow(() -> failure("RG.CORRECTNESS.ORACLE_REFERENCE_DRIFT",
                        "The exact Business Oracle revision is unavailable or has drifted."));
        BusinessOracle oracle = stored.oracle();
        if (!oracle.target().equals(assertionSet.target())) {
            throw failure("RG.CORRECTNESS.TARGET_MISMATCH",
                    "Assertion Set and Business Oracle must bind the same exact target.");
        }
        if (requireApproved && oracle.lifecycle() != OracleLifecycle.APPROVED) {
            throw failure("RG.CORRECTNESS.ORACLE_NOT_APPROVED",
                    "A valid Assertion Set requires an approved Business Oracle.");
        }
        return stored;
    }

    private static void requireActor(PrincipalRef actor) {
        if (actor == null) {
            throw failure("RG.CORRECTNESS.ACTOR_REQUIRED",
                    "An authenticated command actor is required.");
        }
    }

    private static OracleAssertionCommandException conflict() {
        return failure("RG.CORRECTNESS.REVISION_CONFLICT",
                "The Assertion Set changed; reload the exact head and retry.");
    }

    private static OracleAssertionCommandException failure(String code, String message) {
        return new OracleAssertionCommandException(code, message);
    }

    public record ValidationResult(
            StoredAssertionSet stored,
            AssertionCompilationReport compilation
    ) {
        public ValidationResult {
            if (stored == null || compilation == null
                    || stored.assertionSet().lifecycle() != AssertionLifecycle.VALID
                    || !compilation.compatibility().supported()) {
                throw new IllegalArgumentException("Valid Assertion Set result is required");
            }
        }
    }
}
