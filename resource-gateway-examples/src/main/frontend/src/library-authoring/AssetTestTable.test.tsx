// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type {
  VisualAuthoringFunctionTestDraft,
  VisualAuthoringFunctionTestRunEvidence,
  VisualAuthoringOperatorTestDraft,
  VisualAuthoringOperatorTestRunEvidence,
  VisualLibraryAuthoringDraft,
} from '../types';
import AssetTestTable from './AssetTestTable';

const apiMocks = vi.hoisted(() => ({
  draftFunction: vi.fn(),
  draftOperator: vi.fn(),
  runFunction: vi.fn(),
  runOperator: vi.fn(),
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
  draftLibraryAuthoringFunctionTest: apiMocks.draftFunction,
  draftLibraryAuthoringOperatorTest: apiMocks.draftOperator,
  runLibraryAuthoringFunctionTest: apiMocks.runFunction,
  runLibraryAuthoringOperatorTest: apiMocks.runOperator,
  saveLibraryAuthoringFixture: apiMocks.saveFixture,
}));

describe('AssetTestTable', () => {
  let host: HTMLDivElement;
  let root: Root | null;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean })
      .IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
    root = null;
    Object.values(apiMocks).forEach((mock) => mock.mockReset());
  });

  afterEach(async () => {
    if (root) {
      await act(async () => root?.unmount());
      root = null;
    }
    host.remove();
    vi.restoreAllMocks();
  });

  it('generates, edits, and runs an exact-draft operator contract table', async () => {
    apiMocks.draftOperator.mockResolvedValue(operatorDraft());
    apiMocks.runOperator.mockResolvedValue(operatorEvidence());
    await renderTable('operator', 'demo:echo');

    expect(query('[data-testid="library-test-dialog"]').textContent)
      .toContain('SCHEMA CONTRACT');
    expect(query<HTMLTextAreaElement>('[aria-label="Operator case 1 inputs JSON"]').value)
      .toContain('"request": "sample"');

    await click(query('[data-testid="library-test-run-all"]'));
    await settle();

    expect(apiMocks.runOperator).toHaveBeenCalledWith(
      'test-draft',
      3,
      expect.objectContaining({
        operatorRef: 'demo:echo',
        cases: [
          expect.objectContaining({
            inputs: { request: 'sample' },
            mockedOutputs: { result: 'sample' },
          }),
        ],
      }),
    );
    expect(query('[data-testid="library-test-dialog"]').textContent).toContain('Passed');
    expect(query('[data-testid="library-test-dialog"]').textContent).toContain('sha256:eeeeeee...');
  });

  it('makes an unbound function visible instead of presenting a false pass', async () => {
    apiMocks.draftFunction.mockResolvedValue(functionDraft());
    apiMocks.runFunction.mockResolvedValue(functionEvidence());
    await renderTable('function', 'teamNormalize');

    expect(query('[data-testid="library-test-dialog"]').textContent).toContain('UNBOUND');
    expect(query('[data-testid="library-test-dialog"]').textContent)
      .toContain('No exact callable was found');

    await click(query('[data-testid="library-test-run-all"]'));
    await settle();

    expect(apiMocks.runFunction).toHaveBeenCalledWith(
      'test-draft',
      3,
      expect.objectContaining({
        functionRef: 'teamNormalize',
        cases: [
          expect.objectContaining({
            args: ['sample'],
            assertion: 'RETURN_TYPE',
          }),
        ],
      }),
    );
    expect(query('[data-testid="library-test-dialog"]').textContent).toContain('NOT_RUN');
  });

  it('requires explicit governance confirmation before persisting one parsed test row', async () => {
    apiMocks.draftOperator.mockResolvedValue(operatorDraft());
    apiMocks.saveFixture.mockResolvedValue(fixtureReceipt());
    await renderTable('operator', 'demo:echo');

    await click(query('[data-testid="operator-fixture-save-0"]'));
    expect(query('[data-testid="governed-fixture-dialog"]').textContent)
      .toContain('Save as fixture');
    expect(query<HTMLButtonElement>('[data-testid="governed-fixture-save"]').disabled).toBe(true);
    expect(query<HTMLTextAreaElement>('[aria-label="Fixture redaction paths"]').placeholder)
      .toBe('/inputs/request\n/mockedOutputs/result');

    await click(query('[data-testid="governed-fixture-confirm"]'));
    expect(query<HTMLButtonElement>('[data-testid="governed-fixture-save"]').disabled).toBe(false);
    await click(query('[data-testid="governed-fixture-save"]'));
    await settle();

    expect(apiMocks.saveFixture).toHaveBeenCalledWith(
      'test-draft',
      3,
      expect.objectContaining({
        schemaVersion: 'bloge.visualAuthoringFixtureSaveRequest.v1',
        sourceKind: 'OPERATOR_TEST_CASE',
        assetKind: 'OPERATOR',
        assetRef: 'demo:echo',
        classification: 'INTERNAL',
        retentionDays: 7,
        payload: expect.objectContaining({
          inputs: { request: 'sample' },
          mockedOutputs: { result: 'sample' },
        }),
      }),
    );
    expect(query('[data-testid="governed-fixture-receipt"]').textContent)
      .toContain('operator:demo:echo:generated-contract-case');
    expect(query('[data-testid="governed-fixture-receipt"]').textContent)
      .toContain('Payload returnedNo');
  });

  async function renderTable(kind: 'operator' | 'function', assetRef: string) {
    root = createRoot(host);
    await act(async () => {
      root?.render(
        <AssetTestTable
          kind={kind}
          assetRef={assetRef}
          prepareDraft={async () => storedDraft()}
          fixtureAvailable
          onConflict={vi.fn()}
          onClose={vi.fn()}
        />,
      );
    });
    await settle();
  }

  function query<T extends Element = HTMLElement>(selector: string): T {
    const element = host.querySelector<T>(selector);
    if (!element) {
      throw new Error(`Missing element: ${selector}`);
    }
    return element;
  }
});

