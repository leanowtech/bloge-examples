// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { BlogeApiRequestError } from '../api';
import type {
  VisualLibraryAuthoringCommitResult,
  VisualLibraryAuthoringCompileResult,
  VisualLibraryAuthoringDocument,
  VisualLibraryAuthoringDraft,
} from '../types';
import LibraryWorkbench from './LibraryWorkbench';

const apiMocks = vi.hoisted(() => ({
  commit: vi.fn(),
  fetchDraft: vi.fn(),
  preview: vi.fn(),
  save: vi.fn(),
}));

vi.mock('../api', () => ({
  BlogeApiRequestError: class BlogeApiRequestError extends Error {
    constructor(
      readonly status: number,
      readonly detail: string,
    ) {
      super(`Request failed: ${status} ${detail}`);
      this.name = 'BlogeApiRequestError';
    }
  },
  commitLibraryAuthoringDraft: apiMocks.commit,
  fetchLibraryAuthoringDraft: apiMocks.fetchDraft,
  previewLibraryAuthoringDraft: apiMocks.preview,
  saveLibraryAuthoringDraft: apiMocks.save,
}));

describe('LibraryWorkbench', () => {
  let root: Root | null = null;
  let host: HTMLDivElement;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean })
      .IS_REACT_ACT_ENVIRONMENT = true;
    window.history.replaceState({}, '', '/libraries/');
    host = document.createElement('div');
    document.body.appendChild(host);
    apiMocks.commit.mockReset();
    apiMocks.fetchDraft.mockReset();
    apiMocks.preview.mockReset();
    apiMocks.save.mockReset();
    vi.useFakeTimers();
  });

  afterEach(async () => {
    if (root) {
      await act(async () => root?.unmount());
      root = null;
    }
    host.remove();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('opens a complete example and preserves the fenced save-preview-commit flow', async () => {
    apiMocks.save.mockImplementation(async (
      draftId: string,
      _revision: number,
      document: VisualLibraryAuthoringDocument,
    ) => storedDraft(draftId, 1, document));
    apiMocks.preview.mockImplementation(async (draftId: string) => readyPreview(draftId, 1));
    apiMocks.commit.mockImplementation(async (
      draftId: string,
      _revision: number,
      preview: VisualLibraryAuthoringCompileResult,
    ) => commitReceipt(draftId, preview));

    await renderWorkbench();
    await click(query('[data-testid="library-start-example:customer-support"]'));

    expect(query('[data-testid="library-workbench"]').textContent)
      .toContain('Customer Support Authoring');
    expect(query('[data-testid="library-tree:operator:support:classify-ticket"]'))
      .toBeTruthy();
    expect(query('[data-testid="library-tree:function:support.firstPresent"]'))
      .toBeTruthy();

    await flushAutosave();

    expect(apiMocks.save).toHaveBeenCalledWith(
      expect.stringMatching(/^customer-support-authoring-/),
      0,
      expect.objectContaining({
        library: expect.objectContaining({ id: 'customer-support-authoring' }),
      }),
      'QUICK',
    );
    expect(apiMocks.preview).toHaveBeenCalledWith(
      expect.stringMatching(/^customer-support-authoring-/),
      1,
    );
    expect(query('[data-testid="library-save-state"]').textContent).toContain('Saved revision 1');

    await click(query('[data-testid="library-tree:operator:support:classify-ticket"]'));
    expect(query('[data-testid="operator-builder"]').textContent).toContain('Classify Ticket');

    await click(query('[data-testid="library-commit"]'));

    expect(apiMocks.commit).toHaveBeenCalledWith(
      expect.stringMatching(/^customer-support-authoring-/),
      1,
      expect.objectContaining({
        authoringRevision: 1,
        previewAuthority: 'SERVER_AUTHORITATIVE',
      }),
      'Reviewed in Library Workbench',
    );
    expect(query('[data-testid="library-commit-receipt"]').textContent)
      .toContain('Imported customer-support-authoring revision 8');
  });

  it('blocks editing on an ETag conflict and reloads the authoritative revision', async () => {
    apiMocks.save.mockRejectedValueOnce(new BlogeApiRequestError(412, 'revision mismatch'));
    apiMocks.fetchDraft.mockImplementation(async (draftId: string) => storedDraft(
      draftId,
      4,
      {
        schemaVersion: 'bloge.visualLibraryAuthoring.v1',
        library: {
          id: 'server-library',
          name: 'Server Library',
          version: '2.0.0',
          owner: 'platform-team',
        },
        types: {},
        operators: {},
        functions: {},
      },
    ));
    apiMocks.preview.mockImplementation(async (draftId: string) => readyPreview(draftId, 4));

    await renderWorkbench();
    await click(query('[data-testid="library-quick-create"]'));
    await flushAutosave();

    expect(query('[data-testid="library-save-state"]').textContent).toContain('Conflict');
    expect(query('[data-testid="library-save-state"]').textContent)
      .toContain('newer revision exists');

    await click(buttonByText('Reload'));

    expect(apiMocks.fetchDraft).toHaveBeenCalledTimes(1);
    expect(query('[data-testid="library-metadata-builder"]').textContent)
      .toContain('Server Library');
    expect(query('[data-testid="library-save-state"]').textContent)
      .toContain('Saved revision 4');
  });

  async function renderWorkbench() {
    await act(async () => {
      root = createRoot(host);
      root.render(<LibraryWorkbench />);
    });
  }
});

