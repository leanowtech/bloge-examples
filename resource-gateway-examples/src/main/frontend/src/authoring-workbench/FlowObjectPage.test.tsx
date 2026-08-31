// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import AuthoringWorkbench from './AuthoringWorkbench';
import * as api from './api';
import * as flowApi from './flowApi';
import type { ApiResourceSpec } from './model';

vi.mock('./api', async () => ({
  ...(await vi.importActual<typeof import('./api')>('./api')),
  readApiResource: vi.fn(),
}));
vi.mock('./flowApi', async () => ({
  ...(await vi.importActual<typeof import('./flowApi')>('./flowApi')),
  readFlow: vi.fn(), readFlowFixture: vi.fn(), listFlowDraftFixtures: vi.fn(),
  saveFlow: vi.fn(), saveFlowFixture: vi.fn(), simulateFlowFixture: vi.fn(), publishFlow: vi.fn(),
}));

describe('Tool and Solution object page', () => {
  let root: Root;
  let host: HTMLDivElement;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    window.history.replaceState(null, '', '/workbench/?create=flow&kind=TOOL');
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    host.remove();
    vi.clearAllMocks();
  });

  it('composes exact API Resources, saves the Flow, then saves and simulates its whole-flow Fixture', async () => {
    vi.mocked(api.readApiResource)
      .mockResolvedValueOnce(stored(resource('profile', { customerId: 'string' }, { customerId: 'string', name: 'string' })))
      .mockResolvedValueOnce(stored(resource('orders', { customerId: 'string' }, { orders: 'object' })));
    const draftRef = { kind: 'FLOW_DRAFT' as const, draftId: 'draft-1', revision: 1, fingerprint: hash('c') };
    vi.mocked(flowApi.saveFlow).mockResolvedValue({
      strongEtag: '"flow-r1"', replayed: false,
      value: { schemaVersion: 'bloge.reusableFlowSaveReceipt.v1', flowId: 'overview', draft: draftRef, validation: 'VALID' },
    });
    vi.mocked(flowApi.saveFlowFixture).mockResolvedValue({
      strongEtag: '"fixture-r1"', replayed: false,
      value: {
        schemaVersion: 'bloge.fixtureSetSaveReceipt.v1', fixtureSetId: 'overview.default', revision: 1,
        fingerprint: hash('d'), subject: draftRef, caseIds: ['default'], status: 'PRIVATE_DRAFT', statusRevision: 1,
      },
    });
    vi.mocked(flowApi.simulateFlowFixture).mockResolvedValue({
      schemaVersion: 'bloge.simulationRun.v1', runId: 'run-flow-1', status: 'SUCCEEDED', output: { orders: [] },
      nodes: [{ nodeId: 'subject', status: 'COMPLETED', execution: 'MOCKED', fixtureSource: 'INLINE' }],
      verdicts: { execution: 'SIMULATED_ONLY', contract: 'PASSED', assertions: 'PASSED', governance: 'NOT_CHECKED' },
      diagnostics: [],
    });

    await act(async () => root.render(<AuthoringWorkbench />));
    await act(async () => { change('flow-name', 'Customer overview'); change('flow-id', 'overview'); });
    await add('profile');
    await add('orders');
    await act(async () => button('save-flow').click());

    expect(flowApi.saveFlow).toHaveBeenCalledWith('overview', expect.objectContaining({
      flow: expect.objectContaining({
        kind: 'TOOL', graph: expect.objectContaining({
          nodes: [
            expect.objectContaining({ nodeId: 'step1' }),
            expect.objectContaining({
              nodeId: 'step2', inputs: [{
                to: '$.customerId', from: { kind: 'NODE_OUTPUT', nodeId: 'step1', path: '$.customerId' },
              }],
            }),
          ],
        }),
      }),
    }), null, expect.stringMatching(/^save-flow:overview:/));
    expect(element('flow-fixture-panel')).toBeTruthy();

    await act(async () => {
      change('flow-fixture-input', '{"customerId":"c-1"}');
      change('flow-fixture-output', '{"orders":[]}');
    });
    await act(async () => button('save-flow-fixture').click());

    expect(flowApi.saveFlowFixture).toHaveBeenCalledWith(
      'overview.default', expect.objectContaining({
        subject: draftRef,
        cases: [expect.objectContaining({ controls: [expect.objectContaining({ target: { kind: 'SUBJECT' } })] })],
      }), null, expect.stringMatching(/^save-flow-fixture:overview.default:/),
    );
    expect(flowApi.simulateFlowFixture).toHaveBeenCalledWith(
      'overview.default', 1, 'default', expect.stringMatching(/^simulate-flow:overview.default-1:/),
    );
    expect(element('flow-simulation-panel').textContent).toContain('SIMULATED_ONLY');
    await act(async () => tab('Fixture').click());
    expect(element<HTMLAnchorElement>('open-flow-fixture').getAttribute('href')).toBe(
      '/workbench/?fixtureSetId=overview.default',
    );
  });

  it('reloads an exact Flow draft and publishes the same authority', async () => {
    window.history.replaceState(null, '', '/workbench/?flowId=overview');
    const profile = resource('profile', { customerId: 'string' }, { name: 'string' });
    vi.mocked(flowApi.readFlow).mockResolvedValue({
      strongEtag: '"flow-r2"', replayed: false,
      value: {
        schemaVersion: 'bloge.reusableFlowDraft.v1', flowId: 'overview', draftId: 'draft-1', revision: 2,
        fingerprint: hash('c'), displayName: 'Overview', kind: 'SOLUTION', description: '', status: 'DRAFT',
        contract: profile.contract,
        graph: {
          nodes: [{
            nodeId: 'step1', label: 'Profile',
            use: { kind: 'API_RESOURCE', resourceId: 'profile', revision: 3, fingerprint: profile.fingerprint },
            inputs: [{ to: '$.customerId', from: { kind: 'FLOW_INPUT', path: '$.customerId' } }],
          }],
          output: { nodeId: 'step1', path: '$' },
        },
        layout: { nodes: { step1: { x: 120, y: 160 } } },
      },
    });
    vi.mocked(api.readApiResource).mockResolvedValue(stored(profile));
    vi.mocked(flowApi.listFlowDraftFixtures).mockResolvedValue([]);
    vi.mocked(flowApi.publishFlow).mockResolvedValue({
      schemaVersion: 'bloge.reusableFlowPublishReceipt.v1',
      source: { kind: 'FLOW_DRAFT', draftId: 'draft-1', revision: 2, fingerprint: hash('c') },
      version: { kind: 'FLOW_VERSION', publicationId: 'published-overview', revision: 1, fingerprint: hash('e') },
      catalog: 'AVAILABLE',
    });

    await act(async () => {
      root.render(<AuthoringWorkbench />);
      await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
    });
    await act(async () => tab('Versions').click());
    await act(async () => button('publish-flow').click());

    expect(api.readApiResource).toHaveBeenCalledWith('profile', 3);
    expect(flowApi.publishFlow).toHaveBeenCalledWith(
      'overview', { kind: 'FLOW_DRAFT', draftId: 'draft-1', revision: 2, fingerprint: hash('c') },
      expect.stringMatching(/^publish-flow:overview:/),
    );
    expect(element('published-flow-version').textContent).toBe('published-overview@1');
  });

  async function add(id: string) {
    await act(async () => change('flow-resource-id', id));
    await act(async () => button('add-flow-resource').click());
  }

  function change(testId: string, value: string) {
    const input = element<HTMLInputElement | HTMLTextAreaElement>(testId);
    const prototype = input instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(prototype, 'value')?.set?.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  }

  function tab(name: string): HTMLButtonElement {
    const value = [...host.querySelectorAll<HTMLButtonElement>('.object-tabs button')]
      .find((candidate) => candidate.textContent === name);
    if (!value) throw new Error(`Missing ${name} tab`);
    return value;
  }
  function button(testId: string): HTMLButtonElement { return element(testId); }
  function element<T extends Element = HTMLElement>(testId: string): T {
    const value = host.querySelector<T>(`[data-testid="${testId}"]`);
    if (!value) throw new Error(`Missing ${testId}`);
    return value;
  }
});

function stored(value: ApiResourceSpec) { return { value, strongEtag: '"resource-r3"', replayed: false }; }
function resource(resourceId: string, input: Record<string, string>, output: Record<string, string>): ApiResourceSpec {
  return {
    schemaVersion: 'bloge.apiResourceSpec.v1', resourceId, revision: 3, fingerprint: hash('a'),
    displayName: resourceId, connectionId: 'connection', operation: { method: 'GET', path: '/', bindings: [] },
    contract: { input: envelope(input), output: envelope(output) },
    response: { success: { kind: 'HTTP_STATUS', codes: [200] } }, effect: { kind: 'READ_ONLY' },
    examples: [{ name: 'default', input: {}, output: {} }], status: 'DRAFT',
  };
}
function envelope(properties: Record<string, string>) {
  return {
    format: 'json-schema' as const, version: '2020-12' as const,
    schema: {
      type: 'object' as const,
      properties: Object.fromEntries(Object.entries(properties).map(([name, type]) => [name, { type }])),
      required: Object.keys(properties), additionalProperties: false as const,
    },
  };
}
function hash(value: string): string { return `sha256:${value.repeat(64)}`; }
