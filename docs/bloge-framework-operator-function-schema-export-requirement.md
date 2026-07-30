# BLOGE Framework Operator / Function Schema Export Requirement

状态：Proposed  
目标读者：BLOGE framework / engine / developer tooling 团队  
提出背景：resource-gateway 通用可视化编排画布近几轮迭代  
期望结果：BLOGE 框架层原生支持导出算子库与表达式函数 schema JSON，供画布、IDE、测试、网关和生态工具统一消费

## 1. 一句话需求

BLOGE 框架应该提供一个稳定的 **Operator & Function Schema Export** 能力：从 BLOGE runtime / operator registry / expression function registry 中导出标准 JSON 文档，描述当前应用可用的算子、端口 input/output schema、config schema、执行能力、治理元数据、表达式 built-in functions 及其签名。

这不是要求 BLOGE framework 内置可视化画布。真正要推进的是更基础的框架能力：**把 BLOGE 运行时已有的可编排能力，以机器可读、可版本化、可校验、可分发的 schema artifact 暴露出来。**

## 2. 背景

这几天我们基于 `resource-gateway-examples` 做了一轮通用可视化编排画布迭代。画布已经不再只是资源网关 demo，而是在逼近一个通用模式：

```text
operator library
  -> schema-aware palette
  -> schema-constrained drag/drop and connection
  -> validate
  -> simulate with mock/real hybrid execution
  -> export draft/publication
  -> run test suite/golden regression
```

为了支撑这个模式，我们在 example 层已经做了几件关键工作：

| 能力 | 当前位置 | 说明 |
| --- | --- | --- |
| 可视化算子库合同 | `docs/bloge-visual-operator-library-schema.md` | 定义 `bloge.visualOperatorLibrary.v1` / `bloge.visualOperator.v1` |
| 机器 JSON Schema | `docs/schemas/bloge-visual-operator-library.schema.json` | 为用户手写 JSON/YAML 算子库做结构预检 |
| Java wire model | `OperatorLibrary` / `OperatorDefinition` | 描述 library、operator、ports、configSchema、capabilities、policy、lowering |
| Built-in function schema | `OperatorLibrary.BuiltInFunction` | 描述表达式函数名、namespace、signature、parameters、returns、examples |
| 人类创作合同 | `bloge.visualLibraryAuthoring.v1` | 让用户用紧凑类型、archetype 和签名定义能力，再确定性编译到 visual library；不是 runtime source of truth |
| 权威预览 API | `/admin/visual-operator-library-authoring/preview` | 安全解析、编译、canonical validate、callable conflict 与 target diff |
| Catalog API | `GET /api/visual/operators` | 下发 operators + `builtInFunctions` 给画布 |
| Builtin export | `GET /api/visual-operator-libraries/builtin/export` | 把当前服务端 Java operator registry 投影成可导入的 operator library |
| 函数目录 | `BuiltInFunctionCatalog.defaults()` | 手工声明 `coalesce`、`toNumber`、`jsonPath`、`round` 等表达式函数 |
| 画布消费 | `/author/` | 根据 operator/function schema 做 palette、连线校验、详情浮层、transform 函数补全、Test Suite |

这些工作证明了一个事实：**一旦 operator/function schema 被标准化，BLOGE 的可视化、测试、集成和生态扩展能力会明显提升。**

Resource Gateway 已经补出一层面向人的 `bloge.visualLibraryAuthoring.v1`，但它只解决“如何更容易地声明和审阅能力”。Framework export 仍然不可替代：runtime operator/function inventory、implementation fingerprint、determinism 和 profile availability 必须由 BLOGE 框架提供，不能从用户填写的 authoring YAML 反推成运行真相。

但这也暴露出一个更根本的问题：当前 schema 能力是在 resource-gateway example 层“反向补出来”的，不是 BLOGE 框架层的原生能力。

### 2.1 与存量 DSL 可视化迁移的关系

存量业务升级到可视化交付时，仅有 operator/function schema 还不够。业务团队通常已经在代码库里积累了两类资产：

1. 自定义 Java operator 与 expression built-in function。
2. 已上线或已验证的手写 `.bloge` DSL。

因此完整迁移链路应该分成两段：

```text
任意合法 operator/function schema -> visual operator/function library
手写 .bloge -> DslCompiler.parseAst() -> VisualGraphDraft + source map + diagnostics
```

