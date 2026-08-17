export const capabilityStudioDemoPackFixture = {
  packId: 'capability:cn-rideshare:cancellation-fee-dispute:v1',
  revision: 1,
  packFingerprint: 'sha256:capability-studio-demo-v1',
  name: { en: 'Cancellation fee dispute handling', 'zh-CN': '取消费争议处理' },
  summary: { en: 'A governed capability for explaining, checking, and resolving cancellation fee disputes.', 'zh-CN': '用于解释、核验和处理取消费争议的治理能力。' },
  owner: { en: 'Customer Service Platform', 'zh-CN': '客服平台团队' },
  readiness: 'METADATA_READY_RUNTIME_EVIDENCE_PENDING',
  acceptanceStatus: 'NO_GO',
  canonicalBaseline: {
    name: { en: 'Canonical baseline', 'zh-CN': '当前标准基线' },
    purpose: { en: 'The reference behavior used for repeatable review.', 'zh-CN': '用于可重复评审的参考行为。' },
    status: { en: 'CURRENT', 'zh-CN': '当前' },
    ref: 'baseline:cancellation-fee-dispute:v1',
  },
  tutorialBranch: {
    name: { en: 'Tutorial branch', 'zh-CN': '教程分支' },
    purpose: { en: 'An isolated branch for learning timeout and edge behavior.', 'zh-CN': '用于学习超时和边界行为的隔离分支。' },
    status: { en: 'ISOLATED', 'zh-CN': '已隔离' },
    ref: 'branch:cancellation-fee-dispute:tutorial',
  },
  cardinality: { api: 4, feature: 1, tool: 1, scenarios: 9 },
  apiCapabilities: [
    {
      id: 'api:order-query:v1', name: { en: 'Order lookup', 'zh-CN': '订单查询' }, summary: { en: 'Find the trip and payment facts behind a customer question.', 'zh-CN': '查询客户问题对应的行程和支付事实。' }, owner: { en: 'Trip Platform', 'zh-CN': '行程平台' }, readiness: 'CONTRACT_READY_MOCK_PENDING',
      contract: {
        inputs: [{ name: { en: 'Order number', 'zh-CN': '订单号' }, type: 'string', required: true, description: { en: 'The customer-visible trip order number.', 'zh-CN': '客户可见的行程订单号。' } }],
        successResult: [{ name: { en: 'Order status', 'zh-CN': '订单状态' }, type: 'OrderStatus', description: { en: 'The current business status of the order.', 'zh-CN': '订单当前业务状态。' } }],
        errors: [{ code: 'ORDER_NOT_FOUND', meaning: { en: 'The order cannot be located.', 'zh-CN': '找不到对应订单。' }, retryable: false }],
        sideEffects: [{ en: 'Read-only lookup; no business data is changed.', 'zh-CN': '只读查询，不修改业务数据。' }],
        owner: { en: 'Trip Platform', 'zh-CN': '行程平台' }, sla: 'p95 < 300 ms', sensitivity: { en: 'Internal business data', 'zh-CN': '内部业务数据' },
      },
    },
    { id: 'api:fee-detail:v1', name: { en: 'Fee detail lookup', 'zh-CN': '费用明细查询' }, summary: { en: 'Explain the fee components.', 'zh-CN': '解释费用组成。' }, owner: 'Billing Platform', readiness: 'CONTRACT_READY_MOCK_PENDING' },
    { id: 'api:trip-events:v1', name: { en: 'Trip event lookup', 'zh-CN': '行程事件查询' }, summary: { en: 'Read cancellation and arrival events.', 'zh-CN': '读取取消和到达事件。' }, owner: 'Trip Platform', readiness: 'CONTRACT_READY_MOCK_PENDING' },
    { id: 'api:policy-query:v1', name: { en: 'Cancellation policy lookup', 'zh-CN': '取消规则查询' }, summary: { en: 'Read the policy version applied to the order.', 'zh-CN': '读取订单适用的规则版本。' }, owner: 'Policy Platform', readiness: 'CONTRACT_READY_MOCK_PENDING' },
  ],
  featureCapabilities: [{ id: 'feature:cancellation-dispute:v1', name: { en: 'Cancellation dispute feature', 'zh-CN': '取消费争议特征' }, summary: { en: 'Combines order, fee, event, and policy facts into a reviewable feature.', 'zh-CN': '将订单、费用、事件和规则事实加工为可核验特征。' }, owner: 'Customer Service Platform', readiness: 'DAG_CONTRACT_READY_RUNTIME_PENDING' }],
  toolCapabilities: [{ id: 'tool:cancellation-resolution:v1', name: { en: 'Cancellation dispute resolution tool', 'zh-CN': '取消费争议处理工具' }, summary: { en: 'Returns an explanation and the next service action.', 'zh-CN': '返回解释和下一步服务动作。' }, owner: 'Customer Service Platform', readiness: 'CONTRACT_READY_RUNTIME_PENDING' }],
  scenarios: [
    ['正常收取取消费', '正常路径', '业务规则', '应返回可解释的费用原因'], ['司机已到达后取消', '边界路径', '业务规则', '应识别到达事件并解释费用'], ['司机未接单前取消', '边界路径', '业务规则', '应返回不收取取消费'], ['乘客免责取消', '免责路径', '客服经验', '应返回免责原因'], ['规则版本变更', '回归路径', '规则发布', '应使用订单时点规则'], ['订单不存在', '错误路径', '接口契约', '应返回订单不存在'], ['费用明细缺失', '降级路径', '模拟数据', '应标记信息不完整'], ['查询超时', '故障路径', '隔离演练', '应停止等待并给出可行动提示'], ['重复请求', '幂等路径', '历史案例', '应返回一致解释'],
  ].map(([name, category, source, expectedResult], index) => ({
    id: `scenario:cancellation-fee:${index + 1}`,
    name: { en: name, 'zh-CN': name }, category: { en: category, 'zh-CN': category }, source: { en: source, 'zh-CN': source }, owner: { en: 'Customer Service Platform', 'zh-CN': '客服平台团队' }, oracle: { en: 'Feature Oracle', 'zh-CN': '特征正确性依据' }, applicableContractCount: 4, expectedResult: { en: expectedResult, 'zh-CN': expectedResult }, quality: { en: index === 6 ? 'REVIEW' : 'CURATED', 'zh-CN': index === 6 ? '待复核' : '已整理' }, lifecycle: { en: index === 6 ? 'DRAFT' : 'CURRENT', 'zh-CN': index === 6 ? '草稿' : '当前' },
  })),
};

