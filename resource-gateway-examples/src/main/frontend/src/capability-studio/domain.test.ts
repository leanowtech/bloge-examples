import { describe, expect, it } from 'vitest';

import {
  parseCapabilityStudioDemoPack,
  parseFeatureRehearsalProjection,
  parseGovernedBaselineProjection,
  parseScenarioDatasetProjection,
} from './domain';
import {
  featureRehearsalProjectionFixture,
  governedBaselineProjectionFixture,
  scenarioDatasetProjectionFixture,
} from './testFixtures';

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
    expect(result.cases[4].behaviorProfiles).toEqual(expect.arrayContaining([
      expect.objectContaining({ behavior: 'TIMEOUT', purpose: 'RUNTIME_CONTROL' }),
    ]));
  });

  it('does not accept a business expectation as runtime-control quality closure', () => {
    const expectationOnly = structuredClone(scenarioDatasetProjectionFixture);
    expectationOnly.cases[0].behaviorProfiles.forEach((profile) => {
      (profile as { purpose: string }).purpose = 'BUSINESS_EXPECTATION';
    });
    expect(() => parseScenarioDatasetProjection(expectationOnly)).toThrow(
      'incomplete active case',
    );
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

  it('strictly parses the real Trace-shaped Feature rehearsal in both permission modes', () => {
    const structure = parseFeatureRehearsalProjection(featureRehearsalProjectionFixture());
    const payload = parseFeatureRehearsalProjection(featureRehearsalProjectionFixture('PAYLOAD_VISIBLE'));

    expect(structure.run).toMatchObject({ status: 'TIMED_OUT', realExternalCallCount: 0 });
    expect(structure.dataLens.nodes).toHaveLength(6);
    expect(structure.dataLens.edges).toHaveLength(5);
    expect(structure.dataLens.nodes.every((node) => node.input === null && node.output === null)).toBe(true);
    expect(payload.dataLens.nodes.find((node) => node.nodeId === 'orderLookup')?.input)
      .toMatchObject({ resourceId: 'api-order-lookup' });
    expect(payload.dataLens.nodes.find((node) => node.nodeId === 'compensationHistoryLookup'))
      .toMatchObject({ status: 'TIMEOUT', errorCode: 'COMPENSATION_HISTORY_TIMEOUT' });
  });

  it('rejects Feature rehearsal schema drift, payload leakage, and broken Trace identity', () => {
    const unknown = structuredClone(featureRehearsalProjectionFixture());
    (unknown.dataLens.nodes[0] as Record<string, unknown>).inventedSummary = 'not in v1';
    expect(() => parseFeatureRehearsalProjection(unknown)).toThrow('Unknown field');

    const leaked = structuredClone(featureRehearsalProjectionFixture());
    leaked.dataLens.nodes[0].input = { orderId: 'leaked' };
    expect(() => parseFeatureRehearsalProjection(leaked)).toThrow('STRUCTURE_ONLY cannot contain payload');

    const drift = structuredClone(featureRehearsalProjectionFixture());
    drift.dataLens.runId = 'test-run-another-case';
    expect(() => parseFeatureRehearsalProjection(drift)).toThrow('Run and Data Lens identity do not match');

    const brokenEdge = structuredClone(featureRehearsalProjectionFixture());
    brokenEdge.dataLens.edges[0].toInvocationSite = '/root/unknown#PRIMARY';
    expect(() => parseFeatureRehearsalProjection(brokenEdge)).toThrow('unknown invocation site');
  });
});

