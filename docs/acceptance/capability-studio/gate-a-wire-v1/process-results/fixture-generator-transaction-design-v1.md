# Fixture Generator Transaction Design v6

**文件**：`docs/acceptance/capability-studio/gate-a-wire-v1/process-results/fixture-generator-transaction-design-v1.md`
**版本**：v6 — 简化三集模型；精确 trace-derived 枚举；精确状态机；精确 rollback 三态；精确 acknowledge 语义；精确安全边界；移除 v5 发明产物。

---

## 1. 沙箱边界（Sandbox Boundary）

```
SANDBOX_REPO    = 仓库根目录
SANDBOX_WIRE    = SANDBOX_REPO/docs/acceptance/capability-studio/gate-a-wire-v1
SANDBOX_SRCS    = SANDBOX_WIRE/process-results
SANDBOX_TRUST   = SANDBOX_WIRE/trust-build
SANDBOX_COMP    = SANDBOX_WIRE/protocol-compiler
SANDBOX_CANON   = SANDBOX_WIRE/canonicalization
SANDBOX_SCHEMAS = SANDBOX_REPO/docs/schemas/resource-gateway-capability-studio
```

所有路径在物理上位于同一仓库树内，均在 sandbox 范围内。

### 1.1 三集分类（Complement Model）

每条物理路径**精确属于**以下两类之一；两类互不相交，且无祖先歧义：

| 分类 | 含义 | 约束 |
|---|---|---|
| **mutableDirectoryRoots** | 生成器输出目录树根 | 包含目录节点；生成器可在其中 CREATE/REPLACE/DELETE |
| **mutableFiles** | 生成器输出普通文件 | 包含普通文件节点（非目录/链接/特殊文件）；生成器可在其中 CREATE/REPLACE |

**互补模型**：所有不在 `mutableDirectoryRoots` 和 `mutableFiles` 中的 sandbox 路径均为 immutable，由其 SHA256 冻结。COPYING 阶段复制全部 inventoried scope 作为 baseline；before/after 对比验证互补路径未变。

**祖先歧义防护**：若路径 A 是路径 B 的祖先，则两者必须属于同一分类。禁止"父目录可写、子目录不可写"的歧义结构。

### 1.2 Frozen Authority

事务使用以下冻结权威（computed from repo）：

| 用途 | SHA256 |
|---|---|
| Frozen generator | `decc8e3d55738dd2f88828080a05b7d87cdc4c174a98316404486e47bafcb1a9` |
| Frozen validator: process-results/validate-fixtures.py | `47e8ed3b2b4447ce8fe67e38ef2e03bfa80ade4f31c2083e7decc0124c14d593` |
| Frozen validator: process-results/validate_run_material.py | `d3b568e9081897270e45069335a102ff52c279edd699eadf50e029b89ba39dde` |
| Frozen validator: trust-build/validate-fixtures.py | `fddaf572d0d4a2e554f5a6b5702fcc9a2823e39e30bedf27e5e531be5ff462c6` |

权威 SHA 漂移在 CANDIDATE_VALIDATED 阶段 REJECT。

### 1.3 Policy File

Policy 文件路径：`process-results/fixture-generator-output-policy-v1.json`

Policy 由实现生成并 checked in，非预先存在。Future checked-in policy 必须 concrete/non-null。

Future checked-in policy normatively defines exact 3 mutableDirectoryRoots + 42 mutableFiles (42 = 40 process-results + 2 trust-build). Verifier compares policy to trace evidence. Policy may reference prior trace-derived enumeration; no placeholders in final policy.

### 1.4 Before/After Physical Manifest

Physical manifest 覆盖：
- `SANDBOX_WIRE/`（完整 wire 子树）
- `SANDBOX_SCHEMAS/`（schema 子树）

**Copy/manifest 排除**：复制和 manifest 显式排除 transaction namespace 路径以防止递归：
- `.fixture-publish-active/`
- `.fixture-publish-committed/`
- `.fixture-publish-rolled-back/`
- `.fixture-publish-history/`

