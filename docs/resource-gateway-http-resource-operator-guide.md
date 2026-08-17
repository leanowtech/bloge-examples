# `HttpResourceOperator` 用法与适用场景

> 面向业务集成开发者。本文以 `resource-gateway-examples` 当前实现为准。

## 1. 先看结论

`HttpResourceOperator` 是一个通用的 HTTP 资源算子。业务集成开发者不需要为每个上游 API 编写一个 Java 算子，而是为上游接口登记一个 `ResourceDescriptor`，再通过稳定的 `resourceId` 调用它。

算子负责以下工作：

- 从 `ResourceRegistry` 解析 `ResourceDescriptor`。
- 使用 BLOGE 表达式把业务输入映射到路径参数、查询参数、请求头、Cookie 和请求体。
- 渲染 URL、合并请求头、选择鉴权方式和超时。
- 调用底层 `HttpRequestOperator`。
- 按 `ResponseProtocol` 判断上游业务是否成功。
- 按 `payloadPath` 或响应表达式提取业务 payload。

适合使用 `HttpResourceOperator` 的典型条件是：接口采用 HTTP 请求-响应模式，接口差异主要体现在 URL、方法、参数、鉴权和响应格式，并且可以通过配置描述清楚。

## 2. 组件关系

| 组件 | 作用 | 业务集成开发者需要关心的内容 |
| --- | --- | --- |
| `HttpResourceOperator` | 通用运行时算子，名称为 `httpResource` | 调用输入和运行行为 |
| `ResourceDescriptor` | 一个上游 HTTP 接口的完整描述 | URL、方法、鉴权、超时、参数映射、响应协议 |
| `ResourceRegistry` | 按 `resourceId` 查找描述 | 资源注册、更新和发布 |
| `ParameterMapping` | 将输入参数映射到 HTTP 请求 | BLOGE 表达式和目标位置 |
| `ResponseProtocol` | 定义响应成功条件 | HTTP 状态、响应码、成功标记或自定义表达式 |
| `HttpResourceInput` | 算子输入 | `resourceId`、`params` 和单次调用覆盖项 |
| `HttpResourceOutput` | 算子输出 | 状态码、提取后的 payload、原始响应体和耗时 |

一个资源由逻辑名称标识，例如 `customer-service.getProfile`。调用方只依赖这个名称，不直接依赖 URL。这样可以在不修改业务图或调用方的情况下切换环境地址、服务版本或供应商实现。

相关实现：[`HttpResourceOperator`](../resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/operator/HttpResourceOperator.java)、[`ResourceDescriptor`](../resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/resource/ResourceDescriptor.java)、[`ParameterMapping`](../resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/resource/ParameterMapping.java)。

## 3. 一次调用的执行顺序

一次 `httpResource` 调用按以下顺序执行：

1. 使用输入中的 `resourceId` 查找资源描述。找不到时抛出 `ResourceNotFoundException`。
2. 构造表达式上下文。请求参数位于 `ctx.params`，租户和命名空间分别位于 `ctx.tenantId`、`ctx.namespace`。
3. 计算 `ParameterMapping` 中的路径、查询、请求头、Cookie 和请求体表达式。
4. 将路径参数替换到 `urlTemplate`，并对查询参数进行 URL 编码。
5. 按「描述默认请求头 → 动态请求头 → 单次调用覆盖」的顺序合并请求头。
6. 注入 `X-Tenant-Id` 和 `X-Namespace`。这两个请求头由运行上下文决定，覆盖前面同名值。
7. 根据请求头中的 `Content-Type` 对表单请求体进行编码。
8. 选择超时：单次调用的 `timeoutOverride` 优先，否则使用描述中的 `defaultTimeout`；最终值还会受到图执行剩余预算限制。
9. 选择鉴权：单次调用的 `authOverride` 优先，否则使用描述中的 `authStrategy`。
10. 调用底层 HTTP 算子，并使用 `ResponseProtocol` 校验响应。
11. 校验成功后提取 payload，返回 `HttpResourceOutput`。

注意：算子自身声明的幂等性为 `UNKNOWN`，副作用类型为 `EXTERNAL_CALL`。图上的 `retry`、`fallback` 等策略属于图运行时策略，不应在没有确认接口幂等性的情况下用于外部写操作。

在示例网关中，资源调用还可以接入网关级拦截器，例如租户限流、供应商熔断和响应缓存。是否启用这些能力由网关配置和拦截器装配决定，不属于 `ResourceDescriptor` 本身的字段。

