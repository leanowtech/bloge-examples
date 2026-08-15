package com.leanowtech.bloge.gateway.testing.correctness.workspace;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.publication.CorrectnessPublicationRepository;
import com.leanowtech.bloge.gateway.testing.correctness.publication.StoredCorrectnessPublication;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.PublicationSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Adds the latest immutable Publication manifest to the metadata-only Workspace. */
public final class PublicationCorrectnessWorkspaceComponentSource
        implements CorrectnessWorkspaceComponentSource {

    private final CorrectnessWorkspaceComponentSource delegate;
    private final CorrectnessPublicationRepository publications;

    public PublicationCorrectnessWorkspaceComponentSource(
            CorrectnessWorkspaceComponentSource delegate,
            CorrectnessPublicationRepository publications
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.publications = Objects.requireNonNull(publications, "publications");
    }

    @Override
    public Components load(Coordinate coordinate, PageRequest pageRequest) {
        Components base = delegate.load(coordinate, pageRequest);
        StoredCorrectnessPublication latest = publications.findLatestPublication(
                coordinate.scope(), coordinate.definitionRef(), coordinate.target()).orElse(null);
        PublicationSummary summary = latest == null ? null : summary(coordinate, latest);
        List<String> capabilities = new ArrayList<>(base.capabilities());
        capabilities.add("CORRECTNESS_PUBLICATION_READ_V1");
        return new Components(
                base.coverage(), base.oracleAssertions(), base.cases(), base.fixtures(),
                base.reviews(), summary, base.lastRun(), base.verdict(), base.staleReasons(),
                capabilities, base.commandPolicy());
    }

    private static PublicationSummary summary(
            Coordinate coordinate,
            StoredCorrectnessPublication stored
    ) {
        var publication = stored.publication();
        if (!publication.scope().equals(coordinate.scope())
                || !publication.definitionRef().equals(coordinate.definitionRef())
                || !publication.target().equals(coordinate.target())
                || (coordinate.activeInventoryRef() != null
                && !publication.inventoryRef().equals(coordinate.activeInventoryRef()))) {
            throw new IllegalStateException(
                    "Latest Correctness Publication does not match the Workspace coordinate");
        }
        ExactAssetRef publicationRef = new ExactAssetRef(
                "CORRECTNESS_PUBLICATION", publication.publicationId(), 1,
                stored.publicationFingerprint());
        return new PublicationSummary(
                publicationRef, "COMMITTED", publication.metadata().updatedAt());
    }
}
