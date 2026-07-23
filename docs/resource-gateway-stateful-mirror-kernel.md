# Resource Gateway Stateful Mirror 事务内核与工业接入指南

## 1. 当前结论

Stateful Mirror 的第一条退款域纵向切片已经完成协议和进程内事务内核，但还不是可对外调用的
Session 服务。

当前实现解决的是最危险、也最值得先解决的确定性问题：

- 业务世界、写效果和会话状态不再是任意 `Map`，而是版本化、严格封闭、可内容寻址的协议。
- 一个写能力可以原子修改多个实体；失败、超时适配、取消和提交拒绝不会留下半状态。
- 同一 idempotency key 的相同命令返回原 receipt，输入漂移则失败关闭。
- update/delete 的历史实体只允许从 exact recorded sample 或 owner fixture copy-in。
- 删除形成不可复活的 tombstone。
- 单 session 写事务串行化，时间、sequence 和 ID 由确定性执行服务产生。
- state revision、transition event 和 transaction receipt 必须形成完整闭包。
- 独立 test-kit 不依赖 Resource Gateway/Spring，可复验相同 Schema、fingerprint 和闭包。

当前实现**不代表**以下能力已经可用：

- `POST /api/mirror/sessions`；
- TEE/data-plane 加密 state store；
- stateful query resolver；
- checkpoint/recovery；
- state transition evidence 与 ANEKE workbook 导出；
- 多副本 session owner、租约和故障转移；
- capability probe 中任何为 `true` 的 stateful API/store/resolver/runtime readiness；
- 生产环境启用。

在这些接线完成之前，Resource Gateway 必须继续把 stateful runtime 报告为 unavailable。

## 2. 为什么先做事务内核

客服业务拟合不是“返回一个长得像真的 JSON”。退款、工单、权益、库存、账户和审批都具有状态历史：

1. 当前输出依赖之前发生过什么。
2. 相同命令重试不能产生第二次副作用。
3. 多实体变化必须同时成功或同时失败。
4. 删除、超时、取消、冲突和补偿会改变后续可观察行为。
5. 业务正确性最终取决于状态转移和业务不变量，而不只取决于单次响应 Schema。

因此，stateless replay/trajectory/cluster 只能拟合局部表象。Stateful Mirror 的价值是把客户业务规则转成
可执行、可回放、可审计的虚拟世界。拟合保真度越高，回归测试、场景演练、发布门禁和服务策略优化的上限越高。

## 3. 已冻结协议

权威 JSON Schema 位于
[`docs/schemas/resource-gateway-mirror/`](schemas/resource-gateway-mirror/README.md)。

| 协议 | Schema | 责任 |
|---|---|---|
| `resourceGateway.boundedStateExpression.v1` | `bounded-state-expression-v1.schema.json` | 无循环、无外部访问的确定性表达式 AST |
| `resourceGateway.stateModel.v1` | `state-model-v1.schema.json` | 实体 Schema、唯一业务键和业务不变量 |
| `resourceGateway.writeEffectSpec.v1` | `write-effect-spec-v1.schema.json` | 一个虚拟写能力的原子 mutation 集合、前置条件、响应投影和幂等契约 |
| `resourceGateway.sessionStateSpace.v1` | `session-state-space-v1.schema.json` | 一个隔离会话的实体、索引、tombstone、事件和 receipt |
| `resourceGateway.statefulRefundFixture.v1` | `stateful-refund-stage3-v1.fixture.schema.json` | 跨实现固定退款兼容样本 |

固定样本
[`stateful-refund-stage3-v1.fixture.json`](schemas/resource-gateway-mirror/stateful-refund-stage3-v1.fixture.json)
包含：

- `order` 与 `refund` 两类实体；
- `create-refund` 两实体原子写效果；
- 初始订单 `O-100`；
- `requestId=REQ-1`、退款金额 `450` 的命令与预期结果。

### 3.1 StateModel

