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
  Eye,
  EyeOff,
  LayoutDashboard,
  ListFilter,
  PlayCircle,
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
  fetchFeatureRehearsal,
  fetchScenarioDataset,
  fetchTutorialBranch,
  preflightTutorialBranch,
  runGovernedBaseline,
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
  type FeatureRehearsalEdge,
  type FeatureRehearsalNode,
  type FeatureRehearsalPermission,
  type FeatureRehearsalProjection,
  type GovernedBaselineSuccessProjection,
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
  const [governedBaseline, setGovernedBaseline] = useState<GovernedBaselineSuccessProjection | null>(null);
  const [governedBaselineError, setGovernedBaselineError] = useState<Error | null>(null);
  const [governedBaselineLoading, setGovernedBaselineLoading] = useState(false);

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

  const executeGovernedBaseline = useCallback(async () => {
    setGovernedBaselineLoading(true);
    setGovernedBaselineError(null);
    try {
      setGovernedBaseline(await runGovernedBaseline(fetcher));
    } catch (nextError) {
      setGovernedBaseline(null);
      setGovernedBaselineError(nextError instanceof Error ? nextError : new Error('The governed verification could not be completed.'));
    } finally {
      setGovernedBaselineLoading(false);
    }
  }, [fetcher]);

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
          <p className="capability-eyebrow"><Sparkles size={15} aria-hidden="true" /> {locale === 'zh-CN' ? '能力资产演示 · 隔离分支' : 'Capability asset demo · Isolated branch'}</p>
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
          {model.assets.features.map((asset, index) => <TaskButton key={asset.technicalRef ?? index} active={task === 'feature'} icon={<GitBranch size={16} />} label={text(asset.name)} onClick={() => setTask('feature')} testId="capability-task-feature" />)}
          {model.assets.tools.map((asset, index) => <TaskButton key={asset.technicalRef ?? index} active={task === 'tool'} icon={<Wrench size={16} />} label={text(asset.name)} onClick={() => setTask('tool')} testId="capability-task-tool" />)}
          <TaskButton active={task === 'scenarios'} icon={<Database size={16} />} label={locale === 'zh-CN' ? '场景数据' : 'Scenario data'} onClick={() => setTask('scenarios')} badge={model.scenarios.length} testId="capability-task-scenarios" />
          <TaskButton active={task === 'tutorial'} icon={<Beaker size={16} />} label={locale === 'zh-CN' ? '隔离演练配置' : 'Isolated rehearsal setup'} onClick={() => setTask('tutorial')} testId="capability-task-tutorial" />
        </aside>

        <section className="capability-main" aria-live="polite">
          {task === 'overview' && <OverviewView model={model} text={text} locale={locale} onOpenContract={openApi} onOpenScenarios={() => setTask('scenarios')} onOpenTutorial={() => setTask('tutorial')} />}
          {task === 'contract' && currentAsset && <ContractView asset={currentAsset} text={text} locale={locale} />}
          {task === 'scenarios' && <ScenarioView fetcher={fetcher} locale={locale} />}
          {task === 'tutorial' && <TutorialBranchView fetcher={fetcher} locale={locale} />}
          {task === 'feature' && selectedFeature && <FeatureRehearsalView asset={selectedFeature} fetcher={fetcher} text={text} locale={locale} />}
          {task === 'tool' && selectedTool && <ToolGovernedBaselineView asset={selectedTool} text={text} locale={locale} projection={governedBaseline} error={governedBaselineError} loading={governedBaselineLoading} onRun={() => void executeGovernedBaseline()} />}
        </section>

        <ReadinessPanel model={model} text={text} locale={locale} task={task} governedBaseline={governedBaseline} governedBaselineError={governedBaselineError} governedBaselineLoading={governedBaselineLoading} onNextAction={() => task === 'overview' ? openApi(0) : task === 'tool' ? void executeGovernedBaseline() : setTask('scenarios')} />
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
  const controls = scenario.behaviorProfiles.filter((profile) => profile.purpose === 'RUNTIME_CONTROL');
  const expectations = scenario.behaviorProfiles.filter((profile) => profile.purpose === 'BUSINESS_EXPECTATION');
  return <article className="capability-scenario-details" data-testid="capability-scenario-details"><div className="capability-scenario-details-heading"><div><p className="capability-kicker">{displayScenarioValue(scenario.category, locale)}</p><h4>{scenario.name}</h4></div><span className={`capability-quality-status capability-quality-${scenario.qualityState.toLowerCase()}`}>{displayScenarioValue(scenario.qualityState, locale)}</span></div><dl className="capability-scenario-detail-grid"><div><dt>{locale === 'zh-CN' ? '业务目标' : 'Business goal'}</dt><dd>{scenario.businessIntent}</dd></div><div><dt>{locale === 'zh-CN' ? '预期 / Oracle' : 'Expected / Oracle'}</dt><dd><strong>{scenario.oracle?.displayName ?? missing}</strong><span>{scenario.oracle?.summary ?? missing}</span></dd></div><div><dt>{locale === 'zh-CN' ? '来源' : 'Source'}</dt><dd>{scenario.source?.displayName ?? missing}<span>{scenario.source?.type ?? ''}</span></dd></div><div><dt>{locale === 'zh-CN' ? '适用契约' : 'Applicable contracts'}</dt><dd>{scenario.applicableContractRefs.length} {locale === 'zh-CN' ? '个契约' : 'contracts'}</dd></div><div><dt>{locale === 'zh-CN' ? '隔离运行依赖' : 'Isolated runtime controls'}</dt><dd>{controls.length === 0 ? missing : controls.map((profile) => <span key={profile.behaviorRef.id}>{displayScenarioDependency(profile.dependencyRef.id, locale)} · {displayScenarioValue(profile.behavior, locale)}</span>)}</dd></div><div><dt>{locale === 'zh-CN' ? '业务正确性要求' : 'Business correctness expectations'}</dt><dd>{expectations.length === 0 ? <span>{locale === 'zh-CN' ? '由 Oracle 校验业务结果' : 'Business result is checked by the Oracle'}</span> : expectations.map((profile) => <span key={profile.behaviorRef.id}>{profile.summary}</span>)}</dd></div><div><dt>{locale === 'zh-CN' ? '负责人' : 'Owner'}</dt><dd>{scenario.owner?.name ?? missing}</dd></div></dl><details className="capability-technical-details"><summary><ChevronDown size={15} aria-hidden="true" /> {locale === 'zh-CN' ? '精确技术引用' : 'Exact technical references'}</summary><dl><div><dt>Case</dt><dd>{formatScenarioRef(scenario.caseRef)}</dd></div><div><dt>Contracts</dt><dd>{scenario.applicableContractRefs.map(formatScenarioRef).join(', ')}</dd></div><div><dt>Source / Oracle</dt><dd>{scenario.sourceRef ? formatScenarioRef(scenario.sourceRef) : missing} / {scenario.oracleRef ? formatScenarioRef(scenario.oracleRef) : missing}</dd></div><div><dt>Behavior</dt><dd>{scenario.behaviorProfiles.map((profile) => formatScenarioRef(profile.behaviorRef)).join(', ') || missing}</dd></div></dl></details></article>;
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

