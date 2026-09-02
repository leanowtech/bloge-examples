// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import AuthoringWorkbench from './AuthoringWorkbench';
import * as authoringApi from './api';
import * as flowApi from './flowApi';
import type { ApiResourceSpec } from './model';

vi.mock('./flowApi', async () => ({
  ...(await vi.importActual<typeof import('./flowApi')>('./flowApi')),
  readFlow: vi.fn(), readLatestFlowVersion: vi.fn(), readFlowFixture: vi.fn(), listFlowFixtures: vi.fn(),
  listComposableCatalog: vi.fn(),
  readLegacyReusableFlowPreview: vi.fn(), readLegacyFixtureReauthorPreview: vi.fn(),
  saveFlow: vi.fn(), saveFlowFixture: vi.fn(), simulateFlowFixture: vi.fn(), publishFlow: vi.fn(),
}));
vi.mock('./api', async () => ({
  ...(await vi.importActual<typeof import('./api')>('./api')), readAuthoringAvailability: vi.fn(),
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
    vi.mocked(authoringApi.readAuthoringAvailability).mockResolvedValue({
      schemaVersion: 'bloge.authoringAvailability.v1', apiResource: true, reusableFlow: true,
    });
    vi.mocked(flowApi.readLatestFlowVersion).mockResolvedValue(null);
    vi.mocked(flowApi.listFlowFixtures).mockResolvedValue([]);
    vi.mocked(flowApi.listComposableCatalog).mockResolvedValue([]);
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    host.remove();
    vi.clearAllMocks();
  });

  it('composes exact API Resources, saves the Flow, then saves and simulates its whole-flow Fixture', async () => {
    const profile = resource('profile', { customerId: 'string' }, { customerId: 'string', name: 'string' });
    const orders = resource('orders', { customerId: 'string' }, { orders: 'object' });
    vi.mocked(flowApi.listComposableCatalog).mockResolvedValue([catalogItem(profile), catalogItem(orders)]);
    const draftRef = { kind: 'FLOW_DRAFT' as const, draftId: 'draft-1', revision: 1, fingerprint: hash('c') };
    vi.mocked(flowApi.saveFlow).mockResolvedValue({
      strongEtag: '"flow-r1"', replayed: false,
      value: { schemaVersion: 'bloge.reusableFlowSaveReceipt.v1', flowId: 'overview', draft: draftRef, validation: 'VALID' },
    });
    const versionRef = {
      kind: 'FLOW_VERSION' as const, publicationId: 'published-overview', revision: 1, fingerprint: hash('e'),
    };
    vi.mocked(flowApi.publishFlow).mockResolvedValue({
      schemaVersion: 'bloge.reusableFlowPublishReceipt.v1', source: draftRef,
      version: versionRef, catalog: 'AVAILABLE',
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
      nodes: [{ nodeId: 'subject', status: 'COMPLETED', execution: 'MOCKED', fixtureSource: 'INLINE',
        egress: { decision: 'FIXTURE', attempted: false } }],
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

    await act(async () => tab('Versions').click());
    await act(async () => button('publish-flow').click());
    expect(element('flow-fixture-panel')).toBeTruthy();

    await act(async () => {
      change('flow-fixture-input', '{"customerId":"c-1"}');
      change('flow-fixture-output', '{"orders":[]}');
    });
    await act(async () => button('save-flow-fixture').click());

    expect(flowApi.saveFlowFixture).toHaveBeenCalledWith(
      'overview.default', expect.objectContaining({
        subject: versionRef,
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

  it('visibly reviews and saves an exact fixture-free legacy Flow projection', async () => {
    window.history.replaceState(null, '', '/workbench/?create=flow&kind=TOOL'
      + '&legacyFlowKind=REUSABLE_FLOW_DRAFT&legacyFlowId=legacy-draft&legacyFlowRevision=3');
    const profile = resource('profile', { customerId: 'string' }, { customerId: 'string', name: 'string' });
    const orders = resource('orders', { customerId: 'string' }, { orders: 'object' });
    const suggestedFlow = {
      schemaVersion: 'bloge.reusableFlowSaveCommand.v1' as const,
      flow: {
        displayName: 'Customer orders', kind: 'TOOL' as const, description: '',
        contract: { input: profile.contract.input, output: orders.contract.output },
        graph: {
          nodes: [{
            nodeId: 'lookup', label: 'Profile',
            use: { kind: 'API_RESOURCE' as const, resourceId: 'profile', revision: 3, fingerprint: profile.fingerprint },
            inputs: [{ to: '$.customerId', from: { kind: 'FLOW_INPUT' as const, path: '$.customerId' } }],
          }, {
            nodeId: 'orders', label: 'Orders',
            use: { kind: 'API_RESOURCE' as const, resourceId: 'orders', revision: 3, fingerprint: orders.fingerprint },
            inputs: [{ to: '$.customerId', from: {
              kind: 'NODE_OUTPUT' as const, nodeId: 'lookup', path: '$.customerId',
            } }],
          }],
          output: { nodeId: 'orders', path: '$' },
        },
        layout: { nodes: { lookup: { x: 120, y: 160 }, orders: { x: 400, y: 160 } } },
      },
    };
    vi.mocked(flowApi.readLegacyReusableFlowPreview).mockResolvedValue({
      schemaVersion: 'bloge.legacyReusableFlowReauthorPreview.v1',
      source: { kind: 'REUSABLE_FLOW_DRAFT', sourceId: 'legacy-draft', sourceRevision: 3 },
      suggestedFlowId: 'customer-orders', suggestedFlow, fixtureReferences: 2,
      diagnostics: [{ code: 'FIXTURE_REAUTHOR_REQUIRED', message: 'Rebuild Fixtures after saving.' }],
    });
    vi.mocked(flowApi.listComposableCatalog).mockResolvedValue([catalogItem(profile), catalogItem(orders)]);
    vi.mocked(flowApi.saveFlow).mockResolvedValue({
      strongEtag: '"flow-r1"', replayed: false,
      value: { schemaVersion: 'bloge.reusableFlowSaveReceipt.v1', flowId: 'customer-orders',
        draft: { kind: 'FLOW_DRAFT', draftId: 'draft-new', revision: 1, fingerprint: hash('c') },
        validation: 'VALID' },
    });

    await act(async () => {
      root.render(<AuthoringWorkbench />);
      await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
    });

    expect(flowApi.readLegacyReusableFlowPreview).toHaveBeenCalledWith(
      'REUSABLE_FLOW_DRAFT', 'legacy-draft', 3,
    );
    expect(element('legacy-flow-reauthor-preview').textContent)
      .toContain('Nothing is migrated automatically');
    expect(element('legacy-flow-reauthor-preview').textContent).toContain('2 Fixture references');
    expect(element<HTMLInputElement>('flow-id').value).toBe('customer-orders');
    expect(element('flow-node-list').textContent).toContain('Profile');
    expect(element('flow-node-list').textContent).toContain('Orders');

    await act(async () => button('save-flow').click());

    expect(flowApi.saveFlow).toHaveBeenCalledWith(
      'customer-orders', suggestedFlow, null, expect.stringMatching(/^save-flow:customer-orders:/),
    );
    expect(element('flow-fixture-panel')).toBeTruthy();
  });

  it('selects an immutable Flow Version and visibly applies its exact Fixture Case', async () => {
    const child = {
      schemaVersion: 'bloge.composableCatalogItem.v1' as const, displayName: 'Customer child tool',
      reference: { kind: 'FLOW_VERSION' as const, publicationId: 'published-child',
        revision: 4, fingerprint: hash('v') },
      contract: { input: envelope({ customerId: 'string' }), output: envelope({ orderCount: 'integer' }) },
    };
    const childFixture = {
      schemaVersion: 'bloge.fixtureSetSummary.v1' as const, fixtureSetId: 'child.default', revision: 2,
      fingerprint: hash('f'), displayName: 'Child default', subject: child.reference,
      cases: [{ caseId: 'default', name: 'Default' }], status: 'PRIVATE_DRAFT' as const, statusRevision: 1,
    };
    const parentDraft = { kind: 'FLOW_DRAFT' as const, draftId: 'parent-draft',
      revision: 1, fingerprint: hash('d') };
    const parentVersion = { kind: 'FLOW_VERSION' as const, publicationId: 'published-parent',
      revision: 1, fingerprint: hash('p') };
    vi.mocked(flowApi.listComposableCatalog).mockResolvedValue([child]);
    vi.mocked(flowApi.listFlowFixtures).mockImplementation(async (subject) =>
      subject.kind === 'FLOW_VERSION' && subject.publicationId === 'published-child' ? [childFixture] : []);
    vi.mocked(flowApi.saveFlow).mockResolvedValue({ strongEtag: '"parent-r1"', replayed: false, value: {
      schemaVersion: 'bloge.reusableFlowSaveReceipt.v1', flowId: 'parent', draft: parentDraft, validation: 'VALID',
    } });
    vi.mocked(flowApi.publishFlow).mockResolvedValue({
      schemaVersion: 'bloge.reusableFlowPublishReceipt.v1', source: parentDraft,
      version: parentVersion, catalog: 'AVAILABLE',
    });
    vi.mocked(flowApi.saveFlowFixture).mockResolvedValue({ strongEtag: '"parent-fixture-r1"', replayed: false,
      value: { schemaVersion: 'bloge.fixtureSetSaveReceipt.v1', fixtureSetId: 'parent.parent-default',
        revision: 1, fingerprint: hash('q'), subject: parentVersion, caseIds: ['default'],
        status: 'PRIVATE_DRAFT', statusRevision: 1 } });
    vi.mocked(flowApi.simulateFlowFixture).mockResolvedValue({
      schemaVersion: 'bloge.simulationRun.v1', runId: 'parent-run', status: 'SUCCEEDED', output: { orderCount: 2 },
      nodes: [{ nodeId: 'step1', status: 'COMPLETED', execution: 'MOCKED', fixtureSource: 'APPLY_CASE',
        egress: { decision: 'NOT_APPLICABLE', attempted: false } }],
      verdicts: { execution: 'PASSED_WITH_MOCKS', contract: 'PASSED', assertions: 'PASSED', governance: 'NOT_CHECKED' },
      diagnostics: [],
    });

    await act(async () => root.render(<AuthoringWorkbench />));
    await act(async () => { change('flow-name', 'Parent'); change('flow-id', 'parent'); });
    await add('Customer child tool');
    expect(element('flow-node-list').textContent).toContain('published-child@4');
    await act(async () => button('save-flow').click());
    await act(async () => tab('Versions').click());
    await act(async () => button('publish-flow').click());
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    const nodeMode = host.querySelectorAll<HTMLInputElement>('input[name="flow-fixture-mode"]')[1];
    await act(async () => nodeMode.click());
    await act(async () => {
      change('flow-fixture-input', '{"customerId":"c-1"}');
      change('flow-fixture-output', '{"orderCount":2}');
    });
    await act(async () => button('save-flow-fixture').click());

    expect(flowApi.saveFlowFixture).toHaveBeenCalledWith('parent.parent-default', expect.objectContaining({
      subject: parentVersion, cases: [expect.objectContaining({ controls: [{
        target: { kind: 'NODE', nodeId: 'step1' },
        behavior: { kind: 'APPLY_CASE', fixtureSetId: 'child.default', revision: 2, caseId: 'default' },
      }] })],
    }), null, expect.stringMatching(/^save-flow-fixture:parent.parent-default:/));
    expect(element('flow-simulation-panel').textContent).toContain('PASSED_WITH_MOCKS');
  });

  it('opens one payload-free legacy Fixture review against the exact reauthored Flow draft', async () => {
    window.history.replaceState(null, '', '/workbench/?flowId=customer-orders&tab=fixture'
      + '&legacyFixtureDraftId=legacy-draft&legacyFixtureRevision=3');
    const profile = resource('profile', { customerId: 'string' }, { name: 'string' });
    const target = { kind: 'FLOW_DRAFT' as const, draftId: 'draft-new', revision: 1, fingerprint: hash('c') };
    vi.mocked(flowApi.readFlow).mockResolvedValue({
      strongEtag: '"flow-r1"', replayed: false,
      value: {
        schemaVersion: 'bloge.reusableFlowDraft.v1', flowId: 'customer-orders',
        draftId: target.draftId, revision: target.revision, fingerprint: target.fingerprint,
        displayName: 'Customer orders', kind: 'TOOL', description: '', status: 'DRAFT',
        contract: profile.contract,
        graph: { nodes: [{
          nodeId: 'profile', label: 'Profile',
          use: { kind: 'API_RESOURCE', resourceId: 'profile', revision: 3, fingerprint: profile.fingerprint },
          inputs: [],
        }], output: { nodeId: 'profile', path: '$' } },
        layout: { nodes: { profile: { x: 120, y: 160 } } },
      },
    });
    vi.mocked(flowApi.listComposableCatalog).mockResolvedValue([catalogItem(profile)]);
    vi.mocked(flowApi.readLegacyFixtureReauthorPreview).mockResolvedValue({
      schemaVersion: 'bloge.legacyFixtureReauthorPreview.v1',
      source: { draftId: 'legacy-draft', revision: 3 }, targetFlowId: 'customer-orders',
      suggestedFixtureSetId: 'customer-orders.default', target,
      references: [{
        nodeId: 'profile', materialKind: 'GOVERNED', fidelity: 'TRANSPORT_LEVEL',
        expectedInputPresent: true,
      }],
      diagnostics: [{
        code: 'GOVERNED_MATERIAL_NOT_COPIED', message: 'Protected material was not copied.',
      }],
    });
    vi.mocked(flowApi.saveFlowFixture).mockResolvedValue({
      strongEtag: '"fixture-r1"', replayed: false,
      value: {
        schemaVersion: 'bloge.fixtureSetSaveReceipt.v1', fixtureSetId: 'customer-orders.default',
        revision: 1, fingerprint: hash('d'), subject: target, caseIds: ['default'],
        status: 'PRIVATE_DRAFT', statusRevision: 1,
      },
    });
    const publishedSubject = {
      kind: 'FLOW_VERSION' as const, publicationId: 'published-customer-orders',
      revision: 1, fingerprint: hash('e'),
    };
    vi.mocked(flowApi.publishFlow).mockResolvedValue({
      schemaVersion: 'bloge.reusableFlowPublishReceipt.v1', source: target,
      version: publishedSubject, catalog: 'AVAILABLE',
    });
    vi.mocked(flowApi.simulateFlowFixture).mockResolvedValue({
      schemaVersion: 'bloge.simulationRun.v1', runId: 'run-legacy-fixture', status: 'SUCCEEDED',
      output: { name: 'new-value' },
      nodes: [{ nodeId: 'subject', status: 'COMPLETED', execution: 'MOCKED', fixtureSource: 'INLINE',
        egress: { decision: 'FIXTURE', attempted: false } }],
      verdicts: { execution: 'SIMULATED_ONLY', contract: 'PASSED', assertions: 'PASSED', governance: 'NOT_CHECKED' },
      diagnostics: [],
    });

    await act(async () => {
      root.render(<AuthoringWorkbench />);
      await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
    });

    expect(flowApi.readLegacyFixtureReauthorPreview).toHaveBeenCalledWith('legacy-draft', 3);
    expect(element('legacy-fixture-reauthor-preview').textContent)
      .toContain('Legacy Fixture material is not copied');
    expect(element('legacy-fixture-reauthor-preview').textContent)
      .toContain('profile · GOVERNED');
    expect(element('legacy-fixture-reauthor-preview').textContent)
      .toContain('TRANSPORT_LEVEL · expected input present');
    expect(element<HTMLTextAreaElement>('flow-fixture-input').value).toBe('{}');
    expect(element<HTMLTextAreaElement>('flow-fixture-output').value).toBe('{}');
    expect(host.textContent).not.toContain('fixtureAssetId');

    await act(async () => {
      change('flow-fixture-input', '{"customerId":"new-input"}');
      change('flow-fixture-output', '{"name":"new-value"}');
      button('save-flow-fixture').click();
    });

    expect(flowApi.saveFlowFixture).toHaveBeenCalledWith(
      'customer-orders.default', expect.objectContaining({ subject: target }), null,
      expect.stringMatching(/^save-flow-fixture:customer-orders.default:/),
    );
    expect(element('flow-simulation-output').textContent).toContain('new-value');

    await act(async () => tab('Versions').click());
    await act(async () => button('publish-flow').click());
    await act(async () => button('save-flow-fixture').click());

    expect(flowApi.saveFlowFixture).toHaveBeenCalledTimes(2);
    expect(flowApi.saveFlowFixture).toHaveBeenLastCalledWith(
      'customer-orders.default', expect.objectContaining({ subject: publishedSubject }), '"fixture-r1"',
      expect.stringMatching(/^save-flow-fixture:customer-orders.default:/),
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
    vi.mocked(flowApi.listComposableCatalog).mockResolvedValue([catalogItem(profile)]);
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

    expect(flowApi.listComposableCatalog).toHaveBeenCalled();
    expect(flowApi.publishFlow).toHaveBeenCalledWith(
      'overview', { kind: 'FLOW_DRAFT', draftId: 'draft-1', revision: 2, fingerprint: hash('c') },
      expect.stringMatching(/^publish-flow:overview:/),
    );
    expect(element('flow-fixture-panel')).toBeTruthy();
    await act(async () => tab('Versions').click());
    expect(element('published-flow-version').textContent).toBe('published-overview@1');
  });

  it('reloads the exact published version before authoring a Fixture', async () => {
    window.history.replaceState(null, '', '/workbench/?flowId=overview');
    const profile = resource('profile', { customerId: 'string' }, { name: 'string' });
    vi.mocked(flowApi.readFlow).mockResolvedValue({
      strongEtag: '"flow-r2"', replayed: false,
      value: {
        schemaVersion: 'bloge.reusableFlowDraft.v1', flowId: 'overview', draftId: 'draft-1', revision: 2,
        fingerprint: hash('c'), displayName: 'Overview', kind: 'TOOL', description: '', status: 'DRAFT',
        contract: profile.contract,
        graph: { nodes: [{
          nodeId: 'step1', label: 'Profile',
          use: { kind: 'API_RESOURCE', resourceId: 'profile', revision: 3, fingerprint: profile.fingerprint },
          inputs: [],
        }], output: { nodeId: 'step1', path: '$' } },
        layout: { nodes: { step1: { x: 120, y: 160 } } },
      },
    });
    vi.mocked(flowApi.listComposableCatalog).mockResolvedValue([catalogItem(profile)]);
    vi.mocked(flowApi.readLatestFlowVersion).mockResolvedValue({
      schemaVersion: 'bloge.reusableFlowVersion.v1', publicationId: 'published-overview', revision: 3,
      fingerprint: hash('e'), source: { draftId: 'draft-1', revision: 2, fingerprint: hash('c') },
      flowId: 'overview',
    });

    await act(async () => {
      root.render(<AuthoringWorkbench />);
      await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
    });

    expect(flowApi.listFlowFixtures).toHaveBeenCalledWith({
      kind: 'FLOW_VERSION', publicationId: 'published-overview', revision: 3, fingerprint: hash('e'),
    });
    await act(async () => tab('Versions').click());
    expect(element('published-flow-version').textContent).toBe('published-overview@3');
  });

  async function add(id: string) {
    const select = element<HTMLSelectElement>('flow-catalog-selection');
    const option = [...select.options].find((candidate) => candidate.textContent?.includes(id));
    if (!option) throw new Error(`Missing catalog item ${id}`);
    await act(async () => changeSelect('flow-catalog-selection', option.value));
    await act(async () => button('add-flow-catalog-item').click());
  }

  function change(testId: string, value: string) {
    const input = element<HTMLInputElement | HTMLTextAreaElement>(testId);
    const prototype = input instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(prototype, 'value')?.set?.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  }
  function changeSelect(testId: string, value: string) {
    const input = element<HTMLSelectElement>(testId);
    Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')?.set?.call(input, value);
    input.dispatchEvent(new Event('change', { bubbles: true }));
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

function catalogItem(value: ApiResourceSpec) {
  return {
    schemaVersion: 'bloge.composableCatalogItem.v1' as const, displayName: value.displayName,
    reference: { kind: 'API_RESOURCE' as const, resourceId: value.resourceId,
      revision: value.revision, fingerprint: value.fingerprint }, contract: value.contract,
  };
}
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
