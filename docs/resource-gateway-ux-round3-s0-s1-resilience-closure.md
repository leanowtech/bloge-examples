# Resource Gateway UX Round 3 S0/S1 连续性与压力门禁补强

> 状态：Implemented / automated evidence passed
>
> 日期：2026-08-09
>
> 对应范围：`WP-02 WorkspaceContinuityKernel`、`WP-03 SafeNavigationBoundary`、
> `WP-04 MutationJournal Core` 的完成度复核

## 1. 为什么需要这轮补强

上一轮已经让 Author 具备恢复、离页保护、Undo/Redo 与 VS Code 加密 checkpoint，但逐条对照 Round 3
退出标准后发现，以下结论不能仅凭“页面能恢复”推导：

1. Library 仍使用独立 700ms `persist + preview`，没有注册统一 navigation/dispose guard；
2. autosave 在 recovery debounce 完成后才开始计时，默认路径实际接近 `350 + 1500ms`；
3. 两个保存触发源可并发调用同一权威 API；
4. 已恢复内容的首次同值投影会把 `RECOVERED` 降成 `DIRTY`；
5. 恢复包只检查 TTL 与 scope，没有重新计算内容指纹；
6. mutation 的 20 类样例测试不等于任意合法序列的逆运算证明；
7. 100/20MiB 预算只有缩小参数测试，没有生产默认值压力证据。

这七项的共同病根是：实现具备功能路径，但退出标准尚未成为可重复、不依赖设计者解释的工程门禁。

## 2. 本轮实现

### 2.1 Library 进入共享连续性协议

Library 现在复用 `useWorkspaceContinuity`：

- 编辑后 350ms 写 recovery，700ms 触发原有权威 `persist + preview`；
- 顶层导航、`beforeunload`、`pagehide`、visibility change 和 VS Code disposal 共用同一 guard；
- recovery 坐标固定使用宿主 tenant/project/environment，不再使用只有打开资产后才知道的文档 namespace；
- recovery payload 保留 draft、expected revision、selection、start source 与最后权威 revision；
- 返回 Library 首页时可恢复未赶上 700ms autosave 的编辑，并继续使用原 expected revision；
- Workspace Context Bar 显示真实 `NEW / DIRTY / RECOVERABLE / SAVING / SAVED /
  CONFLICTED / RECOVERABLE_OFFLINE / RECOVERED`，不再只显示局部 `SaveState`。

资产 namespace 仍保留在 `TaskCoordinate` 中用于命令解释，但不能作为 recovery storage key。否则离开资产页
后，空首页无法重新构造同一 key，恢复包会存在却不可发现。

### 2.2 autosave 时限与并发语义

- autosave deadline 从内容 epoch 变化时开始，而不是等待 recovery debounce 后重新计时；
- 默认 Author deadline 因此是编辑后 1500ms，而不是约 1850ms；
- `saveInFlightRef` 合并手动 Save、autosave、online retry 和 host lifecycle 的并发请求；
- `RECOVERABLE_OFFLINE` 不进行定时请求风暴，只在浏览器 `online` 事件后单次重试；
- 409 与 412 都进入 `CONFLICTED`，不能降级成普通网络错误；
- 旧 epoch 或旧 revision 的回执不能替换更新的 authoritative checkpoint。

这里证明的是客户端调度上界，不冒充真实网络端到端 P95。真实环境 P95 仍需 E3 性能采样。

### 2.3 recovery 完整性

恢复前重新计算领域内容 SHA-256；与 envelope 指纹不一致时删除该候选且不调用领域 restore。解析器同时拒绝：

- 负数、非整数或超出安全整数的 epoch；
- 非法时间；
- expiry 早于或等于 capture 的 envelope；
- 已过期、跨 tenant/project/environment 或畸形 JSON。

VS Code 宿主仍由 AES-256-GCM 提供密文完整性；浏览器演示存储则至少具备内容损坏检测，不能把被改写的
sessionStorage 当成可信资产。

### 2.4 mutation 属性与生产预算