`StateModel` 是 owner 治理的虚拟业务世界定义。一个 revision 冻结：

- 完整 enterprise scope；
- 1..256 个 entity type；
- 每类实体的受支持 JSON Schema；
- 1..32 个完整业务键；
- 有界业务不变量；
- provenance、lifecycle、createdAt；
- canonical fingerprint。

业务键名称在整个 StateModel 内唯一。这样 session 索引不会因两个实体类型使用同名但不同语义的键而产生歧义。

### 3.2 WriteEffectSpec

一个 `WriteEffectSpec` 精确绑定：

- 一个写 capability revision；
- 一个 StateModel revision；
- 1..64 个有顺序的 mutation；
- 最终 response projection；
- request 中的 idempotency key path；
- owner provenance 与 lifecycle。

mutation 支持 `CREATE`、`UPDATE`、`DELETE`、`UPSERT`。一个 spec 中的 mutation 使用稳定 alias；
表达式只能引用当前已经可用的 alias，未知 alias 和前向引用在执行前拒绝。

update/delete 可声明 exact `baselineReadCapabilityRef`。该引用只是允许的读取能力，不代表任意 resolver
可以提供基线；实际 baseline 仍必须经过受限来源协议。

### 3.3 BoundedStateExpression

v1 只允许：

- `LITERAL`
- `INPUT_POINTER`
- `ENTITY_POINTER`
- `LOGICAL_TIME`
- `DETERMINISTIC_ID`
- `SEQUENCE`
- `ADD`
- `CONCAT`
- `EQUALS`
- `GREATER_THAN_OR_EQUAL`
- `NOT_NULL`
- `AND`
- `OBJECT`

单个表达式最大深度 32、最大节点数 1024。它不能循环、递归、赋值、访问网络、读取 secret、调用真实时间或
动态加载函数。表达式 missing field、类型错误或非法 JSON Pointer 会使整个事务失败。

### 3.4 SessionStateSpace

一个 session snapshot 同时保存两类完整性：

- `worldFingerprint`：当前实体、tombstone、业务键、revision、逻辑时间和依赖。
- `fingerprint`：world 加完整 transition journal 和 idempotency receipt journal。

Map 型数据在 wire protocol 中使用有序 list，避免 JSON 解码时重复 key 被静默覆盖。每个 entity、tombstone、
event 和 receipt 还有自己的 fingerprint。

v1 强制：

- `stateRevision == processedCommands.size()`；
- receipt revision 从 1 连续增长；
- 每个 event 只属于一个 receipt；
- 所有 event 必须且只能被一个 receipt 引用；
- 最新 receipt 的 resulting world fingerprint 必须等于当前 world fingerprint。

这组约束阻止“实体已变化但没有 receipt”“有 event 但无法归属命令”“receipt 指向另一个 world”等伪证据。

## 4. 事务语义

一次新命令按以下顺序执行：

1. 获取公平的 session mutation lock。
2. 检查 wall-clock session expiry。
3. 复验 StateModel、WriteEffectSpec、scope、fingerprint 和 admitted effect ref。
4. 从 request JSON Pointer 读取 idempotency key。
5. 计算绑定 session、plan、model、effect、key 和 input 的 command fingerprint。
6. 已处理 key 且 fingerprint 相同：返回原 receipt。
7. 已处理 key 但 fingerprint 不同：返回 `IDEMPOTENCY_CONFLICT`。
8. 基于当前 immutable head 创建 working world。
9. 按声明顺序执行所有 mutation。
10. 解析 exact baseline、校验前置条件、计算字段效果和完整业务键。
11. 校验 entity Schema、业务键唯一性和 StateModel 不变量。
12. 生成 payload-free transition event。
13. 计算 candidate world fingerprint。
14. 生成绑定完整 event closure 和 resulting world 的 receipt。
15. 封印完整 candidate session。
16. 通过 commit guard 对 expected head 和 candidate 做原子 compare-and-set/persist。
17. commit guard 返回成功后更新进程内 head，并返回 committed receipt。

