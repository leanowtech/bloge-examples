package com.leanowtech.bloge.gateway.visual.draft;

import java.util.List;

/**
 * Lightweight control-plane summary for active and retained visual draft history.
 *
 * @param draftId draft id
 * @param graphName latest known graph name
 * @param active true when the draft has a current working copy
 * @param currentRevision current working revision, or zero when deleted
 * @param latestRevision latest retained revision
 * @param revisionCount number of retained immutable revision snapshots
 * @param updatedAt latest revision timestamp
 * @param updatedBy latest revision actor
 * @param changeSource latest revision change source
 * @param changeSummary latest revision summary
 */
public record GraphDraftHistorySummary(
        String draftId,
        String graphName,
        boolean active,
        long currentRevision,
        long latestRevision,
        int revisionCount,
        String updatedAt,
        String updatedBy,
        String changeSource,
        String changeSummary
) {
    /**
     * Builds a summary from a current draft pointer and retained revisions.
     *
     * @param draftId draft id
     * @param current current draft when active
     * @param revisions retained immutable revisions, newest first preferred
     * @return summary
     */
    public static GraphDraftHistorySummary from(String draftId, GraphDraft current, List<GraphDraft> revisions) {
        List<GraphDraft> snapshots = revisions == null ? List.of() : revisions.stream()
                .filter(snapshot -> snapshot != null)
                .sorted((left, right) -> Long.compare(right.revision(), left.revision()))
                .toList();
        GraphDraft latest = snapshots.isEmpty() ? current : snapshots.getFirst();
        GraphDraft.RevisionMetadata metadata = latest == null
                ? GraphDraft.RevisionMetadata.empty()
                : latest.revisionMetadata();
        boolean active = current != null;
        return new GraphDraftHistorySummary(
                draftId == null ? "" : draftId,
                latest == null ? "" : latest.graphName(),
                active,
                active ? current.revision() : 0,
                latest == null ? 0 : latest.revision(),
                snapshots.size(),
                metadata.updatedAt(),
                metadata.updatedBy(),
                metadata.changeSource(),
                metadata.changeSummary()
        );
    }
}