本文件解决第一段里的一种高质量来源：**从 runtime / registry / build metadata 导出业务能力 schema**。对通用画布来说，schema provenance 不应进入渲染边界；只要输入的 operator/function schema 结构合法，就应该能和手写 `.bloge` 一起进入 DSL 可视化投影。第二段已经在 resource-gateway 示例层以 `POST /api/visual/dsl-imports/preview`、`POST /api/visual/dsl-imports/commit`、`POST /api/visual/dsl-imports/rewrite-gate` 和 `/author/` Legacy DSL 面板验证了浏览器 preview/render/source-map/stored-draft/source-replacement-preflight 路径；后续仍需继续推进 opaque 保留和真正的源码 writer / VCS 集成，详见 [存量 BLOGE DSL 业务迁移到可视化编排设计方案](./bloge-legacy-dsl-visual-migration-design.md)。

## 3. 要解决的问题

### 3.1 算子运行能力没有标准机器合同

BLOGE runtime 知道有哪些 operator、operator 如何执行、输入输出大概是什么，但外部工具不知道。画布、IDE、测试平台、网关、agent toolchain 如果要理解这些能力，只能各自做适配：

```text
BLOGE runtime/operator registry
  -> application-specific projection
    -> custom JSON contract
      -> visual canvas / gateway / tests consume
```

这个路径最大的问题不是“能不能做”，而是每个集成都要重新发明一次 schema 投影、命名规范、端口建模、配置建模和兼容性判断。

### 3.2 表达式函数是隐形能力

`bloge:transform`、branch、decision、config expression 等场景都依赖 BLOGE expression functions。用户真正写表达式时需要知道：

- 有哪些函数可以调用。
- 函数名是什么。
- 支持哪些 overload。
- 参数顺序、可选参数、variadic 参数是什么。
- 返回类型是什么。
- 示例表达式怎么写。
- 函数是否纯函数、是否确定性、是否只在某些 runtime profile 可用。

当前 example 层用 `BuiltInFunctionCatalog.defaults()` 手工维护了一份函数目录。它能支持画布提示，但它不是 engine source of truth。一旦 BLOGE expression evaluator 增减函数、改签名或引入 profile-specific function，文档和运行时就可能漂移。

### 3.3 可视化画布不能成为 schema source of truth

画布应该消费 schema，不应该定义 BLOGE 的真实能力。现在 `bloge.visualOperatorLibrary.v1` 在 example 层已经很有价值，但它的定位仍然偏 authoring/import contract。

如果没有框架层导出能力，长期会形成错误边界：

```text
visual canvas owns operator/function schema
```

这会导致三个后果：

1. **倒置依赖**：框架能力被 UI 合同反向约束。
2. **语义不完整**：UI 只能看见适配层暴露的子集，不能覆盖 runtime 真正的 operator/function 语义。
3. **生态碎片化**：不同应用、不同画布、不同网关可能各自定义不兼容的 operator schema。

正确边界应该是：

```text
BLOGE framework owns operator/function contract
  -> visual canvas consumes it
  -> gateway/IDE/test/agent tools consume it
```

### 3.4 当前 Java operator introspection 是有损投影

`JavaOperatorInventoryProjector` 已经能把内置 Java operator registry 投影成 visual schema，但这是 example 层的 best-effort：

- 某些 Java `SchemaDescriptor` 不能完整映射到 visual JSON Schema 子集时，只能降级为 opaque schema。
- 输入端口命名、DSL-safe field、source.kind relabel 等规则是 resource-gateway 适配层在兜底。
- operator capability、side effect、idempotency、streaming、durable、secret requirement 等治理元数据没有统一框架导出规范。
- 函数 schema 不是从 expression runtime 自动导出，而是手工维护。

这条路可以证明方向，但不能成为生态长期解。

## 4. 我们现有的解法

当前 `resource-gateway-examples` 已经建立了一套可运行的 example-level 解法。

### 4.1 `bloge.visualOperatorLibrary.v1`

我们定义了一个 operator library root object：

```yaml
schemaVersion: bloge.visualOperatorLibrary.v1
libraryId: risk-policy
displayName: Risk Policy
version: 1.0.0
owner: risk-platform
status: ACTIVE
builtInFunctions: []
operators: []
```

其中 `operators[]` 描述：

- `operatorRef` / `operatorVersion`
- `display`
- `source`
- `ports.inputs[]` / `ports.outputs[]`
- `configSchema`
- `capabilities`
- `policy`
- `lowering`

端口 schema 采用 JSON Schema envelope：

