package com.leanowtech.bloge.gateway.testing.correctness.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.AssertionLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.BusinessOracle.OracleLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactObligationRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.CoverageObligation;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.InventoryLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ControlledDependencyV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.FixtureVariantRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.GeneratedValueRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.InlineValue;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ReplayMaterialRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ScenarioDraftV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ValueSource;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.AssertionSetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.BusinessOracleRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.CoverageInventoryRepository;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.ScenarioClosureReport.CheckStatus;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.ScenarioClosureReport.ClosureCheck;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.ScenarioClosureReport.ClosurePhase;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Validates exact governed Case closure without reading or returning fixture payloads. */
public final class ScenarioClosureValidator {

    private final CoverageInventoryRepository inventories;
    private final BusinessOracleRepository oracles;
    private final AssertionSetRepository assertionSets;
    private final ScenarioExternalReferenceSource externalReferences;
    private final ObjectMapper mapper;

    public ScenarioClosureValidator(
            CoverageInventoryRepository inventories,
            BusinessOracleRepository oracles,
            AssertionSetRepository assertionSets,
            ScenarioExternalReferenceSource externalReferences,
            ObjectMapper mapper
    ) {
        this.inventories = Objects.requireNonNull(inventories, "inventories");
        this.oracles = Objects.requireNonNull(oracles, "oracles");
        this.assertionSets = Objects.requireNonNull(assertionSets, "assertionSets");
        this.externalReferences = externalReferences == null
                ? ScenarioExternalReferenceSource.denyAll() : externalReferences;
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public ScenarioClosureReport validate(
            ScenarioDraftSetV2 draftSet,
            ScenarioDraftV2 scenario,
            ClosurePhase phase
    ) {
        Objects.requireNonNull(draftSet, "draftSet");
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(phase, "phase");
        EnterpriseScope scope = draftSet.scope();
        List<ClosureCheck> checks = new ArrayList<>();

        checks.add(externalCheck(
                "contract", "CONTRACT", draftSet.contractRef(), scope, draftSet));
        obligationChecks(checks, draftSet, scenario, phase);
        oracleChecks(checks, draftSet, scenario);
        assertionChecks(checks, draftSet, scenario);
        for (ExactAssetRef reference : externalRefs(scenario)) {
            checks.add(externalCheck(
                    "source:" + coordinate(reference), reference.kind(), reference,
                    scope, draftSet));
        }

        boolean complete = !checks.isEmpty()
                && checks.stream().allMatch(check -> check.status() == CheckStatus.VERIFIED);
        return new ScenarioClosureReport(
                "", scenario.scenarioId(), phase, complete, checks);
    }

    private void obligationChecks(
            List<ClosureCheck> checks,
            ScenarioDraftSetV2 draftSet,
            ScenarioDraftV2 scenario,
            ClosurePhase phase
    ) {
        if (scenario.obligationRefs().isEmpty()) {
            if (phase == ClosurePhase.CANONICAL) {
                checks.add(failed(
                        "obligation:required", "OBLIGATION", null, "",
                        CheckStatus.MISSING, "RG.CORRECTNESS.DENOMINATOR_NOT_FROZEN"));
            }
            return;
        }
        for (ExactObligationRef ref : scenario.obligationRefs()) {
            ExactAssetRef inventoryRef = ref.inventoryRef();
            String checkId = "obligation:" + coordinate(inventoryRef) + ":" + ref.obligationId();
            var stored = inventories.findRevision(
                    draftSet.scope(), inventoryRef.id(), inventoryRef.revision()).orElse(null);
            if (stored == null || !"INVENTORY".equals(inventoryRef.kind())
                    || !stored.inventoryFingerprint().equals(inventoryRef.fingerprint())
                    || !stored.inventory().target().equals(draftSet.target())) {
                checks.add(failed(
                        checkId, "OBLIGATION", inventoryRef, ref.obligationId(),
                        CheckStatus.STALE, "RG.CORRECTNESS.OBLIGATION_REFERENCE_DRIFT"));
                continue;
            }
            if (stored.inventory().lifecycle() != InventoryLifecycle.FROZEN) {
                checks.add(failed(
                        checkId, "OBLIGATION", inventoryRef, ref.obligationId(),
                        CheckStatus.WRONG_STATE, "RG.CORRECTNESS.DENOMINATOR_NOT_FROZEN"));
                continue;
            }
            CoverageObligation obligation = stored.inventory().obligations().stream()
                    .filter(value -> value.obligationId().equals(ref.obligationId()))
                    .findFirst().orElse(null);
            if (obligation == null
                    || !CorrectnessProtocolFingerprint.obligationFingerprint(mapper, obligation)
                            .equals(ref.obligationFingerprint())) {
                checks.add(failed(
                        checkId, "OBLIGATION", inventoryRef, ref.obligationId(),
                        CheckStatus.STALE, "RG.CORRECTNESS.OBLIGATION_REFERENCE_DRIFT"));
            } else if (obligation.lifecycle() != ObligationLifecycle.FROZEN) {
                checks.add(failed(
                        checkId, "OBLIGATION", inventoryRef, ref.obligationId(),
                        CheckStatus.WRONG_STATE,
                        "RG.CORRECTNESS.OBLIGATION_NOT_ACTIONABLE"));
            } else {
                checks.add(verified(checkId, "OBLIGATION", inventoryRef, ref.obligationId()));
            }
        }
    }

    private void oracleChecks(
            List<ClosureCheck> checks,
            ScenarioDraftSetV2 draftSet,
            ScenarioDraftV2 scenario
    ) {
        if (scenario.oracleRefs().isEmpty()) {
            checks.add(failed(
                    "oracle:required", "ORACLE", null, "", CheckStatus.MISSING,
                    "RG.CORRECTNESS.ORACLE_NOT_APPROVED"));
            return;
        }
        for (ExactAssetRef ref : scenario.oracleRefs()) {
            String checkId = "oracle:" + coordinate(ref);
            var stored = oracles.findRevision(
                    draftSet.scope(), ref.id(), ref.revision()).orElse(null);
            if (stored == null || !"ORACLE".equals(ref.kind())
                    || !stored.oracleFingerprint().equals(ref.fingerprint())
                    || !stored.oracle().target().equals(draftSet.target())) {
                checks.add(failed(
                        checkId, "ORACLE", ref, "", CheckStatus.STALE,
                        "RG.CORRECTNESS.ORACLE_REFERENCE_DRIFT"));
            } else if (stored.oracle().lifecycle() != OracleLifecycle.APPROVED) {
                checks.add(failed(
                        checkId, "ORACLE", ref, "", CheckStatus.WRONG_STATE,
                        "RG.CORRECTNESS.ORACLE_NOT_APPROVED"));
            } else {
                checks.add(verified(checkId, "ORACLE", ref, ""));
            }
        }
    }

    private void assertionChecks(
            List<ClosureCheck> checks,
            ScenarioDraftSetV2 draftSet,
            ScenarioDraftV2 scenario
    ) {
        if (scenario.assertionSetRefs().isEmpty()) {
            checks.add(failed(
                    "assertion:required", "ASSERTION_SET", null, "", CheckStatus.MISSING,
                    "RG.CORRECTNESS.ASSERTION_NONE"));
            return;
        }
        Set<ExactAssetRef> oracleRefs = Set.copyOf(scenario.oracleRefs());
        for (ExactAssetRef ref : scenario.assertionSetRefs()) {
            String checkId = "assertion:" + coordinate(ref);
            var stored = assertionSets.findRevision(
                    draftSet.scope(), ref.id(), ref.revision()).orElse(null);
            if (stored == null || !"ASSERTION_SET".equals(ref.kind())
                    || !stored.assertionSetFingerprint().equals(ref.fingerprint())
                    || !stored.assertionSet().target().equals(draftSet.target())) {
                checks.add(failed(
                        checkId, "ASSERTION_SET", ref, "", CheckStatus.STALE,
                        "RG.CORRECTNESS.ASSERTION_REFERENCE_DRIFT"));
            } else if (stored.assertionSet().lifecycle() != AssertionLifecycle.VALID
                    || !stored.assertionSet().compatibility().supported()) {
                checks.add(failed(
                        checkId, "ASSERTION_SET", ref, "", CheckStatus.WRONG_STATE,
                        "RG.CORRECTNESS.ASSERTION_UNSUPPORTED"));
            } else if (!oracleRefs.contains(stored.assertionSet().oracleRef())) {
                checks.add(failed(
                        checkId, "ASSERTION_SET", ref, "", CheckStatus.INCOMPATIBLE,
                        "RG.CORRECTNESS.ASSERTION_ORACLE_MISMATCH"));
            } else {
                checks.add(verified(checkId, "ASSERTION_SET", ref, ""));
            }
        }
    }

    private ClosureCheck externalCheck(
            String checkId,
            String kind,
            ExactAssetRef ref,
            EnterpriseScope scope,
            ScenarioDraftSetV2 draftSet
    ) {
        return externalReferences.referenceIsCurrent(scope, draftSet.target(), ref)
                ? verified(checkId, kind, ref, "")
                : failed(checkId, kind, ref, "", CheckStatus.STALE,
                        "RG.CORRECTNESS.EXTERNAL_REFERENCE_DRIFT");
    }

    private static List<ExactAssetRef> externalRefs(ScenarioDraftV2 scenario) {
        Set<ExactAssetRef> refs = new LinkedHashSet<>(scenario.sourceRefs());
        addValueRef(refs, scenario.given().input());
        for (ControlledDependencyV2 dependency : scenario.dependencies()) {
            addValueRef(refs, dependency.behavior().value());
        }
        return refs.stream()
                .sorted(java.util.Comparator.comparing(ExactAssetRef::kind)
                        .thenComparing(ExactAssetRef::id)
                        .thenComparingLong(ExactAssetRef::revision)
                        .thenComparing(ExactAssetRef::fingerprint))
                .toList();
    }

    private static void addValueRef(Set<ExactAssetRef> refs, ValueSource value) {
        if (value == null || value instanceof InlineValue) return;
        if (value instanceof FixtureVariantRef fixture) {
            refs.add(fixture.fixtureAssetRef());
        } else if (value instanceof GeneratedValueRef generated) {
            refs.add(generated.generatorRef());
        } else if (value instanceof ReplayMaterialRef replay) {
            refs.add(replay.replayMaterialRef());
        }
    }

    private static ClosureCheck verified(
            String checkId,
            String kind,
            ExactAssetRef ref,
            String childId
    ) {
        return new ClosureCheck(
                checkId, kind, ref, childId, CheckStatus.VERIFIED, "");
    }

    private static ClosureCheck failed(
            String checkId,
            String kind,
            ExactAssetRef ref,
            String childId,
            CheckStatus status,
            String reasonCode
    ) {
        return new ClosureCheck(checkId, kind, ref, childId, status, reasonCode);
    }

    private static String coordinate(ExactAssetRef ref) {
        return ref.kind() + ":" + ref.id() + "@" + ref.revision();
    }
}
