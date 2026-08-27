// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import ToolAuthoringPanel from './ToolAuthoringPanel';
import type { ToolDraftLike } from './toolModel';
import type { ToolCoordinate } from '../spine/authorSpine';

const coordinate: ToolCoordinate = { toolId: 'loan-tool', toolName: 'Loan tool', stage: 'publish' };
const draft: ToolDraftLike = {
  draftId: 'draft-7', revision: 4, graphName: 'loan-tool', status: 'DRAFT',
  inputSchema: { schema: { type: 'object' } }, outputSchema: { schema: { type: 'string' } },
};

function response(body: unknown, status = 201): Response {
  return new Response(JSON.stringify(body), { status });
}

describe('ToolAuthoringPanel', () => {
  let host: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
  });

  afterEach(() => act(() => root.unmount()));

  it('publishes the current real draft and updates the badge from the receipt', async () => {
    const request = vi.fn(async () => response({
      published: true,
      publication: { publicationId: 'pub-42', draftRevision: 4 },
    }));
    const onPublished = vi.fn();
    await act(async () => root.render(
      <ToolAuthoringPanel draft={draft} coordinate={coordinate} onPublished={onPublished} request={request} />,
    ));
    expect(host.querySelector('[data-testid="tool-signature-badge"]')?.getAttribute('data-tool-state')).toBe('draft');
    await act(async () => host.querySelector<HTMLButtonElement>('[data-testid="tool-publish"]')?.click());
    expect(request).toHaveBeenCalledTimes(1);
    expect(onPublished).toHaveBeenCalledWith({ publicationId: 'pub-42', publicationRevision: 4 });
  });

  it('keeps retryable errors visible without leaking the response body', async () => {
    const request = vi.fn(async () => response({ secret: 'do-not-show' }, 409));
    await act(async () => root.render(
      <ToolAuthoringPanel draft={draft} coordinate={coordinate} onPublished={vi.fn()} request={request} />,
    ));
    await act(async () => host.querySelector<HTMLButtonElement>('[data-testid="tool-publish"]')?.click());
    expect(host.textContent).toContain('Tool publish failed (409).');
    expect(host.textContent).not.toContain('do-not-show');
    expect(host.querySelector<HTMLButtonElement>('[data-testid="tool-publish"]')?.disabled).toBe(false);
  });
});
