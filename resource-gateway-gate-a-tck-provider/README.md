# Gate A TCK Provider

该模块是 Capability Studio Gate A 的 `A1.2 TCK_PROVIDER` 薄 Provider 制品。它不是 Resource Gateway 服务，也不持有企业 Evidence、KMS、Owner 或发布权限。

## 构建

先将 A1.1 Candidate 安装到本地 Maven 仓库：

```bash
mvn -f resource-gateway-test-kit/pom.xml \
  -Pgate-a-candidate -Dgate.a.slice=A1.1 clean install
```

再执行 Provider 的洁净验证：

```bash
mvn -f resource-gateway-gate-a-tck-provider/pom.xml \
  -Pgate-a-provider -Dgate.a.slice=A1.2 clean verify
```

成功构建必须依次得到三个 `PASS`：依赖锁生成、精确归档校验和真实双 JAR 黑盒挑战。最终 JAR 位于：

```text
resource-gateway-gate-a-tck-provider/target/resource-gateway-gate-a-tck-provider-1.0.0.jar
```

## 制品边界

最终 JAR 只允许五个非目录 entry：

```text
META-INF/MANIFEST.MF
META-INF/gate-a/manifests/dependencies.json
META-INF/maven/com.leanowtech.bloge/resource-gateway-gate-a-tck-provider/pom.properties
META-INF/services/com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider
com/leanowtech/bloge/gatetckprovider/GateATckProvider.class
```

`src/build` 下的生成器和验证器只在构建期运行，不能进入 Provider JAR。Provider 对不属于自身的 Evidence Resolver、Issuer Policy 和 Owner Authority 始终失败关闭。

## 验证含义

`BUILD SUCCESS` 只表示 A1.2 开发协议闭环通过，不代表正式发布准入。企业 Candidate/Environment Attestation、Target Binding、外部 Evidence Store/KMS、目标环境固定矩阵和 Owner 签署仍由外部 Authority 提供，仓库不得代签或模拟为正式事实。
