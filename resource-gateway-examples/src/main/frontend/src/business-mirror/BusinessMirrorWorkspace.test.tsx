// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import I18nProvider from '../i18n/I18nProvider';
import { SafeWorkspaceNavigationProvider } from '../author/continuity/SafeWorkspaceNavigation';
import type { ReferenceCandidate, ReferencePage } from '../shared/reference-picker/types';
import BusinessMirrorWorkspace from './BusinessMirrorWorkspace';
import type {
  BusinessMirrorDomainEvidencePortfolio,
  BusinessMirrorPackageDraft,
  BusinessMirrorPackageEvidenceIndex,
  LegacyGraphPackageProjection,
  StoredBusinessMirrorPackage,
} from './domain';
import { createGuidedAuthoringTelemetry, type GuidedTelemetryEvent } from '../shared/guided-telemetry/guidedTelemetry';

const api = vi.hoisted(() => ({
  BlogeApiRequestError: class BlogeApiRequestError extends Error {
    constructor(readonly status: number, readonly detail: string) {
      super(`Request failed: ${status} ${detail}`);
    }
  },
  fetchBusinessMirrorLegacyCatalog: vi.fn(),
  fetchBusinessMirrorPackageEvidence: vi.fn(),
  fetchBusinessMirrorDomainEvidencePortfolio: vi.fn(),
  fetchBusinessMirrorPackages: vi.fn(),
  importBusinessMirrorLegacyPackage: vi.fn(),
  saveBusinessMirrorPackage: vi.fn(),
  compileBusinessMirrorPackage: vi.fn(),
  refreshBusinessMirrorPackageEvidence: vi.fn(),
  acknowledgeBusinessMirrorEvidenceTask: vi.fn(),
  fetchBusinessMirrorReferenceCandidates: vi.fn(),
  resolveBusinessMirrorReferenceCandidate: vi.fn(),
  resolveBusinessMirrorAuthorLink: vi.fn(),
}));

vi.mock('../api', () => api);

