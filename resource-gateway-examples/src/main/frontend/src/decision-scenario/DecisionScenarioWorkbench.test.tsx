// @vitest-environment jsdom
import { act, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fetchScenarioGraphContract, fetchScenarioOperatorContract, saveScenarioDraftSet } from '../api';
import type { ExactTargetRef, EnterpriseScope } from '../contract-scenario/domain';
import { DecisionScenarioWorkbench } from './DecisionScenarioWorkbench';

vi.mock('../api', () => ({ fetchScenarioGraphContract: vi.fn(), fetchScenarioOperatorContract: vi.fn(), saveScenarioDraftSet: vi.fn() }));

const target: ExactTargetRef = { kind: 'OPERATOR', id: 'bloge:decisionTable', revision: 1, fingerprint: 'sha256:operator' };
const scope: EnterpriseScope = { tenantId: 'tenant-a', organizationId: 'org-a', projectId: 'project-a', environment: 'dev', region: 'sg' };
const editor = {
  hitPolicy: 'unique', outputType: '{ decision: String }', conditionColumns: [{ id: 'score' }], outputColumns: [{ id: 'decision' }],
  rows: [
    { conditions: { score: 'score >= 720' }, outputs: { decision: 'approve' }, otherwise: false },
    { conditions: { score: 'score < 720' }, outputs: { decision: 'review' }, otherwise: false },
    { conditions: { score: '' }, outputs: { decision: 'decline' }, otherwise: true },
  ],
};

async function waitForElement(host: ParentNode, selector: string): Promise<HTMLElement> {
  const startedAt = Date.now();
  let element = host.querySelector<HTMLElement>(selector);
  while (!element) {
    if (Date.now() - startedAt >= 2000) {
      throw new Error(`Timed out after 2000ms waiting for "${selector}"`);
    }
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 10)); });
    element = host.querySelector<HTMLElement>(selector);
  }
  return element;
}