const scenarioDatasetScope = {
  tenantId: 'tenant-demo',
  organizationId: 'customer-service-platform',
  projectId: 'resource-gateway',
  environmentId: 'demo',
  region: 'ap-southeast-1',
};

function scenarioDatasetRef(kind: string, id: string, seed: string) {
  return {
    kind,
    id,
    revision: 1,
    fingerprint: `sha256:${seed.repeat(64).slice(0, 64)}`,
    authority: 'resource-gateway-demo-authority',
    scope: scenarioDatasetScope,
  };
}

const scenarioDatasetCaseDefinitions = [
  ['Driver liable and fee exceeds policy', 'Confirm a driver-liable cancellation fee and explain why compensation is due.', 'GOLDEN', 'ACTIVE', 'READY', 'Business case', 'Return an explainable fee decision and a compensation recommendation.', 'RETURN'],
  ['Passenger liable and amount matches policy', 'Prevent compensation when the passenger is responsible and the fee is policy-compliant.', 'NEGATIVE', 'ACTIVE', 'READY', 'Business policy review', 'Do not recommend compensation; explain the policy match.', 'RETURN'],
  ['Compensation reaches the city cap', 'Keep a valid compensation recommendation within the configured city maximum.', 'BOUNDARY', 'ACTIVE', 'READY', 'Policy boundary review', 'Return a capped recommendation that never exceeds the city limit.', 'RETURN'],
  ['Policy effective time boundary', 'Use the policy version effective one minute before or after the trip event.', 'BOUNDARY', 'ACTIVE', 'READY', 'Policy release record', 'Use the policy version that was effective at the event time.', 'RETURN'],
  ['Compensation history times out', 'Make a missing historical lookup explicit instead of treating it as no history.', 'FAULT', 'ACTIVE', 'READY', 'Isolation rehearsal', 'Stop automatic decisioning and route the case to human review.', 'TIMEOUT'],
  ['City policy is missing', 'Block an automatic decision when no applicable city policy can be found.', 'FAULT', 'ACTIVE', 'READY', 'Contract fault case', 'Explain that policy data is missing and require human review.', 'ERROR'],
  ['Responsibility cannot be determined', 'Give the service agent a useful next action when responsibility facts are incomplete.', 'NEGATIVE', 'DRAFT', 'DESIGNED_NOT_RUN', 'Support escalation case', 'Return an information-incomplete outcome and a next-step request.', 'RETURN'],
  ['Duplicate fee incident regression', 'Keep a known duplicate-charge incident from reappearing in the decision.', 'REGRESSION', 'ACTIVE', 'READY', 'Historical incident', 'Detect the duplicate charge and recommend the governed correction.', 'RETURN'],
  ['Payment credential appears in dependency response', 'Prevent sensitive payment credential fields from reaching the feature or tool result.', 'SECURITY', 'ACTIVE', 'READY', 'Security review', 'Redact sensitive fields and continue with a safe business explanation.', 'MUST_NOT_CALL'],
] as const;