describe('Business Mirror Workspace', () => {
  let root: Root | null = null;
  let host: HTMLDivElement | null = null;
  const projection = legacyProjection();

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    window.history.replaceState({}, '', '/business-mirror/');
    const values = new Map<string, string>();
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      value: {
        clear: () => values.clear(),
        getItem: (key: string) => values.get(key) ?? null,
        setItem: (key: string, value: string) => values.set(key, value),
        removeItem: (key: string) => values.delete(key),
        key: (index: number) => [...values.keys()][index] ?? null,
        get length() { return values.size; },
      },
    });
    window.localStorage.clear();
    api.fetchBusinessMirrorLegacyCatalog.mockResolvedValue({
      schemaVersion: 'resourceGateway.legacyGraphPackageProjectionCatalog.v1',
      scope: projection.scope,
      items: [projection],
    });
    api.fetchBusinessMirrorPackages.mockResolvedValue({
      schemaVersion: 'resourceGateway.domainCapabilityPackagePage.v1', items: [], nextCursor: '',
    });
    api.fetchBusinessMirrorReferenceCandidates.mockImplementation(async (kind: string) =>
      referencePage([demoReference(kind)]));
    api.resolveBusinessMirrorReferenceCandidate.mockImplementation(async (candidate: ReferenceCandidate) => ({
      schemaVersion: 'bloge.referenceResolveResult.v1',
      status: 'RESOLVED',
      candidate,
      errorCode: '',
    }));
    api.resolveBusinessMirrorAuthorLink.mockImplementation(async () => authoringLinkDescriptor());
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
  });

  afterEach(async () => {
    if (root) await act(async () => root?.unmount());
    host?.remove();
    root = null;
    host = null;
    vi.clearAllMocks();
  });

  it('leads from Portfolio to the first blocker without exposing raw JSON', async () => {
    await render();

    expect(document.body.textContent).toContain('Business capability portfolio');
    expect(document.body.textContent).toContain('Loan Decision Policy');
    expect(document.querySelector('textarea')).toBeNull();

    await click(button('Loan Decision Policy'));

    expect(document.body.textContent).toContain('Package readiness');
    expect(document.body.textContent).toContain('ACCOUNTABLE_OWNER_MISSING');
    expect(document.body.textContent).toContain('1. Define problem');
    expect(document.body.textContent).toContain('Which customer problem are we accountable for');
    expect(document.body.textContent).toContain('Inputs for this step');
    expect(document.body.textContent).toContain('Next best action');
    expect(button('Continue this step')).toBeInstanceOf(HTMLButtonElement);
    expect(referenceInput('Business domain').disabled).toBe(true);
    expect(document.querySelectorAll('.business-mirror-task-rail button')).toHaveLength(7);
  });

  it('focuses and highlights a blocker in the current Sheet instead of becoming a visual no-op', async () => {
    const stored = storedPackage(projection.packageDraft, 1);
    api.fetchBusinessMirrorPackages.mockResolvedValue({
      schemaVersion: 'resourceGateway.domainCapabilityPackagePage.v1',
      items: [stored],
      nextCursor: '',
    });
    api.saveBusinessMirrorPackage.mockImplementation(async (draft: BusinessMirrorPackageDraft) =>
      saveReceipt(storedPackage(draft, 2)));
    window.history.replaceState({}, '', '/business-mirror/?packageId=legacy%3AloanDecisionPolicy&task=problem');

    await render();
    await click(button('Resolve first blocker'));
    await settleFrame();

    const owner = referenceInput('Accountable owner');
    const target = owner.closest<HTMLElement>('[data-remediation-anchor]');
    expect(document.activeElement).toBe(owner);
    expect(owner.getAttribute('aria-expanded')).toBe('true');
    expect(target?.dataset.remediationAnchor).toBe('business-mirror.problem.owner');
    expect(target?.classList.contains('business-mirror-remediation-target')).toBe(true);
    expect(document.querySelector('[data-testid="business-mirror-remediation-outcome"]')?.textContent)
      .toContain('exact control is focused and highlighted');
    expect(new URLSearchParams(window.location.search).get('gapCode')).toBe('ACCOUNTABLE_OWNER_MISSING');

    await chooseReference('Accountable owner', 'Risk service owner');
    await click(button('Save Package changes'));
    expect(document.querySelector('[data-testid="business-mirror-remediation-outcome"]')?.textContent)
      .toContain('blocker is now resolved');
  });

  it('closes every remediation start with a payload-free terminal event', async () => {
    const events: GuidedTelemetryEvent[] = [];
    const telemetry = createGuidedAuthoringTelemetry((event) => events.push(event));
    const stored = storedPackage(projection.packageDraft, 1);
    api.fetchBusinessMirrorPackages.mockResolvedValue({
      schemaVersion: 'resourceGateway.domainCapabilityPackagePage.v1',
      items: [stored],
      nextCursor: '',
    });
    window.history.replaceState({}, '', '/business-mirror/?packageId=legacy%3AloanDecisionPolicy&task=problem');

    await render(telemetry);
    await act(async () => new Promise((resolve) => window.setTimeout(resolve, 50)));
    await settleFrame();
    await click(button('Resolve first blocker'));
    await settleFrame();
    await settleFrame();

    expect(events.map((event) => event.name)).toEqual([
      'REMEDIATION_STARTED', 'REMEDIATION_COMPLETED',
    ]);
    expect(events[0]?.metadata).toEqual({
      gapCode: 'ACCOUNTABLE_OWNER_MISSING', actionKind: 'OPEN_PICKER', sameStep: true,
    });
    expect(events[1]?.metadata).toMatchObject({
      gapCode: 'ACCOUNTABLE_OWNER_MISSING', outcome: 'TARGETED',
    });
    expect(events.every((event) => !JSON.stringify(event).includes('loanDecisionPolicy'))).toBe(true);
  });

  it('reopens the exact picker when a preview remediation is retried after import', async () => {
    const stored = storedPackage(projection.packageDraft, 1);
    api.importBusinessMirrorLegacyPackage.mockResolvedValue(saveReceipt(stored));

    await render();
    await click(button('Loan Decision Policy'));
    await click(button('Resolve first blocker'));
    await settleFrame();
    expect(referenceInput('Accountable owner').disabled).toBe(true);

    await click(button('Import Package'));
    await settleFrame();
    await click(button('Resolve first blocker'));
    await settleFrame();

    const owner = referenceInput('Accountable owner');
    expect(owner.disabled).toBe(false);
    expect(document.activeElement).toBe(owner);
    expect(owner.getAttribute('aria-expanded')).toBe('true');
  });

  it('moves a cross-Sheet blocker to its exact actionable requirement and preserves the coordinate', async () => {
    const stored = storedPackage(projection.packageDraft, 1);
    api.fetchBusinessMirrorPackages.mockResolvedValue({
      schemaVersion: 'resourceGateway.domainCapabilityPackagePage.v1',
      items: [stored],
      nextCursor: '',
    });
    window.history.replaceState({}, '', '/business-mirror/?packageId=legacy%3AloanDecisionPolicy&task=problem');

    await render();
    await click(button('SCENARIO_PACK_MISSING'));
    await settleFrame();

    const target = document.querySelector<HTMLElement>(
      '[data-remediation-anchor="business-mirror.scenarios.pack"]',
    );
    expect(document.querySelector('.business-mirror-task-rail button[aria-current="step"]')?.textContent)
      .toContain('4. Freeze scenarios');
    expect(document.body.textContent).toContain('Which business branches must always be covered');
    const scenarioPack = referenceInput('Executable Scenario packs');
    expect(document.activeElement).toBe(scenarioPack);
    expect(scenarioPack.getAttribute('aria-expanded')).toBe('true');
    expect(target?.classList.contains('business-mirror-remediation-target')).toBe(true);
    const params = new URLSearchParams(window.location.search);
    expect(params.get('task')).toBe('scenarios');
    expect(params.get('remediationAnchor')).toBe('business-mirror.scenarios.pack');
  });

  it('executes a cross-workspace remediation and preserves its return coordinate', async () => {
    const projectionWithRehearsal = {
      ...projection,
      gaps: [...projection.gaps, {
        code: 'MIRROR_PLAN_MISSING', origin: 'MIGRATION_POLICY' as const,
        category: 'EXECUTION_MODEL', severity: 'BLOCKING' as const,
        draftPath: '/mirrorPlanRef', explanation: 'missing', requiredAction: 'open rehearsal',
        evidenceRefs: [],
      }],
    };
    const stored = storedPackage(projectionWithRehearsal.packageDraft, 1);
    const navigate = vi.fn();
    api.fetchBusinessMirrorLegacyCatalog.mockResolvedValue({
      schemaVersion: 'resourceGateway.legacyGraphPackageProjectionCatalog.v1',
      scope: projection.scope,
      items: [projectionWithRehearsal],
    });
    api.fetchBusinessMirrorPackages.mockResolvedValue({
      schemaVersion: 'resourceGateway.domainCapabilityPackagePage.v1',
      items: [stored],
      nextCursor: '',
    });
    window.history.replaceState({}, '', '/business-mirror/?packageId=legacy%3AloanDecisionPolicy&task=problem');

    await render(undefined, navigate);
    await click(button('MIRROR_PLAN_MISSING'));
    await settleFrame();

    expect(navigate).toHaveBeenCalledWith(expect.stringContaining('/rehearsals/?'));
    const href = navigate.mock.calls[0]?.[0] as string;
    const target = new URL(href, window.location.origin);
    expect(target.searchParams.get('returnRoute')).toBe('business-mirror');
    expect(target.searchParams.get('returnPackageId')).toBe('legacy:loanDecisionPolicy');
    expect(target.searchParams.get('returnTask')).toBe('rehearsal');
    expect(target.searchParams.get('returnAnchor')).toBe('mirror-plan');
  });

  it('keeps a catalog outage distinct from no results and explains the safe fallback', async () => {
    const stored = storedPackage(projection.packageDraft, 1);
    api.fetchBusinessMirrorPackages.mockResolvedValue({
      schemaVersion: 'resourceGateway.domainCapabilityPackagePage.v1',
      items: [stored],
      nextCursor: '',
    });
    api.fetchBusinessMirrorReferenceCandidates.mockRejectedValue(
      Object.assign(new Error('catalog down'), { status: 'unavailable' }),
    );
    window.history.replaceState({}, '',
      '/business-mirror/?packageId=legacy%3AloanDecisionPolicy&task=problem');

    await render();
    await act(async () => referenceInput('Accountable owner').focus());
    await act(async () => new Promise((resolve) => window.setTimeout(resolve, 275)));

    expect(document.querySelector('[role="alert"]')?.textContent)
      .toContain('This deployment does not provide the required directory.');
    expect(document.body.textContent)
      .toContain('Keep the current exact binding or ask the platform owner');
    expect(document.body.textContent).not.toContain('No matching governed assets.');
  });

  it('imports durably, edits business fields, and asks the server for readiness', async () => {
    const stored = storedPackage(projection.packageDraft, 1);
    api.importBusinessMirrorLegacyPackage.mockResolvedValue(saveReceipt(stored));
    api.saveBusinessMirrorPackage.mockImplementation(async (draft: BusinessMirrorPackageDraft) =>
      saveReceipt(storedPackage(draft, 2)));
    api.compileBusinessMirrorPackage.mockResolvedValue({
      schemaVersion: 'resourceGateway.packageCompilationReceipt.v1',
      requestFingerprint: fingerprint('request'), packageId: stored.draft.packageId,
      sourceDraftRevision: 2, sourceDraftFingerprint: fingerprint('draft-2'),
      compilationRevision: 1, authorityGeneration: 'offline:v1', completedAt: now(), snapshot: null,
      readiness: {
        schemaVersion: 'resourceGateway.packageReadinessReport.v1', reportId: 'readiness:loan',
        revision: 1, fingerprint: fingerprint('readiness'), scope: stored.draft.scope,
        packageId: stored.draft.packageId, sourceDraftRevision: 2,
        sourceDraftFingerprint: fingerprint('draft-2'), status: 'BLOCKED', createdAt: now(),
        findings: [{
          findingId: 'finding:taxonomy', code: 'PROBLEM_TAXONOMY_MISSING', severity: 'ERROR',
          category: 'BUSINESS_DEFINITION', fieldPath: '/businessDefinition/problemTaxonomyRef',
          artifactRef: null, messageId: 'business-mirror.problem-taxonomy-missing',
        }],
      },
    });
    await render();
    await click(button('Loan Decision Policy'));
    await click(button('Import Package'));

    expect(api.importBusinessMirrorLegacyPackage)
      .toHaveBeenCalledWith('loanDecisionPolicy', 'business-mirror:import:loanDecisionPolicy:v1');
    expect(referenceInput('Business domain').disabled).toBe(false);
    await chooseReference('Business domain', 'Ride customer service');
    await change(input('Problem code'), 'loan-decision');
    await change(control('Service goal'), 'Explain every decision.');
    await change(control('Expected customer outcome'), 'Correct decisions with safe abstention.');
    await chooseReference('Accountable owner', 'Risk service owner');

    expect(document.body.textContent).toContain('Unsaved Package changes');
    await click(button('Save Package changes'));
    expect(api.saveBusinessMirrorPackage).toHaveBeenCalledOnce();
    expect(api.saveBusinessMirrorPackage).toHaveBeenCalledWith(expect.objectContaining({
      businessDefinition: expect.objectContaining({
        domainId: 'ride.customer-service',
        accountableOwner: 'risk-service-owner',
      }),
    }), expect.any(String));
    expect(document.body.textContent).toContain('Package changes saved as revision 2.');

    await click(button('Check readiness'));
    expect(api.compileBusinessMirrorPackage)
      .toHaveBeenCalledWith('legacy:loanDecisionPolicy', 2, expect.stringContaining('business-mirror:compile:r2:'));
    expect(document.body.textContent).toContain('PROBLEM_TAXONOMY_MISSING');
  });

  it('treats a governed binding on another Sheet as a Package change and saves its exact coordinate', async () => {
    const stored = storedPackage(projection.packageDraft, 1);
    api.fetchBusinessMirrorPackages.mockResolvedValue({
      schemaVersion: 'resourceGateway.domainCapabilityPackagePage.v1',
      items: [stored],
      nextCursor: '',
    });
    api.saveBusinessMirrorPackage.mockImplementation(async (draft: BusinessMirrorPackageDraft) =>
      saveReceipt(storedPackage(draft, 2)));
    window.history.replaceState({}, '',
      '/business-mirror/?packageId=legacy%3AloanDecisionPolicy&task=scenarios');

    await render();
    await chooseReference('Executable Scenario packs', 'Loan policy regression pack');

    expect(document.body.textContent).toContain('Unsaved Package changes');
    await click(button('Save Package changes'));
    expect(api.saveBusinessMirrorPackage).toHaveBeenCalledWith(expect.objectContaining({
      scenarioPackRefs: [expect.objectContaining({
        kind: 'SCENARIO_PACK',
        id: 'loan-regression',
        revision: 1,
      })],
    }), expect.any(String));
  });

  it('guards exact Author navigation until unsaved Package changes are secured', async () => {
    const stored = storedPackage(projection.packageDraft, 1);
    const navigate = vi.fn();
    api.fetchBusinessMirrorPackages.mockResolvedValue({
      schemaVersion: 'resourceGateway.domainCapabilityPackagePage.v1', items: [stored], nextCursor: '',
    });
    api.saveBusinessMirrorPackage.mockImplementation(async (draft: BusinessMirrorPackageDraft) =>
      saveReceipt(storedPackage(draft, 2)));
    window.history.replaceState({}, '',
      '/business-mirror/?packageId=legacy%3AloanDecisionPolicy&task=capabilities');

    await render(undefined, navigate);
    await chooseReference('L3 Application', 'Customer support chat');
    await click(anchor('Open exact Graph'));

    expect(document.querySelector('[data-testid="workspace-leave-dialog"]')).not.toBeNull();
    expect(navigate).not.toHaveBeenCalled();
    await click(button('Save and leave'));

    expect(api.saveBusinessMirrorPackage).toHaveBeenCalledOnce();
    expect(navigate).toHaveBeenCalledWith(expect.stringContaining('/author/'));
    expect(String(navigate.mock.calls[0]?.[0])).not.toContain('/showcase/');
  });

  it('renders the same fixed task in Chinese and exposes keyboard-native controls', async () => {
    window.history.replaceState({}, '', '/business-mirror/?lang=zh-CN');
    await render();

    expect(document.body.textContent).toContain('业务能力资产组合');
    await click(button('贷款决策策略'));
    expect(document.body.textContent).toContain('1. 定义问题');
    expect(document.querySelector('.business-mirror-task-rail button[aria-current="step"]'))
      .toBeInstanceOf(HTMLButtonElement);
    expect(button('导入能力包').getAttribute('type')).toBe('button');
  });

  it('opens an impact deep link on Capability Map and highlights the exact target', async () => {
    window.history.replaceState({}, '', '/business-mirror/?packageId=legacy%3AloanDecisionPolicy'
      + '&compilationRevision=7&task=capabilities&assetKind=RESOURCE&assetId=trip-api'
      + '&assetRevision=3&assetAuthority=customer-registry');

    await render();

    expect(document.body.textContent).toContain('Which L0-L3 assets form the complete path');
    expect(document.body.textContent).toContain('1 of 4 ready');
    expect(document.body.textContent).toContain('Impact target located');
    expect(document.body.textContent).toContain('Package compilation r7');
    const focused = document.querySelector('[data-focused-asset="true"]');
    expect(focused?.textContent).toContain('trip-api');
    expect(focused?.closest('.capability-layer')?.textContent).toContain('L0 Foundation');

    await click(button('Next: 4. Freeze scenarios'));
    expect(new URLSearchParams(window.location.search).get('task')).toBe('scenarios');
    expect(document.body.textContent).toContain('Which business branches must always be covered');
  });

  it('opens the exact source Graph in Author Compose instead of the run Showcase', async () => {
    await render();
    await click(button('Loan Decision Policy'));
    await click(button('3. Assemble capabilities'));

    const href = anchor('Open exact Graph').href;
    const url = new URL(href);
    expect(url.pathname).toBe('/author/');
    expect(url.searchParams.get('authorMode')).toBe('compose');
    expect(url.searchParams.get('sourceGraphName')).toBe('loanDecisionPolicy');
    expect(url.searchParams.get('sourceId')).toBe(projection.sourceGraphRef.id);
    expect(url.searchParams.get('sourceRevision')).toBe(String(projection.sourceGraphRef.revision));
    expect(url.searchParams.get('sourceFingerprint')).toBe(projection.sourceGraphRef.fingerprint);
    expect(url.searchParams.get('returnPackageId')).toBe(projection.packageDraft.packageId);
    expect(href).not.toContain('showcase');
    expect(api.resolveBusinessMirrorAuthorLink).toHaveBeenCalledWith({
      graphName: 'loanDecisionPolicy',
      graphRef: projection.sourceGraphRef,
      packageId: projection.packageDraft.packageId,
    });
  });

  it('fails closed and offers retry when exact Author link resolution fails', async () => {
    api.resolveBusinessMirrorAuthorLink.mockRejectedValueOnce(new Error('resolver unavailable'));
    await render();
    await click(button('Loan Decision Policy'));
    await click(button('3. Assemble capabilities'));
    await act(async () => Promise.resolve());

    expect(document.body.textContent).toContain('You remain on this Package');
    expect(document.body.textContent).toContain('resolver unavailable');
    expect(document.querySelector('a[href*="/author/"]')).toBeNull();

    await click(button('Retry'));
    await act(async () => Promise.resolve());
    expect(anchor('Open exact Graph')).toBeInstanceOf(HTMLAnchorElement);
  });

  it('keeps five evidence layers and seven Fidelity dimensions independently inspectable', async () => {
    const stored = storedPackage(projection.packageDraft, 3);
    const index = evidenceIndex(stored);
    api.fetchBusinessMirrorPackages.mockResolvedValue({
      schemaVersion: 'resourceGateway.domainCapabilityPackagePage.v1',
      items: [stored],
      nextCursor: '',
    });
    api.fetchBusinessMirrorPackageEvidence.mockResolvedValue(index);
    api.fetchBusinessMirrorDomainEvidencePortfolio.mockResolvedValue(evidencePortfolio(index));
    api.acknowledgeBusinessMirrorEvidenceTask.mockResolvedValue(undefined);
    window.history.replaceState({}, '', '/business-mirror/?packageId=legacy%3AloanDecisionPolicy&task=evidence');

    await render();
    await act(async () => Promise.resolve());

    expect(document.body.textContent).toContain('What current evidence proves each layer');
    expect(document.body.textContent).toContain('0 of 2 ready');
    expect(document.querySelectorAll('.business-mirror-evidence-layers article')).toHaveLength(5);
    expect(document.querySelectorAll('.business-mirror-fidelity-table [role="row"]')).toHaveLength(8);
    expect(document.body.textContent).toContain('20%-100%');
    expect(document.body.textContent).not.toContain('Overall score');

    await click(button('Acknowledge'));
    expect(api.acknowledgeBusinessMirrorEvidenceTask).toHaveBeenCalledWith('task:fidelity', 1);
  });

  it('offers an explicitly isolated reference projection when current evidence is absent', async () => {
    const stored = storedPackage(projection.packageDraft, 3);
    api.fetchBusinessMirrorPackages.mockResolvedValue({
      schemaVersion: 'resourceGateway.domainCapabilityPackagePage.v1',
      items: [stored],
      nextCursor: '',
    });
    api.fetchBusinessMirrorPackageEvidence.mockRejectedValue(
      new api.BlogeApiRequestError(404, 'RG.BUSINESS_MIRROR.EVIDENCE_INDEX_NOT_FOUND'),
    );
    window.history.replaceState({}, '', '/business-mirror/?packageId=legacy%3AloanDecisionPolicy&task=evidence');

    await render();
    expect(document.body.textContent).toContain('No evidence projection exists yet');
    await click(button('Open reference evidence'));

    expect(document.body.textContent).toContain('Read-only protocol reference');
    expect(document.body.textContent).toContain('this is not evidence for the selected Package');
    expect(document.querySelectorAll('.business-mirror-evidence-layers article')).toHaveLength(5);
    expect(button('Return to current Package')).toBeInstanceOf(HTMLButtonElement);
  });

  async function render(
    telemetry?: import('../shared/guided-telemetry/guidedTelemetry').GuidedAuthoringTelemetry,
    navigate: (href: string) => void = () => undefined,
  ) {
    await act(async () => root?.render(
      <I18nProvider>
        <SafeWorkspaceNavigationProvider navigate={navigate}>
          <BusinessMirrorWorkspace telemetry={telemetry} />
        </SafeWorkspaceNavigationProvider>
      </I18nProvider>,
    ));
    await act(async () => Promise.resolve());
  }
});

