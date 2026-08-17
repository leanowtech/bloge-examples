import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  AlertTriangle,
  ArrowRight,
  BriefcaseBusiness,
  ChevronDown,
  Clock3,
  Database,
  FileText,
  Filter,
  GitBranch,
  LayoutDashboard,
  ListFilter,
  RefreshCw,
  Search,
  ShieldCheck,
  Sparkles,
  Wrench,
} from 'lucide-react';

import { useI18n } from '../i18n/I18nProvider';
import { fetchCapabilityStudioDemoPack, type CapabilityStudioFetcher } from './api';
import {
  isCapabilityStudioProtocolError,
  localized,
  type CapabilityAssetSummary,
  type CapabilityStudioModel,
  type ContractSummary,
  type ScenarioRow,
} from './domain';
import './capabilityStudio.css';

type Task = 'overview' | 'contract' | 'scenarios' | 'feature' | 'tool';

export interface CapabilityStudioProps {
  fetcher?: CapabilityStudioFetcher;
}

export default function CapabilityStudio({ fetcher }: CapabilityStudioProps) {
  const { locale } = useI18n();
  const [model, setModel] = useState<CapabilityStudioModel | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [loading, setLoading] = useState(true);
  const [task, setTask] = useState<Task>('overview');
  const [selectedApiIndex, setSelectedApiIndex] = useState(0);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setModel(await fetchCapabilityStudioDemoPack(fetcher));
    } catch (nextError) {
      setModel(null);
      setError(nextError instanceof Error ? nextError : new Error('The demo pack could not be loaded.'));
    } finally {
      setLoading(false);
    }
  }, [fetcher]);

  useEffect(() => { void load(); }, [load]);

  const text = useCallback((value: Parameters<typeof localized>[0]) => localized(value, locale), [locale]);

  if (loading) {
    return <main className="capability-studio capability-studio-state" aria-busy="true">{locale === 'zh-CN' ? '正在加载能力资产...' : 'Loading capability assets...'}</main>;
  }

  if (error || !model) {
    return <LoadError error={error} locale={locale} onRetry={() => void load()} />;
  }

  const selectedApi = model.assets.apis[selectedApiIndex] ?? model.assets.apis[0];
  const selectedFeature = model.assets.features[0];
  const selectedTool = model.assets.tools[0];
  const currentAsset = task === 'contract' ? selectedApi : task === 'feature' ? selectedFeature : task === 'tool' ? selectedTool : undefined;
  const openApi = (index: number) => {
    setSelectedApiIndex(index);
    setTask('contract');
  };

  return (
    <main className="capability-studio" data-testid="capability-studio">
      <header className="capability-studio-heading">
        <div>
          <p className="capability-eyebrow"><Sparkles size={15} aria-hidden="true" /> {locale === 'zh-CN' ? '阶段 0 · 只读能力资产' : 'Stage 0 · Read-only capability assets'}</p>
          <h2>{text(model.capability.name)}</h2>
          <p className="capability-summary">{text(model.capability.summary)}</p>
        </div>
        <div className="capability-heading-meta">
          <span className="capability-status"><Clock3 size={15} aria-hidden="true" /> {displayProtocolStatus(text(model.capability.readiness), locale)}</span>
          <span>{locale === 'zh-CN' ? '负责人' : 'Owner'} · {text(model.capability.owner)}</span>
        </div>
      </header>

      <div className="capability-mobile-task-switcher">
        <label htmlFor="capability-task-select">{locale === 'zh-CN' ? '当前任务' : 'Current task'}</label>
        <select id="capability-task-select" value={task} onChange={(event) => setTask(event.target.value as Task)}>
          <option value="overview">{locale === 'zh-CN' ? '能力总览' : 'Capability overview'}</option>
          <option value="contract">{locale === 'zh-CN' ? '订单查询契约' : 'Order lookup contract'}</option>
          <option value="scenarios">{locale === 'zh-CN' ? '场景数据' : 'Scenario data'}</option>
          <option value="feature">{locale === 'zh-CN' ? '特征编排' : 'Feature orchestration'}</option>
          <option value="tool">{locale === 'zh-CN' ? '工具契约' : 'Tool contract'}</option>
        </select>
      </div>

      <div className="capability-layout">
        <aside className="capability-sidebar" aria-label={locale === 'zh-CN' ? '能力资产任务导航' : 'Capability asset task navigation'}>
          <div className="capability-sidebar-heading"><BriefcaseBusiness size={17} aria-hidden="true" /><span>{locale === 'zh-CN' ? '能力资产' : 'Capability assets'}</span></div>
          <TaskButton active={task === 'overview'} icon={<LayoutDashboard size={16} />} label={locale === 'zh-CN' ? '能力总览' : 'Overview'} onClick={() => setTask('overview')} />
          <div className="capability-sidebar-group-label">{locale === 'zh-CN' ? '可复用接口' : 'Reusable APIs'} <span>{model.assets.apis.length}</span></div>
          {model.assets.apis.map((asset, index) => <TaskButton key={asset.technicalRef ?? index} active={task === 'contract' && index === selectedApiIndex} icon={<FileText size={16} />} label={text(asset.name)} onClick={() => openApi(index)} />)}
          <div className="capability-sidebar-group-label">{locale === 'zh-CN' ? '业务能力' : 'Business assets'} <span>2</span></div>
          {model.assets.features.map((asset, index) => <TaskButton key={asset.technicalRef ?? index} active={task === 'feature'} icon={<GitBranch size={16} />} label={text(asset.name)} onClick={() => setTask('feature')} />)}
          {model.assets.tools.map((asset, index) => <TaskButton key={asset.technicalRef ?? index} active={task === 'tool'} icon={<Wrench size={16} />} label={text(asset.name)} onClick={() => setTask('tool')} />)}
          <TaskButton active={task === 'scenarios'} icon={<Database size={16} />} label={locale === 'zh-CN' ? '场景数据' : 'Scenario data'} onClick={() => setTask('scenarios')} badge={model.scenarios.length} />
        </aside>

        <section className="capability-main" aria-live="polite">
          {task === 'overview' && <OverviewView model={model} text={text} locale={locale} onOpenContract={openApi} onOpenScenarios={() => setTask('scenarios')} />}
          {task === 'contract' && currentAsset && <ContractView asset={currentAsset} text={text} locale={locale} />}
          {task === 'scenarios' && <ScenarioView scenarios={model.scenarios} text={text} locale={locale} />}
          {task === 'feature' && selectedFeature && <StageOneView asset={selectedFeature} text={text} locale={locale} kind="feature" />}
          {task === 'tool' && selectedTool && <StageOneView asset={selectedTool} text={text} locale={locale} kind="tool" />}
        </section>

        <ReadinessPanel model={model} text={text} locale={locale} task={task} onNextAction={() => task === 'overview' ? openApi(0) : setTask('scenarios')} />
      </div>
    </main>
  );
}

