import { describe, expect, it, vi } from 'vitest';

import { publishToolDraft, type ToolAuthoringRequester } from './toolTransport';

function response(body: unknown, status = 201): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

describe('publishToolDraft', () => {
  it('uses the existing publish seam and projects only the immutable publication facts', async () => {
    const request: ToolAuthoringRequester = vi.fn(async (_input, init) => {
      expect(init?.method).toBe('POST');
      expect(JSON.parse(String(init?.body))).toEqual({
        expectedRevision: 4,
        artifactKind: 'EXECUTABLE',
        actor: 'author-canvas',
        changeSource: 'tool-authoring',
        changeSummary: 'Publish tool revision.',
      });
      return response({
        published: true,
        publication: { publicationId: 'publication-42', draftRevision: 4, draft: { secret: 'never project' } },
      });
    });

    await expect(publishToolDraft('draft-7', 4, request)).resolves.toEqual({
      publicationId: 'publication-42',
      publicationRevision: 4,
    });
  });

  it('fails safely and allows a caller retry after a rejected response', async () => {
    const request = vi.fn()
      .mockResolvedValueOnce(response({ diagnostics: [{ message: 'secret body' }] }, 409))
      .mockResolvedValueOnce(response({
        published: true,
        publication: { publicationId: 'publication-42', draftRevision: 5 },
      }));

    await expect(publishToolDraft('draft-7', 5, request)).rejects.toThrow('Tool publish failed (409).');
    await expect(publishToolDraft('draft-7', 5, request)).resolves.toEqual({
      publicationId: 'publication-42', publicationRevision: 5,
    });
    expect(request).toHaveBeenCalledTimes(2);
    await expect(publishToolDraft('draft-7', 5, vi.fn(async () => response({
      published: false, publication: null, secret: 'not shown',
    })))).rejects.toThrow('Tool publish was rejected.');
  });
});