## 4. 资源描述如何配置

### 4.1 Java 注册示例

下面的资源描述一个读取客户资料的接口：

```java
var descriptor = new ResourceDescriptor(
        "customer-service.getProfile",
        "https://customer.example.com/api/customers/{customerId}",
        "GET",
        Map.of("Accept", "application/json"),
        null,
        Duration.ofSeconds(5),
        new ParameterMapping(
                Map.of("customerId", "ctx.params.customerId"),
                Map.of("locale", "ctx.params.locale"),
                Map.of("X-Correlation-Id", "ctx.params.correlationId"),
                Map.of("SESSION", "ctx.params.sessionId"),
                null
        ),
        new ResponseProtocol.BodyCode(
                "code",
                Set.of(0, "0", "SUCCESS"),
                "message"
        ),
        "data"
);

writableResourceRegistry.register(descriptor);
```

示例中：

- `customerId` 替换 URL 中的 `{customerId}`。
- `locale` 变为查询参数 `locale`。
- `correlationId` 变为请求头 `X-Correlation-Id`。
- `sessionId` 变为 Cookie `SESSION`。
- 响应体中的 `code` 为 `0`、`"0"` 或 `"SUCCESS"` 时视为成功。
- 成功响应的业务数据从 JSON 的 `data` 字段提取。

生产环境可以通过配置管理或资源管理 API 保存相同的描述。项目示例提供资源管理接口，基础路径为 `/admin/resources`。资源描述应在运行前完成注册和校验，表达式无法编译时应修复配置，而不是等待运行时失败。

### 4.2 `ResourceDescriptor` 字段

| 字段 | 说明 | 默认或限制 |
| --- | --- | --- |
| `resourceId` | 资源的逻辑标识 | 必填，必须唯一且非空 |
| `urlTemplate` | 上游 URL，可包含 `{placeholder}` 路径占位符 | 必填 |
| `method` | HTTP 方法 | 必填，运行时转为大写 |
| `defaultHeaders` | 每次调用默认发送的请求头 | 可为空 |
| `authStrategy` | 默认鉴权策略 | 支持 Bearer、Basic、API key；可为空 |
| `defaultTimeout` | 默认超时 | 未配置时为 `30s` |
| `parameterMapping` | 输入到 HTTP 请求的映射 | 未配置时为空映射 |
| `responseProtocol` | 响应成功判定规则 | 未配置时按 HTTP 状态判定 |
| `payloadPath` | JSON 点号路径 | 为空时提取完整 JSON 响应体 |
| `externalWriteContract` | 外部写操作的幂等、回执和对账协议 | 仅写操作需要 |

路径占位符会直接替换，当前 `UrlTemplateRenderer` 不会对路径值再次编码。路径值中如果可能出现斜杠、空格或其他 URL 保留字符，应在资源输入约束中明确处理方式。

## 5. 参数映射

`ParameterMapping` 包含五类映射：

| 映射 | 目标位置 | 示例 | `null` 行为 |
| --- | --- | --- | --- |
| `pathExpressions` | URL 路径占位符 | `"customerId" -> "ctx.params.customerId"` | 会得到空字符串，可能生成不完整路径 |
| `queryExpressions` | URL 查询参数 | `"page" -> "ctx.params.page"` | 不追加该参数 |
| `headerExpressions` | HTTP 请求头 | `"X-Request-Id" -> "ctx.params.requestId"` | 不写入该请求头 |
| `cookieExpressions` | Cookie | `"SESSION" -> "ctx.params.sessionId"` | 不写入该 Cookie |
| `bodyExpression` | HTTP 请求体 | `"ctx.params.body"` | 不发送请求体 |

表达式上下文示例：

```text
ctx.params.customerId
ctx.params["X-Correlation-Id"]
ctx.params.page ?? 1
ctx.tenantId
ctx.namespace
```

查询参数使用 UTF-8 URL 编码；Cookie 值也会编码。请求头表达式和 Cookie 表达式只在表达式结果不为 `null` 时写入请求。

### 请求体编码

当前实现对以下请求体进行特殊处理：

- `application/x-www-form-urlencoded`：`Map` 被编码为表单键值对；`Iterable` 值会重复生成同名键。
- `multipart/form-data`：`Map` 被编码为文本表单字段；未提供 boundary 时自动生成。
- 其他类型：交由底层 `HttpRequestOperator` 处理。