function TaskButton({ active, icon, label, onClick, badge }: { active: boolean; icon: React.ReactNode; label: string; onClick: () => void; badge?: number }) {
  return <button type="button" className={`capability-task-button${active ? ' active' : ''}`} aria-current={active ? 'step' : undefined} onClick={onClick}>
    {icon}<span>{label}</span>{badge !== undefined && <strong>{badge}</strong>}
  </button>;
}

function OverviewView({ model, text, locale, onOpenContract, onOpenScenarios }: { model: CapabilityStudioModel; text: (value: Parameters<typeof localized>[0]) => string; locale: 'en' | 'zh-CN'; onOpenContract: (index: number) => void; onOpenScenarios: () => void }) {
  return <div className="capability-view" data-testid="capability-overview">
    <div className="capability-view-heading"><div><p className="capability-kicker">GP-01</p><h3>{text(model.capability.name)}</h3><p>{text(model.capability.summary)}</p></div><span className="capability-readonly">{locale === 'zh-CN' ? '只读' : 'READ-ONLY'}</span></div>
    <div className="capability-count-strip" aria-label={locale === 'zh-CN' ? '能力资产数量' : 'Capability inventory counts'}>
      <CountTile icon={<FileText size={18} />} count={model.assets.apis.length} label={locale === 'zh-CN' ? '接口' : 'API'} />
      <CountTile icon={<GitBranch size={18} />} count={model.assets.features.length} label={locale === 'zh-CN' ? '特征' : 'Feature'} />
      <CountTile icon={<Wrench size={18} />} count={model.assets.tools.length} label={locale === 'zh-CN' ? '工具' : 'Tool'} />
      <CountTile icon={<Database size={18} />} count={model.scenarios.length} label={locale === 'zh-CN' ? '场景' : 'Scenario'} />
    </div>
    <div className="capability-overview-grid">
      <section className="capability-section capability-asset-list"><SectionTitle icon={<FileText size={17} />} title={locale === 'zh-CN' ? '业务接口契约' : 'API contracts'} subtitle={locale === 'zh-CN' ? '先看业务输入、结果和失败边界' : 'Business-facing inputs and outcomes'} />{model.assets.apis.map((asset, index) => <AssetRow key={asset.technicalRef ?? index} asset={asset} text={text} locale={locale} onClick={() => onOpenContract(index)} />)}</section>
      <section className="capability-section"><SectionTitle icon={<GitBranch size={17} />} title={locale === 'zh-CN' ? '业务特征与工具' : 'Feature and Tool'} subtitle={locale === 'zh-CN' ? '设计事实与运行证据分开呈现' : 'Runtime evidence is deliberately separate'} />{model.assets.features.map((asset, index) => <AssetRow key={asset.technicalRef ?? index} asset={asset} text={text} locale={locale} />)}{model.assets.tools.map((asset, index) => <AssetRow key={asset.technicalRef ?? index} asset={asset} text={text} locale={locale} />)}</section>
    </div>
    <section className="capability-section capability-branch-section"><SectionTitle icon={<GitBranch size={17} />} title={locale === 'zh-CN' ? '两条安全工作线' : 'Two safe working lines'} subtitle={locale === 'zh-CN' ? '标准基线用于对照，教程分支用于受控探索。' : 'The baseline is the reference; the tutorial branch is exploratory.'} /><div className="capability-branch-grid"><BranchRow branch={model.baseline} text={text} locale={locale} /><BranchRow branch={model.tutorialBranch} text={text} locale={locale} /></div></section>
    <div className="capability-next-action"><div><strong>{locale === 'zh-CN' ? '建议下一步' : 'Next action'}</strong><span>{locale === 'zh-CN' ? '先确认业务契约，让每条场景都有稳定边界。' : 'Start with the business contract so every scenario has a stable boundary.'}</span></div><button className="capability-primary-action" type="button" onClick={() => onOpenContract(0)}>{locale === 'zh-CN' ? '查看订单查询契约' : 'Review order lookup contract'} <ArrowRight size={16} aria-hidden="true" /></button><button type="button" className="capability-secondary-action" onClick={onOpenScenarios}><ListFilter size={16} aria-hidden="true" /> {locale === 'zh-CN' ? '浏览场景' : 'Browse scenarios'}</button></div>
  </div>;
}

