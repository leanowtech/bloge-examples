# Capability Studio Gate A0 实施与独立复审记录

> 复审日期：2026-08-22
>
> 结论：`A0_IMPLEMENTATION_CANDIDATE_PASS`
>
> 开放项：`P0=0 / P1=0 / P2=0`
>
> 权限边界：该结论不等于 A1 Proof、Gate A admission 或 Gate B 启动许可。

## 1. 本切片交付范围

Gate A0 已形成一条单向、可重算的本地类型化回放流水线：

```text
exact manifest bytes
  -> immutable typed compile
  -> exact Evidence Root seal
  -> 3-slot closed typed replay
  -> one terminal derivation
  -> strict Candidate Result + exact adapter materials
```

| 责任 | 实现 | 关闭的边界 |
|---|---|---|
| Manifest Compiler | `CapabilityStudioFormalEvidenceRunManifest` | duplicate/trailing、canonical bytes、Schema、14 obligation、inventory、0..3 replay、fingerprint |
| Evidence Collector | `CapabilityStudioFormalEvidenceBundleCollector` | POSIX identity、owner/mode/link、exact inventory、subject pre/post digest、final closure、hard-link scope |
| Replay Registry | `CapabilityStudioTypedEvidenceReplayRegistry` | 3 个固定 tuple、typed inputs、真实 verifier、`INVALID/UNAVAILABLE` 分类 |
| Terminal Deriver | `CapabilityStudioCandidateReplayDeriver` | adapter facts、closure、manifest level 的单点优先级归约 |
| Facade / CLI | `CapabilityStudioFormalEvidenceRunVerifier` / `CapabilityStudioFormalEvidenceRunVerifyCli` | 固定阶段、payload-free failure、退出码 `2/3/4` |
| Strict Result | `CapabilityStudioGateACandidateReplayResult` | 3 adapter + 14 obligation、Challenge refs、派生计数、terminal/reason、domain fingerprint、adapter material closure |

模块之间不传递可修改的业务 `JsonNode`。Manifest 只在 Compiler 内解释一次；下游只消费
immutable typed facts。CLI stdout 是本地诊断，不是 wire result。

## 2. 最终语义决策

### 2.1 Evidence Root closure 失败不生成 Result

`GateACandidateReplayResult v1` 没有隐藏的 filesystem closure 槽位。因此：

- closure `VERIFIED`：可以生成严格 Candidate Result；
- closure `INVALID/UNAVAILABLE`：禁止生成半成品 Result，只保留调用方观察的退出码和
  ProcessTranscript；
- Result 内 `INVALID/UNAVAILABLE`：必须分别由至少一个 adapter 的同名状态支撑。

这使 Result 的 adapter count、terminal、reason 和 fingerprint 可以脱离生产者重新计算。

### 2.2 终态优先级

Deriver 固定使用：

```text
UNAVAILABLE > INVALID > STRUCTURE_VERIFIED > INCOMPLETE
```

`STRUCTURE_VERIFIED` 需要至少一个 adapter `VERIFIED`，且没有 `INVALID/UNAVAILABLE`；
`INCOMPLETE` 需要三个 adapter 全部 `NOT_RUN`。`passed` 和 `formalPassCount` 在 A0 恒为
`0`，不得出现 `PASS/ACCEPTED`。

### 2.3 可解析 material

每个 `VERIFIED/INVALID` adapter 都生成一份 canonical exact-byte material。Result 的
`resultRef` 必须与返回 Bundle 中 material 的 URI 和 raw fingerprint 一一闭合；
`UNAVAILABLE/NOT_RUN` 不得携带 resultRef。Bundle 构造器会再次验证该闭包，调用方随后以
create-new 语义持久化。

## 3. 对抗复审闭环

独立复审不是一次性打分，而是发现、修复、补测试、再复审。已关闭问题如下：

