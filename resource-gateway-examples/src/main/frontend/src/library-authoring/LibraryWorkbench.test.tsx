// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { BlogeApiRequestError } from '../api';
import I18nProvider from '../i18n/I18nProvider';
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
    window.sessionStorage.clear();
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
    vi.unstubAllGlobals();
  });

  it('makes the empty-library start choice the only active central task', async () => {
    await renderWorkbench();
    await settle();

    expect(query('[data-testid="library-home"]').getAttribute('data-view')).toBe('start');
    expect(query<HTMLElement>('.library-home-heading').hidden).toBe(true);
    expect(query<HTMLElement>('.library-home-work-queue').hidden).toBe(true);
    expect(query('[data-testid="library-home-create-panel"]')).toBeTruthy();

    await click(buttonByText('Close'));
    expect(query('[data-testid="library-home"]').getAttribute('data-view')).toBe('queue');
    expect(query<HTMLElement>('.library-home-heading').hidden).toBe(false);
    expect(query<HTMLElement>('.library-home-work-queue').hidden).toBe(false);
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
    expect(query('[data-testid="workspace-context-bar"]').textContent)
      .toContain('tenant-a');
    expect(query('[data-testid="workspace-context-bar"]').textContent)
      .toContain('TEST');
    expect(query('[data-testid="workspace-context-bar"]').getAttribute('data-role'))
      .toBe('editor');
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

  it('recovers an unsaved Library edit after leaving before the 700ms autosave deadline', async () => {
    apiMocks.save.mockImplementation(async (
      draftId: string,
      _revision: number,
      document: VisualLibraryAuthoringDocument,
    ) => storedDraft(draftId, 1, document));
    apiMocks.preview.mockImplementation(async (draftId: string) => readyPreview(draftId, 1));

    await renderWorkbench();
    await click(query('[data-testid="library-start-example:customer-support"]'));
    const name = inputForLabel('Name');
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set
        ?.call(name, 'Recovered Support Library');
      name.dispatchEvent(new Event('input', { bubbles: true }));
      name.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(360);
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(apiMocks.save).not.toHaveBeenCalled();
    expect(window.sessionStorage.length).toBe(1);
    await act(async () => root?.unmount());
    root = null;
    window.history.replaceState({}, '', '/libraries/');

    await renderWorkbench();
    await settle();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(inputForLabel('Name').value).toBe('Recovered Support Library');
    expect(query('[data-testid="library-save-state"]').textContent)
      .toContain('Recovered unsaved work');
    expect(query('[data-testid="workspace-context-bar"]').textContent).toContain('RECOVERED');
  });

  it('projects mobile Library work into review and basic metadata editing', async () => {
    vi.stubGlobal('matchMedia', vi.fn().mockImplementation(() => ({
      matches: true,
      media: '(max-width: 520px)',
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })));
    apiMocks.save.mockImplementation(async (
      draftId: string,
      _revision: number,
      document: VisualLibraryAuthoringDocument,
    ) => storedDraft(draftId, 1, document));
    apiMocks.preview.mockImplementation(async (draftId: string) => readyPreview(draftId, 1));

    await renderWorkbench();
    await click(query('[data-testid="library-start-example:customer-support"]'));

    expect(query('[data-testid="library-workbench"]').getAttribute('data-responsive-task'))
      .toBe('LIBRARY_REVIEW');
    expect(query('[data-testid="mobile-library-review"]')).toBeTruthy();
    expect(document.querySelector('.library-tree')).toBeNull();
    expect(document.querySelector('.library-contract-preview')).toBeNull();
    expect(document.querySelector('.schema-tree-editor')).toBeNull();
    expect(query('[data-testid="mobile-library-task"]')
      .getAttribute('data-max-primary-actions')).toBe('1');

    const picker = query<HTMLSelectElement>('[aria-label="Current asset"]');
    await act(async () => {
      picker.value = 'operator|support%3Aclassify-ticket';
      picker.dispatchEvent(new Event('change', { bubbles: true }));
    });
    expect(query('[data-testid="mobile-library-review"]').textContent).toContain('Classify Ticket');
    expect(query('[data-testid="mobile-library-review"]').textContent).toContain('Inputs2');
    expect(query('[data-testid="mobile-library-review"]').textContent).toContain('Outputs1');
    expect(window.location.search).toContain('assetKind=operator');
    expect(window.location.search).toContain('assetRef=support%3Aclassify-ticket');

    await click(query<HTMLButtonElement>('.mobile-library-review-actions .primary'));
    expect(query('[data-testid="library-workbench"]').getAttribute('data-responsive-task'))
      .toBe('LIBRARY_LIGHT_EDIT');
    expect(query('[data-testid="mobile-library-light-editor"]')).toBeTruthy();
    expect(document.querySelector('[data-testid="operator-builder"]')).toBeNull();
    expect(document.querySelector('.schema-tree-editor')).toBeNull();

    const nameInput = inputForLabel('Display name');
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set
        ?.call(nameInput, 'Classify Priority Ticket');
      nameInput.dispatchEvent(new Event('input', { bubbles: true }));
      nameInput.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await flushAutosave();
    expect(window.location.search).toContain('revision=1');
    expect(window.location.search).toContain('assetRef=support%3Aclassify-ticket');
    expect(apiMocks.save).toHaveBeenCalledWith(
      expect.stringMatching(/^customer-support-authoring-/),
      0,
      expect.objectContaining({
        operators: expect.objectContaining({
          'support:classify-ticket': expect.objectContaining({ name: 'Classify Priority Ticket' }),
        }),
      }),
      'QUICK',
    );

    await click(buttonByText('Review changes'));
    expect(query('[data-testid="mobile-library-review"]').textContent)
      .toContain('Classify Priority Ticket');
    const desktopLink = query<HTMLAnchorElement>('.mobile-library-desktop-link');
    expect(desktopLink.href).toContain('assetKind=operator');
    expect(desktopLink.href).toContain('assetRef=support%3Aclassify-ticket');
    expect(desktopLink.href).toContain('task=complex-edit');
  });

  it('renders dynamic mobile readiness, runtime, and save notices in Chinese', async () => {
    window.history.replaceState({}, '', '/libraries/?lang=zh-CN');
    vi.stubGlobal('matchMedia', vi.fn().mockImplementation(() => ({
      matches: true,
      media: '(max-width: 520px)',
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })));
    apiMocks.save.mockImplementation(async (
      draftId: string,
      _revision: number,
      document: VisualLibraryAuthoringDocument,
    ) => storedDraft(draftId, 1, document));
    apiMocks.preview.mockImplementation(async (draftId: string) => ({
      ...readyPreview(draftId, 1),
      runtimeParity: [{
        assetKind: 'OPERATOR',
        assetRef: 'support:classify-ticket',
        runtimeProfile: 'demo',
        state: 'DOCUMENTED_ONLY',
        executableReady: false,
        declaredFingerprint: 'sha256:declared',
        runtimeFingerprint: '',
        reasonCode: 'RG.AUTHORING.RUNTIME_OPERATOR_MISSING',
        message: 'No exact operator was found in the target runtime inventory.',
      }],
    }));

    await renderWorkbench(true);
    await click(query('[data-testid="library-start-example:customer-support"]'));
    await flushAutosave();

    const surfaceText = query('[data-testid="library-workbench"]').textContent ?? '';
    expect(surfaceText).toContain('已保存修订版 1');
    expect(surfaceText).toContain('设计有效，运行时未绑定');
    expect(surfaceText).toContain('当前部署可执行 0/1 个已声明资产');
    expect(surfaceText).not.toContain('Saved revision');
    expect(surfaceText).not.toContain('Design valid; runtime unbound');
    expect(surfaceText).not.toContain('declared assets can execute');
    expect(surfaceText).toContain('下一步');
    expect(surfaceText).not.toContain('下一页');

    const picker = query<HTMLSelectElement>('[aria-label="当前资产"]');
    await act(async () => {
      picker.value = 'operator|support%3Aclassify-ticket';
      picker.dispatchEvent(new Event('change', { bubbles: true }));
    });
    const operatorText = query('[data-testid="mobile-library-review"]').textContent ?? '';
    expect(operatorText).toContain('执行类型纯数据转换');
    expect(operatorText).toContain('运行时仅有文档');
    expect(query('[data-testid="mobile-library-root-blocker"]').textContent)
      .toContain('此资产被运行时就绪度阻断');
    expect(query('[data-testid="mobile-library-root-blocker"]').textContent)
      .toContain('目标运行时清单中不存在精确匹配的算子');
    expect(operatorText).not.toContain('DOCUMENTED ONLY');
  });

  it('compares an ETag conflict and requires explicit confirmation before reloading', async () => {
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

    expect(apiMocks.save).toHaveBeenCalledOnce();
    expect(query('[data-testid="library-save-state"]').textContent).toContain('Conflict');
    expect(query('[data-testid="library-save-state"]').textContent)
      .toContain('newer revision exists');
    expect(query('[data-testid="save-conflict-comparison"]').textContent)
      .toContain('Server Library');
    expect(query('[data-conflict-fact="operators"]').textContent).toContain('1');
    expect(query('[data-conflict-fact="operators"]').textContent).toContain('0');

    await click(query('[data-testid="save-conflict-reload"]'));

    expect(query('[data-testid="library-metadata-builder"]').textContent)
      .not.toContain('Server Library');
    expect(query('[data-testid="save-conflict-dialog"]').textContent)
      .toContain('cannot be undone');

    await click(query('[data-testid="save-conflict-confirm-reload"]'));

    expect(apiMocks.fetchDraft).toHaveBeenCalledTimes(1);
    expect(query('[data-testid="library-metadata-builder"]').textContent)
      .toContain('Server Library');
    expect(query('[data-testid="library-save-state"]').textContent)
      .toContain('Saved revision 4');
  });

  it('forks conflicted local work into a new autosaved draft without overwriting the head', async () => {
    apiMocks.save
      .mockRejectedValueOnce(new BlogeApiRequestError(412, 'revision mismatch'))
      .mockImplementation(async (
        draftId: string,
        _revision: number,
        nextDocument: VisualLibraryAuthoringDocument,
      ) => storedDraft(draftId, 1, nextDocument));
    apiMocks.fetchDraft.mockImplementation(async (draftId: string) => storedDraft(
      draftId,
      3,
      {
        schemaVersion: 'bloge.visualLibraryAuthoring.v1',
        library: { id: 'server-library', name: 'Server Library', owner: 'platform-team' },
        types: {},
        operators: {},
        functions: {},
      },
    ));
    apiMocks.preview.mockImplementation(async (draftId: string, revision: number) => (
      readyPreview(draftId, revision)
    ));

    await renderWorkbench();
    await click(query('[data-testid="library-quick-create"]'));
    await flushAutosave();
    const conflictedDraftId = String(apiMocks.save.mock.calls[0][0]);

    await click(query('[data-testid="save-conflict-fork"]'));
    await settle();
    expect(document.querySelector('[data-testid="save-conflict-dialog"]')).toBeNull();
    expect(query('[data-testid="library-metadata-builder"]').textContent)
      .toContain('Team Operator Library');

    expect(apiMocks.save).toHaveBeenCalledTimes(2);
    expect(apiMocks.save.mock.calls[1][0]).not.toBe(conflictedDraftId);
    expect(apiMocks.save.mock.calls[1][1]).toBe(0);
    expect(Object.keys(apiMocks.save.mock.calls[1][2].operators ?? {})).toHaveLength(1);
    expect(query('[data-testid="library-save-state"]').textContent)
      .toContain('preserved as revision 1');
  });

  it('reclaims the same conflict fork after an ambiguous successful response', async () => {
    let originalDraftId = '';
    let reservedForkId = '';
    let forkDocument: VisualLibraryAuthoringDocument | null = null;
    let saveCall = 0;
    apiMocks.save.mockImplementation(async (
      draftId: string,
      _revision: number,
      nextDocument: VisualLibraryAuthoringDocument,
    ) => {
      saveCall += 1;
      if (saveCall === 1) {
        originalDraftId = draftId;
        throw new BlogeApiRequestError(412, 'revision mismatch');
      }
      if (saveCall === 2) {
        reservedForkId = draftId;
        forkDocument = structuredClone(nextDocument);
        throw new Error('network response lost after commit');
      }
      expect(draftId).toBe(reservedForkId);
      throw new BlogeApiRequestError(412, 'revision already exists');
    });
    apiMocks.fetchDraft.mockImplementation(async (draftId: string) => {
      if (draftId === originalDraftId) {
        return storedDraft(draftId, 4, {
          schemaVersion: 'bloge.visualLibraryAuthoring.v1',
          library: { id: 'server-library', name: 'Server Library', owner: 'platform-team' },
          types: {}, operators: {}, functions: {},
        });
      }
      return storedDraft(draftId, 1, forkDocument as VisualLibraryAuthoringDocument);
    });

    await renderWorkbench();
    await click(query('[data-testid="library-quick-create"]'));
    await flushAutosave();
    await click(query('[data-testid="save-conflict-fork"]'));
    await settle();

    expect(query('[data-testid="save-conflict-dialog"]').textContent)
      .toContain('network response lost');
    await click(query('[data-testid="save-conflict-fork"]'));
    await settle();

    expect(document.querySelector('[data-testid="save-conflict-dialog"]')).toBeNull();
    expect(apiMocks.save).toHaveBeenCalledTimes(3);
    expect(apiMocks.save.mock.calls[1][0]).toBe(apiMocks.save.mock.calls[2][0]);
    expect(query('[data-testid="library-save-state"]').textContent)
      .toContain('preserved as revision 1');
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

  async function renderWorkbench(withI18n = false) {
    await act(async () => {
      root = createRoot(host);
      root.render(withI18n
        ? <I18nProvider><LibraryWorkbench /></I18nProvider>
        : <LibraryWorkbench />);
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

function inputForLabel(label: string): HTMLInputElement {
  const candidate = Array.from(document.querySelectorAll<HTMLLabelElement>('label'))
    .find((element) => element.querySelector('span')?.textContent === label)
    ?.querySelector('input');
  expect(candidate, `Expected input for label ${label}`).not.toBeNull();
  return candidate as HTMLInputElement;
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
  for (let attempt = 0; attempt < 4; attempt += 1) {
    await act(async () => {
      await crypto.subtle.digest('SHA-256', new Uint8Array([attempt]));
      await Promise.resolve();
      await vi.advanceTimersByTimeAsync(710);
      await Promise.resolve();
      await Promise.resolve();
    });
  }
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
