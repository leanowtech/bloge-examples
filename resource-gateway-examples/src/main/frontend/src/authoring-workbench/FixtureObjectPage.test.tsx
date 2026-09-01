// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import AuthoringWorkbench from './AuthoringWorkbench';
import * as flowApi from './flowApi';

vi.mock('./flowApi', async () => ({
  ...(await vi.importActual<typeof import('./flowApi')>('./flowApi')),
  readFixtureSet: vi.fn(), saveFixtureSet: vi.fn(), shareFixtureSet: vi.fn(),
  reviewFixtureSet: vi.fn(), simulateFixtureSetCase: vi.fn(),
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
      output: { result: 'new' }, nodes: [{
        nodeId: 'subject', status: 'COMPLETED', execution: 'MOCKED', fixtureSource: 'FIXTURE_ASSET',
        fidelity: 'OUTPUT_LEVEL', egress: { decision: 'FIXTURE', attempted: false },
      }],
      verdicts: { execution: 'SIMULATED_ONLY', contract: 'PASSED', assertions: 'PASSED', governance: 'PASSED' },
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
    expect(element('fixture-simulation-status').textContent).toBe('SUCCEEDED');
    expect(element('fixture-simulation-execution').textContent).toBe('SIMULATED_ONLY');
    expect(element('fixture-simulation-contract').textContent).toBe('PASSED');
    expect(element('fixture-simulation-assertions').textContent).toBe('PASSED');
    expect(element('fixture-simulation-governance').textContent).toBe('PASSED');
    const evidence = element('fixture-simulation-node:subject').textContent;
    for (const value of ['MOCKED', 'FIXTURE_ASSET', 'OUTPUT_LEVEL', 'FIXTURE', 'NO_EGRESS']) {
      expect(evidence).toContain(value);
    }
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

  it('visibly submits a protected revision and keeps pending material non-runnable', async () => {
    const subject = { kind: 'FLOW_VERSION' as const, publicationId: 'flow-overview', revision: 1,
      fingerprint: hash('a') };
    const privateView = {
      schemaVersion: 'bloge.fixtureSet.v1' as const, fixtureSetId: 'overview.default', revision: 1,
      fingerprint: hash('b'), statusRevision: 1, displayName: 'Overview default', subject,
      cases: [{
        caseId: 'default', name: 'Default', input: { customerId: 'c-1' },
        controls: [{ target: { kind: 'SUBJECT' as const }, behavior: {
          kind: 'RETURN' as const, material: { kind: 'INLINE' as const, value: { result: 'private' } },
        } }],
        expect: { output: { result: 'private' } },
      }],
      status: 'PRIVATE_DRAFT' as const,
    };
    const pendingView = {
      ...privateView, revision: 2, fingerprint: hash('c'), statusRevision: 2,
      status: 'SHARING_PENDING' as const,
      cases: [{ ...privateView.cases[0], controls: [{
        target: { kind: 'SUBJECT' as const }, behavior: {
          kind: 'RETURN' as const, material: {
            kind: 'FIXTURE_ASSET' as const, fixtureAssetId: 'overview-default-default',
            revision: 2, schemaFingerprint: hash('d'),
          },
        },
      }] }],
    };
    vi.mocked(flowApi.readFixtureSet)
      .mockResolvedValueOnce({ value: privateView, strongEtag: '"fixture-r1"', replayed: false })
      .mockResolvedValueOnce({ value: pendingView, strongEtag: '"fixture-r2"', replayed: false });
    vi.mocked(flowApi.shareFixtureSet).mockResolvedValue({
      strongEtag: '"fixture-r2"', replayed: false,
      value: {
        schemaVersion: 'bloge.fixtureShareReceipt.v1', fixtureSetId: 'overview.default',
        derivedFromRevision: 1, revision: 2, fingerprint: hash('c'), status: 'SHARING_PENDING',
        statusRevision: 2, reviewRequestId: 'review-overview-r2',
      },
    });

    await act(async () => {
      root.render(<AuthoringWorkbench />);
      await Promise.resolve(); await Promise.resolve();
    });
    expect(element('fixture-share-panel').textContent).toContain('Share with team');
    await act(async () => setControl('fixture-share-classification', 'CONFIDENTIAL'));
    await act(async () => setControl('fixture-share-retention', '45'));
    await act(async () => setControl('fixture-share-redaction-paths', '/customer/email\n/token'));
    await act(async () => {
      button('share-fixture-object').click();
      await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
    });

    expect(flowApi.shareFixtureSet).toHaveBeenCalledWith(
      'overview.default', {
        schemaVersion: 'bloge.fixtureShareCommand.v1',
        source: {
          fixtureSetId: 'overview.default', revision: 1,
          fingerprint: hash('b'), statusRevision: 1,
        },
        policy: {
          classification: 'CONFIDENTIAL', retentionDays: 45,
          redaction: { profileVersion: 'default-v1', paths: ['/customer/email', '/token'] },
        },
      }, '"fixture-r1"', expect.stringMatching(/^share-fixture:overview.default-1:/),
    );
    expect(flowApi.readFixtureSet).toHaveBeenLastCalledWith('overview.default', 2);
    expect(element('fixture-status').textContent).toContain('SHARING_PENDING');
    expect(element('fixture-sharing-pending').textContent).toContain('cannot run or be reused');
    expect(button('run-fixture-case').disabled).toBe(true);
    expect(host.querySelector('[data-testid="fixture-share-panel"]')).toBeNull();
    expect(element('fixture-message').textContent).toContain('review-overview-r2');
    expect(flowApi.simulateFixtureSetCase).not.toHaveBeenCalled();
  });

  it('requires visible reviewer attestations before publishing an exact pending Fixture', async () => {
    window.history.replaceState(null, '',
      '/workbench/?fixtureSetId=overview.default&reviewRequestId=review-overview-r2');
    const subject = { kind: 'FLOW_VERSION' as const, publicationId: 'flow-overview', revision: 1,
      fingerprint: hash('a') };
    const pendingView = {
      schemaVersion: 'bloge.fixtureSet.v1' as const, fixtureSetId: 'overview.default', revision: 2,
      fingerprint: hash('c'), statusRevision: 2, displayName: 'Overview default', subject,
      cases: [{
        caseId: 'default', name: 'Default', input: { customerId: 'c-1' },
        controls: [{ target: { kind: 'SUBJECT' as const }, behavior: {
          kind: 'RETURN' as const, material: {
            kind: 'FIXTURE_ASSET' as const, fixtureAssetId: 'overview-default-default',
            revision: 2, schemaFingerprint: hash('d'),
          },
        } }], expect: { output: { result: 'active' } },
      }], status: 'SHARING_PENDING' as const,
    };
    const activeView = { ...pendingView, revision: 3, fingerprint: hash('e'), statusRevision: 3,
      status: 'TEAM_AVAILABLE' as const, cases: [{ ...pendingView.cases[0], controls: [{
        target: { kind: 'SUBJECT' as const }, behavior: { kind: 'RETURN' as const, material: {
          kind: 'FIXTURE_ASSET' as const, fixtureAssetId: 'overview-default-default',
          revision: 5, schemaFingerprint: hash('d'),
        } },
      }] }] };
    vi.mocked(flowApi.readFixtureSet)
      .mockResolvedValueOnce({ value: pendingView, strongEtag: '"fixture-r2"', replayed: false })
      .mockResolvedValueOnce({ value: activeView, strongEtag: '"fixture-r3"', replayed: false });
    vi.mocked(flowApi.reviewFixtureSet).mockResolvedValue({
      strongEtag: '"fixture-r3"', replayed: false,
      value: {
        schemaVersion: 'bloge.fixtureReviewReceipt.v1', reviewRequestId: 'review-overview-r2',
        fixtureSetId: 'overview.default', derivedFromRevision: 2, revision: 3,
        fingerprint: hash('e'), status: 'TEAM_AVAILABLE', statusRevision: 3, activatedAssetCount: 1,
      },
    });

    await act(async () => {
      root.render(<AuthoringWorkbench />);
      await Promise.resolve(); await Promise.resolve();
    });
    expect(button('approve-fixture-object').disabled).toBe(true);
    await act(async () => toggle('fixture-review-redaction-reviewed'));
    await act(async () => toggle('fixture-review-schema-valid'));
    await act(async () => toggle('fixture-review-redaction-verified'));
    await act(async () => change('fixture-review-comment', 'Independent reviewer verified protected material'));
    expect(button('approve-fixture-object').disabled).toBe(false);
    await act(async () => {
      button('approve-fixture-object').click();
      await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
    });

    expect(flowApi.reviewFixtureSet).toHaveBeenCalledWith(
      'overview.default', {
        schemaVersion: 'bloge.fixtureReviewCommand.v1', source: {
          reviewRequestId: 'review-overview-r2', fixtureSetId: 'overview.default',
          revision: 2, fingerprint: hash('c'), statusRevision: 2,
        }, attestations: {
          redactionReviewed: true, schemaValid: true, redactionVerified: true,
          comment: 'Independent reviewer verified protected material',
        },
      }, '"fixture-r2"', expect.stringMatching(/^review-fixture:overview.default-2:/),
    );
    expect(element('fixture-status').textContent).toContain('TEAM_AVAILABLE');
    expect(button('run-fixture-case').disabled).toBe(false);
  });

  function change(testId: string, value: string) {
    const input = element<HTMLTextAreaElement>(testId);
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set?.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  }
  function setControl(testId: string, value: string) {
    const control = element<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>(testId);
    const prototype = control instanceof HTMLSelectElement ? HTMLSelectElement.prototype
      : control instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(prototype, 'value')?.set?.call(control, value);
    control.dispatchEvent(new Event(control instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true }));
  }
  function toggle(testId: string) {
    const control = element<HTMLInputElement>(testId);
    control.click();
  }
  function button(testId: string): HTMLButtonElement { return element(testId); }
  function element<T extends Element = HTMLElement>(testId: string): T {
    const value = host.querySelector<T>(`[data-testid="${testId}"]`);
    if (!value) throw new Error(`Missing ${testId}`);
    return value;
  }
});

function hash(value: string): string { return `sha256:${value.repeat(64)}`; }
