# Resource Gateway 汇报原生 SVG

本目录中的五张图是 `resource-gateway-technical-architecture-briefing.html` 的图形源文件：

- `resource-gateway-runtime.svg`：业务资产治理、稳定执行主链与证据回流。
- `dsl-asset-production.svg`：IntentSpec、编码智能体、确定性编译与治理发布。
- `testability-control-evidence.svg`：测试控制面、隔离执行面与证据治理面。
- `intent-driven-operations-workflow.svg`：意图驱动的运营变更闭环。
- `asset-delivery-collaboration.svg`：角色、交付产物与诊断闭环。

图形使用原生 SVG 元素与 `<text>`，不包含 Draw.io 元数据、`foreignObject`、内嵌图片或 Base64 资源。修改源文件后，在仓库根目录执行：

```bash
node docs/assets/briefing-diagrams/embed-native-diagrams.mjs
node /Users/jtsuser/.codex/skills/build-corporate-architecture-html/scripts/check-html.mjs \
  docs/resource-gateway-technical-architecture-briefing.html
```

如需按当前叙事模板重新生成全部章节，再嵌入最新图形，可先执行：

```bash
node docs/assets/briefing-diagrams/refresh-corporate-briefing.mjs
```

语义色保持统一：蓝色表示业务执行主流，青绿色表示编码智能体与 DSL 资产生产，琥珀色表示业务判断与控制，绿色表示证据和可发布结果，红色表示失败反馈与硬边界。
