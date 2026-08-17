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