function CountTile({ icon, count, label }: { icon: React.ReactNode; count: number; label: string }) {
  return <div className="capability-count-tile"><span>{icon}</span><strong>{count}</strong><small>{label}</small></div>;
}

function SectionTitle({ icon, title, subtitle }: { icon: React.ReactNode; title: string; subtitle: string }) {
  return <div className="capability-section-title"><span className="capability-section-icon">{icon}</span><div><h4>{title}</h4><p>{subtitle}</p></div></div>;
}

function AssetRow({ asset, text, locale, onClick }: { asset: CapabilityAssetSummary; text: (value: Parameters<typeof localized>[0]) => string; locale: 'en' | 'zh-CN'; onClick?: () => void }) {
  const content = <><span className="capability-asset-kind">{displayAssetKind(asset.kind, locale)}</span><div><strong>{text(asset.name)}</strong><p>{text(asset.summary)}</p></div><span className="capability-asset-readiness">{displayProtocolStatus(text(asset.readiness), locale)}</span>{onClick && <ArrowRight size={16} aria-hidden="true" />}</>;
  return onClick ? <button type="button" className="capability-asset-row" onClick={onClick}>{content}</button> : <div className="capability-asset-row">{content}</div>;
}

function BranchRow({ branch, text, locale }: { branch: CapabilityStudioModel['baseline']; text: (value: Parameters<typeof localized>[0]) => string; locale: 'en' | 'zh-CN' }) {
  const projection = displayBranch(branch, text, locale);
  return <div className="capability-branch-row"><div><strong>{projection.name}</strong><p>{projection.purpose}</p></div><span>{displayProtocolStatus(text(branch.status), locale)}</span></div>;
}

