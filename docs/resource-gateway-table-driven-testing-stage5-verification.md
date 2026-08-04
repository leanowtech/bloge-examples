# Resource Gateway 表格驱动测试 Stage 5 工程核心验证记录

> 日期：2026-08-05
>
> 对应设计：[产品设计](resource-gateway-table-driven-testing-product-design.md)
>
> 关键决策：[ADR-006](adr/ADR-006-scenario-matrix-scale-and-concurrency-boundary.md)
>
> 结论：规模化查询与并发编辑内核完成；真实企业 pilot、saved/team view 与治理协作扩展保留为外部阶段。

## 1. 已实现能力

| 能力 | 实现结果 |
|---|---|
| 1 万行 authoring source | `ScenarioDraftSet` 上限提升到 10,000，strict schema 与 Java validator 一致 |
| 服务端 Matrix 查询 | name/id/tag、case type、canonical/name/type 排序、ASC/DESC、最多 200 行、opaque cursor |
| 精确 source closure | 每个查询必须匹配 current revision + draft fingerprint；cursor 绑定 query fingerprint |
| 行级并发 fence | page 返回每个 case fingerprint；bulk edit 同时验证 draft 与所有目标行 |
| 原子批量编辑 | 最多 5,000 个唯一 cell，`ALL_OR_NOTHING`，完整 validation 后只产生一个 revision |
| Payload-free conflict | 冲突只返回 revision/fingerprint 与 `CHANGED/DELETED/UNCHANGED`，不回显 Given |
| 有界事务投影 | canonical JSON、history、baseline、payload-free head 与行索引同事务更新；正常 page read 不解析全量 payload，缺失索引才从 canonical head 懒修复 |
| 运行分片 | 10,000 行 source 可选择 500 行运行；单次运行预算没有被放宽 |
| 能力发现与客户端 | capability probe 发布四个 schema/两个 endpoint；TypeScript API 带正确 purpose |

## 2. 协议与入口

严格 Schema：

- [Scenario Table Page Query v1](schemas/bloge-scenario-table-page-query-v1.schema.json)
- [Scenario Table Page v1](schemas/bloge-scenario-table-page-v1.schema.json)
- [Scenario Bulk Edit Command v1](schemas/bloge-scenario-bulk-edit-command-v1.schema.json)
- [Scenario Bulk Edit Result v1](schemas/bloge-scenario-bulk-edit-result-v1.schema.json)

HTTP 入口只存在于 `test` / `staging`：

```text
POST /api/visual/scenario-draft-sets/{id}/matrix/query       X-Purpose: TEST_SUITE_READ
POST /api/visual/scenario-draft-sets/{id}/matrix/bulk-edits  X-Purpose: TEST_SUITE_WRITE
```

查询第一页时 cursor 为空；下一页必须原样复用 `nextCursor`，同时保持 revision、draft fingerprint、
filter 和 sort 不变。任何 source 或 query 漂移都会 fail closed。Bulk edit 成功只返回 payload-free
receipt；调用方随后使用 stored revision/fingerprint 查询新 source。

## 3. 测试证据

`ScenarioTableScaleAndConcurrencyTest` 使用真实 H2 行索引验证：

- 10,000 case canonical 保存及 200 行有界分页；
- 第二页稳定、倒序、文本与类型过滤；
- cursor 不能跨 query 重用；
- 三个 cell 一次提交并产生一个 revision；
- stale row 返回 payload-free conflict 且不覆盖新值；
- 第二个非法 edit 使整个 command 回滚。

`ScenarioTableBoundedReadTest` 额外锁定正常查询只调用 payload-free head 与 page repository 方法，
不会先调用 full-asset `find`，避免“HTTP 有界、服务端仍 O(N)”的伪分页。

`TableSuiteRunServiceTest` 额外证明 10,000-case source 只能冻结 500-case selected shard。
`ScenarioTableScaleProtocolSchemaTest` 冻结 Java record 与 strict JSON Schema 的字段、枚举和预算；
`ScenarioTableScaleCapabilityTest` 冻结 portable protocol 与 profile-owned endpoint 的区别；前端 transport
测试冻结 URL、source coordinates 和 `TEST_SUITE_READ/WRITE`。

聚焦门禁：

```text
Frontend scale API             1 file / 2 tests passed
Frontend full test             61 files / 486 tests passed
Frontend production build      TypeScript + Vite passed
Java scale/concurrency suite    19 tests, 0 failures, 0 errors, 0 skipped
Resource Gateway clean verify  5893 tests, 0 failures, 0 errors, 13 skipped
```

完整 `clean verify` 于 2026-08-05 执行，用时 10 分 36 秒。13 项 skipped 为既有环境/能力门控
用例，Stage 5 新增测试没有被跳过。完整阶段门禁也记录在
[实施状态](resource-gateway-table-driven-testing-implementation-status.md)。

## 4. 诚实边界

当前 Author 页面在 500-case 语料上继续使用 50 行 progressive projection；前端已经有类型安全的
server page client，但尚未把 10,000-case 数据直接挂入虚拟表格。因此本阶段可以宣称“规模化协议和
并发内核可集成”，不能宣称“1 万行浏览器体验已通过真实用户验证”。

尚未由自动化替代的工作包括：saved/team view、评论与审批、payload-view ABAC、change event、ANEKE
row deep link、跨 shard promotion 聚合，以及两个真实团队连续两个发布周期的 E3/E4 证据。