const featureRehearsalCases = [
  { id: 'case-standard-cancellation-fee', name: { en: 'Standard cancellation fee', 'zh-CN': '标准取消费处理' } },
  { id: 'case-rider-not-responsible', name: { en: 'Rider is not responsible', 'zh-CN': '乘客无责' } },
  { id: 'case-driver-responsible', name: { en: 'Driver is responsible', 'zh-CN': '司机有责' } },
  { id: 'case-city-policy-missing', name: { en: 'City policy is missing', 'zh-CN': '城市政策缺失' } },
  { id: 'case-compensation-history-empty', name: { en: 'Compensation history is empty', 'zh-CN': '历史补偿记录为空' } },
  { id: 'case-compensation-history-timeout', name: { en: 'Compensation history times out', 'zh-CN': '历史补偿记录查询超时' } },
  { id: 'case-duplicate-cancellation', name: { en: 'Duplicate cancellation request', 'zh-CN': '重复取消请求' } },
  { id: 'case-forbidden-write-effect', name: { en: 'Forbidden write effect', 'zh-CN': '禁止写副作用' } },
  { id: 'case-policy-revision-regression', name: { en: 'Policy revision regression', 'zh-CN': '政策版本回归' } },
] as const;

function FeatureRehearsalView({ asset, fetcher, text, locale }: { asset: CapabilityAssetSummary; fetcher?: CapabilityStudioFetcher; text: (value: Parameters<typeof localized>[0]) => string; locale: 'en' | 'zh-CN' }) {
  const [caseId, setCaseId] = useState('case-compensation-history-timeout');
  const [permission, setPermission] = useState<FeatureRehearsalPermission>('STRUCTURE_ONLY');
  const [projection, setProjection] = useState<FeatureRehearsalProjection | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);

  const load = useCallback(async (nextCaseId = caseId, nextPermission = permission) => {
    setLoading(true);
    setError(null);
    try {
      setProjection(await fetchFeatureRehearsal(nextCaseId, nextPermission, fetcher));
    } catch (nextError) {
      setError(nextError instanceof Error ? nextError : new Error('The Feature rehearsal could not be loaded.'));
    } finally {
      setLoading(false);
    }
  }, [caseId, fetcher, permission]);

  useEffect(() => { void load(); }, []);

  const changeCase = (nextCaseId: string) => {
    setCaseId(nextCaseId);
    void load(nextCaseId, permission);
  };
  const changePermission = (nextPermission: FeatureRehearsalPermission) => {
    setPermission(nextPermission);
    void load(caseId, nextPermission);
  };
  const selectedCase = featureRehearsalCases.find((entry) => entry.id === caseId) ?? featureRehearsalCases[5];

  return <div className="capability-view" data-testid="capability-feature-rehearsal">
    <ViewHeading kicker="GP-05 / GP-06" title={text(asset.name)} description={locale === 'zh-CN' ? '从业务场景进入特征加工图，并沿同一次运行查看每个节点和数据边。' : 'Start from a business scenario, inspect the feature DAG, and follow the same run through every node and data edge.'} status={locale === 'zh-CN' ? '运行态只读' : 'RUN VIEW · READ-ONLY'} />
    <section className="capability-feature-context capability-section">
      <div className="capability-feature-context-main"><label htmlFor="feature-rehearsal-case">{locale === 'zh-CN' ? '演示场景' : 'Rehearsal scenario'}</label><select id="feature-rehearsal-case" value={caseId} onChange={(event) => changeCase(event.target.value)}>{featureRehearsalCases.map((entry) => <option key={entry.id} value={entry.id}>{text(entry.name)}</option>)}</select><p>{locale === 'zh-CN' ? '选择业务问题，系统会加载对应的脱离真实接口的特征演练结果。' : 'Choose a business problem to load its isolated feature rehearsal result.'}</p></div>
      <div className="capability-feature-permission" role="group" aria-label={locale === 'zh-CN' ? '数据可见权限' : 'Data visibility permission'}><span>{locale === 'zh-CN' ? '数据查看' : 'Data view'}</span><div className="capability-segmented-control"><button type="button" aria-pressed={permission === 'STRUCTURE_ONLY'} className={permission === 'STRUCTURE_ONLY' ? 'active' : ''} onClick={() => changePermission('STRUCTURE_ONLY')}><EyeOff size={14} aria-hidden="true" /> {locale === 'zh-CN' ? '结构' : 'Structure'}</button><button type="button" aria-pressed={permission === 'PAYLOAD_VISIBLE'} className={permission === 'PAYLOAD_VISIBLE' ? 'active' : ''} onClick={() => changePermission('PAYLOAD_VISIBLE')}><Eye size={14} aria-hidden="true" /> {locale === 'zh-CN' ? '受控数据' : 'Payload'}</button></div><small>{permission === 'STRUCTURE_ONLY' ? (locale === 'zh-CN' ? '仅显示摘要与指纹，不显示值。' : 'Summaries and fingerprints only; values stay hidden.') : (locale === 'zh-CN' ? '仅展示演示数据，不代表真实业务载荷。' : 'Controlled demo values only; never real business payloads.')}</small></div>
    </section>
    {loading && <div className="capability-feature-state" role="status" aria-live="polite"><RefreshCw className="capability-spin" size={18} aria-hidden="true" /> {locale === 'zh-CN' ? '正在加载特征运行和数据视图...' : 'Loading feature run and data lens...'}</div>}
    {!loading && error && <section className="capability-operation-error capability-feature-error" role="alert"><AlertTriangle size={19} aria-hidden="true" /><div><strong>{locale === 'zh-CN' ? '特征演练暂时无法加载' : 'Feature rehearsal could not load'}</strong><div className="capability-operation-error-grid"><div><small>{locale === 'zh-CN' ? '发生了什么' : 'What happened'}</small><p>{error.message}</p></div><div><small>{locale === 'zh-CN' ? '影响' : 'Impact'}</small><p>{locale === 'zh-CN' ? '当前场景的 DAG 和 Data Lens 保持不变。' : 'The current scenario DAG and Data Lens remain unchanged.'}</p></div><div><small>{locale === 'zh-CN' ? '如何恢复' : 'Recovery'}</small><p>{locale === 'zh-CN' ? '保持场景选择不变，重试加载。' : 'Keep the scenario selected and retry the load.'}</p></div></div><button type="button" className="capability-secondary-action" onClick={() => void load()}><RefreshCw size={15} aria-hidden="true" /> {locale === 'zh-CN' ? '重试当前场景' : 'Retry current scenario'}</button></div></section>}
    {!loading && projection && <FeatureRehearsalContent projection={projection} selectedCase={selectedCase} locale={locale} text={text} />}
  </div>;
}

