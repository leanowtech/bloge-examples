// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type {
  VisualAuthoringFunctionTestDraft,
  VisualAuthoringFunctionTestRunEvidence,
  VisualAuthoringOperatorTestDraft,
  VisualAuthoringOperatorTestRunEvidence,
  VisualAuthoringTestDraftGate,
  VisualAuthoringTestEvidenceView,
  VisualLibraryAuthoringDraft,
} from '../types';
import AssetTestTable from './AssetTestTable';

const apiMocks = vi.hoisted(() => ({
  draftFunction: vi.fn(),
  draftOperator: vi.fn(),
  runFunction: vi.fn(),
  runOperator: vi.fn(),
  fetchEvidence: vi.fn(),
  fetchGate: vi.fn(),
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
  fetchLibraryAuthoringTestEvidence: apiMocks.fetchEvidence,
  fetchLibraryAuthoringTestGate: apiMocks.fetchGate,
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
    apiMocks.fetchEvidence.mockResolvedValue(evidenceView());
    apiMocks.fetchGate.mockResolvedValue(draftGate());
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
    expect(query<HTMLInputElement>('[aria-label="request"]').value).toBe('sample');
    expect(host.querySelector('[aria-label="Operator case 1 inputs JSON"]')).toBeNull();
    expect(query('[data-testid="asset-scenario-workspace"]').textContent)
      .toContain('Given');
    expect(query('[data-testid="asset-scenario-workspace"]').textContent)
      .toContain('Then');
    expect(Array.from(host.querySelectorAll('.scenario-case-step-rail a')).map((link) => (
      link.getAttribute('href')
    ))).toEqual([
      '#operator-case-editor-1-given',
      '#operator-case-editor-1-dependencies',
      '#operator-case-editor-1-then',
      '#operator-case-editor-1-review',
    ]);
    expect(query('#operator-case-editor-1-review').textContent).toContain('Validate this case');

    await changeValue(query<HTMLInputElement>('[aria-label="request"]'), 'edited');

    await click(query('[data-testid="library-test-run-all"]'));
    await settle();

    expect(apiMocks.runOperator).toHaveBeenCalledWith(
      'test-draft',
      3,
      expect.objectContaining({
        operatorRef: 'demo:echo',
        cases: [
          expect.objectContaining({
            inputs: { request: 'edited' },
            mockedOutputs: { result: 'sample' },
          }),
        ],
      }),
    );
    expect(query('[data-testid="library-test-dialog"]').textContent).toContain('Schema valid');
    expect(query('[data-testid="library-test-dialog"]').textContent).not.toContain('Passed');
    expect(query('[data-testid="library-test-dialog"]').textContent).toContain('sha256:mmmmmmm...');
    expect(query('[data-testid="library-test-evidence-trust"]').textContent)
      .toContain('SIGNED CURRENT');
    expect(query('[data-testid="library-test-draft-gate"]').textContent)
      .toContain('Draft gate 1/1');
  });

  it('makes an unbound function visible instead of presenting a false pass', async () => {
    apiMocks.draftFunction.mockResolvedValue(functionDraft());
    apiMocks.runFunction.mockResolvedValue(functionEvidence());
    await renderTable('function', 'teamNormalize');

    expect(query('[data-testid="library-test-dialog"]').textContent).toContain('UNBOUND');
    expect(query('[data-testid="library-test-dialog"]').textContent)
      .toContain('Runner ISOLATED PROCESS');
    expect(query('[data-testid="library-test-dialog"]').textContent)
      .toContain('No exact callable was found');
    expect(query<HTMLInputElement>('[aria-label="value"]').value).toBe('sample');
    expect(host.querySelector('[aria-label="Function case 1 arguments JSON"]')).toBeNull();
    expect(query('[data-testid="asset-scenario-workspace"]').textContent)
      .toContain('DependenciesRuntime binding');
    expect(query('[data-testid="asset-scenario-workspace"]').textContent)
      .toContain('UNBOUND');
    expect(Array.from(host.querySelectorAll('.scenario-case-step-rail a')).map((link) => (
      link.getAttribute('href')
    ))).toEqual([
      '#function-case-editor-1-given',
      '#function-case-editor-1-dependencies',
      '#function-case-editor-1-then',
      '#function-case-editor-1-review',
    ]);

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

  it('projects operator cases into the shared Matrix and runs an exact selection', async () => {
    const generated = operatorDraft();
    generated.suite.cases.push({
      ...generated.suite.cases[0],
      name: 'second contract case',
      inputs: { request: 'second' },
      mockedOutputs: { result: 'second' },
    });
    const evidence = operatorEvidence();
    evidence.result.totalCases = 2;
    evidence.result.passedCases = 2;
    evidence.result.results.push({ name: 'second contract case', passed: true, diagnostics: [] });
    apiMocks.draftOperator.mockResolvedValue(generated);
    apiMocks.runOperator.mockResolvedValue(evidence);
    await renderTable('operator', 'demo:echo');

    expect(query('[data-testid="scenario-matrix"]').textContent).toContain('2 canonical cases');
    await click(query('[aria-label="Select generated contract case"]'));
    await click(query('[aria-label="Select second contract case"]'));
    await click(query('[data-testid="scenario-run-selected"]'));
    await settle();

    expect(apiMocks.runOperator).toHaveBeenCalledWith(
      'test-draft',
      3,
      expect.objectContaining({
        cases: [
          expect.objectContaining({ name: 'generated contract case', inputs: { request: 'sample' } }),
          expect.objectContaining({ name: 'second contract case', inputs: { request: 'second' } }),
        ],
      }),
    );
    expect(query('[data-testid="scenario-matrix-row-operator-case-1"]').textContent)
      .toContain('Schema contract valid');
  });

  it('requires explicit governance confirmation before persisting one parsed test row', async () => {
    apiMocks.draftOperator.mockResolvedValue(operatorDraft());
    apiMocks.saveFixture.mockResolvedValue(fixtureReceipt());
    await renderTable('operator', 'demo:echo');

    await click(query('[data-testid="operator-fixture-save-0"]'));
    expect(query('[data-testid="governed-fixture-panel"]').textContent)
      .toContain('Save as fixture');
    expect(query('[data-testid="governed-fixture-panel"]').getAttribute('role'))
      .toBe('complementary');
    expect(host.querySelectorAll('[role="dialog"]')).toHaveLength(1);
    expect(query<HTMLButtonElement>('[data-testid="governed-fixture-save"]').disabled).toBe(true);
    expect(query<HTMLTextAreaElement>('[aria-label="Fixture redaction paths"]').placeholder)
      .toBe('/inputs/request\n/mockedOutputs/result');
    expect(query('[data-testid="governed-fixture-preview"]').textContent)
      .toContain('"request": "sample"');
    await changeValue(
      query<HTMLTextAreaElement>('[aria-label="Fixture redaction paths"]'),
      '/inputs/request',
    );
    expect(query('[data-testid="governed-fixture-preview"]').textContent)
      .toContain('"request": "[REDACTED]"');

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

async function changeValue(
  element: HTMLInputElement | HTMLTextAreaElement,
  value: string,
) {
  await act(async () => {
    const prototype = element instanceof HTMLTextAreaElement
      ? HTMLTextAreaElement.prototype
      : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(prototype, 'value')?.set?.call(element, value);
    element.dispatchEvent(new Event('input', { bubbles: true }));
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
      operators: {
        'demo:echo': {
          input: { request: 'string' },
          config: { fields: { 'timeoutMs?': 'integer' } },
          output: { result: 'string' },
        },
      },
      functions: {
        teamNormalize: {
          signatures: ['(value: string) -> string'],
        },
      },
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
    executionProfile: 'bloge-core-isolated-process.v1',
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
    executionProfile: 'bloge-core-isolated-process.v1',
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

function evidenceView(): VisualAuthoringTestEvidenceView {
  return {
    schemaVersion: 'bloge.visualAuthoringTestEvidenceView.v1',
    evidence: {
      schemaVersion: 'bloge.visualAuthoringTestEvidenceRecord.v1',
      scope: {
        tenantId: 'tenant-a',
        organizationId: 'organization-a',
        projectId: 'project-a',
        environmentId: 'test',
        region: 'region-a',
      },
      runId: 'operator-run',
      assetKind: 'OPERATOR',
      assetRef: 'demo:echo',
      draftId: 'test-draft',
      authoringRevision: 3,
      authoringFingerprint: `sha256:${'a'.repeat(64)}`,
      canonicalFingerprint: `sha256:${'c'.repeat(64)}`,
      artifactFingerprint: `sha256:${'o'.repeat(64)}`,
      runtimeFingerprint: '',
      executionProfile: '',
      suiteFingerprint: `sha256:${'s'.repeat(64)}`,
      sourceEvidenceFingerprint: `sha256:${'e'.repeat(64)}`,
      policyVersion: 'visual-authoring-test-evidence-gate.v1',
      proofMode: 'SCHEMA_CONTRACT',
      bindingStatus: '',
      passed: true,
      totalCases: 1,
      passedCases: 1,
      failedCases: 0,
      requiredCaseCount: 1,
      coverage: {
        inputPortSchemaValidated: 1,
        configSchemaValidated: 1,
        mockedOutputSchemaValidated: 1,
        mockedOutputCount: 1,
        assertionCount: 0,
      },
      cases: [{
        caseId: `case:sha256:${'d'.repeat(64)}`,
        kind: 'CONTRACT',
        status: 'PASSED',
        passed: true,
        assertionCount: 0,
        durationMicros: 0,
        errorCode: '',
        diagnosticCodes: [],
      }],
      declaredTestRefs: [],
      diagnosticCodes: [],
      executedAt: '2026-07-30T00:00:00Z',
      actorId: 'tester',
      payloadPersisted: false,
      materialFingerprint: `sha256:${'m'.repeat(64)}`,
      seal: {
        schemaVersion: 'bloge.visualRunEvidenceSeal.v1',
        materialFingerprint: `sha256:${'m'.repeat(64)}`,
        algorithm: 'Ed25519',
        keyId: 'memory-key',
        signedAt: '2026-07-30T00:00:00Z',
        signature: 'detached-signature',
      },
    },
    integrityStatus: 'VERIFIED',
    freshness: 'CURRENT',
    staleReasons: [],
    observedDraftRevision: 3,
    observedAuthoringFingerprint: `sha256:${'a'.repeat(64)}`,
    observedCanonicalFingerprint: `sha256:${'c'.repeat(64)}`,
    evaluatedAt: '2026-07-30T00:00:01Z',
  };
}

function draftGate(): VisualAuthoringTestDraftGate {
  return {
    schemaVersion: 'bloge.visualAuthoringTestEvidenceGate.v1',
    scope: evidenceView().evidence.scope,
    draftId: 'test-draft',
    authoringRevision: 3,
    authoringFingerprint: `sha256:${'a'.repeat(64)}`,
    canonicalFingerprint: `sha256:${'c'.repeat(64)}`,
    policyVersion: 'visual-authoring-test-evidence-gate.v1',
    status: 'PASSED',
    achievedMaturity: 'TEST_EVIDENCED',
    requiredAssets: 1,
    satisfiedAssets: 1,
    reasons: [],
    assets: [{
      assetKind: 'OPERATOR',
      assetRef: 'demo:echo',
      status: 'PASSED',
      reasons: [],
      evidenceRunId: 'operator-run',
      evidenceFingerprint: `sha256:${'m'.repeat(64)}`,
      freshness: 'CURRENT',
      requiredCases: 1,
      observedCases: 1,
      observedAssertions: 0,
      proofMode: 'SCHEMA_CONTRACT',
    }],
    evaluatedAt: '2026-07-30T00:00:01Z',
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