当前 `multipart/form-data` 逻辑面向文本字段，不提供文件名、媒体类型和二进制文件内容的专用抽象。文件上传、分段上传或复杂签名请求应评估专用适配器或专用算子。

## 6. 调用方式

### 6.1 在 BLOGE 图中调用

业务图通常直接使用 `httpResource`，只需要绑定 `resourceId` 和业务参数：

```bloge
graph customerProfile {
  node fetchProfile : httpResource {
    input {
      resourceId = "customer-service.getProfile"
      params = {
        customerId: ctx.customerId,
        locale: ctx.locale
      }
    }
    timeout = 3s
    retry = { attempts: 1, backoff: 200ms }
  }
}
```

如果通过可视化编排界面使用资源，资源通常以 `resource:<resourceId>` 形式作为虚拟算子展示，运行时再降低为 `httpResource`。业务图仍然依赖资源描述，而不是硬编码 URL。

### 6.2 Java 直接调用

```java
var input = new HttpResourceInput(
        "customer-service.getProfile",
        Map.of(
                "customerId", "customer-123",
                "locale", "zh-CN"
        )
);

HttpResourceOutput output = httpResourceOperator.execute(input, operatorContext);
Object customer = output.payload();
```

`execute(Object, OperatorContext)` 也接受 DSL 风格的 `Map` 输入。最小输入形状如下：

```json
{
  "resourceId": "customer-service.getProfile",
  "params": {
    "customerId": "customer-123"
  }
}
```

### 6.3 通过统一 HTTP 执行接口调用

Resource Gateway 示例提供 `POST /api/gateway/resources/execute`，调用方可以不直接依赖 Java 类型：

```bash
curl -X POST http://localhost:8080/api/gateway/resources/execute \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-Id: demo-tenant' \
  -H 'X-Namespace: customer' \
  -d '{
    "resourceId": "customer-service.getProfile",
    "params": {
      "customerId": "customer-123",
      "locale": "zh-CN",
      "correlationId": "req-42"
    },
    "headerOverrides": {
      "X-Request-Source": "customer-portal"
    },
    "timeoutOverride": "PT2S"
  }'
```

统一接口返回的 `data` 是完整的 `HttpResourceOutput`，而不是只有 `payload`：

```json
{
  "success": true,
  "data": {
    "resourceId": "customer-service.getProfile",
    "statusCode": 200,
    "payload": {
      "customerId": "customer-123"
    },
    "rawBody": "{\"code\":0,\"data\":{\"customerId\":\"customer-123\"}}",
    "duration": "PT0.015S",
    "success": true
  },
  "error": null,
  "elapsedMs": 19
}
```

统一接口支持以下单次调用字段：

| 字段 | 说明 |
| --- | --- |
| `resourceId` | 必填，资源逻辑标识 |
| `params` | 提供给参数映射表达式的业务参数 |
| `headerOverrides` | 覆盖资源默认请求头和动态请求头 |
| `authOverride` | 覆盖资源默认鉴权，可用 `bearer`、`basic`、`apiKey` |
| `timeoutOverride` | ISO-8601 时长，例如 `PT2S` |

使用 Map 形式传入鉴权覆盖时，支持以下结构：

```json
{ "type": "bearer", "token": "token-from-secret-provider" }
```

```json
{ "type": "basic", "username": "service-user", "password": "password-from-secret-provider" }
```

```json
{ "type": "apiKey", "headerName": "X-Api-Key", "key": "key-from-secret-provider" }
```

示例中的 token、password 和 key 仅表示输入结构，不应写入源代码、资源描述持久化内容或日志。

请求头 `Authorization` 可以由统一执行接口的入站 `Authorization` 头传入；如果请求体提供 `headerOverrides.Authorization`，请求体值优先。使用 `authOverride` 时，算子选择单次鉴权对象，而不是资源描述中的默认鉴权策略。

## 7. 响应判定与 payload 提取

### 7.1 选择 `ResponseProtocol`

| 协议 | 成功条件 | 适用接口 |
| --- | --- | --- |
| `HttpStatus` | 底层 HTTP 响应为成功状态 | 遵循 HTTP 语义的 REST API |
| `BodyCode` | JSON 中的 `codePath` 命中 `successValues` | HTTP 总是 `200`、业务码在响应体中的接口 |
| `BodyFlag` | JSON 中的布尔字段为 `true` | 返回 `success: true/false` 的接口 |
| `StatusCodes` | HTTP 状态码命中显式集合 | 需要把 `204`、`206` 等非默认状态纳入成功的接口 |
| `BlgeExpression` | 自定义 BLOGE 表达式结果为真 | 结构化协议无法表达的边界情况 |

