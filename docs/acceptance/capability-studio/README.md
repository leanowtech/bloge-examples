# Capability Studio 验收制品

> 当前状态：`NO_GO`
>
> 本目录记录 Capability Studio 的治理边界、验收合同、证据追踪和未通过实例。GP-01 至 GP-03 已形成只读纵向切片，GP-04 正在形成可编辑教程分支；任何局部完成都不表示整体产品验收通过，也不替代产品与架构签署。

## 1. 制品清单

| 制品 | 路径 | 用途 | 当前结论 |
|---|---|---|---|
| 资产投影 ADR | [`ADR-007`](../../adr/ADR-007-capability-studio-asset-projections-and-compiler-boundary.md) | 固定对象权威、编译和生产隔离边界 | `Proposed` |
| Acceptance Baseline v1 | [`capability-studio-acceptance-baseline-v1.json`](capability-studio-acceptance-baseline-v1.json) | 冻结 GP、黄金包、Spike、可用性、安全和 NFR 门禁 | `NO_GO` |
| Golden Path Manifest Schema | [`capability-studio-golden-path-acceptance-manifest-v1.schema.json`](../../schemas/resource-gateway-capability-studio/capability-studio-golden-path-acceptance-manifest-v1.schema.json) | 约束发布候选验收证据的机器结构 | 可消费，未证明通过 |
| Golden Path NO_GO fixture | [`capability-studio-golden-path-acceptance-manifest-v1.no-go.fixture.json`](capability-studio-golden-path-acceptance-manifest-v1.no-go.fixture.json) | 提供真实的初始缺证据状态 | `NO_GO` |
| Screen State Inventory v1 | [`screen-state-inventory-v1.md`](screen-state-inventory-v1.md) | 逐个 GP 固化页面状态和恢复动作 | 实现大多 `MISSING`/`PARTIAL` |
| Requirement Traceability v1 | [`requirement-to-evidence-traceability-matrix-v1.md`](requirement-to-evidence-traceability-matrix-v1.md) | 把 GP、Spike、安全和 NFR 绑定到证据与责任人 | 缺口已显式列出 |

跨工具 JSON Schema 位于 [`docs/schemas/resource-gateway-capability-studio/`](../../schemas/resource-gateway-capability-studio/)，因为它们是稳定协议，而不是某一个验收运行的私有 fixture。

## 2. 当前事实

审计确认现有仓库拥有可复用底座，包括 Graph/Canvas、Correctness Studio、Scenario/Fixture 编译、Mirror 隔离和 Run Evidence。当前已经新增：

