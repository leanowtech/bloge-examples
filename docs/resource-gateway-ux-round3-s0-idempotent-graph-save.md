# Resource Gateway UX Round 3 Graph 保存幂等协议

> 状态：Implemented / E2 automated evidence passed
>
> 日期：2026-08-10
>
> 对应范围：`WP-02 WorkspaceContinuityKernel` 的网络不确定结果收口

## 1. 解决的问题

客户端合并并发保存只能避免同一进程主动发出重复请求，不能解决下列工业故障：

1. 服务端已经提交 CREATE，但响应在代理或网络中丢失；客户端重试后创建第二张草稿；
2. 服务端已经提交 UPDATE，但响应丢失；客户端仍携带旧 revision 重试并得到伪冲突；
3. 两个网关副本同时接到同一个请求，各自都在看到 receipt 为空后执行 mutation；
4. 进程重启后内存去重消失，同一请求再次执行；
5. 同一个幂等键被错误地复用于另一份内容，却返回旧结果或覆盖新内容。

病根是保存缺少一个位于 Graph mutation **同一事务边界内**的持久命令身份。autosave debounce、前端
in-flight Promise 或乐观锁都不能单独解决这个问题。

## 2. 协议

Graph CREATE 与 UPDATE 保持原有路径和响应体，新增可选请求头：

```http
Idempotency-Key: graph-save:sha256-<content-fingerprint>
```

键必须是 1-160 个 URL-safe 非空白字符。相同 tenant/namespace/environment 中：

- 同 key + 同一 canonical `bloge.graphDraftSaveCommand.v1`：返回第一次提交的精确 Graph id/revision；
- 同 key + 不同 command fingerprint：`409` + `visual.draft.idempotencyKeyReuse`；
- 非法 key：`400` + `visual.draft.idempotencyKeyInvalid`；
- 没有 key：保留兼容行为，不声称可安全重放。

响应保留 GraphDraft body，并增加：

```http
Idempotency-Replayed: true|false
Graph-Draft-Request-Fingerprint: sha256:<64-hex>
```

协议 Schema：

- [Graph save command](schemas/bloge-graph-draft-save-command-v1.schema.json)
- [Graph save receipt](schemas/bloge-graph-draft-save-receipt-v1.schema.json)
- [Graph draft](schemas/bloge-visual-graph-draft-v1.schema.json)

Capability probe 同步声明 `graphDraftIdempotentSave`、`graphDraftDurableSaveReceipt`、
`graphDraftCrossReplicaSaveSerialization` 和两个 save endpoint。

## 3. 原子性与并发

数据库使用两个职责分离的表：

| 表 | 责任 |
|---|---|
| `visual_graph_draft_save_locks` | 以 scope + key 建立数据库行写锁，串行化跨线程、跨副本命令 |
| `visual_graph_draft_save_receipts` | 保存 canonical request fingerprint、精确 draftId/revision 与完成时间 |

执行顺序为：

```text
acquire database command lock
  -> read durable receipt
  -> replay or reject key drift
  -> execute Graph mutation
  -> insert exact receipt
commit Graph + receipt together
```

锁、Graph revision 和 receipt 位于同一 Spring transaction。任何一步失败都会整体回滚；不存在 Graph 已
提交但 receipt 未提交的应用级 crash window。锁表只保存作用域和随机/指纹键；receipt 不复制大型 Graph
JSON，只引用不可变 revision history，也不保存运行输入输出。其保留周期必须与所引用 Graph revision 一致，
后续若引入 revision purge，必须在同一维护事务中清理 receipt 与 lock row。

## 4. 前端使用

`useWorkspaceContinuity` 为每个内容 fingerprint 生成稳定键：

```text
graph-save:sha256-<content fingerprint>
```

手动 Save、1500ms autosave、网络恢复重试和子面板保存全部经过同一 continuity save。一次请求响应不确定时，
后续重试继续使用原键；内容发生变化后 fingerprint 和键一起变化。这样客户端 epoch fencing 负责“不让旧回执
祝福新内容”，服务端 receipt 负责“同一内容命令最多提交一次”。

## 5. 验证证据

自动化覆盖：

| 场景 | 断言 |
|---|---|
| CREATE exact replay | id/revision 相同，revision history 只有一条 |
| UPDATE ambiguous retry | 返回第一次成功的 revision，不产生 409 或第三个 revision |
| same key / different body | 409，原草稿不变 |
| invalid key | mutation 前 400 |
| repository restart | 新 coordinator 从数据库返回原 receipt，mutation 次数为 0 |
| concurrent replicas | 两个 coordinator 同时执行，mutation 总数为 1，一真一 replay |
| transaction rollback | Graph 表与 receipt 表都为 0 |
| offline retry | 两次 `onSave` 收到完全相同的 idempotency attempt |

定向结果：Java `79/79`，前端 `121/121`。完整工程门禁在 Round 3 最终复审时统一执行。

## 6. 后续闭环

持久幂等消除了“其实已保存却显示冲突”的一类伪冲突；真实的多人 revision 冲突现已通过共享 Compare /
Fork local / Reload authoritative 决策面关闭。Graph Fork 原子保留 Graph、Scenario 与 fixture，Library Fork
固定分支坐标并能回收模糊成功；两者都使旧 evidence 失效。详见
[多人保存冲突决策](resource-gateway-ux-round3-s0-conflict-resolution.md)。