`BodyCode` 支持点号路径，例如 `result.code`。数值型成功值支持数值等价判断，也会兼容常见的数字与字符串形式，例如 `0` 和 `"0"`。

`BlgeExpression` 的表达式上下文如下：

```text
ctx.statusCode
ctx.headers
ctx.body
```

示例：

```java
new ResponseProtocol.BlgeExpression(
        "ctx.statusCode == 200 && ctx.body.score != null",
        "ctx.body.message",
        "ctx.body"
)
```

### 7.2 提取 payload

普通响应使用 `payloadPath` 从 JSON 中提取数据：

```text
payloadPath = "data"
payloadPath = "result.items"
payloadPath = "result.items.0"
```

`payloadPath` 为空时，算子返回完整的已解析 JSON。路径不存在时，提取结果为 `null`。如果响应体不是合法 JSON，payload 提取会失败；因此当前实现不适合直接承载纯文本或二进制响应。

当 `responseProtocol` 是 `BlgeExpression` 且配置了 `payloadExpr` 时，`payloadExpr` 优先于 `payloadPath`，并使用 `ctx.statusCode`、`ctx.headers` 和已解析的 `ctx.body` 计算结果。

## 8. 外部写操作的特殊要求

`GET`、`HEAD` 和 `OPTIONS` 被视为非写操作。其他 HTTP 方法，包括 `POST`、`PUT` 和 `DELETE`，都会被视为跨越外部写边界的操作。

外部写操作必须配置符合要求的 `externalWriteContract`。没有该协议，算子会在发送 HTTP 请求前拒绝执行。协议至少需要声明：

- 幂等键从哪个输入参数读取，以及写入哪个请求头。
- 对账查询引用从哪个输入参数读取，以及使用哪个 reconciler。
- 成功回执从哪个响应头读取。
- provider 标识。

调用时，`params` 必须提供协议声明的幂等键和对账查询引用，例如：

```json
{
  "resourceId": "orders.create",
  "params": {
    "order": {
      "orderId": "order-42"
    },
    "idempotencyKey": "order-42-create-v1",
    "reconciliationLookupRef": "vault://commands/order-42"
  }
}
```

对账查询引用需要是可保存为证据的 URI 形式。当前实现限制引用长度不超过 `1024` 个字符，并拒绝包含 `?`、`#` 或 `@` 的值。引用中不要放入访问令牌、密码或业务响应正文。

上游成功响应必须返回协议声明的提交回执头。成功响应缺少回执时，系统会将提交状态视为 `UNKNOWN_COMMIT`；调用方不应直接盲目重试，而应先使用对账引用确认上游状态。

外部写操作的最小配置示意：

```java
new ResourceDescriptor.ExternalWriteContract(
        ResourceDescriptor.ExternalWriteContract.SCHEMA_VERSION,
        "idempotencyKey",
        "Idempotency-Key",
        "reconciliationLookupRef",
        "orders.status",
        "X-Commit-Receipt",
        "X-Transaction-Id",
        "orders",
        "X-Proof-Ref",
        "X-Proof-Fingerprint",
        false
)
```

## 9. 适用场景与不适用场景

### 适用场景

| 场景 | 使用方式 |
| --- | --- |
| 聚合多个业务系统的查询接口 | 为每个接口登记描述，在 BLOGE 图中并行调用，再由 transform 组装统一结果 |
| 同一业务能力有多个供应商 | 为供应商分别登记资源，通过分支、降级或路由选择不同 `resourceId` |
| 上游 API 只在 URL、参数和响应包裹层面有差异 | 使用 `ParameterMapping` 和 `ResponseProtocol` 配置差异，不新增 Java 算子 |
| 需要统一租户、命名空间、鉴权和超时 | 使用算子统一注入和选择机制，调用方只传业务参数 |
| 需要把已有 HTTP API 接入可视化编排 | 使用 `resource:<resourceId>` 虚拟算子，运行时降低到 `httpResource` |
| 需要对读接口配置重试或 fallback | 在 BLOGE 节点上声明图运行时策略，并根据接口幂等性设置参数 |

### 不适用或需要专用适配的场景