async function click(element: Element) {
  await act(async () => {
    element.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  });
}

async function settle() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

function storedDraft(): VisualLibraryAuthoringDraft {
  return {
    schemaVersion: 'bloge.visualLibraryAuthoringDraft.v1',
    draftId: 'test-draft',
    revision: 3,
    sourceMode: 'QUICK',
    document: {
      schemaVersion: 'bloge.visualLibraryAuthoring.v1',
      library: { id: 'test-library' },
      operators: {},
      functions: {},
    },
    fingerprint: `sha256:${'d'.repeat(64)}`,
    createdAt: '2026-07-30T00:00:00Z',
    updatedAt: '2026-07-30T00:00:00Z',
    savedBy: 'tester',
  };
}

function operatorDraft(): VisualAuthoringOperatorTestDraft {
  return {
    schemaVersion: 'bloge.visualAuthoringOperatorTestDraft.v1',
    draftId: 'test-draft',
    authoringRevision: 3,
    authoringFingerprint: `sha256:${'a'.repeat(64)}`,
    canonicalFingerprint: `sha256:${'c'.repeat(64)}`,
    artifactFingerprint: `sha256:${'o'.repeat(64)}`,
    suiteFingerprint: `sha256:${'s'.repeat(64)}`,
    suite: {
      schemaVersion: 'bloge.visualOperatorContractTestSuiteRequest.v1',
      operatorRef: 'demo:echo',
      cases: [{
        schemaVersion: 'bloge.visualOperatorContractTestCase.v1',
        name: 'generated contract case',
        description: '',
        inputs: { request: 'sample' },
        config: {},
        mockedOutputs: { result: 'sample' },
        outputAssertions: {},
      }],
    },
    diagnostics: [],
    payloadPersisted: false,
  };
}

