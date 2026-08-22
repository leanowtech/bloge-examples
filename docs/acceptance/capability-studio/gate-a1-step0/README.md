# Gate A1 Step0 Authority

本目录保存 Gate A1 Step0 的 NON_RELEASE 基线：一方面将 3 个 legacy 片段按原始字节
内容寻址隔离，另一方面用 13 个 target schema 和全覆盖 migration mapping 建立新的
Source Authority。

普通验证读取工作区，只证明 Step0 文件彼此一致，不建立提交级权威。候选权威必须再通过
`--index-check`：全部 authority 文件已进入 Git index，且工作区原始字节与 index blob
逐一相同。提交后，CI 或签名审查还应对 HEAD commit 建立外部 attestation。
`parentCommitSha` 只证明 Git 祖先关系，不代表该 commit 中的 live source 是字节事实源。

## 快速验证

```bash
./scripts/oracle/verify-step0.sh
./scripts/oracle/verify-step0.sh --capture-check
./scripts/oracle/verify-step0.sh --index-check

cd /tmp
/absolute/path/to/bloge-examples/scripts/oracle/verify-step0.sh
```

普通与 capture 模式成功输出 `STEP0_PASS`；index 模式成功输出
`STEP0_INDEX_PASS`。任何子进程非零退出、空输出或未知输出都 fail-closed。
验证过程只读取 `.pyc` 原始字节计算摘要，禁止执行或导入 quarantine 中的 `.pyc`。

默认模式验证工作区中的 quarantine bytes。`--capture-check` 用于建立或重新捕获基线，
会额外验证 live source 的真实文件、大小和摘要。两者都只是开发态一致性检查，不能单独
宣称 Step0 candidate authority。

`--index-check` 验证 baseline quarantine、manifest、Legacy authority inventory、全部 103 个
Legacy schema、13 个 target schema、migration mapping 和 3 个 authority script 均满足：路径已被 Git index 跟踪；stage 精确为 0；
mode 仅为 `100644` 或 `100755`，拒绝 symlink；工作区原始字节的 `git hash-object
--no-filters` 结果与 index blob 相同。只有 `STEP0_INDEX_PASS`，或提交后的 HEAD
attestation，才允许宣称 Step0 candidate authority。

## 基线协议

```text
gate-a1-step0/
  step0-manifest-v1.json
  non-release/quarantine/fragments/<sha256-lowerhex>/<original-basename>
```

Manifest 顶层字段精确为 `releaseStatus`、`parentCommitSha`、`generatedAt`、
`artifacts`、`corpusRootDigest`。其中：

- `releaseStatus` 固定为 `NON_RELEASE`；
- `parentCommitSha` 是 40 位小写 Git commit SHA，且必须是 `HEAD` 祖先；
- `generatedAt` 使用 `YYYY-MM-DDTHH:MM:SSZ`；
- `artifacts` 精确包含 3 个描述符；
- `corpusRootDigest` 是描述符数组按 Step0 数字输入 profile 计算的 WireDigest。

每个描述符精确包含 `contentDigest`、`quarantinePath`、`size`、`sourcePath`。
`sourcePath` 必须完整覆盖且只能取以下 3 个值：

- `docs/acceptance/capability-studio/gate-a-wire-v1/protocol-compiler/compile-protocol-authority.py`
- `docs/acceptance/capability-studio/gate-a-wire-v1/trust-build/__pycache__/validate-fixtures.cpython-314.pyc`
- `docs/acceptance/capability-studio/gate-a-wire-v1/trust-build/validate-fixtures.py`

## JCS 数字输入 Profile

Gate A1 全局规范仍是 RFC 8785。Step0 authority document 采用 sound numeric input
profile：UTF-8、`ensure_ascii=false`、对象 key 按 ECMAScript UTF-16 code unit
顺序递归排序；允许 null、boolean、string、array、object，以及区间
`[-9007199254740991, 9007199254740991]` 内的 IEEE-754 safe integer；拒绝 floating-point
number 与越界 integer。对所有被接受的输入，当前 serializer 产生的字节与 RFC 8785
一致；它不宣称是可接受任意 JSON number 的完整 RFC 8785 serializer。

Python 排序必须拆分 16 位 code unit，不能直接按 Unicode code point：

```python
def utf16_sort_key(value):
    encoded = value.encode("utf-16-be", errors="strict")
    return tuple(int.from_bytes(encoded[i:i + 2], "big")
                 for i in range(0, len(encoded), 2))
```

验证器启动时执行中文原文输出、non-BMP/BMP 逆序、escaped surrogate pair 归一化、
Node/ECMAScript key-order vector、嵌套 float、`9007199254740993` 越界 integer、孤立
surrogate 与 duplicate-key 拒绝测试。

## Schema 引用闭包

target 目录必须恰好包含冻结的 13 个 Draft 2020-12 schema。验证器先建立完整的
`$id -> file` registry，再解析每个 `$ref`：

- `#/...` 必须是当前 schema 的有效 JSON Pointer；
- `urn:studio:schema:*#...` 必须命中 registry 中的唯一 `$id`；
- 相对引用必须留在 target root 内并指向 registry 文件；
- 未知 URN、`http(s)`、其他 scheme、路径穿越和 symlink 一律拒绝。

结构化 object schema 必须显式声明 `additionalProperties`；仅承载业务动态值的
`content` / `payload` object 可以保留开放语义。

## Migration Mapping

`docs/schemas/migration-mapping-v1.json` 固定版本与 authority root，精确保留 3 个
authority pair；它只声明迁移身份、摘要与转换版本，不声明字段级语义等价。其余 legacy schema 全部为 `NO_TARGET_EQUIVALENT`，其余 target
schema 全部为 `TARGET_ONLY`。mapping 与 disposition 必须无重叠、无遗漏、无重复；
digest 使用当前 schema 的 Gate A1 JCS 规则重算；Step0 自身文档受上述数字输入 profile
约束；disposition 与 reason 使用冻结值。

`legacy-authority-inventory-v1.json` 冻结全部 Legacy schema ID 与 WireDigest；103 份 Legacy
schema 原文全部纳入 Git index authority，保证迁移库存与兼容包可由该提交独立重建。三份
authority pair 可重放验证；其余 disposition 是对冻结库存的处置声明，不被伪装成可执行字段转换。

## 负向保障与回滚

内置负向测试覆盖 duplicate key、float、UTF-16 排序、absolute/traversal/backslash/
symlink 路径、未知/网络/越界 `$ref`、mapping root/version/disposition/reason 漂移。
Shell 协议另行验证 child nonzero、empty output 和 unknown output 均不能成为成功。

quarantine、manifest、schema exact set、引用闭包或 migration coverage 任一漂移都必须
阻止 Step1。恢复时只能重新建立并审查 Step0 输出，不修改或执行 quarantine 的 legacy
内容。Step0 commit SHA 在提交后由 CI/signed review 作为外部 attestation 记录。
