# Resource Gateway 正确性工作台演示指南

本文面向产品评审、业务正确性 Owner、测试负责人和实施人员，说明如何在本地体验 Correctness Studio 的只读样板，以及如何理解页面中的业务定义、覆盖分母、标准用例、测试数据资产、业务预期和五轴证明。

该样板使用正式 Workspace 查询协议、完整企业作用域、精确资产坐标和 Capability Probe。它只装配元数据投影，不装配写入、发布、预检或运行服务，不能作为生产运行能力证明。

## 1. 启动与停止

在仓库根目录执行：

```bash
./scripts/start-visual-canvas-demo.sh --correctness --open
```

脚本会构建前后端、启用 `test` profile、装配只读样板并校验两项条件：

- `correctnessWorkspaceApi=true`；
- `correctnessRunApi=false`。

浏览器未自动打开时，访问以下地址：

```text
http://localhost:8080/correctness/?targetKind=GRAPH&targetId=loan-decision-with-fallback&targetFingerprint=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa&definitionId=loan-correctness-demo&correctnessView=overview&lang=zh-CN
```

查看状态、日志或停止服务：

```bash
./scripts/visual-canvas-demo.sh status
tail -f target/example-logs/visual-canvas-demo.log
./scripts/stop-visual-canvas-demo.sh
```

`--correctness` 只能用于 `test` 或 `staging`。脚本会拒绝在 `production` profile 装配该样板。

## 2. 建议体验路径

### 2.1 总览：先判断证明是否可信

总览展示 `loan-decision-with-fallback` Graph 的精确 revision 和 fingerprint，以及四条必须满足的业务结果。先观察顶部五轴裁决：

| 轴 | 样板状态 | 含义 |
|---|---|---|
| 执行 | 执行成功 | 最近一次运行完成，不代表业务正确 |
| 断言 | 断言通过 | 当前可执行断言通过 |
| 覆盖 | 覆盖不完整 | 9 条冻结义务中仍有 2 条缺少当前证明 |
| 证据 | 当前证据 | 证据与当前精确资产闭包一致 |
| 门禁 | 已阻断 | 覆盖不完整时不得把运行成功冒充可发布 |

页面底部的「需要处理」给出唯一下一步：从覆盖缺口创建用例。演示投影只展示动作语义，不执行写入。

### 2.2 覆盖率：检查业务分母

进入「覆盖率」，确认冻结分母为 9 条义务、已履行 7 条、缺口 2 条。覆盖状态由 Canonical Case 与精确 obligation 引用派生，客户端不能直接把义务标成已覆盖。

评审重点不是通过率，而是分母是否完整、是否冻结、变更后旧证据是否失效。

### 2.3 用例数：查看复杂业务样板

进入「用例数」，查看 8 条标准用例。样板同时包含：

- 高信用自动通过和低信用拒绝；
- 680、720 两个边界；
- 主征信超时后的备用征信回退；
- 双路超时后的人工审核；
- 申请人信息缺失；
- 禁止决策 Graph 产生写副作用。

表格在窄屏中只在自身范围横向滚动，不会撑宽整个页面。`APPROVED`、`SECURITY` 等 wire 枚举会显示为中文产品状态，机器可读值保持不变。

### 2.4 模拟数据：把测试输入当作资产

进入「模拟数据」，查看 5 个 Fixture descriptor：申请人画像、征信超时、备用征信成功和政策边界时间。普通 Workspace 只返回名称、variant、Schema 引用、material fingerprint、分类和使用次数，不返回 Fixture material。

这体现了两条边界：测试数据可以复用、追踪和判定 stale；敏感 payload 必须通过独立授权端点读取，不能进入页面初始投影、日志或治理导出。

### 2.5 业务预期：分离业务权威与技术断言

进入「业务预期」，查看已批准 Business Oracle、待审核 Oracle、可执行 Assertion Set 和失效断言数量。Business Oracle 说明业务上什么结果才正确；Assertion Set 负责把该预期编译成可执行验证。执行成功但没有可执行断言时，系统必须显示 `UNPROVEN/BLOCKED`。

### 2.6 运行：验证能力探测与失败关闭

进入「运行」。样板会显示「当前部署未声明受治理运行预检能力」，并禁用运行计划审查。这是预期结果：页面先读取 Capability Probe，不用 404 猜测能力，也不会在浏览器伪造预检或运行结果。

完整受治理运行需要部署正式 correctness authoring/runtime 依赖，包括持久化迁移、企业身份与 purpose 授权、测试资产 registry、Fixture material authority、Publication 和 Run Service。缺少任一权威依赖时，对应 capability 必须保持关闭。

## 3. API 验证

脚本使用内置演示身份校验精确 Workspace。可手工执行同一请求：

```bash
curl -fsS \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: CORRECTNESS_READ' \
  'http://localhost:8080/api/visual/correctness-workspaces/GRAPH/loan-decision-with-fallback?targetFingerprint=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa&definitionId=loan-correctness-demo&caseLimit=100'
```

响应是 `no-store`、payload-free 的 Workspace envelope。修改 target fingerprint、Definition 或企业作用域后，请求必须失败关闭或返回不可用投影，不能回退到模糊匹配。

## 4. 常见问题

### 页面提示没有运行能力

这是只读样板的设计边界，不是启动失败。用总览、覆盖率、用例、模拟数据和业务预期视图演示正确性资产模型；不要通过补假数据或绕过 Capability Probe 点亮运行按钮。

### 页面没有样板数据

确认启动命令包含 `--correctness`，再执行：

```bash
./scripts/visual-canvas-demo.sh status
```

若服务已由不带该参数的旧进程启动，先停止，再用完整命令重启。

### 需要切换语言

使用全局 `EN / 中文` 分段控件，或把 URL 中的 `lang` 改为 `en`、`zh-CN`。fingerprint、资产 ID、JSON Schema、DSL 和协议错误码不会翻译。

## 5. 验收基线

当前样板已经在真实 Chromium 中验证：

- 320、768、1440 像素宽度下无根页面横向溢出；
- 标签栏与宽表格保留局部横向滚动；
- 中英文路由、精确 deep link 和刷新保持稳定；
- Overview、Cases、Fixtures、Runs 关键任务可达；
- 原始状态码不作为中文界面的主要文案；
- 浏览器控制台无 warning 或 error。

该基线只证明只读体验和协议边界。完整工业验收仍需覆盖键盘与屏幕阅读器、500/5000 Case 性能、写入冲突恢复、受治理运行、证据回放和跨系统门禁闭环。
