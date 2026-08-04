# ADR-006：Scenario Matrix 规模化查询与并发编辑边界

> 状态：Accepted
>
> 日期：2026-08-05
>
> 决策范围：501–10,000 行 Scenario Matrix 的存储、查询、批量编辑和执行分片

## 1. 背景

成熟商业测试产品普遍使用表格承载批量 case，但 Resource Gateway 不能把表格本身升级成第二套
测试事实源。若浏览器每次下载完整 `ScenarioDraftSet` 再筛选，1 万行会把首屏延迟、内存和冲突
窗口一起放大；若直接把每一行独立持久化，又会破坏 Scenario set 的 canonical fingerprint、不可变
修订和 publication closure。

多人协作还有一个更隐蔽的风险：只用 draft revision 做批量保存，客户端无法说明自己到底编辑了
哪些旧行；只做逐 cell PATCH 则会产生部分成功、重试歧义和无法解释的中间 revision。

## 2. 决策

### 2.1 整份资产是真相，行索引是可重建投影

`StoredScenarioDraftSet` 的 canonical JSON、revision 和 fingerprint 继续是唯一权威状态。数据库在同一
事务内维护 payload-free head 与 `visual_scenario_draft_set_cases` 行索引，保存任一步失败都会回滚
整份资产和索引。正常 page read 只读取 head、count 和当前页，保持服务端内存有界；旧数据或索引
缺失时才从当前 canonical 资产懒修复。索引永远不能反向覆盖 canonical JSON。

### 2.2 查询绑定不可变 source

`bloge.scenarioTablePageQuery.v1` 必须携带 `expectedRevision` 和
`expectedDraftFingerprint`。服务端只在二者与当前 head 完全相等时查询，并返回：

- 当前 source coordinate；
- canonical index 与逐行 fingerprint；
- query fingerprint、总命中数、至多 200 行；
- 只对同一 query fingerprint 有效的 opaque cursor。

Cursor 只编码 query fingerprint 与 offset。因为每个 page 都绑定不可变 revision，所以同一 source
内稳定；source 或 query 改变必须重新查询，不能把旧 cursor 猜测性套用到新结果。

### 2.3 批量编辑是一个原子 command

`bloge.scenarioBulkEditCommand.v1` 同时携带 draft revision/fingerprint 和每个被编辑 case 的
fingerprint。一次命令最多 5,000 个唯一 cell，只允许 `ALL_OR_NOTHING`。服务端在内存中应用全部
修改，通过完整 Scenario validation 后只生成一个新 revision。

冲突响应只返回 current revision、current draft fingerprint 和受影响 case 的
`CHANGED / DELETED / UNCHANGED` 及 current case fingerprint，不回显 Given 或业务 payload。客户端
必须刷新、向用户展示冲突并重新构造命令；服务端不做不可审计的自动 merge。

### 2.4 authoring 规模与 execution 预算分离

- 一个 Scenario set 最多 10,000 case；超过上限必须按 owner、风险或标签拆成 governed suite。
- 单次 table suite run 仍最多 500 case。
- 1 万行 source 可以通过 `SELECTED` 冻结一个 500 行 execution shard；不能用 source 规模绕过
  `maxCases`、超时、失败预算和 payload retention。
- 完整 1 万行全量 promotion 需要外部 shard coordinator 聚合精确分片证据，当前协议不伪装支持。

### 2.5 能力发现

Capability probe 始终公布四个 portable schema；只有 test/staging testing control plane 组装时才将
`scenarioTableScaleApi=true` 并公布 query/bulk-edit 端点。生产 profile 不暴露 authoring route。

## 3. 被拒绝方案

- **浏览器下载整份 1 万行后分页**：无法控制首屏、传输和 stale window。
- **行表成为权威存储**：破坏 canonical set fingerprint 和一次 publication 的确定闭包。
- **无 source fingerprint 的普通 offset 分页**：并发插入会造成重复或漏行。
- **逐 cell 独立提交**：中途失败会留下部分写入，无法安全重试。
- **last-write-wins**：静默丢失另一位作者修改，不适用于测试资产。
- **返回 payload diff**：冲突日志和遥测会成为业务数据外泄通道。
- **允许 1 万行同步全量运行**：把 authoring 容量错误等同于 runtime 容量。

## 4. 后果

收益是：1 万行可被有界检索，多人写入不会静默覆盖，重试结果可判定，且原有 Scenario publication
和 fingerprint 语义不变。代价是每次保存要事务性重建该 set 的行索引；v1 以写放大换取模型简单与
可修复性。若真实生产剖析表明重建成为瓶颈，可以在保持同一事务和 canonical truth 的前提下实现
fingerprint diff 增量更新，不能改变本 ADR 的权威边界。

Saved/team view、评论、审批和跨 shard evidence aggregation 是独立协作协议，不应塞进
`ScenarioDraftSet` 制造业务 revision noise。
