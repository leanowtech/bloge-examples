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
  VisualSampleInferenceResult,
} from '../types';
import LibraryWorkbench from './LibraryWorkbench';

const apiMocks = vi.hoisted(() => ({
  applyInference: vi.fn(),
  commit: vi.fn(),
  fetchDraft: vi.fn(),
  fetchCatalogs: vi.fn(),
  fetchTestEvidence: vi.fn(),
  fetchTestGate: vi.fn(),
  draftFunctionTest: vi.fn(),
  draftOperatorTest: vi.fn(),
  discover: vi.fn(),
  fetchContext: vi.fn(),
  infer: vi.fn(),
  preview: vi.fn(),
  fetchDrafts: vi.fn(),
  fetchDraftRevision: vi.fn(),
  runFunctionTest: vi.fn(),
  runOperatorTest: vi.fn(),
  save: vi.fn(),
  saveFixture: vi.fn(),
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
  applyLibraryAuthoringSamples: apiMocks.applyInference,
  commitLibraryAuthoringDraft: apiMocks.commit,
  draftLibraryAuthoringFunctionTest: apiMocks.draftFunctionTest,
  draftLibraryAuthoringOperatorTest: apiMocks.draftOperatorTest,
  discoverLibraryAuthoringAssets: apiMocks.discover,
  fetchLibraryAuthoringDraft: apiMocks.fetchDraft,
  fetchLibraryAuthoringDraftRevision: apiMocks.fetchDraftRevision,
  fetchLibraryAuthoringDrafts: apiMocks.fetchDrafts,
  fetchLibraryAuthoringContext: apiMocks.fetchContext,
  fetchLibraryAuthoringCatalogs: apiMocks.fetchCatalogs,
  fetchLibraryAuthoringTestEvidence: apiMocks.fetchTestEvidence,
  fetchLibraryAuthoringTestGate: apiMocks.fetchTestGate,
  inferLibraryAuthoringSamples: apiMocks.infer,
  previewLibraryAuthoringDraft: apiMocks.preview,
  runLibraryAuthoringFunctionTest: apiMocks.runFunctionTest,
  runLibraryAuthoringOperatorTest: apiMocks.runOperatorTest,
  saveLibraryAuthoringDraft: apiMocks.save,
  saveLibraryAuthoringFixture: apiMocks.saveFixture,
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
    apiMocks.applyInference.mockReset();
    apiMocks.draftFunctionTest.mockReset();
    apiMocks.draftOperatorTest.mockReset();
    apiMocks.discover.mockReset();
    apiMocks.fetchDraft.mockReset();
    apiMocks.fetchDraftRevision.mockReset();
    apiMocks.fetchDrafts.mockReset();
    apiMocks.fetchContext.mockReset();
    apiMocks.fetchCatalogs.mockReset();
    apiMocks.fetchTestEvidence.mockReset();
    apiMocks.fetchTestGate.mockReset();
    apiMocks.infer.mockReset();
    apiMocks.preview.mockReset();
    apiMocks.runFunctionTest.mockReset();
    apiMocks.runOperatorTest.mockReset();
    apiMocks.save.mockReset();
    apiMocks.saveFixture.mockReset();
    apiMocks.fetchCatalogs.mockResolvedValue({
      schemaVersion: 'bloge.visualLibraryAuthoringCatalogs.v1',
      limits: {},
      features: { governedFixturePersistence: true },
    });
    apiMocks.fetchDrafts.mockResolvedValue([]);
    apiMocks.fetchContext.mockResolvedValue({
      schemaVersion: 'bloge.visualLibraryAuthoringHomeContext.v1',
      actorId: 'visual-library-workbench',
      tenantId: 'tenant-a',
      organizationId: 'organization-a',
      projectId: 'project-a',
      environmentId: 'test',
      region: 'local',
    });
    apiMocks.fetchTestGate.mockResolvedValue({
      status: 'PASSED',
      reasons: [],
      assets: [],
    });
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

  it('starts on a durable asset home with readiness queues and exact revision links', async () => {
    const library = storedDraft('support-library', 7, {
      schemaVersion: 'bloge.visualLibraryAuthoring.v1',
      library: {
        id: 'support-library',
        name: 'Support Operations',
        owner: 'visual-library-workbench',
      },
      operators: {
        'support:search': {
          input: { query: 'string' },
          output: { answer: 'string' },
        },
      },
      functions: {},
    });
    apiMocks.fetchDrafts.mockResolvedValue([library]);
    apiMocks.preview.mockResolvedValue({
      ...readyPreview('support-library', 7),
      confirmationRequests: [{
        code: 'OWNER_CONFIRMATION_REQUIRED',
        authoringPath: '/library/owner',
        question: 'Confirm owner?',
        allowedValues: ['YES'],
      }],
      runtimeParity: [{
        assetKind: 'OPERATOR',
        assetRef: 'support:search',
        runtimeProfile: 'demo',
        state: 'DRIFTED',
        executableReady: false,
        declaredFingerprint: 'declared',
        runtimeFingerprint: 'runtime',
        reasonCode: 'RUNTIME_CHANGED',
        message: 'Runtime changed.',
      }],
    });
    apiMocks.fetchTestGate.mockResolvedValue({
      status: 'BLOCKED',
      reasons: ['MISSING_EVIDENCE'],
      assets: [],
    });

    await renderWorkbench();
    await settle();

    const row = query('[data-testid="library-home-row:support-library"]');
    expect(row.textContent).toContain('Support Operations');
    expect(row.textContent).toContain('Needs confirmation');
    expect(row.textContent).toContain('Runtime drift');
    expect(row.textContent).toContain('Test gate incomplete');
    expect(query<HTMLAnchorElement>('[data-testid="library-home-row:support-library"] a').href)
      .toContain('/libraries/?draftId=support-library&revision=7');

    await click(query('[data-testid="library-filter:runtime-drift"]'));
    expect(query('[data-testid="library-home-row:support-library"]')).toBeTruthy();
  });

  it('opens an exact historical revision read-only and offers latest or fork', async () => {
    const current = storedDraft('support-library', 3, {
      schemaVersion: 'bloge.visualLibraryAuthoring.v1',
      library: { id: 'support-library', name: 'Current support', owner: 'support-team' },
      operators: { 'support:current': { input: {}, output: {} } },
      functions: {},
    });
    const historical = storedDraft('support-library', 1, {
      schemaVersion: 'bloge.visualLibraryAuthoring.v1',
      library: { id: 'support-library', name: 'Original support', owner: 'support-team' },
      operators: { 'support:original': { input: {}, output: {} } },
      functions: {},
    });
    window.history.replaceState(
      {},
      '',
      '/libraries/?draftId=support-library&revision=1',
    );
    apiMocks.fetchDraft.mockResolvedValue(current);
    apiMocks.fetchDraftRevision.mockResolvedValue(historical);

    await renderWorkbench();
    await settle();

    expect(apiMocks.fetchDraftRevision).toHaveBeenCalledWith('support-library', 1);
    expect(query('[data-testid="library-history-view"]').textContent)
      .toContain('Exact revision 1 is open read-only');
    expect(query('[data-testid="library-history-view"]').textContent)
      .toContain('mutable head is revision 3');
    expect(query<HTMLAnchorElement>('.library-history-actions a').href)
      .toContain('draftId=support-library&revision=3');

    await click(buttonByText('Fork this revision'));
    expect(query('[data-testid="library-workbench"]').textContent).toContain('Original support');
    expect(window.location.search).toContain('draftId=support-library-');
    expect(window.location.search).not.toContain('revision=');
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
    expect(query('[data-testid="library-workbench"]').textContent)
      .toContain('Design-only example');
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
    expect(query('.library-contract-heading').textContent).toContain('Contract Preview');
    expect(query('.library-readiness-summary').textContent)
      .toContain('Design valid; runtime not verified');
    expect(query('.library-readiness-summary').querySelector('small')).toBeTruthy();
    expect(query('.library-readiness-summary').querySelector('code')).toBeTruthy();

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

  it('turns representative samples into an explicitly confirmed operator port', async () => {
    let savedDocument: VisualLibraryAuthoringDocument | null = null;
    apiMocks.save.mockImplementation(async (
      draftId: string,
      _revision: number,
      document: VisualLibraryAuthoringDocument,
    ) => {
      savedDocument = document;
      return storedDraft(draftId, 1, document);
    });
    apiMocks.preview.mockImplementation(async (draftId: string, revision: number) => (
      readyPreview(draftId, revision)
    ));
    apiMocks.infer.mockImplementation(async (draftId: string) => sampleInferenceResult(draftId));
    apiMocks.saveFixture.mockResolvedValue(sampleFixtureReceipt());
    apiMocks.applyInference.mockImplementation(async (draftId: string) => {
      const current = savedDocument as VisualLibraryAuthoringDocument;
      return storedDraft(draftId, 2, {
        ...current,
        operators: {
          ...current.operators,
          'support:classify-ticket': {
            ...current.operators?.['support:classify-ticket'],
            input: {
              request: {
                fields: {
                  customerId: 'string',
                  priority: 'string',
                },
                additionalProperties: true,
              },
            },
          },
        },
      });
    });

    await renderWorkbench();
    await click(query('[data-testid="library-start-choice:samples"]'));
    await click(query('[data-testid="library-samples-create"]'));

    expect(query('[data-testid="sample-inference-dialog"]').textContent)
      .toContain('support:classify-ticket');

    await click(query('[data-testid="sample-inference-analyze"]'));
    await settle();

    expect(apiMocks.save).toHaveBeenCalledWith(
      expect.stringMatching(/^team-operator-library-/),
      0,
      expect.objectContaining({
        operators: expect.objectContaining({
          'support:classify-ticket': expect.objectContaining({ input: {}, output: {} }),
        }),
      }),
      'QUICK',
    );
    expect(apiMocks.infer).toHaveBeenCalledWith(
      expect.stringMatching(/^team-operator-library-/),
      1,
      expect.objectContaining({
        schemaVersion: 'bloge.visualSampleInferenceRequest.v1',
        target: {
          assetKind: 'OPERATOR',
          assetRef: 'support:classify-ticket',
          portDirection: 'INPUT',
          portName: 'request',
        },
        samples: expect.any(Array),
        options: {
          suggestEnums: true,
          suggestFormats: true,
          persistPayload: false,
        },
      }),
    );
    expect(query('[data-testid="sample-inference-dialog"]').textContent)
      .toContain('Confirmation queue');
    expect(query<HTMLButtonElement>('[data-testid="sample-inference-apply"]').disabled).toBe(true);

    await click(query('[data-testid="sample-inference-save-fixture"]'));
    await click(query('[data-testid="governed-fixture-confirm"]'));
    await click(query('[data-testid="governed-fixture-save"]'));
    await settle();

    expect(apiMocks.saveFixture).toHaveBeenCalledWith(
      expect.stringMatching(/^team-operator-library-/),
      1,
      expect.objectContaining({
        sourceKind: 'SAMPLE',
        assetKind: 'OPERATOR',
        assetRef: 'support:classify-ticket',
        payload: expect.objectContaining({
          target: expect.objectContaining({ portName: 'request' }),
          samples: expect.any(Array),
        }),
      }),
    );
    expect(query('[data-testid="governed-fixture-receipt"]').textContent)
      .toContain('Payload returnedNo');
    await click(buttonByText('Done'));

    await click(query('[data-testid="sample-inference-use-recommendations"]'));
    expect(query<HTMLButtonElement>('[data-testid="sample-inference-apply"]').disabled).toBe(false);

    await click(query('[data-testid="sample-inference-apply"]'));
    await settle();

    expect(apiMocks.applyInference).toHaveBeenCalledWith(
      expect.stringMatching(/^team-operator-library-/),
      1,
      expect.objectContaining({
        target: expect.objectContaining({
          assetRef: 'support:classify-ticket',
          portName: 'request',
        }),
      }),
      `sha256:${'a'.repeat(64)}`,
      [
        { confirmationId: `sha256:${'c'.repeat(64)}`, value: 'OPEN' },
        { confirmationId: `sha256:${'d'.repeat(64)}`, value: 'KEEP_STRING' },
      ],
    );
    expect(document.querySelector('[data-testid="sample-inference-dialog"]')).toBeNull();
    expect(query<HTMLInputElement>('[aria-label="Inputs field 1 name"]').value).toBe('request');
    expect(query<HTMLInputElement>('[aria-label="Inputs field request type"]').value).toBe('object');
    expect(query('.schema-tree-field-group').textContent).toContain('customerId');
    expect(query('.schema-tree-field-group').textContent).toContain('priority');
    expect(query('[data-testid="library-save-state"]').textContent).toContain('Saved revision 2');
  });

  async function renderWorkbench() {
    await act(async () => {
      root = createRoot(host);
      root.render(<LibraryWorkbench />);
    });
    await settle();
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

function sampleInferenceResult(draftId: string): VisualSampleInferenceResult {
  const target = {
    assetKind: 'OPERATOR' as const,
    assetRef: 'support:classify-ticket',
    portDirection: 'INPUT' as const,
    portName: 'request',
  };
  return {
    schemaVersion: 'bloge.visualSampleInferenceResult.v1',
    draftId,
    authoringRevision: 1,
    target,
    evidenceFingerprint: `sha256:${'a'.repeat(64)}`,
    inferencerVersion: 'sample-inferencer-v1',
    redactionProfileVersion: 'redaction-v1',
    sampleCount: 2,
    candidate: {
      fields: {
        customerId: 'string',
        priority: 'string',
      },
      additionalProperties: true,
    },
    observations: [{
      factId: `sha256:${'b'.repeat(64)}`,
      authoringPath: '/operators/support:classify-ticket/input/request/priority',
      sourceLevel: 'OBSERVED',
      suggestedType: 'string',
      sampleCount: 2,
      presenceCount: 2,
      nullCount: 0,
      distinctCount: 2,
      sensitive: false,
      requiredCandidate: true,
      nullableCandidate: false,
      formatCandidate: '',
      enumCandidates: ['HIGH', 'LOW'],
      conflictTypes: [],
      widenReasons: [],
    }],
    confirmationRequests: [
      {
        confirmationId: `sha256:${'c'.repeat(64)}`,
        factId: `sha256:${'b'.repeat(64)}`,
        code: 'RG.AUTHORING.INFERENCE_OBJECT_CLOSURE_CONFIRMATION_REQUIRED',
        authoringPath: '/operators/support:classify-ticket/input/request',
        question: 'Can valid payloads contain other fields?',
        recommendedValue: 'OPEN',
        allowedValues: ['OPEN', 'CLOSED'],
        blocking: false,
      },
      {
        confirmationId: `sha256:${'d'.repeat(64)}`,
        factId: `sha256:${'b'.repeat(64)}`,
        code: 'RG.AUTHORING.INFERENCE_ENUM_CONFIRMATION_REQUIRED',
        authoringPath: '/operators/support:classify-ticket/input/request/priority',
        question: 'Do the values form a complete business enum?',
        recommendedValue: 'KEEP_STRING',
        allowedValues: ['KEEP_STRING', 'DECLARE_ENUM'],
        blocking: false,
      },
    ],
    diagnostics: [],
    payloadPersisted: false,
  };
}

function sampleFixtureReceipt() {
  return {
    schemaVersion: 'bloge.visualAuthoringFixtureReceipt.v1',
    tenantId: 'tenant-a',
    organizationId: 'organization-a',
    projectId: 'project-a',
    environmentId: 'test',
    region: 'region-a',
    fixtureId: 'sample:support:classify-ticket:request',
    revision: 1,
    sourceKind: 'SAMPLE',
    assetKind: 'OPERATOR',
    assetRef: 'support:classify-ticket',
    draftId: 'sample-draft',
    authoringRevision: 1,
    authoringFingerprint: `sha256:${'a'.repeat(64)}`,
    canonicalFingerprint: `sha256:${'c'.repeat(64)}`,
    artifactFingerprint: `sha256:${'o'.repeat(64)}`,
    payloadFingerprint: `sha256:${'p'.repeat(64)}`,
    classification: 'INTERNAL',
    retentionPolicyVersion: 'retention-v1',
    expiresAt: '2026-08-06T00:00:00Z',
    redactionProfileVersion: 'redaction-v1',
    redactedPaths: [],
    createdAt: '2026-07-30T00:00:00Z',
    createdBy: 'tester',
    payloadPersisted: true,
    payloadReturned: false,
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

async function settle(): Promise<void> {
  await act(async () => {
    await Promise.resolve();
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