function storedDraft(
  draftId: string,
  revision: number,
  document: VisualLibraryAuthoringDocument,
): VisualLibraryAuthoringDraft {
  return {
    schemaVersion: 'bloge.visualLibraryAuthoringDraft.v1',
    draftId,
    revision,
    sourceMode: 'QUICK',
    document,
    fingerprint: `sha256:draft-${revision}`,
    createdAt: '2026-07-30T00:00:00Z',
    updatedAt: '2026-07-30T00:00:01Z',
    savedBy: 'visual-library-workbench',
  };
}

function readyPreview(draftId: string, revision: number): VisualLibraryAuthoringCompileResult {
  return {
    schemaVersion: 'bloge.visualLibraryCompileResult.v1',
    draftId,
    authoringRevision: revision,
    authoringFingerprint: 'sha256:authoring',
    compileFingerprint: 'sha256:compile',
    compilerVersion: '1',
    grammarVersion: '1',
    catalogFingerprint: 'sha256:catalog',
    previewAuthority: 'SERVER_AUTHORITATIVE',
    canonicalFingerprint: 'sha256:canonical',
    sourceMap: [],
    diagnostics: [],
    confirmationRequests: [],
    readiness: {
      state: 'READY',
      importable: true,
      strongSchemaReady: true,
      designReady: true,
      productionReady: false,
      gates: [],
    },
    diff: {
      libraryId: 'customer-support-authoring',
      baseRevision: 7,
      changed: true,
      addedOperatorCount: 2,
      removedOperatorCount: 0,
      changedOperatorCount: 0,
    },
  };
}

function commitReceipt(
  draftId: string,
  preview: VisualLibraryAuthoringCompileResult,
): VisualLibraryAuthoringCommitResult {
  return {
    schemaVersion: 'bloge.visualLibraryAuthoringCommitResult.v1',
    draftId,
    authoringRevision: preview.authoringRevision,
    authoringFingerprint: preview.authoringFingerprint,
    canonicalFingerprint: preview.canonicalFingerprint,
    catalogFingerprintBeforeCommit: preview.catalogFingerprint,
    targetRevision: 8,
    library: {
      schemaVersion: 'bloge.operatorLibrary.v1',
      libraryId: 'customer-support-authoring',
      displayName: 'Customer Support Authoring',
      version: '1.0.0',
      operators: [],
      builtInFunctions: [],
    },
    preview,
    committedAt: '2026-07-30T00:00:02Z',
    committedBy: 'visual-library-workbench',
  };
}

async function click(element: Element): Promise<void> {
  await act(async () => {
    element.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  });
}

async function flushAutosave(): Promise<void> {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(710);
    await Promise.resolve();
    await Promise.resolve();
  });
}

function buttonByText(text: string): HTMLButtonElement {
  const button = [...document.querySelectorAll<HTMLButtonElement>('button')]
    .find((candidate) => candidate.textContent?.includes(text));
  if (!button) {
    throw new Error(`Missing button: ${text}`);
  }
  return button;
}

function query<T extends Element = Element>(selector: string): T {
  const element = document.querySelector<T>(selector);
  if (!element) {
    throw new Error(`Missing element: ${selector}`);
  }
  return element;
}
