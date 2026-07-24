# Resource Gateway 场景演练注册与编译指南

本文说明 E7 场景演练的第一条可运行纵向链路：把业务处置规则、已有测试、
Fixture、MirrorPlan 和可选 Session checkpoint 绑定成一个不可变、
可复验且不携带业务 payload 的演练执行许可。

## 1. 当前交付边界

| 能力 | 状态 | 能证明什么 |
|---|---|---|
| `CaseHandlingAssertion.v1` | 可用 | 处置结果以 typed selector/expectation 表达，不复制业务值 |
| `ScenarioCase.v1` | 可用 | 一个业务 case 精确引用既有 TestSuite case、Fixture、MirrorPlan、fault 和 checkpoint |
| `ScenarioPack.v1` | 可用 | 多个 case、断言、状态模型和写效果形成同一业务能力的不可变场景包 |
| 场景资产注册表 | 可用 | 全企业 scope、append-only、exact revision/fingerprint、读取时重验完整性 |
| `CompiledScenarioRehearsalPlan.v1` | 可用 | 编译时证明跨仓储依赖闭包一致，产出 payload-free 执行许可 |
| 场景执行与聚合 evidence | 未交付 | capability probe 必须继续返回 `false`，不能把“可编译”冒充成“已跑通” |

这条链路解决的是“执行前到底冻结了什么”。下一条链路才负责逐 case
执行、断言求值、state diff、聚合 evidence 和 ANEKE workbook seed。

## 2. 为什么需要独立编译计划

`ScenarioPack` 是可复用的业务剧本，`MirrorPlan` 是可复用的镜像运行计划。
如果二者互相保存 exact fingerprint，会形成无法稳定求值的内容寻址环：

```text
ScenarioPack -> ScenarioCase -> MirrorPlan -> ScenarioPack
```

系统因此禁止 `MirrorPlan.scenarioPackRef` 反向引用，并由
`CompiledScenarioRehearsalPlan` 作为唯一 join artifact：

```text
ScenarioPack
  -> ScenarioCase
     -> TestSuite/TestCase
     -> FixtureBundle
     -> MirrorPlan
     -> optional signed Session checkpoint
     -> CaseHandlingAssertion
  -> CompiledScenarioRehearsalPlan
```

编译计划保存所有 exact ref、隔离策略、执行服务和断言闭包，但不保存
TestCase input、Fixture 返回值、Session payload 或真实凭据。相同依赖输入
必须生成相同 fingerprint；任何依赖漂移都必须重新编译。

## 3. 启动与停止

从仓库根目录启动 test profile 的 Mirror control plane：

```bash
RG_MIRROR_RUNTIME_ENABLED=true \
  ./scripts/start-visual-canvas-demo.sh --profile test
```

需要注册 stateful case 的 Session checkpoint 时启用独立加密数据面：

```bash
./scripts/start-visual-canvas-demo.sh --profile test --stateful
```

`--stateful` 已包含 Mirror runtime 开关。脚本会等待 capability probe
可读后才报告启动成功。停止服务：

```bash
./scripts/stop-visual-canvas-demo.sh
```

检查装配真值：

```bash
curl -sS http://localhost:8080/api/integration/capabilities
```

当前里程碑应满足：

| Feature flag | 期望值 |
|---|---:|
| `mirrorScenarioArtifactRegistry` | `true` |
| `mirrorScenarioRehearsalCompilation` | `true` |
| `mirrorScenarioRehearsalExecution` | `false` |
| `mirrorScenarioRehearsalEvidence` | `false` |

若前两个为 `false`，先检查 profile 是否为 `test`/`staging`、Mirror 开关、
数据库与依赖仓储是否完成装配。不要绕过 probe 直接把环境标记为可用。

## 4. 调用身份

所有场景路由都要求受验证身份和 `MIRROR_REHEARSAL` purpose。本地演示请求
使用：

```http
Authorization: Bearer bloge-aneke-demo-token
X-Purpose: MIRROR_REHEARSAL
Content-Type: application/json
```