function FeatureRehearsalContent({ projection, selectedCase, locale, text }: { projection: FeatureRehearsalProjection; selectedCase: typeof featureRehearsalCases[number]; locale: 'en' | 'zh-CN'; text: (value: Parameters<typeof localized>[0]) => string }) {
  const { run, dataLens } = projection;
  return <>
    <section className="capability-feature-run-strip" aria-label={locale === 'zh-CN' ? '运行摘要' : 'Run summary'}>
      <div><span>{locale === 'zh-CN' ? '场景' : 'Scenario'}</span><strong>{text(selectedCase.name)}</strong></div>
      <div><span>{locale === 'zh-CN' ? '运行状态' : 'Run status'}</span><strong className={`feature-status feature-status-${run.status.toLowerCase()}`}>{displayFeatureStatus(run.status, locale)}</strong></div>
      <div><span>{locale === 'zh-CN' ? '绑定方式' : 'Binding'}</span><strong>{locale === 'zh-CN' ? '隔离 Fixture 控制' : 'Isolated fixture control'}</strong></div>
      <div><span>{locale === 'zh-CN' ? '真实调用' : 'Real calls'}</span><strong>{run.realExternalCallCount}</strong></div>
    </section>
    <section className="capability-section capability-feature-dag-section" aria-labelledby="feature-dag-heading"><SectionTitle icon={<GitBranch size={17} />} title={locale === 'zh-CN' ? '特征加工 DAG' : 'Feature processing DAG'} subtitle={locale === 'zh-CN' ? '4 个业务接口并行取数，汇聚为取消费事实，再进入决策。节点和边均来自同一次运行 Trace。' : 'Four business APIs feed cancellation facts and then a decision. Every node and edge comes from the same run trace.'} /><div className="capability-feature-dag" data-testid="feature-dag" role="region" tabIndex={0} aria-label={locale === 'zh-CN' ? '特征加工 DAG 图，可横向滚动' : 'Feature processing DAG diagram, horizontally scrollable'}><div className="feature-dag-column feature-dag-inputs">{featureNodesOfKind(dataLens.nodes, 'API').map((node) => <FeatureNodeCard key={node.nodeId} node={node} locale={locale} text={text} />)}</div><FeatureEdgeColumn edges={featureEdgesFromKind(dataLens.edges, 'API')} locale={locale} text={text} /><div className="feature-dag-column">{featureNodesOfKind(dataLens.nodes, 'AGGREGATOR').map((node) => <FeatureNodeCard key={node.nodeId} node={node} locale={locale} text={text} />)}</div><FeatureEdgeColumn edges={featureEdgesFromKind(dataLens.edges, 'AGGREGATOR')} locale={locale} text={text} /><div className="feature-dag-column">{featureNodesOfKind(dataLens.nodes, 'DECISION').map((node) => <FeatureNodeCard key={node.nodeId} node={node} locale={locale} text={text} />)}</div></div></section>
    <section className="capability-section capability-data-lens" aria-labelledby="feature-lens-heading"><SectionTitle icon={<Database size={17} />} title={locale === 'zh-CN' ? 'Data Lens · 数据流检查' : 'Data Lens · Data flow inspection'} subtitle={locale === 'zh-CN' ? '按节点和数据边查看同一运行中的输入、输出和稳定指纹。' : 'Inspect inputs, outputs, and stable fingerprints from the same run by node and edge.'} /><div className="capability-lens-grid"><div><h4>{locale === 'zh-CN' ? '节点数据' : 'Node data'}</h4><div className="capability-lens-node-list">{dataLens.nodes.map((node) => <FeatureLensNode key={node.invocationSite} node={node} permission={dataLens.permissionMode} locale={locale} text={text} />)}</div></div><div><h4>{locale === 'zh-CN' ? '运行数据边' : 'Runtime data edges'}</h4><div className="capability-lens-edge-list">{dataLens.edges.map((edge) => <FeatureLensEdge key={edge.edgeId} edge={edge} permission={dataLens.permissionMode} locale={locale} />)}</div></div></div>{dataLens.firstDifference && <div className="capability-first-difference" role="status"><AlertTriangle size={18} aria-hidden="true" /><div><strong>{locale === 'zh-CN' ? '首个断言差异已定位' : 'First assertion difference located'}</strong><p>{dataLens.firstDifference.scope || dataLens.firstDifference.source}</p><dl><div><dt>{locale === 'zh-CN' ? '位置' : 'Location'}</dt><dd>{dataLens.firstDifference.locator} · {dataLens.firstDifference.path || '/'}</dd></div><div><dt>{locale === 'zh-CN' ? '期望' : 'Expected'}</dt><dd>{dataLens.permissionMode === 'PAYLOAD_VISIBLE' ? formatPayload(dataLens.firstDifference.expected) : shortFingerprint(dataLens.firstDifference.expectedFingerprint)}</dd></div><div><dt>{locale === 'zh-CN' ? '实际' : 'Actual'}</dt><dd>{dataLens.permissionMode === 'PAYLOAD_VISIBLE' ? formatPayload(dataLens.firstDifference.actual) : shortFingerprint(dataLens.firstDifference.actualFingerprint)}</dd></div></dl></div></div>}{isFeatureLensTruncated(dataLens.truncation) && <div className="capability-feature-truncation" role="status"><Filter size={16} aria-hidden="true" /><span>{locale === 'zh-CN' ? `数据视图已截断：省略 ${dataLens.truncation.omittedNodes} 个节点、${dataLens.truncation.omittedEdges} 条边和 ${dataLens.truncation.omittedAttempts} 次尝试。` : `Data Lens truncated: ${dataLens.truncation.omittedNodes} nodes, ${dataLens.truncation.omittedEdges} edges, and ${dataLens.truncation.omittedAttempts} attempts omitted.`}</span></div>}</section>
    <p className="capability-feature-integrity"><ShieldCheck size={15} aria-hidden="true" /> {locale === 'zh-CN' ? '本页展示的是一次隔离演练结果，不代表业务验收通过。运行状态、真实调用次数和绑定方式均为证据字段。' : 'This is an isolated rehearsal result, not an acceptance decision. Run status, real-call count, and binding mode are evidence fields.'}</p>
  </>;
}

