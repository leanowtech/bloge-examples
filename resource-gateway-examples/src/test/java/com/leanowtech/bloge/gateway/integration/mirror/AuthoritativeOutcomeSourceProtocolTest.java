package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoritativeOutcomeSourceProtocolTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void livePageIsContentAddressedAndTamperEvident() {
        AuthoritativeOutcomeSourcePage page = livePage().seal(mapper);

        page.verify(mapper);
        assertThat(page.pageFingerprint()).startsWith("sha256:");
        assertThat(page.entries()).hasSize(1);
        assertThat(page.sourceSeal().signed()).isFalse();

        AuthoritativeOutcomeSourcePage tampered =
                new AuthoritativeOutcomeSourcePage(
                        page.schemaVersion(), page.pageFingerprint(), page.scope(),
                        page.connectorId(), page.connectorGeneration(), page.streamKind(),
                        page.streamId(), page.controlCommandRef(), page.sequence(),
                        page.previousPageFingerprint(), page.previousCursorRef(),
                        ref(AuthoritativeOutcomeSourcePage.CURSOR_KIND, "cursor-2-tampered", 'f'),
                        page.watermark(), page.producedAt(), page.entries(), page.sourceSeal());

        assertThatThrownBy(() -> tampered.verify(mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint mismatch");
    }

    @Test
    void liveAndBackfillChainsCannotMasqueradeAsEachOther() {
        AuthoritativeOutcomeSourcePage live = livePage();

        assertThatThrownBy(() -> new AuthoritativeOutcomeSourcePage(
                live.schemaVersion(), "", live.scope(), live.connectorId(), 1,
                AuthoritativeOutcomeSourcePage.StreamKind.LIVE, "repair-2026-07",
                null, 1, live.previousPageFingerprint(), live.previousCursorRef(),
                live.nextCursorRef(), live.watermark(), live.producedAt(),
                live.entries(), VisualRunEvidenceSeal.unsigned()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("live stream");

        assertThatThrownBy(() -> new AuthoritativeOutcomeSourcePage(
                live.schemaVersion(), "", live.scope(), live.connectorId(), 1,
                AuthoritativeOutcomeSourcePage.StreamKind.BACKFILL, "repair-2026-07",
                null, 1, live.previousPageFingerprint(), live.previousCursorRef(),
                live.nextCursorRef(), live.watermark(), live.producedAt(),
                live.entries(), VisualRunEvidenceSeal.unsigned()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("controlCommandRef");
    }

    @Test
    void sourceMutationRequiresAddressedUnsignedObservationAndExactPredecessor() {
        AuthoritativeOutcomeObservation unaddressed =
                AuthoritativeOutcomeTestFixtures.matched();
        AuthoritativeOutcomeObservation addressed = addressed(unaddressed);

        assertThatThrownBy(() -> entry(unaddressed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("addressed unsigned observation");

        AuthoritativeOutcomeObservation signedLooking =
                addressed.withObservationSeal(new VisualRunEvidenceSeal(
                        VisualRunEvidenceSeal.SCHEMA_VERSION,
                        AuthoritativeOutcomeTestFixtures.fingerprint('a'),
                        "Ed25519", "customer-key", Instant.parse("2026-07-10T00:00:00Z"),
                        "signature"));
        assertThatThrownBy(() -> entry(signedLooking))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("addressed unsigned observation");

        assertThatThrownBy(() -> new AuthoritativeOutcomeSourcePage.Entry(
                1, AuthoritativeOutcomeSourcePage.Operation.UPSERT,
                AuthoritativeOutcomeTestFixtures.fingerprint('b'), null, addressed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("predecessor");
    }

    @Test
    void revokeMutationCarriesARealSuccessorAndExactAffectedSource() {
        AuthoritativeOutcomeObservation current =
                addressed(AuthoritativeOutcomeTestFixtures.matched());
        AuthoritativeOutcomeObservation successor = addressed(
                AuthoritativeOutcomeTestFixtures.successor(
                        current,
                        AuthoritativeOutcomeObservation.Reconciliation.CENSORED,
                        List.of(),
                        List.of(AuthoritativeOutcomeTestFixtures.watermark(
                                "settlement-ledger",
                                AuthoritativeOutcomeTestFixtures.WINDOW_CLOSES_AT)),
                        AuthoritativeOutcomeTestFixtures.RECONCILED_AT.plusSeconds(60)));

        AuthoritativeOutcomeSourcePage.Entry revoke =
                new AuthoritativeOutcomeSourcePage.Entry(
                        1, AuthoritativeOutcomeSourcePage.Operation.REVOKE,
                        current.observationFingerprint(),
                        AuthoritativeOutcomeTestFixtures.ref(
                                "AUTHORITATIVE_OUTCOME_SOURCE_RECORD",
                                "settlement-001", 'c'),
                        successor);

        assertThat(revoke.operation())
                .isEqualTo(AuthoritativeOutcomeSourcePage.Operation.REVOKE);
        assertThat(revoke.observation().revision()).isEqualTo(2);
        assertThatThrownBy(() -> new AuthoritativeOutcomeSourcePage.Entry(
                1, AuthoritativeOutcomeSourcePage.Operation.REVOKE,
                "", revoke.affectedSourceRef(), current))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void backfillAndRevocationCommandsHaveDisjointMaterial() {
        AuthoritativeOutcomeConnectorControlCommand backfill = backfill().seal(mapper);

        backfill.verify(mapper);
        assertThat(backfill.artifactRef().kind())
                .isEqualTo(AuthoritativeOutcomeConnectorControlCommand.ARTIFACT_KIND);
        assertThat(backfill.streamId()).isEqualTo("repair-2026-07");

        AuthoritativeOutcomeConnectorControlCommand revoke =
                new AuthoritativeOutcomeConnectorControlCommand(
                        "", "revoke-ledger-generation-7", 1, "", scope(),
                        "settlement-ledger", 7,
                        AuthoritativeOutcomeConnectorControlCommand.CommandType.REVOKE_GENERATION,
                        "", null, "", null, "SOURCE_AUTHORITY_REVOKED",
                        Instant.parse("2026-07-12T00:00:00Z"),
                        Instant.parse("2026-07-13T00:00:00Z"),
                        VisualRunEvidenceSeal.unsigned()).seal(mapper);
        revoke.verify(mapper);
        assertThat(revoke.streamId()).isBlank();

        assertThatThrownBy(() -> new AuthoritativeOutcomeConnectorControlCommand(
                "", "bad-revoke", 1, "", scope(), "settlement-ledger", 7,
                AuthoritativeOutcomeConnectorControlCommand.CommandType.REVOKE_GENERATION,
                "repair", backfill.eventTimeRange(),
                backfill.baselinePageFingerprint(), backfill.baselineCursorRef(),
                "SOURCE_AUTHORITY_REVOKED", backfill.requestedAt(), backfill.expiresAt(),
                VisualRunEvidenceSeal.unsigned()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backfill stream material");
    }

    @Test
    void productionSourceDescriptorCannotAdvertiseWeakenedTransport() {
        AuthoritativeOutcomeSource.Descriptor secure =
                new AuthoritativeOutcomeSource.Descriptor(
                        AuthoritativeOutcomeSource.Descriptor.SCHEMA_VERSION,
                        true, true, true, true, true, true);
        assertThat(secure.mutualTls()).isTrue();

        assertThatThrownBy(() -> new AuthoritativeOutcomeSource.Descriptor(
                AuthoritativeOutcomeSource.Descriptor.SCHEMA_VERSION,
                true, true, true, false, true, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("descriptor");
    }

    private AuthoritativeOutcomeSourcePage livePage() {
        return new AuthoritativeOutcomeSourcePage(
                "", "", scope(), "settlement-ledger", 7,
                AuthoritativeOutcomeSourcePage.StreamKind.LIVE, "live", null,
                1, AuthoritativeOutcomeTestFixtures.fingerprint('a'),
                ref(AuthoritativeOutcomeSourcePage.CURSOR_KIND, "cursor-1", 'b'),
                ref(AuthoritativeOutcomeSourcePage.CURSOR_KIND, "cursor-2", 'c'),
                new AuthoritativeOutcomeSourcePage.SourceWatermark(
                        ref(AuthoritativeOutcomeSourcePage.WATERMARK_KIND,
                                "ledger-watermark-44", 'd'),
                        Instant.parse("2026-07-10T23:59:00Z"),
                        Instant.parse("2026-07-11T00:00:00Z")),
                Instant.parse("2026-07-11T00:00:01Z"),
                List.of(entry(addressed(AuthoritativeOutcomeTestFixtures.matched()))),
                VisualRunEvidenceSeal.unsigned());
    }

    private AuthoritativeOutcomeConnectorControlCommand backfill() {
        return new AuthoritativeOutcomeConnectorControlCommand(
                "", "backfill-ledger-july", 1, "", scope(),
                "settlement-ledger", 7,
                AuthoritativeOutcomeConnectorControlCommand.CommandType.BACKFILL,
                "repair-2026-07",
                new AuthoritativeOutcomeConnectorControlCommand.EventTimeRange(
                        Instant.parse("2026-07-01T00:00:00Z"),
                        Instant.parse("2026-08-01T00:00:00Z")),
                AuthoritativeOutcomeTestFixtures.fingerprint('d'),
                ref(AuthoritativeOutcomeSourcePage.CURSOR_KIND, "cursor-july", 'e'),
                "LATE_LEDGER_REPAIR",
                Instant.parse("2026-08-02T00:00:00Z"),
                Instant.parse("2026-08-09T00:00:00Z"),
                VisualRunEvidenceSeal.unsigned());
    }

    private AuthoritativeOutcomeSourcePage.Entry entry(
            AuthoritativeOutcomeObservation observation) {
        return new AuthoritativeOutcomeSourcePage.Entry(
                1, AuthoritativeOutcomeSourcePage.Operation.UPSERT,
                observation.revision() == 1
                        ? "" : AuthoritativeOutcomeTestFixtures.fingerprint('f'),
                null, observation);
    }

    private AuthoritativeOutcomeObservation addressed(
            AuthoritativeOutcomeObservation observation) {
        return observation.withFingerprint(
                observation.calculateFingerprint(mapper));
    }

    private static CapabilitySnapshot.Scope scope() {
        return DomainFidelityTestFixtures.scope("support");
    }

    private static MirrorArtifactRef ref(String kind, String id, char material) {
        return AuthoritativeOutcomeTestFixtures.ref(kind, id, material);
    }
}