Stage 使用 exact repo topology，无 transaction namespace 路径。

Diffs outside allowlist → REJECT。

---

## 2. 事务命名空间（Transaction Namespace）

WIRE_ROOT = SANDBOX_WIRE

| 目录 | 用途 |
|---|---|
| `WIRE_ROOT/.fixture-publish-active/` | 活动事务工作目录（writer lock） |
| `WIRE_ROOT/.fixture-publish-committed/` | 已接受事务存档 |
| `WIRE_ROOT/.fixture-publish-rolled-back/` | 已回滚事务存档 |
| `WIRE_ROOT/.fixture-publish-history/` | 历史事务（outcome/fingerprint 索引） |

### 2.1 Active 结构

```
.fixture-publish-active/
  owner.json      # 进程身份（token, pid, processStartIdentity）
  header.json     # lastCompletedPhase, activePhase, operational metadata
  stage/          # 临时 staging 目录（stage/SANDBOX_REPO 结构）
  metadata/       # transactionId, frozenAuthority, policy
  plan.json       # deterministic plan manifest
  backups/        # before-image 备份
  result.json     # deterministic result manifest
```

**owner.json**：包含 token（random operational nonce），pid，processStartIdentity。Token 排除于 plan/result SHA 计算。

**Copy 排除**：stage 目录结构排除 `.fixture-publish-*` 命名空间路径。

**原子 mkdir active 是 writer lock**：`mkdirSync(path, {recursive: false})`，EEXIST 表示已有活动事务。

### 2.2 Terminal Dirs

- `.fixture-publish-committed/`
- `.fixture-publish-rolled-back/`

Normal publish 要求 terminal dirs **均不存在**。

### 2.3 History

```
.fixture-publish-history/COMMITTED/<fingerprint>/
.fixture-publish-history/ROLLED_BACK/<fingerprint>/
  owner.json
  header.json
  metadata/
  plan.json
  result.json
```

**History 路径区分 outcome**：outcome 编码在路径中以区分 COMMITTED 和 ROLLED_BACK。

Explicit acknowledge 精确 fingerprint 后，原子 rename committed/rolled-back → history。禁止自动删除。

---

## 3. 状态机（State Machine）

```
LOCKED → COPYING → GENERATED → CANDIDATE_VALIDATED → PLANNED
                                                       ↓
                                                   BACKED_UP
                                                       ↓
                                                   APPLYING
                                                       ↓
                                                 VALIDATING
                                                       ↓
                                               READY_TO_COMMIT
                                                       ↓
                                                   (terminal)
```

**无 COMMITTED phase**：active → committed 原子 rename 是 acceptance，不存储 phase。

### 3.1 Phase Definitions

| Phase | 入口条件 | 出口条件 |
|---|---|---|
| LOCKED | mkdir active 成功 | 验证无现存 terminal dirs；capture LIVE immutable-complement baseline |
| COPYING | LOCKED | 复制全部 inventoried scope 完成；capture stage BEFORE snapshot |
| GENERATED | 冻结生成器执行完成 | capture stage AFTER snapshot；diff stage BEFORE/AFTER |
| CANDIDATE_VALIDATED | candidate manifest 完整 | 全部 3 个 validator + diff + policy 通过 |
| PLANNED | CANDIDATE_VALIDATED | deterministic plan.json 生成 |
| BACKED_UP | PLANNED | 所有 backups 完成 |
| APPLYING | BACKED_UP | 全部 live apply 完成 |
| VALIDATING | APPLYING | wrapper 验证 current bytes == plan AFTER |
| READY_TO_COMMIT | VALIDATING | 全部 3 live validators exit0；result COMMITTED durable；committed rename 成功 |

---

## 4. Phase Detail

### 4.1 LOCKED