function FeatureNodeCard({ node, locale, text }: { node: FeatureRehearsalNode; locale: 'en' | 'zh-CN'; text: (value: Parameters<typeof localized>[0]) => string }) {
  const presentation = featureNodePresentation(node.nodeId);
  return <article className={`feature-dag-node feature-node-${presentation.kind.toLowerCase()}`} data-node-id={node.nodeId}><div className="feature-dag-node-heading"><span className="feature-node-kind">{displayFeatureNodeKind(presentation.kind, locale)}</span><span className={`feature-status feature-status-${node.status.toLowerCase()}`}>{displayFeatureStatus(node.status, locale)}</span></div><h4>{text(presentation.name)}</h4><p>{text(presentation.summary)}</p><div className="feature-node-fingerprints"><span><small>IN</small>{shortFingerprint(node.inputFingerprint)}</span><span><small>OUT</small>{shortFingerprint(node.outputFingerprint)}</span></div></article>;
}

function FeatureEdgeColumn({ edges, locale, text }: { edges: FeatureRehearsalEdge[]; locale: 'en' | 'zh-CN'; text: (value: Parameters<typeof localized>[0]) => string }) {
  return <div className="feature-dag-edge-column" aria-label={locale === 'zh-CN' ? '数据边' : 'Data edges'}>{edges.map((edge) => <div className="feature-dag-edge-label" key={edge.edgeId}><ArrowRight size={16} aria-hidden="true" /><span><strong>{text(featureNodePresentation(invocationNodeId(edge.fromInvocationSite)).name)} → {text(featureNodePresentation(invocationNodeId(edge.toInvocationSite)).name)}</strong><small>{shortFingerprint(edge.valueFingerprint)} · {displayFeatureStatus(edge.status, locale)}</small></span></div>)}</div>;
}

function featureNodesOfKind(nodes: FeatureRehearsalNode[], kind: FeatureNodeKind): FeatureRehearsalNode[] {
  return nodes.filter((node) => featureNodePresentation(node.nodeId).kind === kind)
    .sort((left, right) => featureNodeRank(left.nodeId) - featureNodeRank(right.nodeId));
}

function featureEdgesFromKind(edges: FeatureRehearsalEdge[], kind: FeatureNodeKind): FeatureRehearsalEdge[] {
  return edges.filter((edge) => featureNodePresentation(invocationNodeId(edge.fromInvocationSite)).kind === kind)
    .sort((left, right) => featureNodeRank(invocationNodeId(left.fromInvocationSite)) - featureNodeRank(invocationNodeId(right.fromInvocationSite)));
}

function featureNodeRank(nodeId: string): number {
  const rank = ['orderLookup', 'responsibilityLookup', 'cityPolicyLookup', 'compensationHistoryLookup', 'aggregateCancellationContext', 'cancellationDecision'].indexOf(nodeId);
  return rank === -1 ? Number.MAX_SAFE_INTEGER : rank;
}

function FeatureLensNode({ node, permission, locale, text }: { node: FeatureRehearsalNode; permission: FeatureRehearsalPermission; locale: 'en' | 'zh-CN'; text: (value: Parameters<typeof localized>[0]) => string }) {
  const presentation = featureNodePresentation(node.nodeId);
  return <article className="feature-lens-row"><div className="feature-lens-row-heading"><strong>{text(presentation.name)}</strong><span className={`feature-status feature-status-${node.status.toLowerCase()}`}>{displayFeatureStatus(node.status, locale)}</span></div><p>{node.operatorRef} · {node.fidelity || (locale === 'zh-CN' ? '未声明保真度' : 'Fidelity not declared')} · {node.durationMs} ms</p><div className="feature-lens-values"><span><small>{locale === 'zh-CN' ? '输入' : 'Input'}</small>{permission === 'PAYLOAD_VISIBLE' && node.input !== null ? <code>{formatPayload(node.input)}</code> : <code>{shortFingerprint(node.inputFingerprint)}</code>}</span><span><small>{locale === 'zh-CN' ? '输出' : 'Output'}</small>{permission === 'PAYLOAD_VISIBLE' && node.output !== null ? <code>{formatPayload(node.output)}</code> : <code>{shortFingerprint(node.outputFingerprint)}</code>}</span></div>{node.errorCode && <p className="feature-lens-error-code">{locale === 'zh-CN' ? '错误码' : 'Error code'}: <code>{node.errorCode}</code></p>}</article>;
}

function FeatureLensEdge({ edge, permission, locale }: { edge: FeatureRehearsalEdge; permission: FeatureRehearsalPermission; locale: 'en' | 'zh-CN' }) {
  return <article className="feature-lens-row feature-lens-edge"><div className="feature-lens-row-heading"><strong>{invocationNodeId(edge.fromInvocationSite)} <ArrowRight size={13} aria-hidden="true" /> {invocationNodeId(edge.toInvocationSite)}</strong><span className={`feature-status feature-status-${edge.status.toLowerCase()}`}>{displayFeatureStatus(edge.status, locale)}</span></div><p>{edge.edgeId}</p><code>{permission === 'PAYLOAD_VISIBLE' && edge.value !== null ? formatPayload(edge.value) : shortFingerprint(edge.valueFingerprint)}</code></article>;
}

type FeatureNodeKind = 'API' | 'AGGREGATOR' | 'DECISION';

function featureNodePresentation(nodeId: string): { kind: FeatureNodeKind; name: Parameters<typeof localized>[0]; summary: Parameters<typeof localized>[0] } {
  const values: Record<string, { kind: FeatureNodeKind; name: Parameters<typeof localized>[0]; summary: Parameters<typeof localized>[0] }> = {
    orderLookup: { kind: 'API', name: { en: 'Order lookup', 'zh-CN': '订单查询' }, summary: { en: 'Reads order facts.', 'zh-CN': '读取订单事实。' } },
    responsibilityLookup: { kind: 'API', name: { en: 'Responsibility lookup', 'zh-CN': '取消责任判定' }, summary: { en: 'Reads responsibility facts.', 'zh-CN': '读取责任归因事实。' } },
    cityPolicyLookup: { kind: 'API', name: { en: 'City policy lookup', 'zh-CN': '城市政策查询' }, summary: { en: 'Reads the governed policy revision.', 'zh-CN': '读取受治理的政策版本。' } },
    compensationHistoryLookup: { kind: 'API', name: { en: 'Compensation history lookup', 'zh-CN': '历史补偿查询' }, summary: { en: 'Reads prior compensation decisions.', 'zh-CN': '读取历史补偿决策。' } },
    aggregateCancellationContext: { kind: 'AGGREGATOR', name: { en: 'Cancellation facts', 'zh-CN': '取消费事实聚合' }, summary: { en: 'Combines the four governed dependency results.', 'zh-CN': '汇聚四个受治理的依赖结果。' } },
    cancellationDecision: { kind: 'DECISION', name: { en: 'Cancellation decision', 'zh-CN': '取消费决策' }, summary: { en: 'Produces the explainable service action.', 'zh-CN': '生成可解释的服务动作。' } },
  };
  return values[nodeId] ?? { kind: 'DECISION', name: nodeId, summary: { en: 'Runtime trace node.', 'zh-CN': '运行轨迹节点。' } };
}

