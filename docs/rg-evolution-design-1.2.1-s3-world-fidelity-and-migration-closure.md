# S3 世界保真、影响分析与存量迁移闭环技术方案

本文把 [`rg-evolution-design-1.2.1.md`](./rg-evolution-design-1.2.1.md) 的阶段三展开为可实施方案。当前状态为 `IN_PROGRESS`：`S3-A` 已完成开发验证，`S3-B..F` 尚未闭合。阶段三不重做现有 replay、corpus、review、impact 和 mutation 基础设施，而是在这些能力之上补齐面向 `Scenario + ResourceWorldModel` 的收敛层。

## 1. 差距判断

仓库已经具备大量工程零件：

- 受治理 replay payload、保留策略和精确引用；
- capability corpus/trajectory/cluster 的发布、复核和服务语义；
- 业务资产影响投影；
- 图级 mutation suite；
- correctness workspace 的 fixture、scenario 和 coverage 资产；
- evidence、消费计数、来源映射和协议指纹。

但这些能力尚未回答五个面向世界资产的问题：

1. 一条真实观测怎样安全、可追溯地变成 World 草稿，而不是直接成为“正确答案”；
2. 契约变化后，哪些 Scenario 在静态上受影响，哪些在运行时真的消费了变化点；
3. 存量 FixtureBundle/TestSuite 怎样单向抬升为新资产，且不破坏旧协议；
4. World Fragment 与真实 API 开始漂移时，怎样降低可信度并阻止错误晋升；
5. 怎样证明 Scenario 不仅能抓住错误业务图，也能抓住错误世界。

阶段三的核心不是新增另一个治理系统，而是新增一个**世界资产闭环协调器**。

## 2. 不变量

- 不做无差别生产流量录制，只接受显式 capture 或受治理 replay/corpus 引用；
- 观测值只能生成草稿，不能直接生成已发布 World 或 golden expectation；
- 默认按 schema 脱敏，无法证明安全的字段删除或使候选进入隔离区；
- 人工复核必须绑定候选精确版本、来源指纹和脱敏策略指纹；
- 静态影响与运行时消费是两个独立事实，不能相互冒充；
- 迁移只新增草稿，不删除、改写或隐式升级旧测试资产；
- World 漂移只影响后续证据等级和发布门禁，不篡改历史 evidence；
- mutation 的“存活”不能被包装成测试通过；等价变异必须有显式判定证据；
- 所有错误、索引和默认 evidence 保持 payload-free。

## 3. 观测到世界草稿

### 3.1 输入边界

`WorldDraftCandidateService` 只接受以下来源引用：

- 精确 `ReplayPayloadRef`；
- 已发布且在有效期内的 capability corpus revision/trajectory；
- 显式 golden capture receipt；
- 已完成并可验证的 Run Evidence 中的授权 payload companion。

调用方不能在“从生产沉淀”接口直接提交任意 request/response Map。服务端先完成租户、purpose、来源治理、保留期和 payload 授权，再读取值。

### 3.2 候选生命周期

```text
CAPTURED
  -> REDACTION_REQUIRED
  -> REVIEW_READY
  -> APPROVED | REJECTED
  -> MATERIALIZED_DRAFT
  -> PUBLISHED
```

每次转换使用精确 CAS。`APPROVED` 绑定候选版本、来源指纹、schema 指纹、脱敏策略指纹、review ticket、reviewer 和时间。来源、schema 或策略变化后，旧审批失效。

### 3.3 脱敏管线

脱敏顺序固定为：

1. 按逻辑契约 schema 识别字段和数据类别；
2. 执行字段级保留、删除、固定替换、格式保持 tokenization；
3. 对未知字段执行默认删除；
4. 运行凭证、身份、联系方式、地理位置和自由文本残留扫描；
5. 对 request/response 做 schema 重验；
6. 生成 payload-free redaction report 和安全指纹。

任何必填字段因脱敏无法保持 schema 时，候选停在 `REDACTION_REQUIRED`，由拥有者提供合成替代值；不得为了通过 schema 恢复原始敏感值。

### 3.4 草稿生成

批准候选只生成：

- `ResourceWorldModel` 的下一版草稿；
- 一个精确输入匹配的 World rule；
- 可选 Scenario 草稿及待人工定义的 expectation；
- 来源映射、脱敏报告引用和候选指纹。

系统不能根据真实响应自动推断“业务应该如此”。响应可以成为 World 行为候选，但 Scenario expectation 必须由人工或既有权威 oracle 提供。

## 4. 回归影响分析

### 4.1 两类权威事实

静态依赖索引从不可变资产和编译产物生成：

```text
Scenario revision
  -> logical contract fingerprint
  -> world slice fingerprint
  -> world fragment fingerprint
  -> target graph artifact fingerprint
```

运行时消费索引从已验证 evidence 和 source map 生成：

```text
Scenario run
  -> consumed fixture rule
  -> world rule / fragment
  -> logical contract
  -> invocation site
```

### 4.2 影响分类

