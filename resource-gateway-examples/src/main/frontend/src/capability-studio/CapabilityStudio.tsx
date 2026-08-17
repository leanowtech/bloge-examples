import { useCallback, useEffect, useState } from 'react';
import {
  AlertTriangle,
  ArrowRight,
  Beaker,
  BriefcaseBusiness,
  CheckCircle2,
  ChevronDown,
  Clock3,
  Database,
  FileText,
  Filter,
  GitBranch,
  LayoutDashboard,
  ListFilter,
  RefreshCw,
  Save,
  Search,
  ShieldCheck,
  Sparkles,
  Wrench,
} from 'lucide-react';

import { useI18n } from '../i18n/I18nProvider';
import {
  CapabilityStudioRequestError,
  fetchCapabilityStudioDemoPack,
  fetchScenarioDataset,
  fetchTutorialBranch,
  preflightTutorialBranch,
  saveTutorialBehavior,
  type CapabilityStudioFetcher,
  type TutorialBranchPreflight,
  type TutorialBranchProjection,
} from './api';
import {
  isCapabilityStudioProtocolError,
  localized,
  type CapabilityAssetSummary,
  type CapabilityStudioModel,
  type ContractSummary,
  type ScenarioCase,
  type ScenarioDataset,
  type ScenarioRow,
} from './domain';
import './capabilityStudio.css';

type Task = 'overview' | 'contract' | 'scenarios' | 'tutorial' | 'feature' | 'tool';

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
          <p className="capability-eyebrow"><Sparkles size={15} aria-hidden="true" /> {locale === 'zh-CN' ? '阶段 1 · 可编辑教程分支' : 'Stage 1 · Editable tutorial branch'}</p>
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
          <option value="tutorial">{locale === 'zh-CN' ? '隔离演练配置' : 'Isolated rehearsal setup'}</option>
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
          <TaskButton active={task === 'scenarios'} icon={<Database size={16} />} label={locale === 'zh-CN' ? '场景数据' : 'Scenario data'} onClick={() => setTask('scenarios')} badge={model.scenarios.length} testId="capability-task-scenarios" />
          <TaskButton active={task === 'tutorial'} icon={<Beaker size={16} />} label={locale === 'zh-CN' ? '隔离演练配置' : 'Isolated rehearsal setup'} onClick={() => setTask('tutorial')} testId="capability-task-tutorial" />
        </aside>

        <section className="capability-main" aria-live="polite">
          {task === 'overview' && <OverviewView model={model} text={text} locale={locale} onOpenContract={openApi} onOpenScenarios={() => setTask('scenarios')} onOpenTutorial={() => setTask('tutorial')} />}
          {task === 'contract' && currentAsset && <ContractView asset={currentAsset} text={text} locale={locale} />}
          {task === 'scenarios' && <ScenarioView fetcher={fetcher} locale={locale} />}
          {task === 'tutorial' && <TutorialBranchView fetcher={fetcher} locale={locale} />}
          {task === 'feature' && selectedFeature && <StageOneView asset={selectedFeature} text={text} locale={locale} kind="feature" />}
          {task === 'tool' && selectedTool && <StageOneView asset={selectedTool} text={text} locale={locale} kind="tool" />}
        </section>

        <ReadinessPanel model={model} text={text} locale={locale} task={task} onNextAction={() => task === 'overview' ? openApi(0) : setTask('scenarios')} />
      </div>
    </main>
  );
}

function TaskButton({ active, icon, label, onClick, badge, testId }: { active: boolean; icon: React.ReactNode; label: string; onClick: () => void; badge?: number; testId?: string }) {
  return <button type="button" className={`capability-task-button${active ? ' active' : ''}`} aria-current={active ? 'step' : undefined} data-testid={testId} onClick={onClick}>
    {icon}<span>{label}</span>{badge !== undefined && <strong>{badge}</strong>}
  </button>;
}

