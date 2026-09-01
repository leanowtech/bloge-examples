// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import AuthoringWorkbench from './AuthoringWorkbench';
import * as authoringApi from './api';

vi.mock('./api', async () => {
  const actual = await vi.importActual<typeof import('./api')>('./api');
  return {
    ...actual, readApiResource: vi.fn(), listApiResourceFixtures: vi.fn(),
    listApiConnections: vi.fn(), previewOpenApi: vi.fn(),
    saveApiResource: vi.fn(), simulateFixtureCase: vi.fn(),
  };
});

describe('simple authoring workbench', () => {
  let root: Root;
  let host: HTMLDivElement;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    window.history.replaceState(null, '', '/workbench/');
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
    vi.mocked(authoringApi.listApiConnections).mockResolvedValue([{
      schemaVersion: 'bloge.apiConnectionView.v1', connectionId: 'crm', revision: 1,
      displayName: 'CRM', baseUrl: 'https://crm.example.test', auth: { kind: 'NONE', configured: false },
    }]);
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    host.remove();
    vi.clearAllMocks();
  });

  it('offers only the three approved creation intents', async () => {
    await act(async () => root.render(<AuthoringWorkbench />));

    expect(host.querySelectorAll('.simple-authoring-intents a')).toHaveLength(3);
    expect(link('create-api-resource').getAttribute('href')).toBe('/workbench/?create=api');
    expect(link('create-tool').getAttribute('href')).toBe('/workbench/?create=flow&kind=TOOL');
    expect(link('create-solution').getAttribute('href')).toBe('/workbench/?create=flow&kind=SOLUTION');
  });

  it('previews and visibly applies one OpenAPI operation before save', async () => {
    window.history.replaceState(null, '', '/workbench/?create=api');
    vi.mocked(authoringApi.previewOpenApi).mockResolvedValue({
      schemaVersion: 'bloge.openApiPreview.v1', discoveryId: 'preview-1',
      operations: [{
        operationId: 'getCustomer', method: 'GET', path: '/customers/{customerId}', diagnostics: [],
        suggestedResource: {
          displayName: 'Get customer',
          operation: {
            method: 'GET', path: '/customers/{customerId}',
            bindings: [{ from: '$.customerId', to: { location: 'PATH', name: 'customerId' } }],
          },
          contract: {
            input: { format: 'json-schema', version: '2020-12', schema: {
              type: 'object', properties: { customerId: { type: 'string' } },
              required: ['customerId'], additionalProperties: false,
            } },
            output: { format: 'json-schema', version: '2020-12', schema: {
              type: 'object', properties: { name: { type: 'string' } },
              required: ['name'], additionalProperties: false,
            } },
          },
          response: { success: { kind: 'HTTP_STATUS', codes: [200] } },
          effect: { kind: 'READ_ONLY' },
          examples: [{ name: 'openapi-example', input: { customerId: 'string' }, output: { name: 'string' } }],
        },
      }],
    });

    await act(async () => root.render(<AuthoringWorkbench />));
    await act(async () => {
      change('api-connection-id', 'crm');
      change('openapi-document', 'openapi: 3.0.3');
    });
    await act(async () => button('preview-openapi').click());
    await act(async () => button('use-openapi-operation-getCustomer').click());

    expect(authoringApi.previewOpenApi).toHaveBeenCalledWith('openapi: 3.0.3');
    expect(element<HTMLInputElement>('api-name').value).toBe('Get customer');
    expect(element<HTMLInputElement>('api-resource-id').value).toBe('getCustomer');
    expect(element<HTMLSelectElement>('api-connection-id').value).toBe('crm');
    expect(element<HTMLInputElement>('api-path').value).toBe('/customers/{customerId}');
    expect(element('openapi-binding-summary').textContent).toContain('PATH:customerId');
  });

  it('saves one compound Resource command and immediately runs its exact Default Fixture Case', async () => {
    window.history.replaceState(null, '', '/workbench/?create=api');
    vi.mocked(authoringApi.saveApiResource).mockResolvedValue({
      strongEtag: '"resource-r1"', replayed: false,
      value: {
        schemaVersion: 'bloge.apiResourceSaveReceipt.v1',
        resource: { kind: 'API_RESOURCE', resourceId: 'profile', revision: 1, fingerprint: `sha256:${'a'.repeat(64)}` },
        connection: { connectionId: 'crm', revision: 1 },
        defaultFixture: {
          fixtureSetId: 'profile:r1', revision: 1, fingerprint: `sha256:${'b'.repeat(64)}`,
          cases: [{ exampleName: 'default', caseId: 'default' }],
        },
        projections: { descriptor: 'READY', designContract: 'READY', operator: 'READY' },
      },
    });
    vi.mocked(authoringApi.simulateFixtureCase).mockResolvedValue({
      schemaVersion: 'bloge.simulationRun.v1', runId: 'run-1', status: 'SUCCEEDED', output: { name: 'Ada' },
      nodes: [{ nodeId: 'subject', status: 'COMPLETED', execution: 'MOCKED', fixtureSource: 'INLINE',
        egress: { decision: 'FIXTURE', attempted: false } }],
      verdicts: { execution: 'SIMULATED_ONLY', contract: 'PASSED', assertions: 'PASSED', governance: 'NOT_CHECKED' },
      diagnostics: [],
    });
    await act(async () => root.render(<AuthoringWorkbench />));
    await act(async () => {
      change('api-name', 'Customer profile');
      change('api-resource-id', 'profile');
      change('api-connection-id', 'crm');
      change('api-path', '/profile');
    });

    await act(async () => button('save-and-simulate').click());

    expect(authoringApi.saveApiResource).toHaveBeenCalledWith(
      'profile', expect.objectContaining({
        schemaVersion: 'bloge.apiResourceSaveCommand.v1',
        defaultFixture: { kind: 'FROM_EXAMPLES', displayName: 'Customer profile default', exampleNames: ['default'] },
      }), null, expect.stringMatching(/^save:profile:/),
    );
    expect(authoringApi.simulateFixtureCase).toHaveBeenCalledWith(
      'profile:r1', 1, 'default', expect.stringMatching(/^simulate:profile:r1-1-default:/),
    );
    expect(element('resource-simulation-panel').textContent).toContain('SIMULATED_ONLY');
    expect(element('simulation-output').textContent).toContain('Ada');
  });

  it('reloads a committed Resource, discovers its exact Fixture, and can rerun it', async () => {
    window.history.replaceState(null, '', '/workbench/?resourceId=profile');
    vi.mocked(authoringApi.readApiResource).mockResolvedValue({
      strongEtag: '"resource-r2"', replayed: false,
      value: {
        schemaVersion: 'bloge.apiResourceSpec.v1', resourceId: 'profile', revision: 2,
        fingerprint: `sha256:${'a'.repeat(64)}`, displayName: 'Profile', connectionId: 'crm',
        operation: { method: 'GET', path: '/profile', bindings: [] },
        contract: {
          input: { format: 'json-schema', version: '2020-12', schema: { type: 'object', properties: {}, required: [], additionalProperties: false } },
          output: { format: 'json-schema', version: '2020-12', schema: { type: 'object', properties: { name: { type: 'string' } }, required: ['name'], additionalProperties: false } },
        },
        response: { success: { kind: 'HTTP_STATUS', codes: [200] } }, effect: { kind: 'READ_ONLY' },
        examples: [{ name: 'default', input: {}, output: { name: 'Ada' } }], status: 'DRAFT',
      },
    });
    vi.mocked(authoringApi.listApiResourceFixtures).mockResolvedValue([{
      schemaVersion: 'bloge.fixtureSetSummary.v1', fixtureSetId: 'profile:r2', revision: 1,
      fingerprint: `sha256:${'b'.repeat(64)}`, displayName: 'Profile default',
      subject: { kind: 'API_RESOURCE', resourceId: 'profile', revision: 2, fingerprint: `sha256:${'a'.repeat(64)}` },
      cases: [{ caseId: 'default', name: 'default' }], status: 'PRIVATE_DRAFT', statusRevision: 1,
    }]);
    vi.mocked(authoringApi.simulateFixtureCase).mockResolvedValue({
      schemaVersion: 'bloge.simulationRun.v1', runId: 'run-2', status: 'SUCCEEDED', output: { name: 'Ada' },
      nodes: [], verdicts: { execution: 'SIMULATED_ONLY', contract: 'PASSED', assertions: 'PASSED', governance: 'NOT_CHECKED' },
      diagnostics: [],
    });

    await act(async () => {
      root.render(<AuthoringWorkbench />);
      await Promise.resolve();
      await Promise.resolve();
    });
    await act(async () => {
      [...host.querySelectorAll<HTMLButtonElement>('.object-tabs button')]
        .find((value) => value.textContent === 'Fixture')?.click();
    });
    expect(element<HTMLAnchorElement>('open-resource-fixture').getAttribute('href'))
      .toBe('/workbench/?fixtureSetId=profile%3Ar2');
    await act(async () => button('run-saved-fixture').click());

    expect(authoringApi.listApiResourceFixtures).toHaveBeenCalledWith(expect.objectContaining({
      resourceId: 'profile', revision: 2,
    }));
    expect(authoringApi.simulateFixtureCase).toHaveBeenCalledWith(
      'profile:r2', 1, 'default', expect.stringMatching(/^simulate:profile:r2-1-default:/),
    );
    expect(element('resource-simulation-panel').textContent).toContain('run-2');
  });

  function change(testId: string, value: string) {
    const input = element<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>(testId);
    const prototype = input instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype
      : input instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(prototype, 'value')?.set?.call(input, value);
    input.dispatchEvent(new Event(input instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true }));
  }

  function link(testId: string): HTMLAnchorElement { return element(testId); }
  function button(testId: string): HTMLButtonElement { return element(testId); }
  function element<T extends Element = HTMLElement>(testId: string): T {
    const value = host.querySelector<T>(`[data-testid="${testId}"]`);
    if (!value) throw new Error(`Missing ${testId}`);
    return value;
  }
});