function displayFeatureNodeKind(kind: FeatureNodeKind, locale: 'en' | 'zh-CN'): string {
  if (locale === 'en') return kind === 'AGGREGATOR' ? 'AGGREGATION' : kind;
  return kind === 'API' ? '接口' : kind === 'AGGREGATOR' ? '聚合' : '决策';
}

function displayFeatureStatus(status: string, locale: 'en' | 'zh-CN'): string {
  if (locale === 'en') return status.split('_').map((word) =>
    word.charAt(0) + word.slice(1).toLowerCase()).join(' ');
  const translations: Record<string, string> = { SUCCESS: '成功', FAILED: '失败', TIMEOUT: '超时', TIMED_OUT: '运行超时', SKIPPED: '已跳过', PARTIAL: '部分完成', MOCKED: '替身运行', CANCELLED: '已取消', FALLBACK: '已降级', NOT_INVOKED: '未调用', TRANSFERRED: '已传递', NOT_TRANSFERRED: '未传递', PASSED: '通过', ASSERTION_FAILED: '断言失败', EXECUTION_FAILED: '执行失败', CONTROL_PLAN_REJECTED: '控制计划被拒绝', FIXTURE_UNMATCHED: '替身未匹配', FIXTURE_UNUSED: '替身未消费', CONTROL_PLAN_UNAVAILABLE: '控制计划不可用', EVIDENCE_INCOMPLETE: '证据不完整' };
  return translations[status] ?? status;
}

function invocationNodeId(value: string): string {
  const segments = value.split('/').filter(Boolean);
  const segment = segments.length > 0 ? segments[segments.length - 1] : value;
  return segment.split('#')[0];
}

function isFeatureLensTruncated(value: FeatureRehearsalProjection['dataLens']['truncation']): boolean {
  return value.nodesTruncated || value.edgesTruncated || value.attemptsTruncated;
}

function shortFingerprint(value: string): string { return !value ? '—' : value.length > 18 ? `${value.slice(0, 12)}…${value.slice(-6)}` : value; }
function formatPayload(value: unknown): string { return typeof value === 'string' ? value : JSON.stringify(value) ?? 'null'; }

function ToolGovernedBaselineView({ asset, text, locale, projection, error, loading, onRun }: { asset: CapabilityAssetSummary; text: (value: Parameters<typeof localized>[0]) => string; locale: 'en' | 'zh-CN'; projection: GovernedBaselineSuccessProjection | null; error: Error | null; loading: boolean; onRun: () => void }) {
  return <div className="capability-view" data-testid="capability-tool">
    <ViewHeading kicker="GP-07 / GP-08" title={text(asset.name)} description={locale === 'zh-CN' ? '用同一份受治理场景数据连续验证工具，不连接真实业务接口。' : 'Verify the Tool repeatedly with one governed scenario dataset and no real business API calls.'} status={projection?.status === 'PASSED' ? (locale === 'zh-CN' ? '开发验证通过' : 'DEVELOPMENT VERIFIED') : displayProtocolStatus(text(asset.readiness), locale)} />
    <section className="capability-section capability-governed-intro">
      <SectionTitle icon={<ShieldCheck size={17} />} title={locale === 'zh-CN' ? '业务正确性验证' : 'Business correctness verification'} subtitle={locale === 'zh-CN' ? '固定场景分母、重复运行和完整证据闭包由服务端统一控制。' : 'The server controls the scenario denominator, repeated execution, and evidence closure.'} />
      <p className="capability-large-copy">{locale === 'zh-CN' ? '系统会发布 9 份隔离数据和 1 份测试套件，再将同一套件运行 3 轮。每轮都核对业务断言和结果指纹；任何真实外部调用、结果漂移或证据缺失都会使本次验证失败。' : 'The system publishes nine isolated fixtures and one test suite, then runs that exact suite three times. Every round verifies the business assertion and result fingerprint; any real call, result drift, or missing evidence fails the verification.'}</p>
      <div className="capability-governed-target" aria-label={locale === 'zh-CN' ? '验证目标' : 'Verification target'}>
        <div><span>{locale === 'zh-CN' ? '固定场景' : 'Fixed scenarios'}</span><strong>9</strong></div>
        <div><span>{locale === 'zh-CN' ? '重复轮次' : 'Rounds'}</span><strong>3</strong></div>
        <div><span>{locale === 'zh-CN' ? '预期检查' : 'Expected checks'}</span><strong>27</strong></div>
        <div><span>{locale === 'zh-CN' ? '真实接口' : 'Real APIs'}</span><strong>0</strong></div>
      </div>
      <div className="capability-governed-action"><div><strong>{locale === 'zh-CN' ? '运行不会修改标准基线' : 'The canonical baseline remains unchanged'}</strong><span>{locale === 'zh-CN' ? '结果只标记为开发验证，不自动通过发布门禁。' : 'Results are development evidence and never approve the release gate.'}</span></div><button type="button" className="capability-primary-action" disabled={loading} onClick={onRun} data-testid="run-governed-baseline">{loading ? <RefreshCw className="capability-spin" size={16} aria-hidden="true" /> : <PlayCircle size={16} aria-hidden="true" />} {loading ? (locale === 'zh-CN' ? '正在运行 27 项检查...' : 'Running 27 checks...') : projection ? (locale === 'zh-CN' ? '重新运行验证' : 'Run verification again') : (locale === 'zh-CN' ? '运行 9 × 3 受治理验证' : 'Run governed 9 x 3 verification')}</button></div>
    </section>
    {loading && <div className="capability-governed-running" role="status" aria-live="polite"><RefreshCw className="capability-spin" size={20} aria-hidden="true" /><div><strong>{locale === 'zh-CN' ? '正在准备隔离资产并逐轮验证' : 'Preparing isolated assets and running each round'}</strong><p>{locale === 'zh-CN' ? '页面会在 3 轮全部结束后一次性展示可复验结果。' : 'The page shows the verifiable result after all three rounds finish.'}</p></div></div>}
    {error && !loading && <GovernedBaselineError error={error} locale={locale} onRetry={onRun} />}
    {projection && !loading && <GovernedBaselineResult projection={projection} locale={locale} />}
    <TechnicalDetails asset={asset} locale={locale} />
  </div>;
}