- SSE、WebSocket 或其他长连接、流式协议。当前算子是请求-响应模型，流式场景应使用对应的 streaming operator。
- 非 HTTP 协议，例如原生消息队列、数据库协议或本地 SDK 调用。
- 纯文本、二进制或文件下载响应。当前 payload 提取器按 JSON 解析响应体。
- 文件上传、分段上传、复杂签名、动态分页状态机等需要专用协议的接口。
- 没有幂等键、对账查询和提交回执的外部写接口。该类接口不能直接通过当前 `HttpResourceOperator` 放行。

## 10. 错误处理与排查

| 现象 | 常见原因 | 处理建议 |
| --- | --- | --- |
| `Resource descriptor not found` | `resourceId` 未注册、拼写错误或环境未加载资源 | 先检查资源注册表，再检查调用输入 |
| 启动阶段出现 `ResourceDescriptorException` | 参数映射或 `BlgeExpression` 无法编译 | 修复表达式；不要把配置错误延后到请求运行时 |
| `ResourceCallException` | HTTP 状态失败、业务码失败、成功标记为 `false` 或自定义表达式为假 | 对照真实响应体重新选择 `ResponseProtocol`，检查 `codePath`、`messagePath` 和成功值 |
| JSON 解析失败 | 响应体不是合法 JSON，但算子仍尝试提取 payload | 确认上游返回格式；纯文本或二进制接口改用专用适配 |
| `externalWriteContract` 相关拒绝 | 写操作没有配置符合要求的外部写协议 | 补齐幂等、对账引用和回执配置，或不要将该接口声明为写资源 |
| 成功响应后出现 `commit receipt` 错误 | 上游没有返回协议声明的提交回执 | 先按对账引用查询上游，不要直接重试写请求 |
| 请求超时 | 描述默认超时过短、单次覆盖过短或图剩余预算不足 | 检查 `defaultTimeout`、`timeoutOverride` 和图执行预算 |
| 上游收到的请求头不是预期值 | 单次 `headerOverrides` 覆盖了描述值，或租户头由运行上下文重新注入 | 检查三层合并顺序；确认 `X-Tenant-Id`、`X-Namespace` 的来源 |

公共执行接口的状态码语义如下：未知 `resourceId` 返回 `404`；上游执行失败或响应校验失败返回 `502`。直接调用 Java 算子时，应按异常类型处理，不要只根据 `HttpResourceOutput.success` 判断，因为失败响应通常不会返回成功输出对象。

## 11. 集成检查清单

接入一个新的上游接口前，建议完成以下检查：

- 为接口选择稳定且有业务含义的 `resourceId`，不要把环境地址写进 `resourceId`。
- 确认 `urlTemplate` 中的每个路径占位符都有对应的 `pathExpressions`。
- 确认请求参数的类型、空值行为和 URL 编码要求。
- 确认默认请求头、动态请求头和单次覆盖的边界。
- 确认鉴权凭据不会写入日志、测试 fixture 或业务 payload。
- 根据真实成功和失败响应选择 `ResponseProtocol`，不要默认把 HTTP `200` 当作业务成功。
- 配置 `payloadPath` 或 `payloadExpr`，并验证返回 payload 的结构。
- 对读接口确认是否允许重试和 fallback；对写接口先完成 `externalWriteContract` 评审。
- 使用 WireMock 或等效测试桩覆盖成功、业务失败、HTTP 失败、超时和异常响应体。
- 对公共接口评估是否需要隐藏 `rawBody`，因为原始响应体可能包含敏感信息。

## 12. 相关入口

- [`HttpResourceOperator.java`](../resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/operator/HttpResourceOperator.java)
- [`HttpResourceInput.java`](../resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/operator/HttpResourceInput.java)
- [`HttpResourceOutput.java`](../resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/operator/HttpResourceOutput.java)
- [`ResourceDescriptor.java`](../resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/resource/ResourceDescriptor.java)
- [`ParameterMapping.java`](../resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/resource/ParameterMapping.java)
- [`ResponseProtocol.java`](../resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/resource/ResponseProtocol.java)
- [`resource-dispatch.bloge`](../resource-gateway-examples/src/main/resources/bloge/gateway/resource-dispatch.bloge)
- [`HttpResourceOperatorTest.java`](../resource-gateway-examples/src/test/java/com/leanowtech/bloge/gateway/operator/HttpResourceOperatorTest.java)
