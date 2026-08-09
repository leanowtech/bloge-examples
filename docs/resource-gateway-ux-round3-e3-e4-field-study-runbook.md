# Resource Gateway UX Round 3 E3/E4 现场验证手册

> 状态：Ready to execute / 尚未取得真实参与者与连续发布证据
>
> 适用范围：Web、VS Code WebView、Author、Library、Scenario Matrix、Evidence 与 Rehearsal
>
> 证据合同：[resource-gateway-ux-evidence-v1.schema.json](schemas/resource-gateway-ux-evidence-v1.schema.json)

## 1. 为什么还需要这一步

自动化、production build 和真实 Chromium 可以证明功能和工程协议按预期工作，不能证明复杂企业组织中的
作者、测试工程师、治理审阅者和故障处理者都能正确理解它。E3/E4 只回答三类代码无法替人回答的问题：

1. 用户能否在没有设计者提示的情况下完成关键任务；
2. 用户会不会把环境、作用域、Mock 证明或当前性理解错；
3. 团队连续发布时，恢复、回退和门禁是否真的阻止严重事故。

未满足本手册门禁时，E2 工程分可以达到 95 以上，但对外工业成熟度继续封顶 89。

## 2. 招募与覆盖

必须招募至少 12 名真实目标用户，每类至少 3 名：

| 角色 | 最小人数 | 必须覆盖的主要任务 |
|---|---:|---|
| `AUTHOR` | 3 | 恢复编辑、删除影响、Undo、环境与命令作用域 |
| `TEST_ENGINEER` | 3 | Matrix 比较、字段 diff、fixture、失败定位 |
| `GOVERNANCE_REVIEWER` | 3 | Mock/Runtime/Certifiable、currentness、门禁资格 |
| `INCIDENT_RESPONDER` | 3 | Rehearsal、Evidence、return coordinate、根因详情 |

至少 2 名参与者必须使用真实 VS Code WebView，其余可使用企业浏览器。不能用产品设计者或本次实现者
替代全部目标用户；熟悉度、设备、语言和辅助技术使用情况记录在研究系统中，不进入本仓库证据合同。

## 3. 固定任务

主持人只给目标，不提示按钮位置或正确路径。每个任务结束后先记录行为，再进行访谈。

| taskId | 给参与者的目标 | 成功标准 | 关键误判 |
|---|---|---|---|
| `RECOVER_EDIT` | 修改贷款示例后离开，再回到相同工作 | 恢复同一 draft、revision、selection 与最新修改 | 把 recoverable 当作 server saved |
| `UNDO_DESTRUCTIVE_CHANGE` | 删除带测试资产的节点，再完整恢复 | 先理解影响，一次 Undo 恢复节点、边、fixture、suite | 只恢复图而漏测试资产 |
| `MATRIX_DIFF` | 找到三个用例中失败项并解释差异 | 390/820 下不横向扫表，最多 2 次点击看到字段 diff | 只读绿色状态，不读 proof |
| `PROOF_CLASSIFICATION` | 判断一条 Mock PASS 是否可以发布 | 明确回答不可用于发布，并指出 proof/currentness | 把 PASS 或 CURRENT 当作可认证 |
| `SCOPE_CONFIRMATION` | 在 production 上执行给定范围命令 | 执行前正确说出 tenant、environment、subject、scope、role | 把当前 selection 与 suite scope 混淆 |
| `INCIDENT_RETURN_COORDINATE` | 从演练阻断回到原资产修复并返回 | draft/node/case/run/focus/scroll 坐标保持 | 回到首页后重新寻找上下文 |

每位参与者至少执行 3 项；角色主要任务必须执行，`PROOF_CLASSIFICATION` 和 `SCOPE_CONFIRMATION` 为所有
角色共同任务。任务失败后可以继续探索，但 `completed` 仍按首次无帮助完成结果记录。

## 4. 测量口径

每个 session 记录：