function GovernedBaselineError({ error, locale, onRetry }: { error: Error; locale: 'en' | 'zh-CN'; onRetry: () => void }) {
  const requestError = error instanceof CapabilityStudioRequestError ? error : null;
  return <section className="capability-operation-error capability-governed-error" role="alert"><AlertTriangle size={19} aria-hidden="true" /><div><strong>{locale === 'zh-CN' ? '本次受治理验证未完成' : 'The governed verification did not complete'}</strong><div className="capability-operation-error-grid"><div><b>{locale === 'zh-CN' ? '发生了什么' : 'What happened'}</b><p>{requestError?.whatHappened ?? error.message}</p></div><div><b>{locale === 'zh-CN' ? '影响' : 'Impact'}</b><p>{requestError?.impact ?? (locale === 'zh-CN' ? '没有生成新的开发验证结果，已有资产未改变。' : 'No new development result was created; existing assets are unchanged.')}</p></div><div><b>{locale === 'zh-CN' ? '如何恢复' : 'Recovery'}</b><p>{requestError?.recoveryAction ?? (locale === 'zh-CN' ? '确认演示服务可用后重试。' : 'Confirm the demo service is available and retry.')}</p></div></div><button type="button" className="capability-secondary-action" onClick={onRetry}><RefreshCw size={15} aria-hidden="true" /> {locale === 'zh-CN' ? '重新运行' : 'Run again'}</button></div></section>;
}

function GovernedBaselineResult({ projection, locale }: { projection: GovernedBaselineSuccessProjection; locale: 'en' | 'zh-CN' }) {
  const highRiskCases = projection.cases.filter((caseResult) => caseResult.proofs.length > 4);
  const evidenceLabel = projection.evidenceClass === 'CERTIFIABLE'
    ? (locale === 'zh-CN' ? '可认证运行证据' : 'certifiable runtime evidence')
    : (locale === 'zh-CN' ? '探索性运行证据' : 'exploratory runtime evidence');
  const candidateLabel = projection.candidateBuild
    ? (locale === 'zh-CN' ? '候选构建已绑定' : 'candidate build bound')
    : (locale === 'zh-CN' ? '候选构建未绑定' : 'candidate build unbound');
  return <section className="capability-governed-result" data-testid="governed-baseline-result" aria-labelledby="governed-result-heading">
    <div className="capability-governed-result-heading"><div><CheckCircle2 size={22} aria-hidden="true" /><span><small>{locale === 'zh-CN' ? `开发验证 · ${evidenceLabel} · ${candidateLabel}` : `Development verification · ${evidenceLabel} · ${candidateLabel}`}</small><strong id="governed-result-heading">{locale === 'zh-CN' ? '27 项业务检查全部通过' : 'All 27 business checks passed'}</strong></span></div><div className="capability-governed-gate"><AlertTriangle size={17} aria-hidden="true" /><span><small>{locale === 'zh-CN' ? '发布门禁' : 'Release gate'}</small><strong>{locale === 'zh-CN' ? '仍不可验收' : 'Still not accepted'}</strong></span></div></div>
    <div className="capability-governed-metrics"><div><span>{locale === 'zh-CN' ? '业务场景' : 'Business cases'}</span><strong>{projection.caseCount} / 9</strong></div><div><span>{locale === 'zh-CN' ? '业务判定' : 'Business Oracles'}</span><strong>{projection.oraclePassCount} / 9</strong></div><div><span>{locale === 'zh-CN' ? '业务断言' : 'Business assertions'}</span><strong>{projection.businessCheckPassCount} / {projection.businessCheckCount}</strong></div><div><span>{locale === 'zh-CN' ? '真实调用' : 'Real calls'}</span><strong>{projection.realExternalCallCount}</strong></div></div>
    <div className="capability-governed-rounds" aria-label={locale === 'zh-CN' ? '三轮套件运行' : 'Three suite rounds'}>{projection.rounds.map((round) => <div key={round.round}><span>{locale === 'zh-CN' ? `第 ${round.round} 轮` : `Round ${round.round}`}</span><strong>{round.childRunCount} / 9</strong><em className={round.status === 'PASSED' ? 'passed' : 'failed'}>{displayGovernedStatus(round.status, locale)}</em></div>)}</div>
    <div className="capability-governed-case-table-wrap" role="region" tabIndex={0} aria-label={locale === 'zh-CN' ? '九个场景的三轮业务结果' : 'Three-round business results for nine scenarios'}><table className="capability-governed-case-table"><thead><tr><th>{locale === 'zh-CN' ? '业务场景' : 'Business scenario'}</th><th>{locale === 'zh-CN' ? '业务判定' : 'Oracle'}</th><th>{locale === 'zh-CN' ? '第 1 轮' : 'Round 1'}</th><th>{locale === 'zh-CN' ? '第 2 轮' : 'Round 2'}</th><th>{locale === 'zh-CN' ? '第 3 轮' : 'Round 3'}</th></tr></thead><tbody>{projection.cases.map((caseResult) => <tr key={caseResult.caseId}><th scope="row">{governedCaseName(caseResult.caseId, locale)}<small>{shortFingerprint(caseResult.semanticResultFingerprint)}</small></th><td className="capability-oracle-cell"><ShieldCheck size={14} aria-hidden="true" /><span>{locale === 'zh-CN' ? '结果稳定' : 'Stable result'}</span></td>{caseResult.rounds.map((round) => <td key={round.round}><CheckCircle2 size={14} aria-hidden="true" /><span>{round.assertionsPassed} / {round.assertionsEvaluated}</span></td>)}</tr>)}</tbody></table></div>
    <div className="capability-governed-oracle-proofs" aria-label={locale === 'zh-CN' ? '高风险场景专项证明' : 'High-risk case proofs'}>{highRiskCases.map((caseResult) => <div key={caseResult.caseId}><ShieldCheck size={16} aria-hidden="true" /><span><strong>{governedCaseName(caseResult.caseId, locale)}</strong><small>{governedProofLabel(caseResult.proofs[caseResult.proofs.length - 1] ?? '', locale)}</small></span></div>)}</div>
    <div className="capability-governed-limitations"><div><AlertTriangle size={18} aria-hidden="true" /><span><strong>{locale === 'zh-CN' ? '距离发布验收还差什么' : 'What still blocks release acceptance'}</strong><small>{locale === 'zh-CN' ? '这些缺口不会被本次绿色结果自动关闭。' : 'These gaps are not closed by the green development result.'}</small></span></div><ul>{projection.limitations.map((limitation) => <li key={limitation}>{governedLimitationLabel(limitation, locale)}</li>)}</ul></div>
    <details className="capability-technical-details"><summary><ChevronDown size={15} aria-hidden="true" /> {locale === 'zh-CN' ? '验证技术证据' : 'Verification technical evidence'}</summary><dl>{projection.candidateBuild && <><div><dt>Candidate</dt><dd>{projection.candidateBuild.buildRef}@{projection.candidateBuild.revision}</dd></div><div><dt>Artifact fingerprint</dt><dd>{projection.candidateBuild.artifactFingerprint}</dd></div><div><dt>Source commit</dt><dd>{projection.candidateBuild.sourceCommit}</dd></div><div><dt>Execution intent</dt><dd>{projection.candidateIntentFingerprint}</dd></div></>}<div><dt>Suite</dt><dd>{projection.publication.suiteRef.id}@{projection.publication.suiteRef.revision}</dd></div><div><dt>Suite fingerprint</dt><dd>{projection.publication.suiteRef.fingerprint}</dd></div><div><dt>Compilation</dt><dd>{projection.compilationFingerprint}</dd></div><div><dt>Source map</dt><dd>{projection.sourceMapFingerprint}</dd></div><div><dt>Provenance</dt><dd>{projection.provenanceFingerprint}</dd></div>{projection.rounds.map((round) => <div key={round.round}><dt>Round {round.round}</dt><dd>{round.suiteRunId} · {round.evidenceFingerprint}</dd></div>)}{projection.cases.map((caseResult) => <div key={caseResult.caseId}><dt>{caseResult.oracleId}</dt><dd>{caseResult.semanticResultFingerprint}</dd></div>)}</dl></details>
  </section>;
}