function ContractView({ asset, text, locale }: { asset: CapabilityAssetSummary; text: (value: Parameters<typeof localized>[0]) => string; locale: 'en' | 'zh-CN' }) {
  const contract = asset.contract;
  return <div className="capability-view" data-testid="capability-contract">
    <ViewHeading kicker="GP-02" title={text(asset.name)} description={text(asset.summary)} status={displayProtocolStatus(text(asset.readiness), locale)} />
    {contract ? <>
      <div className="capability-contract-grid"><ContractFields title={locale === 'zh-CN' ? '输入信息' : 'Inputs'} fields={contract.inputs} text={text} locale={locale} /><ContractFields title={locale === 'zh-CN' ? '成功结果' : 'Success result'} fields={contract.successResult} text={text} locale={locale} /></div>
      <div className="capability-contract-grid"><ContractErrors title={locale === 'zh-CN' ? '可预期错误' : 'Expected errors'} contract={contract} text={text} locale={locale} /><InfoList title={locale === 'zh-CN' ? '副作用' : 'Side effects'} values={contract.sideEffects} text={text} locale={locale} /></div>
      <section className="capability-section capability-ownership-grid"><InfoItem label={locale === 'zh-CN' ? '负责人' : 'Owner'} value={text(contract.owner)} /><InfoItem label="SLA" value={text(contract.sla)} /><InfoItem label={locale === 'zh-CN' ? '数据敏感度' : 'Sensitivity'} value={text(contract.sensitivity)} /></section>
    </> : <EmptyEvidence locale={locale} />}
    <TechnicalDetails asset={asset} locale={locale} />
  </div>;
}

function ContractFields({ title, fields, text, locale }: { title: string; fields: ContractSummary['inputs']; text: (value: Parameters<typeof localized>[0]) => string; locale: 'en' | 'zh-CN' }) {
  return <section className="capability-section"><SectionTitle icon={<FileText size={17} />} title={title} subtitle={locale === 'zh-CN' ? '优先展示业务含义' : 'Business meaning is shown first'} /><div className="capability-field-list">{fields.map((field, index) => <div className="capability-field-row" key={`${text(field.name)}-${index}`}><strong>{text(field.name)}</strong><span>{text(field.type)}</span><small>{field.required ? (locale === 'zh-CN' ? '必填' : 'Required') : (locale === 'zh-CN' ? '可选' : 'Optional')}{field.description ? ` · ${text(field.description)}` : ''}</small></div>)}</div></section>;
}

function ContractErrors({ title, contract, text, locale }: { title: string; contract: ContractSummary; text: (value: Parameters<typeof localized>[0]) => string; locale: 'en' | 'zh-CN' }) {
  return <section className="capability-section"><SectionTitle icon={<AlertTriangle size={17} />} title={title} subtitle={locale === 'zh-CN' ? '失败也是契约的一部分' : 'Failure is part of the contract.'} /><div className="capability-field-list">{contract.errors.map((error, index) => <div className="capability-field-row" key={`${text(error.code)}-${index}`}><strong>{text(error.code)}</strong><span>{text(error.meaning)}</span>{error.retryable !== undefined && <small>{error.retryable ? (locale === 'zh-CN' ? '可重试' : 'Retryable') : (locale === 'zh-CN' ? '不可重试' : 'Not retryable')}</small>}</div>)}</div></section>;
}