服务端从身份派生 tenant、organization、project、environment 和 region。
请求内的 Scenario scope 必须与该完整身份一致；不同组织、项目、环境或
region 即使 id 相同，也不能互相读取 Scenario 资产。

当前既有 TestSuite/Fixture registry 历史协议只以 tenant + environment
寻址。compiler 会通过该 authority 读取并验证 exact fingerprint、target 和
classification，但还不能从其 envelope 证明 organization/project/region
归属。因此这条 compile-only API 仅存在于 test/staging；进入生产认证前必须
升级 TestSuite/Fixture scope envelope 与仓储主键，或接入能签发完整 scope
ownership attestation 的外部 authority。不能用 ScenarioCase 中调用方声明的
scope 替代来源资产的归属证明。

## 5. 资产准备

一个可编译 case 至少需要下列已有资产：

1. exact target capability revision；
2. 已注册且通过自身完整性校验的 TestSuite revision，其中存在
   `testCaseId`；
3. TestCase 精确引用的 FixtureBundle revision；
4. 已编译 MirrorPlan；
5. case 声明的每一个 fault rule 都真实存在于 FixtureBundle；
6. 每一个 `CaseHandlingAssertion`；
7. stateful case 需要 live、同 scope、签名有效且与 plan/state closure
   一致的 Session checkpoint。

业务输入和预期业务输出继续保存在 TestSuite/Fixture 的既有受治理边界中。
Scenario 资产只能引用，不能复制这些内容。

## 6. 注册顺序

注册表采用 child-before-parent 的 append-only 规则：

```text
CaseHandlingAssertion
        |
optional signed checkpoint
        |
ScenarioCase
        |
ScenarioPack
```

端点如下：

| 方法与路径 | 权限操作 | 行为 |
|---|---|---|
| `POST /api/mirror/scenarios/assertions` | `MIRROR_SCENARIO_ARTIFACT_WRITE` | 写入一个 exact assertion revision |
| `POST /api/mirror/scenarios/checkpoints` | `MIRROR_SCENARIO_ARTIFACT_WRITE` | 写入一个 live signed checkpoint |
| `POST /api/mirror/scenarios/cases` | `MIRROR_SCENARIO_ARTIFACT_WRITE` | 验证 assertion/checkpoint closure 后写入 case |
| `POST /api/mirror/scenarios/packs` | `MIRROR_SCENARIO_ARTIFACT_WRITE` | 验证完整 case/assertion/state closure 后写入 pack |
| `GET /api/mirror/scenarios/packs/{packId}` | `MIRROR_SCENARIO_ARTIFACT_READ` | 按 revision + fingerprint 精确读取 |

同一 scope、kind、id、revision 的相同内容重试是幂等的；不同 fingerprint
是不可变修订冲突。API 不提供 `latest`、覆盖更新或删除语义。

严格 decoder 在构造领域对象前拒绝：

- 重复 JSON key；
- 未知顶层或嵌套字段；
- 错误 `schemaVersion`；
- 超过 8 MiB 的请求；
- 深度超过 64 或节点数超过 100,000；
- 在 selector、metadata 等位置夹带 request/response/payload。

## 7. 编译

注册完 ScenarioPack 后提交 exact 坐标：

```bash
curl -sS -X POST \
  http://localhost:8080/api/mirror/scenarios/packs/refund-pack/compiled-plans \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: MIRROR_REHEARSAL' \
  -H 'Content-Type: application/json' \
  --data '{
    "schemaVersion": "resourceGateway.scenarioRehearsalCompileRequest.v1",
    "revision": 1,
    "fingerprint": "sha256:<scenario-pack-fingerprint>"
  }'
```

编译器会重新从各自 authority 解析依赖，而不是相信客户端提交的拼装结果。
它逐项证明：

1. pack、case、assertion seal 有效且 lifecycle/approval 未过期；
2. pack 与所有 child 的完整企业 scope、target capability 一致；
3. TestSuite envelope 完整且包含 exact `testCaseId`；
4. TestCase 的 Fixture ref 与 case 完全一致；
5. Fixture target lineage、classification 和 lifecycle 合法；
6. MirrorPlan seal、scope、target、Fixture、state/write closure 与 policy
   完全一致；