1. `mkdirSync(".fixture-publish-active", {recursive: false})` — writer lock；EEXIST 则 REJECT_ACTIVE_EXISTS
2. 验证 `.fixture-publish-committed/` 和 `.fixture-publish-rolled-back/` 均不存在
3. 写入 `active/owner.json`（token, pid, processStartIdentity）
4. 写入 `active/header.json`（lastCompletedPhase = LOCKED, activePhase = null）
5. `fsync(header.json)`
6. `fsync(active/)`
7. **Capture LIVE immutable-complement baseline**：owner/header durable 后，capture live snapshot 作为 immutable-complement baseline
8. 进入 COPYING

### 4.2 COPYING

1. 复制全部 inventoried scope 到 `active/stage/SANDBOX_REPO` 作为 baseline
2. 包括所有 mutableDirectoryRoots 和 mutableFiles 的当前状态
3. Copy 排除 transaction namespace 路径（见 1.4）
4. before/after 对比验证互补路径（不在 policy 中的路径）未变
5. 写入 `active/metadata/`（transactionId, frozenAuthority, policy）
6. 验证无 symlink/hardlink(nlink≠1)/special 文件
7. pre/post lstat 验证所有路径
8. **Capture stage BEFORE snapshot**：copy 完成 后 capture stage manifest
9. **Update header.json**：lastCompletedPhase = COPYING, activePhase = null（temp file + fsync + rename + parent fsync）
10. 进入 GENERATED

**安全边界**：
- 拒绝 symlink/hardlink/special
- 拒绝 casefold/path traversal

### 4.3 GENERATED

1. 在 `active/stage/` 执行冻结生成器
2. 生成器输出到 stage 目录树
3. 验证输出仅在 mutableDirectoryRoots 和 mutableFiles
4. **Capture stage AFTER snapshot**：generator 执行后 capture stage manifest
5. **Diff stage BEFORE/AFTER**：对比阶段快照
6. 生成 candidate manifest（before snapshot + after snapshot + op list）
7. **Policy validator**：验证所有变更被 policy 覆盖
8. **Update header.json**：lastCompletedPhase = GENERATED, activePhase = null（temp file + fsync + rename + parent fsync）
9. 进入 CANDIDATE_VALIDATED

### 4.4 CANDIDATE_VALIDATED

1. **Diff validator**：stage vs live，验证所有变更在 mutableFiles/mutableDirectoryRoots 内
2. **Three unmodified validators**（exact stage repo topology）：
   - `process-results/validate-fixtures.py` SHA `47e8ed3b2b4447ce8fe67e38ef2e03bfa80ade4f31c2083e7decc0124c14d593`
   - `process-results/validate_run_material.py` SHA `d3b568e9081897270e45069335a102ff52c279edd699eadf50e029b89ba39dde`
   - `trust-build/validate-fixtures.py` SHA `fddaf572d0d4a2e554f5a6b5702fcc9a2823e39e30bedf27e5e531be5ff462c6`
3. **Update header.json**：lastCompletedPhase = CANDIDATE_VALIDATED, activePhase = null（temp file + fsync + rename + parent fsync）
4. 全部通过后进入 PLANNED

**LIVE immutable-complement**：LOCKED 阶段已 capture baseline；CANDIDATE_VALIDATED 验证 live 未偏离该 baseline。

**失败**：任何验证器 REJECT_CANDIDATE_INVALID。

### 4.5 PLANNED

1. 生成 deterministic plan.json：
   - 相对 POSIX 路径（ASCII 排序）
   - 无 timestamps/pid/uid/token/nonce
2. **Verify LIVE immutable-complement unchanged**：立即在 plan 前验证 live snapshot 与 baseline 一致
3. **Update header.json**：lastCompletedPhase = PLANNED, activePhase = null（temp file + fsync + rename + parent fsync）
4. 进入 BACKED_UP

### 4.6 BACKED_UP