function InfoList({ title, values, text, locale }: { title: string; values: ScenarioRow['source'][]; text: (value: Parameters<typeof localized>[0]) => string; locale: 'en' | 'zh-CN' }) {
  return <section className="capability-section"><SectionTitle icon={<ShieldCheck size={17} />} title={title} subtitle={locale === 'zh-CN' ? '声明的影响始终对评审者可见' : 'Declared effects remain visible to reviewers.'} /><ul className="capability-value-list">{values.map((value, index) => <li key={`${text(value)}-${index}`}>{text(value)}</li>)}</ul></section>;
}

function InfoItem({ label, value }: { label: string; value: string }) { return <div><small>{label}</small><strong>{value}</strong></div>; }

function TechnicalDetails({ asset, locale }: { asset: CapabilityAssetSummary; locale: 'en' | 'zh-CN' }) {
  const missing = locale === 'zh-CN' ? '未提供' : 'Not supplied';
  return <details className="capability-technical-details"><summary><ChevronDown size={15} aria-hidden="true" /> {locale === 'zh-CN' ? '技术引用（按需展开）' : 'Technical references (expand when needed)'}</summary><dl><div><dt>Ref</dt><dd>{asset.technicalRef ?? missing}</dd></div><div><dt>Fingerprint</dt><dd>{asset.fingerprint ?? missing}</dd></div></dl></details>;
}

function ScenarioView({ scenarios, text, locale }: { scenarios: ScenarioRow[]; text: (value: Parameters<typeof localized>[0]) => string; locale: 'en' | 'zh-CN' }) {
  const [query, setQuery] = useState('');
  const [category, setCategory] = useState('ALL');
  const [lifecycle, setLifecycle] = useState('ALL');
  const categories = useMemo(() => [...new Set(scenarios.map((scenario) => displayScenarioValue(text(scenario.category), locale)))], [locale, scenarios, text]);
  const lifecycles = useMemo(() => [...new Set(scenarios.map((scenario) => displayScenarioValue(text(scenario.lifecycle), locale)))], [locale, scenarios, text]);
  const visible = scenarios.filter((scenario) => {
    const displayedCategory = displayScenarioValue(text(scenario.category), locale);
    const displayedLifecycle = displayScenarioValue(text(scenario.lifecycle), locale);
    const haystack = [scenario.name, scenario.source, scenario.owner, scenario.oracle, scenario.expectedResult].map(text).concat(displayedCategory, displayedLifecycle).join(' ').toLowerCase();
    return haystack.includes(query.toLowerCase()) && (category === 'ALL' || displayedCategory === category) && (lifecycle === 'ALL' || displayedLifecycle === lifecycle);
  });
  return <div className="capability-view" data-testid="capability-scenarios"><ViewHeading kicker="GP-03" title={locale === 'zh-CN' ? '场景数据' : 'Scenario data'} description={locale === 'zh-CN' ? '九条业务场景共同构成当前验证分母。这里维护业务预期，不展示模拟桩的原始数据。' : 'Nine business scenarios form the current validation denominator. Business expectations are visible without exposing raw fixture payloads.'} status={`${visible.length}/${scenarios.length}`} />
    <div className="capability-filter-bar"><label className="capability-search"><Search size={16} aria-hidden="true" /><span className="sr-only">{locale === 'zh-CN' ? '搜索场景' : 'Search scenarios'}</span><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder={locale === 'zh-CN' ? '搜索业务场景、负责人或预期结果' : 'Search business scenario, owner, or expected result'} /></label><label><Filter size={15} aria-hidden="true" /><span className="sr-only">{locale === 'zh-CN' ? '分类' : 'Category'}</span><select value={category} onChange={(event) => setCategory(event.target.value)}><option value="ALL">{locale === 'zh-CN' ? '全部分类' : 'All categories'}</option>{categories.map((value) => <option key={value}>{value}</option>)}</select></label><label><Clock3 size={15} aria-hidden="true" /><span className="sr-only">{locale === 'zh-CN' ? '生命周期' : 'Lifecycle'}</span><select value={lifecycle} onChange={(event) => setLifecycle(event.target.value)}><option value="ALL">{locale === 'zh-CN' ? '全部状态' : 'All lifecycle states'}</option>{lifecycles.map((value) => <option key={value}>{value}</option>)}</select></label></div>
    <div className="capability-table-wrap"><table className="capability-scenario-table"><thead><tr><th>{locale === 'zh-CN' ? '业务场景' : 'Business scenario'}</th><th>{locale === 'zh-CN' ? '分类' : 'Category'}</th><th>{locale === 'zh-CN' ? '来源' : 'Source'}</th><th>{locale === 'zh-CN' ? '负责人' : 'Owner'}</th><th>{locale === 'zh-CN' ? '正确性依据' : 'Oracle'}</th><th>{locale === 'zh-CN' ? '契约数' : 'Contracts'}</th><th>{locale === 'zh-CN' ? '预期结果' : 'Expected result'}</th><th>{locale === 'zh-CN' ? '质量 / 生命周期' : 'Quality / lifecycle'}</th></tr></thead><tbody>{visible.map((scenario, index) => <ScenarioTableRow key={scenario.technicalRef ?? `${text(scenario.name)}-${index}`} scenario={scenario} text={text} locale={locale} />)}</tbody></table></div>
  </div>;
}

