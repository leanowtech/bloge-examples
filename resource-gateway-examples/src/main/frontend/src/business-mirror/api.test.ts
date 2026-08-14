import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  compileBusinessMirrorPackage,
  fetchBusinessMirrorLegacyCatalog,
  fetchBusinessMirrorPackages,
  importBusinessMirrorLegacyPackage,
  resetBlogeApiTransport,
  saveBusinessMirrorPackage,
  setBlogeApiTransport,
} from '../api';
import type { BusinessMirrorPackageDraft } from './domain';

describe('Business Mirror API client', () => {
  afterEach(() => resetBlogeApiTransport());

  it('loads Portfolio inputs through authenticated bounded endpoints', async () => {
    const transport = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
      const url = String(input);
      return json(url.includes('legacy-graphs')
        ? { schemaVersion: 'resourceGateway.legacyGraphPackageProjectionCatalog.v1', items: [] }
        : { schemaVersion: 'resourceGateway.domainCapabilityPackagePage.v1', items: [], nextCursor: '' });
    });
    setBlogeApiTransport(transport);

    await Promise.all([fetchBusinessMirrorLegacyCatalog(), fetchBusinessMirrorPackages()]);

    expect(transport).toHaveBeenCalledTimes(2);
    expect(String(transport.mock.calls[0][0])).toBe('/api/business-mirror/legacy-graphs');
    expect(String(transport.mock.calls[1][0])).toBe('/api/business-mirror/packages?limit=200');
    expect(header(transport.mock.calls[0][1], 'Authorization'))
      .toBe('Bearer bloge-aneke-demo-token');
    expect(transport.mock.calls.every((call) =>
      header(call[1], 'X-Purpose') === 'BUSINESS_MIRROR_AUTHORING')).toBe(true);
  });

  it('imports, saves, and compiles with explicit idempotency and optimistic coordinates', async () => {
    const transport = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) =>
      json({ schemaVersion: 'test' }));
    setBlogeApiTransport(transport);
    const draft = minimalDraft();

    await importBusinessMirrorLegacyPackage('loanDecisionPolicy', 'import:loan:v1');
    await saveBusinessMirrorPackage(draft, 'save:loan:r1');
    await compileBusinessMirrorPackage(draft.packageId, 1, 'compile:loan:r1');

    expect(String(transport.mock.calls[0][0]))
      .toBe('/api/business-mirror/legacy-graphs/loanDecisionPolicy/packages');
    expect(transport.mock.calls[0][1]?.method).toBe('POST');
    expect(header(transport.mock.calls[0][1], 'Idempotency-Key')).toBe('import:loan:v1');
    expect(header(transport.mock.calls[0][1], 'X-Purpose')).toBe('BUSINESS_MIRROR_AUTHORING');
    expect(String(transport.mock.calls[1][0]))
      .toBe('/api/business-mirror/packages/legacy%3AloanDecisionPolicy?expectedRevision=1');
    expect(transport.mock.calls[1][1]?.body).toBe(JSON.stringify(draft));
    expect(header(transport.mock.calls[1][1], 'X-Purpose')).toBe('BUSINESS_MIRROR_AUTHORING');
    expect(String(transport.mock.calls[2][0]))
      .toBe('/api/business-mirror/packages/legacy%3AloanDecisionPolicy/compile?sourceRevision=1');
    expect(header(transport.mock.calls[2][1], 'X-Purpose')).toBe('BUSINESS_MIRROR_AUTHORING');
  });

  it('surfaces a stable server diagnostic instead of an opaque HTTP status', async () => {
    setBlogeApiTransport(async () => json({
      code: 'RG.BUSINESS_MIRROR.PACKAGE_REVISION_CONFLICT',
      detail: 'Package draft changed after it was loaded.',
    }, 409, 'Conflict'));

    await expect(saveBusinessMirrorPackage(minimalDraft(), 'save:conflict:r1'))
      .rejects.toThrow('Package draft changed after it was loaded.');
  });
});

function json(body: unknown, status = 200, statusText = 'OK'): Response {
  return new Response(JSON.stringify(body), {
    status,
    statusText,
    headers: { 'Content-Type': 'application/json' },
  });
}

function header(init: RequestInit | undefined, name: string): string | null {
  return new Headers(init?.headers).get(name);
}

function minimalDraft(): BusinessMirrorPackageDraft {
  return {
    schemaVersion: 'bloge.domainCapabilityPackageDraft.v1',
    packageId: 'legacy:loanDecisionPolicy',
    revision: 1,
    scope: {
      tenantId: 'ride-hailing', organizationId: 'customer-service', projectId: 'loan',
      environmentId: 'test', region: 'sg',
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
      schemaVersion: 'resourceGateway.artifactProvenance.v1', sourceType: 'DECLARED',
      sourceRefs: [], tenantId: 'ride-hailing', purpose: 'TEST', sampleFrom: null, sampleTo: null,
      sampleCount: null, confidence: null, biasRisks: [], approvedBy: '', approvedAt: null,
      expiresAt: null, revocationRef: '',
    },
    lifecycle: 'DRAFT',
  };
}