function legacyProjection(): LegacyGraphPackageProjection {
  const scope = {
    tenantId: 'ride-hailing', organizationId: 'customer-service', projectId: 'loan',
    environmentId: 'test', region: 'sg',
  };
  const graph = ref('GRAPH_DRAFT', 'built-in:loanDecisionPolicy');
  const contract = ref('CONTRACT', 'built-in:loanDecisionPolicy:contract');
  const capability = ref('CAPABILITY', 'graph:loanDecisionPolicy');
  const suite = ref('TEST_SUITE', 'loan-decision-smoke');
  const draft: BusinessMirrorPackageDraft = {
    schemaVersion: 'bloge.domainCapabilityPackageDraft.v1', packageId: 'legacy:loanDecisionPolicy',
    revision: 0, scope,
    businessDefinition: {
      domainId: '', problemTaxonomyRef: null, problemCode: '', businessGoal: '', expectedOutcome: '',
      riskClass: 'CRITICAL', accountableOwner: '', collaboratingOwners: [],
    },
    packageContractRef: contract, capabilityRefs: [], graphRefs: [graph], proposalRefs: [],
    stateModelRefs: [], effectModelRefs: [], scenarioInventoryRef: null, scenarioPackRefs: [],
    solutionRefs: [], carrierRefs: [], channelRefs: [], fidelityInventoryRef: null,
    outcomeDefinitionRefs: [], limitations: [], assumptions: [], expiresAt: null,
    provenance: {
      schemaVersion: 'resourceGateway.artifactProvenance.v1', sourceType: 'INFERRED',
      sourceRefs: [graph, contract, capability, suite], tenantId: scope.tenantId,
      purpose: 'BUSINESS_MIRROR_LEGACY_MIGRATION', sampleFrom: null, sampleTo: null,
      sampleCount: null, confidence: null, biasRisks: [], approvedBy: '', approvedAt: null,
      expiresAt: null, revocationRef: '',
    }, lifecycle: 'DRAFT',
  };
  return {
    schemaVersion: 'resourceGateway.legacyGraphPackageProjection.v1',
    projectorVersion: 'legacy-graph-package-projector-v1', migrationMode: 'LEGACY_IMPORTED',
    graphName: 'loanDecisionPolicy', scope, sourceGraphRef: graph, sourceContractRef: contract,
    projectedCapabilityRef: capability, capabilityClosureRef: ref('CAPABILITY_CLOSURE', 'graph:loanDecisionPolicy'),
    discoveredTestSuiteRefs: [suite], packageDraft: draft,
    gaps: [
      { code: 'ACCOUNTABLE_OWNER_MISSING', origin: 'PACKAGE_READINESS', category: 'BUSINESS_CONTEXT',
        severity: 'BLOCKING', draftPath: '/businessDefinition/accountableOwner', explanation: 'missing',
        requiredAction: 'assign', evidenceRefs: [] },
      { code: 'PROBLEM_TAXONOMY_MISSING', origin: 'PACKAGE_READINESS', category: 'BUSINESS_CONTEXT',
        severity: 'BLOCKING', draftPath: '/businessDefinition/problemTaxonomyRef', explanation: 'missing',
        requiredAction: 'bind', evidenceRefs: [] },
      { code: 'SCENARIO_PACK_MISSING', origin: 'PACKAGE_READINESS', category: 'SCENARIO',
        severity: 'BLOCKING', draftPath: '/scenarioPackRefs', explanation: 'missing',
        requiredAction: 'author', evidenceRefs: [suite] },
    ],
    status: 'BLOCKED', projectionFingerprint: fingerprint('projection'),
  };
}