commit guard 返回成功意味着事务已经提交。此后即使线程收到中断，也不能把结果伪装成取消。commit guard
抛出异常必须意味着 candidate 没有对其他读者可见；不满足这一点的 store adapter 不得接入。

## 5. Copy-on-write

update/delete 在 session 中找不到实体时，只允许以下来源：

| Source | artifact kind | 含义 |
|---|---|---|
| `RECORDED_EXACT` | `CORPUS_SAMPLE` | exact business-key recorded sample |
| `OWNER_SPECIFIED` | `FIXTURE` | owner 明确定义并审批的初始实体 |

不允许 cluster、trajectory、schema synthesis、随机生成或真实生产读调用冒充历史状态。

baseline 必须：

- 精确匹配请求的 entity key；
- 携带已封印实体；
- 携带该实体的完整业务键；
- 通过当前 StateModel Schema；
- 与当前 session scope 和 exact read capability lookup 绑定。

copy-in 与随后发生的 update/delete 在同一事务提交，并分别生成 `COPY_IN` 与业务 mutation event。任一步失败，
copy-in 也回滚。

## 6. 退款行为示例

初始世界：

```text
order O-100
  paidAmount = 1000
  refundedAmount = 0
```

命令：

```json
{
  "requestId": "REQ-1",
  "orderId": "O-100",
  "amount": 450
}
```

`create-refund` WriteEffectSpec 在一个事务内：

1. 创建 `refund R-1`。
2. 验证 `paidAmount >= refundedAmount + amount`。
3. 把 `order O-100.refundedAmount` 更新为 `450`。
4. 返回 `{"refundId":"R-1","orderId":"O-100","status":"CREATED"}`。
5. 生成两个 mutation event 和一个 receipt。

相同命令重试返回原 receipt，不增加 revision、不产生 `R-2`。相同 requestId 携带不同 amount 时失败关闭。

## 7. Java 内核接入

当前内核位于：

- `integration.mirror`: 协议和完整性验证。
- `testing.runtime`: baseline boundary、稳定异常和事务引擎。

典型的进程内装配如下：

```java
StateModel model = StateModelIntegrity.seal(mapper, unsealedModel);
WriteEffectSpec effect = WriteEffectSpecIntegrity.seal(mapper, unsealedEffect);
WriteEffectSpecIntegrity.verify(mapper, effect, model);

SessionStateSpace initial =
        SessionStateSpaceIntegrity.seal(mapper, unsealedInitialState);

MirrorStateTransactionEngine engine = new MirrorStateTransactionEngine(
        mapper,
        model,
        initial,
        baselineResolver,
        serverClock,
        (expected, candidate) ->
                stateStore.compareAndSet(expected.fingerprint(), candidate));

SessionStateSpace.TransactionReceipt receipt =
        engine.execute(effect, commandInput);
```

生产 adapter 的 `compareAndSet` 必须原子完成：

1. exact session/scope owner 验证；
2. expected fingerprint fence；
3. candidate encrypted write；
4. current-head pointer replacement；
5. durable commit；
6. 必要的 payload-free operation audit。

任何部分成功都必须回滚。不能先写 candidate、再异步推进 head。

## 8. 独立验证

`resource-gateway-test-kit` 提供 `MirrorStateProtocolVerifier`：

```java
JsonNode fixture = CapabilityMirrorProtocol.statefulRefundFixture();

MirrorStateProtocolVerifier verifier = new MirrorStateProtocolVerifier();
var model = verifier.verifyStateModel(fixture.path("stateModel"));
var effect = verifier.verifyWriteEffect(
        fixture.path("writeEffect"),
        fixture.path("stateModel"));
var session = verifier.verifySession(
        fixture.path("initialState"),
        fixture.path("stateModel"),
        List.of(fixture.path("writeEffect")));
```