function operatorEvidence(): VisualAuthoringOperatorTestRunEvidence {
  return {
    schemaVersion: 'bloge.visualAuthoringOperatorTestRunEvidence.v1',
    runId: 'operator-run',
    draftId: 'test-draft',
    authoringRevision: 3,
    authoringFingerprint: `sha256:${'a'.repeat(64)}`,
    canonicalFingerprint: `sha256:${'c'.repeat(64)}`,
    artifactFingerprint: `sha256:${'o'.repeat(64)}`,
    suiteFingerprint: `sha256:${'s'.repeat(64)}`,
    evidenceFingerprint: `sha256:${'e'.repeat(64)}`,
    executedAt: '2026-07-30T00:00:00Z',
    result: {
      schemaVersion: 'bloge.visualOperatorContractTestSuiteResult.v1',
      operatorRef: 'demo:echo',
      operatorVersion: '1.0.0',
      mode: 'SCHEMA_CONTRACT',
      passed: true,
      totalCases: 1,
      passedCases: 1,
      failedCases: 0,
      coverage: {
        inputPortSchemaValidated: 1,
        configSchemaValidated: 1,
        mockedOutputSchemaValidated: 1,
        mockedOutputCount: 1,
        assertionCount: 0,
      },
      results: [{ name: 'generated contract case', passed: true, diagnostics: [] }],
      diagnostics: [],
    },
    diagnostics: [],
    payloadPersisted: false,
  };
}

function functionDraft(): VisualAuthoringFunctionTestDraft {
  return {
    schemaVersion: 'bloge.visualAuthoringFunctionTestDraft.v1',
    draftId: 'test-draft',
    authoringRevision: 3,
    authoringFingerprint: `sha256:${'a'.repeat(64)}`,
    canonicalFingerprint: `sha256:${'c'.repeat(64)}`,
    functionFingerprint: `sha256:${'f'.repeat(64)}`,
    runtimeFingerprint: '',
    bindingStatus: 'UNBOUND',
    suiteFingerprint: `sha256:${'s'.repeat(64)}`,
    suite: {
      schemaVersion: 'bloge.visualAuthoringFunctionTestSuite.v1',
      functionRef: 'teamNormalize',
      cases: [{
        schemaVersion: 'bloge.visualAuthoringFunctionTestCase.v1',
        id: 'returns-declared-type',
        kind: 'BOUNDARY',
        args: ['sample'],
        assertion: 'RETURN_TYPE',
        expect: null,
        expectError: null,
      }],
    },
    diagnostics: [{
      code: 'visual.authoring.functionTest.runtimeUnbound',
      message: 'No exact callable was found in the BLOGE runtime inventory.',
    }],
    payloadPersisted: false,
  };
}

function functionEvidence(): VisualAuthoringFunctionTestRunEvidence {
  return {
    schemaVersion: 'bloge.visualAuthoringFunctionTestRunEvidence.v1',
    runId: 'function-run',
    draftId: 'test-draft',
    authoringRevision: 3,
    authoringFingerprint: `sha256:${'a'.repeat(64)}`,
    canonicalFingerprint: `sha256:${'c'.repeat(64)}`,
    functionFingerprint: `sha256:${'f'.repeat(64)}`,
    runtimeFingerprint: '',
    bindingStatus: 'UNBOUND',
    suiteFingerprint: `sha256:${'s'.repeat(64)}`,
    evidenceFingerprint: `sha256:${'e'.repeat(64)}`,
    executedAt: '2026-07-30T00:00:00Z',
    passed: false,
    totalCases: 1,
    passedCases: 0,
    failedCases: 1,
    results: [{
      id: 'returns-declared-type',
      kind: 'BOUNDARY',
      passed: false,
      status: 'NOT_RUN',
      actual: null,
      actualType: 'null',
      errorCode: '',
      durationMicros: 0,
      diagnostics: [{
        code: 'visual.authoring.functionTest.runtimeUnbound',
        message: 'No exact callable was found in the BLOGE runtime inventory.',
      }],
    }],
    diagnostics: [{
      code: 'visual.authoring.functionTest.runtimeUnbound',
      message: 'No exact callable was found in the BLOGE runtime inventory.',
    }],
    payloadPersisted: false,
  };
}

function fixtureReceipt() {
  return {
    schemaVersion: 'bloge.visualAuthoringFixtureReceipt.v1',
    tenantId: 'tenant-a',
    organizationId: 'organization-a',
    projectId: 'project-a',
    environmentId: 'test',
    region: 'region-a',
    fixtureId: 'operator:demo:echo:generated-contract-case',
    revision: 1,
    sourceKind: 'OPERATOR_TEST_CASE',
    assetKind: 'OPERATOR',
    assetRef: 'demo:echo',
    draftId: 'test-draft',
    authoringRevision: 3,
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
