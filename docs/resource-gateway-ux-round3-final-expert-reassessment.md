# Resource Gateway UX Round 3 最终资深体验复评

> 复评日期：2026-08-09
>
> 工程基线：S0-S5 E2 implementation + completion evidence audit
>
> 结论：持久 save receipt 与 Compare/Fork/Reload 均已闭合，E2 工程成熟度恢复为 `97 / 100`；E3/E4 尚未关闭
>
> 校正证据：[Graph 保存幂等协议](resource-gateway-ux-round3-s0-idempotent-graph-save.md)、[多人保存冲突决策](resource-gateway-ux-round3-s0-conflict-resolution.md)

## 1. 最强判断

当前版本已经没有 E2 P0；Round 3 最初发现的静默草稿丢失、不可逆级联删除、企业坐标缺失、Mock 证明
误导、移动 Matrix 无法比较、断点伪可读、Library 告警墙和同步大包，都已从页面补丁下沉为 lifecycle、
mutation、coordinate、proof、projection、zoom、readiness 与 bundle 协议。Graph Save 现在以数据库命令锁、
canonical command fingerprint 和持久 receipt 关闭网络不确定结果；Author/Library 进一步以业务事实比较、
稳定 Fork 与二次确认 Reload 关闭真实多人 revision 冲突。当前没有剩余已知 E2 P0/P1/P2。

但“工程上没有已知硬伤”不等于“复杂企业组织已经验证可用”。参考 VS Code 扩展已经真实跑通，并在
实机走查中进一步关闭 CSP、画布裁切/覆盖、相机竞争、minimap 状态泄漏和重复面板；12 名目标角色和两个
团队的连续发布仍未发生。任何继续把旧 E2 97 分直接宣传成客户成熟度 97 的说法，都是证据越权。

## 2. 本轮事实

| 事实 | 证据 |
|---|---|
| 工作区跨导航、reload 与恢复不再静默丢失 | lifecycle/recovery tests、S0 Chromium 路径 |
| 20 类编辑与验证资产可原子 Undo/Redo | mutation round-trip、删除影响和 S1 浏览器证据 |
| 环境、角色、对象、作用域和 production 风险可见 | TaskCoordinate、command authority、S2 浏览器证据 |
| Behavior/Proof/Freshness/Governance 不再混成绿色 PASS | typed presentation、S3 中英文浏览器证据 |
| 390/820 Matrix 能比较三个结果并一次进入 diff | mobile projection、S4 五档视口证据 |
| Overview 不伪装可读，Focus/Inspect 保持标题下限 | SemanticZoomContract、S4 画布测量 |
| Library 同源问题按根因聚合 | 5 个资产 -> 3 个根因，当前资产 1 个 blocker |
| 同步主包不再装入四个工作面 | 867.23 -> 153.09 kB；route lazy + intent prefetch |
| 最大路由启动闭包低于预算 | Author `326.45 KiB` gzip，门禁 `350 KiB` |
| WebView dispose 可以等待 recovery | versioned bridge、false/reject/timeout fail-closed tests |
| 参考 VS Code 宿主可独立运行 | 离线 catalog、AES-GCM recovery、SecretStorage、CSP 与受限 fetch |
| Graph Save 可跨响应丢失、重启和副本精确重放 | versioned command、数据库行锁、事务 receipt、same-key drift 409 |
| 多人冲突不再迫使用户丢弃一方 | 业务事实 Compare、完整 Workspace/Library Fork、二次确认 Reload、模糊成功回收；production JAR 真实冲突路径通过 |
| 真实宿主恢复与画布可读 | 干净 profile 重启 1 个面板、5/12 exact recovery；标准/聚焦零目标碰撞 |
| E3/E4 不能被空样本误判通过 | 版本化证据合同、反例测试、空模板 `NOT READY` |

完成度补强后前端 `98` 个文件、`750` 项测试与扩展 `16` 项测试全绿；production build、TypeScript、i18n、UX、host
和 bundle gate 全部通过。当前 Maven 全量 `5,905` 项全绿；S5 变更对应的
`VisualAuthoringAppJsTest` 29 项通过。production JAR 的真实 Chromium 冲突路径进一步验证了首焦点、两阶段 Reload、
Escape、Fork 内容保全和 390px 零横向溢出，并据此修复了 dialog 容器抢先聚焦的时序缺陷。

## 3. 体验成熟度评分