```yaml
schema:
  format: json-schema
  version: "2020-12"
  schema:
    type: object
    properties:
      applicantId:
        type: string
    required: [applicantId]
```

### 4.2 Built-in function schema

我们把 expression function 也纳入 library contract：

```yaml
builtInFunctions:
  - name: coalesce
    namespace: bloge
    displayName: coalesce
    description: Returns the first non-null argument.
    category: null-handling
    signatures:
      - label: coalesce(value, fallback)
        parameters:
          - name: value
            type: any
          - name: fallback
            type: any
        returns:
          type: any
    examples:
      - coalesce(inputs.primaryScore, 0)
```

画布使用这份 function schema 做：

- transform expression 函数 chip。
- 函数名补全。
- signature hint。
- 内置复杂示例中的 `coalesce(...)`、`toNumber(...)`、`round(...)` 可发现性。

### 4.3 Builtin operator library export

`BuiltinOperatorLibraryExporter` 把服务端内置 Java operator registry 作为虚拟 `builtin` library 导出：

```text
GET /api/visual-operator-libraries/builtin/export
```

这让“框架/应用自带算子”和“用户导入算子库”走同一种画布消费模型：

```text
builtin Java operators
  -> bloge.visualOperatorLibrary.v1
  -> import/export/validate/visual compose
```

### 4.4 现有解法的价值

这套 example-level 解法已经证明：

1. operator/function schema 可以支撑通用画布，而不是只服务 resource gateway。
2. schema-aware connection 可以降低编排错误。
3. function schema 可以显著改善 expression authoring。
4. mock/test/golden 可以基于 schema 自动生成样例、验证输入输出和跑批量用例。
5. builtin export 可以让系统自带能力也变成可分发、可审阅、可迁移的 artifact。

### 4.5 现有解法的局限

局限也很明确：

| 局限 | 影响 |
| --- | --- |
| source of truth 在 example 层 | 其他 BLOGE 应用无法直接复用，需要复制模型和投影代码 |
| function catalog 手工维护 | expression evaluator 与 editor hint 可能漂移 |
| Java operator schema projection 有损 | 不支持的 schema descriptor 会降级 opaque，工具链无法做强校验 |
| capability/governance 字段不是框架标准 | 不同应用可能对 side effect、idempotency、secret、streaming 的解释不同 |
| export API 是 Spring/resource-gateway 形态 | 非 Spring 应用、CLI、build plugin、IDE 插件无法统一使用 |
| 兼容性策略未上升到框架层 | library diff、schema drift、breaking/compatible 变更缺少统一判定基础 |

所以当前方案适合证明产品方向，不适合作为 BLOGE 生态的最终合同边界。

## 5. 期望 BLOGE 框架层提供的解决方案

### 5.1 目标能力

BLOGE framework 提供一组框架级 API/SPI/CLI，用于导出当前应用的 operator/function schema：

```text
BLOGE OperatorRegistry
BLOGE ExpressionFunctionRegistry
BLOGE Runtime Capability Metadata
BLOGE SchemaDescriptor / TypeDescriptor
        |
        v
BlogeSchemaExporter
        |
        v
bloge.operatorLibrary.v1.json
```

建议产物命名可以是：

- `bloge.operatorLibrary.v1`
- `bloge.operatorCatalog.v1`
- `bloge.expressionFunctionCatalog.v1`

是否沿用当前 `bloge.visualOperatorLibrary.v1` 需要 BLOGE 团队决策。我的建议是：**框架层命名去掉 `visual`，但保持字段与当前 visual contract 尽量兼容。** Visual canvas 是主要消费者之一，但不是唯一消费者。

### 5.2 框架 API

建议提供核心 Java API：

```java
public interface BlogeSchemaExporter {
    OperatorLibrarySchema exportOperatorLibrary(OperatorLibraryExportOptions options);
    ExpressionFunctionCatalogSchema exportExpressionFunctions(FunctionExportOptions options);
    CombinedCapabilitySchema exportAll(CapabilityExportOptions options);
}
```

其中 `exportAll()` 产出一个可供工具消费的完整 JSON：

```json
{
  "schemaVersion": "bloge.operatorLibrary.v1",
  "libraryId": "builtin",
  "displayName": "Built-in BLOGE Operators",
  "version": "1.0.0",
  "frameworkVersion": "x.y.z",
  "functions": [],
  "operators": []
}
```