独立 verifier 不链接服务端类，也不把 payload 放进成功结果或异常。它适合：

- TEE ingress；
- ANEKE workbook import；
- state checkpoint restore admission；
- CI compatibility gate；
- 非 Java 客户端实现对照。

当前 canonical fingerprint 仍以仓库现有 Java/test-kit canonical JSON 规则为基准。跨语言数字规范、Unicode
normalization 和固定签名向量仍是独立工作项；在完成前，不得声称任意语言生成的 artifact 都能获得相同 fingerprint。

## 9. 稳定错误语义

内核异常只暴露稳定 code，不携带业务 payload：

| Code | 含义 |
|---|---|
| `RG.MIRROR.STATE.SESSION_EXPIRED` | session 已过期 |
| `RG.MIRROR.STATE.WRITE_EFFECT_NOT_ADMITTED` | effect 不在 session exact closure |
| `RG.MIRROR.STATE.WRITE_EFFECT_NOT_ACTIVE` | effect 非 ACTIVE、已撤销或已过期 |
| `RG.MIRROR.STATE.IDEMPOTENCY_CONFLICT` | 同 key 的命令内容漂移 |
| `RG.MIRROR.STATE.BASELINE_ABSENT` | update/delete 缺少允许的历史基线 |
| `RG.MIRROR.STATE.BASELINE_AUTHORITY_UNAVAILABLE` | baseline authority 不可用 |
| `RG.MIRROR.STATE.BASELINE_IDENTITY_MISMATCH` | baseline 返回了错误实体 |
| `RG.MIRROR.STATE.ENTITY_ALREADY_EXISTS` | CREATE 身份已存在 |
| `RG.MIRROR.STATE.ENTITY_TOMBSTONED` | 身份已删除，不允许复活 |
| `RG.MIRROR.STATE.ENTITY_SCHEMA_INVALID` | candidate entity 不满足 StateModel |
| `RG.MIRROR.STATE.BUSINESS_KEY_CONFLICT` | 唯一业务键冲突 |
| `RG.MIRROR.STATE.EXPRESSION_INVALID` | 表达式类型或结构错误 |
| `RG.MIRROR.STATE.EXPRESSION_MISSING_VALUE` | JSON Pointer 读取缺失 |
| `RG.MIRROR.STATE.CANCELLED_BEFORE_COMMIT` | commit 前收到取消 |
| `RG.MIRROR.STATE.COMMIT_FAILED` | state store/commit guard 拒绝 |

owner 定义的 precondition/invariant error code会原样作为稳定业务失败 code 返回。

## 10. 直接开工的剩余工作

### 10.1 P0：Session service 与 data-plane state store

| Ticket | 工作 | 验收门禁 |
|---|---|---|
| RG-MIR-STATE-002 | 定义 SessionDescriptor、Create/Get/Destroy/Command API；身份派生 scope，不接受 caller 自报 tenant | 跨 scope 读写为 0；exact create retry 幂等；未知字段拒绝 |
| RG-MIR-STATE-003 | TEE/data-plane encrypted state store、CAS head、session owner lease、TTL 和显式销毁 | 进程崩溃/双 owner/lease 接管不丢更新；payload 不入 control DB |
| RG-MIR-STATE-004 | 把 transaction kernel 接到 store；commit、head、receipt、成功审计形成原子边界 | fault injection 覆盖每个写点；无半状态、孤儿 receipt 或孤儿 audit |
| RG-MIR-STATE-005 | 固定容量预算、admission、backpressure 和 payload-free telemetry | 100k entity/16 MiB payload 边界无 OOM；高水位明确拒绝 |

### 10.2 P0：Stateful resolver 与退款读写读