| 维度 | 权重 | E2 得分 | 判断 |
|---|---:|---:|---|
| 任务完成与心智模型 | 15 | 15 | 表面按任务组织，save conflict 有完整保全决策；首次理解率仍需 E3 |
| 资产安全与连续性 | 20 | 19 | Author/Library、extension recovery 与 Graph Save 持久幂等闭环；直接 X 矩阵待 E3 |
| 可逆编辑与效率 | 12 | 12 | 高风险与高频 mutation 已统一 |
| 测试与证据信任 | 15 | 15 | 四维证明模型和技术详情边界完整 |
| 企业上下文与控制 | 10 | 10 | 坐标、权限、scope 与生产保护统一 |
| 响应式任务完成 | 10 | 10 | 390-1440 任务等价投影成立 |
| 本地化与语义所有权 | 6 | 6 | typed catalog、动态 surface 与 raw detail 隔离 |
| 视觉层级与密度 | 6 | 5 | 视觉预算稳定；长时高密度疲劳待真人观察 |
| 无障碍与键盘安全 | 3 | 3 | 自动化通过；真实读屏与低视力使用仍属 E3 |
| 性能与宿主适配 | 3 | 3 | 构建门禁、参考宿主和单次实测完成；多用户 P75 待测 |
| **合计** | **100** | **97** | **已知 E2 P0/P1/P2 清零；组织证据仍未解锁** |

## 4. 仍存在的问题

### 4.1 不是代码缺陷，但仍是发布证据阻断

| 缺口 | 为什么不能由团队自测替代 | 根治动作 |
|---|---|---|
| 真实 VS Code cold start P75 | 浏览器和 jsdom 不包含 extension host、企业策略与 WebView 生命周期 | 至少 2 名真实 VS Code 用户，P75 `<=2s` |
| 12 名角色固定任务 | 设计者知道正确路径，无法代表首次使用者 | 四角色各 3 名，成功/坐标/证明准确率 `>=95%` |
| 两团队两周期 | 单次演示看不到组织漂移、发布压力和事故恢复 | 两个团队各两个周期，三类严重事故为 0 |
| 辅助技术实测 | axe 和键盘测试不能代表读屏心智与放大模式 | E3 纳入读屏或低视力参与者，不以自动化替代 |

### 4.2 P2 维护性熵源

1. `AuthorCanvas.tsx` 仍是大型编排器。chunk 已达预算，但源码责任密度高；后续只在真实回归或并行开发
   冲突出现时，按 command/surface 边界继续抽取，不能为追求行数重写。
2. 全局 CSS minified `316.13 kB`。gzip 只有 `50.63 kB`，当前启动闭包已达标；若 WebView style/layout
   trace 证明主线程受压，再按路由抽 CSS。没有测量前不做高风险样式拆分。
3. 参考 VS Code 宿主已经实现加密、唯一面板、超时与安全代理，但 Windows/Linux、Remote/SSH、企业代理
   和客户安全策略矩阵仍未执行。当前证据只支持参考实现，不支持宣称所有宿主环境兼容。

这些是可观察的熵源，不是继续改页面的许可证。它们的触发条件分别是：chunk/closure 预算失败、真实
WebView P75 失败、代码 ownership 冲突持续上升或 disposal evidence 出现失败。

## 5. 下一步计划

1. 先用 4 人做研究流程校准，每种角色 1 人；只修任务脚本和记录歧义，不把校准样本并入正式评分。
2. 用参考扩展执行至少 2 名真实用户的 cold/warm start、直接 X/kill/timeout 与 390/820/1024 resize。
3. 执行 12 人正式 E3，使用 `resourceGateway.uxEvidence.v1` 录入匿名计数。
4. 只针对失败门禁修病根；每个修复必须新增固定任务或自动化回归，再重跑完整 E3。
5. E3 通过后进入两个团队、两个周期 E4；任何严重事故清零并重新开始连续观察窗口。
6. 评分器返回 `PASS` 后，才把对外成熟度从 89 解锁到 95+。

宿主实机缺陷谱系见
[VS Code 宿主集成与实机体验审阅](resource-gateway-ux-round3-s5-vscode-host-integration.md)，执行手册见
[E3/E4 现场验证手册](resource-gateway-ux-round3-e3-e4-field-study-runbook.md)。

## 6. 最终审计结论

Round 3 的 E2 工程演进现在可以收口。真实宿主复审发现的硬伤、Library continuity、压力门禁、持久
idempotent save receipt 和 Compare/Fork/Reload 均已补齐。剩余工作是不能由设计者自测替代的真实用户与
组织验证，不应再以继续堆页面功能来掩盖证据缺口。

这套机制具备负熵能力：事故和误判会进入结构化 evidence，失败 gate 会生成明确修复对象，修复再回到
自动化与现场任务，而不是沉淀为口头经验。当前 E2 工程完成度为 97；组织成熟度仍诚实保持 89。