export const scenarioDatasetProjectionFixture = {
  schemaVersion: 'resource-gateway.capability-studio.scenario-dataset.v1',
  datasetRef: scenarioDatasetRef('DATASET', 'cancellation-fee-scenarios', 'd'),
  name: 'Cancellation fee dispute scenario dataset',
  description: 'Governed business cases for explaining and reviewing cancellation fee disputes.',
  lifecycle: 'ACTIVE',
  classification: 'INTERNAL',
  owner: { id: 'customer-service-platform', name: 'Customer Service Platform' },
  targetRef: scenarioDatasetRef('TOOL', 'cancellation-resolution', 'e'),
  contractRefs: Array.from({ length: 4 }, (_, index) => scenarioDatasetRef('CONTRACT', `cancellation-contract-${index + 1}`, String(index + 1))),
  cases: scenarioDatasetCaseDefinitions.map(([name, businessIntent, category, lifecycle, qualityState, sourceName, oracleSummary, behavior], index) => ({
    caseRef: scenarioDatasetRef('DATA_CASE', `cancellation-fee-case-${index + 1}`, String(index + 1)),
    name,
    businessIntent,
    category,
    lifecycle,
    qualityState,
    owner: { id: 'customer-service-platform', name: 'Customer Service Platform' },
    sourceRef: scenarioDatasetRef('SOURCE', `cancellation-source-${index + 1}`, 'a'),
    source: { displayName: sourceName, type: 'BUSINESS_RECORD' },
    oracleRef: scenarioDatasetRef('ORACLE', `cancellation-oracle-${index + 1}`, 'b'),
    oracle: { displayName: 'Cancellation dispute business oracle', summary: oracleSummary },
    applicableContractRefs: Array.from({ length: 4 }, (_, contractIndex) => scenarioDatasetRef('CONTRACT', `cancellation-contract-${contractIndex + 1}`, String(contractIndex + 1))),
    behaviorProfiles: [{
      behaviorRef: scenarioDatasetRef('BEHAVIOR_PROFILE', `cancellation-behavior-${index + 1}`, 'c'),
      dependencyRef: scenarioDatasetRef('API', 'cancellation-dependency', 'f'),
      behavior,
      summary: behavior === 'TIMEOUT' ? 'The compensation history dependency times out; the case must be reviewed by a person.' : 'The dependency returns the governed behavior for this business case.',
    }],
  })),
  quality: {
    status: 'READY',
    totalCaseCount: 9,
    activeCaseCount: 8,
    staleCaseCount: 0,
    ownerCoveragePercent: 100,
    sourceCoveragePercent: 100,
    oracleCoveragePercent: 100,
    contractCoveragePercent: 100,
    behaviorClosurePercent: 100,
  },
};

const featureFp = (digit: string) => `sha256:${digit.repeat(64)}`;

