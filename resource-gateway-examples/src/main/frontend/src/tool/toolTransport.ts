import type { ToolPublicationMetadata } from './toolModel';

/** Minimal publication artifact facts returned by the visual graph endpoint. */
export interface VisualGraphPublicationLike {
  publicationId?: unknown;
  draftRevision?: unknown;
  revision?: unknown;
}

/** Response envelope returned by POST /api/visual/drafts/{draftId}/publish. */
export interface VisualGraphPublishResultLike {
  published?: unknown;
  publication?: VisualGraphPublicationLike | null;
}

/** Injectable same-origin request seam for tool publication. */
export type ToolAuthoringRequester = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

/**
 * Publishes one stored graph draft through the existing visual graph endpoint.
 *
 * <p>The response is reduced to immutable publication identity and revision;
 * diagnostic bodies are never copied into user-visible errors.</p>
 *
 * @param draftId stored graph draft identifier
 * @param expectedRevision revision currently shown by the authoring surface
 * @param request injectable same-origin request function
 * @returns publication identity and frozen source-draft revision (the tool revision)
 * @throws Error when the endpoint rejects, returns malformed JSON, or does not publish
 */
export async function publishToolDraft(
  draftId: string,
  expectedRevision: number,
  request: ToolAuthoringRequester = (input, init) => fetch(input, init),
): Promise<ToolPublicationMetadata> {
  const normalizedId = draftId.trim();
  if (!normalizedId || !Number.isInteger(expectedRevision) || expectedRevision < 1) {
    throw new Error('Tool publish requires a stored draft revision.');
  }
  let response: Response;
  try {
    response = await request(`/api/visual/drafts/${encodeURIComponent(normalizedId)}/publish`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        expectedRevision,
        artifactKind: 'EXECUTABLE',
        actor: 'author-canvas',
        changeSource: 'tool-authoring',
        changeSummary: 'Publish tool revision.',
      }),
    });
  } catch {
    throw new Error('Tool publish failed (network).');
  }
  if (!response.ok) {
    throw new Error(`Tool publish failed (${response.status}).`);
  }
  let payload: unknown;
  try {
    payload = await response.json();
  } catch {
    throw new Error('Tool publish failed (invalid response).');
  }
  const result = isPublishResult(payload) ? payload : null;
  const publication = result?.publication;
  const publicationId = typeof publication?.publicationId === 'string'
    ? publication.publicationId.trim()
    : '';
  const revisionCandidate = publication?.draftRevision ?? publication?.revision;
  const publicationRevision = typeof revisionCandidate === 'number'
    ? revisionCandidate
    : Number(revisionCandidate);
  if (result?.published !== true || !publicationId
      || !Number.isInteger(publicationRevision) || publicationRevision < 1) {
    throw new Error('Tool publish was rejected.');
  }
  return { publicationId, publicationRevision };
}

function isPublishResult(value: unknown): value is VisualGraphPublishResultLike {
  return value !== null && typeof value === 'object';
}
