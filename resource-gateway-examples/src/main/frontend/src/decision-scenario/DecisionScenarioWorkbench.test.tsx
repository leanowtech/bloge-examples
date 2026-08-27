// @vitest-environment jsdom
import { act, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fetchScenarioOperatorContract, saveScenarioDraftSet } from '../api';
import type { ExactTargetRef, EnterpriseScope } from '../contract-scenario/domain';
import { DecisionScenarioWorkbench } from './DecisionScenarioWorkbench';

vi.mock('../api', () => ({ fetchScenarioOperatorContract: vi.fn(), saveScenarioDraftSet: vi.fn() }));

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

describe('DecisionScenarioWorkbench', () => {
  let root: Root | undefined;
  afterEach(() => { root?.unmount(); root = undefined; document.body.innerHTML = ''; vi.clearAllMocks(); });

  it('generates and persists through the real ScenarioDraftSet endpoint with exact target scope', async () => {
    const onPersistedChange = vi.fn();
    const onOutputKindChange = vi.fn();
    vi.mocked(fetchScenarioOperatorContract).mockResolvedValue({ scope, contract: { target, inputSchema: {}, outputSchema: {} }, contractFingerprint: 'sha256:contract' } as never);
    vi.mocked(saveScenarioDraftSet).mockResolvedValue({ draftSet: { metadata: {}, scenarios: [] } } as never);
    const host = document.createElement('div'); document.body.appendChild(host); root = createRoot(host);
    await act(async () => { root?.render(<DecisionScenarioWorkbench editor={editor} tableId="decision-node" target={target} scope={scope} owner="owner" persisted={null} onPersistedChange={onPersistedChange} onOutputKindChange={onOutputKindChange} />); });
    await act(async () => { (host.querySelector('[aria-label="Decision output kind"]') as HTMLSelectElement).value = 'plan'; (host.querySelector('[aria-label="Decision output kind"]') as HTMLSelectElement).dispatchEvent(new Event('change', { bubbles: true })); });
    expect(onOutputKindChange).toHaveBeenCalledWith('plan');
    await act(async () => { (host.querySelector('[data-testid="generate-decision-scenarios"]') as HTMLButtonElement).click(); });
    expect(host.querySelector('[data-testid="decision-scenario-preview"]')).not.toBeNull();
    await act(async () => { (host.querySelector('[data-testid="decision-scenario-preview"] button') as HTMLButtonElement).click(); });
    expect(fetchScenarioOperatorContract).toHaveBeenCalledWith(target.id);
    expect(saveScenarioDraftSet).toHaveBeenCalledOnce();
    expect(vi.mocked(saveScenarioDraftSet).mock.calls[0]?.[0]).toMatchObject({ target, scope, metadata: { owner: 'owner' } });
    expect(onPersistedChange).toHaveBeenCalledOnce();
  });

  it('keeps a failed save retryable instead of reporting local-only success', async () => {
    vi.mocked(saveScenarioDraftSet).mockRejectedValue(new Error('network unavailable'));
    vi.mocked(fetchScenarioOperatorContract).mockResolvedValue({ scope, contract: { target, inputSchema: {}, outputSchema: {} }, contractFingerprint: 'sha256:contract' } as never);
    const host = document.createElement('div'); document.body.appendChild(host); root = createRoot(host);
    await act(async () => { root?.render(<DecisionScenarioWorkbench editor={editor} tableId="decision-node" target={target} scope={scope} owner="owner" persisted={null} onPersistedChange={vi.fn()} />); });
    await act(async () => { (host.querySelector('[data-testid="generate-decision-scenarios"]') as HTMLButtonElement).click(); });
    await act(async () => { (host.querySelector('[data-testid="decision-scenario-preview"] button') as HTMLButtonElement).click(); });
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('network unavailable');
    expect(host.querySelector('[role="alert"] button')?.textContent).toBe('Retry');
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
    await act(async () => { (host.querySelector('[data-testid="decision-scenario-preview"] button') as HTMLButtonElement).click(); });
    expect(saveScenarioDraftSet).toHaveBeenCalledOnce();
    expect(host.querySelector('[data-testid="decision-scenario-stale"]')).toBeNull();
  });

  it('does not expose save when the authoritative projection cannot be loaded', async () => {
    vi.mocked(fetchScenarioOperatorContract).mockRejectedValue(new Error('contract unavailable'));
    const host = document.createElement('div'); document.body.appendChild(host); root = createRoot(host);
    await act(async () => { root?.render(<DecisionScenarioWorkbench editor={editor} tableId="decision-node" target={target} scope={scope} owner="owner" persisted={null} onPersistedChange={vi.fn()} />); });
    await act(async () => { (host.querySelector('[data-testid="generate-decision-scenarios"]') as HTMLButtonElement).click(); });
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('contract unavailable');
    expect(host.querySelector('[data-testid="decision-scenario-preview"]')).toBeNull();
    expect(saveScenarioDraftSet).not.toHaveBeenCalled();
  });
});