function displayGovernedStatus(status: string, locale: 'en' | 'zh-CN'): string { return status === 'PASSED' ? (locale === 'zh-CN' ? '通过' : 'Passed') : status === 'FAILED_CLOSED' ? (locale === 'zh-CN' ? '失败关闭' : 'Failed closed') : status; }
function governedCaseName(caseId: string, locale: 'en' | 'zh-CN'): string { const labels: Record<string, { en: string; 'zh-CN': string }> = { 'case-standard-cancellation-fee': { en: 'Standard cancellation fee', 'zh-CN': '标准取消费' }, 'case-rider-not-responsible': { en: 'Rider not responsible', 'zh-CN': '乘客无责' }, 'case-driver-responsible': { en: 'Driver responsible', 'zh-CN': '司机责任' }, 'case-city-policy-missing': { en: 'City policy missing', 'zh-CN': '城市规则缺失' }, 'case-compensation-history-empty': { en: 'No compensation history', 'zh-CN': '无历史补偿' }, 'case-compensation-history-timeout': { en: 'Compensation history timeout', 'zh-CN': '补偿历史超时' }, 'case-duplicate-cancellation': { en: 'Duplicate cancellation', 'zh-CN': '重复取消' }, 'case-forbidden-write-effect': { en: 'Forbidden write effect', 'zh-CN': '禁止写入' }, 'case-policy-revision-regression': { en: 'Policy revision regression', 'zh-CN': '政策版本回归' } }; return labels[caseId]?.[locale] ?? caseId; }
function governedProofLabel(value: string, locale: 'en' | 'zh-CN'): string { const labels: Record<string, { en: string; 'zh-CN': string }> = { TIMEOUT_FALLBACK_CONFIRMED: { en: 'Timeout fallback and safe continuation confirmed', 'zh-CN': '已确认超时降级并安全继续' }, DUPLICATE_IDEMPOTENCY_CONFIRMED: { en: 'Distinct runs produced the same business result', 'zh-CN': '不同运行产生相同业务结果' }, FORBIDDEN_WRITE_EFFECT_ABSENT: { en: 'No write operator, write trace, or real call observed', 'zh-CN': '未发现写算子、写轨迹或真实调用' } }; return labels[value]?.[locale] ?? value; }
function governedLimitationLabel(value: string, locale: 'en' | 'zh-CN'): string { const labels: Record<string, { en: string; 'zh-CN': string }> = { IMMUTABLE_RELEASE_CANDIDATE_NOT_BOUND: { en: 'This result is not bound to an immutable release candidate build.', 'zh-CN': '当前结果尚未绑定不可变的发布候选构建。' }, RUNTIME_ENVIRONMENT_NOT_ATTESTED: { en: 'The target runtime environment has not supplied a certification attestation.', 'zh-CN': '目标运行环境尚未提供认证证明。' }, CERTIFIABLE_EVIDENCE_NOT_ESTABLISHED: { en: 'The runtime currently classifies these signed runs as exploratory, not certifiable.', 'zh-CN': '运行时当前将这些已签名运行归类为探索性证据，尚非可认证证据。' }, DEPLOYMENT_EGRESS_NOT_OBSERVED: { en: 'Deployment-level network denial and egress observation are still missing.', 'zh-CN': '尚缺部署级断网与出口观测，当前只证明进程内真实调用为 0。' }, OWNER_SIGNOFF_NOT_PRESENT: { en: 'Correctness, Runtime, and QA owners have not signed off.', 'zh-CN': '正确性、Runtime 与 QA 负责人尚未签署。' } }; return labels[value]?.[locale] ?? value; }

function ViewHeading({ kicker, title, description, status }: { kicker: string; title: string; description: string; status: string }) { return <div className="capability-view-heading"><div><p className="capability-kicker">{kicker}</p><h3>{title}</h3><p>{description}</p></div><span className="capability-readonly">{status}</span></div>; }

