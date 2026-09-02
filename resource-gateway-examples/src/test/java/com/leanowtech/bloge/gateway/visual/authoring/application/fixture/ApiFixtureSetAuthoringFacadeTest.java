package com.leanowtech.bloge.gateway.visual.authoring.application.fixture;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSummary;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetView;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.GeneratedDefaultFixture;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.ApiFixtureSetCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.ApiFixtureSetCommitStoreException;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.FixtureSetPrecondition;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetSaveResult;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StoredFixtureSet;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiFixtureSetAuthoringFacadeTest {
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant-a", "project-a", "test");
    private static final FixtureSubjectRef SUBJECT = new FixtureSubjectRef.ApiResource(
            "customer.get", 1, "sha256:" + "a".repeat(64));

    @Test
    void readsCurrentAndExactRevisionsWithoutWeakeningScope() {
        ApiFixtureSetCommitStore store = mock(ApiFixtureSetCommitStore.class);
        StoredFixtureSet stored = stored();
        when(store.findHead(SCOPE, "customer.get:r1")).thenReturn(Optional.of(stored));
        when(store.findRevision(SCOPE, "customer.get:r1", 1)).thenReturn(Optional.of(stored));
        ApiFixtureSetAuthoringFacade facade = new ApiFixtureSetAuthoringFacade(store);

        assertThat(facade.read(SCOPE, "customer.get:r1", null))
                .isEqualTo(new ApiFixtureSetAuthoringRead(view(), null));
        assertThat(facade.read(SCOPE, "customer.get:r1", 1))
                .isEqualTo(new ApiFixtureSetAuthoringRead(view(), null));
    }

    @Test
    void preservesTheIndependentEditValidatorAtTheApplicationBoundary() {
        ApiFixtureSetCommitStore store = mock(ApiFixtureSetCommitStore.class);
        StoredFixtureSet stored = new StoredFixtureSet(SCOPE, stored().generated(), "\"fixture-etag\"");
        when(store.findHead(SCOPE, "flow-cases")).thenReturn(Optional.of(stored));

        assertThat(new ApiFixtureSetAuthoringFacade(store).read(SCOPE, "flow-cases", null))
                .isEqualTo(new ApiFixtureSetAuthoringRead(view(), "\"fixture-etag\""));
    }

    @Test
    void listsOnlyMetadataForOneExactSubject() {
        ApiFixtureSetCommitStore store = mock(ApiFixtureSetCommitStore.class);
        when(store.listSummariesBySubject(SCOPE, SUBJECT)).thenReturn(List.of(summary()));

        assertThat(new ApiFixtureSetAuthoringFacade(store).list(SCOPE, SUBJECT))
                .containsExactly(summary());
        verify(store).listSummariesBySubject(SCOPE, SUBJECT);
    }

    @Test
    void missingAndCorruptAuthorityUseClosedFailures() {
        ApiFixtureSetCommitStore store = mock(ApiFixtureSetCommitStore.class);
        when(store.findHead(SCOPE, "missing")).thenReturn(Optional.empty());
        when(store.findHead(SCOPE, "broken")).thenThrow(new ApiFixtureSetCommitStoreException(
                ApiFixtureSetCommitStoreException.Code.INTEGRITY));
        ApiFixtureSetAuthoringFacade facade = new ApiFixtureSetAuthoringFacade(store);

        assertThatThrownBy(() -> facade.read(SCOPE, "missing", null))
                .isInstanceOf(ApiFixtureSetAuthoringFailure.class)
                .extracting("code").isEqualTo(ApiFixtureSetAuthoringFailure.Code.NOT_FOUND);
        assertThatThrownBy(() -> facade.read(SCOPE, "broken", null))
                .isInstanceOf(ApiFixtureSetAuthoringFailure.class)
                .extracting("code").isEqualTo(ApiFixtureSetAuthoringFailure.Code.INTEGRITY);
    }

    @Test
    void dispatchesComponentSubjectsOnlyToTheComponentWriter() {
        ApiFixtureSetCommitStore store = mock(ApiFixtureSetCommitStore.class);
        ReusableFlowFixtureModule flowWriter = mock(ReusableFlowFixtureModule.class);
        ComponentFixtureSetModule componentWriter = mock(ComponentFixtureSetModule.class);
        FixtureSubjectRef.OperatorVersion operator = new FixtureSubjectRef.OperatorVersion(
                "risk-library", 3, "risk.score", "sha256:" + "d".repeat(64));
        FixtureSetCommand command = new FixtureSetCommand(
                FixtureSetCommand.SCHEMA_VERSION, "Risk", operator, List.of());
        StandaloneFixtureSetSaveResult expected = mock(StandaloneFixtureSetSaveResult.class);
        when(componentWriter.save(SCOPE, "author", "risk", FixtureSetPrecondition.create(),
                "key", command)).thenReturn(expected);
        ApiFixtureSetAuthoringFacade facade = new ApiFixtureSetAuthoringFacade(
                store, flowWriter, componentWriter, null, null);

        assertThat(facade.save(SCOPE, "author", "risk", FixtureSetPrecondition.create(),
                "key", command)).isSameAs(expected);
        verify(componentWriter).save(SCOPE, "author", "risk", FixtureSetPrecondition.create(),
                "key", command);
        verify(flowWriter, never()).save(any(), any(), any(), any(), any(), any());
    }

    private static StoredFixtureSet stored() {
        GeneratedDefaultFixture generated = mock(GeneratedDefaultFixture.class);
        when(generated.view()).thenReturn(view());
        return new StoredFixtureSet(SCOPE, generated);
    }

    private static FixtureSetView view() {
        return new FixtureSetView(FixtureSetView.SCHEMA_VERSION, "customer.get:r1", 1,
                "sha256:" + "b".repeat(64), 1, "Customer defaults", SUBJECT, List.of(),
                FixtureSetView.Status.PRIVATE_DRAFT);
    }

    private static FixtureSetSummary summary() {
        return new FixtureSetSummary(FixtureSetSummary.SCHEMA_VERSION, "customer.get:r1", 1,
                "sha256:" + "b".repeat(64), "Customer defaults", SUBJECT,
                List.of(new FixtureSetSummary.CaseSummary("happy", "Happy")),
                FixtureSetView.Status.PRIVATE_DRAFT, 1);
    }
}
