package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSummary;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetView;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureShareMaterialization;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureReviewMaterialization;
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
    private final Map<CommandKey, ShareCompletion> shareCommands = new HashMap<>();
    private final Map<CommandKey, ReviewCompletion> reviewCommands = new HashMap<>();
    private final Map<ReviewKey, ReviewState> reviews = new HashMap<>();
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
        if (current != null && current.stored().generated().view().status()
                != FixtureSetView.Status.PRIVATE_DRAFT) {
            throw failure(StandaloneFixtureSetStoreException.Code.CAS_MISMATCH);
        }
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

    @Override public synchronized StandaloneFixtureSetShareResult share(
            StandaloneFixtureSetShareIntent intent, FixtureSetShareDeriver deriver) {
        if (intent == null || deriver == null) {
            throw failure(StandaloneFixtureSetStoreException.Code.INTEGRITY);
        }
        CommandKey commandKey = new CommandKey(intent.scope(), intent.actorId(),
                intent.fixtureSetId(), intent.idempotencyKey());
        ShareCompletion prior = shareCommands.get(commandKey);
        if (prior != null) {
            if (!prior.requestFingerprint().equals(intent.requestFingerprint())
                    || !prior.sourceStrongEtag().equals(intent.sourceStrongEtag())) {
                throw failure(StandaloneFixtureSetStoreException.Code.CONFLICT);
            }
            StandaloneFixtureSetShareResult result = prior.result();
            return new StandaloneFixtureSetShareResult(
                    result.view(), result.receipt(), result.strongEtag(), true);
        }
        FixtureKey fixtureKey = new FixtureKey(intent.scope(), intent.fixtureSetId());
        StoredStandaloneFixtureSet current = heads.get(fixtureKey);
        requireExactShareSource(current, intent);
        int revision = Math.addExact(current.stored().generated().view().revision(), 1);
        int statusRevision = Math.addExact(
                current.stored().generated().view().statusRevision(), 1);
        String reviewRequestId = nextIdentifier();
        FixtureShareMaterialization materialization = deriver.derive(
                current.stored(), revision, statusRevision, reviewRequestId);
        requireExactShareMaterialization(current, materialization, revision,
                statusRevision, reviewRequestId);
        String strongEtag = "\"" + nextIdentifier() + "\"";
        StoredFixtureSet stored = new StoredFixtureSet(
                intent.scope(), materialization.generated(), strongEtag);
        StoredStandaloneFixtureSet authority = new StoredStandaloneFixtureSet(stored, strongEtag);
        StandaloneFixtureSetShareResult result = new StandaloneFixtureSetShareResult(
                materialization.generated().view(), materialization.receipt(), strongEtag, false);
        heads.put(fixtureKey, authority);
        history.put(new RevisionKey(intent.scope(), intent.fixtureSetId(), revision), authority);
        shareCommands.put(commandKey, new ShareCompletion(
                intent.requestFingerprint(), intent.sourceStrongEtag(), result));
        reviews.put(new ReviewKey(intent.scope(), materialization.receipt().reviewRequestId()),
                new ReviewState(intent.fixtureSetId(), revision,
                        materialization.generated().view().fingerprint(), strongEtag,
                        intent.actorId(), false));
        return result;
    }

    @Override public synchronized StandaloneFixtureSetReviewResult review(
            StandaloneFixtureSetReviewIntent intent, FixtureSetReviewDeriver deriver) {
        if (intent == null || deriver == null) {
            throw failure(StandaloneFixtureSetStoreException.Code.INTEGRITY);
        }
        CommandKey commandKey = new CommandKey(intent.scope(), intent.actorId(),
                intent.fixtureSetId(), intent.idempotencyKey());
        ReviewCompletion prior = reviewCommands.get(commandKey);
        if (prior != null) {
            if (!prior.requestFingerprint().equals(intent.requestFingerprint())
                    || !prior.sourceStrongEtag().equals(intent.sourceStrongEtag())) {
                throw failure(StandaloneFixtureSetStoreException.Code.CONFLICT);
            }
            var result = prior.result();
            return new StandaloneFixtureSetReviewResult(
                    result.view(), result.receipt(), result.strongEtag(), true);
        }
        FixtureKey fixtureKey = new FixtureKey(intent.scope(), intent.fixtureSetId());
        StoredStandaloneFixtureSet current = heads.get(fixtureKey);
        requireExactReviewSource(current, intent);
        ReviewKey reviewKey = new ReviewKey(
                intent.scope(), intent.command().source().reviewRequestId());
        ReviewState state = reviews.get(reviewKey);
        if (state == null || state.completed() || state.createdBy().equals(intent.actorId())
                || !state.fixtureSetId().equals(intent.fixtureSetId())
                || state.revision() != intent.command().source().revision()
                || !state.fingerprint().equals(intent.command().source().fingerprint())
                || !state.strongEtag().equals(intent.sourceStrongEtag())) {
            throw failure(StandaloneFixtureSetStoreException.Code.CAS_MISMATCH);
        }
        int revision = Math.addExact(current.stored().generated().view().revision(), 1);
        int statusRevision = Math.addExact(
                current.stored().generated().view().statusRevision(), 1);
        FixtureReviewMaterialization materialization = deriver.derive(
                current.stored(), revision, statusRevision);
        requireExactReviewMaterialization(current, materialization, revision, statusRevision,
                intent.command().source().reviewRequestId());
        String strongEtag = "\"" + nextIdentifier() + "\"";
        StoredFixtureSet stored = new StoredFixtureSet(
                intent.scope(), materialization.generated(), strongEtag);
        StoredStandaloneFixtureSet authority = new StoredStandaloneFixtureSet(stored, strongEtag);
        var result = new StandaloneFixtureSetReviewResult(
                materialization.generated().view(), materialization.receipt(), strongEtag, false);
        heads.put(fixtureKey, authority);
        history.put(new RevisionKey(intent.scope(), intent.fixtureSetId(), revision), authority);
        reviews.put(reviewKey, new ReviewState(state.fixtureSetId(), state.revision(),
                state.fingerprint(), state.strongEtag(), state.createdBy(), true));
        reviewCommands.put(commandKey, new ReviewCompletion(
                intent.requestFingerprint(), intent.sourceStrongEtag(), result));
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

    private static void requireExactShareSource(
            StoredStandaloneFixtureSet current, StandaloneFixtureSetShareIntent intent) {
        if (current == null || !current.strongEtag().equals(intent.sourceStrongEtag())) {
            throw failure(StandaloneFixtureSetStoreException.Code.CAS_MISMATCH);
        }
        var source = intent.command().source();
        var view = current.stored().generated().view();
        if (view.status() != FixtureSetView.Status.PRIVATE_DRAFT
                || view.revision() != source.revision()
                || !view.fingerprint().equals(source.fingerprint())
                || view.statusRevision() != source.statusRevision()) {
            throw failure(StandaloneFixtureSetStoreException.Code.CAS_MISMATCH);
        }
    }

    private static void requireExactShareMaterialization(
            StoredStandaloneFixtureSet source, FixtureShareMaterialization materialization,
            int revision, int statusRevision, String reviewRequestId) {
        if (materialization == null
                || materialization.receipt().derivedFromRevision()
                != source.stored().generated().view().revision()
                || materialization.receipt().revision() != revision
                || materialization.receipt().statusRevision() != statusRevision
                || !materialization.receipt().reviewRequestId().equals(reviewRequestId)) {
            throw failure(StandaloneFixtureSetStoreException.Code.INTEGRITY);
        }
    }

    private static void requireExactReviewSource(
            StoredStandaloneFixtureSet current, StandaloneFixtureSetReviewIntent intent) {
        if (current == null || !current.strongEtag().equals(intent.sourceStrongEtag())) {
            throw failure(StandaloneFixtureSetStoreException.Code.CAS_MISMATCH);
        }
        var expected = intent.command().source();
        var view = current.stored().generated().view();
        if (view.status() != FixtureSetView.Status.SHARING_PENDING
                || view.revision() != expected.revision()
                || !view.fingerprint().equals(expected.fingerprint())
                || view.statusRevision() != expected.statusRevision()) {
            throw failure(StandaloneFixtureSetStoreException.Code.CAS_MISMATCH);
        }
    }

    private static void requireExactReviewMaterialization(
            StoredStandaloneFixtureSet source, FixtureReviewMaterialization materialization,
            int revision, int statusRevision, String reviewRequestId) {
        if (materialization == null
                || materialization.receipt().derivedFromRevision()
                != source.stored().generated().view().revision()
                || materialization.receipt().revision() != revision
                || materialization.receipt().statusRevision() != statusRevision
                || !materialization.receipt().reviewRequestId().equals(reviewRequestId)) {
            throw failure(StandaloneFixtureSetStoreException.Code.INTEGRITY);
        }
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
    private record ShareCompletion(String requestFingerprint, String sourceStrongEtag,
                                   StandaloneFixtureSetShareResult result) { }
    private record ReviewCompletion(String requestFingerprint, String sourceStrongEtag,
                                    StandaloneFixtureSetReviewResult result) { }
    private record ReviewKey(AuthoringScope scope, String reviewRequestId) { }
    private record ReviewState(String fixtureSetId, int revision, String fingerprint,
                               String strongEtag, String createdBy, boolean completed) { }
}