- 默认 `/capabilities/` 入口和只读 `CapabilityAsset` 产品投影；
- 4 API、1 Feature、1 Tool、9 Case 的 payload-free Canonical Golden Demo Pack；
- Canonical Baseline 与 Tutorial Branch 的不可混用引用及加载期不变量；
- GP-01 至 GP-03 的组件、严格协议、真实 HTTP 路由和启动脚本探针；
- GP-01 至 GP-03 的中文真实 Chrome 烟测，覆盖 1440×900、1024×768 和 390×844；
- Capability Studio 的 Baseline/Manifest Schema、初始 `NO_GO` fixture 和追踪矩阵。
- GP-04 的教程分支业务句式编辑器，用户通过条件、表现和持续时间配置超时，无需编辑 Mock JSON；
- GP-04 的数据库 head、immutable revision、严格保存协议和隔离预检，以及 409 冲突保留输入、结构化恢复动作和断网分类测试；
- GP-04 的 SQL 原子 optimistic CAS、同版本并发单赢家、stale 同内容重试幂等、Authority 重建恢复和 Canonical Baseline 漂移失败关闭测试；
- GP-04 的四份严格 JSON Schema、独立 Test Kit 内容指纹重算和真实 HTTP before/after/preflight 互验；
- GP-04 中文 1440×900 真实 Chrome 保存与预检烟测。
- Scenario Dataset v1 的严格 payload-free 投影 Schema、由 Golden Demo Pack 确定性生成的服务端投影端点，以及独立 Test Kit 的指纹、Scope、引用闭包、质量计数和 Active readiness 验证器；
- GP-03 已从静态九行表切换为真实 Dataset master-detail 视图，显示 Dataset 分母、生命周期、分类、Owner、五项质量覆盖、Case 业务目标、来源、Oracle、适用契约、依赖表现与精确引用；协议、网络或语义校验失败时拒绝展示并提供恢复动作；
- GP-03 中文 1440×900 与 390×844 真实 Chrome 烟测，覆盖 Dataset 摘要、质量状态、九条 Case、超时案例详情、移动端筛选与无横向溢出。
- Dataset 到既有 `ScenarioDraftSet` 的确定性编译适配器，覆盖 9 Case、RETURN/ERROR/TIMEOUT/顺序消费/`MUST_NOT_CALL`、rule source map、三次确定性和 fail-closed 负向语义；尚未生成 FixtureBundle/TestSuite 或运行证据；
- 生产运行协议边界已扩展到 fixture、stub、binding override、dependency behavior 和 Dataset 字段族，并在五类运行入口、三组 production profile 上验证 DTO 前拒绝、审计失败关闭和 Payload 不泄漏；
- GP-01/03/04 英文 1440×900、1024×768、390×844 真实 Chrome 证据，Dataset 的 Tab/Enter/Space 键盘路径，以及六种组件状态与真实浏览器完整 axe-core 检查；真实 axe 曾检出并推动修复选中场景辅助文字对比度问题。

仍未完成的是持久化且具备权限边界的 Dataset Authority、Dataset 到 `ScenarioGovernedCompiler`/FixtureBundle/TestSuite 的端到端 lowering、Feature DAG Data Lens、隔离运行、9/9 三次语义一致性、完整 zero-egress 运行证明、契约与 Tutorial 全键盘/人工读屏/并发浏览器矩阵、GP-05 至 GP-10 的完整自动化和六人可用性签署。当前 `/scenario-dataset` 是 Stage 0 Golden Demo Pack 的不可变业务投影，不是客户生产 Dataset 的写入 Authority。

因此 Baseline 和 Manifest 必须保持 `NO_GO`、`PENDING` 或 `NOT_RUN`。元数据可读、开发自动化通过和启动探针成功，只能证明当前纵向切片可演示，不能冒充 Capability Studio 产品验收通过。

### 2.1 当前浏览器证据

以下截图由 `CapabilityStudioBrowserAcceptanceTest` 启动真实 Spring Boot 服务，并通过 headless Chrome 执行 GP-01 至 GP-04 后生成。自动化同时断言资产数量、API 选择、九行场景、中文业务状态文案、内部状态码不泄漏、GP-04 实际保存与隔离预检，以及页面级无横向溢出。

| 视口 | 覆盖内容 | 证据 |
|---|---|---|
| 1440×900 | GP-01 总览、GP-02 API 选择、GP-03 Dataset 分母、质量摘要、九条 Case 与超时案例详情 | [`capability-studio-gp01-gp03-zh-1440.png`](../../assets/capability-studio/capability-studio-gp01-gp03-zh-1440.png) |
| 1440×900 | GP-04 业务句式编辑、分支边界、保存后的隔离预检 | [`capability-studio-gp04-zh-1440.png`](../../assets/capability-studio/capability-studio-gp04-zh-1440.png) |
| 1024×768 | GP-01 紧凑桌面布局 | [`capability-studio-gp01-zh-1024.png`](../../assets/capability-studio/capability-studio-gp01-zh-1024.png) |
| 390×844 | GP-03 移动端任务选择、Dataset 摘要 | [`capability-studio-gp03-zh-390.png`](../../assets/capability-studio/capability-studio-gp03-zh-390.png) |
| 390×844 | GP-03 五项质量覆盖、搜索、筛选与有界 Case 列表 | [`capability-studio-gp03-quality-zh-390.png`](../../assets/capability-studio/capability-studio-gp03-quality-zh-390.png) |
| 1440×900 | 英文 GP-01/03、Dataset 质量与 Case 详情；真实 axe serious/critical 为 0 | [`capability-studio-gp01-gp03-en-1440.png`](../../assets/capability-studio/capability-studio-gp01-gp03-en-1440.png) |
| 1024×768 | 英文 GP-04 Tutorial Branch 紧凑桌面 | [`capability-studio-gp01-gp03-en-1024.png`](../../assets/capability-studio/capability-studio-gp01-gp03-en-1024.png) |
| 390×844 | 英文 GP-03 移动端任务选择与 Dataset 摘要 | [`capability-studio-gp01-gp03-en-390.png`](../../assets/capability-studio/capability-studio-gp01-gp03-en-390.png) |