function OverviewView({ model, text, locale, onOpenContract, onOpenScenarios, onOpenTutorial }: { model: CapabilityStudioModel; text: (value: Parameters<typeof localized>[0]) => string; locale: 'en' | 'zh-CN'; onOpenContract: (index: number) => void; onOpenScenarios: () => void; onOpenTutorial: () => void }) {
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
    <section className="capability-section capability-branch-section"><SectionTitle icon={<GitBranch size={17} />} title={locale === 'zh-CN' ? '两条安全工作线' : 'Two safe working lines'} subtitle={locale === 'zh-CN' ? '标准基线用于对照，教程分支用于受控探索。' : 'The baseline is the reference; the tutorial branch is exploratory.'} /><div className="capability-branch-grid"><BranchRow branch={model.baseline} text={text} locale={locale} /><BranchRow branch={model.tutorialBranch} text={text} locale={locale} onClick={onOpenTutorial} /></div></section>
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

function BranchRow({ branch, text, locale, onClick }: { branch: CapabilityStudioModel['baseline']; text: (value: Parameters<typeof localized>[0]) => string; locale: 'en' | 'zh-CN'; onClick?: () => void }) {
  const projection = displayBranch(branch, text, locale);
  const content = <><div><strong>{projection.name}</strong><p>{projection.purpose}</p></div><span>{displayProtocolStatus(text(branch.status), locale)}</span>{onClick && <ArrowRight size={16} aria-hidden="true" />}</>;
  return onClick
    ? <button type="button" className="capability-branch-row capability-branch-action" onClick={onClick}>{content}</button>
    : <div className="capability-branch-row">{content}</div>;
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

function ScenarioView({ fetcher, locale }: { fetcher?: CapabilityStudioFetcher; locale: 'en' | 'zh-CN' }) {
  const [dataset, setDataset] = useState<ScenarioDataset | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState('');
  const [category, setCategory] = useState('ALL');
  const [lifecycle, setLifecycle] = useState('ALL');
  const [selectedCaseRef, setSelectedCaseRef] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const nextDataset = await fetchScenarioDataset(fetcher);
      setDataset(nextDataset);
      setSelectedCaseRef(nextDataset.cases[0]?.caseRef.id ?? '');
    } catch (nextError) {
      setDataset(null);
      setError(nextError instanceof Error ? nextError : new Error('The scenario dataset could not be loaded.'));
    } finally {
      setLoading(false);
    }
  }, [fetcher]);

  useEffect(() => { void load(); }, [load]);

  if (loading) {
    return <div className="capability-view capability-scenario-state" data-testid="capability-scenario-loading" aria-busy="true"><ViewHeading kicker="GP-03" title={locale === 'zh-CN' ? '场景数据' : 'Scenario data'} description={locale === 'zh-CN' ? '正在加载受治理的 Scenario Dataset…' : 'Loading the governed scenario dataset...'} status={locale === 'zh-CN' ? '加载中' : 'Loading'} /><p className="capability-inline-state">{locale === 'zh-CN' ? '正在读取业务场景与质量摘要。' : 'Reading business cases and quality summary.'}</p></div>;
  }

  if (error || !dataset) return <ScenarioDatasetError error={error} locale={locale} onRetry={() => void load()} />;

  const categories = [...new Set(dataset.cases.map((scenario) => scenario.category))];
  const lifecycles = [...new Set(dataset.cases.map((scenario) => scenario.lifecycle))];
  const visible = dataset.cases.filter((scenario) => {
    const haystack = [
      scenario.name,
      scenario.businessIntent,
      scenario.source?.displayName,
      scenario.owner?.name,
      scenario.oracle?.displayName,
      scenario.oracle?.summary,
      ...scenario.behaviorProfiles.map((profile) => profile.summary),
      scenario.category,
      scenario.lifecycle,
    ].filter(Boolean).join(' ').toLowerCase();
    return haystack.includes(query.toLowerCase())
      && (category === 'ALL' || scenario.category === category)
      && (lifecycle === 'ALL' || scenario.lifecycle === lifecycle);
  });
  const selected = visible.find((scenario) => scenario.caseRef.id === selectedCaseRef) ?? visible[0];

  return <div className="capability-view" data-testid="capability-scenarios">
    <ViewHeading kicker="GP-03" title={locale === 'zh-CN' ? '场景数据中心' : 'Scenario data center'} description={dataset.description} status={`${visible.length}/${dataset.cases.length}`} />
    <section className="capability-scenario-dataset-header" aria-label={locale === 'zh-CN' ? '数据集摘要' : 'Dataset summary'}>
      <div className="capability-scenario-dataset-title"><Database size={19} aria-hidden="true" /><div><strong>{dataset.name}</strong><span>{locale === 'zh-CN' ? '业务验证分母' : 'Business validation denominator'} · {dataset.cases.length} {locale === 'zh-CN' ? '条 case' : 'cases'}</span></div></div>
      <dl className="capability-scenario-metadata"><div><dt>{locale === 'zh-CN' ? '生命周期' : 'Lifecycle'}</dt><dd>{displayScenarioValue(dataset.lifecycle, locale)}</dd></div><div><dt>{locale === 'zh-CN' ? '版本' : 'Revision'}</dt><dd>{dataset.datasetRef.revision}</dd></div><div><dt>{locale === 'zh-CN' ? '分类' : 'Classification'}</dt><dd>{displayScenarioValue(dataset.classification, locale)}</dd></div><div><dt>{locale === 'zh-CN' ? '负责人' : 'Owner'}</dt><dd>{dataset.owner.name}</dd></div></dl>
    </section>
    <section className="capability-scenario-quality" aria-label={locale === 'zh-CN' ? '质量摘要' : 'Quality summary'}><div className="capability-scenario-quality-heading"><ShieldCheck size={17} aria-hidden="true" /><strong>{locale === 'zh-CN' ? '质量摘要' : 'Quality summary'}</strong><span className={`capability-quality-status capability-quality-${dataset.quality.status.toLowerCase()}`}>{displayScenarioValue(dataset.quality.status, locale)}</span></div><div className="capability-scenario-quality-grid"><QualityMetric label={locale === 'zh-CN' ? 'Owner 覆盖' : 'Owner coverage'} value={dataset.quality.ownerCoveragePercent} /><QualityMetric label={locale === 'zh-CN' ? '来源覆盖' : 'Source coverage'} value={dataset.quality.sourceCoveragePercent} /><QualityMetric label={locale === 'zh-CN' ? 'Oracle 覆盖' : 'Oracle coverage'} value={dataset.quality.oracleCoveragePercent} /><QualityMetric label={locale === 'zh-CN' ? '契约覆盖' : 'Contract coverage'} value={dataset.quality.contractCoveragePercent} /><QualityMetric label={locale === 'zh-CN' ? '行为闭包' : 'Behavior closure'} value={dataset.quality.behaviorClosurePercent} /></div></section>
    <div className="capability-filter-bar"><label className="capability-search"><Search size={16} aria-hidden="true" /><span className="sr-only">{locale === 'zh-CN' ? '搜索场景' : 'Search scenarios'}</span><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder={locale === 'zh-CN' ? '搜索业务场景、负责人或预期结果' : 'Search business scenario, owner, or expected result'} /></label><label><Filter size={15} aria-hidden="true" /><span className="sr-only">{locale === 'zh-CN' ? '分类' : 'Category'}</span><select aria-label={locale === 'zh-CN' ? '分类' : 'Category'} value={category} onChange={(event) => setCategory(event.target.value)}><option value="ALL">{locale === 'zh-CN' ? '全部分类' : 'All categories'}</option>{categories.map((value) => <option key={value} value={value}>{displayScenarioValue(value, locale)}</option>)}</select></label><label><Clock3 size={15} aria-hidden="true" /><span className="sr-only">{locale === 'zh-CN' ? '生命周期' : 'Lifecycle'}</span><select aria-label={locale === 'zh-CN' ? '生命周期' : 'Lifecycle'} value={lifecycle} onChange={(event) => setLifecycle(event.target.value)}><option value="ALL">{locale === 'zh-CN' ? '全部状态' : 'All lifecycle states'}</option>{lifecycles.map((value) => <option key={value} value={value}>{displayScenarioValue(value, locale)}</option>)}</select></label></div>
    {visible.length === 0 ? <ScenarioEmptyState locale={locale} onClear={() => { setQuery(''); setCategory('ALL'); setLifecycle('ALL'); }} /> : <div className="capability-scenario-master-detail"><div className="capability-scenario-list" aria-label={locale === 'zh-CN' ? '场景列表' : 'Scenario list'}>{visible.map((scenario) => <ScenarioListItem key={scenario.caseRef.id} scenario={scenario} selected={scenario.caseRef.id === selected?.caseRef.id} locale={locale} onClick={() => setSelectedCaseRef(scenario.caseRef.id)} />)}</div>{selected && <ScenarioDetails scenario={selected} locale={locale} />}</div>}
  </div>;
}

