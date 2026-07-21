# Stage 4 Certificate Rotation Event Page-chain 与 Durable Cursor 验收

## 1. 结论

本子步关闭的是证书轮换事件分发链路的第一个根因：事件本身签名正确，不代表消费位置可恢复、可防
跳页或可防分叉。新增协议把外部事件流冻结为连续 page chain，并为每个稳定 serving slot 建立数据库
游标。游标采用 `stage -> apply -> commit` 两阶段语义，只有调用方确认页面内全部事件已进入各自 durable
rotation floor 后才允许推进 committed head。

这一步没有宣称 CA event watcher 已经运行。HTTP/mTLS 拉取、自动调度、freshness SLO、health 与
capability 接线属于下一子步。事件页的链指纹也不是授权签名；每个事件仍必须独立通过既有 M-of-N
Ed25519 trust store，事件源不能借助传输身份获得轮换授权。

## 2. 根因与不变量

| 根因 | 错误做法 | 本子步约束 |
| --- | --- | --- |
| apply 成功后进程先崩溃 | 内存 cursor 丢失，重启无法区分已处理与未处理 | staged page 持久化；重启只接受 exact replay |
| 页面内部分事件成功 | 直接推进 cursor，剩余 target 永久漏轮换 | 全部 durable apply 成功前禁止 commit |
| source 对同一位置返回另一页面 | 仅比较 sequence，分叉内容可穿透 | staged sequence、predecessor 与 page fingerprint 必须全部一致 |
| source 跳页或回退 | opaque cursor 无法证明单调性 | `sequence = committed + 1` 且 predecessor 必须等于 committed fingerprint |
| 数据库行被直接修改 | 把存储值当可信 cursor | whole-record canonical fingerprint；任一漂移 fail closed |
| 滚动重启换 startup id | 每次从 genesis 重放，旧事件阻塞已恢复 floor | cursor 绑定稳定 instance/serving slot，不绑定 process-start id |
| 多 target 同页存在隐式顺序 | 同一 target 两代事件可能在一页内互相依赖 | 每页最多 12 项，target 必须唯一 |

## 3. 协议

`bloge.controlPlaneCertificateRotationEventPage.v1` 包含：

- exact `deploymentScopeId`；
- 严格递增的 `sequence` 和 `previousPageFingerprint`；
- 有界 `issuedAt/expiresAt`；
- 1..12 个不同 target 的既有 signed rotation event；
- canonical `pageFingerprint`。

`bloge.controlPlaneCertificateRotationEventCursorSnapshot.v1` 只投影 baseline、committed head 与至多一个
staged successor。它不包含事件正文、证书、material id、settings fingerprint、secret ref、路径、
provider exception 或错误文本。

数据库行永久绑定部署配置提供的 baseline sequence/fingerprint。已有游标可从更高 committed head
恢复，但修改 baseline、制造 gap/fork、替换 staged page 或改写任一列都会失败关闭。

## 4. 代码与测试证据

- `ControlPlaneCertificateRotationEventPage`：严格 page material、scope/target 唯一性与 canonical
  fingerprint 验证。
- `ControlPlaneCertificateRotationEventCursor`：冻结 stage/commit closed outcome 与 material-free
  snapshot。
- `DatabaseControlPlaneCertificateRotationEventCursor`：稳定 scope/instance 锁、baseline binding、
  whole-record fingerprint、exact replay 和两阶段推进。
- `control-plane-certificate-rotation-event-page-v1.schema.json` 与
  `control-plane-certificate-rotation-event-cursor-snapshot-v1.schema.json`：机器可验证协议。

聚焦门禁：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ControlPlaneCertificateRotationEventPageTest,\
DatabaseControlPlaneCertificateRotationEventCursorTest,\
ControlPlaneCertificateRotationEventCursorProtocolSchemaTest test
```

结果：15 tests，0 failures、0 errors、0 skips。覆盖 canonical body 篡改、跨 scope、重复 target、空/
超大 page、sub-microsecond 时间、stage/commit/restart、gap、predecessor fork、competing page、exact
并发 replay、baseline drift、whole-record corruption 与 serving-slot 隔离。

## 5. 下一门禁

下一子步将 page-chain cursor 接到严格 HTTP adapter 和自动 watcher：请求必须通过独立
PKIX/hostname/SPKI/mTLS/workload identity transport，响应必须执行 media type、protocol header、
body size、时间窗、scope 与 page fingerprint 校验。watcher 只能在本副本 serving admission 成立时拉取，
并按 `stage -> 对每个 event 调用 runtime.apply -> commit` 执行。source/protocol/cursor/apply 失败必须
形成固定状态且不推进 cursor；health/capability 只能读取本地 bounded descriptor，不触发远程 I/O。
