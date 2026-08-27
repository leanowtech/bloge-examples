package com.leanowtech.bloge.gateway.testing.world.mutation;

import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV5;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV5;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldMutationProtocolTest {
    @Test
    void planFingerprintIsDeterministic() {
        assertThat(plan(2).planFingerprint()).isEqualTo(plan(2).planFingerprint());
    }

    @Test
    void planKeepsMutantsOrdered() {
        assertThat(plan(3).mutants()).extracting(WorldMutationPlan.PlannedMutant::mutantId)
                .containsExactly("world-mutant-0001", "world-mutant-0002", "world-mutant-0003");
    }

    @Test
    void planRejectsDuplicateMutantIds() {
        WorldMutationPlan.PlannedMutant first = mutant(1, WorldMutationPlan.MutationKind.RULE_DELETED);
        assertThatThrownBy(() -> new WorldMutationPlan("tenant-a", "world-a", 1, fp(1), fp(2),
                "fragment.bloge", 1, fp(3), fp(4), WorldMutationPlan.PLANNER_VERSION,
                new WorldMutationPlan.Policy(4, false), List.of(first, first), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void planRejectsNonFingerprintContent() {
        assertThatThrownBy(() -> new WorldMutationPlan("tenant-a", "world-a", 1, "raw", fp(2),
                "fragment.bloge", 1, fp(3), fp(4), WorldMutationPlan.PLANNER_VERSION,
                new WorldMutationPlan.Policy(4, false), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gapCodeIsStableAndPayloadFree() {
        WorldMutationPlan.PlanningGap gap = new WorldMutationPlan.PlanningGap(
                WorldMutationPlan.MutationKind.STATE_WRITE_DROPPED, "NO_SUPPORTED_MUTATION_SITE", "/members");
        assertThat(gap.code()).isEqualTo("NO_SUPPORTED_MUTATION_SITE");
        assertThat(gap.toString()).doesNotContain("stateWrites");
    }

    @Test
    void allSixMutationKindsArePartOfTheProtocol() {
        assertThat(WorldMutationPlan.MutationKind.values()).containsExactly(
                WorldMutationPlan.MutationKind.RULE_DELETED,
                WorldMutationPlan.MutationKind.DECISION_CONDITION_REVERSED,
                WorldMutationPlan.MutationKind.BOUNDARY_VALUE_REPLACED,
                WorldMutationPlan.MutationKind.RESULT_CHANGED,
                WorldMutationPlan.MutationKind.STATE_WRITE_DROPPED,
                WorldMutationPlan.MutationKind.DEFAULT_RULE_PRIORITY_CHANGED);
    }

    @Test
    void matrixAcceptsOneObservationPerScenarioAndMutant() {
        WorldMutationPlan plan = plan(2);
        assertThat(matrix(plan, List.of(WorldMutationMatrix.ObservationStatus.PASSED,
                WorldMutationMatrix.ObservationStatus.ASSERTION_FAILED)).observations()).hasSize(2);
    }

    @Test
    void matrixRejectsDuplicateScenarioIds() {
        assertThatThrownBy(() -> new WorldMutationMatrix.ScenarioMutantMatrix(
                List.of(new WorldMutationMatrix.ScenarioRef("s", fp(10)),
                        new WorldMutationMatrix.ScenarioRef("s", fp(11))), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matrixRejectsUnknownScenarioObservation() {
        assertThatThrownBy(() -> new WorldMutationMatrix.ScenarioMutantMatrix(
                List.of(new WorldMutationMatrix.ScenarioRef("s", fp(10))),
                List.of(new WorldMutationMatrix.Observation("other", fp(10), "m", fp(11),
                        WorldMutationMatrix.ObservationStatus.PASSED, fp(12), "OK"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matrixRejectsDuplicateScenarioMutantCell() {
        WorldMutationMatrix.Observation cell = new WorldMutationMatrix.Observation("s", fp(10),
                "m", fp(11), WorldMutationMatrix.ObservationStatus.PASSED, fp(12), "OK");
        assertThatThrownBy(() -> new WorldMutationMatrix.ScenarioMutantMatrix(
                List.of(new WorldMutationMatrix.ScenarioRef("s", fp(10))), List.of(cell, cell)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void evaluatorCountsAssertionFailureAsKilled() {
        WorldMutationPlan plan = plan(1);
        WorldMutationEvaluator.GateReport report = evaluate(plan,
                List.of(WorldMutationMatrix.ObservationStatus.ASSERTION_FAILED), List.of(),
                WorldMutationEvaluator.Mode.EXPLORATORY);
        assertThat(report.world().killed()).isEqualTo(1);
        assertThat(report.world().scoreBasisPoints()).isEqualTo(10_000);
    }

    @Test
    void evaluatorListsSurvivorAndIncludesItInDenominator() {
        WorldMutationPlan plan = plan(2);
        WorldMutationEvaluator.GateReport report = evaluate(plan,
                List.of(WorldMutationMatrix.ObservationStatus.ASSERTION_FAILED,
                        WorldMutationMatrix.ObservationStatus.PASSED), List.of(),
                WorldMutationEvaluator.Mode.EXPLORATORY);
        assertThat(report.world().denominator()).isEqualTo(2);
        assertThat(report.world().survived()).isEqualTo(1);
        assertThat(report.world().survivors()).containsExactly("world-mutant-0002");
    }

    @Test
    void aScenarioTimeoutDoesNotEraseAnEstablishedKill() {
        WorldMutationPlan plan = plan(1);
        WorldMutationPlan.PlannedMutant mutant = plan.mutants().getFirst();
        WorldMutationMatrix.ScenarioMutantMatrix matrix = new WorldMutationMatrix.ScenarioMutantMatrix(
                List.of(new WorldMutationMatrix.ScenarioRef("early", fp(10)),
                        new WorldMutationMatrix.ScenarioRef("late", fp(11))),
                List.of(new WorldMutationMatrix.Observation("early", fp(10), mutant.mutantId(),
                                mutant.mutantTargetFingerprint(),
                                WorldMutationMatrix.ObservationStatus.ASSERTION_FAILED, fp(12), "OK"),
                        new WorldMutationMatrix.Observation("late", fp(11), mutant.mutantId(),
                                mutant.mutantTargetFingerprint(),
                                WorldMutationMatrix.ObservationStatus.TIMEOUT, fp(13), "TIMEOUT")));

        WorldMutationEvaluator.GateReport report = new WorldMutationEvaluator(acceptingAuthority()).evaluate(
                plan, matrix, graphScore(), List.of(), WorldMutationEvaluator.Mode.EXPLORATORY,
                WorldMutationEvaluator.Policy.defaults());

        assertThat(report.mutants().getFirst().status()).isEqualTo(WorldMutationEvaluator.MutantStatus.KILLED);
        assertThat(report.mutants().getFirst().killedScenarioCount()).isEqualTo(1);
        assertThat(report.world().killed()).isEqualTo(1);
        assertThat(report.world().inconclusive()).isZero();
    }

    @Test
    void evaluatorRequiresCompleteCartesianMatrix() {
        WorldMutationPlan plan = plan(2);
        WorldMutationMatrix.Observation only = new WorldMutationMatrix.Observation("s", fp(10),
                plan.mutants().get(0).mutantId(), plan.mutants().get(0).contentFingerprint(),
                WorldMutationMatrix.ObservationStatus.PASSED, fp(12), "OK");
        assertThatThrownBy(() -> new WorldMutationEvaluator().evaluate(plan,
                new WorldMutationMatrix.ScenarioMutantMatrix(
                        List.of(new WorldMutationMatrix.ScenarioRef("s", fp(10))), List.of(only)),
                graphScore(), List.of(), WorldMutationEvaluator.Mode.EXPLORATORY,
                WorldMutationEvaluator.Policy.defaults()))
                .isInstanceOf(WorldMutationException.class)
                .extracting("code").isEqualTo(WorldMutationException.Code.MATRIX_INCOMPLETE);
    }

    @Test
    void certifiableGateBlocksBelowThreshold() {
        WorldMutationEvaluator.GateReport report = evaluate(plan(1),
                List.of(WorldMutationMatrix.ObservationStatus.PASSED), List.of(),
                WorldMutationEvaluator.Mode.CERTIFIABLE);
        assertThat(report.status()).isEqualTo(WorldMutationEvaluator.GateStatus.BLOCKED);
        assertThat(report.reasons()).contains("WORLD_SCORE_BELOW_THRESHOLD");
    }

    @Test
    void exploratoryGateWarnsInsteadOfBlocking() {
        WorldMutationEvaluator.GateReport report = evaluate(plan(1),
                List.of(WorldMutationMatrix.ObservationStatus.PASSED), List.of(),
                WorldMutationEvaluator.Mode.EXPLORATORY);
        assertThat(report.status()).isEqualTo(WorldMutationEvaluator.GateStatus.WARNING);
    }

    @Test
    void equivalentReceiptExcludesOnlyTheBoundMutant() {
        WorldMutationPlan plan = plan(2);
        WorldMutationPlan.PlannedMutant mutant = plan.mutants().get(0);
        WorldMutationEquivalenceReceipt receipt = receipt(plan, mutant);
        WorldMutationEvaluator.GateReport report = evaluate(plan,
                List.of(WorldMutationMatrix.ObservationStatus.ASSERTION_FAILED,
                        WorldMutationMatrix.ObservationStatus.PASSED), List.of(receipt),
                WorldMutationEvaluator.Mode.EXPLORATORY);
        assertThat(report.world().equivalent()).isEqualTo(1);
        assertThat(report.world().denominator()).isEqualTo(1);
        assertThat(report.world().equivalenceSources())
                .containsExactly(WorldMutationEvaluator.EquivalenceSource.INDEPENDENT_SEMANTIC_PROOF.name());
    }

    @Test
    void receiptMetadataAloneCannotClassifyEquivalent() {
        WorldMutationPlan plan = plan(1);
        WorldMutationEquivalenceReceipt receipt = receipt(plan, plan.mutants().getFirst());
        assertThatThrownBy(() -> new WorldMutationEvaluator().evaluate(
                plan, matrix(plan, List.of(WorldMutationMatrix.ObservationStatus.PASSED)), graphScore(),
                List.of(receipt), WorldMutationEvaluator.Mode.EXPLORATORY,
                WorldMutationEvaluator.Policy.defaults()))
                .isInstanceOf(WorldMutationException.class)
                .extracting("code").isEqualTo(WorldMutationException.Code.EQUIVALENCE_RECEIPT_INVALID);
    }

    @Test
    void contentFingerprintCannotMasqueradeAsMutantTarget() {
        WorldMutationPlan plan = plan(1);
        WorldMutationPlan.PlannedMutant mutant = plan.mutants().getFirst();
        WorldMutationEquivalenceReceipt forged = WorldMutationEquivalenceReceipt.semantic(
                "receipt-content-target", plan.tenantId(), plan.planFingerprint(), mutant.mutantId(),
                mutant.baselineFragmentFingerprint(), WorldMutationEvaluator.DEFAULT_PURPOSE,
                mutant.mutantSourceFingerprint(), mutant.mutantGraphFingerprint(),
                mutant.contentFingerprint(), "semantic-authority", "PROOF_ACCEPTED");
        assertThatThrownBy(() -> new WorldMutationEvaluator(acceptingAuthority()).evaluate(
                plan, matrix(plan, List.of(WorldMutationMatrix.ObservationStatus.PASSED)), graphScore(),
                List.of(forged), WorldMutationEvaluator.Mode.EXPLORATORY,
                WorldMutationEvaluator.Policy.defaults()))
                .isInstanceOf(WorldMutationException.class)
                .extracting("code").isEqualTo(WorldMutationException.Code.EQUIVALENCE_RECEIPT_INVALID);
    }

    @Test
    void tamperedReceiptFailsClosed() {
        WorldMutationPlan plan = plan(1);
        WorldMutationPlan.PlannedMutant mutant = plan.mutants().get(0);
        assertThatThrownBy(() -> new WorldMutationEquivalenceReceipt("r", "tenant-a", plan.planFingerprint(),
                mutant.mutantId(), mutant.baselineFragmentFingerprint(), WorldMutationEvaluator.DEFAULT_PURPOSE,
                mutant.mutantSourceFingerprint(), mutant.mutantGraphFingerprint(), mutant.mutantTargetFingerprint(),
                WorldMutationEquivalenceReceipt.Source.HUMAN_REVIEW, "reviewer", "REASON", fp(99)))
                .isInstanceOf(WorldMutationException.class)
                .extracting("code").isEqualTo(WorldMutationException.Code.EQUIVALENCE_RECEIPT_INVALID);
    }

    @Test
    void crossTenantReceiptFailsClosed() {
        WorldMutationPlan plan = plan(1);
        assertThatThrownBy(() -> evaluate(plan, List.of(WorldMutationMatrix.ObservationStatus.PASSED),
                List.of(receiptWithTenant(plan, "tenant-b")), WorldMutationEvaluator.Mode.EXPLORATORY))
                .isInstanceOf(WorldMutationException.class)
                .extracting("code").isEqualTo(WorldMutationException.Code.EQUIVALENCE_RECEIPT_INVALID);
    }

    @Test
    void receiptCannotBeConsumedTwice() {
        WorldMutationPlan plan = plan(1);
        WorldMutationPlan.PlannedMutant mutant = plan.mutants().get(0);
        WorldMutationEquivalenceReceipt receipt = receipt(plan, mutant);
        WorldMutationReceiptLedger ledger = new WorldMutationReceiptLedger();
        ledger.consume(verified(receipt), receipt, "tenant-a", plan, mutant, WorldMutationEvaluator.DEFAULT_PURPOSE);
        assertThatThrownBy(() -> ledger.consume(verified(receipt), receipt, "tenant-a", plan, mutant,
                WorldMutationEvaluator.DEFAULT_PURPOSE)).isInstanceOf(WorldMutationException.class)
                .extracting("code").isEqualTo(WorldMutationException.Code.EQUIVALENCE_RECEIPT_REUSED);
    }

    @Test
    void receiptReplayIsAtomicUnderConcurrency() {
        WorldMutationPlan plan = plan(1);
        WorldMutationPlan.PlannedMutant mutant = plan.mutants().get(0);
        WorldMutationEquivalenceReceipt receipt = receipt(plan, mutant);
        WorldMutationReceiptLedger ledger = new WorldMutationReceiptLedger();
        ConcurrentLinkedQueue<WorldMutationException.Code> failures = new ConcurrentLinkedQueue<>();
        IntStream.range(0, 16).parallel().forEach(ignored -> {
            try {
                ledger.consume(verified(receipt), receipt, "tenant-a", plan, mutant,
                        WorldMutationEvaluator.DEFAULT_PURPOSE);
            } catch (WorldMutationException ex) {
                failures.add(ex.code());
            }
        });
        assertThat(ledger.consumedCount()).isEqualTo(1);
        assertThat(failures).containsOnly(WorldMutationException.Code.EQUIVALENCE_RECEIPT_REUSED);
    }

    @Test
    void wrongTargetFingerprintCannotEnterMatrix() {
        WorldMutationPlan plan = plan(1);
        WorldMutationMatrix.ScenarioMutantMatrix wrong = new WorldMutationMatrix.ScenarioMutantMatrix(
                List.of(new WorldMutationMatrix.ScenarioRef("s", fp(10))),
                List.of(new WorldMutationMatrix.Observation("s", fp(10),
                        plan.mutants().get(0).mutantId(), fp(404),
                        WorldMutationMatrix.ObservationStatus.PASSED, fp(12), "OK")));
        TestSuiteV5.MutationScorePolicy policy = new TestSuiteV5.MutationScorePolicy(10_000, 0, true, false);
        TestSuiteRunEvidenceV5.MutationScoreVerdict graph = new TestSuiteRunEvidenceV5.MutationScoreVerdict(
                TestSuiteRunEvidenceV5.MutationScoreStatus.SATISFIED, policy, 1, 1, 0, 0, 0,
                1, 10_000, 0, List.of());
        assertThatThrownBy(() -> new WorldMutationEvaluator().evaluate(plan, wrong, graph, List.of(),
                WorldMutationEvaluator.Mode.EXPLORATORY, WorldMutationEvaluator.Policy.defaults()))
                .isInstanceOf(WorldMutationException.class)
                .extracting("code").isEqualTo(WorldMutationException.Code.MATRIX_INCOMPLETE);
    }

    @Test
    void noWorldMutantsAreExplicitlyNotApplicable() {
        WorldMutationPlan plan = new WorldMutationPlan("tenant-a", "world-a", 1, fp(1), fp(2),
                "fragment.bloge", 1, fp(3), fp(4), WorldMutationPlan.PLANNER_VERSION,
                new WorldMutationPlan.Policy(4, false), List.of(), List.of(
                new WorldMutationPlan.PlanningGap(WorldMutationPlan.MutationKind.RULE_DELETED,
                        "NO_SUPPORTED_MUTATION_SITE", "/members")));
        WorldMutationEvaluator.GateReport report = evaluate(plan, List.of(), List.of(),
                WorldMutationEvaluator.Mode.EXPLORATORY);
        assertThat(report.world().naReasons()).containsExactly("WORLD_MUTATION_NO_MUTANTS");
        assertThat(report.status()).isEqualTo(WorldMutationEvaluator.GateStatus.WARNING);
    }

    @Test
    void policyRejectsUnboundedMutationRequest() {
        assertThatThrownBy(() -> new WorldMutationPlan.Policy(513, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void payloadFreeExceptionDoesNotEchoInput() {
        WorldMutationException error = new WorldMutationException(WorldMutationException.Code.PAYLOAD_FORBIDDEN);
        assertThat(error.getMessage()).isEqualTo("RG.WORLD.MUTATION.PAYLOAD_FORBIDDEN");
        assertThat(error.getMessage()).doesNotContain("payload", "secret", "customer");
    }

    @Test
    void graphLayerRemainsAnIndependentInput() {
        WorldMutationEvaluator.GateReport report = evaluate(plan(1),
                List.of(WorldMutationMatrix.ObservationStatus.ASSERTION_FAILED), List.of(),
                WorldMutationEvaluator.Mode.EXPLORATORY);
        assertThat(report.graph().layer()).isEqualTo("GRAPH");
        assertThat(report.world().layer()).isEqualTo("WORLD");
        assertThat(report.graph().scoreBasisPoints()).isEqualTo(10_000);
    }

    @Test
    void graphInconclusiveAndUnclassifiedStayInRecomputedDenominator() {
        WorldMutationPlan plan = plan(1);
        WorldMutationEvaluator.GraphLayerInput graph = new WorldMutationEvaluator.GraphLayerInput(
                List.of(new WorldMutationEvaluator.GraphMutantSummary("graph-killed",
                                WorldMutationEvaluator.GraphMutantStatus.KILLED,
                                WorldMutationEvaluator.EquivalenceSource.NONE),
                        new WorldMutationEvaluator.GraphMutantSummary("graph-inconclusive",
                                WorldMutationEvaluator.GraphMutantStatus.INCONCLUSIVE,
                                WorldMutationEvaluator.EquivalenceSource.NONE),
                        new WorldMutationEvaluator.GraphMutantSummary("graph-unclassified",
                                WorldMutationEvaluator.GraphMutantStatus.UNCLASSIFIED,
                                WorldMutationEvaluator.EquivalenceSource.NONE)),
                TestSuiteRunEvidenceV5.MutationScoreStatus.INCOMPLETE, List.of(), List.of());
        WorldMutationEvaluator.GateReport report = new WorldMutationEvaluator(acceptingAuthority()).evaluate(
                plan, matrix(plan, List.of(WorldMutationMatrix.ObservationStatus.ASSERTION_FAILED)), graph,
                List.of(), WorldMutationEvaluator.Mode.EXPLORATORY, WorldMutationEvaluator.Policy.defaults());

        assertThat(report.graph().denominator()).isEqualTo(3);
        assertThat(report.graph().inconclusive()).isEqualTo(2);
        assertThat(report.graph().scoreBasisPoints()).isEqualTo(3_333);
        assertThat(report.graph().complete()).isFalse();
    }

    @Test
    void completeGraphClassificationCanFailThresholdWithoutBeingIncomplete() {
        WorldMutationPlan plan = plan(1);
        WorldMutationEvaluator.GraphLayerInput graph = new WorldMutationEvaluator.GraphLayerInput(
                List.of(new WorldMutationEvaluator.GraphMutantSummary("graph-killed",
                                WorldMutationEvaluator.GraphMutantStatus.KILLED,
                                WorldMutationEvaluator.EquivalenceSource.NONE),
                        new WorldMutationEvaluator.GraphMutantSummary("graph-survived",
                                WorldMutationEvaluator.GraphMutantStatus.SURVIVED,
                                WorldMutationEvaluator.EquivalenceSource.NONE)),
                TestSuiteRunEvidenceV5.MutationScoreStatus.UNSATISFIED, List.of(), List.of());
        WorldMutationEvaluator.GateReport report = new WorldMutationEvaluator(acceptingAuthority()).evaluate(
                plan, matrix(plan, List.of(WorldMutationMatrix.ObservationStatus.ASSERTION_FAILED)), graph,
                List.of(), WorldMutationEvaluator.Mode.CERTIFIABLE,
                new WorldMutationEvaluator.Policy(10_000, 10_000, 0, false, true,
                        WorldMutationEvaluator.DEFAULT_PURPOSE));

        assertThat(report.graph().complete()).isTrue();
        assertThat(report.graph().scoreBasisPoints()).isEqualTo(5_000);
        assertThat(report.status()).isEqualTo(WorldMutationEvaluator.GateStatus.BLOCKED);
    }

    @Test
    void graphSurvivorIdsAreExplicitAndLegacySurvivorCountFailsClosed() {
        WorldMutationPlan plan = plan(1);
        WorldMutationEvaluator.GraphLayerInput graph = new WorldMutationEvaluator.GraphLayerInput(
                List.of(new WorldMutationEvaluator.GraphMutantSummary("exact-survivor-id",
                                WorldMutationEvaluator.GraphMutantStatus.SURVIVED,
                                WorldMutationEvaluator.EquivalenceSource.NONE)),
                TestSuiteRunEvidenceV5.MutationScoreStatus.UNSATISFIED, List.of(), List.of());
        WorldMutationEvaluator.GateReport report = new WorldMutationEvaluator(acceptingAuthority()).evaluate(
                plan, matrix(plan, List.of(WorldMutationMatrix.ObservationStatus.ASSERTION_FAILED)), graph,
                List.of(), WorldMutationEvaluator.Mode.EXPLORATORY, WorldMutationEvaluator.Policy.defaults());
        assertThat(report.graph().survivors()).containsExactly("exact-survivor-id");

        TestSuiteV5.MutationScorePolicy oldPolicy = new TestSuiteV5.MutationScorePolicy(0, 0, false, false);
        TestSuiteRunEvidenceV5.MutationScoreVerdict old = new TestSuiteRunEvidenceV5.MutationScoreVerdict(
                TestSuiteRunEvidenceV5.MutationScoreStatus.UNSATISFIED, oldPolicy, 1, 0, 1, 0, 0,
                1, 0, 0, List.of());
        assertThatThrownBy(() -> new WorldMutationEvaluator().evaluate(plan,
                matrix(plan, List.of(WorldMutationMatrix.ObservationStatus.ASSERTION_FAILED)), old,
                List.of(), WorldMutationEvaluator.Mode.EXPLORATORY,
                WorldMutationEvaluator.Policy.defaults()))
                .isInstanceOf(WorldMutationException.class)
                .extracting("code").isEqualTo(WorldMutationException.Code.GATE_INCOMPLETE);
    }

    @Test
    void worldNoExecutableMutantsBlocksWhenRequiredAndIsNotApplicableWhenOptional() {
        WorldMutationPlan empty = new WorldMutationPlan("tenant-a", "world-a", 1, fp(1), fp(2),
                "fragment.bloge", 1, fp(3), fp(4), WorldMutationPlan.PLANNER_VERSION,
                new WorldMutationPlan.Policy(4, false), List.of(), List.of(
                new WorldMutationPlan.PlanningGap(WorldMutationPlan.MutationKind.RULE_DELETED,
                        "NO_SUPPORTED_MUTATION_SITE", "/members")));
        WorldMutationMatrix.ScenarioMutantMatrix matrix = new WorldMutationMatrix.ScenarioMutantMatrix(
                List.of(new WorldMutationMatrix.ScenarioRef("s", fp(10))), List.of());
        WorldMutationEvaluator.Policy required = WorldMutationEvaluator.Policy.defaults();
        WorldMutationEvaluator.Policy optional = new WorldMutationEvaluator.Policy(10_000, 10_000,
                0, true, false, WorldMutationEvaluator.DEFAULT_PURPOSE);

        WorldMutationEvaluator.GateReport blocked = new WorldMutationEvaluator().evaluate(empty, matrix,
                graphScore(), List.of(), WorldMutationEvaluator.Mode.CERTIFIABLE, required);
        WorldMutationEvaluator.GateReport warned = new WorldMutationEvaluator().evaluate(empty, matrix,
                graphScore(), List.of(), WorldMutationEvaluator.Mode.EXPLORATORY, required);
        WorldMutationEvaluator.GateReport notApplicable = new WorldMutationEvaluator().evaluate(empty, matrix,
                graphScore(), List.of(), WorldMutationEvaluator.Mode.CERTIFIABLE, optional);

        assertThat(blocked.status()).isEqualTo(WorldMutationEvaluator.GateStatus.BLOCKED);
        assertThat(warned.status()).isEqualTo(WorldMutationEvaluator.GateStatus.WARNING);
        assertThat(notApplicable.status()).isEqualTo(WorldMutationEvaluator.GateStatus.NOT_APPLICABLE);
        assertThat(notApplicable.world().naReasons()).containsExactly("WORLD_MUTATION_NO_MUTANTS");
        assertThat(notApplicable.reasons()).contains("WORLD_MUTATION_NO_MUTANTS")
                .doesNotContain("WORLD_SCORE_BELOW_THRESHOLD", "WORLD_LAYER_INCOMPLETE");
    }

    @Test
    void optionalWorldNADoesNotHideIndependentGraphIncompleteReason() {
        WorldMutationPlan empty = new WorldMutationPlan("tenant-a", "world-a", 1, fp(1), fp(2),
                "fragment.bloge", 1, fp(3), fp(4), WorldMutationPlan.PLANNER_VERSION,
                new WorldMutationPlan.Policy(4, false), List.of(), List.of(
                new WorldMutationPlan.PlanningGap(WorldMutationPlan.MutationKind.RULE_DELETED,
                        "NO_SUPPORTED_MUTATION_SITE", "/members")));
        WorldMutationEvaluator.GraphLayerInput graph = new WorldMutationEvaluator.GraphLayerInput(
                List.of(new WorldMutationEvaluator.GraphMutantSummary("pending-graph",
                        WorldMutationEvaluator.GraphMutantStatus.UNCLASSIFIED,
                        WorldMutationEvaluator.EquivalenceSource.NONE)),
                TestSuiteRunEvidenceV5.MutationScoreStatus.INCOMPLETE,
                List.of("GRAPH_INPUT_PENDING"), List.of());
        WorldMutationMatrix.ScenarioMutantMatrix matrix = new WorldMutationMatrix.ScenarioMutantMatrix(
                List.of(new WorldMutationMatrix.ScenarioRef("s", fp(10))), List.of());
        WorldMutationEvaluator.Policy optional = new WorldMutationEvaluator.Policy(0, 0, 0,
                false, false, WorldMutationEvaluator.DEFAULT_PURPOSE);

        WorldMutationEvaluator.GateReport report = new WorldMutationEvaluator().evaluate(empty, matrix,
                graph, List.of(), WorldMutationEvaluator.Mode.CERTIFIABLE, optional);

        assertThat(report.status()).isEqualTo(WorldMutationEvaluator.GateStatus.BLOCKED);
        assertThat(report.reasons()).contains("GRAPH_INPUT_PENDING", "GRAPH_LAYER_INCOMPLETE",
                "WORLD_MUTATION_NO_MUTANTS");
    }

    @Test
    void graphPolicyControlsSurvivorAndInconclusiveNABehavior() {
        WorldMutationPlan empty = new WorldMutationPlan("tenant-a", "world-a", 1, fp(1), fp(2),
                "fragment.bloge", 1, fp(3), fp(4), WorldMutationPlan.PLANNER_VERSION,
                new WorldMutationPlan.Policy(4, false), List.of(), List.of(
                new WorldMutationPlan.PlanningGap(WorldMutationPlan.MutationKind.RULE_DELETED,
                        "NO_SUPPORTED_MUTATION_SITE", "/members")));
        WorldMutationMatrix.ScenarioMutantMatrix matrix = new WorldMutationMatrix.ScenarioMutantMatrix(
                List.of(new WorldMutationMatrix.ScenarioRef("s", fp(10))), List.of());
        WorldMutationEvaluator.Policy optionalNoSurvivorRequirement = new WorldMutationEvaluator.Policy(
                0, 0, 0, false, false, WorldMutationEvaluator.DEFAULT_PURPOSE);
        WorldMutationEvaluator.GraphLayerInput survivor = new WorldMutationEvaluator.GraphLayerInput(
                List.of(new WorldMutationEvaluator.GraphMutantSummary("allowed-survivor",
                        WorldMutationEvaluator.GraphMutantStatus.SURVIVED,
                        WorldMutationEvaluator.EquivalenceSource.NONE)),
                TestSuiteRunEvidenceV5.MutationScoreStatus.UNSATISFIED, List.of(), List.of());
        WorldMutationEvaluator.GraphLayerInput inconclusive = new WorldMutationEvaluator.GraphLayerInput(
                List.of(new WorldMutationEvaluator.GraphMutantSummary("timed-out",
                        WorldMutationEvaluator.GraphMutantStatus.INCONCLUSIVE,
                        WorldMutationEvaluator.EquivalenceSource.NONE)),
                TestSuiteRunEvidenceV5.MutationScoreStatus.UNSATISFIED, List.of(), List.of());

        WorldMutationEvaluator.GateReport survivorReport = new WorldMutationEvaluator().evaluate(empty, matrix,
                survivor, List.of(), WorldMutationEvaluator.Mode.EXPLORATORY,
                optionalNoSurvivorRequirement);
        WorldMutationEvaluator.GateReport inconclusiveReport = new WorldMutationEvaluator().evaluate(empty, matrix,
                inconclusive, List.of(), WorldMutationEvaluator.Mode.EXPLORATORY,
                optionalNoSurvivorRequirement);

        assertThat(survivorReport.status()).isEqualTo(WorldMutationEvaluator.GateStatus.NOT_APPLICABLE);
        assertThat(inconclusiveReport.status()).isEqualTo(WorldMutationEvaluator.GateStatus.WARNING);
        assertThat(inconclusiveReport.reasons()).contains("GRAPH_INCONCLUSIVE_LIMIT_EXCEEDED");
    }

    @Test
    void graphProtocolRejectsPayloadCanariesAtEveryReportBoundary() {
        assertThatThrownBy(() -> new WorldMutationEvaluator.GraphMutantSummary(
                "customer payload", WorldMutationEvaluator.GraphMutantStatus.SURVIVED,
                WorldMutationEvaluator.EquivalenceSource.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("customer payload");
        assertThatThrownBy(() -> new WorldMutationEvaluator.GraphMutantSummary(
                "graph-equivalent", WorldMutationEvaluator.GraphMutantStatus.EQUIVALENT,
                WorldMutationEvaluator.EquivalenceSource.NONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorldMutationEvaluator.GraphLayerInput(
                List.of(new WorldMutationEvaluator.GraphMutantSummary("graph-killed",
                        WorldMutationEvaluator.GraphMutantStatus.KILLED,
                        WorldMutationEvaluator.EquivalenceSource.NONE)),
                TestSuiteRunEvidenceV5.MutationScoreStatus.SATISFIED,
                List.of("customer payload"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorldMutationEvaluator.GraphLayerInput(
                List.of(new WorldMutationEvaluator.GraphMutantSummary("graph-killed",
                        WorldMutationEvaluator.GraphMutantStatus.KILLED,
                        WorldMutationEvaluator.EquivalenceSource.NONE)),
                TestSuiteRunEvidenceV5.MutationScoreStatus.SATISFIED,
                List.of(), List.of("customer payload")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new WorldMutationEvaluator.LayerReport("GRAPH", 1, 0, 1,
                1, 0, 0, 1, 10_000, List.of("customer payload"),
                List.of("INDEPENDENT_SEMANTIC_PROOF"), List.of(), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorldMutationEvaluator.LayerReport("GRAPH", 1, 0, 1,
                1, 0, 0, 1, 10_000, List.of("graph-survivor"),
                List.of("customer payload"), List.of(), true))
                .isInstanceOf(IllegalArgumentException.class);
        WorldMutationEvaluator.LayerReport valid = new WorldMutationEvaluator.LayerReport("GRAPH",
                1, 0, 1, 0, 1, 0, 1, 0, List.of("graph-survivor"), List.of(), List.of(), true);
        WorldMutationEvaluator.LayerReport world = new WorldMutationEvaluator.LayerReport("WORLD",
                1, 0, 1, 0, 1, 0, 1, 0, List.of("world-mutant-0001"), List.of(), List.of(), true);
        assertThat(valid.toString()).doesNotContain("customer payload");
        assertThat(world.toString()).doesNotContain("customer payload");
        assertThatThrownBy(() -> new WorldMutationEvaluator.GateReport(
                WorldMutationEvaluator.Mode.CERTIFIABLE, WorldMutationEvaluator.GateStatus.BLOCKED,
                valid, world, List.of(), List.of("customer payload")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new WorldMutationEvaluator.LayerReport("GRAPH", 1, 0, 1,
                1, 0, 0, 1, 10_000, List.of("graph-survivor"),
                List.of("NONE"), List.of(), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void layerReportRequiresExactSurvivorAndEquivalenceSourceClosure() {
        assertThatThrownBy(() -> new WorldMutationEvaluator.LayerReport("WORLD", 2, 0, 2,
                1, 1, 0, 2, 5_000, List.of(), List.of(), List.of(), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorldMutationEvaluator.LayerReport("WORLD", 1, 1, 0,
                0, 0, 0, 0, 0, List.of(), List.of(), List.of(), true))
                .isInstanceOf(IllegalArgumentException.class);

        WorldMutationEvaluator.LayerReport noMutants = new WorldMutationEvaluator.LayerReport(
                "WORLD", 0, 0, 0, 0, 0, 0, 0, 0,
                List.of(), List.of(), List.of("WORLD_MUTATION_NO_MUTANTS"), false);
        assertThat(noMutants.survivors()).isEmpty();
        assertThat(noMutants.equivalenceSources()).isEmpty();
    }

    @Test
    void gateReportRejectsSwappedLayersAndForgedWorldMutantClosure() {
        WorldMutationEvaluator.LayerReport graph = new WorldMutationEvaluator.LayerReport(
                "GRAPH", 0, 0, 0, 0, 0, 0, 0, 0,
                List.of(), List.of(), List.of("GRAPH_MUTATION_NO_MUTANTS"), false);
        WorldMutationEvaluator.LayerReport emptyWorld = new WorldMutationEvaluator.LayerReport(
                "WORLD", 0, 0, 0, 0, 0, 0, 0, 0,
                List.of(), List.of(), List.of("WORLD_MUTATION_NO_MUTANTS"), false);
        assertThatThrownBy(() -> new WorldMutationEvaluator.GateReport(
                WorldMutationEvaluator.Mode.EXPLORATORY, WorldMutationEvaluator.GateStatus.WARNING,
                emptyWorld, graph, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        WorldMutationEvaluator.LayerReport nonEmptyWorld = new WorldMutationEvaluator.LayerReport(
                "WORLD", 1, 0, 1, 1, 0, 0, 1, 10_000,
                List.of(), List.of(), List.of(), true);
        assertThatThrownBy(() -> new WorldMutationEvaluator.GateReport(
                WorldMutationEvaluator.Mode.EXPLORATORY, WorldMutationEvaluator.GateStatus.PASSED,
                graph, nonEmptyWorld, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        WorldMutationEvaluator.MutantResult killed = new WorldMutationEvaluator.MutantResult(
                "world-mutant-0001", WorldMutationPlan.MutationKind.RULE_DELETED,
                WorldMutationEvaluator.EquivalenceSource.NONE,
                WorldMutationEvaluator.MutantStatus.KILLED, 1, 1,
                List.of(new WorldMutationEvaluator.ScenarioResult(
                        "scenario-1", "world-mutant-0001", WorldMutationEvaluator.MutantStatus.KILLED,
                        fp(100), "OK")), "");
        WorldMutationEvaluator.MutantResult duplicate = new WorldMutationEvaluator.MutantResult(
                "world-mutant-0001", WorldMutationPlan.MutationKind.RULE_DELETED,
                WorldMutationEvaluator.EquivalenceSource.NONE,
                WorldMutationEvaluator.MutantStatus.KILLED, 1, 1,
                List.of(new WorldMutationEvaluator.ScenarioResult(
                        "scenario-2", "world-mutant-0001", WorldMutationEvaluator.MutantStatus.KILLED,
                        fp(101), "OK")), "");
        WorldMutationEvaluator.LayerReport twoKilled = new WorldMutationEvaluator.LayerReport(
                "WORLD", 2, 0, 2, 2, 0, 0, 2, 10_000,
                List.of(), List.of(), List.of(), true);
        assertThatThrownBy(() -> new WorldMutationEvaluator.GateReport(
                WorldMutationEvaluator.Mode.EXPLORATORY, WorldMutationEvaluator.GateStatus.PASSED,
                graph, twoKilled, List.of(killed, duplicate), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mutantResultRejectsParentScenarioAndAggregateForgery() {
        assertThatThrownBy(() -> new WorldMutationEvaluator.MutantResult(
                "world-mutant-0001", WorldMutationPlan.MutationKind.RULE_DELETED,
                WorldMutationEvaluator.EquivalenceSource.NONE,
                WorldMutationEvaluator.MutantStatus.SURVIVED, 1, 0,
                List.of(new WorldMutationEvaluator.ScenarioResult(
                        "scenario-1", "world-mutant-0002", WorldMutationEvaluator.MutantStatus.SURVIVED,
                        fp(100), "OK")), ""))
                .isInstanceOf(IllegalArgumentException.class);

        WorldMutationEvaluator.ScenarioResult first = new WorldMutationEvaluator.ScenarioResult(
                "scenario-1", "world-mutant-0001", WorldMutationEvaluator.MutantStatus.SURVIVED,
                fp(100), "OK");
        assertThatThrownBy(() -> new WorldMutationEvaluator.MutantResult(
                "world-mutant-0001", WorldMutationPlan.MutationKind.RULE_DELETED,
                WorldMutationEvaluator.EquivalenceSource.NONE,
                WorldMutationEvaluator.MutantStatus.SURVIVED, 2, 0,
                List.of(first, first), ""))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new WorldMutationEvaluator.MutantResult(
                "world-mutant-0001", WorldMutationPlan.MutationKind.RULE_DELETED,
                WorldMutationEvaluator.EquivalenceSource.NONE,
                WorldMutationEvaluator.MutantStatus.KILLED, 1, 1,
                List.of(new WorldMutationEvaluator.ScenarioResult(
                        "scenario-1", "world-mutant-0001", WorldMutationEvaluator.MutantStatus.SURVIVED,
                        fp(101), "OK")), ""))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new WorldMutationEvaluator.MutantResult(
                "world-mutant-0001", WorldMutationPlan.MutationKind.RULE_DELETED,
                WorldMutationEvaluator.EquivalenceSource.NONE,
                WorldMutationEvaluator.MutantStatus.SURVIVED, 1, 1,
                List.of(new WorldMutationEvaluator.ScenarioResult(
                        "scenario-1", "world-mutant-0001", WorldMutationEvaluator.MutantStatus.KILLED,
                        fp(102), "OK")), ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matrixObservationAcceptsOnlyMachineDiagnosticCode() {
        assertThatThrownBy(() -> new WorldMutationMatrix.Observation("s", fp(10), "m", fp(11),
                WorldMutationMatrix.ObservationStatus.PASSED, fp(12), "raw customer payload"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static WorldMutationEvaluator.GateReport evaluate(WorldMutationPlan plan,
                                                               List<WorldMutationMatrix.ObservationStatus> statuses,
                                                               List<WorldMutationEquivalenceReceipt> receipts,
                                                               WorldMutationEvaluator.Mode mode) {
        WorldMutationMatrix.ScenarioMutantMatrix matrix = matrix(plan, statuses);
        TestSuiteV5.MutationScorePolicy policy = new TestSuiteV5.MutationScorePolicy(
                10_000, 0, true, false);
        TestSuiteRunEvidenceV5.MutationScoreVerdict graph = graphScore();
        return new WorldMutationEvaluator(acceptingAuthority()).evaluate(plan, matrix, graph, receipts, mode,
                WorldMutationEvaluator.Policy.defaults());
    }

    private static TestSuiteRunEvidenceV5.MutationScoreVerdict graphScore() {
        TestSuiteV5.MutationScorePolicy policy = new TestSuiteV5.MutationScorePolicy(
                10_000, 0, true, false);
        return new TestSuiteRunEvidenceV5.MutationScoreVerdict(
                TestSuiteRunEvidenceV5.MutationScoreStatus.SATISFIED, policy, 1, 1, 0, 0, 0,
                1, 10_000, 0, List.of());
    }

    private static WorldMutationMatrix.ScenarioMutantMatrix matrix(
            WorldMutationPlan plan, List<WorldMutationMatrix.ObservationStatus> statuses) {
        List<WorldMutationMatrix.Observation> observations = new ArrayList<>();
        for (int index = 0; index < plan.mutants().size(); index++) {
            WorldMutationPlan.PlannedMutant mutant = plan.mutants().get(index);
            observations.add(new WorldMutationMatrix.Observation("s", fp(10), mutant.mutantId(),
                    mutant.mutantTargetFingerprint(), statuses.get(index), fp(100 + index), "OK"));
        }
        return new WorldMutationMatrix.ScenarioMutantMatrix(
                List.of(new WorldMutationMatrix.ScenarioRef("s", fp(10))), observations);
    }

    private static WorldMutationPlan plan(int count) {
        List<WorldMutationPlan.PlannedMutant> mutants = IntStream.rangeClosed(1, count)
                .mapToObj(index -> mutant(index, WorldMutationPlan.MutationKind.values()[index - 1]))
                .toList();
        return new WorldMutationPlan("tenant-a", "world-a", 1, fp(1), fp(2), "fragment.bloge", 1,
                fp(3), fp(4), WorldMutationPlan.PLANNER_VERSION,
                new WorldMutationPlan.Policy(Math.max(4, count), false), mutants, List.of());
    }

    private static WorldMutationPlan.PlannedMutant mutant(int index, WorldMutationPlan.MutationKind kind) {
        String source = fp(100 + index);
        String graph = fp(200 + index);
        String content = com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint.ofText(
                fp(4) + "\n" + kind + "\n/members/0/rules/0\n" + source + "\n" + graph);
        String target = WorldMutationPlan.targetFingerprintFor(fp(1), fp(2), graph);
        return new WorldMutationPlan.PlannedMutant("world-mutant-%04d".formatted(index), kind,
                new WorldMutationPlan.Site(kind, "/members/0/rules/0", 1, 1), fp(3), source,
                graph, target, content);
    }

    private static WorldMutationEquivalenceReceipt receipt(WorldMutationPlan plan,
                                                            WorldMutationPlan.PlannedMutant mutant) {
        return receiptWithTenant(plan, plan.tenantId(), mutant);
    }

    private static WorldMutationEquivalenceReceipt receiptWithTenant(WorldMutationPlan plan, String tenant) {
        return receiptWithTenant(plan, tenant, plan.mutants().get(0));
    }

    private static WorldMutationEquivalenceReceipt receiptWithTenant(WorldMutationPlan plan, String tenant,
                                                                      WorldMutationPlan.PlannedMutant mutant) {
        return WorldMutationEquivalenceReceipt.semantic("receipt-1", tenant, plan.planFingerprint(),
                mutant.mutantId(), mutant.baselineFragmentFingerprint(), WorldMutationEvaluator.DEFAULT_PURPOSE,
                mutant.mutantSourceFingerprint(), mutant.mutantGraphFingerprint(), mutant.mutantTargetFingerprint(),
                "semantic-authority", "PROOF_ACCEPTED");
    }

    private static WorldMutationEquivalenceAuthority acceptingAuthority() {
        return (receipt, tenant, plan, mutant, purpose) -> verified(receipt);
    }

    private static WorldMutationEquivalenceAuthority.Verification verified(
            WorldMutationEquivalenceReceipt receipt) {
        return new WorldMutationEquivalenceAuthority.Verification(true, receipt.authorityId(),
                receipt.receiptFingerprint());
    }

    private static String fp(int value) {
        return "sha256:%064x".formatted(value);
    }
}
