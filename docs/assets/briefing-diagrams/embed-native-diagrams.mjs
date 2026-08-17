import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const htmlPath = resolve(scriptDirectory, '../../resource-gateway-technical-architecture-briefing.html');
const nativeDirectory = join(scriptDirectory, 'native');
const readSvg = (name) => readFileSync(join(nativeDirectory, name), 'utf8').trim();

const operations = readSvg('intent-driven-operations-workflow.svg');
const delivery = readSvg('asset-delivery-collaboration.svg');
const runtime = readSvg('resource-gateway-runtime.svg');
const authoring = readSvg('dsl-asset-production.svg');
const evidence = readSvg('testability-control-evidence.svg');

const gallery = `      <!-- BEGIN INLINE NATIVE ARCHITECTURE GALLERY -->
      <div class="architecture-gallery fade-in">
        <div class="architecture-tabs" role="tablist" aria-label="Resource Gateway 技术架构视图">
          <button class="architecture-tab" id="architecture-tab-runtime" type="button" role="tab" aria-selected="true" aria-controls="architecture-panel-runtime" data-architecture="runtime">运行架构</button>
          <button class="architecture-tab" id="architecture-tab-authoring" type="button" role="tab" aria-selected="false" aria-controls="architecture-panel-authoring" data-architecture="authoring" tabindex="-1">资产生产</button>
          <button class="architecture-tab" id="architecture-tab-evidence" type="button" role="tab" aria-selected="false" aria-controls="architecture-panel-evidence" data-architecture="evidence" tabindex="-1">测试与证据</button>
        </div>

        <article class="architecture-panel diagram-shell" id="architecture-panel-runtime" role="tabpanel" aria-labelledby="architecture-tab-runtime" data-architecture-panel="runtime">
          <div class="architecture-brief">
            <div><small>架构判断</small><strong>稳定执行内核如何承接持续变化的业务资产？</strong></div>
            <div><small>图上结论</small><span>DSL、Registry、Scenario 与发布版本在带外治理；业务请求沿 Gateway → Graph Engine → 通用 HttpResourceOperator 执行；Trace 与 Evidence 回到发布门禁。</span></div>
          </div>
          <div class="diagram-toolbar"><span>先看中部蓝色执行主链，再看上方资产控制与下方证据回流。</span><div class="diagram-scale-group" aria-label="图形缩放"><button class="diagram-scale" type="button" data-diagram-scale="fit" aria-pressed="false">适应宽度</button><button class="diagram-scale" type="button" data-diagram-scale="actual" aria-pressed="true">1:1 阅读</button></div></div>
          <div class="architecture-viewport" data-scale="actual">
${runtime}
          </div>
        </article>

        <article class="architecture-panel diagram-shell" id="architecture-panel-authoring" role="tabpanel" aria-labelledby="architecture-tab-authoring" data-architecture-panel="authoring" hidden>
          <div class="architecture-brief">
            <div><small>架构判断</small><strong>运营工作如何从繁杂配置升级为意图与验证？</strong></div>
            <div><small>图上结论</small><span>运营定义 IntentSpec 与业务 Oracle；编码智能体复用工程生态生成可审查 ChangeSet；确定性编译、隔离验证和人工风险门共同形成可发布业务资产。</span></div>
          </div>
          <div class="diagram-toolbar"><span>从左向右阅读四个责任区；红色回路将验证失败定位回具体资产。</span><div class="diagram-scale-group" aria-label="图形缩放"><button class="diagram-scale" type="button" data-diagram-scale="fit" aria-pressed="false">适应宽度</button><button class="diagram-scale" type="button" data-diagram-scale="actual" aria-pressed="true">1:1 阅读</button></div></div>
          <div class="architecture-viewport" data-scale="actual">
${authoring}
          </div>
        </article>

        <article class="architecture-panel diagram-shell" id="architecture-panel-evidence" role="tabpanel" aria-labelledby="architecture-tab-evidence" data-architecture-panel="evidence" hidden>
          <div class="architecture-brief">
            <div><small>架构判断</small><strong>如何证明业务资产的组合语义与晋级条件正确？</strong></div>
            <div><small>图上结论</small><span>测试控制、隔离执行和证据验证三面分离；GraphContext 不承载测试控制；生产依赖硬拒绝；独立 Test Kit 可复核签名 Evidence。</span></div>
          </div>
          <div class="diagram-toolbar"><span>从计划、执行到证据逐列阅读；底部蓝色与红色条带表示两条不可破坏的边界。</span><div class="diagram-scale-group" aria-label="图形缩放"><button class="diagram-scale" type="button" data-diagram-scale="fit" aria-pressed="false">适应宽度</button><button class="diagram-scale" type="button" data-diagram-scale="actual" aria-pressed="true">1:1 阅读</button></div></div>
          <div class="architecture-viewport" data-scale="actual">
${evidence}
          </div>
        </article>
      </div>
      <!-- END INLINE NATIVE ARCHITECTURE GALLERY -->
`;