1. 对每个 REPLACE/DELETE 操作，创建 before-image backup 到 `active/backups/`
2. Backup 仅用于 replace/delete；不用于 create
3. `created` marker 仅用于 create 操作
4. **Update header.json**：lastCompletedPhase = BACKED_UP, activePhase = null（temp file + fsync + rename + parent fsync）
5. 进入 APPLYING

### 4.7 APPLYING

**Entry protocol**：
1. **Verify LIVE immutable-complement unchanged**：before first LIVE mutation，验证 live snapshot 与 baseline 一致
2. **Set activePhase durably**：`activePhase = APPLYING`（temp file + fsync + rename + parent fsync）

**Apply operations**：
3. 对每个 LIVE 目标文件：
   - temp file in destination parent
   - fsync(temp)
   - rename(temp, destination)
4. 每个目录：deterministic recursive mkdir
5. **Directory ops ordering**：create parents first
6. 所有操作直接应用到 live，不在 active 下完成后再 rename
7. **Update header.json**：lastCompletedPhase = APPLYING, activePhase = null（temp file + fsync + rename + parent fsync）
8. 进入 VALIDATING

**Stage 位于 active 同文件系统**：无 cross-fs rename 需求。

### 4.8 VALIDATING

1. Wrapper 验证 live current bytes == plan AFTER
2. **Verify LIVE immutable-complement unchanged**：验证 live snapshot 与 baseline 一致
3. **Set activePhase durably**：`activePhase = VALIDATING`（temp file + fsync + rename + parent fsync）
4. 三 unmodified validators 运行于 LIVE（writer lock 持有）
5. **Update header.json**：lastCompletedPhase = VALIDATING, activePhase = null（temp file + fsync + rename + parent fsync）
6. 进入 READY_TO_COMMIT

**失败路径**：验证失败时，执行 rollback then `active -> rolled-back` rename。

### 4.9 READY_TO_COMMIT

**前置条件**：全部 3 live validators exit0 且 result COMMITTED durable。

1. **Set activePhase durably**：`activePhase = READY_TO_COMMIT`（temp file + fsync + rename + parent fsync）
2. 写入 deterministic result.json（无 timestamps/pid/token）
3. `fsync(result.json)`
4. **Update header.json**：lastCompletedPhase = READY_TO_COMMIT, activePhase = null（temp file + fsync + rename + parent fsync）
5. `fsync(active/)`
6. `rename("active", "committed")` — 原子 acceptance
7. **Rename 本身移除 active 目录**；无需额外 delete active

**失败**：REJECT_CROSS_FILESYSTEM_RENAME（EXDEV）。

---

## 5. Rollback 三态

| 条件 | 操作 | 结果 |
|---|---|---|
| REPLACE/DELETE：current == after | restore-before | 恢复 before-image |
| CREATE：current == after | **DELETE TARGET then remove created marker** | 删除目标文件；删除 created marker |
| current == before | no-op | 保持 before 状态 |
| else | fail closed | 保留 current，报告错误 |

**CREATE rollback 语义**：DELETE TARGET，然后删除 created marker；不是只删除 marker。

**DELETE absence 表示**：null digest。

**Directory ops ordering**：create parents first / delete children first；rollback inverse。

**Invariants**：
- backups 仅用于 REPLACE/DELETE
- created marker 仅用于 CREATE
- 精确 rollback 每次操作
- fail closed 保留 current 状态

---

## 6. Recovery

### 6.1 进程身份

Owner file（`owner.json`）包含：token, pid, processStartIdentity。

**Linux owner identity**：
- Source: `/proc/<pid>/stat`
- Parse suffix after final `)`（括号后内容）
- Suffix token[0]（field 3，1-indexed）= state
- Suffix token[19]（field 22，1-indexed）= starttime

**macOS owner identity**：
- Command: `LC_ALL=C ps -o lstart= -p <pid>`
- Exact bounded output；解析输出不超过 256 bytes
- Parse 精确输出格式

**Owner missing grace**：owner 缺失后进入 grace period；stale identity explicit 处理。

### 6.2 Recovery Scenarios