function ScenarioListItem({ scenario, selected, locale, onClick }: { scenario: ScenarioCase; selected: boolean; locale: 'en' | 'zh-CN'; onClick: () => void }) {
  return <button type="button" className={`capability-scenario-list-item${selected ? ' selected' : ''}`} aria-pressed={selected} onClick={onClick}><span className="capability-scenario-list-item-heading"><strong>{scenario.name}</strong><span>{displayScenarioValue(scenario.category, locale)}</span></span><span className="capability-scenario-list-item-meta">{displayScenarioValue(scenario.lifecycle, locale)} · {displayScenarioValue(scenario.qualityState, locale)}</span></button>;
}

function ScenarioDetails({ scenario, locale }: { scenario: ScenarioCase; locale: 'en' | 'zh-CN' }) {
  const missing = locale === 'zh-CN' ? '未声明' : 'Not declared';
  return <article className="capability-scenario-details" data-testid="capability-scenario-details"><div className="capability-scenario-details-heading"><div><p className="capability-kicker">{displayScenarioValue(scenario.category, locale)}</p><h4>{scenario.name}</h4></div><span className={`capability-quality-status capability-quality-${scenario.qualityState.toLowerCase()}`}>{displayScenarioValue(scenario.qualityState, locale)}</span></div><dl className="capability-scenario-detail-grid"><div><dt>{locale === 'zh-CN' ? '业务目标' : 'Business goal'}</dt><dd>{scenario.businessIntent}</dd></div><div><dt>{locale === 'zh-CN' ? '预期 / Oracle' : 'Expected / Oracle'}</dt><dd><strong>{scenario.oracle?.displayName ?? missing}</strong><span>{scenario.oracle?.summary ?? missing}</span></dd></div><div><dt>{locale === 'zh-CN' ? '来源' : 'Source'}</dt><dd>{scenario.source?.displayName ?? missing}<span>{scenario.source?.type ?? ''}</span></dd></div><div><dt>{locale === 'zh-CN' ? '适用契约' : 'Applicable contracts'}</dt><dd>{scenario.applicableContractRefs.length} {locale === 'zh-CN' ? '个契约' : 'contracts'}</dd></div><div><dt>{locale === 'zh-CN' ? '依赖表现' : 'Dependency behavior'}</dt><dd>{scenario.behaviorProfiles.length === 0 ? missing : scenario.behaviorProfiles.map((profile) => <span key={profile.behaviorRef.id}>{displayScenarioValue(profile.behavior, locale)} · {profile.summary}</span>)}</dd></div><div><dt>{locale === 'zh-CN' ? '负责人' : 'Owner'}</dt><dd>{scenario.owner?.name ?? missing}</dd></div></dl><details className="capability-technical-details"><summary><ChevronDown size={15} aria-hidden="true" /> {locale === 'zh-CN' ? '精确技术引用' : 'Exact technical references'}</summary><dl><div><dt>Case</dt><dd>{formatScenarioRef(scenario.caseRef)}</dd></div><div><dt>Contracts</dt><dd>{scenario.applicableContractRefs.map(formatScenarioRef).join(', ')}</dd></div><div><dt>Source / Oracle</dt><dd>{scenario.sourceRef ? formatScenarioRef(scenario.sourceRef) : missing} / {scenario.oracleRef ? formatScenarioRef(scenario.oracleRef) : missing}</dd></div><div><dt>Behavior</dt><dd>{scenario.behaviorProfiles.map((profile) => formatScenarioRef(profile.behaviorRef)).join(', ') || missing}</dd></div></dl></details></article>;
}