| 分类 | 含义 | 动作 |
|---|---|---|
| `DECLARED_AND_OBSERVED` | 声明且实际消费 | 必测 |
| `DECLARED_ONLY` | 声明但近期未消费 | 检查死分支或覆盖缺口 |
| `OBSERVED_ONLY` | 实际消费但未声明 | 声明漂移，发布阻断 |
| `COMPATIBLE_CHANGE` | 结构兼容变更 | 选择性回归 |
| `BREAKING_CHANGE` | 不兼容变更 | 所有静态依赖剧情失效 |
| `UNKNOWN` | 缺证据或索引过期 | 失败关闭，不缩小影响面 |

影响报告绑定旧新契约指纹、索引水位、证据时间窗和算法版本。投影异步更新时，读取方必须看见 staleness，不能把旧索引当最新事实。

## 5. 存量测试单向抬升

### 5.1 支持范围

输入为精确版本的 `FixtureBundle + TestSuite + GraphArtifact`。迁移器输出 `MigrationDraftPackage`：

- 一个或多个 World Model 草稿；
- Scenario 草稿；
- 逻辑契约依赖候选；
- 无法映射项和人工补全清单；
- 旧资产到新草稿的双向来源映射；
- 确定性迁移指纹。

### 5.2 映射规则

- 可精确绑定逻辑契约的 RETURN/REPLAY 规则转为 World rule；
- THROW/TIMEOUT 可转为失败行为规则；
- schema stand-in 只能转为探索级草稿；
- node-only 且无逻辑契约标签的规则保留为未映射，不猜测契约；
- TestSuite assertions 转为 Scenario expectations；
- 执行服务 fixture 转为 Scenario 运行控制，而不是 World 外部资源规则；
- SPY、ALLOW_REAL、模糊 selector 和未冻结 replay 不能自动晋升。

同一输入重复迁移必须得到相同草稿内容和迁移指纹。迁移从不自动 publish，也不删除旧套件；旧 endpoint 和协议继续可用。

## 6. 世界保真校准

### 6.1 受控双跑

`WorldFidelityCalibrationService` 只能在专用非生产工作负载中运行：同一组已授权请求分别调用真实 API 和精确 World Slice，并执行 schema-aware diff。

比较维度：

- 响应 schema 与必填字段；
- 规范值差异和允许容差；
- 错误类型、状态和可重试语义；
- 状态转移后的后续可观察结果；
- latency 只作为诊断，不进入世界语义等价。

### 6.2 漂移状态

```text
CURRENT -> SUSPECTED -> CONFIRMED -> REMEDIATING -> CURRENT
                      -> ACCEPTED_DIVERGENCE
```

状态转换绑定固定样本分母、阈值、真实实现指纹和 World Slice 指纹。`SUSPECTED` 降低新运行证据上限；`CONFIRMED` 阻止认证级发布。历史证据不重写，但查询时可附带“其来源世界随后被确认漂移”的当前治理事实。

## 7. 双层变异门禁

复用现有图 mutation service 作为第一层，再增加 `WorldMutationPlanner`：

- 删除一条世界规则；
- 反转一个决策条件；
- 替换边界值；
- 改变错误/成功结果；
- 丢弃一个状态写入；
- 改变默认规则优先级。

每个 mutant 绑定原工件指纹、变异算子、位置和内容指纹。Scenario 必须使非等价 mutant 失败。等价 mutant 只能由独立语义判定或人工复核标记，不能因“测试没抓到”自动视为等价。

门禁输出图变异得分、世界变异得分、存活 mutant 清单、等价判定来源和不适用原因。认证级资产必须达到策略阈值；探索级只提示，不阻断作者迭代。

## 8. 验证器自证

为以下验证器维护“必须拒绝”的负面对照语料：

- 脱敏器遗漏凭证和自由文本身份信息；
- 影响分析漏掉 `OBSERVED_ONLY`；
- 迁移器猜测无标签契约；
- 漂移比较器放过破坏性 schema 差异；
- mutation evaluator 把存活 mutant 记为 killed；
- 审批引用与候选版本不一致。

黄金语料绑定源 schema、算法版本和 policy fingerprint。任一来源变化而语料未更新时，验证器门禁失败。

## 9. 实施切片

### S3-A：World 草稿候选与安全晋升

- 引用式 capture；
- schema 引导脱敏和残留扫描；
- 候选 CAS 生命周期与人工复核；
- World/Scenario 草稿物化，不自动 publish。

开发验证证据：36/36 聚焦测试与 Resource Gateway 7439 项全量测试通过。实现只接受四类受治理精确来源，先校验租户、授权、过期和元数据完整性再读取 payload；schema 引导脱敏、最终树 DLP 和未知字段默认删除共同约束候选。审批与发布由外部 authority 签发并以持久化 receipt 精确绑定。物化使用真实 `WorldFragmentTestKit` 编译执行 BLOGE 片段；发布事务原子提交 receipt、治理目录、草稿资产、候选 CAS 和受保护行为数据 pin。发布资产只携带 payload-free 引用，运行时按租户与发布绑定从加密 vault 解析；跨进程重建后仍可精确执行，普通过期清理不删除 pinned 行，撤销、篡改和跨租户读取均失败关闭。详见 [S3-A 验证说明](./rg-evolution-design-1.2.1-s3-world-draft-candidate-verification.md)。

