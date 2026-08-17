import { describe, expect, it } from 'vitest';

import { parseCapabilityStudioDemoPack, parseScenarioDatasetProjection } from './domain';
import { scenarioDatasetProjectionFixture } from './testFixtures';

describe('Capability Studio backend projection adapter', () => {
  it('adapts exact refs, business contract summaries, and governed scenario metadata', () => {
    const result = parseCapabilityStudioDemoPack(backendProjection());

    expect(result.capability.name).toBe('取消费用争议能力演示包');
    expect(result.capability.owner).toBe('客服技术平台');
    expect(result.capability.fingerprint).toMatch(/^sha256:/);
    expect(result.assets.apis).toHaveLength(4);
    expect(result.assets.apis[0]).toMatchObject({
      technicalRef: 'API:api-order-lookup@1',
      fingerprint: `sha256:${'1'.repeat(64)}`,
      contract: {
        successResult: [{ name: 'order.status', type: 'contract output' }],
        sideEffects: ['READ_ONLY'],
        sla: 'P95 <= 300ms',
        sensitivity: 'NO_SENSITIVE_FIELDS_DECLARED',
      },
    });
    expect(result.scenarios).toHaveLength(9);
    expect(result.scenarios[0]).toMatchObject({
      source: '业务案例来源',
      owner: '客服技术平台',
      oracle: '业务结论校验器',
      contractCount: 1,
      technicalRef: 'SCENARIO:case-1@1',
    });
    expect(result.baseline).toMatchObject({ name: 'Canonical Baseline', status: 'IMMUTABLE' });
    expect(result.tutorialBranch).toMatchObject({ name: 'Tutorial Branch', status: 'ISOLATED_NOT_RUN' });
  });

  it('strictly parses all nine Dataset cases and preserves complete reference closure', () => {
    const result = parseScenarioDatasetProjection(scenarioDatasetProjectionFixture);

    expect(result.cases).toHaveLength(9);
    expect(result.datasetRef.scope).toMatchObject({ tenantId: 'tenant-demo', environmentId: 'demo' });
    expect(result.cases.every((scenario) => scenario.caseRef.scope.region === 'ap-southeast-1')).toBe(true);
    expect(result.cases[4].behaviorProfiles[0].behavior).toBe('TIMEOUT');
  });

  it('rejects unknown fields and incomplete exact-ref scope instead of accepting a partial projection', () => {
    expect(() => parseScenarioDatasetProjection({ ...scenarioDatasetProjectionFixture, unexpected: true })).toThrow('INVALID_SCENARIO_DATASET');
    const incompleteScope = structuredClone(scenarioDatasetProjectionFixture);
    delete (incompleteScope.cases[0].caseRef.scope as { region?: string }).region;
    expect(() => parseScenarioDatasetProjection(incompleteScope)).toThrow('scenarioDataset.cases[0].caseRef.scope.region');
  });

  it('rejects cross-scope and contract-closure violations before rendering governed cases', () => {
    const crossScope = structuredClone(scenarioDatasetProjectionFixture);
    crossScope.cases[0].sourceRef.scope = {
      ...crossScope.cases[0].sourceRef.scope,
      environmentId: 'production',
    };
    expect(() => parseScenarioDatasetProjection(crossScope)).toThrow('cross-scope reference');

    const brokenClosure = structuredClone(scenarioDatasetProjectionFixture);
    brokenClosure.cases[0].applicableContractRefs[0].id = 'unknown-contract';
    expect(() => parseScenarioDatasetProjection(brokenClosure)).toThrow('contract closure is incomplete');
  });

  it('rejects duplicate governed identities and quality summaries that drift from case content', () => {
    const duplicateCase = structuredClone(scenarioDatasetProjectionFixture);
    duplicateCase.cases[1].caseRef.id = duplicateCase.cases[0].caseRef.id;
    expect(() => parseScenarioDatasetProjection(duplicateCase)).toThrow('duplicate case reference');

    const qualityDrift = structuredClone(scenarioDatasetProjectionFixture);
    qualityDrift.quality.activeCaseCount = 7;
    expect(() => parseScenarioDatasetProjection(qualityDrift)).toThrow('quality metrics do not match');
  });

  it('fails closed when an active case or active Dataset is not readiness-complete', () => {
    const incompleteCase = structuredClone(scenarioDatasetProjectionFixture);
    incompleteCase.cases[0].qualityState = 'DESIGNED_NOT_RUN';
    expect(() => parseScenarioDatasetProjection(incompleteCase)).toThrow('incomplete active case');

    const blockedDataset = structuredClone(scenarioDatasetProjectionFixture);
    blockedDataset.quality.status = 'BLOCKED';
    expect(() => parseScenarioDatasetProjection(blockedDataset)).toThrow('Active Scenario Dataset is not ready');
  });
});

