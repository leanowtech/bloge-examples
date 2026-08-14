package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Instant;
import java.util.List;

/** Shared deterministic source page, command, and checkpoint fixtures. */
final class AuthoritativeOutcomeSourceTestFixtures {
    static final Instant PAGE_AT = Instant.parse("2026-07-11T00:00:01Z");

    private AuthoritativeOutcomeSourceTestFixtures() {
    }

    static AuthoritativeOutcomeSourceCheckpointRepository.Registration liveRegistration() {
        return new AuthoritativeOutcomeSourceCheckpointRepository.Registration(
                liveKey(), fingerprint('a'), cursor("cursor-1", 'b'));
    }

    static AuthoritativeOutcomeSourceCheckpointRepository.StreamKey liveKey() {
        return new AuthoritativeOutcomeSourceCheckpointRepository.StreamKey(
                scope(), "settlement-ledger", 7,
                AuthoritativeOutcomeSourcePage.StreamKind.LIVE, "live");
    }

    static AuthoritativeOutcomeSourcePage livePage(ObjectMapper mapper) {
        AuthoritativeOutcomeObservation observation = addressed(
                mapper, AuthoritativeOutcomeTestFixtures.matched());
        return page(
                mapper,
                AuthoritativeOutcomeSourcePage.StreamKind.LIVE,
                "live", null, 1, fingerprint('a'), cursor("cursor-1", 'b'),
                cursor("cursor-2", 'c'),
                List.of(new AuthoritativeOutcomeSourcePage.Entry(
                        1, AuthoritativeOutcomeSourcePage.Operation.UPSERT,
                        "", null, observation)));
    }

    static AuthoritativeOutcomeSourcePage page(
            ObjectMapper mapper,
            AuthoritativeOutcomeSourcePage.StreamKind kind,
            String streamId,
            MirrorArtifactRef commandRef,
            long sequence,
            String previousPageFingerprint,
            MirrorArtifactRef previousCursor,
            MirrorArtifactRef nextCursor,
            List<AuthoritativeOutcomeSourcePage.Entry> entries) {
        return new AuthoritativeOutcomeSourcePage(
                "", "", scope(), "settlement-ledger", 7,
                kind, streamId, commandRef, sequence, previousPageFingerprint,
                previousCursor, nextCursor,
                new AuthoritativeOutcomeSourcePage.SourceWatermark(
                        ref(AuthoritativeOutcomeSourcePage.WATERMARK_KIND,
                                "ledger-watermark-" + sequence, (char) ('d' + sequence - 1)),
                        Instant.parse("2026-07-10T23:59:00Z").plusSeconds(sequence),
                        Instant.parse("2026-07-11T00:00:00Z").plusSeconds(sequence)),
                PAGE_AT.plusSeconds(sequence), entries,
                VisualRunEvidenceSeal.unsigned()).seal(mapper);
    }

    static AuthoritativeOutcomeConnectorControlCommand backfill(ObjectMapper mapper) {
        AuthoritativeOutcomeConnectorControlCommand addressed =
                new AuthoritativeOutcomeConnectorControlCommand(
                        "", "backfill-ledger-july", 1, "", scope(),
                        "settlement-ledger", 7,
                        AuthoritativeOutcomeConnectorControlCommand.CommandType.BACKFILL,
                        "repair-2026-07",
                        new AuthoritativeOutcomeConnectorControlCommand.EventTimeRange(
                                Instant.parse("2026-07-01T00:00:00Z"),
                                Instant.parse("2026-08-01T00:00:00Z")),
                        fingerprint('e'), cursor("cursor-july", 'f'),
                        "LATE_LEDGER_REPAIR",
                        Instant.parse("2026-08-02T00:00:00Z"),
                        Instant.parse("2026-08-09T00:00:00Z"),
                        VisualRunEvidenceSeal.unsigned()).seal(mapper);
        return addressed.withAuthoritySeal(signedSeal(addressed.commandFingerprint()));
    }

    static AuthoritativeOutcomeConnectorControlCommand revoke(ObjectMapper mapper) {
        AuthoritativeOutcomeConnectorControlCommand addressed =
                new AuthoritativeOutcomeConnectorControlCommand(
                        "", "revoke-ledger-generation-7", 1, "", scope(),
                        "settlement-ledger", 7,
                        AuthoritativeOutcomeConnectorControlCommand.CommandType.REVOKE_GENERATION,
                        "", null, "", null, "SOURCE_AUTHORITY_REVOKED",
                        Instant.parse("2026-08-03T00:00:00Z"),
                        Instant.parse("2026-08-10T00:00:00Z"),
                        VisualRunEvidenceSeal.unsigned()).seal(mapper);
        return addressed.withAuthoritySeal(signedSeal(addressed.commandFingerprint()));
    }

    static AuthoritativeOutcomeObservation addressed(
            ObjectMapper mapper, AuthoritativeOutcomeObservation observation) {
        return observation.withFingerprint(observation.calculateFingerprint(mapper));
    }

    static VisualRunEvidenceSeal signedSeal(String materialFingerprint) {
        return new VisualRunEvidenceSeal(
                VisualRunEvidenceSeal.SCHEMA_VERSION,
                materialFingerprint, "Ed25519", "customer-data-authority",
                PAGE_AT, "deterministic-signature");
    }

    static MirrorArtifactRef cursor(String id, char material) {
        return ref(AuthoritativeOutcomeSourcePage.CURSOR_KIND, id, material);
    }

    static MirrorArtifactRef ref(String kind, String id, char material) {
        return AuthoritativeOutcomeTestFixtures.ref(kind, id, material);
    }

    static CapabilitySnapshot.Scope scope() {
        return DomainFidelityTestFixtures.scope("support");
    }

    static String fingerprint(char material) {
        return AuthoritativeOutcomeTestFixtures.fingerprint(material);
    }
}