describe('Capability Studio governed baseline protocol', () => {
  it('parses the complete nine-case, three-round governed evidence projection', () => {
    const result = parseGovernedBaselineProjection(governedBaselineProjectionFixture);

    expect(result.status).toBe('PASSED');
    if (result.status !== 'PASSED') throw new Error('Expected a passed governed baseline fixture.');
    expect(result.caseCount).toBe(9);
    expect(result.roundCount).toBe(3);
    expect(result.suiteRunCount).toBe(3);
    expect(result.childRunCount).toBe(27);
    expect(result.oraclePassCount).toBe(9);
    expect(result.businessCheckPassCount).toBe(27);
    expect(result.verificationLevel).toBe('DEVELOPMENT_VERIFIED');
    expect(result.candidateBuild?.artifactFingerprint).toMatch(/^sha256:/);
    expect(result.candidateIntentFingerprint).toMatch(/^sha256:/);
    expect(result.publication.suiteRef.kind).toBe('TEST_SUITE');
    expect(new Set(result.rounds.map((round) => round.suiteRunId)).size).toBe(3);
    expect(new Set(result.cases.flatMap((entry) => entry.rounds.map((round) => round.runId))).size).toBe(27);
  });

  it('accepts a closed failure while preserving the required limitations', () => {
    const failed = structuredClone(governedBaselineProjectionFixture) as Record<string, unknown>;
    failed.status = 'FAILED_CLOSED';
    failed.suiteRunCount = 0;
    failed.childRunCount = 0;
    failed.oraclePassCount = 0;
    failed.businessCheckCount = 0;
    failed.businessCheckPassCount = 0;
    failed.verificationLevel = 'NOT_VERIFIED';
    failed.evidenceClass = null;
    failed.realExternalCallCount = null;
    failed.compilationFingerprint = null;
    failed.sourceMapFingerprint = null;
    failed.provenanceFingerprint = null;
    failed.candidateIntentFingerprint = null;
    failed.publication = null;
    failed.rounds = [];
    failed.cases = [];
    failed.diagnostics = ['suite assertion failed'];

    expect(parseGovernedBaselineProjection(failed)).toMatchObject({
      status: 'FAILED_CLOSED',
      diagnostics: ['suite assertion failed'],
      realExternalCallCount: null,
    });
  });

  it('rejects schema drift, tampered fingerprints, duplicate identities, and count drift', () => {
    const unknown = structuredClone(governedBaselineProjectionFixture);
    (unknown as Record<string, unknown>).unexpected = true;
    expect(() => parseGovernedBaselineProjection(unknown)).toThrow('Unknown field');

    const tampered = structuredClone(governedBaselineProjectionFixture);
    tampered.publication.receiptFingerprint = `sha256:${'z'.repeat(64)}`;
    expect(() => parseGovernedBaselineProjection(tampered)).toThrow('Invalid governedBaseline.publication.receiptFingerprint');

    const duplicateRun = structuredClone(governedBaselineProjectionFixture);
    duplicateRun.cases[1].rounds[0].runId = duplicateRun.cases[0].rounds[0].runId;
    expect(() => parseGovernedBaselineProjection(duplicateRun)).toThrow('Duplicate governed baseline runId');

    const wrongCount = structuredClone(governedBaselineProjectionFixture);
    wrongCount.childRunCount = 26;
    expect(() => parseGovernedBaselineProjection(wrongCount)).toThrow('Child run count does not close');
  });

  it('rejects incomplete PASSED evidence instead of silently downgrading it', () => {
    const failedCase = structuredClone(governedBaselineProjectionFixture);
    failedCase.cases[0].rounds[0].status = 'FAILED';
    expect(() => parseGovernedBaselineProjection(failedCase)).toThrow('passed governed baseline');

    const diagnostic = structuredClone(governedBaselineProjectionFixture);
    diagnostic.diagnostics = ['validation was not established'];
    expect(() => parseGovernedBaselineProjection(diagnostic)).toThrow('passed governed baseline');

    const duplicateSuiteRun = structuredClone(governedBaselineProjectionFixture);
    duplicateSuiteRun.rounds[2].suiteRunId = duplicateSuiteRun.rounds[0].suiteRunId;
    expect(() => parseGovernedBaselineProjection(duplicateSuiteRun)).toThrow('Duplicate governedBaseline.rounds suiteRunId');
  });

  it('requires all four release limitations and exact round sequences', () => {
    const missingLimitation = structuredClone(governedBaselineProjectionFixture);
    missingLimitation.limitations = ['IMMUTABLE_RELEASE_CANDIDATE_NOT_BOUND'];
    expect(() => parseGovernedBaselineProjection(missingLimitation)).toThrow('Required governed baseline limitation');

    const brokenSequence = structuredClone(governedBaselineProjectionFixture);
    brokenSequence.cases[0].rounds[2].round = 2;
    expect(() => parseGovernedBaselineProjection(brokenSequence)).toThrow('Invalid governedBaseline.cases[0].rounds round sequence');

    const wrongCaseOrder = structuredClone(governedBaselineProjectionFixture);
    [wrongCaseOrder.cases[0], wrongCaseOrder.cases[1]] = [wrongCaseOrder.cases[1], wrongCaseOrder.cases[0]];
    expect(() => parseGovernedBaselineProjection(wrongCaseOrder)).toThrow('canonical case order');
  });

  it('keeps candidate and evidence limitations synchronized with their facts', () => {
    const unbound = structuredClone(governedBaselineProjectionFixture);
    unbound.candidateBuild = null as never;
    unbound.candidateIntentFingerprint = null as never;
    unbound.limitations = [
      'IMMUTABLE_RELEASE_CANDIDATE_NOT_BOUND',
      ...unbound.limitations,
    ];
    expect(parseGovernedBaselineProjection(unbound)).toMatchObject({ candidateBuild: null });

    const certifiable = structuredClone(governedBaselineProjectionFixture);
    certifiable.evidenceClass = 'CERTIFIABLE' as never;
    certifiable.limitations = certifiable.limitations
      .filter((value) => value !== 'CERTIFIABLE_EVIDENCE_NOT_ESTABLISHED');
    const parsed = parseGovernedBaselineProjection(certifiable);
    expect(parsed.status === 'PASSED' && parsed.evidenceClass).toBe('CERTIFIABLE');

    const intentWithoutCandidate = structuredClone(governedBaselineProjectionFixture);
    intentWithoutCandidate.candidateBuild = null as never;
    intentWithoutCandidate.limitations = [
      'IMMUTABLE_RELEASE_CANDIDATE_NOT_BOUND',
      ...intentWithoutCandidate.limitations,
    ];
    expect(() => parseGovernedBaselineProjection(intentWithoutCandidate))
      .toThrow('Candidate build and execution intent binding are inconsistent');
  });

  it('rejects business Oracle drift and missing high-risk proofs', () => {
    const fingerprintDrift = structuredClone(governedBaselineProjectionFixture);
    fingerprintDrift.cases[0].rounds[2].semanticResultFingerprint = `sha256:${'f'.repeat(64)}`;
    expect(() => parseGovernedBaselineProjection(fingerprintDrift)).toThrow('business Oracle closure');

    const assertionDrift = structuredClone(governedBaselineProjectionFixture);
    assertionDrift.cases[1].rounds[0].assertionsPassed = 0;
    expect(() => parseGovernedBaselineProjection(assertionDrift)).toThrow('business assertion counts');

    const timeoutProofMissing = structuredClone(governedBaselineProjectionFixture);
    timeoutProofMissing.cases[2].proofs = timeoutProofMissing.cases[2].proofs
      .filter((proof) => proof !== 'TIMEOUT_FALLBACK_CONFIRMED');
    expect(() => parseGovernedBaselineProjection(timeoutProofMissing)).toThrow('business Oracle proofs');
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
