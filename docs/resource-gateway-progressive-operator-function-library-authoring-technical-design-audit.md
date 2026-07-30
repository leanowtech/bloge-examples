# Resource Gateway 渐进式 Library Authoring 技术方案深度审计

> 审计对象：[渐进式算子与 Built-in Function 库创作技术方案](resource-gateway-progressive-operator-function-library-authoring-technical-design.md)
>
> 审计日期：2026-07-30
>
> 最终结论：**97 / 100，达到“可进入工程评审与任务拆分”的设计成熟度门槛**
>
> 重要限定：该分数评价的是技术方案完备度，不代表功能已经实现，也不替代真实用户 E3/E4 体验证据。

## 1. 审计方法

本轮不按“章节是否齐全”评分，而按以下问题反向攻击方案：

1. 用户是否真的不再需要理解 canonical contract？
2. 精简语法是否存在无法实现或产生歧义的地方？
3. 自动推断会不会把有限样例伪装成完整业务合同？
4. Quick、Advanced、Web、VS Code、Java server 之间会不会形成多套真相？
5. function-only library 和跨库同名 function 能否正确处理？
6. 设计态可用与生产可执行是否被混淆？
7. 并发提交、编译器升级和 registry 变化时会不会提交过期结果？
8. 样例 payload、secret、跨 tenant 和恶意 Schema 是否有系统性防护？
9. 服务部分不可用时，产品会不会显示虚假成功？
10. 方案能否直接拆成代码模块、API、测试和阶段性交付？

评分采用 100 分制：

| 维度 | 权重 |
| --- | ---: |
| 问题定义与边界 | 8 |
| 领域模型与合同语义 | 14 |
| 架构边界与可实施性 | 14 |
| 一致性、并发与生命周期 | 12 |
| 安全、治理与企业隔离 | 12 |
| 可靠性、性能与可观测性 | 10 |
| UX / DX 与渐进披露 | 10 |
| 测试性与证据链 | 10 |
| 迁移与协议兼容 | 5 |
| 交付路径与验收 | 5 |
| **总计** | **100** |

硬门槛：

- 存在未解决 P0，则不得超过 89 分；
- canonical authority、runtime authority 或 stale commit 任一不明确，则不得超过 85 分；
- 自动推断没有 observed/confirmed 分层，则不得超过 80 分；
- 没有安全资源上限、payload 隔离或跨 tenant 约束，则不得超过 90 分；
- 没有可执行测试矩阵和阶段 Exit Gate，则不得超过 92 分。

## 2. 第一轮审计

第一轮草案得分：**89 / 100**。

### 2.1 P0 问题

| 编号 | 问题 | 后果 | 整改 |
| --- | --- | --- | --- |
| A-01 | `input/output` 第一层 key 是端口还是对象字段没有彻底去歧义 | 不同 parser/UI 可能编译出不同 ports | 规定第一层永远是 port，内联对象必须显式 `fields` |
| A-02 | 字段 optional 与值 nullable 共用 `?`，语义未拆开 | required 和 nullability 会被错误编译 | 定义 key `?`、type `?` 和组合语义 |
| A-03 | 只有 canonical export，没有 portable authoring artifact | 跨环境后丢失 Quick source、provenance、confirmation 和 tests | 增加 `bloge.visualLibraryAuthoringBundle.v1` |
| A-04 | Draft commit 与 canonical registry 的事务边界未定义 | 可能出现 UI 显示成功但 registry 未提交，或审计缺失 | 明确锁、验证、registry revision、audit、outbox 的原子事务 |

### 2.2 P1 问题

| 编号 | 问题 | 后果 | 整改 |
| --- | --- | --- | --- |
| A-05 | 初版 compact type grammar 使用递归 `BaseType ::= ArrayType` | parser 设计存在左递归/无限递归风险 | 改为 `PrimaryType ArraySuffix* NullableSuffix?` |
| A-06 | 推断请求缺少 evidence fingerprint 和幂等语义 | 重复提交可能重复累计证据和 confirmation | 增加 normalized sample digest 与 inferencer version fingerprint |
| A-07 | 系统部分不可用时的降级语义未说明 | 容易出现虚假 autosave、import 或 readiness | 增加故障矩阵、禁止行为和只读降级 |
| A-08 | Local TypeScript 与 Java compiler 差异只说“服务端优先” | 无法定位和治理长期漂移 | 增加结构化 diff、最小复现 bundle、metric 和扩展发布 gate |
| A-09 | Draft、confirmation、preview、outbox 的持久化实体未定义 | 团队无法评估实现与迁移成本 | 增加推荐表结构和恢复规则 |

### 2.3 P2 问题

| 编号 | 问题 | 整改 |
| --- | --- | --- |
| A-10 | 图中多种 corporate 色彩缺少图例 | 四张图补充一致的颜色图例 |
| A-11 | JSON Schema 基础映射未集中说明 | 补充 primitive、array、nullable、object 和 unknown 映射表 |
| A-12 | `unknown` 与 `any` 的成熟度差异不清 | 明确两者运行约束相同，但 unknown 阻断强 Schema readiness |

第一轮结论：

> 方向正确，但还不能直接开工。最大的问题不在功能缺失，而在“创作 artifact 如何跨环境保真”“语法是否真正无歧义”“commit 是否原子”三个工程根部。

