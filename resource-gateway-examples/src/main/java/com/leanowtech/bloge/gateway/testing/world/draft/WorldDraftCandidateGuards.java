package com.leanowtech.bloge.gateway.testing.world.draft;

import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import java.time.Clock;

/** Package-private security and metadata guards kept outside the lifecycle coordinator. */
final class WorldDraftCandidateGuards {
    private WorldDraftCandidateGuards() { }

    static WorldDraftSourceAuthority.SourceMetadata inspect(WorldDraftSourceAuthority authority,
                                                             WorldDraftSourceRef source,
                                                             WorldDraftCandidateService.Access access) {
        try {
            WorldDraftSourceAuthority.SourceMetadata result = authority.inspect(source, access);
            if (result == null) throw fail(WorldDraftCandidateException.Code.SOURCE_NOT_FOUND);
            return result;
        } catch (WorldDraftCandidateException failure) { throw failure; }
        catch (RuntimeException failure) { throw fail(WorldDraftCandidateException.Code.SOURCE_NOT_AUTHORIZED); }
    }

    static WorldDraftSourceAuthority.SourcePayload read(WorldDraftSourceAuthority authority,
                                                         WorldDraftSourceAuthority.SourceMetadata metadata,
                                                         WorldDraftCandidateService.Access access) {
        try {
            WorldDraftSourceAuthority.SourcePayload result = authority.read(metadata, access);
            if (result == null) throw fail(WorldDraftCandidateException.Code.SOURCE_READ_FAILED);
            return result;
        } catch (WorldDraftCandidateException failure) { throw failure; }
        catch (RuntimeException failure) { throw fail(WorldDraftCandidateException.Code.SOURCE_READ_FAILED); }
    }

    static void metadata(WorldDraftSourceAuthority.SourceMetadata metadata, WorldDraftSourceRef source,
                          WorldDraftCandidateService.Access access, Clock clock) {
        if (metadata == null || !source.equals(metadata.source()) || !access.tenantId().equals(metadata.tenantId())) {
            throw fail(WorldDraftCandidateException.Code.SOURCE_INTEGRITY);
        }
        if (!metadata.expiresAt().isAfter(clock.instant())) {
            throw fail(WorldDraftCandidateException.Code.SOURCE_EXPIRED);
        }
        if (!metadata.published() || !metadata.valid()
                || !metadata.schemaFingerprint().equals(metadata.recomputedSchemaFingerprint())
                || !metadata.metadataFingerprint().equals(metadata.recomputedFingerprint())
                || !VisualSchemaValidator.validateEnvelope(metadata.requestSchema(), "/request").isEmpty()
                || !VisualSchemaValidator.validateEnvelope(metadata.responseSchema(), "/response").isEmpty()) {
            throw fail(WorldDraftCandidateException.Code.SOURCE_INTEGRITY);
        }
    }

    static void require(WorldDraftCandidate candidate, WorldDraftState... allowed) {
        for (WorldDraftState state : allowed) if (candidate.state() == state) return;
        throw fail(WorldDraftCandidateException.Code.STATE_TRANSITION_INVALID);
    }

    static String text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max
                || value.chars().anyMatch(Character::isISOControl)) throw invalid();
        return value.trim();
    }
    static WorldDraftCandidateException invalid() { return fail(WorldDraftCandidateException.Code.INVALID_INPUT); }
    static WorldDraftCandidateException fail(WorldDraftCandidateException.Code code) {
        return new WorldDraftCandidateException(code);
    }
}