7. 所有 `THROW`、`DELAY`、`TIMEOUT`、`DENY` rule 都被 case 显式列出，
   且 case 不引用不存在的 fault；
8. deterministic clock/random/identity/feature flags 等执行服务无漂移；
9. stateful checkpoint 的签名、store generation、plan、state model、
   write effect、logical clock 和有效期均一致；
10. generation-one policy 保持 sequential、case-isolated、no real call、
    no real credential、no network egress、`HASH_ONLY`。

成功响应中的 `planId` 为 `<packId>@compiled-v1`。读取时必须同时提供
exact revision 和 fingerprint：

```bash
curl -sS \
  'http://localhost:8080/api/mirror/scenarios/compiled-plans/refund-pack%40compiled-v1?revision=1&fingerprint=sha256%3A<compiled-plan-fingerprint>' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: MIRROR_REHEARSAL'
```

## 8. 失败语义

| 失败类别 | 处理原则 |
|---|---|
| 身份或 purpose 不合法 | 在解析请求体前拒绝 |
| exact dependency 不存在 | 失败关闭，不回退到 latest |
| fingerprint、scope、target 或 execution service 漂移 | 拒绝编译 |
| Fixture 存在隐式 fault | 拒绝编译，要求 case 显式声明 |
| checkpoint 签名无效、过期或状态闭包漂移 | 拒绝编译 |
| 同 revision 不同内容 | immutable conflict |
| artifact store 不可用 | `503`，不返回缓存猜测 |

错误响应统一使用 Resource Gateway `IntegrationProblem`，只携带稳定 code、
correlation id 和 payload-free details。认证失败在 decoder 前发生，避免未授权
请求通过解析差异探测协议内部结构。

## 9. 数据库与重启语义

场景资产和编译计划存放在独立 append-only 表中：

- `mirror_scenario_artifacts`
- `compiled_scenario_rehearsal_plans`

主键覆盖完整 scope、artifact kind、id 和 revision。写入与读取都重算
canonical fingerprint，并核对数据库索引身份；checkpoint 还会重新验签。
表中没有业务 payload、原始 correlation key 或 mutable latest pointer。

本地重启后可以按 exact fingerprint 读取同一资产。数据库备份、跨区域恢复、
WORM、外部 transparency anchor 和法律保留仍属于部署认证，不由本地哈希代替。

## 10. 开发验证

先运行场景纵向聚焦测试：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ScenarioRehearsalControllerTest,ScenarioArtifactRequestDecoderTest,ScenarioArtifactRegistryServiceTest,ScenarioRehearsalIntegrationServiceTest,ScenarioRehearsalCompilerTest,DatabaseScenarioArtifactRepositoryTest,DatabaseCompiledScenarioRehearsalPlanRepositoryTest,ScenarioPackProtocolTest,MirrorRuntimeConfigurationTest \
  test
```

提交前运行完整门禁：

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
mvn -f resource-gateway-test-kit/pom.xml clean verify
```

关键实现与协议：

- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/ScenarioRehearsalCompiler.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/ScenarioArtifactRegistryService.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/ScenarioRehearsalController.java`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-compile-request-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/compiled-scenario-rehearsal-plan-v1.schema.json`

独立 consumer 继续使用 `resource-gateway-test-kit` 的
`ScenarioPackVerifier` 验证 ScenarioPack/Case/Assertion；编译计划的独立
compatibility fixture 和 verifier 将与执行/evidence 协议一起补齐，未完成前
不应把编译对象直接作为发布门禁证据。

生产化之前还必须闭合 TestSuite/Fixture 的完整企业 scope、跨语言 compiled
plan verifier、外部透明度锚点、数据库迁移/备份恢复和多副本并发认证。当前
capability flag 只声明非生产编译能力，不声明这些环境证明已经完成。
