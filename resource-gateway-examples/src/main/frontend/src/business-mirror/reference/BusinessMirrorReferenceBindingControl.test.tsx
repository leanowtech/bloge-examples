// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import I18nProvider from '../../i18n/I18nProvider';
import type { ReferenceCandidate, ReferencePage } from '../../shared/reference-picker/types';
import type { BusinessMirrorPackageDraft } from '../domain';
import BusinessMirrorReferenceBindingControl from './BusinessMirrorReferenceBindingControl';

const api = vi.hoisted(() => ({
  fetchBusinessMirrorReferenceCandidates: vi.fn(),
  resolveBusinessMirrorReferenceCandidate: vi.fn(),
}));

vi.mock('../../api', () => api);

describe('BusinessMirrorReferenceBindingControl', () => {
  let host: HTMLDivElement;
  let root: Root | null;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    vi.useFakeTimers();
    host = document.createElement('div');
    document.body.appendChild(host);
    root = null;
    api.fetchBusinessMirrorReferenceCandidates.mockResolvedValue(page([candidate()]));
  });

  afterEach(async () => {
    if (root) await act(async () => root?.unmount());
    host.remove();
    vi.useRealTimers();
    vi.clearAllMocks();
  });

  it('writes an exact coordinate only after authority re-resolution succeeds', async () => {
    const selected = candidate();
    const onDraft = vi.fn();
    api.resolveBusinessMirrorReferenceCandidate.mockResolvedValue({
      schemaVersion: 'bloge.referenceResolveResult.v1',
      status: 'RESOLVED',
      candidate: selected,
      errorCode: '',
    });
    await render(onDraft);

    await selectFirstCandidate();

    expect(api.fetchBusinessMirrorReferenceCandidates).toHaveBeenCalledWith(
      'PACKAGE_CONTRACT',
      { query: '', cursor: null, limit: 20 },
      expect.any(AbortSignal),
    );
    expect(api.resolveBusinessMirrorReferenceCandidate)
      .toHaveBeenCalledWith(selected, 'BIND_CONTRACT');
    expect(onDraft).toHaveBeenCalledWith(expect.objectContaining({
      packageContractRef: {
        kind: 'CONTRACT', id: 'loan-contract', revision: 3,
        fingerprint: selected.fingerprint,
      },
    }));
    expect(host.textContent).toContain('Exact reference confirmed');
  });

  it('fails closed when the candidate drifts between search and binding', async () => {
    const onDraft = vi.fn();
    api.resolveBusinessMirrorReferenceCandidate.mockResolvedValue({
      schemaVersion: 'bloge.referenceResolveResult.v1',
      status: 'DRIFTED',
      candidate: { ...candidate(), revision: 4, fingerprint: fingerprint('current') },
      errorCode: 'RG.REFERENCE.DRIFTED',
    });
    await render(onDraft);

    await selectFirstCandidate();

    expect(onDraft).not.toHaveBeenCalled();
    expect(host.querySelector('[role="alert"]')?.textContent)
      .toContain('This candidate changed after search');
    expect(host.textContent).toContain('RG.REFERENCE.DRIFTED');
  });

  async function render(onDraft: (draft: BusinessMirrorPackageDraft) => void) {
    await act(async () => {
      root = createRoot(host);
      root.render(
        <I18nProvider>
          <BusinessMirrorReferenceBindingControl
            draft={minimalDraft()}
            editable
            field="contract"
            help="businessMirror.reference.help.contract"
            label="businessMirror.boundary.contract"
            onDraft={onDraft}
            remediationAnchor="business-mirror.boundary.contract"
          />
        </I18nProvider>,
      );
    });
  }

  async function selectFirstCandidate() {
    const input = host.querySelector<HTMLInputElement>('[role="combobox"]');
    if (!input) throw new Error('Missing contract picker');
    await act(async () => input.focus());
    await act(async () => {
      vi.advanceTimersByTime(250);
      await Promise.resolve();
      await Promise.resolve();
    });
    const option = host.querySelector<HTMLElement>('[role="option"]');
    if (!option) throw new Error('Missing contract candidate');
    await act(async () => {
      option.click();
      await Promise.resolve();
      await Promise.resolve();
    });
  }
});

function candidate(): ReferenceCandidate {
  return {
    schemaVersion: 'bloge.referenceCandidate.v1', kind: 'CONTRACT',
    id: 'loan-contract', displayName: 'Loan contract', description: 'Metadata only.',
    revision: 3, fingerprint: fingerprint('loan-contract'), authority: 'test://business-catalog',
    scope: {
      tenantId: 'tenant-a', organizationId: 'customer-service', projectId: 'loan',
      environmentId: 'test', region: 'local',
    },
    lifecycle: 'ACTIVE', owner: { stableId: 'service-design', displayName: 'Service Design' },
    labels: ['demo'], compatibility: 'COMPATIBLE', disabledReasonCode: '',
  };
}

function page(items: readonly ReferenceCandidate[]): ReferencePage {
  return {
    schemaVersion: 'bloge.referencePage.v1', items, nextCursor: null,
    queryFingerprint: fingerprint('query'), catalogGeneration: 1,
  };
}

function minimalDraft(): BusinessMirrorPackageDraft {
  return {
    schemaVersion: 'bloge.domainCapabilityPackageDraft.v1', packageId: 'legacy:loan', revision: 1,
    scope: {
      tenantId: 'tenant-a', organizationId: 'customer-service', projectId: 'loan',
      environmentId: 'test', region: 'local',
    },
    businessDefinition: {
      domainId: '', problemTaxonomyRef: null, problemCode: '', businessGoal: '',
      expectedOutcome: '', riskClass: 'CRITICAL', accountableOwner: '', collaboratingOwners: [],
    },
    packageContractRef: null, capabilityRefs: [], graphRefs: [], proposalRefs: [],
    stateModelRefs: [], effectModelRefs: [], scenarioInventoryRef: null, scenarioPackRefs: [],
    solutionRefs: [], carrierRefs: [], channelRefs: [], fidelityInventoryRef: null,
    outcomeDefinitionRefs: [], limitations: [], assumptions: [], expiresAt: null,
    provenance: {
      schemaVersion: 'resourceGateway.artifactProvenance.v1', sourceType: 'DECLARED', sourceRefs: [],
      tenantId: 'tenant-a', purpose: 'TEST', sampleFrom: null, sampleTo: null, sampleCount: null,
      confidence: null, biasRisks: [], approvedBy: '', approvedAt: null, expiresAt: null,
      revocationRef: '',
    },
    lifecycle: 'DRAFT',
  };
}

function fingerprint(seed: string): string {
  return `sha256:${seed.replace(/[^a-f0-9]/g, 'a').padEnd(64, 'a').slice(0, 64)}`;
}