function backendProjection() {
  const owner = { id: 'customer-service-platform', name: '客服技术平台' };
  const ref = (kind: string, id: string, seed: string) => ({
    kind,
    id,
    revision: 1,
    fingerprint: `sha256:${seed.repeat(64).slice(0, 64)}`,
  });
  const contract = {
    inputs: [{
      name: 'orderId',
      label: '订单 ID',
      type: 'string',
      required: true,
      sensitive: false,
      description: '订单标识',
    }],
    successOutputs: ['order.status'],
    errors: [{ code: 'ORDER_NOT_FOUND', meaning: '订单不存在', retryable: false, suggestedAction: '转人工' }],
  };
  const capability = (kind: string, id: string, name: string, seed: string) => ({
    id,
    name,
    kind,
    description: `${name}业务说明`,
    ref: ref(kind, id, seed),
    owner,
    contractRef: ref('CONTRACT', `contract-${id}`, 'a'),
    contract,
    sideEffect: 'READ_ONLY',
    sla: 'P95 <= 300ms',
    readiness: 'RUNTIME_EVIDENCE_PENDING',
    dependencyRefs: [],
  });
  return {
    packId: 'cancellation-fee-capability-studio-golden-v1',
    revision: 1,
    packFingerprint: `sha256:${'f'.repeat(64)}`,
    displayName: '取消费用争议能力演示包',
    summary: '基于取消争议上下文输出可解释的费用处理建议。',
    owner,
    readiness: 'METADATA_READY_RUNTIME_EVIDENCE_PENDING',
    canonicalBaseline: {
      id: 'baseline-v1',
      name: 'Canonical Baseline',
      purpose: 'Immutable exact-reference baseline for repeatable review',
      status: 'IMMUTABLE',
      ref: ref('BASELINE', 'baseline-v1', 'b'),
      assetCount: 6,
      scenarioCount: 9,
    },
    tutorialBranch: {
      id: 'tutorial-v1',
      name: 'Tutorial Branch',
      purpose: 'Controlled timeout branch',
      status: 'ISOLATED_NOT_RUN',
      ref: ref('BRANCH', 'tutorial-v1', 'c'),
      baseBaselineRef: ref('BASELINE', 'baseline-v1', 'b'),
      behaviorOverrides: [],
    },
    cardinality: { api: 4, feature: 1, tool: 1, scenarios: 9 },
    apiCapabilities: [
      capability('API', 'api-order-lookup', '订单信息查询', '1'),
      capability('API', 'api-responsibility', '取消责任判定', '2'),
      capability('API', 'api-pricing-policy', '城市计价政策查询', '3'),
      capability('API', 'api-compensation-history', '补偿历史查询', '4'),
    ],
    featureCapabilities: [capability('FEATURE', 'feature-context', '取消争议上下文', '5')],
    toolCapabilities: [capability('TOOL', 'tool-resolution', '取消费用争议处理', '6')],
    scenarios: Array.from({ length: 9 }, (_, index) => ({
      id: `case-${index + 1}`,
      name: `业务场景 ${index + 1}`,
      ref: ref('SCENARIO', `case-${index + 1}`, '7'),
      owner,
      source: { displayName: '业务案例来源', type: 'BUSINESS_CASE' },
      oracle: { displayName: '业务结论校验器', summary: '校验预期结果' },
      applicableContractCount: 1,
      category: index === 0 ? 'GOLDEN' : 'REGRESSION',
      expectedResult: '返回可解释业务结论',
      lifecycle: 'ACTIVE',
      qualityState: 'DESIGNED_NOT_RUN',
    })),
  };
}