describe('DecisionScenarioWorkbench', () => {
  let root: Root | undefined;
  afterEach(() => { root?.unmount(); root = undefined; document.body.innerHTML = ''; vi.resetAllMocks(); });

  it('generates and persists through the real ScenarioDraftSet endpoint with exact target scope', async () => {
    const onPersistedChange = vi.fn();
    const onOutputKindChange = vi.fn();
    const onOpenScenarios = vi.fn();
    vi.mocked(fetchScenarioOperatorContract).mockResolvedValue({ scope, contract: { target, inputSchema: {}, outputSchema: {} }, contractFingerprint: 'sha256:contract' } as never);
    vi.mocked(saveScenarioDraftSet).mockResolvedValue({ draftSet: { metadata: {}, scenarios: [] } } as never);
    const host = document.createElement('div'); document.body.appendChild(host); root = createRoot(host);
    await act(async () => { root?.render(<DecisionScenarioWorkbench editor={editor} tableId="decision-node" target={target} scope={scope} owner="owner" operatorRef="bloge:decisionTable" persisted={null} onPersistedChange={onPersistedChange} onOutputKindChange={onOutputKindChange} onOpenScenarios={onOpenScenarios} />); });
    await act(async () => { (host.querySelector('[aria-label="Decision output kind"]') as HTMLSelectElement).value = 'plan'; (host.querySelector('[aria-label="Decision output kind"]') as HTMLSelectElement).dispatchEvent(new Event('change', { bubbles: true })); });
    expect(onOutputKindChange).toHaveBeenCalledWith('plan');
    await act(async () => { (host.querySelector('[data-testid="generate-decision-scenarios"]') as HTMLButtonElement).click(); });
    const preview = await waitForElement(host, '[data-testid="decision-scenario-preview"]');
    expect(preview).not.toBeNull();
    await act(async () => { (preview.querySelector('button') as HTMLButtonElement).click(); });
    expect(fetchScenarioOperatorContract).toHaveBeenCalledWith(target.id);
    expect(saveScenarioDraftSet).toHaveBeenCalledOnce();
    expect(vi.mocked(saveScenarioDraftSet).mock.calls[0]?.[0]).toMatchObject({ target, scope, metadata: { owner: 'owner', provenance: { operatorRef: 'bloge:decisionTable', sourceNodeId: 'decision-node' } } });
    expect(vi.mocked(saveScenarioDraftSet).mock.calls[0]?.[0].scenarioDraftSetId)
      .toMatch(/^[A-Za-z0-9][A-Za-z0-9._-]*$/);
    expect(vi.mocked(saveScenarioDraftSet).mock.calls[0]?.[0].scenarioDraftSetId.length)
      .toBeLessThanOrEqual(255);
    expect(vi.mocked(saveScenarioDraftSet).mock.calls[0]?.[0].scenarios[0]?.given.input)
      .toMatchObject({ inputs: { score: expect.any(Number) } });
    expect(vi.mocked(saveScenarioDraftSet).mock.calls[0]?.[0].scenarios[0]?.given.input)
      .not.toHaveProperty('score');
    expect(onPersistedChange).toHaveBeenCalledOnce();
    await act(async () => { (host.querySelector('[data-testid="open-generated-scenarios"]') as HTMLButtonElement).click(); });
    expect(onOpenScenarios).toHaveBeenCalledOnce();
  });

  it('keeps a failed save retryable instead of reporting local-only success', async () => {
    vi.mocked(saveScenarioDraftSet).mockRejectedValue(new Error('network unavailable'));
    vi.mocked(fetchScenarioOperatorContract).mockResolvedValue({ scope, contract: { target, inputSchema: {}, outputSchema: {} }, contractFingerprint: 'sha256:contract' } as never);
    const host = document.createElement('div'); document.body.appendChild(host); root = createRoot(host);
    await act(async () => { root?.render(<DecisionScenarioWorkbench editor={editor} tableId="decision-node" target={target} scope={scope} owner="owner" persisted={null} onPersistedChange={vi.fn()} />); });
    await act(async () => { (host.querySelector('[data-testid="generate-decision-scenarios"]') as HTMLButtonElement).click(); });
    await waitForElement(host, '[data-testid="decision-scenario-preview"]');
    await act(async () => { (host.querySelector('[data-testid="decision-scenario-preview"] button') as HTMLButtonElement).click(); });
    const alert = await waitForElement(host, '[role="alert"]');
    expect(alert.textContent).toContain('network unavailable');
    expect(host.querySelector('[role="alert"] button')?.textContent).toBe('Retry');
  });

  it('generates a decision table for the exact saved Graph target and opens the Graph Scenario workspace', async () => {
    const graphTarget: ExactTargetRef = { kind: 'GRAPH', id: 'graph-draft-42', revision: 7, fingerprint: 'sha256:graph-target' };
    const onPersistedChange = vi.fn();
    const onOpenGraphScenarios = vi.fn();
    vi.mocked(fetchScenarioGraphContract).mockResolvedValue({
      scope,
      contract: { target: graphTarget, inputSchema: {}, outputSchema: {} },
      contractFingerprint: 'sha256:graph-contract',
    } as never);
    vi.mocked(saveScenarioDraftSet).mockImplementation(async (draftSet) => ({ draftSet } as never));
    const host = document.createElement('div'); document.body.appendChild(host); root = createRoot(host);
    await act(async () => {
      root?.render(<DecisionScenarioWorkbench
        editor={editor}
        tableId="decision-node"
        target={target}
        scope={scope}
        owner="owner"
        persisted={null}
        onPersistedChange={onPersistedChange}
        onOpenGraphScenarios={onOpenGraphScenarios}
        graphDraftId="graph-draft-42"
      />);
    });
    await act(async () => { (host.querySelector('[data-testid="generate-graph-decision-scenarios"]') as HTMLButtonElement).click(); });
    expect(fetchScenarioGraphContract).toHaveBeenCalledWith('graph-draft-42');
    expect(fetchScenarioOperatorContract).not.toHaveBeenCalled();
    const preview = await waitForElement(host, '[data-testid="decision-scenario-preview"]');
    await act(async () => { (preview.querySelector('button') as HTMLButtonElement).click(); });
    const saved = vi.mocked(saveScenarioDraftSet).mock.calls[0]?.[0];
    expect(saved).toMatchObject({
      target: graphTarget,
      contractFingerprint: 'sha256:graph-contract',
      metadata: { provenance: { targetKind: 'GRAPH', graphDraftId: 'graph-draft-42', sourceNodeId: 'decision-node' } },
    });
    expect(saved?.scenarioDraftSetId).toMatch(/^graph-graph-draft-42-/);
    expect(onPersistedChange).toHaveBeenCalledWith(saved);
    await act(async () => { (host.querySelector('[data-testid="open-generated-scenarios"]') as HTMLButtonElement).click(); });
    expect(onOpenGraphScenarios).toHaveBeenCalledOnce();
  });

  it('re-enumerates a stale persisted set and clears stale state after the authoritative save', async () => {
    vi.mocked(fetchScenarioOperatorContract).mockResolvedValue({ scope, contract: { target, inputSchema: {}, outputSchema: {} }, contractFingerprint: 'sha256:contract' } as never);
    vi.mocked(saveScenarioDraftSet).mockImplementation(async (draftSet) => ({ draftSet } as never));
    function Harness() {
      const [persisted, setPersisted] = useState<any>({ metadata: { provenance: { sourceFingerprint: 'sha256:old' } } });
      return <DecisionScenarioWorkbench editor={editor} tableId="decision-node" target={target} scope={scope} owner="owner" persisted={persisted} onPersistedChange={setPersisted} />;
    }
    const host = document.createElement('div'); document.body.appendChild(host); root = createRoot(host);
    await act(async () => { root?.render(<Harness />); });
    expect(host.querySelector('[data-testid="decision-scenario-stale"]')).not.toBeNull();
    await act(async () => { (host.querySelector('[data-testid="decision-scenario-stale"] button') as HTMLButtonElement).click(); });
    const preview = await waitForElement(host, '[data-testid="decision-scenario-preview"]');
    await act(async () => { (preview.querySelector('button') as HTMLButtonElement).click(); });
    expect(saveScenarioDraftSet).toHaveBeenCalledOnce();
    expect(host.querySelector('[data-testid="decision-scenario-stale"]')).toBeNull();
  });

  it('does not expose save when the authoritative projection cannot be loaded', async () => {
    vi.mocked(fetchScenarioOperatorContract).mockRejectedValue(new Error('contract unavailable'));
    const host = document.createElement('div'); document.body.appendChild(host); root = createRoot(host);
    await act(async () => { root?.render(<DecisionScenarioWorkbench editor={editor} tableId="decision-node" target={target} scope={scope} owner="owner" persisted={null} onPersistedChange={vi.fn()} />); });
    await act(async () => { (host.querySelector('[data-testid="generate-decision-scenarios"]') as HTMLButtonElement).click(); });
    const alert = await waitForElement(host, '[role="alert"]');
    expect(alert.textContent).toContain('contract unavailable');
    expect(host.querySelector('[data-testid="decision-scenario-preview"]')).toBeNull();
    expect(saveScenarioDraftSet).not.toHaveBeenCalled();
  });
});
