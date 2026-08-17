// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import I18nProvider from '../i18n/I18nProvider';
import BusinessMirrorWorkspace from './BusinessMirrorWorkspace';
import type {
  BusinessMirrorDomainEvidencePortfolio,
  BusinessMirrorPackageDraft,
  BusinessMirrorPackageEvidenceIndex,
  LegacyGraphPackageProjection,
  StoredBusinessMirrorPackage,
} from './domain';

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
    expect(input('Business domain').closest('fieldset')?.disabled).toBe(true);
    expect(document.querySelectorAll('.business-mirror-task-rail button')).toHaveLength(7);
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
    expect(input('Business domain').disabled).toBe(false);
    await change(input('Business domain'), 'ride.customer-service');
    await change(input('Problem code'), 'loan-decision');
    await change(control('Service goal'), 'Explain every decision.');
    await change(control('Expected customer outcome'), 'Correct decisions with safe abstention.');
    await change(input('Accountable owner'), 'risk-service-owner');

    expect(document.body.textContent).toContain('Unsaved business changes');
    await click(button('Save business definition'));
    expect(api.saveBusinessMirrorPackage).toHaveBeenCalledOnce();
    expect(document.body.textContent).toContain('Business definition saved as revision 2.');

    await click(button('Check readiness'));
    expect(api.compileBusinessMirrorPackage)
      .toHaveBeenCalledWith('legacy:loanDecisionPolicy', 2, expect.stringContaining('business-mirror:compile:r2:'));
    expect(document.body.textContent).toContain('PROBLEM_TAXONOMY_MISSING');
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

    expect(document.body.textContent).toContain('L0-L3 capability map');
    expect(document.body.textContent).toContain('Impact target located');
    expect(document.body.textContent).toContain('Package compilation r7');
    const focused = document.querySelector('[data-focused-asset="true"]');
    expect(focused?.textContent).toContain('trip-api');
    expect(focused?.closest('.capability-layer')?.textContent).toContain('L0 Foundation');
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

    expect(document.body.textContent).toContain('Package evidence and Fidelity');
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

  async function render() {
    await act(async () => root?.render(<I18nProvider><BusinessMirrorWorkspace /></I18nProvider>));
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
