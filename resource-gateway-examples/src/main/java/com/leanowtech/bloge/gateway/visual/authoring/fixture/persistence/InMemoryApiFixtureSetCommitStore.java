package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSummary;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.GeneratedDefaultFixture;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceSaveReceiptClosure;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandReceipt;

import java.time.Clock;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.ApiFixtureSetCommitStoreException.Code;

/** Deterministic reference store for invisible child commit followed by receipt publication. */
public final class InMemoryApiFixtureSetCommitStore implements ApiFixtureSetCommitStore {
    private final Clock clock;
    private final Map<AttemptKey, StagedFixtureSet> stages = new HashMap<>();
    private final Map<AttemptKey, StoredFixtureSet> unpublished = new HashMap<>();
    private final Map<AttemptKey, Published> published = new HashMap<>();
    private final Map<FixtureKey, StoredFixtureSet> heads = new HashMap<>();
    private final Map<RevisionKey, StoredFixtureSet> revisions = new HashMap<>();

    /** Creates a store using UTC wall-clock lease validation. */
    public InMemoryApiFixtureSetCommitStore() { this(Clock.systemUTC()); }

    /** @param clock authority used only for outer lease expiry */
    public InMemoryApiFixtureSetCommitStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized StagedFixtureSet stage(CommandLease lease, GeneratedDefaultFixture generated) {
        requireLive(lease);
        StagedFixtureSet candidate;
        try {
            if (generated == null) throw new IllegalArgumentException("generated Fixture is required");
            candidate = new StagedFixtureSet(lease, generated);
        } catch (IllegalArgumentException ex) {
            throw failure(Code.INTEGRITY);
        }
        AttemptKey key = attempt(lease);
        if (published.containsKey(key) || unpublished.containsKey(key)) throw failure(Code.INTEGRITY);
        StagedFixtureSet prior = stages.putIfAbsent(key, candidate);
        if (prior != null && !prior.equals(candidate)) throw failure(Code.INTEGRITY);
        FixtureKey fixtureKey = fixtureKey(lease.key().scope(), generated.view().fixtureSetId());
        if (heads.containsKey(fixtureKey)) throw failure(Code.CAS_MISMATCH);
        return prior == null ? candidate : prior;
    }

    @Override
    public synchronized StoredFixtureSet commitChild(CommandLease lease) {
        requireLive(lease);
        AttemptKey key = attempt(lease);
        StoredFixtureSet prior = unpublished.get(key);
        if (prior != null) return prior;
        Published already = published.get(key);
        if (already != null) return already.stored();
        StagedFixtureSet staged = stages.get(key);
        if (staged == null) throw failure(Code.STAGE_MISSING);
        requireExact(staged.lease(), lease);
        StoredFixtureSet stored = new StoredFixtureSet(lease.key().scope(), staged.generated());
        FixtureKey fixtureKey = fixtureKey(stored.scope(), stored.generated().view().fixtureSetId());
        if (heads.containsKey(fixtureKey)) throw failure(Code.CAS_MISMATCH);
        unpublished.put(key, stored);
        return stored;
    }

    @Override
    public synchronized StoredFixtureSet publishChild(CommandLease lease, CommandReceipt outerReceipt) {
        if (lease == null) throw failure(Code.LEASE_FENCED);
        AttemptKey key = attempt(lease);
        Published already = published.get(key);
        if (already != null) {
            requireExact(already.lease(), lease);
            if (!already.outerReceipt().equals(outerReceipt)) throw failure(Code.RECEIPT_INVALID);
            return already.stored();
        }
        StoredFixtureSet stored = unpublished.get(key);
        StagedFixtureSet staged = stages.get(key);
        if (stored == null || staged == null) throw failure(Code.STAGE_MISSING);
        requireExact(staged.lease(), lease);
        try {
            ApiResourceSaveReceiptClosure.requireDefaultFixture(outerReceipt, stored.generated());
        } catch (IllegalArgumentException ex) {
            throw failure(Code.RECEIPT_INVALID);
        }
        FixtureKey fixtureKey = fixtureKey(stored.scope(), stored.generated().view().fixtureSetId());
        StoredFixtureSet current = heads.get(fixtureKey);
        if (current != null && !current.equals(stored)) throw failure(Code.CAS_MISMATCH);
        heads.put(fixtureKey, stored);
        revisions.put(new RevisionKey(fixtureKey, stored.generated().view().revision()), stored);
        stages.remove(key);
        unpublished.remove(key);
        published.put(key, new Published(lease, stored, outerReceipt));
        return stored;
    }

    @Override
    public synchronized void failChild(CommandLease lease) {
        if (lease == null) return;
        AttemptKey key = attempt(lease);
        if (published.containsKey(key)) return;
        StagedFixtureSet staged = stages.get(key);
        if (staged != null) requireExact(staged.lease(), lease);
        stages.remove(key);
        unpublished.remove(key);
    }

    @Override
    public synchronized Optional<StoredFixtureSet> findHead(AuthoringScope scope, String fixtureSetId) {
        return Optional.ofNullable(heads.get(fixtureKey(scope, fixtureSetId)));
    }

    @Override
    public synchronized Optional<StoredFixtureSet> findRevision(AuthoringScope scope, String fixtureSetId,
                                                               int revision) {
        if (revision < 1) throw failure(Code.INTEGRITY);
        return Optional.ofNullable(revisions.get(new RevisionKey(fixtureKey(scope, fixtureSetId), revision)));
    }

    @Override
    public synchronized List<FixtureSetSummary> listSummariesBySubject(AuthoringScope scope,
                                                                      FixtureSubjectRef subject) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(subject, "subject");
        return heads.values().stream()
                .filter(stored -> stored.scope().equals(scope)
                        && stored.generated().view().subject().equals(subject))
                .map(stored -> stored.generated().summary())
                .sorted(Comparator.comparing(FixtureSetSummary::fixtureSetId))
                .toList();
    }

    private void requireLive(CommandLease lease) {
        if (lease == null) throw failure(Code.LEASE_FENCED);
        if (!lease.leaseUntil().isAfter(clock.instant())) throw failure(Code.LEASE_EXPIRED);
    }

    private static void requireExact(CommandLease stored, CommandLease supplied) {
        if (!stored.equals(supplied)) throw failure(Code.LEASE_FENCED);
    }

    private static AttemptKey attempt(CommandLease lease) {
        return new AttemptKey(lease.commandId(), lease.attemptNo(), lease.attemptToken());
    }

    private static FixtureKey fixtureKey(AuthoringScope scope, String fixtureSetId) {
        if (scope == null || fixtureSetId == null || fixtureSetId.isBlank()) throw failure(Code.INTEGRITY);
        return new FixtureKey(scope, fixtureSetId);
    }

    private static ApiFixtureSetCommitStoreException failure(Code code) {
        return new ApiFixtureSetCommitStoreException(code);
    }

    private record AttemptKey(String commandId, int attemptNo, String attemptToken) { }
    private record FixtureKey(AuthoringScope scope, String fixtureSetId) { }
    private record RevisionKey(FixtureKey fixture, int revision) { }
    private record Published(CommandLease lease, StoredFixtureSet stored, CommandReceipt outerReceipt) { }
}
