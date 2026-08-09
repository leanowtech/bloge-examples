import type { Locale, TranslationValues } from './i18n';

type LocalizedMessage = Record<Locale, string>;

export const MESSAGE_CATALOG = {
  'author.command.run': { en: 'Run & Compare', 'zh-CN': '运行并比较' },
  'author.command.rerun': { en: 'Rerun & Compare', 'zh-CN': '重新运行并比较' },
  'author.command.running': { en: 'Running...', 'zh-CN': '正在运行...' },
  'author.command.addFirstOperator': { en: 'Add first operator', 'zh-CN': '添加第一个算子' },
  'author.command.fixRequiredInput': { en: 'Fix required input', 'zh-CN': '修复必填输入' },
  'author.command.createScenario': { en: 'Create Scenario', 'zh-CN': '创建测试场景' },
  'author.command.openScenarios': { en: 'Open Scenarios', 'zh-CN': '打开测试场景' },
  'author.command.reviewCompatibility': { en: 'Review compatibility', 'zh-CN': '检查兼容性' },
  'author.command.reviewLayout': { en: 'Review layout', 'zh-CN': '检查布局' },
  'author.command.reviewFailures': { en: 'Review failures', 'zh-CN': '检查失败项' },
  'author.command.reviewResult': { en: 'Review result', 'zh-CN': '检查结果' },
  'author.blocker.graphEmpty': {
    en: 'Add at least one operator before running a Scenario.',
    'zh-CN': '请先添加至少一个算子，再运行测试场景。',
  },
  'author.blocker.inputInvalid': {
    en: 'Resolve the highlighted input values before running.',
    'zh-CN': '请先修复已标出的输入值，再运行。',
  },
  'author.blocker.scenarioMissing': {
    en: 'Create a Scenario with business input and at least one expected outcome.',
    'zh-CN': '请创建包含业务输入和至少一个预期结果的测试场景。',
  },
  'author.blocker.coordinatePreparing': {
    en: 'The canonical Scenario coordinate is still being prepared.',
    'zh-CN': '系统仍在准备当前测试场景的标准坐标。',
  },
  'author.blocker.scenarioStale': {
    en: 'This Scenario targets an older Graph or Contract and cannot create current evidence.',
    'zh-CN': '此测试场景指向旧版编排图或契约，无法生成当前证据。',
  },
  'author.blocker.runInProgress': {
    en: 'The current Scenario run is still in progress.',
    'zh-CN': '当前测试场景仍在运行中。',
  },
  'author.blocker.layoutPending': {
    en: 'Accept or cancel the pending layout preview before running.',
    'zh-CN': '请先接受或取消待处理的布局预览，再运行。',
  },
  'author.command.savedRunDetail': {
    en: 'Runs the exact saved Scenario coordinate and records durable current evidence.',
    'zh-CN': '按已保存的精确场景坐标运行，并记录持久且当前的证据。',
  },
  'author.command.sandboxRunDetail': {
    en: 'Runs an immutable sandbox snapshot and records current exploratory evidence.',
    'zh-CN': '运行不可变的沙盒快照，并记录当前的探索性证据。',
  },
  'author.command.waitForActive': {
    en: 'Wait for the active authoring command to finish.',
    'zh-CN': '请等待当前编排操作完成。',
  },
  'status.saved': { en: 'SAVED', 'zh-CN': '已保存' },
  'status.ephemeral': { en: 'EPHEMERAL', 'zh-CN': '临时' },
  'status.notRun': { en: 'NOT RUN', 'zh-CN': '未运行' },
  'status.notConfigured': { en: 'NOT CONFIGURED', 'zh-CN': '未配置' },
  'status.notChecked': { en: 'NOT CHECKED', 'zh-CN': '未检查' },
  'status.notEvaluated': { en: 'NOT EVALUATED', 'zh-CN': '未评估' },
  'status.valid': { en: 'VALID', 'zh-CN': '有效' },
  'status.invalid': { en: 'INVALID', 'zh-CN': '无效' },
  'status.current': { en: 'CURRENT', 'zh-CN': '当前' },
  'status.stale': { en: 'STALE', 'zh-CN': '已过期' },
  'status.pass': { en: 'PASS', 'zh-CN': '通过' },
  'status.fail': { en: 'FAIL', 'zh-CN': '失败' },
  'status.runnable': { en: 'RUNNABLE', 'zh-CN': '可运行' },
  'status.ready': { en: 'READY', 'zh-CN': '就绪' },
  'status.running': { en: 'RUNNING', 'zh-CN': '运行中' },
  'status.durable': { en: 'DURABLE', 'zh-CN': '持久证据' },
  'status.governed': { en: 'GOVERNED', 'zh-CN': '治理证据' },
  'status.exploratory': { en: 'EXPLORATORY', 'zh-CN': '探索性证据' },
  'diagnostic.requestFailed.title': { en: 'Request failed', 'zh-CN': '请求失败' },
  'diagnostic.requestFailed.explanation': {
    en: 'The requested operation did not complete.',
    'zh-CN': '本次请求未能完成。',
  },
  'diagnostic.requestFailed.remediation': {
    en: 'Review the request details and retry.',
    'zh-CN': '检查请求详情后重试。',
  },
  'diagnostic.runFailed.title': { en: 'Runtime execution failed', 'zh-CN': '运行时执行失败' },
  'diagnostic.runFailed.explanation': {
    en: 'The graph did not complete successfully.',
    'zh-CN': '编排图未能成功完成执行。',
  },
  'diagnostic.runFailed.remediation': {
    en: 'Inspect the failed trace and rerun the same Scenario.',
    'zh-CN': '检查失败链路后重新运行同一测试场景。',
  },
  'diagnostic.assertionFailed.title': { en: 'Business assertion failed', 'zh-CN': '业务断言失败' },
  'diagnostic.assertionFailed.explanation': {
    en: 'The actual result differs from the authored expectation.',
    'zh-CN': '实际结果与已定义的业务预期不一致。',
  },
  'diagnostic.assertionFailed.remediation': {
    en: 'Open the Case and compare Expected with Actual.',
    'zh-CN': '打开用例并比较预期值与实际值。',
  },
  'diagnostic.contractMultipleSources.title': {
    en: 'Contract field has multiple sources',
    'zh-CN': '契约字段存在多个数据源',
  },
  'diagnostic.contractMultipleSources.explanation': {
    en: 'More than one authoritative binding writes the same target field.',
    'zh-CN': '多个权威绑定正在写入同一个目标字段。',
  },
  'diagnostic.contractMultipleSources.remediation': {
    en: 'Keep one authoritative source for this target field.',
    'zh-CN': '为该目标字段保留唯一权威数据源。',
  },
  'diagnostic.contractTypeConflict.title': {
    en: 'Contract field type conflicts',
    'zh-CN': '契约字段类型冲突',
  },
  'diagnostic.contractTypeConflict.explanation': {
    en: 'The bound source type does not match the declared target type.',
    'zh-CN': '绑定的数据源类型与目标声明类型不一致。',
  },
  'diagnostic.contractTypeConflict.remediation': {
    en: 'Align the source and target field types.',
    'zh-CN': '统一数据源与目标字段的类型。',
  },
  'diagnostic.generic.title': { en: 'System diagnostic', 'zh-CN': '系统诊断' },
  'diagnostic.generic.explanation': {
    en: 'The system reported a diagnostic that is not yet in the product catalog.',
    'zh-CN': '系统返回了一条尚未纳入产品目录的诊断。',
  },
  'diagnostic.generic.remediation': {
    en: 'Use the protocol code and technical details to continue investigation.',
    'zh-CN': '请结合协议代码和技术详情继续排查。',
  },
  'library.saveState.idle': { en: 'IDLE', 'zh-CN': '空闲' },
  'library.saveState.dirty': { en: 'UNSAVED', 'zh-CN': '未保存' },
  'library.saveState.saving': { en: 'SAVING', 'zh-CN': '保存中' },
  'library.saveState.saved': { en: 'SAVED', 'zh-CN': '已保存' },
  'library.saveState.conflict': { en: 'Conflict', 'zh-CN': '存在冲突' },
  'library.saveState.error': { en: 'ERROR', 'zh-CN': '发生错误' },
  'library.save.savedRevision': {
    en: 'Saved revision {revision}',
    'zh-CN': '已保存修订版 {revision}',
  },
  'library.save.saving': { en: 'Saving...', 'zh-CN': '正在保存...' },
  'library.save.unsavedChanges': { en: 'Unsaved changes', 'zh-CN': '存在未保存的更改' },
  'library.save.readOnlyPolicy': {
    en: 'This role or tenant scope cannot edit the library.',
    'zh-CN': '当前角色或租户范围不能编辑算子库。',
  },
  'library.save.revisionConflict': {
    en: 'A newer revision exists. Reload before continuing.',
    'zh-CN': '存在更新的修订版，请重新加载后继续。',
  },
  'library.save.loadFailed': {
    en: 'The library draft could not be loaded.',
    'zh-CN': '无法加载算子库草稿。',
  },
  'library.save.autosaveFailed': {
    en: 'Autosave did not complete. Review the technical details and retry.',
    'zh-CN': '自动保存未完成，请检查技术详情后重试。',
  },
  'library.save.commitFailed': {
    en: 'The Design Catalog could not be imported.',
    'zh-CN': '无法导入设计目录。',
  },
  'library.save.importedRevision': {
    en: 'Design Catalog revision {revision} imported',
    'zh-CN': '设计目录修订版 {revision} 已导入',
  },
  'library.save.newQuickDraft': { en: 'New guided draft', 'zh-CN': '新的引导式草稿' },
  'library.save.newSampleDraft': { en: 'New sample-driven draft', 'zh-CN': '新的样例驱动草稿' },
  'library.save.newJsonDraft': { en: 'New JSON draft', 'zh-CN': '新的 JSON 草稿' },
  'library.save.newExampleDraft': { en: 'New example draft', 'zh-CN': '新的示例草稿' },
  'library.save.newDiscoveryDraft': { en: 'New discovered-asset draft', 'zh-CN': '新的存量资产草稿' },
  'library.save.newDraft': { en: 'New library draft', 'zh-CN': '新的算子库草稿' },
  'library.readiness.awaitingValidation.title': {
    en: 'Awaiting validation',
    'zh-CN': '等待校验',
  },
  'library.readiness.awaitingValidation.summary': {
    en: 'No server-authoritative Contract preview is available yet.',
    'zh-CN': '尚无服务端权威的契约预览。',
  },
  'library.readiness.awaitingValidation.action': {
    en: 'Validate the current draft.',
    'zh-CN': '校验当前草稿。',
  },
  'library.readiness.designBlocked.title': { en: 'Design blocked', 'zh-CN': '设计被阻断' },
  'library.readiness.designBlocked.summary': {
    en: '{count} blocking Contract problems must be resolved.',
    'zh-CN': '需要解决 {count} 个契约阻断问题。',
  },
  'library.readiness.designBlocked.action': {
    en: 'Open the first blocking diagnostic.',
    'zh-CN': '打开第一个阻断诊断。',
  },
  'library.readiness.ready.title': { en: 'Ready to execute', 'zh-CN': '已可执行' },
  'library.readiness.ready.summary': {
    en: 'All {count} runtime assets are bound to this exact Contract.',
    'zh-CN': '全部 {count} 个运行时资产均已绑定到此精确契约。',
  },
  'library.readiness.ready.action': {
    en: 'Run the Contract test suite before promotion.',
    'zh-CN': '发布前运行契约测试套件。',
  },
  'library.readiness.runtimeUnknown.title': {
    en: 'Design valid; runtime not verified',
    'zh-CN': '设计有效，运行时尚未验证',
  },
  'library.readiness.runtimeUnknown.summary': {
    en: 'The Contract can be imported, but this deployment did not provide runtime inventory evidence.',
    'zh-CN': '契约可以导入，但当前部署未提供运行时清单证据。',
  },
  'library.readiness.runtimeUnknown.action': {
    en: 'Connect or discover runtime inventory.',
    'zh-CN': '连接或发现运行时清单。',
  },
  'library.readiness.runtimeUnbound.title': {
    en: 'Design valid; runtime unbound',
    'zh-CN': '设计有效，运行时未绑定',
  },
  'library.readiness.runtimeUnbound.summary': {
    en: '{bound}/{total} declared assets can execute in this deployment.',
    'zh-CN': '当前部署可执行 {bound}/{total} 个已声明资产。',
  },
  'library.readiness.runtimeUnbound.action': {
    en: 'Bind an exact runtime implementation or keep this catalog design-only.',
    'zh-CN': '绑定精确的运行时实现，或将此目录保持为仅设计状态。',
  },
  'library.readiness.runtimePartial.title': {
    en: 'Design valid; runtime partially bound',
    'zh-CN': '设计有效，运行时部分绑定',
  },
  'library.readiness.runtimePartial.summary': {
    en: '{bound}/{total} declared assets can execute in this deployment.',
    'zh-CN': '当前部署可执行 {bound}/{total} 个已声明资产。',
  },
  'library.readiness.runtimePartial.action': {
    en: 'Resolve the remaining runtime bindings.',
    'zh-CN': '解决其余运行时绑定。',
  },
  'library.readiness.schemaReview.title': {
    en: 'Schema review required',
    'zh-CN': '需要检查 Schema',
  },
  'library.readiness.schemaReview.summary': {
    en: 'The catalog can be documented, but unresolved types prevent a strong Contract.',
    'zh-CN': '目录可以形成文档，但未解析的类型阻止生成强契约。',
  },
  'library.readiness.schemaReview.action': {
    en: 'Replace unresolved types or explicitly accept an open schema.',
    'zh-CN': '替换未解析类型，或明确接受开放 Schema。',
  },
  'library.runtime.state.bound': { en: 'BOUND', 'zh-CN': '已绑定' },
  'library.runtime.state.drifted': { en: 'DRIFTED', 'zh-CN': '存在漂移' },
  'library.runtime.state.documentedOnly': { en: 'DOCUMENTED ONLY', 'zh-CN': '仅有文档' },
  'library.runtime.state.discovered': { en: 'RUNTIME DISCOVERED', 'zh-CN': '已发现运行时' },
  'library.runtime.state.blockedByPolicy': { en: 'BLOCKED BY POLICY', 'zh-CN': '被策略阻断' },
  'library.runtime.state.unknown': { en: 'UNKNOWN', 'zh-CN': '未知' },
  'library.runtime.detail.bound': {
    en: 'The declared asset Contract matches its bound runtime.',
    'zh-CN': '已声明的资产契约与绑定的运行时一致。',
  },
  'library.runtime.detail.documentedOnly': {
    en: 'No exact executable runtime binding is available for this asset.',
    'zh-CN': '此资产没有可用的精确可执行运行时绑定。',
  },
  'library.runtime.detail.discovered': {
    en: 'A runtime implementation exists, but its exact Contract is not proven.',
    'zh-CN': '已发现运行时实现，但尚未证明其精确契约。',
  },
  'library.runtime.detail.drifted': {
    en: 'The declared Contract differs from runtime inventory evidence.',
    'zh-CN': '已声明契约与运行时清单证据不一致。',
  },
  'library.runtime.detail.blockedByPolicy': {
    en: 'Runtime policy prevents this asset from executing here.',
    'zh-CN': '运行时策略阻止此资产在当前环境执行。',
  },
  'library.runtime.detail.unknown': {
    en: 'The runtime reported an unrecognized parity state.',
    'zh-CN': '运行时返回了无法识别的一致性状态。',
  },
  'library.runtime.reason.operatorMissing': {
    en: 'No exact operator exists in the target runtime inventory.',
    'zh-CN': '目标运行时清单中不存在精确匹配的算子。',
  },
  'library.runtime.reason.functionMissing': {
    en: 'No exact callable exists in the target runtime inventory.',
    'zh-CN': '目标运行时清单中不存在精确匹配的可调用函数。',
  },
  'library.runtime.reason.operatorBindingMissing': {
    en: 'The operator Contract does not declare an executable runtime binding.',
    'zh-CN': '算子契约尚未声明可执行的运行时绑定。',
  },
  'library.runtime.reason.operatorContractUnknown': {
    en: 'The operator exists at runtime, but no exact Contract is available for comparison.',
    'zh-CN': '运行时中存在该算子，但没有可用于比较的精确契约。',
  },
  'library.runtime.reason.functionContractUnknown': {
    en: 'The callable exists at runtime, but no exact signature is available for comparison.',
    'zh-CN': '运行时中存在该函数，但没有可用于比较的精确签名。',
  },
  'library.runtime.reason.operatorLoweringUnverified': {
    en: 'The lowering target exists, but its adapter Contract is not proven.',
    'zh-CN': '运行时映射目标已存在，但适配器契约尚未得到证明。',
  },
  'library.runtime.reason.operatorDrift': {
    en: 'The declared operator Contract differs from runtime inventory evidence.',
    'zh-CN': '已声明的算子契约与运行时清单证据不一致。',
  },
  'library.runtime.reason.functionAmbiguous': {
    en: 'Multiple non-identical runtime implementations claim this callable.',
    'zh-CN': '多个不一致的运行时实现声明了同一个可调用函数。',
  },
  'library.runtime.reason.functionPolicyBlocked': {
    en: 'Runtime policy prevents this function from executing here.',
    'zh-CN': '运行时策略阻止此函数在当前环境执行。',
  },
  'library.runtime.reason.functionSignatureUnknown': {
    en: 'The runtime function has no authoritative signature metadata.',
    'zh-CN': '运行时函数没有权威签名元数据。',
  },
  'library.runtime.reason.functionSignatureDrift': {
    en: 'The declared function signature differs from runtime inventory evidence.',
    'zh-CN': '已声明的函数签名与运行时清单证据不一致。',
  },
  'library.runtime.reason.confirmationRequired': {
    en: 'Runtime binding requires explicit author confirmation.',
    'zh-CN': '运行时绑定需要作者明确确认。',
  },
  'library.runtime.reason.signaturesRequired': {
    en: 'Runtime comparison requires declared function signatures.',
    'zh-CN': '运行时比较需要声明函数签名。',
  },
  'library.runtime.technicalDetails': { en: 'Technical details', 'zh-CN': '技术详情' },
  'showcase.userDashboard.title': { en: 'User Dashboard', 'zh-CN': '用户仪表盘' },
  'showcase.userDashboard.pattern': { en: 'Parallel fan-out aggregation', 'zh-CN': '并行扇出聚合' },
  'showcase.userDashboard.description': {
    en: 'Fetches five independent user-facing resources concurrently, then assembles a dashboard response.',
    'zh-CN': '并发获取五项相互独立的用户资源，再组装为仪表盘响应。',
  },
  'showcase.loanDecisionPolicy.title': { en: 'Loan Decision Policy', 'zh-CN': '贷款决策策略' },
  'showcase.loanDecisionPolicy.pattern': { en: 'Decision-table policy matrix', 'zh-CN': '决策表策略矩阵' },
  'showcase.loanDecisionPolicy.description': {
    en: 'Fetches applicant risk facts, evaluates a UNIQUE decision table, and returns the matched policy row.',
    'zh-CN': '获取申请人的风险事实，执行 UNIQUE 决策表，并返回命中的策略行。',
  },
  'showcase.productDetail.title': { en: 'Product Detail', 'zh-CN': '商品详情' },
  'showcase.productDetail.pattern': { en: 'Conditional branch enrichment', 'zh-CN': '条件分支增强' },
  'showcase.productDetail.description': {
    en: 'Loads a base product and routes to physical, digital, or generic enrichment before unifying the response.',
    'zh-CN': '载入基础商品后路由到实物、数字或通用增强分支，再统一组装响应。',
  },
  'showcase.enrichOrderList.title': { en: 'Enrich Order List', 'zh-CN': '增强订单列表' },
  'showcase.enrichOrderList.pattern': { en: 'Foreach enrichment', 'zh-CN': '逐项增强' },
  'showcase.enrichOrderList.description': {
    en: 'Loads orders once, then enriches every order with shipping and invoice data inside a parallel foreach scope.',
    'zh-CN': '一次载入订单，并在并行 foreach 作用域中为每项订单补充物流和发票数据。',
  },
  'showcase.creditScore.title': { en: 'Credit Score', 'zh-CN': '信用评分' },
  'showcase.creditScore.pattern': { en: 'Provider degradation', 'zh-CN': '提供方降级' },
  'showcase.creditScore.description': {
    en: 'Tries the primary credit provider first and falls back to a secondary provider when the primary path fails.',
    'zh-CN': '优先调用主信用数据提供方，并在主路径失败时降级到备用提供方。',
  },
  'showcase.resourceDispatch.title': { en: 'Resource Dispatch', 'zh-CN': '资源分派' },
  'showcase.resourceDispatch.pattern': {
    en: 'Generic descriptor-backed execution',
    'zh-CN': '描述符驱动的通用执行',
  },
  'showcase.resourceDispatch.description': {
    en: 'Executes any registered resource by resourceId through the generic httpResource operator.',
    'zh-CN': '通过通用 httpResource 算子，按 resourceId 执行任意已注册资源。',
  },
  'showcase.aiEnrichedSearch.title': { en: 'AI Enriched Search', 'zh-CN': 'AI 增强搜索' },
  'showcase.aiEnrichedSearch.pattern': { en: 'Mixed streaming fan-in', 'zh-CN': '混合流式扇入' },
  'showcase.aiEnrichedSearch.description': {
    en: 'Runs metadata, token, and citation streams in parallel and routes each stream to a separate SSE event lane.',
    'zh-CN': '并行运行元数据、Token 和引用流，并将各流路由到独立的 SSE 事件通道。',
  },
  'showcase.concept.parallelFanOut': { en: 'parallel fan-out', 'zh-CN': '并行扇出' },
  'showcase.concept.httpResource': { en: 'httpResource', 'zh-CN': 'httpResource' },
  'showcase.concept.timeout': { en: 'timeout', 'zh-CN': '超时' },
  'showcase.concept.retry': { en: 'retry', 'zh-CN': '重试' },
  'showcase.concept.fallback': { en: 'fallback', 'zh-CN': '降级' },
  'showcase.concept.aggregation': { en: 'aggregation', 'zh-CN': '聚合' },
  'showcase.concept.decisionTable': { en: 'decision_table', 'zh-CN': '决策表' },
  'showcase.concept.uniqueHit': { en: 'hit=unique', 'zh-CN': '唯一命中' },
  'showcase.concept.ruleMatrix': { en: 'rule matrix', 'zh-CN': '规则矩阵' },
  'showcase.concept.explainableOutput': { en: 'explainable output', 'zh-CN': '可解释输出' },
  'showcase.concept.conditionalBranch': { en: 'conditional branch', 'zh-CN': '条件分支' },
  'showcase.concept.branchFallback': { en: 'branch fallback', 'zh-CN': '分支降级' },
  'showcase.concept.resourceDescriptor': { en: 'resource descriptor', 'zh-CN': '资源描述符' },
  'showcase.concept.unifiedResponse': { en: 'unified response', 'zh-CN': '统一响应' },
  'showcase.concept.foreach': { en: 'foreach', 'zh-CN': 'foreach' },
  'showcase.concept.perItemFallback': { en: 'per-item fallback', 'zh-CN': '逐项降级' },
  'showcase.concept.parallelEnrichment': { en: 'parallel enrichment', 'zh-CN': '并行增强' },
  'showcase.concept.collectionTransform': { en: 'collection transform', 'zh-CN': '集合转换' },
  'showcase.concept.degradation': { en: 'degradation', 'zh-CN': '降级' },
  'showcase.concept.branchOnSuccess': { en: 'branch on success', 'zh-CN': '成功后分支' },
  'showcase.concept.providerProvenance': { en: 'provider provenance', 'zh-CN': '提供方溯源' },
  'showcase.concept.descriptorRegistry': { en: 'descriptor registry', 'zh-CN': '描述符注册表' },
  'showcase.concept.parameterMapping': { en: 'parameter mapping', 'zh-CN': '参数映射' },
  'showcase.concept.headerOverride': { en: 'header override', 'zh-CN': '请求头覆盖' },
  'showcase.concept.responseProtocol': { en: 'response protocol', 'zh-CN': '响应协议' },
  'showcase.concept.streamNode': { en: 'stream node', 'zh-CN': '流式节点' },
  'showcase.concept.sse': { en: 'SSE', 'zh-CN': 'SSE' },
  'showcase.concept.parallelStreamFanIn': { en: 'parallel stream fan-in', 'zh-CN': '并行流式扇入' },
  'showcase.concept.citationLane': { en: 'citation lane', 'zh-CN': '引用通道' },
  'rehearsal.demo.governanceBlocked.title': { en: 'Grounding policy regression', 'zh-CN': '溯源策略回归' },
  'rehearsal.demo.governanceBlocked.situation': {
    en: 'Mixed execution, evidence, assertion, governance, warning, and passing results.',
    'zh-CN': '同时包含执行、证据、断言、治理、警告和通过结果。',
  },
  'rehearsal.demo.governanceBlocked.focus': {
    en: 'Triage every failure category',
    'zh-CN': '分诊每一类失败',
  },
  'rehearsal.demo.releaseReady.title': { en: 'Release candidate ready', 'zh-CN': '发布候选已就绪' },
  'rehearsal.demo.releaseReady.situation': {
    en: 'All blocker assertions pass; one non-blocking freshness warning remains visible.',
    'zh-CN': '所有阻断断言均通过，并保留一条非阻断的新鲜度警告。',
  },
  'rehearsal.demo.releaseReady.focus': { en: 'Review gate-ready evidence', 'zh-CN': '审阅门禁就绪证据' },
  'rehearsal.demo.liveDegradation.title': { en: 'Live dependency degradation', 'zh-CN': '实时依赖降级' },
  'rehearsal.demo.liveDegradation.situation': {
    en: 'A regional batch is still running while CRM throttling affects one branch.',
    'zh-CN': '区域批次仍在运行，CRM 限流正在影响其中一个分支。',
  },
  'rehearsal.demo.liveDegradation.focus': {
    en: 'Separate live state from evidence',
    'zh-CN': '区分实时状态与证据',
  },
  'rehearsal.demo.evidenceQuarantine.title': {
    en: 'Evidence finalization quarantine',
    'zh-CN': '证据定稿隔离',
  },
  'rehearsal.demo.evidenceQuarantine.situation': {
    en: 'Business execution partly succeeds, but signing and retention closure are incomplete.',
    'zh-CN': '业务执行部分成功，但签名和留存闭环尚未完成。',
  },
  'rehearsal.demo.evidenceQuarantine.focus': {
    en: 'Diagnose evidence-plane failures',
    'zh-CN': '诊断证据平面故障',
  },
  'showcase.run.notRun': { en: 'Not run yet.', 'zh-CN': '尚未运行。' },
  'showcase.run.streamStopped': { en: 'Stream stopped.', 'zh-CN': '流已停止。' },
  'showcase.run.recipeMissing': {
    en: 'Selected scenario has no run recipe.',
    'zh-CN': '所选场景没有可用的运行配置。',
  },
  'showcase.run.openingStream': { en: 'Opening stream...', 'zh-CN': '正在打开流...' },
  'showcase.run.running': { en: 'Running...', 'zh-CN': '正在运行...' },
  'showcase.run.eventSourceUnavailable': {
    en: 'Streaming is not available in this browser.',
    'zh-CN': '当前浏览器不支持流式连接。',
  },
  'showcase.run.streaming': { en: 'Streaming...', 'zh-CN': '正在流式运行...' },
  'showcase.run.streamClosed': { en: 'Stream closed.', 'zh-CN': '流已关闭。' },
  'showcase.run.httpStatus': { en: 'HTTP {status}', 'zh-CN': 'HTTP {status}' },
  'showcase.run.failed': {
    en: 'Gateway run failed. Review technical details.',
    'zh-CN': '资源网关运行失败，请查看技术详情。',
  },
  'rehearsal.generated.blockerSummary': {
    en: '{failed} failed and {indeterminate} indeterminate blocker assertions',
    'zh-CN': '{failed} 个失败、{indeterminate} 个不确定的阻断断言',
  },
  'rehearsal.generated.warningSummary': {
    en: '{failed} failed and {indeterminate} indeterminate warnings',
    'zh-CN': '{failed} 个失败、{indeterminate} 个不确定警告',
  },
  'rehearsal.generated.mutableProjection': {
    en: 'Mutable {status} projection',
    'zh-CN': '可变的{status}投影',
  },
  'rehearsal.generated.rehearsalOwner': {
    en: '{project} rehearsal owner',
    'zh-CN': '{project} 演练负责人',
  },
  'rehearsal.generated.projectOwner': { en: '{project} owner', 'zh-CN': '{project} 负责人' },
  'rehearsal.generated.missingAuthorSource': {
    en: 'This plan does not advertise an Author source. Contact {owner}.',
    'zh-CN': '该计划未声明编排来源，请联系 {owner}。',
  },
  'layout.quality.geometrySummary': {
    en: 'Node overlaps {overlaps} · label collisions {collisions} · pinned nodes {pinned}',
    'zh-CN': '{overlaps} 个节点重叠 · {collisions} 个标签碰撞 · {pinned} 个固定节点',
  },
  'layout.quality.perception.passPass': {
    en: 'Geometry passes · readability passes · {titlePx}px title · {edgeLabels} edge labels · {density}/100k px',
    'zh-CN': '几何通过 · 可读性通过 · 标题 {titlePx}px · {edgeLabels} 个连线标签 · 密度 {density}/10万 px',
  },
  'layout.quality.perception.passReview': {
    en: 'Geometry passes · readability needs review · {titlePx}px title · {edgeLabels} edge labels · {density}/100k px',
    'zh-CN': '几何通过 · 可读性需检查 · 标题 {titlePx}px · {edgeLabels} 个连线标签 · 密度 {density}/10万 px',
  },
  'layout.quality.perception.reviewPass': {
    en: 'Geometry needs review · readability passes · {titlePx}px title · {edgeLabels} edge labels · {density}/100k px',
    'zh-CN': '几何需检查 · 可读性通过 · 标题 {titlePx}px · {edgeLabels} 个连线标签 · 密度 {density}/10万 px',
  },
  'layout.quality.perception.reviewReview': {
    en: 'Geometry needs review · readability needs review · {titlePx}px title · {edgeLabels} edge labels · {density}/100k px',
    'zh-CN': '几何需检查 · 可读性需检查 · 标题 {titlePx}px · {edgeLabels} 个连线标签 · 密度 {density}/10万 px',
  },
  'layout.quality.reason.nodeOverlaps': {
    en: 'Remaining node overlaps: {count}.',
    'zh-CN': '仍有 {count} 个节点重叠。',
  },
  'layout.quality.reason.nodeLabelCollisions': {
    en: 'Field labels suppressed by nodes: {count}.',
    'zh-CN': '{count} 个字段标签因节点遮挡而隐藏。',
  },
  'layout.quality.reason.labelLabelCollisions': {
    en: 'Field labels suppressed by other labels: {count}.',
    'zh-CN': '{count} 个字段标签因其他标签遮挡而隐藏。',
  },
  'layout.quality.reason.smallGraphZoom': {
    en: 'Small graph fit is below the 80% readability floor.',
    'zh-CN': '小型编排图的适配缩放低于 80% 可读性下限。',
  },
  'layout.quality.reason.titleSize': {
    en: 'Effective node title size is below 12px.',
    'zh-CN': '节点标题的有效字号低于 12px。',
  },
  'layout.quality.reason.overviewFields': {
    en: 'Overview exposes {count} field-level labels.',
    'zh-CN': '总览模式显示了 {count} 个字段级标签。',
  },
  'layout.quality.reason.labelDensity': {
    en: 'Visible label density is too high for reliable scanning.',
    'zh-CN': '可见标签密度过高，难以可靠浏览。',
  },
  'layout.quality.edgeCollision': {
    en: 'Edge {edgeId} label intersects node {nodeId}.',
    'zh-CN': '连线 {edgeId} 的标签与节点 {nodeId} 相交。',
  },
  'layout.notice.computing': { en: 'Computing layout preview...', 'zh-CN': '正在计算布局预览...' },
  'layout.notice.alreadyOptimal': {
    en: 'Layout is already optimal.',
    'zh-CN': '当前布局已是最优状态。',
  },
  'layout.notice.alreadyOptimalWithQuality': {
    en: 'Layout is already optimal · {overlaps} overlaps · {collisions} label collisions · {pinned} pinned.',
    'zh-CN': '当前布局已是最优状态 · {overlaps} 个重叠 · {collisions} 个标签碰撞 · {pinned} 个固定节点。',
  },
  'layout.notice.previewMoves': {
    en: 'Preview node moves: {count}.',
    'zh-CN': '预览将移动 {count} 个节点。',
  },
  'layout.notice.moved': { en: 'Node positions moved: {count}.', 'zh-CN': '已移动 {count} 个节点。' },
  'layout.notice.applied': {
    en: 'Moved node positions applied: {count} · overlaps {overlaps} · label collisions {collisions}.',
    'zh-CN': '已应用 {count} 个节点移动 · {overlaps} 个重叠 · {collisions} 个标签碰撞。',
  },
  'layout.notice.overrideApplied': {
    en: 'Advanced override applied · moved positions {count} · overlaps {overlaps} · label collisions {collisions}.',
    'zh-CN': '已对 {count} 个节点移动应用高级覆盖 · {overlaps} 个重叠 · {collisions} 个标签碰撞。',
  },
  'layout.notice.previewCanceled': {
    en: 'Layout preview canceled; original positions restored.',
    'zh-CN': '已取消布局预览并恢复原始位置。',
  },
  'layout.notice.computationCanceled': {
    en: 'Layout computation canceled.',
    'zh-CN': '已取消布局计算。',
  },
  'layout.notice.restored': {
    en: 'Restored node positions: {count}.',
    'zh-CN': '已恢复 {count} 个节点的位置。',
  },
  'layout.notice.nodeMovable': {
    en: 'Selected node will move with Auto Layout.',
    'zh-CN': '所选节点将随自动布局移动。',
  },
  'layout.notice.nodePinned': {
    en: 'Selected node is pinned to its current position.',
    'zh-CN': '所选节点已固定在当前位置。',
  },
  'layout.chrome.taskSurface': {
    en: 'The active task surface owns the workspace width.',
    'zh-CN': '当前任务工作区占用完整宽度。',
  },
  'layout.chrome.compactWorkspace': {
    en: 'Compact workspace keeps panels available as drawers.',
    'zh-CN': '紧凑工作区将面板保留为按需抽屉。',
  },
  'layout.chrome.graphOverview': {
    en: 'Panels were reclaimed for the graph overview.',
    'zh-CN': '已收起面板，为编排图总览释放空间。',
  },
  'layout.chrome.readabilityFloor': {
    en: 'Panels were reclaimed to keep the graph above its readability floor.',
    'zh-CN': '已收起面板，确保编排图达到可读性下限。',
  },
} as const satisfies Record<string, LocalizedMessage>;

