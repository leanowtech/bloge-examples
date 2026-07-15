# ADR-002: Operator Composability 与 OPAQUE_RUNTIME 认证

- 状态：Accepted
- 日期：2026-07-15
- 影响范围：operator catalog、micro-graph runner、正确性证据、存量迁移

## 背景

“执行一个真实 operator，并在副作用边界注入确定数据”只有在外部依赖可被控制时才成立。operator 若在实现内部直接创建 HTTP client、读取系统时间、生成随机数、访问全局数据库或修改静态状态，节点边界 mock 只能证明编排，无法证明 operator 自身逻辑可重复。

把所有 operator 都标成“可单测”会制造假证据；立即要求所有存量实现整改又会造成不可接受的迁移中断。

## 决策

引入两种运行时可测试性分类：

| 分类 | 含义 | 可发布证据 |
| --- | --- | --- |
| `EXECUTABLE_UNIT` | 真实 binding 在单节点 micro graph 中执行，所有外部效应通过可注入边界控制 | 满足指纹、schema、fixture 保真度和无 waiver 时可为 CERTIFIABLE |
| `OPAQUE_RUNTIME` | 实现可运行，但存在不可枚举或不可替换的隐藏依赖 | 只能产生 EXPLORATORY；不得声称可重复单元验证 |

`EXECUTABLE_UNIT` 必须满足 Composability Contract：

1. 外部 I/O 通过 resource binding、声明式 port 或 execution-scoped provider；
2. 时间来自 `OperatorContext`/`ExecutionServices` 的 time source；
3. 随机数、UUID、identity 和 feature flag 来自 execution-scoped provider；
4. side-effect 类型、幂等性、依赖 manifest、secret policy 和 SLA 如实声明；
5. 不读取或写入未声明的全局可变状态；
6. effect-boundary double 可观察真实参数映射、序列化和协议解析。

对 `HttpResourceOperator`，L1 认证必须使用 `TRANSPORT` boundary。用 node `RETURN` 替换整个 httpResource 只证明下游编排，不能认证该算子。

## 自然迁移策略

不追溯性地阻断存量 operator 运行，也不自动授予认证：

1. 首次 inventory 将没有可验证 composability 声明或 conformance evidence 的 binding 标为 `OPAQUE_RUNTIME`；
2. catalog 输出原因码、隐藏依赖清单、owner 和整改建议；
3. 平台按 owner/risk/usage 生成 backlog，优先整改高风险、高复用 operator；
4. operator 增加 provider seam 和 conformance suite 后，通过 micro-graph runner 晋级；
5. 晋级绑定新的 runtime binding fingerprint，历史 evidence 不回写、不升级；
6. publish gate 可逐步从“允许 OPAQUE + warning”收紧到关键域“必须 EXECUTABLE_UNIT”。

这不是临时豁免，而是证据语义的诚实表达：能力缺失会被可见地排队治理，平台不会伪造已经具备的保障。

## 判定与防作弊

- 分类由服务端结合 catalog 声明、runtime binding 和 conformance run 计算，调用方不能直接声明。
- Java 类型扫描只形成 inventory signal，不能单独发证；隐藏反射或动态 client 仍需测试和 review。
- schema waiver、OUTPUT_LEVEL resource mock、inline-only fixture 或缺失 binding fingerprint 会把证据降为 EXPLORATORY。
- binding、依赖 manifest 或 composability metadata 变化后，旧认证失效并重新运行。

## 后果

平台能区分“可运行”和“可重复验证”，让正确性声明与真实工程能力一致。代价是 operator catalog、发布 gate 和团队 backlog 增加一个状态维度；部分存量 operator 会显式暴露技术债，但不会被突然下线。

## 被否方案

- 默认所有 Java operator 都是确定性的：无法证明，拒绝。
- 只做节点级 mock：验证不到被测 operator，拒绝。
- 全量存量立即阻断：迁移冲击过大且会诱发虚假声明，拒绝。
- 永久 warning、不影响证据等级：会继续产生假安全感，拒绝。