function authoringLinkDescriptor() {
  return {
    schemaVersion: 'bloge.authoringLinkDescriptor.v1' as const,
    resolution: 'READ_ONLY_SOURCE' as const,
    route: {
      path: '/author/' as const,
      workspace: 'v2' as const,
      authorMode: 'compose' as const,
      query: {
        authorWorkspace: 'v2', authorMode: 'compose',
        sourceKind: 'BUSINESS_MIRROR_LEGACY_GRAPH', sourceGraphName: 'loanDecisionPolicy',
        sourceId: projectionRef().id, sourceRevision: String(projectionRef().revision),
        sourceFingerprint: projectionRef().fingerprint,
        returnRoute: 'business-mirror', returnPackageId: 'legacy:loanDecisionPolicy',
        returnTask: 'capabilities', returnAnchor: `graph:${projectionRef().id}`,
      },
    },
  };
}

function projectionRef() {
  return ref('GRAPH_DRAFT', 'built-in:loanDecisionPolicy');
}

function storedPackage(draft: BusinessMirrorPackageDraft, revision: number): StoredBusinessMirrorPackage {
  const exactDraft = { ...structuredClone(draft), revision };
  return {
    schemaVersion: 'resourceGateway.storedDomainCapabilityPackageDraft.v1',
    draftFingerprint: fingerprint(`draft-${revision}`), draft: exactDraft,
    createdAt: now(), updatedAt: now(), updatedBy: 'author',
  };
}