API 不应该绑定 Spring。Spring Boot starter 可以自动暴露 actuator/admin endpoint，但核心 exporter 应该可被 CLI、Maven plugin、Gradle plugin、IDE plugin、测试框架直接调用。

### 5.3 Operator schema 标准字段

框架层 operator schema 至少应覆盖：

| 字段 | 说明 |
| --- | --- |
| `operatorRef` | 稳定引用，编排 draft、DSL、trace、test 都使用 |
| `operatorVersion` | operator 合同版本 |
| `display` | name、description、tags |
| `inputPorts` / `outputPorts` | 端口名、required、description、JSON Schema |
| `configSchema` | 节点配置 schema |
| `runtime` | Java/native/remote/HTTP/tool/event/publication 等 runtime binding 类型 |
| `capabilities` | sideEffect、idempotency、streaming、durable、requiresSecrets、timeout、retry hint |
| `executionMode` | pure transform、branch、decision、source、sink、external call 等语义分类 |
| `security` | secret binding、tenant scope、network egress、PII/data classification hint |
| `examples` | input/output/config 示例 |
| `compatibility` | schema fingerprint、breaking-change hint、deprecated/superseded 信息 |

这些字段不必一次性全做满，但命名空间要预留，避免后续每个生态工具自己扩字段。

### 5.4 Function schema 标准字段

表达式函数不应该只是 UI 文案。框架层 function schema 至少应覆盖：

| 字段 | 说明 |
| --- | --- |
| `name` | 表达式中实际调用的函数名 |
| `namespace` | 来源命名空间，例如 `bloge`、`risk`、`date` |
| `displayName` / `description` / `category` | 编辑器展示与分组 |
| `signatures[]` | overload 列表 |
| `parameters[]` | name、type、schema、optional、variadic、description |
| `returns` | type、schema、description |
| `examples[]` | 可复制表达式片段 |
| `purity` | pure / readsContext / external / unknown |
| `determinism` | deterministic / timeDependent / random / external |
| `availability` | runtime profile、feature flag、deprecated/since |
| `evaluationSemantics` | null handling、type coercion、error behavior |

其中 `purity` 和 `determinism` 很关键。它们会影响：

- 可视化编辑器是否允许在 decision/transform 中使用。
- 模拟运行是否可以安全执行。
- golden test 是否稳定。
- 编排发布是否需要额外审批。

### 5.5 Schema projection SPI

BLOGE 应该提供 operator/function schema 的 source-of-truth 注入点，而不是完全依赖反射猜测。

建议支持三种来源，优先级从高到低：

1. **显式 schema provider**

   ```java
   public interface BlogeOperatorSchemaProvider {
       OperatorSchema describeOperator();
   }
   ```

2. **annotation metadata**

   ```java
   @BlogeOperator(
       ref = "risk:score",
       version = "1.0.0",
       sideEffect = READ_ONLY,
       idempotency = DETERMINISTIC
   )
   ```

3. **best-effort type introspection**

   从 Java DTO、record、SchemaDescriptor、generic type 等推断 schema；无法表达时显式输出 warning，不静默丢失。

这能把当前 `JavaOperatorInventoryProjector` 的有损投影升级为框架支持的可治理机制。

### 5.6 Expression function registry 需要可导出

当前最需要框架层补齐的是 function registry。建议 BLOGE expression engine 维护可查询的函数元数据：

```java
public interface BlogeExpressionFunctionRegistry {
    List<ExpressionFunctionDescriptor> functions(FunctionQuery query);
}
```

函数实现注册时必须能带上 descriptor：

```java
registerFunction(
    "coalesce",
    implementation,
    descriptor -> descriptor
        .pure()
        .deterministic()
        .signature("coalesce(value, fallback)")
        .parameter("value", ANY)
        .parameter("fallback", ANY)
        .returns(ANY)
);
```

这样 transform editor、branch editor、validation、AST lint、test generator 才能消费同一个事实源。

### 5.7 导出入口形态

建议同时提供四类入口：

| 入口 | 用途 |
| --- | --- |
| Java API | 应用内、测试、网关直接调用 |
| CLI | 本地开发、CI、文档生成 |
| Maven/Gradle plugin | 构建期生成 schema artifact |
| Optional HTTP endpoint | Spring Boot / gateway / admin console 暴露运行时 catalog |

示例：

```bash
bloge schema export \
  --include-operators \
  --include-functions \
  --format json \
  --out target/bloge-operator-library.json
```

Spring Boot starter 可以约定：