function ReadinessPanel({ model, text, locale, task, governedBaseline, governedBaselineError, governedBaselineLoading, onNextAction }: { model: CapabilityStudioModel; text: (value: Parameters<typeof localized>[0]) => string; locale: 'en' | 'zh-CN'; task: Task; governedBaseline: GovernedBaselineSuccessProjection | null; governedBaselineError: Error | null; governedBaselineLoading: boolean; onNextAction: () => void }) {
  const missing = locale === 'zh-CN' ? '未提供' : 'Not supplied';
  const baseline = displayBranch(model.baseline, text, locale);
  const tutorial = displayBranch(model.tutorialBranch, text, locale);
  const runtimeStatus = governedBaselineLoading ? (locale === 'zh-CN' ? '运行中' : 'RUNNING') : governedBaseline?.status === 'PASSED' ? (locale === 'zh-CN' ? '开发验证通过' : 'DEVELOPMENT VERIFIED') : governedBaselineError ? (locale === 'zh-CN' ? '本次运行失败' : 'RUN FAILED') : (locale === 'zh-CN' ? '未运行' : 'NOT RUN');
  const readinessCopy = task === 'tool' && governedBaseline?.status === 'PASSED'
    ? governedBaseline.candidateBuild
      ? (locale === 'zh-CN' ? '候选构建与运行意图已经绑定；环境认证、部署出口观测和责任人签署仍阻断发布。' : 'The candidate build and execution intent are bound; environment certification, deployment egress observation, and owner sign-off still block release.')
      : (locale === 'zh-CN' ? '9 项业务判定与 27 项业务断言已通过；候选构建、环境证明和签署仍阻断发布。' : 'Nine business Oracles and 27 business assertions passed; candidate build, environment attestation, and sign-off still block release.')
    : (locale === 'zh-CN' ? '设计资产已加载；发布验收证据尚未闭合。' : 'Design assets are loaded; release acceptance evidence is incomplete.');
  return <aside className="capability-readiness-panel"><div className="capability-panel-heading"><ShieldCheck size={17} aria-hidden="true" /><h3>{locale === 'zh-CN' ? '验收与就绪' : 'Acceptance and readiness'}</h3></div><div className="capability-readiness-callout capability-readiness-warning"><AlertTriangle size={18} aria-hidden="true" /><div><strong>{displayProtocolStatus(text(model.acceptanceStatus), locale)}</strong><p>{readinessCopy}</p></div></div><dl className="capability-readiness-list"><div><dt>{locale === 'zh-CN' ? '当前基线' : 'Canonical baseline'}</dt><dd>{baseline.name}</dd></div><div><dt>{locale === 'zh-CN' ? '教程分支' : 'Tutorial branch'}</dt><dd>{tutorial.name}</dd></div><div><dt>{locale === 'zh-CN' ? '场景分母' : 'Scenario denominator'}</dt><dd>{model.scenarios.length} {locale === 'zh-CN' ? '条' : 'scenarios'}</dd></div><div><dt>{locale === 'zh-CN' ? '运行证据' : 'Runtime evidence'}</dt><dd className={governedBaseline?.status === 'PASSED' ? 'capability-runtime-verified' : 'capability-not-run'}>{runtimeStatus}</dd></div></dl><div className="capability-next-panel"><small>{locale === 'zh-CN' ? '下一步' : 'Next action'}</small><strong>{task === 'overview' ? (locale === 'zh-CN' ? '先确认订单查询契约' : 'Review the order lookup contract') : task === 'tool' ? (locale === 'zh-CN' ? '运行并检查 9 × 3 正确性验证' : 'Run and inspect the 9 x 3 verification') : (locale === 'zh-CN' ? '浏览九条场景数据' : 'Review the nine scenarios')}</strong><button type="button" disabled={task === 'tool' && governedBaselineLoading} onClick={onNextAction}>{locale === 'zh-CN' ? '继续' : 'Continue'} <ArrowRight size={15} aria-hidden="true" /></button></div><details className="capability-technical-details capability-panel-details"><summary><ChevronDown size={15} aria-hidden="true" /> {locale === 'zh-CN' ? '资产技术引用' : 'Asset technical references'}</summary><dl><div><dt>Capability ref</dt><dd>{model.capability.technicalRef ?? missing}</dd></div><div><dt>Protocol</dt><dd>{model.protocolVersion ?? missing}</dd></div><div><dt>Fingerprint</dt><dd>{model.capability.fingerprint ?? missing}</dd></div></dl></details></aside>;
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

function displayScenarioDependency(id: string, locale: 'en' | 'zh-CN'): string {
  const names: Record<string, { en: string; 'zh-CN': string }> = {
    'api-order-lookup': { en: 'Order lookup', 'zh-CN': '订单信息查询' },
    'order-lookup': { en: 'Order lookup', 'zh-CN': '订单信息查询' },
    'api-cancellation-responsibility': { en: 'Responsibility lookup', 'zh-CN': '取消责任判定' },
    'responsibility-lookup': { en: 'Responsibility lookup', 'zh-CN': '取消责任判定' },
    'api-city-pricing-policy': { en: 'City policy lookup', 'zh-CN': '城市计价政策查询' },
    'city-policy-lookup': { en: 'City policy lookup', 'zh-CN': '城市计价政策查询' },
    'api-compensation-history': { en: 'Compensation history lookup', 'zh-CN': '补偿历史查询' },
    'compensation-history-lookup': { en: 'Compensation history lookup', 'zh-CN': '补偿历史查询' },
  };
  return names[id]?.[locale] ?? id;
}

function EmptyEvidence({ locale }: { locale: 'en' | 'zh-CN' }) { return <section className="capability-stage-one-notice"><AlertTriangle size={18} aria-hidden="true" /><div><strong>{locale === 'zh-CN' ? '契约详情未提供' : 'Contract details were not provided'}</strong><p>{locale === 'zh-CN' ? '服务端没有返回足够的业务契约信息，不能用前端猜测补齐。' : 'The server did not provide enough business contract detail; the frontend will not guess.'}</p></div></section>; }

function LoadError({ error, locale, onRetry }: { error: Error | null; locale: 'en' | 'zh-CN'; onRetry: () => void }) {
  const protocolCode = isCapabilityStudioProtocolError(error) ? error.code : 'RG.CAPABILITY_STUDIO.DEMO_PACK_UNAVAILABLE';
  const message = isCapabilityStudioProtocolError(error) ? error.message : (error?.message ?? 'The demo pack could not be loaded.');
  const impact = isCapabilityStudioProtocolError(error) ? error.impact : (locale === 'zh-CN' ? '能力总览、契约和场景数据暂时无法展示。' : 'The capability overview, contract, and scenario data cannot be shown yet.');
  return <main className="capability-studio capability-studio-state capability-error-state" data-testid="capability-load-error"><div className="capability-error-icon"><AlertTriangle size={23} aria-hidden="true" /></div><p className="capability-kicker">{protocolCode}</p><h2>{locale === 'zh-CN' ? '能力设计数据暂时不可用' : 'Capability Studio data is unavailable'}</h2><div className="capability-error-grid"><div><strong>{locale === 'zh-CN' ? '发生了什么' : 'What happened'}</strong><p>{message}</p></div><div><strong>{locale === 'zh-CN' ? '影响' : 'Impact'}</strong><p>{impact}</p></div><div><strong>{locale === 'zh-CN' ? '如何继续' : 'How to continue'}</strong><p>{locale === 'zh-CN' ? '确认服务端已发布 demo pack 后重试。' : 'Confirm that the server publishes the demo pack, then retry.'}</p></div></div><button type="button" className="capability-primary-action" onClick={onRetry}><RefreshCw size={16} aria-hidden="true" /> {locale === 'zh-CN' ? '重试加载' : 'Retry loading'}</button><details className="capability-technical-details"><summary><ChevronDown size={15} aria-hidden="true" /> {locale === 'zh-CN' ? '技术详情' : 'Technical details'}</summary><p>{error?.message ?? 'No additional details.'}</p></details></main>;
}
