package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSummary;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Fail-closed read union across child and independently authored Fixture authorities. */
public final class CompositeFixtureSetAuthorityReader implements FixtureSetAuthorityReader {
    private final List<FixtureSetAuthorityReader> readers;

    public CompositeFixtureSetAuthorityReader(List<FixtureSetAuthorityReader> readers) {
        this.readers = readers == null ? List.of() : List.copyOf(readers);
        if (this.readers.isEmpty() || this.readers.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Fixture Set readers are incomplete");
        }
    }

    @Override public Optional<StoredFixtureSet> findHead(AuthoringScope scope, String fixtureSetId) {
        return exact(readers.stream().map(reader -> reader.findHead(scope, fixtureSetId)).toList());
    }

    @Override public Optional<StoredFixtureSet> findRevision(
            AuthoringScope scope, String fixtureSetId, int revision) {
        return exact(readers.stream()
                .map(reader -> reader.findRevision(scope, fixtureSetId, revision)).toList());
    }

    @Override public List<FixtureSetSummary> listSummariesBySubject(
            AuthoringScope scope, FixtureSubjectRef subject) {
        List<FixtureSetSummary> summaries = new ArrayList<>();
        readers.forEach(reader -> summaries.addAll(reader.listSummariesBySubject(scope, subject)));
        Set<String> ids = new HashSet<>();
        if (summaries.stream().anyMatch(summary -> !ids.add(summary.fixtureSetId()))) {
            throw new ApiFixtureSetCommitStoreException(ApiFixtureSetCommitStoreException.Code.INTEGRITY);
        }
        return summaries.stream().sorted(Comparator.comparing(FixtureSetSummary::fixtureSetId)).toList();
    }

    private static Optional<StoredFixtureSet> exact(List<Optional<StoredFixtureSet>> candidates) {
        List<StoredFixtureSet> matches = candidates.stream().flatMap(Optional::stream).toList();
        if (matches.size() > 1) {
            throw new ApiFixtureSetCommitStoreException(ApiFixtureSetCommitStoreException.Code.INTEGRITY);
        }
        return matches.stream().findFirst();
    }
}
