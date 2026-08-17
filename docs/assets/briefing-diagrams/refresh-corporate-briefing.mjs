import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const htmlPath = resolve(scriptDirectory, '../../resource-gateway-technical-architecture-briefing.html');
const nativeDirectory = join(scriptDirectory, 'native');
const readSvg = (name) => readFileSync(join(nativeDirectory, name), 'utf8').trim();

const operations = readSvg('intent-driven-operations-workflow.svg');
const delivery = readSvg('asset-delivery-collaboration.svg');
let html = readFileSync(htmlPath, 'utf8');

const galleryMatch = html.match(/      <!-- BEGIN INLINE NATIVE ARCHITECTURE GALLERY -->[\s\S]*?      <!-- END INLINE NATIVE ARCHITECTURE GALLERY -->\n/u);
if (!galleryMatch) throw new Error('Missing native architecture gallery');
const gallery = galleryMatch[0].trimEnd();

const content = `
<nav class="report-nav" aria-label="汇报章节导航">
  <div class="nav-logo">BLOGE · RESOURCE GATEWAY</div>
  <div class="nav-links">
    <a href="#case">结论</a>
    <a href="#why-change">为何改变</a>
    <a href="#dsl-assets">DSL 资产</a>
    <a href="#operations-shift">运营升级</a>
    <a href="#blueprint">运行架构</a>
    <a href="#testability">正确性证明</a>
    <a href="#delivery-value">交付与价值</a>
    <a href="#roadmap">采用路径</a>
  </div>
  <button class="theme-toggle" id="themeToggle" type="button" aria-label="切换明亮与低光主题">
    <span class="theme-dark" aria-hidden="true">◐</span><span class="theme-light" aria-hidden="true">◑</span>
  </button>
</nav>

<main>
  <section class="report-section hero" id="case">
    <div class="container hero-layout">
      <div class="hero-copy fade-in">
        <div class="hero-badge">RESOURCE GATEWAY · 技术架构汇报</div>
        <h1>把业务集成建设成可编排、可验证、可持续演进的业务资产</h1>
        <p class="lead">Resource Descriptor、BLOGE DSL 与 Schema Contract 共同描述业务语义；通用执行内核负责稳定运行；Scenario、Coverage 与签名 Evidence 负责证明变更。运营人员据此从逐字段配置转向业务意图、正确答案和发布决策。</p>
        <div class="hero-summary" aria-label="汇报核心摘要">
          <div><small>战略目标</small><strong>把一次性接口集成转化为可复用的业务能力资产。</strong></div>
          <div><small>业务结果</small><strong>缩短变更周期，同时降低缺陷逃逸和重复建设。</strong></div>
          <div><small>关键突破</small><strong>让 Coding Agent（编码智能体）在受控 DSL、测试和证据体系内承担工程劳动。</strong></div>
        </div>
        <p class="fact-note">材料范围：Resource Gateway README、测试运行时隔离 ADR、工业级可测试性方案、Contract / Scenario Authoring 与 Test Kit 设计。</p>
      </div>
      <div class="hero-visual fade-in" aria-label="信用兜底规则变更示例">
        <div class="hero-case">
          <div class="hero-case-head"><small>贯穿案例 · 仓库内置信用兜底流程</small><strong>业务希望调整主授信超时后的备选服务规则</strong></div>
          <div class="hero-case-step" data-step="1"><strong>运营表述业务意图</strong><span>完成条件、适用范围、重试边界和预期结果。</span></div>
          <div class="hero-case-step" data-step="2"><strong>编码智能体生成资产变更</strong><span>定位 Graph、Descriptor、Scenario 与依赖，形成可审查 Diff。</span></div>
          <div class="hero-case-step" data-step="3"><strong>平台执行确定性验证</strong><span>覆盖主服务超时、备选成功、全部失败和副作用边界。</span></div>
          <div class="hero-case-step result" data-step="4"><strong>授权人员依据 Evidence 发布</strong><span>变更、路径、覆盖、版本和风险接受均可追溯。</span></div>
        </div>
      </div>
    </div>
  </section>

  <section class="report-section tinted" id="why-change">
    <div class="container">
      <header class="section-header fade-in">
        <div class="section-index">01 · 决策问题：为何改变</div>
        <h2>Resource Gateway 的价值不在多接一个 API，而在保存可复用的业务语义</h2>
        <p class="lead">外部 API、模型服务和内部系统持续变化。若流程、异常策略和验证知识仍锁在项目代码或配置页面中，团队只能重复解释、重复实现、上线后再观察。</p>
      </header>
      <div class="case-context fade-in"><strong>案例中的损失：</strong>一次信用兜底规则调整同时涉及调用顺序、超时、重试、结果映射和失败策略。传统方式需要在多个配置入口和代码位置查找关联关系，业务人员很难在发布前确认完整语义。</div>
      <div class="pressure-grid fade-in">
        <article class="pressure-item"><span>知识不可见</span><strong>业务逻辑埋在服务代码和配置入口</strong><p>调用顺序、分支、降级和完成条件难以共同审阅。</p></article>
        <article class="pressure-item"><span>变更高耦合</span><strong>每个 Provider 复制一套实现</strong><p>供应商差异扩散为算子类、测试、发布和运维成本。</p></article>
        <article class="pressure-item"><span>质量后置</span><strong>运行成功被误当成业务正确</strong><p>算子单测无法证明 DAG 的分支、重试和组合语义。</p></article>
      </div>
      <div class="logic-chain chapter-block fade-in" aria-label="业务能力资产化路径">
        <div class="logic-step"><small>外部能力</small><strong>API / 事件 / 函数</strong><span>来源多样、变化频繁</span></div>
        <div class="logic-step"><small>资源契约</small><strong>Descriptor + Schema</strong><span>统一调用和数据边界</span></div>
        <div class="logic-step"><small>业务语义</small><strong>Graph + Decision</strong><span>显式表达流程与策略</span></div>
        <div class="logic-step"><small>正确性证明</small><strong>Scenario + Evidence</strong><span>冻结案例、路径与结果</span></div>
        <div class="logic-step"><small>组织复用</small><strong>Publication + Catalog</strong><span>按版本发布和演进</span></div>
      </div>
      <div class="chapter-block fade-in">
        <div class="chapter-label">架构投入对应的业务结果</div>
        <div class="outcome-grid">
          <article class="outcome-item"><div class="outcome-number">交付效率</div><h3>缩短新场景与规则变更周期</h3><p>优先装配 Descriptor、Graph 模板和测试资产，减少重复编码和跨团队解释。</p><div class="kpi-line">观察口径：需求确认至可发布版本的周期</div></article>
          <article class="outcome-item"><div class="outcome-number">变更质量</div><h3>把影响识别和验证前移</h3><p>Fingerprint、Diff、Impact 和精确版本测试在发布前暴露不兼容与覆盖缺口。</p><div class="kpi-line">观察口径：失败率、回滚率、缺陷逃逸率</div></article>
          <article class="outcome-item"><div class="outcome-number">资产复用</div><h3>减少专属 Operator 和孤立配置</h3><p>资源、Graph、Scenario 与 Evidence 进入统一生命周期。</p><div class="kpi-line">观察口径：复用资产占比、新增专属实现数量</div></article>
          <article class="outcome-item"><div class="outcome-number">风险质量</div><h3>形成可审计的发布依据</h3><p>测试结果绑定目标、执行计划、Trace、Coverage 与签名 Evidence。</p><div class="kpi-line">观察口径：关键路径覆盖、证据完整率、定位时长</div></article>
        </div>
      </div>
    </div>
  </section>

  <section class="report-section" id="dsl-assets">
    <div class="container">
      <header class="section-header fade-in">
        <div class="section-index">02 · 决策问题：为什么要 DSL 化</div>
        <h2>DSL 把业务知识从实现细节转化为可发现、可变更、可验证的资产</h2>
        <p class="lead">流程、分支、决策表、超时、重试、降级与聚合获得稳定身份和版本。业务语义可以被人审阅，也可以被编码智能体和工程工具安全处理。</p>
      </header>
      <div class="asset-formula fade-in" aria-label="可经营业务资产的构成">
        <b>业务语义</b><i>+</i><b>Schema 契约</b><i>+</i><b>稳定身份与版本</b><i>+</i><b>测试证据</b><i>+</i><b>Owner 与治理策略</b><i>=</i><b>可经营业务资产</b>
      </div>
      <div class="asset-map fade-in">
        <div class="asset-stack">
          <h3>从外部能力到可复用业务产品</h3>
          <div class="asset-layer amber"><strong>资源资产 · ResourceDescriptor</strong><span>端点、参数映射、认证、响应协议与载荷提取</span></div>
          <div class="asset-layer purple"><strong>能力资产 · Operator / Function Library</strong><span>输入输出、运行绑定、兼容语义与影响分析</span></div>
          <div class="asset-layer"><strong>业务资产 · BLOGE Graph / Decision Table</strong><span>并发、分支、聚合、重试、超时与降级</span></div>
          <div class="asset-layer green"><strong>产品资产 · Publication</strong><span>冻结依赖、契约、版本和发布门禁</span></div>
        </div>
        <div class="asset-support">
          <h3>质量资产说明它为什么可信</h3>
          <div class="support-list">
            <div class="support-item"><strong>Contract</strong><span>明确输入、输出、兼容性和系统边界</span></div>
            <div class="support-item"><strong>Scenario / FixtureBundle</strong><span>冻结业务案例、依赖行为和预期结果</span></div>
            <div class="support-item"><strong>EffectiveExecutionPlan</strong><span>在运行前解析真实、替换、回放和拒绝的调用点</span></div>
            <div class="support-item"><strong>Trace / Coverage / Evidence</strong><span>记录路径、尝试、断言、覆盖、版本与签名事实</span></div>
            <div class="support-item"><strong>Diff / Impact / Stale</strong><span>资产变化后定位受影响的 Graph、Suite 与发布物</span></div>
          </div>
        </div>
      </div>
      <div class="asset-lifecycle fade-in" aria-label="业务资产生命周期">
        <div class="lifecycle-step"><strong>发现</strong><span>API / DSL / Catalog</span></div>
        <div class="lifecycle-step"><strong>创作</strong><span>Intent / ChangeSet</span></div>
        <div class="lifecycle-step"><strong>编译</strong><span>Canonical Contract</span></div>
        <div class="lifecycle-step"><strong>验证</strong><span>Schema / Impact</span></div>
        <div class="lifecycle-step"><strong>测试</strong><span>Scenario / Evidence</span></div>
        <div class="lifecycle-step"><strong>发布</strong><span>Revision / Gate</span></div>
        <div class="lifecycle-step"><strong>演进</strong><span>Trace / Stale</span></div>
      </div>
      <div class="grid-3 chapter-block fade-in">
        <article class="card"><div class="item-kicker">版本控制</div><h3>文本资产支持 Diff、评审与回滚</h3><p>编码智能体可以提出可审查变更，不直接修改运行态。</p></article>
        <article class="card"><div class="item-kicker">静态分析</div><h3>Schema 提供确定的工具边界</h3><p>生成、补全和影响分析建立在可机器校验的契约上。</p></article>
        <article class="card result"><div class="item-kicker">测试自动化</div><h3>Scenario 让正确答案进入 CI</h3><p>每次资产变更都可以复用回归、覆盖和发布门禁。</p></article>
      </div>
      <div class="callout evidence fade-in chapter-block"><strong>业务重要性：</strong>DSL 化把复用单位从代码库提升为带契约、Owner、版本和证据的业务能力。运营、领域、测试与平台团队可以围绕同一个可执行事实协作。</div>
    </div>
  </section>

  <section class="report-section tinted" id="operations-shift">
    <div class="container-wide">
      <header class="section-header fade-in">
        <div class="section-index">03 · 决策问题：运营怎样工作</div>
        <h2>运营人员负责业务意图与正确答案，编码智能体承担工程化变更</h2>
        <p class="lead">运营人员表达目标、适用范围、优先级、例外和验收标准。编码智能体复用成熟的代码检索、版本控制、静态分析、测试生成和 CI 工具，完成资产定位、变更草案、影响分析和验证执行。</p>
      </header>
      <div class="operations-thesis fade-in">
        <article class="role-shift">
          <small>角色变化</small>
          <strong>从「配置执行者」转为「业务意图与正确性负责人」</strong>
          <p>运营人员把精力放在业务目标、规则边界、异常处置和预期结果。字段定位、依赖查找、DSL 修改、测试补齐与影响分析由编码智能体在受控工具链内完成。</p>
          <div class="role-boundary"><b>责任边界：</b>编码智能体生成可审查的方案，不替业务定义正确答案。业务 Oracle、风险接受和发布批准仍由授权人员确认；高风险变更进入双人复核或专门治理门禁。</div>
        </article>
        <div>
          <p class="scroll-hint">横向滑动查看传统配置与意图驱动工作的完整对比</p>
          <div class="shift-table" role="table" aria-label="传统配置与意图驱动运营工作方式对比">
            <div class="shift-row header" role="row"><div role="columnheader">比较维度</div><div role="columnheader">传统配置工作</div><div role="columnheader">意图表述与验证工作</div></div>
            <div class="shift-row" role="row"><div role="cell">管理对象</div><div role="cell">页面、字段、参数和值</div><div role="cell">业务目标、约束、规则、案例和证据</div></div>
            <div class="shift-row" role="row"><div role="cell">主要动作</div><div role="cell">查找入口、逐项填写、人工核对</div><div role="cell">表述意图、审阅 Diff、确认结果、批准发布</div></div>
            <div class="shift-row" role="row"><div role="cell">知识复用</div><div role="cell">操作手册、个人经验、复制模板</div><div role="cell">资产目录、Schema、DSL、Scenario 与历史 Evidence</div></div>
            <div class="shift-row" role="row"><div role="cell">质量控制</div><div role="cell">配置后抽查或上线后观察</div><div role="cell">编译、静态校验、测试、覆盖分析与发布门禁</div></div>
            <div class="shift-row" role="row"><div role="cell">变更记录</div><div role="cell">保留最终状态，过程难以还原</div><div role="cell">意图、变更集、评审、测试与 Evidence 全程可追溯</div></div>
            <div class="shift-row" role="row"><div role="cell">改进依据</div><div role="cell">人工复盘和局部运行指标</div><div role="cell">失败路径、覆盖缺口、人工修正和资产版本</div></div>
          </div>
        </div>
      </div>
      <div class="diagram-shell native-report-diagram fade-in">
        <div class="diagram-head"><strong>意图驱动的运营变更工作流</strong><span class="diagram-hint">失败返回具体资产，高风险与语义不确定项返回授权人员</span></div>
        <div class="diagram-viewport native-svg-viewport">
<!-- BEGIN INLINE NATIVE OPERATIONS -->
${operations}
<!-- END INLINE NATIVE OPERATIONS -->
        </div>
      </div>
      <div class="engineering-grid fade-in" aria-label="可复用的软件工程方法">
        <article class="engineering-card"><small>VERSIONING</small><strong>版本控制与变更评审</strong><p>稳定 ID、Diff、历史回溯和可恢复发布。</p></article>
        <article class="engineering-card"><small>CONTRACT</small><strong>Schema 与静态分析</strong><p>执行前发现类型、绑定、兼容性和依赖问题。</p></article>
        <article class="engineering-card"><small>TESTING</small><strong>测试分层与回归套件</strong><p>从 Schema Contract 到完整 DAG 的分层证明。</p></article>
        <article class="engineering-card"><small>GENERATIVE</small><strong>边界、属性与变异测试</strong><p>用 Schema 生成边界，并检验测试的防回归能力。</p></article>
        <article class="engineering-card"><small>DELIVERY</small><strong>CI 与质量门禁</strong><p>把编译、测试、覆盖和 Evidence 固化为发布条件。</p></article>
        <article class="engineering-card"><small>OBSERVABILITY</small><strong>Trace 与过程质量指标</strong><p>按节点、路径、规则、Fixture 和版本归因。</p></article>
      </div>
      <div class="operations-kpis fade-in" aria-label="运营工作方式度量建议">
        <div class="operations-kpi"><strong>效率</strong><span>人工配置时长、意图到可验证方案周期、建议采纳率</span></div>
        <div class="operations-kpi"><strong>正确性</strong><span>首次验证通过率、配置缺陷逃逸率、回滚率</span></div>
        <div class="operations-kpi"><strong>覆盖</strong><span>关键路径、规则、边界、负向和外部依赖覆盖</span></div>
        <div class="operations-kpi"><strong>过程质量</strong><span>自动校验覆盖率、Evidence 完整率、人工例外及修复周期</span></div>
      </div>
      <div class="callout attention fade-in chapter-block"><strong>适用边界：</strong>规则明确、低频且低风险的稳定配置可以继续使用表单。跨系统、规则密集、变化频繁或需要严格证明的业务资产优先进入意图驱动工作流；两种方式共享同一 Canonical Contract。</div>
    </div>
  </section>

  <section class="report-section" id="blueprint">
    <div class="container-wide">
      <header class="section-header fade-in">
        <div class="section-index">04 · 决策问题：架构如何承接</div>
        <h2>三个视图连接 DSL 资产、稳定执行内核与正确性证据</h2>
        <p class="lead">运行时只消费已批准资产；业务请求沿稳定主链执行；测试控制带外进入隔离运行时；Trace 与 Evidence 回到发布治理。</p>
      </header>
${gallery}
      <div class="callout evidence fade-in chapter-block"><strong>边界结论：</strong>业务 DSL、Schema 与版本决定「执行什么」；带外执行计划决定「本次测试如何执行」；Trace、Coverage 与签名 Evidence 记录「实际发生了什么」。三类事实分离后，编码智能体才能安全修改资产，独立工具也能重复验证结果。</div>
      <div class="grid-3 fade-in chapter-block">
        <article class="card"><div class="item-kicker">主信息流</div><h3>业务请求只消费已批准资产</h3><p>Graph Engine 解析冻结 DSL，HttpResourceOperator 根据 Descriptor 访问外部系统。</p></article>
        <article class="card attention"><div class="item-kicker">管理控制</div><h3>版本、策略和测试计划带外进入</h3><p>控制信息不混入 GraphContext；生产 purpose 发现非空测试计划时失败关闭。</p></article>
        <article class="card result"><div class="item-kicker">证据回流</div><h3>运行事实回绑资产与发布决策</h3><p>Trace 连接 Graph、Runtime、Fixture 和 Plan 指纹，支持重放、Impact 与晋级门禁。</p></article>
      </div>
    </div>
  </section>

  <section class="report-section tinted" id="testability">
    <div class="container-wide">
      <header class="section-header fade-in">
        <div class="section-index">05 · 决策问题：如何证明正确</div>
        <h2>可测试性衡量业务变更能否被安全、确定、可重复地证明</h2>
        <p class="lead">单个 Operator 可测试只是必要条件。DAG 仍可能在 binding、edge、branch、join、retry、fallback、decision table 和非确定性依赖上产生业务错误。</p>
      </header>
      <div class="case-context fade-in"><strong>案例中的证明责任：</strong>信用兜底规则不仅要证明两个 HTTP 调用都能成功，还要证明主服务超时后才进入备选分支、重试次数符合策略、全部失败时返回受控结果，并且测试运行没有触达生产写依赖。</div>
      <div class="proof-formula fade-in">可寻址依赖 + 版本化 Fixture + 确定执行计划 + 语义覆盖 + 可验证 Evidence = 可晋级业务资产</div>
      <p class="scroll-hint fade-in">横向滑动查看 L0–L6 正确性证明层级</p>
      <div class="proof-ladder-shell fade-in" aria-label="L0 到 L6 正确性证明层级">
        <div class="proof-ladder">
          <article class="proof-level"><small>L0</small><strong>Schema Contract</strong><span>契约与 Fixture 自洽；不证明真实实现行为。</span></article>
          <article class="proof-level"><small>L1</small><strong>Operator Unit</strong><span>以单节点微图执行真实 Runtime Binding。</span></article>
          <article class="proof-level"><small>L2</small><strong>Subgraph Component</strong><span>验证局部业务语义，虚拟化边界依赖。</span></article>
          <article class="proof-level"><small>L3 · 主力层</small><strong>Graph Contract</strong><span>执行完整 DAG，只替换外部系统边界。</span></article>
          <article class="proof-level"><small>L4</small><strong>Adapter / Sandbox</strong><span>验证协议、序列化、认证与环境适配。</span></article>
          <article class="proof-level"><small>L5</small><strong>Replay / Differential</strong><span>对比新旧版本的输出、路径、错误和副作用意图。</span></article>
          <article class="proof-level"><small>L6</small><strong>Production Observation</strong><span>补充真实 SLO 与漂移事实；不使用 Fixture。</span></article>
        </div>
      </div>
      <div class="phase-grid fade-in">
        <article class="phase-card"><small>PHASE 01</small><h3>计划阶段 · 先证明可安全执行</h3><p>冻结 target、schema、runtime 和 fixture 版本；零命中、歧义和越权调用在执行前拒绝。</p></article>
        <article class="phase-card"><small>PHASE 02</small><h3>执行阶段 · 真实语义与替身同轨运行</h3><p>BLOGE Engine 只消费已批准计划，逐调用记录 REAL、MOCK、FAULT、REPLAY、DENY 与 consumption。</p></article>
        <article class="phase-card"><small>PHASE 03</small><h3>证据阶段 · 结果回绑精确版本</h3><p>输出 Trace、Assertion、Semantic Coverage 与签名 Evidence，由独立 Test Kit 重新验证。</p></article>
      </div>
      <div class="invariant-grid fade-in">
        <div class="invariant"><strong>业务数据保持纯净</strong><span>测试控制不从 GraphContext 读取。</span></div>
        <div class="invariant"><strong>生产数据面失败关闭</strong><span>Production purpose 拒绝任何非空 Control Plan。</span></div>
        <div class="invariant"><strong>替身消费可以核验</strong><span>Required Fixture 未命中、未消费或超额使用均失败。</span></div>
        <div class="invariant"><strong>外部写默认拒绝</strong><span>只有 Sandbox Binding、专用身份和策略同时满足才允许。</span></div>
      </div>
      <div class="truth-separation fade-in"><b>PASSED</b><em>≠</em>Coverage 满足<em>≠</em>Promotion Eligible<em>≠</em>Evidence Verified<em>≠</em>组织批准发布</div>
      <div class="callout evidence fade-in chapter-block"><strong>业务意义：</strong>发布前可以回答哪条业务路径被验证、哪些外部调用被替换、哪个版本产生结果、失败来自执行还是断言，以及这份结果能否用于晋级。质量由个人经验转化为组织证据。</div>
    </div>
  </section>

  <section class="report-section" id="delivery-value">
    <div class="container-wide">
      <header class="section-header fade-in">
        <div class="section-index">06 · 决策问题：如何交付和衡量</div>
        <h2>意图、变更集、合同、执行计划和 Evidence 构成同一条交付主线</h2>
        <p class="lead">业务 Owner、运营人员、编码智能体、平台工程与质量治理围绕同一组可执行资产协作。失败返回对应资产修改，不在线下形成第二套业务事实。</p>
      </header>
      <div class="workflow-io fade-in">
        <div><small>触发输入</small><strong>业务目标、完成条件、API / 事件契约、风险等级</strong></div>
        <div class="output"><small>完成输出</small><strong>冻结发布物、质量门禁结果、可验证 Evidence、资产 Owner</strong></div>
      </div>
      <p class="scroll-hint fade-in">横向滑动查看角色、交付产物与诊断返回路径</p>
      <div class="diagram-shell native-report-diagram fade-in">
        <div class="diagram-head"><strong>业务资产交付协作模型</strong><span class="diagram-hint">业务批准与质量门禁共同决定晋级</span></div>
        <div class="diagram-viewport native-svg-viewport">
<!-- BEGIN INLINE NATIVE DELIVERY -->
${delivery}
<!-- END INLINE NATIVE DELIVERY -->
        </div>
      </div>
      <div class="chapter-block fade-in">
        <div class="chapter-label">从业务结果追溯到架构能力和运行证据</div>
        <div class="trace-layout">
          <div class="trace-selectors" aria-label="选择业务结果">
            <button class="trace-button" type="button" data-trace="delivery" aria-pressed="true">缩短新场景交付周期</button>
            <button class="trace-button" type="button" data-trace="quality" aria-pressed="false">降低变更失败风险</button>
            <button class="trace-button" type="button" data-trace="reuse" aria-pressed="false">提高业务资产复用率</button>
            <button class="trace-button" type="button" data-trace="proof" aria-pressed="false">形成可审计质量证明</button>
            <button class="trace-button" type="button" data-trace="operations" aria-pressed="false">升级运营工作方式</button>
          </div>
          <div class="trace-panel" aria-live="polite">
            <div class="item-kicker" id="traceKicker">RESULT 01 · DELIVERY</div>
            <h3 id="traceTitle">从复制集成代码，转向装配已验证资产</h3>
            <div class="trace-columns">
              <div class="trace-column"><small>架构承接</small><strong id="traceCapability">Resource Registry · Operator Library · Graph DSL</strong><p id="traceCapabilityDesc">外部能力完成一次契约化后，可以进入多个 Graph 复用。</p></div>
              <div class="trace-column"><small>运行证据</small><strong id="traceEvidence">Revision · Compile Result · Publication</strong><p id="traceEvidenceDesc">证明创作输入、Canonical 产物和发布依赖处于同一精确版本。</p></div>
              <div class="trace-column"><small>观察指标</small><strong id="traceMetric">端到端交付周期</strong><p id="traceMetricDesc">同时观察复用资产占比与新增 Java Operator 数量，避免以降低质量换取短期速度。</p></div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </section>

  <section class="report-section tinted" id="roadmap">
    <div class="container">
      <header class="section-header fade-in">
        <div class="section-index">07 · 决策问题：怎样采用</div>
        <h2>先在一条高价值业务链建立完整资产流程，再依据结果和证据扩大范围</h2>
        <p class="lead">现有实现已经具备 Descriptor、DSL、Graph Contract、隔离测试与 Test Kit 基础。采用路径的目标是把这些技术能力转化为稳定的组织工作方式。</p>
      </header>
      <div class="grid-4 fade-in">
        <article class="card roadmap-card"><div class="item-kicker">阶段一 · 运营试点</div><h3>选定一条真实业务链</h3><p class="scope">明确业务 Owner、完成条件和风险边界；由运营描述变更意图，编码智能体生成 DSL 与测试草案。</p><div class="exit">退出条件：关键路径可重复执行，失败可定位，业务 Oracle 由授权人员确认。</div></article>
        <article class="card roadmap-card"><div class="item-kicker">阶段二 · 资产目录</div><h3>把交付物升级为可复用产品</h3><p class="scope">建立 Descriptor、Library、Graph、Scenario、Fixture 的身份、版本、Owner、Diff 与生命周期。</p><div class="exit">退出条件：同类新集成优先装配已有资产，不再默认新增专属 Operator 类。</div></article>
        <article class="card roadmap-card"><div class="item-kicker">阶段三 · 质量门禁</div><h3>把证据接入 CI 与发布决策</h3><p class="scope">固化分层证明、EffectiveExecutionPlan、Semantic Coverage、Signed Evidence 与 Test Kit 验证。</p><div class="exit">退出条件：发布绑定精确证据；生产环境结构性缺失测试控制能力。</div></article>
        <article class="card roadmap-card result"><div class="item-kicker">阶段四 · 资产经营</div><h3>跨团队衡量复用与演进质量</h3><p class="scope">建立资产发现、兼容策略、Impact、Stale、退役与跨域复用机制。</p><div class="exit">退出条件：交付周期、复用率、变更失败率和证据完整率形成稳定看板。</div></article>
      </div>
      <div class="decision-request fade-in">
        <div class="chapter-label">需要确认的三项组织级选择</div>
        <div class="decision-grid">
          <article class="decision-item"><div class="decision-no">DECISION 01</div><h3>确立 DSL 资产与编码智能体工作界面</h3><p>Graph、Decision、Descriptor、Scenario 与 Fixture 具备 Owner、版本和兼容策略，并暴露可发现的 Schema、工具和验证契约。</p></article>
          <article class="decision-item"><div class="decision-no">DECISION 02</div><h3>把可测试性列为设计验收条件</h3><p>新增能力声明依赖、非确定性和副作用，能够进入 L1 / L3 测试链并产出精确 Evidence。</p></article>
          <article class="decision-item"><div class="decision-no">DECISION 03</div><h3>以一条运营工作流建立度量基线</h3><p>共同确认人工配置时长、意图到可验证方案周期、首次验证通过率、语义覆盖和证据完整率的口径与 Owner。</p></article>
        </div>
      </div>
      <div class="material-basis fade-in">材料基线：仓库内 Resource Gateway README、测试运行时隔离 ADR、工业级可测试性方案、Contract / Scenario Authoring、表格驱动测试和 Test Kit 设计。页面只使用仓库已确认事实；业务指标目标值待试点建立基线后确认。</div>
    </div>
  </section>
</main>

<footer class="report-footer">BLOGE · RESOURCE GATEWAY · 业务资产 DSL 化与可测试性架构 · 2026-08-16</footer>`;

const pagePattern = /<nav class="report-nav"[\s\S]*?<\/footer>/u;
if (!pagePattern.test(html)) throw new Error('Missing report content target');
html = html.replace(pagePattern, content.trim());
writeFileSync(htmlPath, html);