**header.json schema**：`{ lastCompletedPhase: Phase|null, activePhase: Phase|null, ... }`

**Phase entry protocol**：On phase entry，durable set `activePhase = Phase`。On phase success，durable set `lastCompletedPhase = Phase, activePhase = null`。

**Recovery key order**：先检查 activePhase；若 activePhase 为 null，使用 lastCompletedPhase。

| activePhase | lastCompletedPhase | 行为 |
|---|---|---|
| APPLYING | - | exact rollback then `active -> rolled-back` rename |
| VALIDATING | - | exact rollback then `active -> rolled-back` rename |
| READY_TO_COMMIT | - | wrapper 验证 plan/result/current == after；完成 committed rename |
| null | LOCKED/COPYING/GENERATED/CANDIDATE_VALIDATED/PLANNED/BACKED_UP | `active -> rolled-back` rename；不执行 rollback 操作 |
| null | APPLYING/VALIDATING | exact rollback then `active -> rolled-back` rename |
| null | READY_TO_COMMIT | wrapper 验证 plan/result/current == after；完成 committed rename |

**Startup/recover 路径**：
1. 检测 active 目录存在
2. 读取 header.json activePhase + lastCompletedPhase
3. 按上表执行恢复路径
4. 检查 frozen authority SHA 未漂移
5. 检查 plan/result 一致性
6. 记录 recover metadata

**无自动 rollback**：recovery 是 explicit 路径，非进程崩溃自动触发。

**Fail closed**：ambiguous current → 保留 active，请求 explicit recover。

### 6.3 Explicit Recover

1. 验证 frozen authority SHA 未漂移
2. 验证 plan/result/current 一致性
3. 完成剩余操作
4. 记录 recover metadata

---

## 7. Acknowledge Outcome

1. 客户端提供 expected resultFingerprint
2. Wrapper 验证 committed resultFingerprint == expected
3. `rename("committed", "history/COMMITTED/<resultFingerprint>")` 原子移动
4. 禁止自动删除 history 条目

**Fingerprint**：deterministic result 的 SHA256（resultFingerprint）。

**Rolled-back acknowledge**：
1. 客户端提供 expected resultFingerprint
2. Wrapper 验证 rolled-back resultFingerprint == expected
3. `rename("rolled-back", "history/ROLLED_BACK/<resultFingerprint>")` 原子移动

---

## 8. Check Mode

1. 使用外部 temp sandbox（不创建 transaction namespace）
2. Temp 可位于不同文件系统（无 rename 需求）
3. **No lock held**
4. 复制 live pre-state
5. 执行 candidate
6. 验证 post-state == expected
7. **不发布任何内容**
8. **Take live pre/post immutable snapshot**；reject drift
9. **Observational**：check mode 是观测性的；在并发 publish 下可能 reject
10. **Never produce false pass**：不会错误地通过验证

---

## 9. Security Boundary

| 检查 | 方法 |
|---|---|
| Symlink | pre/post `lstat` + O_NOFOLLOW（读文件时） |
| Hardlink (nlink≠1) | pre/post `lstat` |
| Special file | pre/post `lstat` |
| Casefold | 检测文件系统能力 |
| Path traversal | 规范化路径 + 验证 prefix |
| Timestamp/pid/token | Plan/result 中明确拒绝 |

**O_CREAT | O_EXCL**：用于创建 temp 文件（不用于 REPLACE 目标）。

**Trust validator invocation**：
- **Stage**：无 active namespace（stage 排除 `.fixture-publish-*`）
- **Live**：trust validator invoked with `--gate-root LIVE_WIRE --transaction-token TOKEN`，环境变量 `GATE_A_FIXTURE_TRANSACTION_TOKEN=TOKEN`，其中 TOKEN 从 `active/owner.json` 读取（bounded nofollow-read）
- **Process validators**：`process-results/validate-fixtures.py` 和 `process-results/validate_run_material.py` 运行 unmodified 正常