function saveReceipt(stored: StoredBusinessMirrorPackage) {
  return {
    schemaVersion: 'resourceGateway.domainCapabilityPackageSaveReceipt.v1',
    requestFingerprint: fingerprint('request'), result: stored, completedAt: now(),
  };
}

function evidenceIndex(stored: StoredBusinessMirrorPackage): BusinessMirrorPackageEvidenceIndex {
  const source = {
    kind: 'DOMAIN_CAPABILITY_PACKAGE', id: stored.draft.packageId,
    coordinate: 'revision:3', fingerprint: fingerprint('source'),
  };
  const dimensions = [
    'BEHAVIOR', 'CONTRACT', 'EFFECT', 'ERROR_DISTRIBUTION',
    'OUTCOME', 'REQUEST_SPACE', 'STATE_TRANSITION',
  ].map((dimension) => ({
    dimension,
    state: 'MEASURED',
    metric: {
      requiredUnits: 1, freshEvidenceUnits: 1, passedUnits: 1, failedUnits: 0,
      abstainedUnits: 0, staleUnits: 0, missingUnits: 0, coverageRatio: 1,
      abstentionRatio: 0, confidence: {
        point: 1, lowerBound: 0.2, upperBound: 1, method: 'WILSON_95_V1',
      }, sufficiency: 'MEASURED',
    },
    sourceLineage: [source],
  }));
  const layerNames = [
    'L0_RESOURCE', 'L1_SERVICE_DESIGN', 'L2_SERVICE_CARRIER', 'L3_APPLICATION', 'CALIBRATION',
  ] as const;
  return {
    schemaVersion: 'resourceGateway.packageEvidenceIndex.v1',
    indexFingerprint: fingerprint('index'), scope: stored.draft.scope,
    packageId: stored.draft.packageId, compilationRevision: 7, projectionRevision: 1,
    domainId: 'ride.customer-service', problemCode: 'LOAN.DECISION',
    layers: layerNames.map((layer, position) => ({
      layer,
      conclusions: [{
        conclusionId: `conclusion:${position}`, layer, evidenceKind: 'CONTRACT',
        proofStrength: 'COMPILED', state: 'AVAILABLE', subject: source,
        sourceLineage: [source], observedAt: null, validUntil: null, limitationCode: '',
      }],
    })),
    fidelity: {
      state: 'CURRENT', inventorySource: source, profileSource: source,
      measuredAt: now(), validUntil: now(), denominator: { totalUnits: 1, totalObligations: 7 },
      dimensions, abstentionDebt: {
        totalObligations: 7, abstainedObligations: 0, ratio: 0, reasons: [],
      },
      sourceComposition: {
        totalUnits: 1, recordedUnits: 0, synthesizedUnits: 0, ownerDeclaredUnits: 0,
        authoritativeUnits: 1, unknownUnits: 0, synthesizedRatio: 0, unknownRatio: 0,
      },
      assessment: 'COMPLETE', limitations: [], sourceLineage: [source],
    },
    driftSignals: [], projectedAt: now(), validUntil: now(),
  };
}

