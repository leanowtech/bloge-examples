// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { contractDraftFromGraphDraft } from './domain';
import ContractScenarioWorkspace from './ContractScenarioWorkspace';
import type { ScenarioEvidenceTrustContext } from './evidenceModel';
import { scenarioDraftSetFromCanvas, type ScenarioComparison } from './scenarioAuthoring';
import type { SimulationResponse } from '../types';
import {
  graphDraft,
  nodes,
  successfulResponse,
} from './testFixtures';

describe('ContractScenarioWorkspace', () => {
  let root: Root | null = null;
  let host: HTMLDivElement;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async () => new Response(
      JSON.stringify({ code: 'RG.SCENARIO.NOT_FOUND' }),
      { status: 404, statusText: 'Not Found', headers: { 'Content-Type': 'application/json' } },
    )));
    host = document.createElement('div');
    document.body.appendChild(host);
  });

  afterEach(async () => {
    if (root) {
      await act(async () => root?.unmount());
      root = null;
    }
    host.remove();
    vi.unstubAllGlobals();
  });

  it('opens with a searchable Contract field tree and four workspace views', async () => {
    await renderWorkspace();

    expect(text()).toContain('Graph Contract');
    expect(text()).toContain('applicantId');
    expect(text()).toContain('approved');
    expect(tabs()).toEqual(['Interface', 'Scenarios 1', 'Compatibility', 'Run Evidence']);
    expect(button('Load Scenario').disabled).toBe(false);
    expect(button('Save Scenario').disabled).toBe(false);
    expect(button('Publish').disabled).toBe(true);
  });

  it('starts each workspace view at its conclusion instead of retaining the previous scroll', async () => {
    await renderWorkspace();
    const body = document.querySelector('.contract-workspace-body') as HTMLDivElement;
    body.scrollTop = 240;

    await clickTab('Scenarios 1');

    expect(body.scrollTop).toBe(0);
  });

  it('blocks Scenario lifecycle actions until the Graph has an exact stored revision', async () => {
    await renderWorkspace({ unsaved: true });

    expect(button('Save Graph').disabled).toBe(false);
    expect(button('Load Scenario').disabled).toBe(true);
    expect(button('Save Scenario').disabled).toBe(true);
    expect(button('Publish').disabled).toBe(true);
  });

  it('edits Given graph input through native form controls', async () => {
    const onChange = vi.fn();
    await renderWorkspace({ onChange });
    await clickTab('Scenarios 1');
    const applicant = input('applicantId');

    await act(async () => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
      setter?.call(applicant, 'A-42');
      applicant.dispatchEvent(new Event('input', { bubbles: true }));
    });

    expect(onChange).toHaveBeenCalled();
    const latest = onChange.mock.calls[onChange.mock.calls.length - 1]?.[0];
    expect(latest.scenarios[0].given.input.applicantId).toBe('A-42');
  });

  it('states controlled and total dependency counts with one unambiguous denominator', async () => {
    await renderWorkspace();
    await clickTab('Scenarios 1');

    expect(text()).toContain('2 controlled / 2 total dependencies');
  });

  it('can open directly on Run Evidence for a result-review task', async () => {
    await renderWorkspace({
      initialTab: 'evidence',
      lastRun: successfulResponse(),
      lastRunScenarioId: 'approved',
      lastComparison: {
        passed: true,
        diagnostics: [],
        results: [{
          assertionId: 'approved-output',
          passed: true,
          path: '',
          expected: successfulResponse().output,
          actual: successfulResponse().output,
          detail: 'Values are equal.',
        }],
      },
    });

    expect(button('Run Evidence').getAttribute('aria-selected')).toBe('true');
    expect(document.querySelector('[data-testid="scenario-evidence"]')).not.toBeNull();
    expect(text()).toContain('1 assertion passed.');
    expect(text()).not.toContain('AssertionsNOT RUN');
  });

  it('keeps Advanced JSON synchronized after graphical edits', async () => {
    await renderControlledWorkspace();
    await clickTab('Scenarios 1');
    const applicant = input('applicantId');

    await act(async () => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
      setter?.call(applicant, 'A-99');
      applicant.dispatchEvent(new Event('input', { bubbles: true }));
    });

    expect(textarea('Advanced Scenario JSON').value).toContain('"applicantId": "A-99"');
  });

  it('compiles, runs, and presents assertion evidence', async () => {
    const onRun = vi.fn().mockResolvedValue(successfulResponse());
    await renderWorkspace({ onRun });
    await clickTab('Scenarios 1');

    await act(async () => {
      button('Run & Compare').click();
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(onRun).toHaveBeenCalledWith(expect.objectContaining({
      context: expect.objectContaining({ applicantId: '' }),
      outputNode: 'decide',
    }));
    expect(text()).toContain('Evidence incomplete');
    expect(text()).not.toContain('Ready for promotion');
    expect(text()).toContain('Terminal output');
    expect(text()).toContain('eligible');
    expect((document.querySelector('[data-testid="passed-assertions"]') as HTMLDetailsElement).open)
      .toBe(false);
  });

  it('publishes only deliberate tab and evidence coordinates to the author shell', async () => {
    const onCoordinateChange = vi.fn();
    const onRunEvidence = vi.fn();
    await renderWorkspace({
      onRun: vi.fn().mockResolvedValue(successfulResponse()),
      onCoordinateChange,
      onRunEvidence,
    });

    await clickTab('Scenarios 1');
    expect(onCoordinateChange).toHaveBeenLastCalledWith('scenarios', 'approved');

    await act(async () => {
      button('Run & Compare').click();
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(onRunEvidence).toHaveBeenCalledWith(
      'approved',
      expect.objectContaining({ passed: true }),
      expect.objectContaining({
        draft: expect.objectContaining({ graphName: 'loanGraph' }),
      }),
    );
    expect(onCoordinateChange).toHaveBeenLastCalledWith('evidence', 'approved');
    expect(onCoordinateChange).toHaveBeenCalledTimes(2);
  });

  it('claims readiness only when Draft, execution, assertions, Contract, and Governance pass', async () => {
    await renderWorkspace({
      onRun: vi.fn().mockResolvedValue(successfulResponse()),
      trustContext: {
        draftStatus: 'SAVED',
        evidenceFreshness: 'CURRENT',
        contractStatus: 'VALID',
        governanceStatus: 'APPROVED',
        coordinate: {
          draftId: 'loan-draft',
          draftRevision: 4,
          draftFingerprint: 'sha256:draft-4',
          contractFingerprint: 'sha256:contract-4',
          scenarioId: 'approved',
          scenarioRevision: 2,
          scenarioFingerprint: 'sha256:scenario-2',
          closureFingerprint: 'sha256:closure-4',
          requestFingerprint: 'sha256:request-9',
        },
      },
    });
    await clickTab('Scenarios 1');

    await act(async () => {
      button('Run & Compare').click();
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(text()).toContain('Ready for promotion');
    expect(text()).toContain('DraftSAVED');
    expect(text()).toContain('ExecutionPASSED');
    expect(text()).toContain('AssertionsPASSED');
    expect(text()).toContain('ContractVALID');
    expect(text()).toContain('GovernanceAPPROVED');
    expect(document.querySelector('[data-testid="scenario-evidence-coordinate"]')?.textContent)
      .toContain('loan-draft r4');
    expect(document.querySelector('[data-testid="scenario-evidence-coordinate"]')?.textContent)
      .toContain('approved r2');
    expect(document.querySelector('[data-testid="scenario-evidence-coordinate"]')?.textContent)
      .toContain('sha256:request-9');
  });

  it('keeps stale run evidence visible while blocking the changed authoring snapshot', async () => {
    await renderWorkspace({
      initialTab: 'evidence',
      lastRun: successfulResponse(),
      lastRunScenarioId: 'approved',
      lastComparison: {
        passed: true,
        diagnostics: [],
        results: [{
          assertionId: 'decision',
          passed: true,
          path: 'decision.approved',
          expected: true,
          actual: true,
          detail: 'Matched.',
        }],
      },
      trustContext: {
        draftStatus: 'DIRTY',
        evidenceFreshness: 'STALE',
        contractStatus: 'STALE',
        governanceStatus: 'STALE',
        coordinate: {
          draftId: 'loan-draft',
          draftRevision: 4,
          draftFingerprint: 'sha256:draft-4',
          contractFingerprint: 'sha256:contract-4',
          scenarioId: 'approved',
          scenarioRevision: 2,
          scenarioFingerprint: 'sha256:scenario-2',
          closureFingerprint: 'sha256:closure-4',
          requestFingerprint: 'sha256:request-9',
        },
      },
    });

    expect(text()).toContain('Promotion blocked');
    expect(text()).toContain('DraftDIRTY');
    expect(text()).toContain('ExecutionSTALE');
    expect(text()).toContain('AssertionsSTALE');
    expect(text()).toContain('"reason": "eligible"');
    expect(document.querySelector('[data-testid="scenario-evidence-coordinate"]')?.textContent)
      .toContain('sha256:draft-4');
    expect(document.querySelector('[data-testid="scenario-evidence-coordinate"]')?.textContent)
      .toContain('sha256:closure-4');
    expect(document.querySelector('[data-testid="scenario-evidence-coordinate"]')?.textContent)
      .toContain('sha256:request-9');
  });

  it('requires review when assertions pass but the Contract has a warning', async () => {
    await renderWorkspace({
      onRun: vi.fn().mockResolvedValue(successfulResponse()),
      trustContext: {
        contractStatus: 'WARNING',
        governanceStatus: 'APPROVED',
      },
    });
    await clickTab('Scenarios 1');

    await act(async () => {
      button('Run & Compare').click();
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(text()).toContain('Review required');
    expect(text()).toContain('CONTRACT_WARNING');
    expect(text()).not.toContain('Ready for promotion');
  });

  it('routes governed evidence findings back to their shared authoring source', async () => {
    const onSelectEvidenceDiagnostic = vi.fn();
    const diagnostic = {
      id: 'gate-owner',
      severity: 'WARNING',
      scope: 'GOVERNANCE',
      code: 'OWNER_APPROVAL_MISSING',
      message: 'Owner approval is required.',
      coordinate: '/owner',
      nodeId: '',
    };
    await renderWorkspace({
      onRun: vi.fn().mockResolvedValue(successfulResponse()),
      trustContext: {
        contractStatus: 'VALID',
        governanceStatus: 'APPROVED',
        diagnostics: [diagnostic],
      },
      onSelectEvidenceDiagnostic,
    });
    await clickTab('Scenarios 1');

    await act(async () => {
      button('Run & Compare').click();
      await Promise.resolve();
      await Promise.resolve();
    });
    await act(async () => button('Open source').click());

    expect(onSelectEvidenceDiagnostic).toHaveBeenCalledWith(diagnostic);
  });

  it('routes stale coordinates through compatibility review instead of blind rebase', async () => {
    const onRebase = vi.fn();
    await renderWorkspace({ stale: true, onRebase });

    expect(text()).toContain('Scenarios target an older graph or Contract.');
    await act(async () => button('Review compatibility').click());
    expect(text()).toContain('Review this local draft before establishing its first baseline');
    expect(button('Rebase local draft').disabled).toBe(true);
    const acknowledgement = document.querySelector(
      '.compatibility-resolution input[type="checkbox"]',
    );
    await act(async () => (acknowledgement as HTMLInputElement).click());
    await act(async () => button('Rebase local draft').click());
    expect(onRebase).toHaveBeenCalledOnce();
  });

  it('shows exact findings and requires acknowledgement before a breaking rebase', async () => {
    const onChange = vi.fn();
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (request) => {
      const url = String(request);
      if (url.includes('/compatibility?revision=1')) {
        return new Response(JSON.stringify({
          schemaVersion: 'bloge.contractCompatibilityReport.v1',
          scenarioDraftSetId: 'loanGraph-scenarios',
          scenarioRevision: 1,
          target: {
            kind: 'GRAPH',
            id: 'loan-graph',
            revision: 2,
            fingerprint: fingerprint('a'),
          },
          baselineContractFingerprint: fingerprint('d'),
          currentContractFingerprint: fingerprint('b'),
          policy: 'STRICT',
          classification: 'BREAKING',
          findings: [{
            findingId: 'F-001',
            scope: 'INPUT',
            path: '/country',
            previousPath: '',
            change: 'ADDED',
            classification: 'BREAKING',
            code: 'RG.CONTRACT.FIELD_ADDED',
            message: 'Field /country was added.',
            details: { currentRequired: true },
          }],
          impactedScenarios: [{
            scenarioId: 'approved',
            status: 'BLOCKED',
            findingIds: ['F-001'],
            paths: ['/country'],
          }],
          migrations: [{
            actionId: 'M-001',
            kind: 'SET_REQUIRED_VALUE',
            scope: 'INPUT',
            fromPath: '',
            toPath: '/country',
            automatic: false,
            scenarioIds: ['approved'],
            rationale: 'Provide an explicit value for the new required input.',
          }],
          generatedAt: '2026-07-27T00:00:00Z',
          reportFingerprint: fingerprint('e'),
        }));
      }
      return new Response('{}', { status: 404, statusText: 'Not Found' });
    }));
    await renderWorkspace({ stale: true, scenarioRevision: 1, onChange });

    await act(async () => {
      button('Review compatibility').click();
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(text()).toContain('Field /country was added.');
    expect(text()).toContain('Approved applicant');
    expect(button('Record review & rebase').disabled).toBe(true);

    const acknowledgement = document.querySelector(
      '.compatibility-resolution input[type="checkbox"]',
    );
    expect(acknowledgement).toBeInstanceOf(HTMLInputElement);
    await act(async () => (acknowledgement as HTMLInputElement).click());
    expect(button('Record review & rebase').disabled).toBe(false);
    await act(async () => button('Record review & rebase').click());
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({
      target: expect.objectContaining({ fingerprint: fingerprint('a') }),
      contractFingerprint: fingerprint('b'),
      metadata: expect.objectContaining({
        provenance: expect.objectContaining({
          compatibilityResolution: expect.objectContaining({
            reportFingerprint: fingerprint('e'),
          }),
        }),
      }),
    }));
  });

  it('presents an authoritative Operator Contract through the shared Scenario workspace', async () => {
    const draft = graphDraft();
    const contractFingerprint = fingerprint('b');
    const graphContract = contractDraftFromGraphDraft(draft, fingerprint('a'));
    const contract = {
      ...graphContract,
      target: {
        kind: 'OPERATOR' as const,
        id: 'risk:score',
        revision: 0,
        fingerprint: fingerprint('a'),
      },
    };
    const operatorDraft = {
      ...draft,
      draftId: undefined,
      revision: undefined,
      graphName: 'operator-risk-score',
      nodes: [{ id: 'operator', operatorRef: 'risk:score', label: 'Risk score' }],
      edges: [],
      output: { nodeId: 'operator' },
      nodeFixtures: {},
    };
    const draftSet = scenarioDraftSetFromCanvas(
      contract.target,
      contractFingerprint,
      operatorDraft,
      [],
      [],
    );
    const onRun = vi.fn().mockResolvedValue(successfulResponse());
    function ControlledOperatorWorkspace() {
      const [controlledDraftSet, setControlledDraftSet] = useState(draftSet);
      return (
        <ContractScenarioWorkspace
          open
          graphDraft={operatorDraft}
          contract={contract}
          contractFingerprint={contractFingerprint}
          scenarioDraftSet={controlledDraftSet}
          nodes={[]}
          lastRun={null}
          targetStored
          contractEditable={false}
          workspaceTransferEnabled={false}
          onScenarioDraftSetChange={setControlledDraftSet}
          onContractChange={vi.fn()}
          onImportWorkspace={vi.fn().mockResolvedValue(undefined)}
          onSaveGraphDraft={vi.fn().mockResolvedValue(undefined)}
          onRebase={vi.fn()}
          onRun={onRun}
          onClose={vi.fn()}
        />
      );
    }
    await act(async () => {
      root = createRoot(host);
      root.render(<ControlledOperatorWorkspace />);
    });

    expect(text()).toContain('Operator Contract');
    expect(text()).toContain('projected from the catalog');
    expect(text()).not.toContain('Save Graph');
    expect(text()).not.toContain('Export Workspace');
    expect(button('Load Scenario').disabled).toBe(false);

    await act(async () => {
      button('Load Scenario').click();
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(text()).not.toContain('Request failed: 404');
    expect(text()).toContain('No saved Scenario revision yet');

    await clickTab('Scenarios 1');
    expect(text()).toContain('Happy path');
    await act(async () => {
      button('Run & Compare').click();
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(onRun).toHaveBeenCalledWith(expect.objectContaining({
      draft: expect.objectContaining({
        nodes: [expect.objectContaining({ operatorRef: 'risk:score' })],
      }),
    }));

    await clickTab('Scenarios 1');
    await act(async () => button('+ Dependency').click());
    expect(input('Operator reference for dependency-1').value).toBe('');
    const removeDependency = document.querySelector(
      '[aria-label="Remove dependency dependency-1"]',
    );
    expect(removeDependency).toBeInstanceOf(HTMLButtonElement);
    await act(async () => (removeDependency as HTMLButtonElement).click());
    expect(document.querySelector('[aria-label="Operator reference for dependency-1"]')).toBeNull();
  });

  it('automatically resumes the latest stored Scenario revision for a stored target', async () => {
    const draft = graphDraft();
    const targetFingerprint = fingerprint('a');
    const contractFingerprint = fingerprint('b');
    const contract = contractDraftFromGraphDraft(draft, targetFingerprint);
    const localDraftSet = scenarioDraftSetFromCanvas(
      contract.target,
      contractFingerprint,
      draft,
      nodes(),
      [],
    );
    const storedDraftSet = {
      ...localDraftSet,
      revision: 4,
      scenarios: [{ ...localDraftSet.scenarios[0], name: 'Stored happy path' }],
    };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(
      JSON.stringify({
        schemaVersion: 'bloge.storedScenarioDraftSet.v1',
        scenarioDraftSetId: storedDraftSet.scenarioDraftSetId,
        revision: 4,
        fingerprint: fingerprint('c'),
        draftSet: storedDraftSet,
        savedAt: '2026-07-27T00:00:00Z',
        savedBy: 'author-a',
      }),
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    )));
    const onChange = vi.fn();

    await act(async () => {
      root = createRoot(host);
      root.render(
        <ContractScenarioWorkspace
          open
          graphDraft={draft}
          contract={contract}
          contractFingerprint={contractFingerprint}
          scenarioDraftSet={localDraftSet}
          nodes={nodes()}
          lastRun={null}
          targetStored
          onScenarioDraftSetChange={onChange}
          onContractChange={vi.fn()}
          onImportWorkspace={vi.fn().mockResolvedValue(undefined)}
          onSaveGraphDraft={vi.fn().mockResolvedValue(undefined)}
          onRebase={vi.fn()}
          onRun={vi.fn().mockResolvedValue(successfulResponse())}
          onClose={vi.fn()}
        />,
      );
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({
      revision: 4,
      scenarios: [expect.objectContaining({ name: 'Stored happy path' })],
    }));
    expect(text()).toContain('Loaded Scenario revision 4.');
  });

  it('rejects a stored Scenario asset for a different target coordinate', async () => {
    const draft = graphDraft();
    const contract = contractDraftFromGraphDraft(draft, fingerprint('a'));
    const localDraftSet = scenarioDraftSetFromCanvas(
      contract.target,
      fingerprint('b'),
      draft,
      nodes(),
      [],
    );
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(
      JSON.stringify({
        schemaVersion: 'bloge.storedScenarioDraftSet.v1',
        scenarioDraftSetId: localDraftSet.scenarioDraftSetId,
        revision: 2,
        fingerprint: fingerprint('c'),
        draftSet: {
          ...localDraftSet,
          revision: 2,
          target: { ...localDraftSet.target, id: 'another-draft' },
        },
        savedAt: '2026-07-27T00:00:00Z',
        savedBy: 'author-a',
      }),
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    )));
    const onChange = vi.fn();

    await act(async () => {
      root = createRoot(host);
      root.render(
        <ContractScenarioWorkspace
          open
          graphDraft={draft}
          contract={contract}
          contractFingerprint={fingerprint('b')}
          scenarioDraftSet={localDraftSet}
          nodes={nodes()}
          lastRun={null}
          targetStored
          onScenarioDraftSetChange={onChange}
          onContractChange={vi.fn()}
          onImportWorkspace={vi.fn().mockResolvedValue(undefined)}
          onSaveGraphDraft={vi.fn().mockResolvedValue(undefined)}
          onRebase={vi.fn()}
          onRun={vi.fn().mockResolvedValue(successfulResponse())}
          onClose={vi.fn()}
        />,
      );
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(onChange).not.toHaveBeenCalled();
    expect(text()).toContain('Stored Scenario target does not match');
  });

  async function renderWorkspace(options: {
    stale?: boolean;
    unsaved?: boolean;
    scenarioRevision?: number;
    initialTab?: 'interface' | 'scenarios' | 'compatibility' | 'evidence';
    lastRun?: SimulationResponse | null;
    lastRunScenarioId?: string;
    lastComparison?: ScenarioComparison | null;
    onChange?: ReturnType<typeof vi.fn>;
    onRebase?: ReturnType<typeof vi.fn>;
    onRun?: ReturnType<typeof vi.fn>;
    trustContext?: ScenarioEvidenceTrustContext;
    onSelectEvidenceDiagnostic?: ReturnType<typeof vi.fn>;
    onCoordinateChange?: ReturnType<typeof vi.fn>;
    onRunEvidence?: ReturnType<typeof vi.fn>;
  } = {}) {
    const draft = options.unsaved
      ? { ...graphDraft(), draftId: '', revision: 0 }
      : graphDraft();
    const targetFingerprint = fingerprint('a');
    const contractFingerprint = fingerprint('b');
    const contract = contractDraftFromGraphDraft(draft, targetFingerprint);
    const projectedDraftSet = scenarioDraftSetFromCanvas(
      options.stale ? { ...contract.target, fingerprint: fingerprint('c') } : contract.target,
      contractFingerprint,
      draft,
      nodes(),
      [{
        id: 'approved',
        name: 'Approved applicant',
        context: { applicantId: '', profile: { age: 18, tags: [] } },
        fixtures: {
          score: { output: { score: 720 } },
          decide: { output: successfulResponse().output },
        },
        hasExpectedOutput: true,
        expectedOutput: successfulResponse().output,
      }],
    );
    const draftSet = {
      ...projectedDraftSet,
      revision: options.scenarioRevision ?? projectedDraftSet.revision,
    };
    await act(async () => {
      root = createRoot(host);
      root.render(
        <ContractScenarioWorkspace
          open
          graphDraft={draft}
          contract={contract}
          contractFingerprint={contractFingerprint}
          scenarioDraftSet={draftSet}
          nodes={nodes()}
          lastRun={options.lastRun ?? null}
          lastRunScenarioId={options.lastRunScenarioId}
          lastComparison={options.lastComparison}
          initialTab={options.initialTab}
          onScenarioDraftSetChange={options.onChange ?? vi.fn()}
          onContractChange={vi.fn()}
          onImportWorkspace={vi.fn().mockResolvedValue(undefined)}
          onSaveGraphDraft={vi.fn().mockResolvedValue(undefined)}
          onRebase={options.onRebase ?? vi.fn()}
          onRun={options.onRun ?? vi.fn().mockResolvedValue(successfulResponse())}
          onClose={vi.fn()}
          trustContext={options.trustContext}
          onSelectEvidenceDiagnostic={options.onSelectEvidenceDiagnostic}
          onCoordinateChange={options.onCoordinateChange}
          onRunEvidence={options.onRunEvidence}
        />,
      );
    });
  }

  async function renderControlledWorkspace() {
    const draft = graphDraft();
    const targetFingerprint = fingerprint('a');
    const contractFingerprint = fingerprint('b');
    const contract = contractDraftFromGraphDraft(draft, targetFingerprint);
    const initialDraftSet = scenarioDraftSetFromCanvas(
      contract.target,
      contractFingerprint,
      draft,
      nodes(),
      [{
        id: 'approved',
        name: 'Approved applicant',
        context: { applicantId: '', profile: { age: 18, tags: [] } },
        fixtures: {},
        hasExpectedOutput: true,
        expectedOutput: successfulResponse().output,
      }],
    );

    function ControlledWorkspace() {
      const [draftSet, setDraftSet] = useState(initialDraftSet);
      return (
        <ContractScenarioWorkspace
          open
          graphDraft={draft}
          contract={contract}
          contractFingerprint={contractFingerprint}
          scenarioDraftSet={draftSet}
          nodes={nodes()}
          lastRun={null}
          onScenarioDraftSetChange={setDraftSet}
          onContractChange={vi.fn()}
          onImportWorkspace={vi.fn().mockResolvedValue(undefined)}
          onSaveGraphDraft={vi.fn().mockResolvedValue(undefined)}
          onRebase={vi.fn()}
          onRun={vi.fn().mockResolvedValue(successfulResponse())}
          onClose={vi.fn()}
        />
      );
    }

    await act(async () => {
      root = createRoot(host);
      root.render(<ControlledWorkspace />);
    });
  }
});

function tabs(): string[] {
  return Array.from(document.querySelectorAll('[role="tab"]'))
    .map((entry) => entry.textContent?.trim() ?? '');
}

async function clickTab(label: string) {
  await act(async () => button(label).click());
}

function button(label: string): HTMLButtonElement {
  const candidate = Array.from(document.querySelectorAll('button'))
    .find((entry) => entry.textContent?.trim() === label);
  if (!(candidate instanceof HTMLButtonElement)) {
    throw new Error(`Missing button: ${label}`);
  }
  return candidate;
}

function input(label: string): HTMLInputElement {
  const candidate = document.querySelector(`[aria-label="${label}"]`);
  if (!(candidate instanceof HTMLInputElement)) {
    throw new Error(`Missing input: ${label}`);
  }
  return candidate;
}

function textarea(label: string): HTMLTextAreaElement {
  const candidate = document.querySelector(`textarea[aria-label="${label}"]`);
  if (!(candidate instanceof HTMLTextAreaElement)) {
    throw new Error(`Missing textarea: ${label}`);
  }
  return candidate;
}

function text(): string {
  return document.body.textContent ?? '';
}

function fingerprint(seed: string): string {
  return `sha256:${seed.repeat(64).slice(0, 64)}`;
}