**Frozen validators**：不在 frozen validators 中添加函数；wrapper 拥有 transaction checks。

**信任模型**：
- 受信任本地 checkout
- Writer lock（mkdir EEXIST）
- External unlocked readers 负责自己持有读锁

---

## 10. Platform & Durability

| 场景 | 保证 |
|---|---|
| Process crash (macOS/Linux) | 可恢复 journal；wrapper 按 phase 状态恢复 |
| SIGKILL (macOS/Linux) | 同上 |
| Power loss (Linux ext4/xfs) | 仅在 file + parent-dir fsync 实现后有效 |
| Power loss (macOS) | **v1 不实现 F_FULLFSYNC；无 durability 声明** |

**Process-crash consistency**：可恢复 journal，非自动恢复。Power-loss durability 精确到 Linux ext4/xfs + 正确 fsync 实现。

---

## 11. Black-Box Verifier Tests

### 11.1 Trace Completeness

| Test | 验证 | 通过条件 |
|---|---|---|
| T-TC-01 | 所有 42 个 mutableFiles 被 policy 覆盖 | exact match |
| T-TC-02 | 所有 3 个 mutableDirectoryRoots 被 policy 覆盖 | exact match |
| T-TC-03 | 2 个 trust 文件在 mutableFiles 中 | policy match |
| T-TC-04 | Before/after 互补路径未变 | SHA256 match |

### 11.2 Frozen Authority Drift

| Test | 验证 | 通过条件 |
|---|---|---|
| T-FD-01 | Generator SHA 改变 | REJECT_FROZEN_DRIFT |
| T-FD-02 | Process validator SHA 改变 | REJECT_FROZEN_DRIFT |
| T-FD-03 | Run validator SHA 改变 | REJECT_FROZEN_DRIFT |
| T-FD-04 | Trust validator SHA 改变 | REJECT_FROZEN_DRIFT |
| T-FD-05 | Authority SHA 漂移在 CANDIDATE_VALIDATED 检测 | phase == CANDIDATE_VALIDATED |

### 11.3 Candidate Determinism

| Test | 验证 | 通过条件 |
|---|---|---|
| T-CD-01 | 两次独立运行 planFingerprint 一致 | exact match |
| T-CD-02 | Result manifest 不含 timestamps/pid/token | schema validation |
| T-CD-03 | Plan manifest 相对路径、无 timestamps/pid/token | schema validation |

### 11.4 Outside Writes

| Test | 验证 | 通过条件 |
|---|---|---|
| T-OW-01 | 写入非 policy 覆盖路径 | REJECT_OUTSIDE_POLICY |
| T-OW-02 | Diffs outside allowlist | REJECT_OUTSIDE_ALLOWLIST |
| T-OW-03 | 祖先歧义路径 | REJECT_AMBIGUOUS_ANCESTRY |

### 11.5 Three Validators

| Test | 验证 | 通过条件 |
|---|---|---|
| T-TV-01 | `process-results/validate-fixtures.py` 通过 | exit 0 |
| T-TV-02 | `process-results/validate_run_material.py` 通过 | exit 0 |
| T-TV-03 | `trust-build/validate-fixtures.py` 通过 | exit 0 |

### 11.6 Trust Validator Live Invocation

| Test | 验证 | 通过条件 |
|---|---|---|
| T-TV-04 | Live trust validator 接收 --gate-root + --transaction-token + GATE_A_FIXTURE_TRANSACTION_TOKEN env | env parsed |

### 11.7 Crashes at Every Phase

| Test | Phase | 验证 |
|---|---|---|
| T-CP-01 | LOCKED | SIGKILL 后 REJECT_ACTIVE_INCOMPLETE |
| T-CP-02 | COPYING | recover 路径完成 |
| T-CP-03 | GENERATED | recover 路径完成 |
| T-CP-04 | CANDIDATE_VALIDATED | recover 路径完成 |
| T-CP-05 | PLANNED | recover 路径完成 |
| T-CP-06 | BACKED_UP | recover 路径完成 |
| T-CP-07 | APPLYING | exact rollback → rolled-back |
| T-CP-08 | VALIDATING | exact rollback → rolled-back |
| T-CP-09 | READY_TO_COMMIT | 验证 plan/result/current==after → committed |