function evidencePortfolio(
  index: BusinessMirrorPackageEvidenceIndex,
): BusinessMirrorDomainEvidencePortfolio {
  const task = {
    schemaVersion: 'resourceGateway.evidenceOwnerTask.v1', taskFingerprint: fingerprint('task'),
    taskId: 'task:fidelity', version: 1, scope: index.scope, packageId: index.packageId,
    compilationRevision: index.compilationRevision, projectionRevision: index.projectionRevision,
    domainId: index.domainId, reason: 'FIDELITY_PROFILE_STALE', severity: 'ERROR' as const,
    owner: 'risk-service-owner', status: 'OPEN' as const,
    sourceLineage: index.fidelity.sourceLineage, detectedAt: now(), dueAt: now(), updatedAt: now(),
    actedBy: '', resolutionEvidenceRef: null, deepLink: '/business-mirror/?task=evidence',
  };
  return {
    schemaVersion: 'resourceGateway.domainEvidencePortfolio.v1',
    portfolioFingerprint: fingerprint('portfolio'), scope: index.scope, domainId: index.domainId,
    packages: [{
      packageId: index.packageId, compilationRevision: index.compilationRevision,
      projectionRevision: index.projectionRevision, evidenceIndexFingerprint: index.indexFingerprint,
      problemCode: index.problemCode, freshness: index.fidelity.state,
      layers: index.layers.map((layer) => ({
        layer: layer.layer, conclusionCount: layer.conclusions.length,
        states: [{ state: 'AVAILABLE', count: layer.conclusions.length }],
        proofComposition: [{ proof: 'COMPILED', count: layer.conclusions.length }],
      })),
      fidelity: index.fidelity, ownerTasks: [task], deepLink: task.deepLink,
    }],
    nextCursor: '', generatedAt: now(),
  };
}