export function featureRehearsalProjectionFixture(permission: 'STRUCTURE_ONLY' | 'PAYLOAD_VISIBLE' = 'STRUCTURE_ONLY', caseId = 'case-compensation-history-timeout') {
  const timeout = caseId === 'case-compensation-history-timeout';
  const payload = (value: unknown) => permission === 'PAYLOAD_VISIBLE' ? value : null;
  const node = (nodeId: string, operatorRef: string, status: string, seed: string, input: unknown, output: unknown, errorCode = '') => ({
    nodeId,
    operatorRef,
    status,
    fidelity: operatorRef === 'httpResource' ? 'OUTPUT_LEVEL' : 'REAL',
    graphPath: '/root',
    invocationSite: `/root/${nodeId}#PRIMARY`,
    correlation: '',
    occurrence: 1,
    graphOccurrence: 1,
    input: payload(input),
    inputFingerprint: input === null ? '' : featureFp(seed),
    output: payload(output),
    outputFingerprint: output === null ? '' : featureFp('f'),
    errorCode,
    durationMs: status === 'TIMEOUT' ? 10 : status === 'CANCELLED' ? 0 : 42,
    attempts: [],
    retryCount: 0,
    fallbackStatus: null,
  });
  const parsedNodes = [
    node('orderLookup', 'httpResource', 'MOCKED', '1', { resourceId: 'api-order-lookup', params: { orderId: 'DEMO-ORDER-20260818-001' } }, { status: 'CANCELLED', cityCode: 'SZ' }),
    node('responsibilityLookup', 'httpResource', 'MOCKED', '2', { resourceId: 'api-cancellation-responsibility' }, { owner: 'PLATFORM', reasonCode: 'DRIVER_LATE' }),
    node('cityPolicyLookup', 'httpResource', 'MOCKED', '3', { resourceId: 'api-city-pricing-policy' }, { version: 'SZ-CANCEL-2026.08' }),
    node('compensationHistoryLookup', 'httpResource', timeout ? 'TIMEOUT' : 'MOCKED', '4', { resourceId: 'api-compensation-history' }, timeout ? null : { hasHistory: false, records: [] }, timeout ? 'COMPENSATION_HISTORY_TIMEOUT' : ''),
    node('aggregateCancellationContext', 'capabilityStudio.aggregate', timeout ? 'CANCELLED' : 'SUCCESS', '5', timeout ? null : { orderId: 'DEMO-ORDER-20260818-001' }, timeout ? null : { complete: true }),
    node('cancellationDecision', 'capabilityStudio.decision', timeout ? 'CANCELLED' : 'SUCCESS', '6', timeout ? null : { complete: true }, timeout ? null : { action: 'AUTO_QUOTE' }),
  ];
  const edge = (edgeId: string, from: string, to: string, status: string, seed: string, value: unknown) => ({
    edgeId,
    status,
    graphPath: '/root',
    correlation: '',
    graphOccurrence: 1,
    fromInvocationSite: `/root/${from}#PRIMARY`,
    toInvocationSite: `/root/${to}#PRIMARY`,
    value: payload(value),
    valueFingerprint: value === null ? '' : featureFp(seed),
  });
  const parsedEdges = [
    edge('orderLookup->aggregateCancellationContext', 'orderLookup', 'aggregateCancellationContext', timeout ? 'NOT_TRANSFERRED' : 'TRANSFERRED', '7', { status: 'CANCELLED' }),
    edge('responsibilityLookup->aggregateCancellationContext', 'responsibilityLookup', 'aggregateCancellationContext', timeout ? 'NOT_TRANSFERRED' : 'TRANSFERRED', '8', { owner: 'PLATFORM' }),
    edge('cityPolicyLookup->aggregateCancellationContext', 'cityPolicyLookup', 'aggregateCancellationContext', timeout ? 'NOT_TRANSFERRED' : 'TRANSFERRED', '9', { version: 'SZ-CANCEL-2026.08' }),
    edge('compensationHistoryLookup->aggregateCancellationContext', 'compensationHistoryLookup', 'aggregateCancellationContext', timeout ? 'NOT_TRANSFERRED' : 'TRANSFERRED', 'a', timeout ? null : { hasHistory: false }),
    edge('aggregateCancellationContext->cancellationDecision', 'aggregateCancellationContext', 'cancellationDecision', timeout ? 'NOT_TRANSFERRED' : 'TRANSFERRED', 'b', timeout ? null : { complete: true }),
  ];
  const status = timeout ? 'TIMED_OUT' : 'PASSED';
  return {
    schemaVersion: 'resource-gateway.capability-studio.feature-rehearsal.v1',
    scenario: { id: caseId, name: featureRehearsalCasesFixtureName(caseId), expectedResult: timeout ? '不因历史查询超时产生未经核验的费用结论' : '返回可解释的取消费处理结论' },
    graph: { id: 'feature-cancellation-dispute-context', fingerprint: featureFp('c') },
    run: { runId: `test-run-${caseId}`, status, semanticFingerprint: featureFp('d'), realExternalCallCount: 0, bindingMode: 'FIXTURE_CONTROLLED_NON_PRODUCTION' },
    dataLens: { schemaVersion: 'resource-gateway.capability-studio.data-lens.v1', runId: `test-run-${caseId}`, runStatus: status, permissionMode: permission, nodes: parsedNodes, edges: parsedEdges, firstDifference: null, truncation: { nodesTruncated: false, omittedNodes: 0, edgesTruncated: false, omittedEdges: 0, attemptsTruncated: false, omittedAttempts: 0 }, fingerprint: featureFp('e') },
  };
}

function featureRehearsalCasesFixtureName(caseId: string): string {
  const names: Record<string, string> = {
    'case-standard-cancellation-fee': 'Standard cancellation fee',
    'case-rider-not-responsible': 'Rider is not responsible',
    'case-driver-responsible': 'Driver is responsible',
    'case-city-policy-missing': 'City policy is missing',
    'case-compensation-history-empty': 'Compensation history is empty',
    'case-compensation-history-timeout': 'Compensation history times out',
    'case-duplicate-cancellation': 'Duplicate cancellation request',
    'case-forbidden-write-effect': 'Forbidden write effect',
    'case-policy-revision-regression': 'Policy revision regression',
  };
  return names[caseId] ?? names['case-compensation-history-timeout'];
}