这些证据关闭了所列中英文 `READY` 状态、Dataset 键盘路径和自动 axe 检查，但不覆盖契约/Tutorial 全键盘、人工屏幕阅读器、异常状态三视口、运行证据或人工可用性签署。业务资产名称和说明仍是 Canonical Demo Pack 的中文权威数据，语言切换只承诺产品界面文案，不应误报为业务内容本地化。

## 3. 更新流程

### 3.1 先改合同，再改实现

改变黄金路径的动作顺序、页面反馈、业务预期、资产数量、场景分母、生产隔离策略或签署门槛时，先更新 Baseline、Screen State Inventory 和 Traceability Matrix，并由产品/架构/QA 重新评审。实现不能先行改变验收含义。

不改变验收语义的实现修复，可以只补充对应证据引用和测试结果，但不得删除失败、限制或未覆盖记录。

### 3.2 更新 Baseline

1. 增加 revision，保留旧文件和变更说明。
2. 更新 `goldenPack`、`goldenPaths`、`spikes`、浏览器矩阵、可用性门槛、安全门禁和 NFR 门禁。
3. 所有引用使用 exact ref；没有真实资产时使用 `status: NOT_RUN` 或 `PENDING`，不得编造 pass。
4. 只有对应角色完成签署，Baseline 才能从 `NO_GO` 变为 `APPROVED`。签署字段不能由脚本代填。

### 3.3 生成 Manifest

每个发布候选生成一个新的 Manifest：

1. 记录候选构建、Baseline、Golden Demo Pack、Contract/Dataset/Binding Plan fingerprints。
2. 填写完整的 `gpResults`、9 个 `scenarioResults`、浏览器、可访问性、协议、安全和 egress 结果。
3. 不复制 payload，只写 evidence ref、脱敏摘要和 fingerprint。
4. 未执行的观测使用 `NOT_RUN`，未知计数使用 `null`，不使用 `0` 代替“没有观测”。
5. 结构校验通过不等于验收通过；需要执行 README 第 5 节的跨字段不变量。

## 4. 内容寻址

所有制品都使用 UTF-8 JSON 或 UTF-8 Markdown。JSON 的 fingerprint 计算使用项目统一的 RFC 8785 JCS canonical bytes，并将自身 `artifactFingerprint` 字段归一化为 `null` 后计算 `sha256:<64 位小写十六进制>`。签署信息仍然参与指纹；只有自身 fingerprint 字段被排除，避免循环引用。

Stage 0 Golden Demo Pack 的 `packFingerprint` 绑定整个 payload-free 投影内容。包内尚未物化的子引用仍使用可复算坐标摘要 `sha256("capability-studio-demo-v1|kind|id|revision")`。GP-03 Dataset 投影的根、Case 与 Behavior Profile 已使用内容指纹，Test Kit 可独立重算根指纹并校验 Scope、引用闭包和质量统计，但 Source、Oracle、Contract 等引用仍继承 Stage 0 坐标摘要，且没有持久化 Dataset Authority。GP-04 Tutorial Branch 已使用 test/staging 数据库 Authority：分支 fingerprint 绑定 `schemaVersion`、`branchId`、Canonical Baseline fingerprint、`dependencyId`、condition、behavior 和 duration；head 只做 SQL 原子 CAS，revision 只追加，Test Kit 会从 before/after 投影重算并校验 digest。Graph、Binding 和运行 material 尚未全部替换为 Authority 内容 fingerprint，因此 Manifest 继续保持 `NO_GO`。