function ScenarioTableRow({ scenario, text, locale }: { scenario: ScenarioRow; text: (value: Parameters<typeof localized>[0]) => string; locale: 'en' | 'zh-CN' }) {
  return <tr><th scope="row">{text(scenario.name)}</th><td>{displayScenarioValue(text(scenario.category), locale)}</td><td>{text(scenario.source)}</td><td>{text(scenario.owner)}</td><td>{text(scenario.oracle)}</td><td>{scenario.contractCount}</td><td>{text(scenario.expectedResult)}</td><td><span>{displayScenarioValue(text(scenario.quality), locale)}</span><small>{displayScenarioValue(text(scenario.lifecycle), locale)}</small></td></tr>;
}

function StageOneView({ asset, text, locale, kind }: { asset: CapabilityAssetSummary; text: (value: Parameters<typeof localized>[0]) => string; locale: 'en' | 'zh-CN'; kind: 'feature' | 'tool' }) {
  return <div className="capability-view" data-testid={`capability-${kind}`}><ViewHeading kicker={displayAssetKind(kind === 'feature' ? 'FEATURE' : 'TOOL', locale)} title={text(asset.name)} description={text(asset.summary)} status={displayProtocolStatus(text(asset.readiness), locale)} /><section className="capability-section"><SectionTitle icon={kind === 'feature' ? <GitBranch size={17} /> : <Wrench size={17} />} title={locale === 'zh-CN' ? '业务摘要' : 'Business summary'} subtitle={locale === 'zh-CN' ? '这里只展示服务端已经提供的事实。' : 'Only server-provided facts are shown here.'} /><p className="capability-large-copy">{text(asset.summary)}</p></section><section className="capability-stage-one-notice"><AlertTriangle size={18} aria-hidden="true" /><div><strong>{locale === 'zh-CN' ? '阶段 1：运行证据尚未接入' : 'Stage 1: runtime evidence is not connected'}</strong><p>{locale === 'zh-CN' ? '运行证据和结果尚未生成；本页面不会把设计摘要误报为验证通过。' : 'Runtime evidence and results have not been generated; design summaries are not reported as verified.'}</p></div><span>{locale === 'zh-CN' ? '未运行' : 'NOT RUN'}</span></section><TechnicalDetails asset={asset} locale={locale} /></div>;
}

function ViewHeading({ kicker, title, description, status }: { kicker: string; title: string; description: string; status: string }) { return <div className="capability-view-heading"><div><p className="capability-kicker">{kicker}</p><h3>{title}</h3><p>{description}</p></div><span className="capability-readonly">{status}</span></div>; }

