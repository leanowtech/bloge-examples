package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoritativeOutcomeObservationTest {
    private static final Instant ACTION_AT =
            Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant WINDOW_CLOSES_AT =
            Instant.parse("2026-07-08T00:00:00Z");
    private static final Instant RECONCILED_AT =
            Instant.parse("2026-07-10T00:00:00Z");

    @Test
    void derivesClosedWindowMatchInsteadOfTrustingProducerOutcome() {
        AuthoritativeOutcomeObservation matched =
                observation(
                        AuthoritativeOutcomeObservation
                                .Reconciliation.MATCH,
                        List.of(fact('a')),
                        List.of(watermark(
                                "settlement-ledger",
                                WINDOW_CLOSES_AT)));

        assertThat(matched.reconciliation())
                .isEqualTo(
                        AuthoritativeOutcomeObservation
                                .Reconciliation.MATCH);
        assertThat(matched.evidenceComplete()).isTrue();

        assertThatThrownBy(() ->
                observation(
                        AuthoritativeOutcomeObservation
                                .Reconciliation.MISMATCH,
                        List.of(fact('a')),
                        List.of(watermark(
                                "settlement-ledger",
                                WINDOW_CLOSES_AT))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not derived");
    }

    @Test
    void keepsProvisionalFactsPendingUntilEveryAuthorityClosesTheWindow() {
        AuthoritativeOutcomeObservation pending =
                observation(
                        AuthoritativeOutcomeObservation
                                .Reconciliation.PENDING,
                        List.of(fact(
                                "settlement-ledger",
                                "settlement-001",
                                'a',
                                'c')),
                        List.of(watermark(
                                "settlement-ledger",
                                WINDOW_CLOSES_AT.minusSeconds(1))));

        assertThat(pending.reconciliation())
                .isEqualTo(
                        AuthoritativeOutcomeObservation
                                .Reconciliation.PENDING);
        assertThat(pending.evidenceComplete()).isTrue();
    }

    @Test
    void reconcilesDelayedArrivalByEventTimeInsteadOfIngestionTime() {
        AuthoritativeOutcomeObservation delayed =
                observation(
                        AuthoritativeOutcomeObservation
                                .Reconciliation.MATCH,
                        List.of(fact(
                                "settlement-ledger",
                                "settlement-delayed",
                                'a',
                                'c')),
                        List.of(watermark(
                                "settlement-ledger",
                                WINDOW_CLOSES_AT)));

        assertThat(delayed.authorityFacts().getFirst()
                .occurredAt()).isBefore(WINDOW_CLOSES_AT);
        assertThat(delayed.authorityFacts().getFirst()
                .recordedAt()).isAfter(WINDOW_CLOSES_AT);
        assertThat(delayed.reconciliation())
                .isEqualTo(
                        AuthoritativeOutcomeObservation
                                .Reconciliation.MATCH);
    }

    @Test
    void distinguishesCensoringFromConflictingAuthoritativeFacts() {
        AuthoritativeOutcomeObservation censored =
                observation(
                        AuthoritativeOutcomeObservation
                                .Reconciliation.CENSORED,
                        List.of(),
                        List.of(watermark(
                                "settlement-ledger",
                                WINDOW_CLOSES_AT)));
        AuthoritativeOutcomeObservation conflict =
                observation(
                        AuthoritativeOutcomeObservation
                                .Reconciliation.CONFLICT,
                        List.of(
                                fact(
                                        "complaint-ledger",
                                        "complaint-001",
                                        'b',
                                        'c'),
                                fact(
                                        "settlement-ledger",
                                        "settlement-001",
                                        'a',
                                        'd')),
                        List.of(
                                watermark(
                                        "complaint-ledger",
                                        WINDOW_CLOSES_AT),
                                watermark(
                                        "settlement-ledger",
                                        WINDOW_CLOSES_AT)));

        assertThat(censored.reconciliation())
                .isEqualTo(
                        AuthoritativeOutcomeObservation
                                .Reconciliation.CENSORED);
        assertThat(conflict.reconciliation())
                .isEqualTo(
                        AuthoritativeOutcomeObservation
                                .Reconciliation.CONFLICT);
    }

    @Test
    void rejectsPostTreatmentSelectionAndCrossAttributionFacts() {
        assertThatThrownBy(() ->
                observation(
                        AuthoritativeOutcomeObservation
                                .Reconciliation.CENSORED,
                        List.of(),
                        List.of(watermark(
                                "settlement-ledger",
                                WINDOW_CLOSES_AT)),
                        ACTION_AT.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("precede");
        assertThatThrownBy(() ->
                observation(
                        AuthoritativeOutcomeObservation
                                .Reconciliation.CENSORED,
                        List.of(),
                        List.of(watermark(
                                "settlement-ledger",
                                WINDOW_CLOSES_AT)),
                        ACTION_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("precede");

        AuthoritativeOutcomeObservation.AuthorityFact drifted =
                new AuthoritativeOutcomeObservation.AuthorityFact(
                        "settlement-ledger",
                        ref(
                                "AUTHORITATIVE_OUTCOME_SOURCE_RECORD",
                                "settlement-drifted",
                                '4'),
                        fingerprint('f'),
                        fingerprint('b'),
                        fingerprint('a'),
                        WINDOW_CLOSES_AT.minusSeconds(1),
                        WINDOW_CLOSES_AT.plusSeconds(30),
                        true);
        assertThatThrownBy(() ->
                observation(
                        AuthoritativeOutcomeObservation
                                .Reconciliation.MATCH,
                        List.of(drifted),
                        List.of(watermark(
                                "settlement-ledger",
                                WINDOW_CLOSES_AT))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attribution closure");
    }

    private static AuthoritativeOutcomeObservation observation(
            AuthoritativeOutcomeObservation.Reconciliation
                    reconciliation,
            List<AuthoritativeOutcomeObservation.AuthorityFact>
                    facts,
            List<AuthoritativeOutcomeObservation
                    .AuthorityWatermark> watermarks) {
        return observation(
                reconciliation,
                facts,
                watermarks,
                ACTION_AT.minusSeconds(60));
    }

    private static AuthoritativeOutcomeObservation observation(
            AuthoritativeOutcomeObservation.Reconciliation
                    reconciliation,
            List<AuthoritativeOutcomeObservation.AuthorityFact>
                    facts,
            List<AuthoritativeOutcomeObservation
                    .AuthorityWatermark> watermarks,
            Instant selectedAt) {
        return new AuthoritativeOutcomeObservation(
                AuthoritativeOutcomeObservation.SCHEMA_VERSION,
                "outcome-refund-boundary",
                1,
                "",
                DomainFidelityTestFixtures.scope("support"),
                ref(
                        DomainFidelityInventory.ARTIFACT_KIND,
                        "refund-support",
                        '1'),
                "refund-boundary",
                ref("SCENARIO_CASE", "refund-boundary", '2'),
                ref("CAPABILITY", "refund", '3'),
                ref(
                        "OUTCOME_DEFINITION",
                        "refund-settled",
                        '4'),
                ref(
                        "OUTCOME_ATTRIBUTION_POLICY",
                        "refund-seven-day-window",
                        '5'),
                ref(
                        "OUTCOME_AUTHORITY_SET",
                        "refund-ledgers",
                        '6'),
                new AuthoritativeOutcomeObservation.SelectionProof(
                        ref(
                                "OUTCOME_CALIBRATION_COHORT",
                                "refunds-2026-07",
                                '7'),
                        ref(
                                "OUTCOME_SAMPLING_FRAME",
                                "eligible-refunds-2026-07",
                                '8'),
                        "amount-band-100-500",
                        fingerprint('9'),
                        selectedAt,
                        10_000,
                        1_000,
                        42,
                        AuthoritativeOutcomeObservation
                                .SelectionMode.HASH_PARTITION),
                fingerprint('a'),
                fingerprint('b'),
                fingerprint('a'),
                new AuthoritativeOutcomeObservation
                        .AttributionWindow(
                        ACTION_AT,
                        ACTION_AT,
                        WINDOW_CLOSES_AT),
                RECONCILED_AT,
                DomainFidelityTestFixtures.NOW,
                watermarks,
                facts,
                reconciliation,
                true,
                VisualRunEvidenceSeal.unsigned());
    }

    private static AuthoritativeOutcomeObservation.AuthorityFact
    fact(char outcome) {
        return fact(
                "settlement-ledger",
                "settlement-001",
                outcome,
                'c');
    }

    private static AuthoritativeOutcomeObservation.AuthorityFact
    fact(
            String authorityId,
            String sourceId,
            char outcome,
            char sourceMaterial) {
        return new AuthoritativeOutcomeObservation.AuthorityFact(
                authorityId,
                ref(
                        "AUTHORITATIVE_OUTCOME_SOURCE_RECORD",
                        sourceId,
                        sourceMaterial),
                fingerprint('a'),
                fingerprint('b'),
                fingerprint(outcome),
                WINDOW_CLOSES_AT.minusSeconds(1),
                WINDOW_CLOSES_AT.plusSeconds(30),
                true);
    }

    private static AuthoritativeOutcomeObservation
            .AuthorityWatermark watermark(
            String authorityId,
            Instant eventTimeThrough) {
        return new AuthoritativeOutcomeObservation
                .AuthorityWatermark(
                authorityId,
                ref(
                        "AUTHORITATIVE_OUTCOME_SOURCE_WATERMARK",
                        authorityId + "-watermark",
                        'd'),
                eventTimeThrough,
                RECONCILED_AT.minusSeconds(1));
    }

    private static MirrorArtifactRef ref(
            String kind, String id, char material) {
        return DomainFidelityTestFixtures.ref(
                kind, id, material);
    }

    private static String fingerprint(char material) {
        char safe = Character.toLowerCase(material);
        if (safe < 'a' || safe > 'f') {
            safe = 'a';
        }
        return "sha256:" + String.valueOf(safe).repeat(64);
    }
}
