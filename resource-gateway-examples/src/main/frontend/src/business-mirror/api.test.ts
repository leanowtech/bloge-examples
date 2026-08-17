import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  compileBusinessMirrorPackage,
  fetchBusinessMirrorLegacyCatalog,
  fetchBusinessMirrorPackages,
  fetchBusinessMirrorReferenceCandidates,
  importBusinessMirrorLegacyPackage,
  resetBlogeApiTransport,
  resolveBusinessMirrorAuthorLink,
  resolveBusinessMirrorReferenceCandidate,
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

  it('searches metadata-only candidates and exact-resolves a selected binding', async () => {
    const transport = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => (
      String(input).endsWith(':resolve')
        ? json({
          schemaVersion: 'bloge.referenceResolveResult.v1', status: 'RESOLVED',
          candidate: referenceCandidate(), errorCode: '',
        })
        : json({
          schemaVersion: 'bloge.referencePage.v1', items: [referenceCandidate()],
          nextCursor: null, queryFingerprint: 'sha256:query', catalogGeneration: 1,
        })
    ));
    setBlogeApiTransport(transport);
    const controller = new AbortController();

    await fetchBusinessMirrorReferenceCandidates(
      'PACKAGE_CONTRACT', { query: 'loan', cursor: null, limit: 20 }, controller.signal,
    );
    await resolveBusinessMirrorReferenceCandidate(referenceCandidate(), 'BIND_PACKAGE_CONTRACT');

    expect(String(transport.mock.calls[0][0])).toContain(
      '/api/visual/reference-candidates?kind=PACKAGE_CONTRACT&query=loan');
    expect(transport.mock.calls[0][1]?.signal).toBe(controller.signal);
    expect(header(transport.mock.calls[0][1], 'X-Purpose')).toBe('BUSINESS_MIRROR_AUTHORING');
    expect(String(transport.mock.calls[1][0])).toBe('/api/visual/reference-candidates:resolve');
    expect(JSON.parse(String(transport.mock.calls[1][1]?.body))).toMatchObject({
      schemaVersion: 'bloge.referenceResolveCommand.v1',
      kind: 'PACKAGE_CONTRACT', id: 'loan-contract', revision: 3,
      intendedUse: 'BIND_PACKAGE_CONTRACT',
    });
  });

  it('maps a runtime catalog outage to the picker unavailable state', async () => {
    setBlogeApiTransport(vi.fn(async () => json({
      detail: 'Reference catalog is temporarily unavailable.',
    }, 503, 'Service Unavailable')));

    await expect(fetchBusinessMirrorReferenceCandidates(
      'OWNER', { query: '', cursor: null, limit: 20 }, new AbortController().signal,
    )).rejects.toMatchObject({ status: 'unavailable', retryable: true });
  });

  it('resolves exact Author navigation through the authenticated protocol', async () => {
    const transport = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => json({
      schemaVersion: 'bloge.authoringLinkDescriptor.v1',
      resolution: 'READ_ONLY_SOURCE',
      route: { path: '/author/', workspace: 'v2', authorMode: 'compose', query: {} },
    }));
    setBlogeApiTransport(transport);

    await resolveBusinessMirrorAuthorLink({
      graphName: 'loanDecisionPolicy',
      graphRef: { id: 'built-in:loanDecisionPolicy', revision: 3, fingerprint: `sha256:${'a'.repeat(64)}` },
      packageId: 'legacy:loanDecisionPolicy',
    });

    expect(String(transport.mock.calls[0][0])).toBe('/api/visual/authoring-links:resolve');
    expect(header(transport.mock.calls[0][1], 'X-Purpose')).toBe('BUSINESS_MIRROR_AUTHORING');
    expect(JSON.parse(String(transport.mock.calls[0][1]?.body))).toEqual({
      schemaVersion: 'bloge.authoringLinkResolveRequest.v1',
      subjectRef: {
        kind: 'BUSINESS_MIRROR_LEGACY_GRAPH', id: 'built-in:loanDecisionPolicy',
        revision: 3, fingerprint: `sha256:${'a'.repeat(64)}`,
      },
      intent: 'EDIT_TOPOLOGY',
      returnCoordinate: {
        route: 'business-mirror', packageId: 'legacy:loanDecisionPolicy', task: 'capabilities',
        anchor: 'graph:built-in:loanDecisionPolicy',
      },
    });
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

function referenceCandidate() {
  return {
    schemaVersion: 'bloge.referenceCandidate.v1' as const,
    kind: 'PACKAGE_CONTRACT', id: 'loan-contract', displayName: 'Loan contract',
    description: '', revision: 3, fingerprint: `sha256:${'a'.repeat(64)}`,
    authority: 'resource-gateway://demo-business-directory',
    scope: {
      tenantId: 'ride-hailing', organizationId: 'customer-service', projectId: 'loan',
      environmentId: 'test', region: 'sg',
    },
    lifecycle: 'ACTIVE' as const,
    owner: { stableId: 'service-design', displayName: 'Service Design' },
    labels: ['demo'], compatibility: 'COMPATIBLE' as const, disabledReasonCode: '',
  };
}