## 3. 第二轮红队审查

完成第一轮整改后，使用典型失败场景重新推演。

| 场景 | 预期攻击 | 方案响应 | 结论 |
| --- | --- | --- | --- |
| 两个样例只有 `free/pro` | 系统是否自动锁成 enum | 只生成 enum suggestion，确认后才关闭空间 | 通过 |
| 样例没有额外字段 | 是否自动 `additionalProperties=false` | observed object 默认开放，关闭需要确认 | 通过 |
| external-write 用户一路下一步 | 是否获得危险安全默认 | idempotency、secret、side-effect protocol 保持 unresolved | 通过 |
| 手工声明 function 但 runtime 未注册 | 是否显示可执行 | readiness 上限 `DOCUMENTED_ONLY` | 通过 |
| 两个 namespace 都定义 `coalesce` | 是否静默先到先得 | effective callable name 全局冲突阻断 | 通过 |
| function-only library 导出给旧 peer | 是否静默丢函数 | capability negotiation，不支持则拒绝 | 通过 |
| 用户 preview 后他人修改 draft | 是否提交旧结果 | ETag + authoring revision + preview fingerprint 阻断 | 通过 |
| registry 在 preview 后发生变化 | 是否沿用旧 impact | commit 重新核对 target registry revision | 通过 |
| compiler 升级改变默认值 | 是否静默重写 canonical | version pin + old/new diff + acknowledgement | 通过 |
| VS Code local 与 server 不一致 | 是否吞掉差异 | server 裁决、显示 diff、生成复现 bundle | 通过 |
| YAML alias bomb / 深层 Schema | 是否靠 OOM 失败 | safe loader + token/depth/property/union quotas | 通过 |
| 用户粘贴生产 payload | 是否进入 draft/log | ephemeral default、digest、独立 fixture vault、日志禁载荷 | 通过 |
| Audit/outbox 写失败 | 是否先提交 canonical | 整个 commit 回滚 | 通过 |
| Runtime inventory 不可用 | 是否保持绿色 | DESIGN_READY 可用，RUNTIME_BOUND/PRODUCTION_READY 不可进入 | 通过 |
| 恢复旧 authoring revision | 是否复活旧 evidence | 必须重新编译，readiness 不继承 | 通过 |

第二轮没有发现新的 P0。

## 4. 最终评分

| 维度 | 得分 | 审计判断 |
| --- | ---: | --- |
| 问题定义与边界 | 8 / 8 | 明确区分人类创作、canonical 和 runtime evidence |
| 领域模型与合同语义 | 14 / 14 | Quick model、type/signature grammar、function-only、provenance 均可落地 |
| 架构边界与可实施性 | 13 / 14 | 模块、API、存储和事务明确；跨语言 compiler 尚未原型验证 |
| 一致性、并发与生命周期 | 12 / 12 | revision、fingerprint、stale preview、失效规则完整 |
| 安全、治理与企业隔离 | 11 / 12 | 威胁和权限完整；实际配额仍需客户规模校准 |
| 可靠性、性能与可观测性 | 9 / 10 | SLO、缓存、降级、RPO/RTO 和 metric 完整；尚无 benchmark |
| UX / DX 与渐进披露 | 10 / 10 | 四入口、Builder、确认队列、Advanced 单向升级清晰 |
| 测试性与证据链 | 10 / 10 | golden、fuzz、browser、parity、security matrix 和 Exit Gate 完整 |
| 迁移与协议兼容 | 5 / 5 | 旧 endpoint 保留、function-only 协商、portable bundle 完整 |
| 交付路径与验收 | 5 / 5 | Stage 0-4 可拆分，阶段退出标准可验证 |
| **总分** | **97 / 100** | **达到审阅门槛** |

## 5. 保留扣分

以下不是文档遗漏，而是必须通过实施证据消除的风险：

1. **跨语言 parity 尚未证明**  
   Java authoritative compiler 与 TypeScript local preview compiler 只有设计约束，还没有共享 golden vector 的真实通过证据。

2. **配额与 SLO 尚未基准测试**  
   5 MiB、1000 operators、p95 300 ms 等是推荐初始值，不是当前系统实测能力。

3. **Function runtime inventory 依赖 BLOGE framework 演进**  
   在框架导出前，Resource Gateway 只能诚实停在 `DOCUMENTED_ONLY`，不能独立完成真实 function binding parity。

4. **体验指标尚无 E3/E4 证据**  
   “60 秒定义 operator、30 秒定义 function”是验收目标，需真实用户任务测试证明。

因此：

> 97 分表示方案已经足够具体，可以进入正式技术评审、ADR 决策和 issue 拆分；不表示产品工业成熟度已经达到 97 分。

## 6. 审计结论

本方案已经从“给复杂 Schema 套一个表单”提升为一套有明确 source of truth 的渐进式创作系统：

- 易用性由人类创作合同和 Builder 提供；
- 准确性由确定性 compiler、source map 和 canonical validator 提供；
- 保真度由 provenance、confirmation 和 portable bundle 提供；
- 工业安全由 runtime parity、stale commit、事务、RBAC 和 evidence invalidation 提供；
- 可持续演进由 golden vectors、compiler version、capability negotiation 和分阶段交付提供。

最终评分超过 95 分，可以停止内部迭代并提交人工审阅。