function QualityMetric({ label, value }: { label: string; value: number }) {
  return <div><span>{label}</span><strong>{value}%</strong><div className="capability-quality-meter" aria-hidden="true"><i style={{ width: `${value}%` }} /></div></div>;
}

function ScenarioEmptyState({ locale, onClear }: { locale: 'en' | 'zh-CN'; onClear: () => void }) {
  return <section className="capability-scenario-empty" data-testid="capability-scenario-empty"><ListFilter size={20} aria-hidden="true" /><div><strong>{locale === 'zh-CN' ? '没有匹配的场景' : 'No matching scenarios'}</strong><p>{locale === 'zh-CN' ? '当前搜索或筛选条件没有结果，Dataset 本身没有被修改。' : 'The current search or filters returned no result; the dataset was not changed.'}</p><button type="button" className="capability-secondary-action" onClick={onClear}>{locale === 'zh-CN' ? '清除筛选' : 'Clear filters'}</button></div></section>;
}

function ScenarioDatasetError({ error, locale, onRetry }: { error: Error | null; locale: 'en' | 'zh-CN'; onRetry: () => void }) {
  const requestError = error instanceof CapabilityStudioRequestError ? error : null;
  const protocolCode = requestError?.code ?? (isCapabilityStudioProtocolError(error) ? error.code : 'RG.CAPABILITY_STUDIO.SCENARIO_DATASET_UNAVAILABLE');
  const message = error?.message ?? (locale === 'zh-CN' ? '场景数据集无法加载。' : 'The scenario dataset could not be loaded.');
  const impact = requestError?.impact ?? (isCapabilityStudioProtocolError(error) ? error.impact : (locale === 'zh-CN' ? '场景列表、业务预期和质量摘要暂时无法展示。' : 'The scenario list, business expectations, and quality summary cannot be shown.'));
  const recovery = requestError?.recoveryAction ?? (locale === 'zh-CN' ? '确认服务端提供严格 Dataset projection 后重试。' : 'Confirm that the server provides the strict dataset projection, then retry.');
  return <div className="capability-view capability-error-state capability-scenario-error" data-testid="capability-scenario-error"><div className="capability-error-icon"><AlertTriangle size={23} aria-hidden="true" /></div><p className="capability-kicker">{protocolCode}</p><h3>{locale === 'zh-CN' ? '场景数据暂时不可用' : 'Scenario data is unavailable'}</h3><div className="capability-error-grid"><div><strong>{locale === 'zh-CN' ? '发生了什么' : 'What happened'}</strong><p>{message}</p></div><div><strong>{locale === 'zh-CN' ? '影响' : 'Impact'}</strong><p>{impact}</p></div><div><strong>{locale === 'zh-CN' ? '如何继续' : 'How to continue'}</strong><p>{recovery}</p></div></div><button type="button" className="capability-primary-action" onClick={onRetry}><RefreshCw size={16} aria-hidden="true" /> {locale === 'zh-CN' ? '重试加载场景数据' : 'Retry scenario dataset'}</button></div>;
}