```text
GET /actuator/bloge/operators
GET /actuator/bloge/functions
GET /actuator/bloge/capabilities
```

具体路径可讨论，但要避免只有 resource-gateway 才能导出。

### 5.8 版本、兼容性与 diff

框架导出 schema 不能只是“当前快照”，还应该为生态治理留下基础：

- 每个 operator/function 有 stable id。
- schema artifact 有 `schemaVersion`。
- operator/function 有 `since`、`deprecated`、`removedIn`、`replacement`。
- ports/config/function signature 有 fingerprint。
- 提供 breaking / compatible / cosmetic change 判定 helper。

至少需要支持：

```text
old exported schema
  + new exported schema
    -> compatibility report
```

这会直接服务于：

- 画布 draft dependency drift。
- publication rebase。
- CI contract test。
- operator marketplace 升级提示。

## 6. 建议的分阶段落地

### Phase 1：标准化 schema artifact

目标：框架团队确认 JSON artifact 的命名、版本、核心字段。

交付：

- `bloge.operatorLibrary.v1` JSON Schema。
- `bloge.expressionFunctionCatalog.v1` JSON Schema。
- Java record/model。
- 兼容当前 `bloge.visualOperatorLibrary.v1` 的迁移映射。

验收：

- 当前 `resource-gateway-examples` 的 `bloge.visualOperatorLibrary.v1` 能无损映射到框架 artifact 的核心字段。
- function schema 能覆盖 `coalesce/defaultIfBlank/toNumber/toString/jsonPath/contains/round/formatDate`。

### Phase 2：框架 exporter API + function registry metadata

目标：不再手工维护 `BuiltInFunctionCatalog.defaults()` 这类影子目录。

交付：

- `BlogeSchemaExporter`。
- `BlogeExpressionFunctionRegistry` metadata。
- 核心函数 descriptor。
- 单元测试保证 evaluator 注册函数与导出函数一致。

验收：

- expression engine 新增/删除函数时，导出 catalog 自动变化。
- transform editor 可以用框架导出的 function schema 生成补全和签名提示。

### Phase 3：operator schema provider SPI

目标：让 operator 作者能显式声明 schema，反射只做兜底。

交付：

- `BlogeOperatorSchemaProvider`。
- `@BlogeOperator` / `@BlogeInput` / `@BlogeOutput` / `@BlogeConfig` 等 annotation 或等价 builder。
- Java DTO / SchemaDescriptor 到 JSON Schema 的标准 projector。
- 无法投影时的 diagnostics。

验收：

- 内置 Java operators 能导出 input/output/config schema。
- 有损投影必须带 warning，不允许静默 opaque。
- operator side effect/idempotency/secret/streaming 信息可导出。

### Phase 4：工具链入口

目标：让 schema artifact 进入日常开发与集成流程。

交付：

- CLI export。
- Maven/Gradle plugin。
- Spring Boot actuator/admin endpoint。
- compatibility diff helper。

验收：

- CI 可以生成 `target/bloge-operator-library.json`。
- IDE / visual canvas / gateway 能直接消费该 JSON。
- schema diff 能识别 operator port breaking change 和 function signature breaking change。

## 7. 非目标

这份需求不要求：

1. BLOGE framework 内置 React/可视化画布。
2. BLOGE framework 负责所有远程 worker / HTTP / AI tool 的 runtime binding 实现。
3. 一次性完成完整 marketplace、权限治理、发布审批。
4. 在第一阶段做表达式 AST 级类型系统。

这些都是后续生态能力。当前最核心的是：**先让 BLOGE 框架能够稳定导出“我有哪些可编排能力，以及这些能力的输入、输出、配置和函数签名是什么”。**

## 8. 生态价值

### 8.1 对 BLOGE 开发者

- 写 operator 时能同时生成运行时能力和工具合同。
- 减少手写文档、手写 JSON、手写 UI adapter。
- operator 变更可以在 CI 中自动发现 breaking change。

### 8.2 对可视化编排

- Palette、搜索、拖拽、连线、详情浮层、配置表单、mock sample、Test Suite 都可以从 schema 自动生成。
- 画布不再需要为每个应用硬编码 operator/function 知识。
- 用户自定义 operator 与框架内置 operator 能进入同一编排体验。

### 8.3 对测试和质量治理

- 根据 input/output/config schema 自动生成 mock row。
- 根据 function signature 检查表达式写法。
- 根据 schema diff 做 draft dependency drift、publication rebase、golden regression。
- 通过 purity/determinism 元数据判断哪些函数/算子可安全模拟。

