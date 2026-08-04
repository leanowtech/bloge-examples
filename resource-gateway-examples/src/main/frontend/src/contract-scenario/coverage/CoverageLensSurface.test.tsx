// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { GraphDraft } from '../../types';
import type { ContractDraft, ScenarioDraftSet } from '../domain';
import CoverageLensSurface from './CoverageLensSurface';

describe('CoverageLensSurface', () => {
  let host: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    host.remove();
  });

  it('shows six independent denominator inventories and does not generate on open', async () => {
    await render();

    for (const label of ['Case intent', 'Contract', 'DAG path', 'Dependency', 'Assertion', 'Evidence']) {
      expect(button(label)).toBeInstanceOf(HTMLButtonElement);
    }
    expect(text()).toContain('Coverage gaps');
    expect(text()).toContain('No generated candidates');
    expect(host.querySelectorAll('.coverage-candidate-row')).toHaveLength(0);
    expect(text()).not.toMatch(/overall|score|percentage/i);
  });

  it('generates bounded review candidates with provenance and an honest oracle state', async () => {
    await render();
    await click(button('Generate candidates'));
    await waitFor(() => host.querySelectorAll('.coverage-candidate-row').length > 0);

    expect(text()).toContain('Needs oracle');
    expect(text()).toContain('bloge.schema-boundary v1.0.0');
    expect(text()).toContain('work units');
    expect(input('Generation seed').value).toBe('42');
    expect(button('Accept')).toBeInstanceOf(HTMLButtonElement);
  });

  it('targets one named gap and rejects a candidate without mutating canonical state', async () => {
    const onAcceptCandidate = vi.fn();
    await render(draftSet(), onAcceptCandidate);
    await click(firstButton('Target gap'));
    expect(text()).toContain('Target:');
    await click(button('Generate candidates'));
    await waitFor(() => host.querySelectorAll('.coverage-candidate-row').length === 1);

    await click(button('Reject'));
    expect(onAcceptCandidate).not.toHaveBeenCalled();
    expect(host.querySelectorAll('.coverage-candidate-row')).toHaveLength(0);
    expect(text()).toContain('1 candidates rejected');
  });

  it('emits the exact candidate and source projection only after explicit acceptance', async () => {
    const onAcceptCandidate = vi.fn();
    await render(draftSet(), onAcceptCandidate);
    await click(button('Generate candidates'));
    await waitFor(() => host.querySelectorAll('.coverage-candidate-row').length > 0);
    await click(button('Accept'));

    expect(onAcceptCandidate).toHaveBeenCalledTimes(1);
    const [candidate, projection] = onAcceptCandidate.mock.calls[0];
    expect(candidate.source.coverageProjectionFingerprint).toBe(projection.projectionFingerprint);
    expect(candidate.proposal.then.assertions).toEqual([]);
    expect(candidate.promotionEligible).toBe(false);
    expect(button('Accepted').disabled).toBe(true);
  });

  it('disables an existing candidate set when Scenario source material changes', async () => {
    const onAcceptCandidate = vi.fn();
    const initial = draftSet();
    await render(initial, onAcceptCandidate);
    await click(button('Generate candidates'));
    await waitFor(() => host.querySelectorAll('.coverage-candidate-row').length > 0);

    const changed: ScenarioDraftSet = {
      ...initial,
      scenarios: [
        ...initial.scenarios,
        {
          ...initial.scenarios[0],
          scenarioId: 'second-case',
          name: 'Second case',
          caseType: 'BOUNDARY',
        },
      ],
    };
    await render(changed, onAcceptCandidate);
    await waitFor(() => text().includes('Source changed. Regenerate'));

    expect(button('Accept').disabled).toBe(true);
  });

  async function render(
    scenarios = draftSet(),
    onAcceptCandidate = vi.fn(),
  ) {
    await act(async () => root.render(
      <CoverageLensSurface
        graphDraft={graph()}
        contract={contract()}
        draftSet={scenarios}
        evidenceByCase={{}}
        onAcceptCandidate={onAcceptCandidate}
      />,
    ));
    await waitFor(() => text().includes('Coverage gaps'));
  }

  function text() {
    return host.textContent ?? '';
  }

  function button(name: string) {
    const candidate = Array.from(host.querySelectorAll<HTMLButtonElement>('button'))
      .find((element) => element.textContent?.trim() === name || element.ariaLabel === name);
    if (!candidate) throw new Error(`Missing button: ${name}`);
    return candidate;
  }

  function firstButton(name: string) {
    return button(name);
  }

  function input(name: string) {
    const candidate = host.querySelector<HTMLInputElement>(`input[aria-label="${name}"]`);
    if (!candidate) throw new Error(`Missing input: ${name}`);
    return candidate;
  }
});