function formatScenarioRef(ref: { kind: string; id: string; revision: number }): string {
  return `${ref.kind}:${ref.id}@${ref.revision}`;
}

function TutorialBranchView({ fetcher, locale }: { fetcher?: CapabilityStudioFetcher; locale: 'en' | 'zh-CN' }) {
  const [branch, setBranch] = useState<TutorialBranchProjection | null>(null);
  const [condition, setCondition] = useState('');
  const [durationMs, setDurationMs] = useState(3000);
  const [preflight, setPreflight] = useState<TutorialBranchPreflight | null>(null);
  const [error, setError] = useState<CapabilityStudioRequestError | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const current = await fetchTutorialBranch(fetcher);
      setBranch(current);
      setCondition(current.behavior.condition);
      setDurationMs(current.behavior.durationMs);
      setPreflight(null);
    } catch (nextError) {
      setError(asRequestError(nextError));
    } finally {
      setLoading(false);
    }
  }, [fetcher]);

  useEffect(() => { void load(); }, [load]);

  const saveAndPreflight = async () => {
    if (!branch) return;
    setSaving(true);
    setError(null);
    setPreflight(null);
    try {
      const saved = await saveTutorialBehavior({
        condition,
        behavior: 'TIMEOUT',
        durationMs,
        expectedRevision: branch.revision,
      }, fetcher);
      if (saved.canonicalBaselineFingerprint !== branch.canonicalBaselineFingerprint) {
        throw new CapabilityStudioRequestError(
          'RG.CAPABILITY_STUDIO.BASELINE_CHANGED',
          locale === 'zh-CN' ? '保存后标准基线指纹发生了变化。' : 'The canonical baseline fingerprint changed after saving.',
          locale === 'zh-CN' ? '无法证明本次编辑只影响教程分支。' : 'The edit cannot be proven to be isolated to the tutorial branch.',
          locale === 'zh-CN' ? '停止演练并重新加载标准基线。' : 'Stop the rehearsal and reload the canonical baseline.',
          409,
        );
      }
      const checked = await preflightTutorialBranch(fetcher);
      if (checked.branchId !== saved.branchId || checked.revision !== saved.revision || checked.fingerprint !== saved.fingerprint) {
        throw new CapabilityStudioRequestError(
          'RG.CAPABILITY_STUDIO.PREFLIGHT_BINDING_MISMATCH',
          locale === 'zh-CN' ? '预检没有绑定到刚保存的教程版本。' : 'Preflight did not bind the version that was just saved.',
          locale === 'zh-CN' ? '当前预检结论不能用于后续运行。' : 'The current preflight result cannot be used for a run.',
          locale === 'zh-CN' ? '重新加载教程分支并再次预检。' : 'Reload the tutorial branch and run preflight again.',
          409,
        );
      }
      setBranch(saved);
      setPreflight(checked);
    } catch (nextError) {
      setError(asRequestError(nextError));
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return <div className="capability-view capability-inline-state" aria-busy="true">{locale === 'zh-CN' ? '正在准备隔离教程分支...' : 'Preparing the isolated tutorial branch...'}</div>;
  }
  if (!branch) {
    return <div className="capability-view"><ViewHeading kicker="GP-04" title={locale === 'zh-CN' ? '隔离演练配置' : 'Isolated rehearsal setup'} description={locale === 'zh-CN' ? '教程分支当前不可用，标准基线未受影响。' : 'The tutorial branch is unavailable; the canonical baseline is unaffected.'} status={locale === 'zh-CN' ? '未加载' : 'Not loaded'} />{error && <TutorialError error={error} locale={locale} onReload={() => void load()} />}</div>;
  }

  return <div className="capability-view" data-testid="capability-tutorial-branch">
    <ViewHeading kicker="GP-04" title={locale === 'zh-CN' ? '演练“历史补偿查询超时”' : 'Rehearse a compensation-history timeout'} description={locale === 'zh-CN' ? '在隔离分支中描述依赖表现，不接触真实业务接口。' : 'Describe dependency behavior on an isolated branch without reaching a real business API.'} status={locale === 'zh-CN' ? `教程分支 · 第 ${branch.revision} 版` : `Tutorial branch · revision ${branch.revision}`} />
    <div className="capability-branch-safety" aria-label={locale === 'zh-CN' ? '分支安全边界' : 'Branch safety boundary'}>
      <div><ShieldCheck size={18} aria-hidden="true" /><span><small>{locale === 'zh-CN' ? '标准基线' : 'Canonical baseline'}</small><strong>{locale === 'zh-CN' ? '只读，不会被本次操作修改' : 'Read-only and unchanged by this task'}</strong></span></div>
      <ArrowRight size={18} aria-hidden="true" />
      <div><Beaker size={18} aria-hidden="true" /><span><small>{locale === 'zh-CN' ? '当前工作区' : 'Current workspace'}</small><strong>{locale === 'zh-CN' ? '教程分支，可安全编辑' : 'Tutorial branch, safe to edit'}</strong></span></div>
    </div>
    <section className="capability-section capability-behavior-editor">
      <SectionTitle icon={<Clock3 size={17} />} title={locale === 'zh-CN' ? '用业务句式描述依赖表现' : 'Describe dependency behavior as a business sentence'} subtitle={locale === 'zh-CN' ? '选择条件、表现和持续时间；无需编写 Mock JSON。' : 'Choose the condition, behavior, and duration without authoring mock JSON.'} />
      <div className="capability-sentence-editor">
        <label><span>1</span><small>{locale === 'zh-CN' ? '当什么条件' : 'When'}</small><select aria-label={locale === 'zh-CN' ? '发生条件' : 'Behavior condition'} value={condition} onChange={(event) => setCondition(event.target.value)}><option value={branch.behavior.condition}>{displayBehaviorCondition(branch.behavior.condition, locale)}</option></select></label>
        <label><span>2</span><small>{locale === 'zh-CN' ? '依赖如何表现' : 'Dependency behavior'}</small><select aria-label={locale === 'zh-CN' ? '依赖表现' : 'Dependency behavior'} value="TIMEOUT" disabled><option value="TIMEOUT">{locale === 'zh-CN' ? '等待后超时' : 'Times out after waiting'}</option></select></label>
        <label><span>3</span><small>{locale === 'zh-CN' ? '持续多久' : 'Duration'}</small><div className="capability-duration-input"><input aria-label={locale === 'zh-CN' ? '超时持续毫秒数' : 'Timeout duration in milliseconds'} type="number" min="100" max="30000" step="100" value={durationMs} onChange={(event) => setDurationMs(Number(event.target.value))} /><b>ms</b></div></label>
      </div>
      <p className="capability-behavior-sentence"><Clock3 size={17} aria-hidden="true" /> {locale === 'zh-CN' ? `当“${displayBehaviorCondition(condition, locale)}”时，“${branch.behavior.dependencyName}”等待 ${formatDuration(durationMs, locale)} 后超时。` : `When “${displayBehaviorCondition(condition, locale)}”, “${branch.behavior.dependencyName}” times out after ${formatDuration(durationMs, locale)}.`}</p>
      <div className="capability-editor-actions"><div><small>{locale === 'zh-CN' ? '保存会创建新的不可变教程版本' : 'Saving creates a new immutable tutorial revision'}</small><span>{locale === 'zh-CN' ? '标准基线始终保持原值' : 'The canonical baseline remains unchanged'}</span></div><button type="button" className="capability-primary-action" disabled={saving || !condition || durationMs < 100 || durationMs > 30000} onClick={() => void saveAndPreflight()}>{saving ? <RefreshCw className="capability-spin" size={16} aria-hidden="true" /> : <Save size={16} aria-hidden="true" />} {saving ? (locale === 'zh-CN' ? '正在保存并预检...' : 'Saving and checking...') : (locale === 'zh-CN' ? '保存并隔离预检' : 'Save and run isolated preflight')}</button></div>
    </section>
    {preflight && <section className="capability-preflight-success" role="status" data-testid="capability-preflight-success"><CheckCircle2 size={21} aria-hidden="true" /><div><strong>{locale === 'zh-CN' ? '隔离预检通过，可以进入试跑' : 'Isolated preflight passed; ready to run'}</strong><p>{locale === 'zh-CN' ? `教程分支已保存为第 ${preflight.revision} 版，标准基线未改变。` : `Tutorial branch revision ${preflight.revision} is saved and the canonical baseline is unchanged.`}</p><dl><div><dt>{locale === 'zh-CN' ? '未解析依赖' : 'Unresolved dependencies'}</dt><dd>{preflight.unresolvedDependencies}</dd></div><div><dt>{locale === 'zh-CN' ? '真实接口调用' : 'Real external calls'}</dt><dd>{preflight.realExternalCallCount}</dd></div><div><dt>{locale === 'zh-CN' ? '失败时转真实接口' : 'Fallback to real APIs'}</dt><dd>{preflight.fallbackToReal ? (locale === 'zh-CN' ? '是' : 'Yes') : (locale === 'zh-CN' ? '已禁止' : 'Blocked')}</dd></div></dl></div></section>}
    {error && <TutorialError error={error} locale={locale} onReload={() => void load()} />}
    <details className="capability-technical-details"><summary><ChevronDown size={15} aria-hidden="true" /> {locale === 'zh-CN' ? '版本技术引用' : 'Revision technical references'}</summary><dl><div><dt>Branch</dt><dd>{branch.branchId}@{branch.revision}</dd></div><div><dt>Fingerprint</dt><dd>{branch.fingerprint}</dd></div><div><dt>Baseline</dt><dd>{branch.canonicalBaselineFingerprint}</dd></div></dl></details>
  </div>;
}