### 11.8 Concurrency

| Test | 验证 | 通过条件 |
|---|---|---|
| T-CO-01 | 并发 publish 调用 | 序列化；第二个 REJECT_ACTIVE_EXISTS |
| T-CO-02 | Check mode 不干扰 publish | live snapshot 未改变 |

### 11.9 Stale/Missing Owner

| Test | 验证 | 通过条件 |
|---|---|---|
| T-SM-01 | Live owner 存在 | REJECT_LIVE_OWNER_EXISTS |
| T-SM-02 | Owner 缺失 grace period | REJECT_ACTIVE_INCOMPLETE |
| T-SM-03 | Explicit recover | Owner 缺失 grace 后成功 recover |

### 11.10 Rollback Tri-State

| Test | 条件 | 通过条件 |
|---|---|---|
| T-RT-01 | REPLACE: current == after | restore-before |
| T-RT-02 | DELETE: current == after | restore-before |
| T-RT-03 | CREATE: current == after | DELETE TARGET then remove created marker |
| T-RT-04 | current == before | no-op |
| T-RT-05 | ambiguous | fail closed |

### 11.11 Terminal Acknowledgement

| Test | 验证 | 通过条件 |
|---|---|---|
| T-TA-01 | resultFingerprint 匹配 | committed → history atomic |
| T-TA-02 | resultFingerprint 不匹配 | REJECT_FINGERPRINT_MISMATCH |
| T-TA-03 | 无自动删除 | history 条目持久存在 |
| T-TA-04 | Rolled-back acknowledge | rolled-back → history atomic |

### 11.12 Check No-Touch

| Test | 验证 | 通过条件 |
|---|---|---|
| T-CN-01 | 不创建 transaction namespace | 目录不存在 |
| T-CN-02 | 不发布任何内容 | live 未改变 |
| T-CN-03 | 两次 deterministicFingerprint 一致 | exact match |

### 11.13 Platform

| Test | 验证 | 通过条件 |
|---|---|---|
| T-PL-01 | macOS process crash | 可恢复 journal |
| T-PL-02 | Linux process crash | 可恢复 journal |
| T-PL-03 | Cross-filesystem rename | REJECT_CROSS_FILESYSTEM_RENAME |

---

## 12. Proof Invariants

1. **Writer lock**：同时仅一个活动事务（mkdir EEXIST 检测）
2. **Determinism**：Plan 和 result 无时间戳/pid/token/nonce；plan 相对路径；token 排除于 plan/result SHA
3. **Atomic acceptance**：`rename(active, committed)` 是 acceptance；无 acceptance 前
4. **Partial live apply**：可恢复（plan + backups）而非自动
5. **Terminal acknowledgement**：resultFingerprint 匹配后显式 acknowledge；rolled-back 有独立 acknowledge 路径
6. **Frozen authority**：SHA256 漂移在 CANDIDATE_VALIDATED 拒绝
7. **Complement model**：所有未覆盖路径为 immutable
8. **Three validators**：process validators unmodified；trust validator stage 无 namespace，live 带 token
9. **Header durable**：header.json 仅在 phase artifacts durable 后更新；activePhase + lastCompletedPhase dual-field

---

## 13. Schema

### 13.1 header.json

```json
{
  "lastCompletedPhase": "COPYING|GENERATED|CANDIDATE_VALIDATED|PLANNED|BACKED_UP|APPLYING|VALIDATING|READY_TO_COMMIT|null",
  "activePhase": "APPLYING|VALIDATING|READY_TO_COMMIT|null",
  "transactionId": "<uuid>",
  "timestamp": "<iso8601>"
}
```