async function click(element: HTMLElement) {
  await act(async () => element.dispatchEvent(new MouseEvent('click', { bubbles: true })));
}

async function waitFor(predicate: () => boolean, timeoutMs = 2_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) return;
    await act(async () => new Promise((resolve) => setTimeout(resolve, 10)));
  }
  expect(predicate()).toBe(true);
}

function graph(): GraphDraft {
  return {
    draftId: 'coverage-graph',
    revision: 2,
    graphName: 'coverageGraph',
    nodes: [
      { id: 'lookup', operatorRef: 'demo:lookup' },
      { id: 'decision', operatorRef: 'demo:decision' },
    ],
    edges: [{
      id: 'lookup-decision',
      kind: 'data',
      source: { nodeId: 'lookup' },
      target: { nodeId: 'decision' },
      condition: '$.found == true',
    }],
    nodeFixtures: { lookup: { output: { found: true } } },
    output: { nodeId: 'decision' },
  };
}

function contract(): ContractDraft {
  return {
    schemaVersion: 'bloge.contractDraft.v1',
    target: {
      kind: 'GRAPH',
      id: 'coverageGraph',
      revision: 2,
      fingerprint: fingerprint('a'),
    },
    inputSchema: {
      format: 'json-schema',
      version: '2020-12',
      schema: {
        type: 'object',
        required: ['customerId', 'age', 'tier'],
        properties: {
          customerId: { type: 'string', minLength: 2 },
          age: { type: 'integer', minimum: 18, maximum: 90 },
          tier: { type: 'string', enum: ['STANDARD', 'PREMIUM'] },
        },
      },
    },
    outputSchema: {
      format: 'json-schema',
      version: '2020-12',
      schema: {
        type: 'object',
        properties: { approved: { type: 'boolean' } },
      },
    },
    errorContract: [{
      code: 'RG.DEMO.INVALID',
      type: 'InvalidInput',
      description: 'Input is invalid.',
      retryable: false,
    }],
    executionSemantics: {
      effect: 'READ',
      idempotency: 'IDEMPOTENT',
      streaming: false,
      durable: true,
    },
    invariants: [],
    compatibilityPolicy: { mode: 'STRICT', unknownBlocksAutomaticMigration: true },
    fieldMetadata: {},
    source: 'AUTHORED',
    confidence: 'EXACT',
  };
}

function draftSet(): ScenarioDraftSet {
  return {
    schemaVersion: 'bloge.scenarioDraftSet.v1',
    scenarioDraftSetId: 'coverage-scenarios',
    revision: 5,
    scope: {
      tenantId: 'demo',
      organizationId: 'quality',
      projectId: 'coverage',
      environment: 'test',
      region: 'local',
    },
    target: {
      kind: 'GRAPH',
      id: 'coverageGraph',
      revision: 2,
      fingerprint: fingerprint('a'),
    },
    contractFingerprint: fingerprint('b'),
    scenarios: [{
      scenarioId: 'golden-case',
      name: 'Known customer',
      description: '',
      caseType: 'GOLDEN',
      tags: [],
      given: {
        input: { customerId: 'C-1', age: 42, tier: 'STANDARD' },
        provenance: 'AUTHORED',
      },
      dependencies: [{
        dependencyId: 'lookup-return',
        selector: {
          graphPath: '/root',
          nodeId: 'lookup',
          operatorRef: 'demo:lookup',
          resourceRef: '',
          functionRef: '',
          attempts: [],
          occurrences: [],
          correlationKey: '',
          pathEquals: {},
        },
        behavior: { kind: 'RETURN', boundary: 'NODE', output: { found: true } },
        consumption: {
          required: true,
          minUses: 1,
          maxUses: 1,
          onExhausted: 'FAIL',
          onUnmatched: 'FAIL',
        },
        schemaCheck: { mode: 'STRICT', waiverReason: '' },
        origin: 'AUTHORED',
      }],
      then: { assertions: [] },
    }],
    metadata: {
      owner: 'quality-team',
      classification: 'INTERNAL',
      createdAt: null,
      updatedAt: null,
      provenance: {},
    },
  };
}

function fingerprint(seed: string) {
  return `sha256:${seed.repeat(64)}`;
}