function TutorialError({ error, locale, onReload }: { error: CapabilityStudioRequestError; locale: 'en' | 'zh-CN'; onReload: () => void }) {
  return <section className="capability-operation-error" role="alert" data-testid="capability-tutorial-error"><AlertTriangle size={20} aria-hidden="true" /><div><p className="capability-kicker">{error.code}</p><div className="capability-operation-error-grid"><div><strong>{locale === 'zh-CN' ? '发生了什么' : 'What happened'}</strong><p>{error.whatHappened}</p></div><div><strong>{locale === 'zh-CN' ? '当前影响' : 'Current impact'}</strong><p>{error.impact}</p></div><div><strong>{locale === 'zh-CN' ? '恢复动作' : 'Recovery action'}</strong><p>{error.recoveryAction}</p></div></div><button type="button" className="capability-secondary-action" onClick={onReload}><RefreshCw size={15} aria-hidden="true" /> {error.status === 409 ? (locale === 'zh-CN' ? '重新加载最新版本' : 'Reload latest revision') : (locale === 'zh-CN' ? '重新加载教程分支' : 'Reload tutorial branch')}</button></div></section>;
}

function asRequestError(error: unknown): CapabilityStudioRequestError {
  if (error instanceof CapabilityStudioRequestError) return error;
  return new CapabilityStudioRequestError(
    'RG.CAPABILITY_STUDIO.OPERATION_FAILED',
    error instanceof Error ? error.message : 'The operation did not complete.',
    'The tutorial branch was not changed.',
    'Reload the tutorial branch and retry.',
    0,
  );
}