| 严重度 | 问题 | 根因修复 | 回归证据 |
|---|---|---|---|
| P1 | same-size/mtime restoration 可绕过 metadata check | adapter 前后复算 file 或 directory descendants raw bytes | file 与 directory mutation tests |
| P1 | final closure 异常可能覆盖先前 adapter facts | Collector 只产出 closure fact，Deriver 单点应用优先级 | mixed adapter/closure precedence test |
| P1 | 只输出诊断 projection，缺完整 strict result | 新增 strict Result writer、Challenge refs、3+14 槽位和 exact materials | candidate result tests |
| P1 | 未知 verifier runtime 被误判为业务 INVALID | 未知基础设施异常统一 `UNAVAILABLE` | schema-unavailable 与 adapter race tests |
| P1 | success terminal 可与 adapter facts 矛盾 | 重算 adapter counts 和 success terminal 条件 | refreshed-fingerprint terminal mutation test |
| P1 | closure failure 被隐藏在 strict result terminal | closure 失败禁止创建 Result；failure terminal 必须有可见 adapter fact | INVALID/UNAVAILABLE relabel tests |
| P1 | terminal 与 reasonCode 可漂移 | Semantic verifier 独立重算 terminal/reason 联合 | reason mutation test |
| P2 | public projection / Bundle 可手工构造矛盾状态 | constructor 重算 level/count 和 resultRef/material closure | forge tests |
| P2 | relative `$ref` 只在 target/classes 验证 | 使用 shaded `-cli.jar` 启动独立 JVM 解析 candidate/common Schema | shaded-JAR IT |
| P2 | 脚本失败可能泄漏本地路径 | Maven/JVM stderr 关闭，输出固定 payload-free reason code | build/runtime leak tests |
| P2 | launcher 未覆盖 `INVALID/2` | 增加真实 invalid manifest 的进程测试 | `2/3/4` matrix tests |
| P2 | adapter unavailable 只做短路或 synthetic 测试 | admission 后删除 subject，再调用三个真实 adapter | 3-adapter disappearance test |

最终独立窄复审结果：

```text
P0: 0
P1: 0
P2: 0
PASS
```

## 4. 构建与运行证据

### 4.1 聚焦矩阵

```text
Tests run: 57
Failures: 0
Errors: 0
Skipped: 0
```

覆盖 8 个 A0 test class：Compiler、Collector、Deriver、Registry、Facade、CLI、Strict
Result 和脚本。

### 4.2 全量 Test Kit

执行：

```bash
mvn -f resource-gateway-test-kit/pom.xml clean verify
```

结果：

```text
Unit tests:       1350 passed
Integration tests:  2 passed
Javadoc gate:        passed
Shaded JAR:          built
Failures / Errors:   0 / 0
```

### 4.3 干净演示链路

```bash
resource-gateway-test-kit/scripts/generate-formal-evidence-run-demo.sh \
  /private/tmp/resource-gateway-a0-demo

resource-gateway-test-kit/scripts/verify-formal-evidence-run.sh \
  --manifest /private/tmp/resource-gateway-a0-demo/manifest.json \
  --bundle-root /private/tmp/resource-gateway-a0-demo/evidence-root
```

预期结果为 payload-free `INCOMPLETE/4`。这证明 A0 正常完成且诚实保留 formal gap，
不表示发布通过。

## 5. 剩余风险与下一步

A0 在同一 OS 用户下运行。它能识别回放前后 identity、metadata 和 bytes 漂移，但不能
抵御同 UID 攻击者在 adapter 读取窗口内完成修改并完全恢复的完整 ABA。该风险不在 A0
本地候选验证中伪装解决，必须由 A1 完成：

1. 独立身份和隔离进程；
2. caller-owned Challenge Pin；
3. actual CodeSource pre/post observation；
4. create-new process/material root；
5. 9 项固定 A1 TCK 和父进程 transcript；
6. 独立 Harness 与后续 A2 admission。

因此，本记录只授权进入 A1 实施，不授权 Gate B。