- `coldStartMs`：导航开始到任务壳和当前工作面可交互；VS Code 必须从新建 WebView 测量；
- `warmStartMs`：同一宿主再次进入该路由到可交互；
- `completed`、`durationMs`、`abandoned`；
- `contextCorrect`：执行命令前是否正确复述环境、对象和作用域；
- `proofClassificationCorrect`：适用任务中是否正确区分行为通过与证明权威；
- `scopeCorrectionCount`：用户在提交前主动纠正选择或 scope 的次数；
- `findings`：按 P0-P3 严重度记录，不存自由文本。

禁止写入证据文件：姓名、邮箱、tenant 名、Graph 名、DSL、Schema、fixture、输入输出、错误原文、token、
屏幕录制地址。定性笔记留在客户批准的研究系统中，仓库只接收匿名计数和时间。

## 5. VS Code 宿主任务

真实扩展必须验证以下顺序：

1. 新建 WebView，记录 cold start；
2. Author 修改后，extension host 发送 `bloge.vscodeHostWillDispose.v1`；
3. WebView 等待所有工作区 recovery flush；
4. 收到 `bloge.vscodeHostDisposalReceipt.v1` 且 `ready=true` 后才销毁；
5. 重开 WebView，确认 `HOST_ENCRYPTED` recovery 恢复 exact fingerprint；
6. 在 390、820、1024 等效宽度 resize，确认 task、selection、focus 不丢失；
7. 再次进入同一路由，记录 warm start。

宿主不得在未收到 receipt 时把超时当作成功；`ready=false` 或 `timedOut=true` 时应保留面板并显示可重试错误。WebView 请求/响应协议的代码
实现位于 `src/host/vscodeWebviewBridge.ts`。

## 6. E4 连续运行

至少两个真实团队，各连续观察两个完整发布周期。每个周期只记录匿名 `teamRef/cycleRef`、发布次数和三类
严重事故计数：

- `SILENT_DATA_LOSS`：编辑或恢复后资产静默丢失；
- `CROSS_ENVIRONMENT_MISOPERATION`：命令作用到错误环境或租户；
- `MOCK_EVIDENCE_PUBLISHED`：Mock/探索性证据被当作可认证证明发布。

任意一类发生 1 次即不通过。事故不能通过改名、降级或从样本中删除来恢复分数；必须先形成回归任务、
修复、重新验证，再开启新的连续周期。

## 7. 证据录入与评分

1. 复制 [resource-gateway-ux-evidence-template.json](examples/resource-gateway-ux-evidence-template.json)。
2. 按 JSON Schema 填入匿名 session 与 release cycle 数据。
3. 运行：

```bash
node scripts/evaluate-resource-gateway-ux-evidence.mjs /path/to/evidence.json
```

评分器要求：

- 参与者 `>=12`，每种角色 `>=3`；
- 核心任务成功率、任务坐标准确率、证明权威判断准确率均 `>=95%`；
- VS Code 参与者 `>=2`，cold start P75 `<=2s`；
- P0/P1 体验发现为 0；
- 两个团队各至少两个发布周期；
- 三类严重组织事故总数为 0。

评分器自身测试：

```bash
node --test scripts/evaluate-resource-gateway-ux-evidence.test.mjs
```

## 8. 决策纪律

| 结果 | 允许宣称 | 下一步 |
|---|---|---|
| E2 通过，E3/E4 未完成 | 工程体验达到目标；外部成熟度封顶 89 | 继续现场研究和团队试点 |
| E3 通过，E4 未完成 | 固定任务可用性已验证 | 进入两个团队连续发布 |
| E3/E4 全部通过 | 可声明工业作者体验达到 95+ | 持续监控 incident-to-regression |
| 任一门禁失败 | 不允许用平均 SUS 或主观满意度覆盖 | 修病根、补回归、重新收集完整证据 |

SUS、UMUX-Lite 和访谈可以帮助解释原因，但不能替代上述行为门禁。主持人不得在任务中教学后仍把结果
记为首次成功，也不得以“用户最终找到了”覆盖明显的作用域或证明权威误判。