function demoReference(kind: string): ReferenceCandidate {
  const values: Record<string, [string, string]> = {
    BUSINESS_DOMAIN: ['ride.customer-service', 'Ride customer service'],
    OWNER: ['risk-service-owner', 'Risk service owner'],
    PROBLEM_TAXONOMY: ['loan-decision-problems', 'Loan decision problems'],
    PACKAGE_CONTRACT: ['loan-decision-contract-v1', 'Loan decision package contract'],
    STATE_MODEL: ['loan-decision-state-v1', 'Loan decision state model'],
    EFFECT_MODEL: ['loan-decision-effect-v1', 'Loan decision effect model'],
    SOLUTION: ['loan-decision-solution', 'Loan decision solution'],
    SERVICE_CARRIER: ['loan-policy-agent', 'Loan policy service carrier'],
    CHANNEL: ['support-chat', 'Customer support chat'],
    SCENARIO_INVENTORY: ['loan-obligations', 'Loan policy scenario inventory'],
    SCENARIO_PACK: ['loan-regression', 'Loan policy regression pack'],
    FIDELITY_INVENTORY: ['loan-fidelity', 'Loan policy fidelity inventory'],
    OUTCOME_DEFINITION: ['loan-outcomes', 'Loan decision outcomes'],
  };
  const [id, displayName] = values[kind] ?? [`${kind.toLowerCase()}-id`, kind];
  const candidateKind = kind === 'SERVICE_CARRIER' ? 'AGENT'
    : kind === 'CHANNEL' ? 'CHANNEL_APPLICATION' : kind;
  return {
    schemaVersion: 'bloge.referenceCandidate.v1', kind: candidateKind, id, displayName,
    description: `${displayName} description`, revision: 1,
    fingerprint: fingerprint(`${candidateKind}:${id}`), authority: 'test://business-catalog',
    scope: {
      tenantId: 'tenant-a', organizationId: 'knowledge-governance',
      projectId: 'tool-studio', environmentId: 'test', region: 'local',
    },
    lifecycle: 'ACTIVE',
    owner: { stableId: 'support-platform', displayName: 'Support platform' },
    labels: ['demo'], compatibility: 'COMPATIBLE', disabledReasonCode: '',
  };
}