function ReadinessPanel({ model, text, locale, task, onNextAction }: { model: CapabilityStudioModel; text: (value: Parameters<typeof localized>[0]) => string; locale: 'en' | 'zh-CN'; task: Task; onNextAction: () => void }) {
  const missing = locale === 'zh-CN' ? '未提供' : 'Not supplied';
  const baseline = displayBranch(model.baseline, text, locale);
  const tutorial = displayBranch(model.tutorialBranch, text, locale);
  return <aside className="capability-readiness-panel"><div className="capability-panel-heading"><ShieldCheck size={17} aria-hidden="true" /><h3>{locale === 'zh-CN' ? '验收与就绪' : 'Acceptance and readiness'}</h3></div><div className="capability-readiness-callout capability-readiness-warning"><AlertTriangle size={18} aria-hidden="true" /><div><strong>{displayProtocolStatus(text(model.acceptanceStatus), locale)}</strong><p>{locale === 'zh-CN' ? '设计资产已加载；运行证据尚未建立。' : 'Design assets are loaded; runtime evidence is not established.'}</p></div></div><dl className="capability-readiness-list"><div><dt>{locale === 'zh-CN' ? '当前基线' : 'Canonical baseline'}</dt><dd>{baseline.name}</dd></div><div><dt>{locale === 'zh-CN' ? '教程分支' : 'Tutorial branch'}</dt><dd>{tutorial.name}</dd></div><div><dt>{locale === 'zh-CN' ? '场景分母' : 'Scenario denominator'}</dt><dd>{model.scenarios.length} {locale === 'zh-CN' ? '条' : 'scenarios'}</dd></div><div><dt>{locale === 'zh-CN' ? '运行证据' : 'Runtime evidence'}</dt><dd className="capability-not-run">{locale === 'zh-CN' ? '未运行' : 'NOT RUN'}</dd></div></dl><div className="capability-next-panel"><small>{locale === 'zh-CN' ? '下一步' : 'Next action'}</small><strong>{task === 'overview' ? (locale === 'zh-CN' ? '先确认订单查询契约' : 'Review the order lookup contract') : (locale === 'zh-CN' ? '浏览九条场景数据' : 'Review the nine scenarios')}</strong><button type="button" onClick={onNextAction}>{locale === 'zh-CN' ? '继续' : 'Continue'} <ArrowRight size={15} aria-hidden="true" /></button></div><details className="capability-technical-details capability-panel-details"><summary><ChevronDown size={15} aria-hidden="true" /> {locale === 'zh-CN' ? '资产技术引用' : 'Asset technical references'}</summary><dl><div><dt>Capability ref</dt><dd>{model.capability.technicalRef ?? missing}</dd></div><div><dt>Protocol</dt><dd>{model.protocolVersion ?? missing}</dd></div><div><dt>Fingerprint</dt><dd>{model.capability.fingerprint ?? missing}</dd></div></dl></details></aside>;
}

function displayAssetKind(kind: CapabilityAssetSummary['kind'], locale: 'en' | 'zh-CN'): string {
  if (locale === 'en') return kind === 'API' ? 'API' : kind === 'FEATURE' ? 'FEATURE' : 'TOOL';
  return kind === 'API' ? '接口' : kind === 'FEATURE' ? '特征' : '工具';
}

function displayProtocolStatus(status: string, locale: 'en' | 'zh-CN'): string {
  const translations: Record<string, { en: string; 'zh-CN': string }> = {
    NO_GO: { en: 'Not ready for acceptance', 'zh-CN': '暂不可验收' },
    METADATA_READY_RUNTIME_EVIDENCE_PENDING: { en: 'Design ready, runtime evidence pending', 'zh-CN': '设计已就绪，待补运行证据' },
    CONTRACT_READY_MOCK_PENDING: { en: 'Contract ready, simulation data pending', 'zh-CN': '契约已就绪，待补模拟数据' },
    DAG_CONTRACT_READY_RUNTIME_PENDING: { en: 'Orchestration contract ready, runtime evidence pending', 'zh-CN': '编排契约已就绪，待补运行证据' },
    CONTRACT_READY_RUNTIME_PENDING: { en: 'Contract ready, runtime evidence pending', 'zh-CN': '契约已就绪，待补运行证据' },
    ACCEPTED: { en: 'Accepted', 'zh-CN': '已验收' },
    APPROVED: { en: 'Approved', 'zh-CN': '已批准' },
    NOT_RUN: { en: 'Not run', 'zh-CN': '未运行' },
    IMMUTABLE: { en: 'Immutable', 'zh-CN': '不可变' },
    MUTABLE: { en: 'Mutable', 'zh-CN': '可修改' },
    ISOLATED_NOT_RUN: { en: 'Isolated, not run', 'zh-CN': '已隔离，未运行' },
  };
  return translations[status]?.[locale] ?? status;
}