function displayBehaviorCondition(condition: string, locale: 'en' | 'zh-CN'): string {
  const known: Record<string, { en: string; 'zh-CN': string }> = {
    WHEN_COMPENSATION_HISTORY_IS_REQUESTED: { en: 'compensation history is requested', 'zh-CN': '查询历史补偿记录' },
    COMPENSATION_HISTORY_LOOKUP: { en: 'compensation history is requested', 'zh-CN': '查询历史补偿记录' },
  };
  return known[condition]?.[locale] ?? condition;
}

function formatDuration(durationMs: number, locale: 'en' | 'zh-CN'): string {
  if (durationMs % 1000 === 0) return locale === 'zh-CN' ? `${durationMs / 1000} 秒` : `${durationMs / 1000} seconds`;
  return locale === 'zh-CN' ? `${durationMs} 毫秒` : `${durationMs} milliseconds`;
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
    FAULT: { en: 'Fault', 'zh-CN': '故障场景' },
    REGRESSION: { en: 'Regression', 'zh-CN': '回归场景' },
    SECURITY: { en: 'Security', 'zh-CN': '安全场景' },
    ACTIVE: { en: 'Active', 'zh-CN': '使用中' },
    DRAFT: { en: 'Draft', 'zh-CN': '草稿' },
    REVIEW_READY: { en: 'Review ready', 'zh-CN': '待评审' },
    RETIRED: { en: 'Retired', 'zh-CN': '已退役' },
    INTERNAL: { en: 'Internal', 'zh-CN': '内部' },
    PUBLIC: { en: 'Public', 'zh-CN': '公开' },
    CONFIDENTIAL: { en: 'Confidential', 'zh-CN': '机密' },
    RESTRICTED: { en: 'Restricted', 'zh-CN': '受限' },
    READY: { en: 'Ready', 'zh-CN': '就绪' },
    STALE: { en: 'Stale', 'zh-CN': '已过期' },
    BLOCKED: { en: 'Blocked', 'zh-CN': '已阻断' },
    DESIGNED_NOT_RUN: { en: 'Designed, not run', 'zh-CN': '已设计，未运行' },
    RETURN: { en: 'Return', 'zh-CN': '正常返回' },
    ERROR: { en: 'Error', 'zh-CN': '业务错误' },
    TIMEOUT: { en: 'Timeout', 'zh-CN': '超时' },
    MUST_NOT_CALL: { en: 'Must not call', 'zh-CN': '禁止调用' },
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
