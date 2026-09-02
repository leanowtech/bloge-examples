// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import CallerDirectedSimulationPanel from './CallerDirectedSimulationPanel';
import * as callerApi from './callerSimulationApi';
import * as flowApi from './flowApi';

vi.mock('./callerSimulationApi', () => ({ runCallerDirectedSimulation: vi.fn() }));
vi.mock('./flowApi', async () => ({
  ...(await vi.importActual<typeof import('./flowApi')>('./flowApi')), readFixtureSet: vi.fn(),
}));

describe('caller-directed simulation panel', () => {
  let root: Root;
  let host: HTMLDivElement;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div'); document.body.appendChild(host); root = createRoot(host);
    vi.mocked(flowApi.readFixtureSet).mockResolvedValue({
      strongEtag: '"fixture-r1"', replayed: false, value: {
        schemaVersion: 'bloge.fixtureSet.v1', fixtureSetId: 'customer-cases', revision: 1,
        fingerprint: hash('b'), statusRevision: 1, displayName: 'Customer cases', subject: subject(),
        status: 'PRIVATE_DRAFT', cases: [{
          caseId: 'vip', name: 'VIP', input: {}, when: {
            conditionId: 'vip-customer', all: [{ operator: 'EQ', path: '$.tier', value: 'vip' }],
          }, controls: [],
        }],
      },
    });
    vi.mocked(callerApi.runCallerDirectedSimulation).mockResolvedValue(run());
  });

  afterEach(async () => {
    await act(async () => root.unmount()); host.remove(); vi.clearAllMocks();
  });

  it('edits input and Fixture Plan independently, previews a condition, and proves invocation evidence', async () => {
    await act(async () => root.render(<CallerDirectedSimulationPanel subject={subject()}
      initialInput={'{"tier":"vip"}'} targets={[{
        key: 'subject', label: 'Customer API', target: { kind: 'SUBJECT' }, fixtures: [fixture()],
      }]} />));
    expect(element('caller-input-section')).not.toBe(element('caller-plan-section'));

    await act(async () => change('caller-plan-kind', 'BINDINGS'));
    await act(async () => change('caller-fixture:subject', 'customer-cases'));
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    await act(async () => change('caller-selection:subject', 'MATCH_CONDITION'));
    await act(async () => change('caller-condition:subject', 'vip-customer'));

    expect(element('caller-match-preview:subject').textContent).toContain('vip-customer=match');
    await act(async () => button('run-caller-simulation').click());

    expect(callerApi.runCallerDirectedSimulation).toHaveBeenCalledWith(expect.objectContaining({
      schemaVersion: 'bloge.simulationCommand.v2', input: { kind: 'INLINE', value: { tier: 'vip' } },
      fixturePlan: { kind: 'BINDINGS', unmatched: 'BLOCK', bindings: [{
        target: { kind: 'SUBJECT' }, selection: {
          kind: 'MATCH_CONDITION', fixtureSet: {
            fixtureSetId: 'customer-cases', revision: 1, fingerprint: hash('b'),
          }, conditionId: 'vip-customer',
        },
      }] },
      executionPolicy: { externalReads: { kind: 'DENY' }, externalWrites: { kind: 'DENY' } },
    }), expect.stringMatching(/^caller-simulation:API_RESOURCE:/));
    expect(element('resolved-simulation-evidence').textContent).toContain('NOT_READY');
    expect(element('caller-invocation:inv-1').textContent).toContain('MOCKED · CONDITION');
    expect(element('caller-invocation:inv-1').textContent).toContain('NO_EGRESS');
  });

  it('warns when the caller asks unmatched targets to run real', async () => {
    await act(async () => root.render(<CallerDirectedSimulationPanel subject={subject()}
      targets={[{ key: 'subject', label: 'Customer API', target: { kind: 'SUBJECT' }, fixtures: [] }]} />));
    await act(async () => change('caller-plan-kind', 'BINDINGS'));
    await act(async () => change('caller-unmatched', 'REAL'));

    expect(host.querySelector('[role="alert"]')?.textContent).toContain('requires an exact read grant');
  });

  function element<T extends HTMLElement = HTMLElement>(testId: string): T {
    const value = host.querySelector<T>(`[data-testid="${testId}"]`);
    if (!value) throw new Error(`Missing ${testId}`); return value;
  }
  function button(testId: string): HTMLButtonElement { return element<HTMLButtonElement>(testId); }
  function change(testId: string, value: string): void {
    const input = element<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>(testId);
    const setter = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(input), 'value')?.set;
    setter?.call(input, value); input.dispatchEvent(new Event('change', { bubbles: true }));
  }
});

function subject() {
  return { kind: 'API_RESOURCE' as const, resourceId: 'customer', revision: 1, fingerprint: hash('a') };
}
function fixture() {
  return { schemaVersion: 'bloge.fixtureSetSummary.v1' as const, fixtureSetId: 'customer-cases', revision: 1,
    fingerprint: hash('b'), displayName: 'Customer cases', subject: subject(),
    cases: [{ caseId: 'vip', name: 'VIP' }], status: 'PRIVATE_DRAFT', statusRevision: 1 };
}
function run() {
  return {
    schemaVersion: 'bloge.simulationRun.v2' as const, runId: 'run-1', status: 'SUCCEEDED' as const,
    subject: subject(), requestFingerprint: hash('c'), resolvedFixturePlanFingerprint: hash('d'),
    output: { customer: 'Ada' }, invocations: [{
      invocationKey: 'inv-1', target: { kind: 'SUBJECT' as const }, subject: subject(),
      status: 'COMPLETED' as const, execution: 'MOCKED' as const, matchedBy: 'CONDITION' as const,
      fixtureCase: { fixtureSetId: 'customer-cases', revision: 1, fingerprint: hash('b'), caseId: 'vip' },
      behavior: 'RETURN' as const, fidelity: 'OUTPUT_LEVEL' as const, provenance: 'PINNED_PRIVATE' as const,
      inputFingerprint: hash('e'), outputFingerprint: hash('f'),
      egress: { decision: 'FIXTURE', attempted: false },
    }], verdicts: {
      execution: 'PASSED' as const, assertions: 'NOT_CHECKED' as const, contract: 'VALID' as const,
      governance: 'NOT_CHECKED' as const, aggregate: 'NOT_READY' as const,
    }, diagnostics: [],
  };
}
function hash(value: string): string { return `sha256:${value.repeat(64)}`; }