let html = readFileSync(htmlPath, 'utf8');

const replaceDiagram = (marker, legacyClass, svg) => {
  const marked = new RegExp(`<!-- BEGIN INLINE NATIVE ${marker} -->[\\s\\S]*?<!-- END INLINE NATIVE ${marker} -->`, 'u');
  const next = `<!-- BEGIN INLINE NATIVE ${marker} -->\n${svg}\n<!-- END INLINE NATIVE ${marker} -->`;
  if (marked.test(html)) {
    html = html.replace(marked, next);
    return;
  }
  const legacy = new RegExp(`<svg class="drawio-inline-svg ${legacyClass}"[\\s\\S]*?<\\/svg>`, 'u');
  if (!legacy.test(html)) throw new Error(`Missing diagram target: ${marker}`);
  html = html.replace(legacy, next);
};

replaceDiagram('OPERATIONS', 'drawio-operations', operations);
replaceDiagram('DELIVERY', 'drawio-delivery', delivery);

const markedGallery = /      <!-- BEGIN INLINE NATIVE ARCHITECTURE GALLERY -->[\s\S]*?      <!-- END INLINE NATIVE ARCHITECTURE GALLERY -->\n/u;
if (markedGallery.test(html)) {
  html = html.replace(markedGallery, gallery);
} else {
  const legacyGallery = /      <div class="architecture-gallery fade-in">[\s\S]*?(?=      <div class="callout evidence fade-in" style="margin-top:18px"><strong>技术边界：)/u;
  if (!legacyGallery.test(html)) throw new Error('Missing architecture gallery target');
  html = html.replace(legacyGallery, gallery);
}

html = html.replace(/      <div class="diagram-shell fade-in legacy-blueprint"[\s\S]*?(?=      <div class="grid-3 fade-in" style="margin-top:18px">)/u, '');
html = html
  .replaceAll('diagram-shell drawio-report-diagram fade-in', 'diagram-shell native-report-diagram fade-in')
  .replaceAll('diagram-viewport drawio-viewport', 'diagram-viewport native-svg-viewport')
  .replace('五个技术视图说明资产如何生产、执行、验证与晋级', '三个技术视图把业务资产、稳定执行与正确性证明连成一体')
  .replace('从目标架构下钻到 DSL 资产创作、确定性编译、执行数据控制和正确性证据。每张图对应仓库中的真实模块与契约，并保留可编辑 Draw.io 源图。', '从运行架构、DSL 资产生产和测试证据三个视角看清同一套边界。图形全部使用原生 SVG 绘制，文字、连线与语义色在浏览器中直接渲染。')
  .replace('横向滑动查看业务、编码智能体、平台与质量治理的完整协作泳道', '横向滑动查看业务资产交付的角色、产物与诊断闭环')
  .replace('业务、编码智能体、平台与质量治理协作泳道', '业务资产交付协作模型');

writeFileSync(htmlPath, html);
