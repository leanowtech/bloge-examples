package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Instant;
import java.util.List;

/** Shared deterministic, payload-free authoritative outcome fixtures. */
final class AuthoritativeOutcomeTestFixtures {
    static final Instant ACTION_AT =
            Instant.parse("2026-07-01T00:00:00Z");
    static final Instant WINDOW_CLOSES_AT =
            Instant.parse("2026-07-08T00:00:00Z");
    static final Instant RECONCILED_AT =
            Instant.parse("2026-07-10T00:00:00Z");

    private AuthoritativeOutcomeTestFixtures() {
    }

    static AuthoritativeOutcomeObservation matched() {
        return observation(
                AuthoritativeOutcomeObservation
                        .Reconciliation.MATCH,
                List.of(fact(
                        "settlement-ledger",
                        "settlement-001",
                        'a',
                        'c',
                        true)),
                List.of(watermark(
                        "settlement-ledger",
                        WINDOW_CLOSES_AT)));
    }

    static AuthoritativeOutcomeObservation observation(
            AuthoritativeOutcomeObservation.Reconciliation
                    reconciliation,
            List<AuthoritativeOutcomeObservation.AuthorityFact>
                    facts,
            List<AuthoritativeOutcomeObservation
                    .AuthorityWatermark> watermarks) {
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
                        ACTION_AT.minusSeconds(60),
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
                facts.stream().allMatch(
                        AuthoritativeOutcomeObservation
                                .AuthorityFact::evidenceComplete),
                VisualRunEvidenceSeal.unsigned());
    }

    static AuthoritativeOutcomeObservation.AuthorityFact
    fact(
            String authorityId,
            String sourceId,
            char outcome,
            char sourceMaterial,
            boolean evidenceComplete) {
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
                evidenceComplete);
    }

    static AuthoritativeOutcomeObservation.AuthorityWatermark
    watermark(
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

    static MirrorArtifactRef ref(
            String kind, String id, char material) {
        return DomainFidelityTestFixtures.ref(
                kind, id, material);
    }

    static String fingerprint(char material) {
        char safe = Character.toLowerCase(material);
        if (safe < 'a' || safe > 'f') {
            safe = 'a';
        }
        return "sha256:" + String.valueOf(safe).repeat(64);
    }
}
