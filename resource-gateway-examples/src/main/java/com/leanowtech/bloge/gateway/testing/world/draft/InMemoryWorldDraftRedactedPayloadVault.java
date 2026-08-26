package com.leanowtech.bloge.gateway.testing.world.draft;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Content-addressed in-memory vault used by the isolated service and focused tests. */
public final class InMemoryWorldDraftRedactedPayloadVault implements WorldDraftRedactedPayloadVault {
    private final ConcurrentHashMap<WorldDraftRedactedPayloadRef, Entry> values = new ConcurrentHashMap<>();

    @Override
    public StoredPayload put(WorldDraftRedactedPayloadRef ref, WorldDraftRedactedPayload payload,
                             WorldDraftCandidateService.Access access) {
        authorize(ref, access);
        StoredPayload candidate = new StoredPayload(ref, payload);
        Entry existing = values.putIfAbsent(ref, new Entry(candidate, null, false));
        if (existing == null) return candidate;
        if (!existing.payload().ref().equals(candidate.ref())
                || !existing.payload().payload().requestFingerprint().equals(candidate.payload().requestFingerprint())
                || !existing.payload().payload().responseFingerprint().equals(candidate.payload().responseFingerprint())) {
            throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.CAS_CONFLICT);
        }
        return existing.payload();
    }

    @Override
    public Optional<StoredPayload> read(WorldDraftRedactedPayloadRef ref, WorldDraftCandidateService.Access access) {
        authorize(ref, access);
        Entry entry = values.get(ref);
        return entry == null || entry.revoked() ? Optional.empty() : Optional.of(entry.payload());
    }

    @Override
    public Optional<StoredPayload> readPublished(WorldDraftRedactedPayloadRef ref, PublishedBinding binding,
                                                 WorldDraftCandidateService.Access access) {
        authorize(ref, access);
        binding.requireMatches(ref, access);
        Entry entry = values.get(ref);
        return entry == null || entry.revoked() || !binding.equals(entry.binding())
                ? Optional.empty() : Optional.of(entry.payload());
    }

    @Override
    public void pin(WorldDraftRedactedPayloadRef ref, PublishedBinding binding,
                    WorldDraftCandidateService.Access access) {
        authorize(ref, access);
        binding.requireMatches(ref, access);
        values.compute(ref, (key, entry) -> {
            if (entry == null || entry.revoked()) throw publicationInvalid();
            if (entry.binding() != null && !entry.binding().equals(binding)) throw publicationInvalid();
            return new Entry(entry.payload(), binding, false);
        });
    }

    @Override
    public void unpin(WorldDraftRedactedPayloadRef ref, PublishedBinding binding,
                      WorldDraftCandidateService.Access access) {
        authorize(ref, access);
        if (binding == null) return;
        binding.requireMatches(ref, access);
        values.computeIfPresent(ref, (key, entry) -> entry.binding() != null && entry.binding().equals(binding)
                ? new Entry(entry.payload(), null, entry.revoked()) : entry);
    }

    @Override
    public void revoke(WorldDraftRedactedPayloadRef ref, WorldDraftCandidateService.Access access) {
        authorize(ref, access);
        values.computeIfPresent(ref, (key, entry) -> new Entry(entry.payload(), entry.binding(), true));
    }

    private record Entry(StoredPayload payload, PublishedBinding binding, boolean revoked) { }

    private static void authorize(WorldDraftRedactedPayloadRef ref, WorldDraftCandidateService.Access access) {
        if (ref == null || access == null || !ref.tenantId().equals(access.tenantId())
                || !WorldDraftCandidateService.PURPOSE.equals(access.purpose())) {
            throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.SOURCE_NOT_AUTHORIZED);
        }
    }

    private static WorldDraftCandidateException publicationInvalid() {
        return new WorldDraftCandidateException(WorldDraftCandidateException.Code.PUBLICATION_INVALID);
    }
}