- 200 组确定性生成的 1-40 步合法 mutation 序列全部执行完整 Undo，再执行完整 Redo；
- 每组都比较 canonical fingerprint，证明起点与终点准确恢复；
- 使用生产默认 `100 entries / 20MiB` 运行大型 fixture snapshot 压力测试；
- 超限只丢弃离当前状态最远的 mutation，saved checkpoint 不被清空；
- 原有 20 类 mutation、100 次往返、删除资产原子恢复测试继续保留。

## 3. 故障注入证据

状态机新增 1000 组确定性故障交错，覆盖：

```text
content changes
  -> recovery stored / not stored
  -> stale save started
  -> offline failure / generic failure / stale success
```

每组都必须满足：

1. `SAVED` 时 current fingerprint 与 saved fingerprint 完全相同；
2. 非 `SAVED` 内容必须停留在明确的 `DIRTY / RECOVERABLE / RECOVERABLE_OFFLINE`；
3. 旧回执不能降低 saved epoch/revision；
4. recovery 写失败不能静默伪装为保存成功。

这组测试是状态机级故障注入，不等于 1000 次真实 OS kill。真实 WebView X/kill/timeout 矩阵仍按 E3 手册执行。

## 4. 自动化结果

定向门禁：

```bash
cd resource-gateway-examples/src/main/frontend
npm test -- --run \
  src/author/continuity/workspaceContinuity.test.ts \
  src/author/continuity/useWorkspaceContinuity.test.tsx \
  src/author/mutations/reversibleMutationJournal.test.ts \
  src/library-authoring/LibraryWorkbench.test.tsx
npx tsc --noEmit
```

结果：定向连续性/压力集与补强后完整前端 `96` 个文件、`744` 项测试全绿；TypeScript、i18n、
UX、host、production build 与 route bundle gate 通过。`VisualAuthoringAppJsTest` `29/29` 通过。覆盖内容包括：

| 门禁 | 自动化证据 |
|---|---|
| stale receipt | epoch 与 revision 双单调测试 |
| autosave deadline | 从 edit epoch 起 1499ms 不保存、1500ms 保存 |
| max wait | 连续 300ms 编辑仍在 5000ms 边界写 recovery |
| save dedupe | 手动、online 与 lifecycle 并发只调用一次权威 Save |
| offline replay | `RECOVERABLE_OFFLINE` 后双 online event 只重试一次 |
| tamper | payload 与 SHA-256 不一致时拒绝并删除候选 |
| Library continuity | 700ms 前离开，重挂后恢复同一未保存字段与 selection |
| state fault matrix | 1000 组故障交错无伪 `SAVED` |
| mutation property | 200 组任意序列 inverse/redo fingerprint 相等 |
| history pressure | 生产 100/20MiB 双预算与 saved checkpoint 保持 |

## 5. 用户可感知变化

1. 在 Library 修改名称后立刻切到 Graph Author，再返回 Library，修改不再消失；
2. Library 顶部 lifecycle 会先显示可恢复，再显示权威保存状态；
3. 断网保存失败显示离线可恢复，网络恢复后自动进行一次受控重试；
4. 同时触发 Save、离页和宿主关闭不会创建并发保存风暴；
5. 损坏的本地恢复包不会把编辑器带入半恢复状态。

## 6. 后续闭环状态

| 项目 | 状态 | 证据或下一步 |
|---|---|---|
| Author Compare/Fork/Reload | 已关闭 | Workspace Fork 保留 Graph、Scenario、Fixture 与 authored tests，旧证据失效 |
| Library Compare/Fork/Reload | 已关闭 | 固定 Fork 坐标、模糊成功回收、二次确认 Reload |
| 真实 autosave P95 未采样 | 调度 1500ms 不等于网络端到端 1500ms | E3 记录 edit-to-receipt histogram |
| 真实 X/kill 多设备矩阵未完成 | 自动化与单机实测不能代表企业宿主矩阵 | 按 E3/E4 手册执行并归档 evidence |

Graph create/update 的持久幂等收据已在
[Graph 保存幂等协议](resource-gateway-ux-round3-s0-idempotent-graph-save.md)中关闭；真实并发冲突已在
[多人保存冲突决策](resource-gateway-ux-round3-s0-conflict-resolution.md)中关闭。已知 E2 P1 归零，剩余两项
需要真实参与者和设备矩阵，不能由组件测试替代。
