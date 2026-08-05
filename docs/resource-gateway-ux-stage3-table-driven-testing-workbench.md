# Resource Gateway Stage 3：表格驱动测试工作台

> 状态：Implemented / E2 verified
>
> 日期：2026-08-05
>
> 范围：Graph、Operator、Built-in Function 的 Matrix、Case 编辑与运行证据

## 1. 本阶段解决什么

旧 Matrix 把协议字段直接铺成 14 列。它能完整导出数据，却不支持测试人员最常见的判断顺序：

1. 哪条 Case 出问题；
2. 输入和受控依赖是什么；
3. 业务断言是否通过；
4. 证据是否对应当前版本；
5. 期望值与实际值具体差在哪里。

因此 Stage 3 不继续增加列，而是建立“摘要负责判断、详情负责解释、Case 负责编辑”的三级信息架构。
同时把被测主体与受控依赖分开，避免把“用了 mock”等同于“没有执行真实被测逻辑”。

## 2. 用户可见能力

### 2.1 七列判断面

Matrix 的决策列固定为：

| 列 | 回答的问题 |
|---|---|
| Case | 是哪条业务路径、属于哪种用例 |
| Result | 运行与断言结论是什么 |
| Given | 最重要的输入是什么 |
| Dependencies | 有多少依赖被控制 |
| Assertions | 是否存在业务 oracle、包含多少检查 |
| Duration | 本次运行耗时 |
| Currentness | 证据是否仍对应当前内容、证据强度是什么 |

选择框和操作列属于交互框架，不计入业务决策列。协议坐标、attempt、baseline outcome 和完整字段
被移入 **Inspect**，避免协议完整性挤占日常判断空间。Case 列和选择列在横向浏览时保持 sticky。

### 2.2 结果优先筛选

Matrix 提供 `Failed / Changed / Impacted / Stale / Unproven` facets。它们不是另一组状态真相，
而是 canonical execution、assertion、freshness、baseline 与 target change 的任务投影：

- **Failed**：运行失败或断言失败；
- **Changed**：Case 内容相对完整 baseline 改变；
- **Impacted**：Case 变化、历史失败或被测目标变化；
- **Stale**：证据不再对应当前内容；
- **Unproven**：未运行、无业务 oracle 或证据不具结论性。

搜索、类型、verdict、排序与 facet 可以组合；选择集不随筛选和排序丢失。

### 2.3 四步 Case 编辑器

Graph、Operator 和 Built-in Function 共用相同的四步导航：

1. **Given**：定义被测主体的业务输入；
2. **Dependencies**：控制外部依赖行为；
3. **Then**：定义业务 oracle；
4. **Review & run**：先看编译问题，再运行并审阅证据。

每一步显示字段数、受控依赖数、断言数或运行状态，并直接锚定对应编辑区。Advanced JSON 保留为
逃生舱，不再承担默认输入方式。

### 2.4 被测主体边界

详情区必须明确显示以下两种模式：

| 模式 | 含义 |
|---|---|
| Real target execution | 被测 Graph / Function 正常执行，仅依赖响应受控 |
| Schema validation only | Operator Schema 本身是被测对象，没有运行时实现被调用 |

`3/3 controlled` 只描述依赖控制，不表示被测主体被 mock。这个边界同时进入 Evidence projection，
避免测试通过后仍无法回答“到底测了谁”。

### 2.5 有意义的初始数据

新增 Case 不再得到空 JSON。生成器按以下优先级构造 fixture：

1. Schema `examples`、`default`、`const`、`enum`；
2. 字符串、数字、数组和对象的声明约束；
3. `id`、`email`、`amount`、`score` 等字段名语义；
4. 类型安全的确定性兜底值。

四种入口表达不同意图：

- **Golden**：典型输入和可审阅的 Equals oracle；
- **Negative**：不利输入，并诚实标记 `Needs oracle`；
- **Boundary**：使用 minimum、maximum、长度等边界；
- **Regression**：典型输入，可在确认后固化为回归守卫。

不会为未知错误语义伪造一个“看起来会通过”的负向断言。

### 2.6 Expected / Actual / Diff

本地可控运行显示实际业务值：字段路径、Expected、Actual、Matched/Different 和诊断说明。
服务端批量运行为了避免在浏览器 session persistence 中沉淀业务 payload，只返回同一路径上的
expected/actual fingerprint；详情会明确展示两侧 fingerprint，而不是把缺少明文伪装成“无数据”。

## 3. 演示操作

```bash
./scripts/start-visual-canvas-demo.sh --scenario-batch --open
```

1. 在 Start dialog 选择 **Load example -> Loan policy fallback**；
2. 打开 **Scenarios**，无需先保存 Graph；
3. 点击 **Run all**，确认 golden、negative、boundary 三条 Case 均为 `Current`；
4. 点击首行 **Inspect**，查看 Given、Dependencies、Subject under test 与 Expected/Actual/Diff；
5. 点击 **Open**，沿 Given、Dependencies、Then、Review & run 四步编辑；
6. 使用 **Add case** 选择 Golden、Negative、Boundary 或 Regression，观察生成的字段值和 oracle 状态；
7. 需要 durable baseline 时再 **Save Graph**，然后通过服务端 Run all 使用 Changed/Impacted 差分运行。

停止服务：

```bash
./scripts/stop-visual-canvas-demo.sh
```

## 4. 工程不变量

- 临时示例的 `Run all` 必须真实运行，不能显示可点击却静默返回；
- 多 Case 证据按 `scenarioId` 的不可变快照校验，不依赖当前 UI 选中项；
- Execution、Assertions、Freshness 和 Proof 不折叠成一个绿色标签；
- 受控依赖不能改变被测主体身份；
- 无 oracle 的 Case 必须显示 `Needs oracle`；
- 批量 fingerprint evidence 与本地 payload evidence 使用同一 Expected/Actual/Diff 结构；
- 默认只渲染 50 行窗口，筛选、排序和选择针对完整 canonical set；
- 1280px 默认视图不得依靠横向滚动完成结果判断。

## 5. 验证证据

自动化覆盖 Scenario projection、facets、50 行窗口、preset generator、Graph/Operator/Function 四步
Case rail、subject mode、本地临时批量运行、服务端脱敏 diff 和 Author 跨 Case currentness。
前端全量门禁为 69 files / 527 tests 全绿，TypeScript 检查与 Vite production build 通过。

真实 Spring Boot jar 与 in-app Chromium 的 E2 结果：

- 视口 `1280 x 720`，Matrix 容器 `1060px`；
- `scrollWidth == clientWidth == 1060px`，Actions 完整可见；
- 未保存示例直接运行 3 条 Case，全部为 `SUCCESS / PASSED / CURRENT / MOCK`；
- 首条详情显示完整 Expected 与 Actual 业务 JSON，结论为 `Matched`；
- 详情明确显示 `Real target execution` 和 `3 dependencies controlled`；
- 500 Case 自动化语料只呈现 50 行稳定窗口，支持前后分页；
- 1000 Case / 100 columns 的预算由摘要投影与行窗口隔离，不把 100 个字段平铺进默认 DOM。

## 6. 保留边界

Stage 3 关闭最后一个 P0，但不等于整体达到 95 分。中文深层界面、诊断术语、移动审阅任务、
视觉 token 和超大图交互仍属于 Stage 4/5。服务端批量运行的明文 payload 受数据治理策略限制；
需要审计明文时应进入受授权的 replay/evidence 服务，不能通过前端本地存储绕过策略。