### 8.4 对生态集成

- IDE 插件可以做表达式补全、operator 补全、schema hover。
- Gateway 可以自动生成 resource/operator catalog。
- Agent toolchain 可以根据 operator schema 选择工具、构造参数、验证返回值。
- 第三方团队可以发布 operator package，而不是只发布 Java jar 或口头文档。

### 8.5 对 BLOGE 框架定位

这项能力会把 BLOGE 从“能运行图”的框架，推进到“能描述、分发、治理和集成图能力”的生态底座。

没有 schema export，BLOGE 的能力主要留在运行时内部。  
有 schema export，BLOGE 的能力可以进入 IDE、CI、可视化画布、网关、agent 和 marketplace。

这就是生态扩展的关键分水岭。

## 9. 建议提交给 BLOGE 团队的需求摘要

> 请在 BLOGE framework 层提供 Operator / Expression Function schema export 能力。框架应能从 operator registry、expression function registry、schema descriptor 和 runtime metadata 中导出稳定 JSON artifact，描述当前应用可用的 operator、input/output/config schema、capability/governance metadata，以及 built-in expression functions 的签名、参数、返回值、示例和纯度/确定性。该能力应通过 Java API、CLI/build plugin 和可选 HTTP endpoint 暴露，并提供版本、fingerprint、compatibility diff 基础。resource-gateway visual canvas 已经在 example 层验证了该模型的价值，但当前实现仍是应用侧投影和手工函数目录，存在 source-of-truth 漂移、有损 introspection 和生态复用困难。将 schema export 上升到 BLOGE 框架层，可以显著提升可视化编排、IDE 补全、schema-gated testing、gateway 集成、agent toolchain 和第三方 operator package 的开发体验。

## 10. 验收标准草案

| 编号 | 验收标准 |
| --- | --- |
| AC-1 | BLOGE framework 提供可调用 API 导出当前 operator catalog JSON |
| AC-2 | 导出 JSON 包含每个 operator 的 input/output/config schema |
| AC-3 | 导出 JSON 包含每个 operator 的 side effect、idempotency、streaming、secret requirement 等基础 capability |
| AC-4 | BLOGE expression function registry 能导出 built-in functions schema |
| AC-5 | function schema 至少包含 name、signature、parameters、returns、examples、purity、determinism |
| AC-6 | 当前 resource-gateway `bloge.visualOperatorLibrary.v1` 可迁移/映射到框架 artifact |
| AC-7 | 当前 visual canvas 可改为消费框架导出的 artifact，而不是维护影子 schema |
| AC-8 | CLI 或 build plugin 可在 CI 中生成 schema artifact |
| AC-9 | 导出能力能报告无法精确投影的 operator/schema/function diagnostics |
| AC-10 | 提供基础 compatibility diff，能识别 port schema、config schema、function signature 的 breaking change |

## 11. 风险与建议处理

| 风险 | 建议 |
| --- | --- |
| 第一版 schema 过度绑定 visual canvas | artifact 命名去掉 `visual`，但兼容 visual library 字段 |
| operator 类型系统过早做大 | Phase 1 只标准化核心字段，复杂类型系统后移 |
| Java 反射导出不可靠 | 显式 schema provider 优先，reflection 只做 fallback |
| function metadata 与 evaluator 分离 | 函数注册必须携带 descriptor；测试保证 registry 与 evaluator 一致 |
| 兼容性判断争议大 | 先做 conservative diff；不确定变化标成 `review-required` |
| 安全元数据缺失 | 先覆盖 side effect、idempotency、requiresSecrets、network/external call，再逐步扩展 |

## 12. 推荐下一步

1. BLOGE 团队先确认 artifact 命名：是否采用 `bloge.operatorLibrary.v1`，以及是否保留 `bloge.visualOperatorLibrary.v1` 作为兼容 alias。
2. 从 expression function registry 开始，因为它当前最明显是手工影子目录，收益高、范围小。
3. 同步定义 `BlogeOperatorSchemaProvider`，让新 operator 可以主动声明 schema。
4. 将 `resource-gateway-examples` 当前 `BuiltinOperatorLibraryExporter` 作为原型参考，但不要直接搬进 framework；应抽象为不绑定 Spring/resource-gateway 的 exporter。
5. 以 `/author/` 画布作为首个消费方验证：一旦框架 artifact 可导出，画布应减少 example-local schema projection 和 manual function catalog。