`resource-gateway-test-kit` 已提供 `CapabilityStudioAcceptanceVerifier`。它使用随 JAR 打包的两份权威 Schema 校验结构，并检查 GP/Case 精确集合、Case 类型映射、证据闭包、零真实外呼和签署绑定。运行回归：
验证器还会将自身 `artifactFingerprint` 归一化为 `null`，递归排序对象 key 后独立复算内容地址；仅修改说明、时间或状态而不更新指纹也会失败关闭。

```bash
mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=CapabilityStudioAcceptanceVerifierTest test

mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=CapabilityStudioScenarioDatasetVerifierTest test
```

也可以用以下命令复算当前 Stage 0 JSON 的临时内容摘要：

```bash
jq -e . docs/acceptance/capability-studio/capability-studio-acceptance-baseline-v1.json
jq -e . docs/acceptance/capability-studio/capability-studio-golden-path-acceptance-manifest-v1.no-go.fixture.json
shasum -a 256 <(jq -S -c 'del(.artifactFingerprint) + {artifactFingerprint:null}' docs/acceptance/capability-studio/capability-studio-acceptance-baseline-v1.json)
```

`jq -S -c` 不是通用 RFC 8785 实现，不能单独作为发布验收证明。当前 Test Kit verifier 负责结构与跨字段门禁，但尚不替代组织签名系统、证据存储的指纹解析、时间戳信任或业务 Owner 的人工判断。发布链还必须验证 exact ref 闭包、证据内容与引用指纹一致、签署主体权限和时间关系。

## 5. 跨字段验证不变量

JSON Schema 负责字段、类型、枚举、数量和 fingerprint 格式；Test Kit verifier 执行其中可确定化的门禁，组织权限和业务结论仍需人工或外部治理系统审阅：

- `gpResults` 的集合必须恰好是 `GP-01` 至 `GP-10`，不能缺失、重复或多出；
- `scenarioResults` 必须恰好覆盖 Golden Demo Pack 的 9 个 Case；
- Canonical Baseline 与 Tutorial Branch 的 exact ref、revision 和 fingerprint 不得混用；
- 每个 Active Case 必须闭合到 Contract、Owner、Oracle、来源和适用 Scope；
- `ACCEPTED` 必须要求所有 GP 和 Case 为 `PASSED`，所有必需证据存在且不是 `NOT_RUN`；
- `realExternalCallCount` 必须是已观测的 `0`，不能以 `null`、无日志或未执行替代；
- 浏览器必须覆盖中文/英文、1440px/1024px/390px，关键状态和可访问性结果必须有证据；
- `SEC-*`、`NFR-*` 和 Spike 结果必须达到 Baseline 规定的门槛；
- 所有 signoff 均为 `APPROVED`，签署时间不晚于生成时间，且引用的证据指纹一致；
- 任一 P0/P1 限制、stale 引用、冲突、越权或未解释 404/400 都阻止 `ACCEPTED`。

## 6. 签署规则

签署人必须是对应职责的真实主体，并通过组织规定的签名或审批系统写入 `signoffs`。本次提交不包含签名，不代表任何角色批准。签署前允许更新制品并保持 `NO_GO`；签署后任何影响验收语义的变更都必须提升 revision、撤销旧签署并重新生成 fingerprint。

## 7. 证据命名建议

建议证据引用按以下格式组织：

```text
evidence://capability-studio/<baseline-or-candidate>/<requirement-id>/<evidence-id>@<revision>#sha256:<64>
```

引用指向 DOM、协议响应、运行 trace、网络拒绝、截图、可访问性报告或安全报告。证据内容由对应系统存储；本目录只保存定位信息和验证结果，不保存业务 payload。
