// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import AuthoringWorkbench from './AuthoringWorkbench';
import * as flowApi from './flowApi';

vi.mock('./flowApi', async () => ({
  ...(await vi.importActual<typeof import('./flowApi')>('./flowApi')),
  readFixtureSet: vi.fn(), saveFixtureSet: vi.fn(), simulateFixtureSetCase: vi.fn(),
}));

describe('Fixture object page', () => {
  let root: Root;
  let host: HTMLDivElement;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    window.history.replaceState(null, '', '/workbench/?fixtureSetId=overview.default');
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    host.remove();
    vi.clearAllMocks();
  });

  it('reloads, updates, and simulates an exact whole-flow Fixture', async () => {
    const subject = { kind: 'FLOW_DRAFT' as const, draftId: 'draft-1', revision: 2, fingerprint: hash('a') };
    vi.mocked(flowApi.readFixtureSet).mockResolvedValue({
      strongEtag: '"fixture-r1"', replayed: false,
      value: {
        schemaVersion: 'bloge.fixtureSet.v1', fixtureSetId: 'overview.default', revision: 1,
        fingerprint: hash('b'), statusRevision: 1, displayName: 'Overview default', subject,
        cases: [{
          caseId: 'default', name: 'Default', input: { customerId: 'c-1' },
          controls: [{
            target: { kind: 'SUBJECT' },
            behavior: { kind: 'RETURN', material: { kind: 'INLINE', value: { result: 'old' } } },
          }],
          expect: { output: { result: 'old' } },
        }],
        status: 'PRIVATE_DRAFT',
      },
    });
    vi.mocked(flowApi.saveFixtureSet).mockResolvedValue({
      strongEtag: '"fixture-r2"', replayed: false,
      value: {
        schemaVersion: 'bloge.fixtureSetSaveReceipt.v1', fixtureSetId: 'overview.default', revision: 2,
        fingerprint: hash('c'), subject, caseIds: ['default'], status: 'PRIVATE_DRAFT', statusRevision: 1,
      },
    });
    vi.mocked(flowApi.simulateFixtureSetCase).mockResolvedValue({
      schemaVersion: 'bloge.simulationRun.v1', runId: 'run-fixture-2', status: 'SUCCEEDED',
      output: { result: 'new' }, nodes: [],
      verdicts: { execution: 'SIMULATED_ONLY', contract: 'PASSED', assertions: 'PASSED', governance: 'NOT_CHECKED' },
      diagnostics: [],
    });

    await act(async () => {
      root.render(<AuthoringWorkbench />);
      await Promise.resolve(); await Promise.resolve();
    });
    expect(element('fixture-object-page').textContent).toContain('Overview default');
    expect(element('fixture-status').textContent).toContain('PRIVATE_DRAFT');

    await act(async () => change('fixture-object-output', '{"result":"new"}'));
    await act(async () => button('save-fixture-object').click());

    expect(flowApi.saveFixtureSet).toHaveBeenCalledWith(
      'overview.default', expect.objectContaining({
        subject,
        cases: [expect.objectContaining({
          input: { customerId: 'c-1' }, expect: { output: { result: 'new' } },
        })],
      }), '"fixture-r1"', expect.stringMatching(/^save-fixture:overview.default:/),
    );
    expect(flowApi.simulateFixtureSetCase).toHaveBeenCalledWith(
      'overview.default', 2, 'default', expect.stringMatching(/^simulate-fixture:overview.default-2-default:/),
    );
    expect(element('fixture-simulation-output').textContent).toContain('new');
  });

  it('keeps an API Resource Default Fixture read-only while allowing exact simulation', async () => {
    vi.mocked(flowApi.readFixtureSet).mockResolvedValue({
      strongEtag: null, replayed: false,
      value: {
        schemaVersion: 'bloge.fixtureSet.v1', fixtureSetId: 'profile:r1', revision: 1,
        fingerprint: hash('d'), statusRevision: 1, displayName: 'Profile default',
        subject: { kind: 'API_RESOURCE', resourceId: 'profile', revision: 1, fingerprint: hash('e') },
        cases: [{
          caseId: 'default', name: 'Default', input: {},
          controls: [{
            target: { kind: 'SUBJECT' },
            behavior: { kind: 'RETURN', material: { kind: 'INLINE', value: { name: 'Ada' } } },
          }],
          expect: { output: { name: 'Ada' } },
        }],
        status: 'PRIVATE_DRAFT',
      },
    });
    vi.mocked(flowApi.simulateFixtureSetCase).mockResolvedValue({
      schemaVersion: 'bloge.simulationRun.v1', runId: 'run-api-fixture', status: 'SUCCEEDED', output: { name: 'Ada' },
      nodes: [], verdicts: { execution: 'SIMULATED_ONLY', contract: 'PASSED', assertions: 'PASSED', governance: 'NOT_CHECKED' },
      diagnostics: [],
    });

    await act(async () => {
      root.render(<AuthoringWorkbench />);
      await Promise.resolve(); await Promise.resolve();
    });
    expect(host.querySelector('[data-testid="save-fixture-object"]')).toBeNull();
    expect(element<HTMLAnchorElement>('fixture-subject-link').getAttribute('href'))
      .toBe('/workbench/?resourceId=profile');
    await act(async () => button('run-fixture-case').click());
    expect(flowApi.simulateFixtureSetCase).toHaveBeenCalledWith(
      'profile:r1', 1, 'default', expect.stringMatching(/^simulate-fixture:profile:r1-1-default:/),
    );
  });

  function change(testId: string, value: string) {
    const input = element<HTMLTextAreaElement>(testId);
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set?.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  }
  function button(testId: string): HTMLButtonElement { return element(testId); }
  function element<T extends Element = HTMLElement>(testId: string): T {
    const value = host.querySelector<T>(`[data-testid="${testId}"]`);
    if (!value) throw new Error(`Missing ${testId}`);
    return value;
  }
});

function hash(value: string): string { return `sha256:${value.repeat(64)}`; }