### S3-B：静态依赖与运行时消费投影

- 不可变依赖索引；
- evidence 消费投影；
- 声明漂移和 staleness；
- 契约变更影响报告。

### S3-C：存量测试迁移器

- FixtureBundle/TestSuite 单向抬升；
- 双向来源映射和未映射诊断；
- 幂等迁移包；
- 旧协议兼容证明。

### S3-D：世界保真校准

- 受控真实 API/World 双跑；
- schema-aware diff；
- 漂移状态机、证据降级和发布阻断。

### S3-E：双层变异与验证器反证

- 复用图 mutation；
- World mutation planner/evaluator；
- 等价 mutant 治理；
- 验证器负面对照与黄金新鲜度。

### S3-F：系统闭环与里程碑

- capture → redact → review → draft → run → impact 全链；
- 未授权、跨租户、过期、篡改和 production 负向矩阵；
- Resource Gateway 与 Test Kit 双项目全量回归。

## 10. 固定验收矩阵

| 编号 | 必须证明的事实 |
|---|---|
| `S3-EXIT-01` | 任意 inline payload 不能伪装为生产沉淀来源 |
| `S3-EXIT-02` | 未授权、跨租户、过期来源在 payload 读取前失败 |
| `S3-EXIT-03` | 未知字段默认删除，敏感残留使候选无法进入 review-ready |
| `S3-EXIT-04` | 审批精确绑定候选、来源、schema 和策略，任一漂移使审批失效 |
| `S3-EXIT-05` | 观测只能生成草稿，不能自动生成已发布 World 或 golden expectation |
| `S3-EXIT-06` | 静态依赖和运行时消费分别可重建，并识别 declared-only/observed-only |
| `S3-EXIT-07` | breaking contract change 的影响面不小于全部静态依赖剧情 |
| `S3-EXIT-08` | 索引过期或缺证据时失败关闭，不错误缩小回归范围 |
| `S3-EXIT-09` | 相同旧资产重复迁移得到相同草稿和迁移指纹 |
| `S3-EXIT-10` | 无逻辑契约标签的 node fixture 不被猜测映射 |
| `S3-EXIT-11` | 迁移不删除旧资产、不改旧 endpoint，且只生成未发布草稿 |
| `S3-EXIT-12` | 真实 API 与 World 的破坏性 schema/语义差异被固定语料检测 |
| `S3-EXIT-13` | 确认漂移降低新 evidence 等级并阻断认证发布，不篡改历史 evidence |
| `S3-EXIT-14` | 图和 World 两层非等价 mutant 均进入分母，存活项不能被隐藏 |
| `S3-EXIT-15` | 验证器面对“必须拒绝”语料时全部失败，黄金来源漂移触发门禁 |
| `S3-EXIT-16` | 默认索引、报告、错误、日志和 evidence 不含业务 payload |
| `S3-EXIT-17` | 真实系统闭环和双项目全量回归通过 |

## 11. 复用决策

| 现有能力 | 决策 | 原因 |
|---|---|---|
| Replay payload repository | 复用 | 已有精确引用、保留和完整性语义 |
| Capability corpus governance | 复用来源与 review 模式 | 不把 corpus publication 直接当 World publication |
| Business asset impact | 复用投影和 staleness 模式 | 新建 World/Scenario 领域投影，避免污染业务资产类型 |
| Test mutation suite | 复用执行和 evidence 聚合 | World mutant 需要独立 planner |
| Correctness fixture/scenario | 复用作者资产和 workspace | 治理 World/Scenario 仍以阶段一资产为权威 |
| Business mirror migration | 参考，不直接复用模型 | 其迁移目标和状态机不是 World Model |

## 12. 设计自审

| 维度 | 得分 | 扣分原因 |
|---|---:|---|
| 治理边界 | 98 | 需要与企业 DLP/审批系统做部署适配 |
| 数据安全 | 97 | 自由文本残留永远存在统计漏检风险，已用人工门禁兜底 |
| 可追溯性 | 98 | 跨系统 review ticket 可用性依赖外部系统 |
| 影响准确性 | 96 | 未执行分支只能依赖静态事实，无法凭运行数据证明 |
| 迁移可逆性 | 99 | 单向加法迁移，旧资产不变 |
| 保真验证 | 96 | 受控真实环境的可用性和成本不由 RG 单方决定 |
| 实施复用度 | 98 | 需要新增领域协调层，但底层仓库大量复用 |

综合评分 **97/100**。剩余扣分来自真实环境、DLP 和外部审批等组织边界，不能用进程内代码伪造解决。若实现取消“观测只生成草稿”“未知字段默认删除”“静态与运行时影响分开呈现”或“迁移不改旧资产”任一不变量，不得进入阶段三验收。
