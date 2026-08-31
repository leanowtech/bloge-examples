package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSummary;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.GeneratedDefaultFixture;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Thread-safe reference authority for independently authored Fixture Set revisions. */
public final class InMemoryStandaloneFixtureSetStore implements StandaloneFixtureSetStore {
    private final Map<FixtureKey, StoredStandaloneFixtureSet> heads = new HashMap<>();
    private final Map<RevisionKey, StoredStandaloneFixtureSet> history = new HashMap<>();
    private final Map<CommandKey, Completion> commands = new HashMap<>();
    private final Supplier<String> identifiers;

    public InMemoryStandaloneFixtureSetStore() {
        this(() -> UUID.randomUUID().toString());
    }

    InMemoryStandaloneFixtureSetStore(Supplier<String> identifiers) {
        this.identifiers = java.util.Objects.requireNonNull(identifiers, "identifiers");
    }

    @Override public synchronized StandaloneFixtureSetSaveResult save(StandaloneFixtureSetSaveIntent intent) {
        FixtureKey fixtureKey = new FixtureKey(intent.scope(), intent.fixtureSetId());
        CommandKey commandKey = new CommandKey(intent.scope(), intent.actorId(),
                intent.fixtureSetId(), intent.idempotencyKey());
        Completion prior = commands.get(commandKey);
        if (prior != null) {
            if (!prior.requestFingerprint().equals(intent.requestFingerprint())
                    || !prior.expectedRevision().equals(intent.expectedRevision())) {
                throw failure(StandaloneFixtureSetStoreException.Code.CONFLICT);
            }
            StandaloneFixtureSetSaveResult result = prior.result();
            return new StandaloneFixtureSetSaveResult(
                    result.view(), result.receipt(), result.strongEtag(), true);
        }

        StoredStandaloneFixtureSet current = heads.get(fixtureKey);
        checkExpected(current, intent.expectedRevision());
        int expectedRevision = current == null ? 1 : current.stored().generated().view().revision() + 1;
        GeneratedDefaultFixture generated = intent.generated();
        if (generated.view().revision() != expectedRevision) {
            throw failure(StandaloneFixtureSetStoreException.Code.INTEGRITY);
        }
        String strongEtag = "\"" + nextIdentifier() + "\"";
        StoredFixtureSet stored = new StoredFixtureSet(intent.scope(), generated, strongEtag);
        StoredStandaloneFixtureSet authority = new StoredStandaloneFixtureSet(stored, strongEtag);
        StandaloneFixtureSetSaveResult result = new StandaloneFixtureSetSaveResult(
                generated.view(), generated.receipt(), strongEtag, false);
        heads.put(fixtureKey, authority);
        history.put(new RevisionKey(intent.scope(), intent.fixtureSetId(), expectedRevision), authority);
        commands.put(commandKey, new Completion(
                intent.requestFingerprint(), intent.expectedRevision(), result));
        return result;
    }

    @Override public synchronized Optional<StoredFixtureSet> findHead(
            AuthoringScope scope, String fixtureSetId) {
        StoredStandaloneFixtureSet stored = heads.get(new FixtureKey(scope, fixtureSetId));
        return stored == null ? Optional.empty() : Optional.of(stored.stored());
    }

    @Override public synchronized Optional<StoredFixtureSet> findRevision(
            AuthoringScope scope, String fixtureSetId, int revision) {
        if (scope == null || fixtureSetId == null || revision < 1) return Optional.empty();
        return Optional.ofNullable(history.get(new RevisionKey(scope, fixtureSetId, revision)))
                .map(StoredStandaloneFixtureSet::stored);
    }

    @Override public synchronized Optional<StoredStandaloneFixtureSet> findRevisionByStrongEtag(
            AuthoringScope scope, String fixtureSetId, String strongEtag) {
        if (scope == null || fixtureSetId == null || !FixtureSetStrongEtag.isValid(strongEtag)) {
            return Optional.empty();
        }
        List<StoredStandaloneFixtureSet> matches = history.entrySet().stream()
                .filter(entry -> entry.getKey().scope().equals(scope)
                        && entry.getKey().fixtureSetId().equals(fixtureSetId)
                        && entry.getValue().strongEtag().equals(strongEtag))
                .map(Map.Entry::getValue).toList();
        if (matches.size() > 1) throw failure(StandaloneFixtureSetStoreException.Code.INTEGRITY);
        return matches.stream().findFirst();
    }

    @Override public synchronized List<FixtureSetSummary> listSummariesBySubject(
            AuthoringScope scope, FixtureSubjectRef subject) {
        if (scope == null || subject == null) return List.of();
        return heads.entrySet().stream()
                .filter(entry -> entry.getKey().scope().equals(scope)
                        && entry.getValue().stored().generated().view().subject().equals(subject))
                .map(entry -> entry.getValue().stored().generated().summary())
                .sorted(java.util.Comparator.comparing(FixtureSetSummary::fixtureSetId))
                .toList();
    }

    private static void checkExpected(StoredStandaloneFixtureSet current, ExpectedRevision expected) {
        boolean mismatch = expected instanceof ExpectedRevision.Create && current != null
                || expected instanceof ExpectedRevision.Match match && (current == null
                || current.stored().generated().view().revision() != match.revision());
        if (mismatch) throw failure(StandaloneFixtureSetStoreException.Code.CAS_MISMATCH);
    }

    private String nextIdentifier() {
        String value = identifiers.get();
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw failure(StandaloneFixtureSetStoreException.Code.INTEGRITY);
        }
        return value;
    }

    private static StandaloneFixtureSetStoreException failure(StandaloneFixtureSetStoreException.Code code) {
        return new StandaloneFixtureSetStoreException(code);
    }

    private record FixtureKey(AuthoringScope scope, String fixtureSetId) { }
    private record RevisionKey(AuthoringScope scope, String fixtureSetId, int revision) { }
    private record CommandKey(AuthoringScope scope, String actorId,
                              String fixtureSetId, String idempotencyKey) { }
    private record Completion(String requestFingerprint, ExpectedRevision expectedRevision,
                              StandaloneFixtureSetSaveResult result) { }
}
