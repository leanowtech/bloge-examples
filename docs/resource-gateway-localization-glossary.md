# Resource Gateway 双语术语与界面文案规范

> 版本：1.0  
> 更新日期：2026-08-05  
> 适用范围：Author、Contract、Scenario、Evidence、Libraries、Rehearsals、Showcase

## 1. 目标

本规范解决的不是逐句翻译，而是让中英文用户看到同一个任务、同一种状态和同一条修复路径。
界面翻译必须满足三个不变量：

1. 产品命令、状态、诊断和帮助文案必须本地化；
2. 业务资产名、字段路径、协议坐标和代码必须保持原值；
3. 切换语言不得改变命令可执行性、Tab 数量、证据语义或用户输入。

## 2. 推荐术语

| English | 简体中文 | 使用边界 |
|---|---|---|
| Graph | 编排图 | 指完整 DAG 资产；不要译为“图表” |
| Draft | 草稿 | 未发布的 Graph 修订版 |
| Operator | 算子 | DAG 的最小可运行或可设计单元 |
| Built-in Function | 内置函数 | 表达式中使用的函数资产 |
| Contract | 契约 | 形式化输入、输出与行为约束 |
| Schema | Schema | 保留工程通用词；可组合为“输入 Schema” |
| Scenario | 测试场景 | 可保存、可运行、可生成证据的业务测试资产 |
| Fixture | Fixture | 保留工程术语；首次出现可写“Fixture 测试数据” |
| Assertion | 断言 | 对结果、错误、Schema 或治理预期的判断 |
| Evidence | 证据 | 与精确资产坐标绑定的运行及断言事实 |
| Rehearsal | 演练 | 批量验证资源或能力模拟保真度的任务 |
| Replay | 回放 | 使用已记录输入和边界数据重放一次运行 |
| Mock | Mock | 保留工程术语；状态使用“已模拟” |
| Dependency | 依赖 | 被测主体调用的资源、算子或函数 |
| Binding | 绑定 | 数据源到目标字段的显式关系 |
| Context | 上下文 | Graph 运行期 `ctx` 数据 |
| Compatibility | 兼容性 | 契约或资产修订之间的兼容判断 |
| Rebase | 重绑定 | 将测试场景迁移到新的精确契约坐标；技术详情保留 `rebase` |
| Fingerprint | 指纹 | 技术详情使用；任务主视图优先解释“是否为当前版本” |
| Current | 当前 | Evidence 与当前资产坐标完全一致 |
| Stale | 已过期 | Evidence 或测试场景指向较早快照 |
| Opaque | 不透明 | 类型或来源无法精确推断 |
| Promotion | 发布 | 进入下游治理、审批或正式运行环境 |
| Governance Gate | 治理门禁 | 发布前由治理系统执行的判定 |
| Readiness | 就绪度 | 资产可设计、可运行、可治理的综合状态 |
| Lowering | 运行时映射 | 从可视化定义落到 BLOGE 执行结构的映射 |
| Side-effect Protocol | 副作用协议 | 外部写入的日志、回执、幂等和对账约束 |

## 3. 状态表达

主视图展示用户可理解的状态，协议原值放在 tooltip、技术详情或可复制坐标中。

| Wire value | 主视图中文 | 用户含义 |
|---|---|---|
| `DECLARED` | 已声明 | 来自作者明确保存的契约 |
| `INFERRED` | 推断 | 系统根据 DSL、连接或样本推演 |
| `OBSERVED` | 观测 | 来自真实或模拟运行结果 |
| `EXACT` | 精确 | 来源和类型可以确定 |
| `OPAQUE` | 不透明 | 信息不足，不能作精确判断 |
| `CONNECTED` | 已连接 | 输入存在有效来源 |
| `UNBOUND` | 未绑定 | 输入尚无来源 |
| `CONFLICT` / `CONFLICTED` | 冲突 | 多个来源或修订无法自动合并 |
| `CURRENT` | 当前 | 证据坐标与当前资产一致 |
| `STALE` | 已过期 | 证据坐标早于当前资产 |
| `EXPLORATORY` | 探索性证据 | 可用于调试，不可直接作为治理门禁证据 |
| `DURABLE` | 持久证据 | 已绑定保存的不可变资产坐标 |
| `GOVERNED` | 治理证据 | 已满足治理校验和发布消费要求 |

不要只显示颜色。状态必须同时具有文本、稳定代码和适当的图标或形状差异。

## 4. 命令写法

- 使用“动词 + 对象”：`运行当前测试场景`、`校验当前契约`、`保存当前草稿`。
- 禁用命令必须同时说明原因和直接修复动作。
- `Run` 在产品任务中译为“运行”；`Run & Compare` 译为“运行并比较”。
- `Load` 用于加载演示或本地内容时译为“载入”，读取远程数据时按语境使用“加载”。
- 同一英文命令不得在不同表面出现多个中文译法。

## 5. 不翻译内容

以下内容必须保持原文，避免破坏可复制性或误译业务语义：

- Graph、Operator、Function 和 Scenario 的用户自定义名称；
- `operatorRef`、`libraryId`、`draftId`、`runId`、fingerprint；
- JSON、YAML、BLOGE DSL、JSON Schema、JSONPath 和表达式；
- 字段路径、枚举业务值、URL、HTTP method、secret 引用；
- 服务端技术详情中的原始异常和协议代码。

内置演示资产可以提供独立的多语言展示名，但稳定 ID 和原始 payload 不得随语言改变。

## 6. 诊断文案

主视图按稳定 diagnostic code 映射为三段：

1. **标题**：发生了什么，例如“业务断言失败”；
2. **解释**：对当前任务的影响；
3. **修复动作**：用户下一步可以直接执行的命令。

服务端自由文本只能进入折叠的“技术详情”。未知 code 使用安全的本地化兜底，不得把整段英文异常
直接作为中文界面的主错误。

## 7. 实现规则

- 新命令、状态、blocker 和 diagnostic 使用 typed message ID；
- 迁移期允许 `t(source)`，但所有深层表面必须通过 strict locale inventory；
- 动态注册表、状态机和协议 union 必须有显式动态覆盖测试；
- 插值值不可拼入 key，例如使用 `t('{count} cases', { count })`；
- 第三方组件缺少 locale API 时，必须在受控适配层处理，不得全局替换 DOM 文本；
- 中文缺失 key 时 CI 失败；英文仍是默认源语言；
- 业务自由文本不作为翻译 key。

## 8. 评审清单

- 中文核心任务中是否出现非业务资产英文？
- 中英文是否拥有相同 Tab、按钮、状态和可执行性？
- 协议状态是否以用户语言展示，并保留原值追踪能力？
- 禁用原因、错误解释和修复动作是否同时本地化？
- 插值、复数、数字、日期和耗时是否由 locale-aware formatter 处理？
- 业务资产、字段路径和代码是否保持不变？
- 1280px 与 390px 下长中文是否截断、覆盖或挤压主操作？

专项门禁命令：

```bash
cd resource-gateway-examples/src/main/frontend
npm run check:i18n
```