export type MessageId = keyof typeof MESSAGE_CATALOG;

export interface MessageDescriptor {
  messageId: MessageId;
  params?: TranslationValues;
  rawCode?: string;
  rawDetail?: string;
}

const STATUS_IDS: Record<string, MessageId> = {
  SAVED: 'status.saved',
  EPHEMERAL: 'status.ephemeral',
  'NOT RUN': 'status.notRun',
  'NOT CONFIGURED': 'status.notConfigured',
  'NOT CHECKED': 'status.notChecked',
  'NOT EVALUATED': 'status.notEvaluated',
  VALID: 'status.valid',
  INVALID: 'status.invalid',
  CURRENT: 'status.current',
  STALE: 'status.stale',
  PASS: 'status.pass',
  FAIL: 'status.fail',
  RUNNABLE: 'status.runnable',
  READY: 'status.ready',
  RUNNING: 'status.running',
  DURABLE: 'status.durable',
  GOVERNED: 'status.governed',
  EXPLORATORY: 'status.exploratory',
};

export function translateMessage(
  locale: Locale,
  id: MessageId,
  values: TranslationValues = {},
): string {
  return interpolate(MESSAGE_CATALOG[id][locale], values);
}

export function statusMessageId(value: string): MessageId | null {
  return STATUS_IDS[value] ?? null;
}

export function messageCatalogErrors(): string[] {
  return Object.entries(MESSAGE_CATALOG).flatMap(([id, localized]) => {
    const errors: string[] = [];
    if (!/^[a-z][A-Za-z0-9]*(\.[a-z][A-Za-z0-9]*)+$/.test(id)) {
      errors.push(`${id}: message id must use stable dotted lower-camel segments`);
    }
    const english = placeholders(localized.en);
    const chinese = placeholders(localized['zh-CN']);
    if (english.join('|') !== chinese.join('|')) {
      errors.push(`${id}: placeholder sets differ (${english.join(',')} vs ${chinese.join(',')})`);
    }
    if (!localized.en.trim() || !localized['zh-CN'].trim()) {
      errors.push(`${id}: both locales require non-empty text`);
    }
    return errors;
  });
}

function placeholders(value: string): string[] {
  return [...value.matchAll(/\{([A-Za-z][A-Za-z0-9]*)\}/g)].map((match) => match[1]).sort();
}

function interpolate(template: string, values: TranslationValues): string {
  return template.replace(/\{([A-Za-z][A-Za-z0-9]*)\}/g, (match, key: string) => (
    Object.prototype.hasOwnProperty.call(values, key) ? String(values[key]) : match
  ));
}