function referencePage(items: readonly ReferenceCandidate[]): ReferencePage {
  return {
    schemaVersion: 'bloge.referencePage.v1', items, nextCursor: null,
    queryFingerprint: fingerprint('query'), catalogGeneration: 1,
  };
}

function ref(kind: string, id: string) {
  return { kind, id, revision: 1, fingerprint: fingerprint(`${kind}:${id}`) };
}

function fingerprint(seed: string): string {
  return `sha256:${seed.padEnd(64, 'a').slice(0, 64).replace(/[^a-f0-9]/g, 'a')}`;
}

function now(): string {
  return '2026-08-14T00:00:00Z';
}

function button(text: string): HTMLButtonElement {
  const match = [...document.querySelectorAll<HTMLButtonElement>('button')]
    .find((candidate) => candidate.textContent?.includes(text));
  if (!match) throw new Error(`Missing button: ${text}`);
  return match;
}

function anchor(text: string): HTMLAnchorElement {
  const match = [...document.querySelectorAll<HTMLAnchorElement>('a')]
    .find((candidate) => candidate.textContent?.includes(text));
  if (!match) throw new Error(`Missing anchor: ${text}`);
  return match;
}

function input(label: string): HTMLInputElement {
  const match = [...document.querySelectorAll<HTMLLabelElement>('label')]
    .find((candidate) => candidate.textContent?.includes(label))
    ?.querySelector<HTMLInputElement>('input');
  if (!match) throw new Error(`Missing input: ${label}`);
  return match;
}

function referenceInput(label: string): HTMLInputElement {
  const match = document.querySelector<HTMLInputElement>(
    `input[role="combobox"][aria-label="${label}"]`,
  );
  if (!match) throw new Error(`Missing reference input: ${label}`);
  return match;
}

function control(label: string): HTMLInputElement | HTMLTextAreaElement {
  const match = [...document.querySelectorAll<HTMLLabelElement>('label')]
    .find((candidate) => candidate.textContent?.includes(label))
    ?.querySelector<HTMLInputElement | HTMLTextAreaElement>('input, textarea');
  if (!match) throw new Error(`Missing control: ${label}`);
  return match;
}

async function click(element: HTMLElement) {
  await act(async () => element.click());
  await act(async () => Promise.resolve());
}

async function change(element: HTMLInputElement | HTMLTextAreaElement, value: string) {
  await act(async () => {
    const prototype = element instanceof HTMLTextAreaElement
      ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    const setter = Object.getOwnPropertyDescriptor(prototype, 'value')?.set;
    setter?.call(element, value);
    element.dispatchEvent(new Event('input', { bubbles: true }));
  });
}

async function chooseReference(label: string, optionName: string) {
  const picker = referenceInput(label);
  await act(async () => picker.focus());
  await act(async () => new Promise((resolve) => window.setTimeout(resolve, 275)));
  const option = [...document.querySelectorAll<HTMLElement>('[role="option"]')]
    .find((candidate) => candidate.textContent?.includes(optionName));
  if (!option) throw new Error(`Missing reference option: ${optionName}`);
  await act(async () => {
    option.click();
    await Promise.resolve();
    await Promise.resolve();
  });
}

async function settleFrame() {
  await act(async () => new Promise((resolve) => window.requestAnimationFrame(() => resolve(undefined))));
}