| Ticket | 工作 | 验收门禁 |
|---|---|---|
| RG-MIR-STATE-006 | 在 resolver precedence 中接入 `SESSION_STATE`；先查 tombstone/live entity，再进入 stateless source | session 命中不访问 corpus/真实资源；tombstone 不回退 |
| RG-MIR-STATE-007 | query-order/create-refund/query-refund 端到端 lowering | 读写读结果一致；真实外部写调用恒为 0 |
| RG-MIR-STATE-008 | baseline resolver 接 exact corpus/owner fixture authority | source/kind/scope/capability/content address 任一漂移失败关闭 |
| RG-MIR-STATE-009 | 已分离 protocol、API、store、resolver、runtime 五段探针；接线时逐项推进 | 只有端到端装配、隔离和故障门禁通过后 runtime 才为 true |

### 10.3 P0：Evidence、checkpoint 与恢复

| Ticket | 工作 | 验收门禁 |
|---|---|---|
| RG-MIR-STATE-010 | state trace/evidence 投影：初始/最终 world fingerprint、event/receipt refs、limitation | evidence payload-free、可离线闭包、可导入 ANEKE |
| RG-MIR-STATE-011 | 签名 checkpoint，固定 plan/model/effect/store generation 和 revision | 恢复结果与不中断执行语义 fingerprint 一致 |
| RG-MIR-STATE-012 | timeout/cancel/crash recovery matrix | commit 前回滚；commit 后只返回/恢复 committed receipt |
| RG-MIR-STATE-013 | destroy、cryptographic erasure、legal hold 和删除证明 | payload 不可恢复；证据仍可验证且标记删除状态 |

### 10.4 P1：Scenario、业务不变量与运营闭环

| Ticket | 工作 | 验收门禁 |
|---|---|---|
| RG-MIR-STATE-014 | state-transition/what-if/fault ScenarioPack | 业务 owner 无需手写 JSON 即可调整初始状态和命令 |
| RG-MIR-STATE-015 | 状态 diff、timeline、失败定位和批量运行 | 能定位到 mutation/precondition/entity/path |
| RG-MIR-STATE-016 | assertion、receipt、state evidence 到 ANEKE workbook seed | 发布门禁能区分运行失败、断言失败和低保真 |
| RG-MIR-STATE-017 | drift/outcome 校准和 owner review | 规则失真自动 stale；未经 owner 确认不能 serving |

## 11. 上线前不可省略的测试矩阵

| 维度 | 必测场景 |
|---|---|
| 协议 | strict Schema、unknown field、tamper、cross-scope、stale ref、cross-language fixture |
| 事务 | multi-entity rollback、idempotency replay/conflict、business-key conflict、schema invalid |
| 并发 | 同 session 1000 命令、双 owner、lease 接管、CAS conflict、无 lost update |
| 时间 | expiry 边界、logical clock、timeout before/after commit、clock skew |
| 基线 | exact hit、absent、authority outage、source mismatch、identity drift、tombstone |
| 恢复 | 每个持久化写点 crash、checkpoint tamper、dependency drift、重复恢复 |
| 隔离 | 跨 tenant/org/project/environment/region、production credential、真实写 egress |
| 容量 | entity/event/receipt 上限、16 MiB payload、256 MiB snapshot、backpressure |
| 数据治理 | TTL、destroy、legal hold、deletion proof、日志/metric/exception 泄漏扫描 |
| 业务 | refund golden/negative/boundary/retry/cancel/over-refund/read-write-read |

## 12. 本地验证

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=StatefulMirrorProtocolTest,MirrorStateTransactionEngineTest test

mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=MirrorStateProtocolVerifierTest test

mvn -f resource-gateway-examples/pom.xml clean verify
mvn -f resource-gateway-test-kit/pom.xml clean verify
```

这些命令验证协议和内核，不会启动 Session API。现有 Resource Gateway 启停脚本只启动当前已装配服务；
probe 会报告 `mirrorStatefulProtocol=true`，同时把 Session API、state store、resolver 和 runtime readiness 保持
为 `false`。在 RG-MIR-STATE-002/003/004/006/009 的剩余接线完成前，演示时不能把 stateful transaction kernel
描述为可调用产品功能。