### 13.2 owner.json

```json
{
  "token": "<random-nonce>",
  "pid": "<integer>",
  "processStartIdentity": "<platform-specific-identity>"
}
```

Token excluded from plan/result SHA computation.

### 13.3 plan.json

```json
{
  "messageVersion": 1,
  "policyFingerprint": "sha256:<64hex>",
  "authorityHashes": {
    "generator": "<64hex>",
    "processValidator": "<64hex>",
    "runValidator": "<64hex>",
    "trustValidator": "<64hex>"
  },
  "beforeManifestSha256": "<64hex>",
  "afterManifestSha256": "<64hex>",
  "operations": [
    {
      "relativePath": "process-results/output/file.json",
      "type": "CREATE|REPLACE|DELETE",
      "beforeSha256": null,
      "afterSha256": "<64hex>"
    }
  ],
  "planFingerprint": null
}
```

**字段**：
- `messageVersion`：版本号
- `policyFingerprint`：计算时为 null；最终为 `sha256:<64hex>`
- `authorityHashes`：frozen authority SHA
- `beforeManifestSha256`：stage BEFORE snapshot SHA
- `afterManifestSha256`：stage AFTER snapshot SHA
- `operations`：按相对路径排序；type/beforeSha256 可 null；afterSha256 可 null
- `planFingerprint`：self-null（计算后填充）；canonical SHA

**无 operational 字段**：plan.json 不包含 policy 内容或其他操作语义。

### 13.4 result.json

```json
{
  "messageVersion": 1,
  "outcome": "COMMITTED|ROLLED_BACK",
  "planFingerprint": "<64hex>",
  "validatorOutcomes": {
    "processValidator": { "exitCode": 0 },
    "runValidator": { "exitCode": 0 },
    "trustValidator": { "exitCode": 0 }
  },
  "finalManifestSha256": "<64hex>",
  "resultFingerprint": null
}
```

**字段**：
- `messageVersion`：版本号
- `outcome`：COMMITTED 或 ROLLED_BACK
- `planFingerprint`：对应 plan 的 fingerprint
- `validatorOutcomes`：固定名称 + exitCode
- `finalManifestSha256`：最终 live manifest SHA
- `resultFingerprint`：self-null（计算后填充）；canonical SHA；用于 acknowledge

**Rollback result**：for rollback，在 active->rolled-back 前 generate result。

---

## 14. Implementation Score

**P0 Design（开放独立评审）**：
- 三集互补模型：完整
- 状态机：完整
- Rollback 三态：完整
- Acknowledge 语义：完整（resultFingerprint + rolled-back acknowledge）
- 安全边界：完整（trust validator 注入）
- Platform 声明：精确（无 macOS durability 声明）
- 3 个 validator 覆盖：完整
- 42 mutableFiles + 3 mutableDirectoryRoots：完整（policy normative statement）
- Schema（plan.json/result.json/header.json/owner.json）：完整
- Transaction namespace 排除：完整
- Stage snapshot capture order：完整
- Header dual-field（activePhase + lastCompletedPhase）：完整
- Check mode observational：完整
- Recovery activePhase key：完整

**Score ≤90**：待独立 P0/P1 评审通过后方可声明冻结。

---

*文档版本：v6（待评审）| 变更：COPYING 先 copy 后 capture BEFORE；GENERATED 先 run 后 capture AFTER 再 diff 再 policy；LOCKED capture LIVE baseline after durable；APPLYING reverify then durable activePhase；header activePhase+lastCompletedPhase dual-field；Recovery key activePhase first；READY activePhase READY_TO_COMMIT then write/fsync then update；Trust validator --gate-root --transaction-token TOKEN + env；owner.json token+pid+processStartIdentity；mac <=256 bytes；Policy normative 3+42 list statement；Acknowledge uses resultFingerprint not planFingerprint；Rolled-back acknowledge path；Add T-TV-04 test；Remove duplicates/contradictions；Score 90 pending review。*