function displayBranch(branch: CapabilityStudioModel['baseline'], text: (value: Parameters<typeof localized>[0]) => string, locale: 'en' | 'zh-CN'): { name: string; purpose: string } {
  const name = text(branch.name);
  const purpose = text(branch.purpose);
  if (locale === 'en') return { name, purpose };
  if (name === 'Canonical Baseline') {
    return { name: '当前标准基线', purpose: '用于可重复评审的不可变精确引用基线。' };
  }
  if (name === 'Tutorial Branch') {
    return { name: '教程分支', purpose: '用于受控演练补偿历史查询超时的隔离分支。' };
  }
  return { name, purpose };
}

function displayScenarioValue(value: string, locale: 'en' | 'zh-CN'): string {
  const translations: Record<string, { en: string; 'zh-CN': string }> = {
    GOLDEN: { en: 'Golden', 'zh-CN': '黄金场景' },
    NEGATIVE: { en: 'Negative', 'zh-CN': '反向场景' },
    BOUNDARY: { en: 'Boundary', 'zh-CN': '边界场景' },
    REGRESSION: { en: 'Regression', 'zh-CN': '回归场景' },
    SECURITY: { en: 'Security', 'zh-CN': '安全场景' },
    ACTIVE: { en: 'Active', 'zh-CN': '使用中' },
    DESIGNED_NOT_RUN: { en: 'Designed, not run', 'zh-CN': '已设计，未运行' },
  };
  return translations[value]?.[locale] ?? value;
}

function EmptyEvidence({ locale }: { locale: 'en' | 'zh-CN' }) { return <section className="capability-stage-one-notice"><AlertTriangle size={18} aria-hidden="true" /><div><strong>{locale === 'zh-CN' ? '契约详情未提供' : 'Contract details were not provided'}</strong><p>{locale === 'zh-CN' ? '服务端没有返回足够的业务契约信息，不能用前端猜测补齐。' : 'The server did not provide enough business contract detail; the frontend will not guess.'}</p></div></section>; }

function LoadError({ error, locale, onRetry }: { error: Error | null; locale: 'en' | 'zh-CN'; onRetry: () => void }) {
  const protocolCode = isCapabilityStudioProtocolError(error) ? error.code : 'RG.CAPABILITY_STUDIO.DEMO_PACK_UNAVAILABLE';
  const message = isCapabilityStudioProtocolError(error) ? error.message : (error?.message ?? 'The demo pack could not be loaded.');
  const impact = isCapabilityStudioProtocolError(error) ? error.impact : (locale === 'zh-CN' ? '能力总览、契约和场景数据暂时无法展示。' : 'The capability overview, contract, and scenario data cannot be shown yet.');
  return <main className="capability-studio capability-studio-state capability-error-state" data-testid="capability-load-error"><div className="capability-error-icon"><AlertTriangle size={23} aria-hidden="true" /></div><p className="capability-kicker">{protocolCode}</p><h2>{locale === 'zh-CN' ? '能力设计数据暂时不可用' : 'Capability Studio data is unavailable'}</h2><div className="capability-error-grid"><div><strong>{locale === 'zh-CN' ? '发生了什么' : 'What happened'}</strong><p>{message}</p></div><div><strong>{locale === 'zh-CN' ? '影响' : 'Impact'}</strong><p>{impact}</p></div><div><strong>{locale === 'zh-CN' ? '如何继续' : 'How to continue'}</strong><p>{locale === 'zh-CN' ? '确认服务端已发布 demo pack 后重试。' : 'Confirm that the server publishes the demo pack, then retry.'}</p></div></div><button type="button" className="capability-primary-action" onClick={onRetry}><RefreshCw size={16} aria-hidden="true" /> {locale === 'zh-CN' ? '重试加载' : 'Retry loading'}</button><details className="capability-technical-details"><summary><ChevronDown size={15} aria-hidden="true" /> {locale === 'zh-CN' ? '技术详情' : 'Technical details'}</summary><p>{error?.message ?? 'No additional details.'}</p></details></main>;
}
