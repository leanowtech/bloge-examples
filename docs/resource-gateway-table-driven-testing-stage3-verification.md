# Resource Gateway 表格驱动测试 Stage 3 验证记录

> 日期：2026-08-04
>
> 对应设计：[产品设计](resource-gateway-table-driven-testing-product-design.md)
>
> 结论：服务端权威 batch 主链完成；Stage 4 Coverage Lens 尚未开始。

## 1. 用户可见能力

进入 `Author -> Scenarios -> Matrix` 后，批量操作不再由浏览器逐行调用 simulate：

1. **Run all** 建立完整基线；只有 full closure 每行都有结论且全部通过时显示
   `Promotion Eligible`。
2. **Run selected** 冻结勾选 case id；搜索、筛选、排序和 50 行窗口不会改变 closure。
3. **Run failed (N)** 从完整基线选择断言、编译、运行或超时失败的 case。
4. **Run changed (N)** 比较当前 canonical case 与完整基线的 payload-free fingerprint。
5. **Run affected (N)** 合并 failed、changed；Graph target 或 Contract 变化时覆盖全部当前 case。
6. 运行中显示 queued/running/passed/failed；可 **Cancel**。终态失败可 **Retry failed**，
   retry 追加 attempt，首次失败不被覆盖，失败后成功标记 flaky。
7. 刷新或导航后，宿主在恢复同一 Scenario coordinate 时按 session 中的 batch id full GET，
   后续以 revision delta 继续；event window 有缺口时服务端要求 full reset。

Matrix 只保存 baseline case fingerprint、失败 id 和 target/Contract coordinate 到
`sessionStorage`，不保存 Given、fixture、expected 或 actual value。

## 2. 服务端协议

### 2.1 Command

`bloge.tableSuiteRunCommand.v1` 包含：

- exact GraphDraft、ContractDraft、ScenarioDraftSet；
- `ALL / SELECTED / FAILED / CHANGED / AFFECTED` selection；
- baseline batch id；
- environment、dependency/effect mode、case/failure/concurrency/timeout budgets；
- idempotent request id。

admission 拒绝跨 scope、production profile、stale closure、unsafe effect、非 simulated dependency、
超预算、缺失/partial/inconclusive baseline 和空 selection。

### 2.2 Batch 与 Delta

`bloge.tableSuiteRunBatch.v1` 持久化：

- exact selection closure 与 fingerprint；
- batch/row 状态和计数；
- append-only attempts、flaky、baseline outcome；
- proof strength、duration、run fingerprint；
- expected/actual fingerprints 与稳定 diagnostic code；
- bounded event suffix、promotion eligibility 和时间坐标。

`bloge.tableSuiteRunDelta.v1` 只传输新 revision events。若调用方的 `afterRevision` 已落后于
retained suffix，返回 `resetRequired=true`，调用方必须读取完整 batch。

Schema authority：

- [GraphDraft v1](schemas/bloge-visual-graph-draft-v1.schema.json)
- [Run Command v1](schemas/bloge-table-suite-run-command-v1.schema.json)
- [Run Batch v1](schemas/bloge-table-suite-run-batch-v1.schema.json)
- [Run Delta v1](schemas/bloge-table-suite-run-delta-v1.schema.json)

### 2.3 API 与授权

```text
POST /api/visual/table-suite-runs
GET  /api/visual/table-suite-runs/{batchId}
GET  /api/visual/table-suite-runs/{batchId}/events?afterRevision=N
POST /api/visual/table-suite-runs/{batchId}/cancel
POST /api/visual/table-suite-runs/{batchId}/retry-failed
```

API 仅存在于 `test | staging` 且非 production 的 profile。调用方发送
`X-Purpose: TEST_EXECUTION`；服务端仍分别按 execute/read/cancel operation 授权，避免把内部
operation 枚举泄露成 transport purpose。

## 3. 运行隔离与资源边界

| 风险 | 约束 |
|---|---|
| production 误用 | controller profile 结构性缺席；preflight environment 必须等于 scope |
| live side effect | v1 只接受 `SIMULATED + SIDE_EFFECT_FREE` |
| case 卡死 | virtual thread `FutureTask` + 100..10000ms hard timeout |
| batch 放大 | 单 command 最多 500 cases；进程级 8 batch fair semaphore |
| 失败风暴 | maxFailures 到达后其余行显式 `BUDGET_STOPPED` |
| payload 落库 | JDBC batch 只保存 fingerprints/status/summary；序列化回归扫描业务 secret |
| retry 失真 | payload-bearing command context 最多 256 个、30 分钟；过期 fail closed |
| 状态争用 | repository revision CAS；更新超过 bounded retry 直接失败 |
| event 丢失 | revision gap 返回 resetRequired，不静默拼接不完整状态 |
| 残缺基线 | cancelled/budget-stopped/queued/running row 禁止驱动 differential selection |

## 4. 断言语义

Stage 3 v1 批量执行只接受 `OUTPUT_PATH + EQUALS`，其他断言返回受治理能力不足，而不是静默
降级。JSON equality 规则：

- object key order 不影响结果，字段集合必须一致；
- array 保持长度与顺序；
- JSON 数字跨 `Integer / Long / Decimal` 按值比较；
- assertion 的 numeric tolerance 递归作用于命中的数值叶子；
- missing 与 null 不等价；
- evidence 不返回 Expected/Actual value，只返回各自 fingerprint。

## 5. 真实浏览器验证

在打包后的 Spring Boot `test` profile 上完成：

```text
Load Loan policy fallback
-> Scenarios
-> Save Graph
-> Review compatibility
-> acknowledge + Rebase local draft
-> Matrix / Run all
-> 2 SUCCESS, 2 assertions PASSED, Promotion Eligible
-> edit applicantId in one row
-> Run changed (1), Run affected (1)
-> Run changed
-> closure 1, PASSED 1, Promotion Partial only
```

检查 1280x720 与 390x844：body 无横向溢出；宽表仅在自身滚动；batch strip 与 bulk bar 不互相
遮挡；窄屏操作栏纵向展开。手工链路同时发现并修复 purpose 漂移、嵌套 JSON numeric equality
和空 differential 400 三项真实集成缺陷。

## 6. 自动化验证

聚焦门禁：

```text
Frontend table/workspace/API/model   4 files / 33 tests passed
Java batch/service/protocol          6 classes / 15 tests passed
Frontend production build           passed
Frontend full suite                 58 files / 470 tests passed
Resource Gateway clean verify       5880 tests, 0 failures, 0 errors, 12 skipped
```

最终 `mvn -f resource-gateway-examples/pom.xml clean verify` 于 2026-08-04 完成，用时 10 分 09 秒。
12 项 skipped 均为既有环境门控用例，不包含 table-suite run 的 service、repository、controller、
protocol、capability 或真实浏览器路径。

## 7. 已知边界

1. v1 batch runner 只执行 Graph target 与 transient simulation 可精确表达的 RETURN/REAL dependency；
   transport/replay/error/delay 等高级行为继续由 governed testing control plane 承担。
2. durable batch 不保存业务 payload，因此 Matrix 只能展示 fingerprint 级 diff；完整值比较留在有权限的
   Case/Evidence 视图，Stage 5 再接企业 RBAC export。
3. batch executor 当前是进程内异步调度，JDBC 状态可恢复读取，但进程崩溃后的自动 claim/lease worker
   属于既有 durable test runtime，不在这个轻量 Author batch v1 中虚假声明。
4. Stage 4 才提供 Coverage Lens 与候选生成；Stage 3 证明“按确切集合可靠运行”，不证明“集合足够”。
