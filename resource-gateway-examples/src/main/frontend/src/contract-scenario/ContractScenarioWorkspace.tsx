import { useEffect, useMemo, useRef, useState } from 'react';
import { Plus, X } from 'lucide-react';
import { useI18n } from '../i18n/I18nProvider';
import type { TranslationValues } from '../i18n/i18n';

import useDialogFocusTrap from '../author/accessibility/useDialogFocusTrap';
import type { AuthorCommandAvailability } from '../author/task/taskStateProjection';
import { sampleFromSchemaEnvelope } from '../draftModel';
import {
  BlogeApiRequestError,
  cancelTableSuiteRun,
  fetchTableSuiteRun,
  fetchTableSuiteRunEvents,
  fetchScenarioCompatibility,
  fetchScenarioDraftSet,
  materializeScenarioImportOnServer,
  publishScenarioDraftSet,
  retryFailedTableSuiteRun,
  saveScenarioDraftSet,
  submitTableSuiteRun,
} from '../api';
import type { GraphDraft, SimulationRequest, SimulationResponse } from '../types';
import type {
  AssertionDraft,
  ContractCompatibilityReport,
  ContractDraft,
  ScenarioCaseType,
  ScenarioDraft,
  ScenarioDraftSet,
  StoredScenarioPublication,
  VisualAuthoringWorkspaceBundle,
} from './domain';
import AssertionBuilder from './AssertionBuilder';
import {
  applyAutomaticCompatibilityMigrations,
  rebaseAfterCompatibilityReview,
} from './compatibility';
import ContractSemanticsEditor from './ContractSemanticsEditor';
import DependencyBehaviorEditor from './DependencyBehaviorEditor';
import {
  scenarioAssertionDiff,
  scenarioEvidenceView,
  type EvidenceIssue,
  type ScenarioEvidenceDiagnostic,
  type ScenarioEvidenceTrustContext,
} from './evidenceModel';
import RemediationActionList from '../remediation/RemediationActionList';
import {
  evidenceRemediationActions,
  type RemediationAction,
} from '../remediation/remediationAction';
import {
  compileScenarioEditorSnapshotForSimulation,
  type ScenarioCompilationProof,
} from './scenarioCompiler';
import {
  behaviorForKind,
  captureScenarioEditorSnapshot,
  dependencyNeedsAttention,
} from './scenarioEditorModel';
import SchemaFieldTree from './SchemaFieldTree';
import SchemaValueForm from './SchemaValueForm';
import ScenarioCaseStepRail from './ScenarioCaseStepRail';
import {
  compareScenarioRun,
  scenarioSetIsCurrent,
  type ScenarioComparison,
  type ScenarioNodeOption,
} from './scenarioAuthoring';
import { generateScenarioPreset } from './scenarioPresetGenerator';
import {
  createWorkspaceBundle,
  parseWorkspaceBundle,
} from './workspaceBundle';
import CoverageLensSurface from './coverage/CoverageLensSurface';
import {
  acceptCoverageCandidate,
  type CoverageCandidate,
  type CoverageProjection,
} from './coverage/coverageModel';
import ScenarioMatrixSurface from './table/ScenarioMatrixSurface';
import ScenarioImportWorkbench from './import/ScenarioImportWorkbench';
import type { ScenarioMaterializationResult } from './import/scenarioImportModel';
import {
  MobileScenarioCasePicker,
  MobileScenarioRunSummary,
  MobileScenarioStepNav,
  MobileScenarioTaskBar,
} from './mobile/MobileScenarioTaskSurface';
import {
  MOBILE_TASK_BREAKPOINT,
  projectResponsiveTask,
  projectionIncludes,
  type ScenarioEditorStep,
  type ScenarioTaskIntent,
} from '../ux/responsiveTaskProjection';
import { useCompactTaskViewport } from '../ux/useCompactTaskViewport';
import {
  applyScenarioTableCellEdit,
  buildScenarioTableProjection,
  resolveExactScenarioRunSelection,
  type ScenarioCommandReceipt,
  type ScenarioRunSelectionMode,
  type ScenarioTableColumn,
  type ScenarioTableEvidenceByCase,
  type ScenarioTableProjection,
  type ScenarioTableSelection,
  type TableCaseEvidenceProjection,
} from './table/scenarioTableModel';
import {
  applyTableSuiteRunDelta,
  createTableSuiteBaselineSummary,
  createTableSuiteRunCommand,
  tableSuiteBatchStorageKey,
  tableSuiteBatchIsCompleteBaseline,
  tableSuiteBatchTerminal,
  tableSuiteCommandReceipt,
  tableSuiteDifferentialCounts,
  tableSuiteEvidenceByCase,
  type TableSuiteBaselineSummary,
  type TableSuiteDifferentialCounts,
  type TableSuiteRunBatch,
} from './table/tableSuiteRunModel';

export type WorkspaceTab = 'interface' | 'scenarios' | 'compatibility' | 'evidence';
export type ContractWorkspacePresentation = 'dialog' | 'surface';
export interface ScenarioRunIntent {
  reviewMode: 'EVIDENCE' | 'MATRIX';
}

interface ContractScenarioWorkspaceProps {
  open: boolean;
  graphDraft: GraphDraft;
  contract: ContractDraft | null;
  contractFingerprint: string;
  scenarioDraftSet: ScenarioDraftSet | null;
  nodes: ScenarioNodeOption[];
  lastRun: SimulationResponse | null;
  onScenarioDraftSetChange: (draftSet: ScenarioDraftSet) => void;
  onContractChange: (contract: ContractDraft) => void;
  onImportWorkspace: (bundle: VisualAuthoringWorkspaceBundle) => Promise<void>;
  onSaveGraphDraft: () => Promise<void>;
  onRebase: () => void;
  onRun: (
    request: SimulationRequest,
    intent?: ScenarioRunIntent,
  ) => Promise<SimulationResponse>;
  onClose: () => void;
  targetStored?: boolean;
  contractEditable?: boolean;
  workspaceTransferEnabled?: boolean;
  trustContext?: ScenarioEvidenceTrustContext;
  onSelectEvidenceDiagnostic?: (diagnostic: ScenarioEvidenceDiagnostic) => void;
  onCoordinateChange?: (tab: WorkspaceTab, scenarioId: string) => void;
  onRunEvidence?: (
    scenarioId: string,
    comparison: ScenarioComparison,
    request: SimulationRequest,
    proof: ScenarioCompilationProof,
    response: SimulationResponse,
  ) => boolean | Promise<boolean> | void;
  initialTab?: WorkspaceTab;
  initialScenarioId?: string;
  lastRunScenarioId?: string;
  lastComparison?: ScenarioComparison | null;
  presentation?: ContractWorkspacePresentation;
  runCommand?: AuthorCommandAvailability;
  onRunRemediation?: () => void;
}

/** Dedicated Contract → Scenario → Run Evidence authoring workspace. */
export default function ContractScenarioWorkspace({
  open,
  graphDraft,
  contract,
  contractFingerprint,
  scenarioDraftSet,
  nodes,
  lastRun,
  onScenarioDraftSetChange,
  onContractChange,
  onImportWorkspace,
  onSaveGraphDraft,
  onRebase,
  onRun,
  onClose,
  targetStored,
  contractEditable = true,
  workspaceTransferEnabled = true,
  trustContext,
  onSelectEvidenceDiagnostic,
  onCoordinateChange,
  onRunEvidence,
  initialTab = 'interface',
  initialScenarioId = '',
  lastRunScenarioId = '',
  lastComparison = null,
  presentation = 'dialog',
  runCommand,
  onRunRemediation,
}: ContractScenarioWorkspaceProps) {
  const { t, d } = useI18n();
  const [activeTab, setActiveTab] = useState<WorkspaceTab>(initialTab);
  const [selectedScenarioId, setSelectedScenarioId] = useState(
    initialScenarioId || lastRunScenarioId,
  );
  const [scenarioView, setScenarioView] = useState<'matrix' | 'case' | 'coverage'>(
    (scenarioDraftSet?.scenarios.length ?? 0) > 1 ? 'matrix' : 'case',
  );
  const [scenarioImportOpen, setScenarioImportOpen] = useState(false);
  const [tableSelection, setTableSelection] = useState<ScenarioTableSelection>({
    selectedCaseIds: [],
  });
  const [tableEvidence, setTableEvidence] = useState<ScenarioTableEvidenceByCase>({});
  const [previousRunCaseIds, setPreviousRunCaseIds] = useState<string[]>([]);
  const [runningCaseIds, setRunningCaseIds] = useState<string[]>([]);
  const [tableBatch, setTableBatch] = useState<TableSuiteRunBatch | null>(null);
  const [tableCommandReceipt, setTableCommandReceipt] = useState<ScenarioCommandReceipt | null>(null);
  const [evidenceCommandReceipt, setEvidenceCommandReceipt] = useState<ScenarioCommandReceipt | null>(null);
  const [tableRunError, setTableRunError] = useState('');
  const [baselineBatchId, setBaselineBatchId] = useState('');
  const [tableBaselineSummary, setTableBaselineSummary] = useState<TableSuiteBaselineSummary | null>(null);
  const [running, setRunning] = useState(false);
  const [runResponse, setRunResponse] = useState<SimulationResponse | null>(null);
  const [comparison, setComparison] = useState<ScenarioComparison | null>(null);
  const [compileMessages, setCompileMessages] = useState<string[]>([]);
  const [advancedText, setAdvancedText] = useState('');
  const [advancedError, setAdvancedError] = useState('');
  const [savedSnapshot, setSavedSnapshot] = useState('');
  const [assetBusy, setAssetBusy] = useState<
    'graph' | 'load' | 'save' | 'publish' | 'export' | 'import' | ''
  >('');
  const [assetNotice, setAssetNotice] = useState<{ level: 'ok' | 'error'; message: string } | null>(null);
  const [publication, setPublication] = useState<StoredScenarioPublication | null>(null);
  const [compatibilityReport, setCompatibilityReport] =
    useState<ContractCompatibilityReport | null>(null);
  const [compatibilityLoading, setCompatibilityLoading] = useState(false);
  const [compatibilityError, setCompatibilityError] = useState('');
  const [compatibilityReviewed, setCompatibilityReviewed] = useState(false);
  const workspaceInputRef = useRef<HTMLInputElement>(null);
  const workspaceBodyRef = useRef<HTMLDivElement>(null);
  const workspaceDialogRef = useRef<HTMLElement>(null);
  const autoLoadAttemptRef = useRef('');
  const scenarioChangeRef = useRef(onScenarioDraftSetChange);
  scenarioChangeRef.current = onScenarioDraftSetChange;

  const scenarios = scenarioDraftSet?.scenarios ?? [];
  const selectedScenario = scenarios.find((scenario) => scenario.scenarioId === selectedScenarioId)
    ?? scenarios[0]
    ?? null;
  const current = Boolean(
    contract
      && scenarioDraftSet
      && scenarioSetIsCurrent(
        scenarioDraftSet,
        contract.target.fingerprint,
        contractFingerprint,
      ),
  );
  const externalEvidenceMatchesSelection = Boolean(
    lastRunScenarioId && selectedScenario?.scenarioId === lastRunScenarioId,
  );
  const visibleRun = runResponse ?? (
    !lastRunScenarioId || externalEvidenceMatchesSelection ? lastRun : null
  );
  const visibleComparison = comparison ?? (
    externalEvidenceMatchesSelection ? lastComparison : null
  );
  const serializedDraftSet = scenarioDraftSet ? JSON.stringify(scenarioDraftSet) : '';
  const dirty = Boolean(scenarioDraftSet && savedSnapshot !== serializedDraftSet);
  const graphStored = Boolean(graphDraft.draftId && (graphDraft.revision ?? 0) > 0);
  const assetStored = targetStored ?? graphStored;
  const targetKind = contract?.target.kind ?? 'GRAPH';
  const targetLabel = targetKind === 'OPERATOR' ? 'Operator' : 'Graph';
  const effectiveRunCommand: AuthorCommandAvailability = runCommand ?? (
    running
      ? {
          commandId: 'RUN_CURRENT_SCENARIO',
          state: 'RUNNING', enabled: false, label: 'Running...',
          labelId: 'author.command.running',
          reasonCode: 'RG.AUTHOR.RUN.IN_PROGRESS',
          message: 'The current Scenario run is still in progress.',
          messageId: 'author.blocker.runInProgress',
        }
      : !selectedScenario
        ? {
            commandId: 'RUN_CURRENT_SCENARIO',
            state: 'BLOCKED', enabled: false, label: 'Run & Compare',
            labelId: 'author.command.run',
            reasonCode: 'RG.AUTHOR.RUN.SCENARIO_MISSING',
            message: 'Create a Scenario before running.',
            messageId: 'author.blocker.scenarioMissing',
          }
        : !current
          ? {
              commandId: 'RUN_CURRENT_SCENARIO',
              state: 'BLOCKED', enabled: false, label: 'Run & Compare',
              labelId: 'author.command.run',
              reasonCode: 'RG.AUTHOR.RUN.SCENARIO_STALE',
              message: 'This Scenario targets an older Graph or Contract.',
              messageId: 'author.blocker.scenarioStale',
              remediation: {
                label: 'Review compatibility',
                labelId: 'author.command.reviewCompatibility',
                mode: 'contract',
              },
            }
          : {
              commandId: 'RUN_CURRENT_SCENARIO',
              state: 'READY', enabled: true, label: 'Run & Compare',
              labelId: 'author.command.run',
              reasonCode: '', message: 'Runs and compares the current Scenario.',
              messageId: 'author.command.sandboxRunDetail',
            }
  );
  const remediateRun = () => {
    if (onRunRemediation) {
      onRunRemediation();
      return;
    }
    if (effectiveRunCommand.reasonCode === 'RG.AUTHOR.RUN.SCENARIO_STALE') {
      navigateWorkspace('compatibility');
    } else {
      navigateWorkspace('scenarios');
    }
  };
  const projectionDiagnostics = Array.isArray(
    scenarioDraftSet?.metadata.provenance.projectionDiagnostics,
  )
    ? scenarioDraftSet.metadata.provenance.projectionDiagnostics
    : [];
  const tableProjection = useMemo(() => (
    scenarioDraftSet ? buildScenarioTableProjection(scenarioDraftSet, tableEvidence) : null
  ), [scenarioDraftSet, tableEvidence]);
  const differentialCounts = useMemo(() => (
    scenarioDraftSet ? tableSuiteDifferentialCounts(scenarioDraftSet, tableBaselineSummary) : null
  ), [scenarioDraftSet, tableBaselineSummary]);
  const tableRunStorageKey = scenarioDraftSet
    ? tableSuiteBatchStorageKey(scenarioDraftSet)
    : '';

  const adoptTableBatch = (
    batch: TableSuiteRunBatch,
    priorReceipt: ScenarioCommandReceipt | null = tableCommandReceipt,
  ) => {
    const admittedReceipt = tableSuiteCommandReceipt(batch);
    const receipt = priorReceipt?.correlationId === admittedReceipt.correlationId
      ? { ...admittedReceipt, previewFingerprint: priorReceipt.previewFingerprint }
      : admittedReceipt;
    setTableBatch(batch);
    setTableCommandReceipt(receipt);
    setPreviousRunCaseIds(batch.selection.caseIds);
    setRunningCaseIds(batch.rows
      .filter((row) => row.status === 'QUEUED' || row.status === 'RUNNING')
      .map((row) => row.caseId));
    setTableEvidence((currentEvidence) => ({
      ...currentEvidence,
      ...tableSuiteEvidenceByCase(batch, receipt),
    }));
    setBaselineBatchId((currentBaseline) => {
      const completeBaseline = tableSuiteBatchIsCompleteBaseline(batch);
      const nextBaseline = completeBaseline
        ? batch.batchId
        : currentBaseline;
      const nextSummary = completeBaseline && scenarioDraftSet
        ? createTableSuiteBaselineSummary(batch, scenarioDraftSet)
        : tableBaselineSummary;
      if (completeBaseline) setTableBaselineSummary(nextSummary);
      writeTableRunSession(tableRunStorageKey, batch.batchId, nextBaseline, nextSummary);
      return nextBaseline;
    });
  };

  useDialogFocusTrap({
    open: open && presentation === 'dialog',
    dialogRef: workspaceDialogRef,
    onDismiss: onClose,
  });

  useEffect(() => {
    if (selectedScenario && selectedScenario.scenarioId !== selectedScenarioId) {
      setSelectedScenarioId(selectedScenario.scenarioId);
    }
  }, [selectedScenario?.scenarioId, selectedScenarioId]);

  useEffect(() => {
    if (open && workspaceBodyRef.current) {
      workspaceBodyRef.current.scrollTop = 0;
    }
  }, [activeTab, open]);

  useEffect(() => {
    if (open) {
      setActiveTab(initialTab);
      if (initialScenarioId || lastRunScenarioId) {
        setSelectedScenarioId(initialScenarioId || lastRunScenarioId);
      }
    }
  }, [initialScenarioId, initialTab, lastRunScenarioId, open]);

  useEffect(() => {
    setAdvancedText(selectedScenario ? JSON.stringify(selectedScenario, null, 2) : '');
    setAdvancedError('');
  }, [selectedScenario]);

  useEffect(() => {
    if (!lastRunScenarioId || !lastRun || !lastComparison) return;
    setTableEvidence((currentEvidence) => {
      const existing = currentEvidence[lastRunScenarioId];
      return {
        ...currentEvidence,
        [lastRunScenarioId]: evidenceFromRun(
          lastRunScenarioId,
          lastRun,
          lastComparison,
          existing?.attempt || 1,
          existing?.durationMs ?? 0,
          existing?.commandReceipt,
        ),
      };
    });
    setPreviousRunCaseIds([lastRunScenarioId]);
  }, [lastComparison, lastRun, lastRunScenarioId]);

  useEffect(() => {
    if (!open || !scenarioDraftSet || !assetStored || !tableRunStorageKey) return undefined;
    const retained = readTableRunSession(tableRunStorageKey);
    if (!retained.activeBatchId && !retained.baselineBatchId) return undefined;
    let cancelled = false;
    setBaselineBatchId(retained.baselineBatchId);
    setTableBaselineSummary(retained.baselineSummary);
    if (!retained.activeBatchId) return undefined;
    fetchTableSuiteRun(retained.activeBatchId)
      .then((batch) => {
        if (cancelled || batch.scenarioDraftSetId !== scenarioDraftSet.scenarioDraftSetId) return;
        const exactCurrent = batch.scenarioDraftSetRevision === scenarioDraftSet.revision
          && batch.contractFingerprint === scenarioDraftSet.contractFingerprint
          && batch.target.fingerprint === scenarioDraftSet.target.fingerprint;
        if (exactCurrent) adoptTableBatch(batch);
        else if (tableSuiteBatchIsCompleteBaseline(batch)) {
          const summary = createTableSuiteBaselineSummary(batch, scenarioDraftSet);
          setBaselineBatchId(batch.batchId);
          setTableBaselineSummary(summary);
          writeTableRunSession(tableRunStorageKey, '', batch.batchId, summary);
        }
      })
      .catch((cause: unknown) => {
        if (cancelled) return;
        if (cause instanceof BlogeApiRequestError && cause.status === 404) {
          clearTableRunSession(tableRunStorageKey);
          return;
        }
        setTableRunError(errorMessage(cause));
      });
    return () => { cancelled = true; };
  }, [
    assetStored,
    open,
    scenarioDraftSet?.contractFingerprint,
    scenarioDraftSet?.revision,
    scenarioDraftSet?.scenarioDraftSetId,
    scenarioDraftSet?.target.fingerprint,
    tableRunStorageKey,
  ]);

  useEffect(() => {
    if (!tableBatch || tableSuiteBatchTerminal(tableBatch)) return undefined;
    let cancelled = false;
    const timer = window.setTimeout(() => {
      fetchTableSuiteRunEvents(tableBatch.batchId, tableBatch.revision)
        .then((delta) => {
          if (cancelled) return;
          if (delta.resetRequired) {
            void fetchTableSuiteRun(tableBatch.batchId)
              .then((batch) => { if (!cancelled) adoptTableBatch(batch); })
              .catch((cause: unknown) => { if (!cancelled) setTableRunError(errorMessage(cause)); });
            return;
          }
          adoptTableBatch(applyTableSuiteRunDelta(tableBatch, delta));
        })
        .catch((cause: unknown) => {
          if (!cancelled) setTableRunError(errorMessage(cause));
        });
    }, 350);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [tableBatch?.batchId, tableBatch?.revision, tableBatch?.status]);

  useEffect(() => {
    if (!scenarioDraftSet) {
      setSavedSnapshot('');
      return;
    }
    setSavedSnapshot(scenarioDraftSet.revision > 0 ? JSON.stringify(scenarioDraftSet) : '');
    setPublication(null);
    setAssetNotice(null);
  }, [
    scenarioDraftSet?.scenarioDraftSetId,
    scenarioDraftSet?.target.fingerprint,
  ]);

  useEffect(() => {
    if (!open || activeTab !== 'compatibility' || !scenarioDraftSet
      || scenarioDraftSet.revision < 1 || !assetStored) {
      return undefined;
    }
    let cancelled = false;
    setCompatibilityLoading(true);
    setCompatibilityError('');
    setCompatibilityReviewed(false);
    fetchScenarioCompatibility(
      scenarioDraftSet.scenarioDraftSetId,
      scenarioDraftSet.revision,
    )
      .then((report) => {
        if (!cancelled) setCompatibilityReport(report);
      })
      .catch((cause: unknown) => {
        if (!cancelled) {
          setCompatibilityReport(null);
          setCompatibilityError(errorMessage(cause));
        }
      })
      .finally(() => {
        if (!cancelled) setCompatibilityLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [
    activeTab,
    assetStored,
    contractFingerprint,
    open,
    scenarioDraftSet?.revision,
    scenarioDraftSet?.scenarioDraftSetId,
  ]);

  useEffect(() => {
    if (!open || !assetStored || !scenarioDraftSet || scenarioDraftSet.revision > 0) {
      return undefined;
    }
    const coordinate = `${scenarioDraftSet.scenarioDraftSetId}:${scenarioDraftSet.target.fingerprint}`;
    if (autoLoadAttemptRef.current === coordinate) {
      return undefined;
    }
    autoLoadAttemptRef.current = coordinate;
    let cancelled = false;
    setAssetBusy('load');
    void fetchScenarioDraftSet(scenarioDraftSet.scenarioDraftSetId)
      .then((stored) => {
        if (cancelled) {
          return;
        }
        requireLoadedScenarioCoordinate(stored.draftSet, scenarioDraftSet);
        scenarioChangeRef.current(stored.draftSet);
        setSavedSnapshot(JSON.stringify(stored.draftSet));
        setPublication(null);
        setAssetNotice({
          level: 'ok',
          message: `Loaded Scenario revision ${stored.revision}.`,
        });
      })
      .catch((cause: unknown) => {
        if (!cancelled && (!(cause instanceof BlogeApiRequestError) || cause.status !== 404)) {
          setAssetNotice({ level: 'error', message: errorMessage(cause) });
        }
      })
      .finally(() => {
        if (!cancelled) {
          setAssetBusy('');
        }
      });
    return () => {
      cancelled = true;
    };
  }, [
    assetStored,
    open,
    scenarioDraftSet?.revision,
    scenarioDraftSet?.scenarioDraftSetId,
    scenarioDraftSet?.target.fingerprint,
  ]);

  if (!open || !contract || !scenarioDraftSet) {
    return null;
  }

  const updateSelectedScenario = (update: (scenario: ScenarioDraft) => ScenarioDraft) => {
    if (!selectedScenario) {
      return;
    }
    const nextScenario = update(selectedScenario);
    onScenarioDraftSetChange({
      ...scenarioDraftSet,
      scenarios: scenarioDraftSet.scenarios.map((scenario) => (
        scenario.scenarioId === selectedScenario.scenarioId ? nextScenario : scenario
      )),
      metadata: {
        ...scenarioDraftSet.metadata,
        updatedAt: new Date().toISOString(),
      },
    });
    setRunResponse(null);
    setComparison(null);
    setCompileMessages([]);
    setPublication(null);
    markTableEvidenceStale(selectedScenario.scenarioId, setTableEvidence);
  };

  const updateScenarioFromMatrix = (
    caseId: string,
    column: ScenarioTableColumn,
    value: unknown,
  ) => {
    const next = applyScenarioTableCellEdit(scenarioDraftSet, caseId, column, value);
    if (next === scenarioDraftSet) return;
    onScenarioDraftSetChange(next);
    setRunResponse(null);
    setComparison(null);
    setCompileMessages([]);
    setPublication(null);
    markTableEvidenceStale(caseId, setTableEvidence);
  };

  const acceptGeneratedCoverageCandidate = (
    candidate: CoverageCandidate,
    projection: CoverageProjection,
  ) => {
    const next = acceptCoverageCandidate(scenarioDraftSet, projection, candidate);
    onScenarioDraftSetChange(next);
    setSelectedScenarioId(candidate.proposal.scenarioId);
    setRunResponse(null);
    setComparison(null);
    setCompileMessages([]);
    setPublication(null);
    setAssetNotice({
      level: 'ok',
      message: `${candidate.proposal.name} accepted as a Scenario draft; add its business oracle before promotion.`,
    });
  };

  const acceptScenarioImport = (result: ScenarioMaterializationResult) => {
    onScenarioDraftSetChange(result.draftSet);
    setTableSelection({ selectedCaseIds: result.receipt.materializedScenarioIds });
    setTableEvidence({});
    setPreviousRunCaseIds([]);
    setRunResponse(null);
    setComparison(null);
    setPublication(null);
    setAssetNotice({
      level: result.receipt.rejectedRowCount === 0 ? 'ok' : 'error',
      message: `${result.receipt.acceptedRowCount} imported; ${result.receipt.rejectedRowCount} rejected.`,
    });
  };

  const navigateWorkspace = (
    tab: WorkspaceTab,
    scenarioId = selectedScenario?.scenarioId ?? '',
  ) => {
    setActiveTab(tab);
    onCoordinateChange?.(tab, scenarioId);
  };

  const selectScenario = (scenarioId: string) => {
    setSelectedScenarioId(scenarioId);
    onCoordinateChange?.(activeTab, scenarioId);
  };

  const addScenario = (caseType: ScenarioCaseType = 'GOLDEN') => {
    const usedIds = new Set(scenarios.map((scenario) => scenario.scenarioId));
    let sequence = scenarios.length + 1;
    while (usedIds.has(`scenario-${sequence}`)) {
      sequence += 1;
    }
    const next = generateScenarioPreset({
      sequence,
      caseType,
      graphDraft,
      contract,
      nodes,
    });
    onScenarioDraftSetChange({
      ...scenarioDraftSet,
      scenarios: [...scenarioDraftSet.scenarios, next],
    });
    setSelectedScenarioId(next.scenarioId);
    navigateWorkspace('scenarios', next.scenarioId);
  };

  const removeSelectedScenario = () => {
    if (!selectedScenario) {
      return;
    }
    const nextScenarios = scenarios.filter((scenario) => scenario.scenarioId !== selectedScenario.scenarioId);
    onScenarioDraftSetChange({
      ...scenarioDraftSet,
      scenarios: nextScenarios,
    });
    const nextScenarioId = nextScenarios[0]?.scenarioId ?? '';
    setSelectedScenarioId(nextScenarioId);
    onCoordinateChange?.(activeTab, nextScenarioId);
  };

  const applyCompatibilityMigrations = () => {
    if (!compatibilityReport) return;
    const application = applyAutomaticCompatibilityMigrations(
      scenarioDraftSet,
      compatibilityReport,
      contract,
    );
    onScenarioDraftSetChange(application.draftSet);
    setPublication(null);
    setRunResponse(null);
    setComparison(null);
    setAssetNotice({
      level: application.blockedActionIds.length > 0 ? 'error' : 'ok',
      message: application.blockedActionIds.length > 0
        ? `Applied ${application.appliedActionIds.length} safe migrations; ${application.blockedActionIds.length} require manual resolution.`
        : `Applied ${application.appliedActionIds.length} safe migrations. Review the draft before rebasing.`,
    });
  };

  const resolveCompatibility = () => {
    if (!compatibilityReport) {
      onRebase();
      return;
    }
    const requiresAcknowledgement = compatibilityReport.classification === 'BREAKING'
      || compatibilityReport.classification === 'REVIEW_REQUIRED';
    if (requiresAcknowledgement && !compatibilityReviewed) return;
    onScenarioDraftSetChange(rebaseAfterCompatibilityReview(
      scenarioDraftSet,
      compatibilityReport,
      contract.target,
      contractFingerprint,
    ));
    setPublication(null);
    setRunResponse(null);
    setComparison(null);
    setAssetNotice({
      level: 'ok',
      message: 'Compatibility review recorded. Save and rerun the rebased Scenario revision.',
    });
  };

  const runScenarioClosure = async (
    caseIds: string[],
    openEvidenceAfterRun: boolean,
    commandReceipt?: ScenarioCommandReceipt,
  ) => {
    const closure = scenarios.filter((scenario) => caseIds.includes(scenario.scenarioId));
    if (closure.length === 0) return;
    const admittedReceipt = commandReceipt
      ? { ...commandReceipt, state: 'ADMITTED' as const }
      : undefined;
    if (admittedReceipt) {
      if (openEvidenceAfterRun) setEvidenceCommandReceipt(admittedReceipt);
      else setTableCommandReceipt(admittedReceipt);
    }
    setRunning(true);
    setPreviousRunCaseIds(closure.map((scenario) => scenario.scenarioId));
    setRunningCaseIds(closure.map((scenario) => scenario.scenarioId));
    setTableEvidence((currentEvidence) => ({
      ...currentEvidence,
      ...Object.fromEntries(closure.map((scenario) => [scenario.scenarioId, {
        ...(currentEvidence[scenario.scenarioId] ?? emptyQueuedEvidence(scenario.scenarioId, admittedReceipt)),
        caseId: scenario.scenarioId,
        execution: 'QUEUED' as const,
        assertions: 'NONE' as const,
        freshness: 'CURRENT' as const,
        firstFailure: null,
        commandReceipt: admittedReceipt,
      }])),
    }));
    setCompileMessages([]);
    setComparison(null);
    const messages: string[] = [];
    let focusTarget = '';
    let lastCompleted: { scenario: ScenarioDraft; response: SimulationResponse; comparison: ScenarioComparison } | null = null;
    for (const scenario of closure) {
      const startedAt = performance.now();
      setRunningCaseIds([scenario.scenarioId]);
      setTableEvidence((currentEvidence) => ({
        ...currentEvidence,
        [scenario.scenarioId]: {
          ...(currentEvidence[scenario.scenarioId] ?? emptyQueuedEvidence(scenario.scenarioId, admittedReceipt)),
          execution: 'RUNNING',
          commandReceipt: admittedReceipt,
        },
      }));
      try {
        const snapshot = captureScenarioEditorSnapshot(
          scenarioDraftSet,
          scenario.scenarioId,
          contract,
          nodes,
        );
        const compilation = await compileScenarioEditorSnapshotForSimulation(
          graphDraft,
          snapshot,
          contract.target.fingerprint,
          contractFingerprint,
        );
        if (!compilation.compiled || !compilation.request || !compilation.proof) {
          const diagnosticMessages = compilation.diagnostics.map((diagnostic) => diagnostic.message);
          const message = diagnosticMessages[0]
            ?? 'Scenario compilation did not produce fingerprint closure proof.';
          messages.push(`${scenario.name}: ${message}`);
          setTableEvidence((currentEvidence) => ({
            ...currentEvidence,
            [scenario.scenarioId]: {
              ...emptyQueuedEvidence(scenario.scenarioId, admittedReceipt),
              execution: 'ERROR',
              assertions: 'INCONCLUSIVE',
              durationMs: Math.round(performance.now() - startedAt),
              firstFailure: {
                category: 'COMPILATION',
                target: compilation.diagnostics[0]?.target ?? '/scenario',
                message,
              },
            },
          }));
          if (closure.length === 1) {
            focusTarget = compilation.diagnostics[0]?.target ?? '';
          }
          continue;
        }
        const response = openEvidenceAfterRun
          ? await onRun(compilation.request)
          : await onRun(compilation.request, { reviewMode: 'MATRIX' });
        const nextComparison = compareScenarioRun(scenario, response);
        const durationMs = Math.round(performance.now() - startedAt);
        setRunResponse(response);
        setComparison(nextComparison);
        setTableEvidence((currentEvidence) => ({
          ...currentEvidence,
          [scenario.scenarioId]: evidenceFromRun(
            scenario.scenarioId,
            response,
            nextComparison,
            (currentEvidence[scenario.scenarioId]?.attempt ?? 0) + 1,
            durationMs,
            admittedReceipt,
          ),
        }));
        const evidenceAccepted = onRunEvidence
          ? await onRunEvidence(
              scenario.scenarioId,
              nextComparison,
              compilation.request,
              compilation.proof,
              response,
            )
          : true;
        if (evidenceAccepted === false) {
          const message = 'The Scenario changed during execution. Rerun to create current evidence.';
          messages.push(`${scenario.name}: ${message}`);
          setTableEvidence((currentEvidence) => ({
            ...currentEvidence,
            [scenario.scenarioId]: {
              ...(currentEvidence[scenario.scenarioId] ?? emptyQueuedEvidence(scenario.scenarioId, admittedReceipt)),
              freshness: 'STALE',
              firstFailure: { category: 'COORDINATE', target: '/scenario', message },
            },
          }));
          continue;
        }
        lastCompleted = { scenario, response, comparison: nextComparison };
      } catch (cause: unknown) {
        const message = cause instanceof Error ? cause.message : String(cause);
        messages.push(`${scenario.name}: ${message}`);
        setTableEvidence((currentEvidence) => ({
          ...currentEvidence,
          [scenario.scenarioId]: {
            ...emptyQueuedEvidence(scenario.scenarioId, admittedReceipt),
            execution: 'ERROR',
            assertions: 'INCONCLUSIVE',
            durationMs: Math.round(performance.now() - startedAt),
            firstFailure: { category: 'RUNTIME', target: '/run', message },
          },
        }));
      }
    }
    setCompileMessages(messages);
    setRunningCaseIds([]);
    setRunning(false);
    if (admittedReceipt) {
      const terminalReceipt = { ...admittedReceipt, state: 'TERMINAL' as const };
      if (openEvidenceAfterRun) setEvidenceCommandReceipt(terminalReceipt);
      else setTableCommandReceipt(terminalReceipt);
      setTableEvidence((currentEvidence) => ({
        ...currentEvidence,
        ...Object.fromEntries(closure.map((scenario) => [scenario.scenarioId, {
          ...(currentEvidence[scenario.scenarioId] ?? emptyQueuedEvidence(scenario.scenarioId)),
          commandReceipt: terminalReceipt,
        }])),
      }));
    }
    if (focusTarget) {
      queueMicrotask(() => focusSchemaPath(focusTarget));
    }
    if (openEvidenceAfterRun && lastCompleted) {
      setSelectedScenarioId(lastCompleted.scenario.scenarioId);
      navigateWorkspace('evidence', lastCompleted.scenario.scenarioId);
    }
  };

  const runSelectedScenario = () => {
    if (!selectedScenario || !tableProjection) return;
    const scope = resolveExactScenarioRunSelection(
      tableProjection,
      { selectedCaseIds: [selectedScenario.scenarioId] },
      'SELECTED',
      previousRunCaseIds,
    );
    const receipt = localCommandReceipt(scope, 'LOCAL');
    setEvidenceCommandReceipt(receipt);
    void runScenarioClosure([selectedScenario.scenarioId], true, receipt);
  };

  const runTableSelection = async (mode: ScenarioRunSelectionMode) => {
    if (!scenarioDraftSet || !contract || !current) return;
    const scope = tableProjection
      ? resolveExactScenarioRunSelection(tableProjection, tableSelection, mode, previousRunCaseIds)
      : null;
    if (!assetStored) {
      const localCaseIds = mode === 'SELECTED'
        ? tableSelection.selectedCaseIds
        : mode === 'ALL'
          ? scenarios.map((scenario) => scenario.scenarioId)
          : [];
      if (localCaseIds.length > 0) {
        const receipt = scope
          ? localCommandReceipt({ ...scope, caseIds: localCaseIds }, 'LOCAL')
          : undefined;
        if (receipt) setTableCommandReceipt(receipt);
        await runScenarioClosure(localCaseIds, false, receipt);
      }
      return;
    }
    if (['FAILED', 'CHANGED', 'AFFECTED'].includes(mode) && !baselineBatchId) {
      setTableRunError('Run all once to create the complete baseline required for differential selection.');
      return;
    }
    setTableRunError('');
    try {
      const command = createTableSuiteRunCommand(
        graphDraft,
        contract,
        scenarioDraftSet,
        mode,
        tableSelection.selectedCaseIds,
        baselineBatchId,
      );
      const submittedReceipt = serverSubmittedReceipt(
        command.requestId,
        mode,
        scope,
        differentialCount(mode, differentialCounts),
      );
      setTableCommandReceipt(submittedReceipt);
      adoptTableBatch(await submitTableSuiteRun(command), submittedReceipt);
    } catch (cause: unknown) {
      setTableRunError(cause instanceof BlogeApiRequestError
        && cause.detail.includes('RG.TABLE_RUN.SELECTION_EMPTY')
        ? 'No cases match this differential selection. The complete baseline is already current.'
        : errorMessage(cause));
    }
  };

  const cancelTableBatch = async () => {
    if (!tableBatch || tableSuiteBatchTerminal(tableBatch)) return;
    setTableRunError('');
    try {
      adoptTableBatch(await cancelTableSuiteRun(tableBatch.batchId));
    } catch (cause: unknown) {
      setTableRunError(errorMessage(cause));
    }
  };

  const retryFailedTableBatch = async () => {
    if (!tableBatch || tableBatch.counts.failed === 0) return;
    setTableRunError('');
    try {
      adoptTableBatch(await retryFailedTableSuiteRun(tableBatch.batchId));
    } catch (cause: unknown) {
      setTableRunError(errorMessage(cause));
    }
  };

  const applyAdvancedJson = () => {
    if (!selectedScenario) {
      return;
    }
    try {
      const parsed = JSON.parse(advancedText) as ScenarioDraft;
      if (!parsed.scenarioId?.trim()) {
        throw new Error('scenarioId is required.');
      }
      if (parsed.scenarioId !== selectedScenario.scenarioId
        && scenarios.some((scenario) => scenario.scenarioId === parsed.scenarioId)) {
        throw new Error(`Scenario '${parsed.scenarioId}' already exists.`);
      }
      updateSelectedScenario(() => parsed);
      setSelectedScenarioId(parsed.scenarioId);
      onCoordinateChange?.(activeTab, parsed.scenarioId);
      setAdvancedError('');
    } catch (cause: unknown) {
      setAdvancedError(cause instanceof Error ? cause.message : String(cause));
    }
  };

  const saveGraph = async () => {
    setAssetBusy('graph');
    setAssetNotice(null);
    try {
      await onSaveGraphDraft();
      setAssetNotice({
        level: 'ok',
        message: 'Graph revision saved. Rebase Scenarios to the server Contract coordinate.',
      });
    } catch (cause: unknown) {
      setAssetNotice({ level: 'error', message: errorMessage(cause) });
    } finally {
      setAssetBusy('');
    }
  };

  const loadDraftSet = async () => {
    setAssetBusy('load');
    setAssetNotice(null);
    try {
      const stored = await fetchScenarioDraftSet(scenarioDraftSet.scenarioDraftSetId);
      requireLoadedScenarioCoordinate(stored.draftSet, scenarioDraftSet);
      onScenarioDraftSetChange(stored.draftSet);
      setSavedSnapshot(JSON.stringify(stored.draftSet));
      setPublication(null);
      setAssetNotice({
        level: 'ok',
        message: `Loaded Scenario revision ${stored.revision}.`,
      });
    } catch (cause: unknown) {
      setAssetNotice(isScenarioNotFound(cause)
        ? {
            level: 'ok',
            message: 'No saved Scenario revision yet. The generated example remains available; use Save Scenario to create revision 1.',
          }
        : { level: 'error', message: errorMessage(cause) });
    } finally {
      setAssetBusy('');
    }
  };

  const saveDraftSet = async () => {
    setAssetBusy('save');
    setAssetNotice(null);
    try {
      const stored = await saveScenarioDraftSet(scenarioDraftSet);
      onScenarioDraftSetChange(stored.draftSet);
      setSavedSnapshot(JSON.stringify(stored.draftSet));
      setPublication(null);
      setAssetNotice({
        level: 'ok',
        message: `Scenario revision ${stored.revision} saved by ${stored.savedBy}.`,
      });
    } catch (cause: unknown) {
      setAssetNotice({ level: 'error', message: errorMessage(cause) });
    } finally {
      setAssetBusy('');
    }
  };

  const publishDraftSet = async () => {
    setAssetBusy('publish');
    setAssetNotice(null);
    try {
      const stored = await publishScenarioDraftSet(
        scenarioDraftSet.scenarioDraftSetId,
        scenarioDraftSet.revision,
      );
      setPublication(stored);
      setAssetNotice({
        level: stored.report.status === 'PUBLISHED' ? 'ok' : 'error',
        message: stored.report.status === 'PUBLISHED'
          ? `Published ${stored.report.fixtures.length} fixture revisions and suite ${stored.report.suite?.id ?? 'pending'}.`
          : `Publication ${stored.report.status.toLowerCase()}: ${stored.report.failure.code || 'retry required'}.`,
      });
    } catch (cause: unknown) {
      setAssetNotice({ level: 'error', message: errorMessage(cause) });
    } finally {
      setAssetBusy('');
    }
  };

  const exportWorkspace = async () => {
    setAssetBusy('export');
    setAssetNotice(null);
    try {
      const candidate = createWorkspaceBundle(
        graphDraft,
        contract,
        contractFingerprint,
        scenarioDraftSet,
        publication,
      );
      const text = JSON.stringify(candidate, null, 2);
      await parseWorkspaceBundle(text);
      const url = URL.createObjectURL(new Blob([text], { type: 'application/json' }));
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = `${scenarioDraftSet.scenarioDraftSetId || contract.target.id}-workspace.json`;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(url);
      setAssetNotice({
        level: 'ok',
        message: 'Verified workspace bundle exported without raw credentials.',
      });
    } catch (cause: unknown) {
      setAssetNotice({ level: 'error', message: errorMessage(cause) });
    } finally {
      setAssetBusy('');
    }
  };

  const importWorkspace = async (file: File | undefined) => {
    if (!file) return;
    setAssetBusy('import');
    setAssetNotice(null);
    try {
      const bundle = await parseWorkspaceBundle(await file.text());
      await onImportWorkspace(bundle);
      setAssetNotice({
        level: 'ok',
        message: `Imported ${bundle.scenarioDraftSet.scenarios.length} Scenarios from a verified workspace bundle.`,
      });
    } catch (cause: unknown) {
      setAssetNotice({ level: 'error', message: errorMessage(cause) });
    } finally {
      setAssetBusy('');
      if (workspaceInputRef.current) workspaceInputRef.current.value = '';
    }
  };

  const surfacePresentation = presentation === 'surface';
  const workspaceTabs: Array<[WorkspaceTab, string]> = surfacePresentation
    ? activeTab === 'interface' || activeTab === 'compatibility'
      ? [
          ['interface', 'Contract details'],
          ['compatibility', 'Compatibility'],
        ]
      : []
    : [
        ['interface', 'Interface'],
        ['scenarios', t('Scenarios {count}', { count: scenarios.length })],
        ['compatibility', 'Compatibility'],
        ['evidence', 'Run Evidence'],
      ];
  const contractTask = activeTab === 'interface' || activeTab === 'compatibility';
  const scenarioTask = activeTab === 'scenarios';
  const evidenceTask = activeTab === 'evidence';
  const showGraphSave = targetKind === 'GRAPH'
    && (!surfacePresentation || contractTask || !assetStored);
  const showScenarioAssets = !surfacePresentation || scenarioTask;
  const showPublish = !surfacePresentation || evidenceTask;
  const assetStateLabel = surfacePresentation && contractTask
    ? targetKind === 'OPERATOR'
      ? t('Projected Contract')
      : !assetStored
        ? t('Exploratory draft')
        : t('Graph r{revision}', { revision: contract.target.revision })
    : !assetStored
      ? t('Exploratory draft')
      : dirty
        ? evidenceTask
          ? t('Evidence from unsaved Scenario')
          : t('Unsaved Scenario changes')
        : current
          ? t('Scenario r{revision} saved', { revision: scenarioDraftSet.revision })
          : t('Contract changed');

  return (
    <div
      className={surfacePresentation
        ? 'contract-workspace-surface-host'
        : 'contract-workspace-backdrop'}
      role="presentation"
      onMouseDown={(event) => {
        if (!surfacePresentation && event.target === event.currentTarget) {
          onClose();
        }
      }}
    >
      <section
        ref={workspaceDialogRef}
        className={`contract-workspace ${surfacePresentation ? 'surface' : 'dialog'}`}
        role={surfacePresentation ? 'region' : 'dialog'}
        {...(!surfacePresentation ? { 'aria-modal': true } : {})}
        aria-label={t('Contract and Scenario workspace')}
        tabIndex={surfacePresentation ? undefined : -1}
        data-testid="contract-workspace"
        data-presentation={presentation}
      >
        <header className="contract-workspace-header">
          <div>
            <span>{d(`${targetLabel} Contract`)}</span>
            <h2 title={contract.target.id}>{contract.target.id}</h2>
            <p>
              {t('Revision {revision} · {confidence} projection', {
                revision: contract.target.revision,
                confidence: d(contract.confidence.toLowerCase()),
              })}
            </p>
          </div>
          <div className="contract-workspace-header-actions">
            <span className={`contract-current-badge ${!dirty && current ? 'current' : 'stale'}`}>
              {assetStateLabel}
            </span>
            {showGraphSave && (
              <button
                type="button"
                className="secondary compact"
                onClick={() => void saveGraph()}
                disabled={Boolean(assetBusy)}
              >
                {t(assetBusy === 'graph' ? 'Saving Graph...' : 'Save Graph')}
              </button>
            )}
            {showScenarioAssets && (
              <>
                <button
                  type="button"
                  className="secondary compact"
                  onClick={() => void loadDraftSet()}
                  disabled={Boolean(assetBusy) || !assetStored}
                >
                  {t(assetBusy === 'load' ? 'Loading...' : 'Load Scenario')}
                </button>
                <button
                  type="button"
                  className="secondary compact"
                  onClick={() => void saveDraftSet()}
                  disabled={Boolean(assetBusy) || !assetStored || !current || !dirty || scenarios.length === 0}
                >
                  {t(assetBusy === 'save' ? 'Saving...' : 'Save Scenario')}
                </button>
              </>
            )}
            {showPublish && (
              <button
                type="button"
                className="primary compact"
                onClick={() => void publishDraftSet()}
                disabled={Boolean(assetBusy) || dirty || !current || scenarioDraftSet.revision < 1}
              >
                {t(assetBusy === 'publish' ? 'Publishing...' : 'Publish')}
              </button>
            )}
            {workspaceTransferEnabled && (
              <>
                <button
                  type="button"
                  className="secondary compact"
                  onClick={() => void exportWorkspace()}
                  disabled={Boolean(assetBusy) || !current}
                >
                  {t(assetBusy === 'export' ? 'Exporting...' : 'Export Workspace')}
                </button>
                <button
                  type="button"
                  className="secondary compact"
                  onClick={() => workspaceInputRef.current?.click()}
                  disabled={Boolean(assetBusy)}
                >
                  {t(assetBusy === 'import' ? 'Importing...' : 'Import Workspace')}
                </button>
                <input
                  ref={workspaceInputRef}
                  className="visually-hidden"
                  type="file"
                  accept="application/json,.json"
                  aria-label={t('Workspace bundle file')}
                  onChange={(event) => void importWorkspace(event.target.files?.[0])}
                />
              </>
            )}
            {!surfacePresentation && (
              <button
                type="button"
                className="icon-button"
                title={t('Close Contract workspace')}
                aria-label={t('Close Contract workspace')}
                onClick={onClose}
              >
                <X size={14} aria-hidden="true" />
              </button>
            )}
          </div>
        </header>

        {!current && (
          <div className="contract-stale-banner" role="alert">
            <div>
              <strong>{t('Scenarios target an older {target} or Contract.', { target: d(targetLabel).toLowerCase() })}</strong>
              <span>{t('Review the interface change, then explicitly rebase before running.')}</span>
            </div>
            <button type="button" className="secondary compact" onClick={() => {
              navigateWorkspace('compatibility');
            }}>
              {t('Review compatibility')}
            </button>
          </div>
        )}

        {assetNotice && (
          <div className={`scenario-asset-notice ${assetNotice.level}`} role={assetNotice.level === 'error' ? 'alert' : 'status'}>
            <strong>{t(assetNotice.level === 'ok' ? 'Asset state' : 'Action blocked')}</strong>
            <span>{d(assetNotice.message)}</span>
            {publication && (
              <code title={publication.report.publicationId}>
                {t('{status} · attempt {attempt}', {
                  status: d(publication.report.status),
                  attempt: publication.report.attempt,
                })}
              </code>
            )}
          </div>
        )}

        {projectionDiagnostics.length > 0 && (
          <div className="scenario-asset-notice error" role="alert" data-testid="scenario-projection-diagnostics">
            <strong>{t('{count} legacy cases need review', { count: projectionDiagnostics.length })}</strong>
            <span>{t('Valid rows were migrated to Scenarios. Unprojectable source is preserved below and is never treated as passing evidence.')}</span>
            <details>
              <summary>{t('Advanced migration details')}</summary>
              <pre>{JSON.stringify(projectionDiagnostics, null, 2)}</pre>
            </details>
          </div>
        )}

        {workspaceTabs.length > 0 && (
          <nav className="contract-tabs" role="tablist" aria-label={t('Contract workspace views')}>
            {workspaceTabs.map(([tab, label]) => (
              <button
                type="button"
                role="tab"
                {...(tab === initialTab && !surfacePresentation
                  ? { 'data-dialog-initial-focus': true }
                  : {})}
                aria-selected={activeTab === tab}
                className={activeTab === tab ? 'active' : ''}
                key={tab}
                onClick={() => navigateWorkspace(tab)}
              >
                {d(label)}
              </button>
            ))}
          </nav>
        )}

        <div className="contract-workspace-body" ref={workspaceBodyRef}>
          {activeTab === 'interface' && (
            <InterfaceTab
              contract={contract}
              contractFingerprint={contractFingerprint}
              onContractChange={onContractChange}
              contractEditable={contractEditable}
            />
          )}
          {activeTab === 'scenarios' && (
            <>
            <ScenarioTab
              graphDraft={graphDraft}
              contract={contract}
              scenarioDraftSet={scenarioDraftSet}
              scenarios={scenarios}
              selectedScenario={selectedScenario}
              selectedScenarioId={selectedScenarioId}
              nodes={nodes}
              running={running}
              runCommand={effectiveRunCommand}
              view={scenarioView}
              tableProjection={tableProjection}
              tableEvidence={tableEvidence}
              tableSelection={tableSelection}
              previousRunCaseIds={previousRunCaseIds}
              runningCaseIds={runningCaseIds}
              tableBatch={tableBatch}
              tableCommandReceipt={tableCommandReceipt}
              tableRunError={tableRunError}
              baselineAvailable={Boolean(baselineBatchId && tableBaselineSummary)}
              differentialCounts={differentialCounts}
              importDisabled={!assetStored || !current}
              importDisabledReason={!assetStored
                ? t('Save {target} before importing cases.', { target: d(targetLabel) })
                : t('Rebase Scenarios to the current Contract before importing cases.')}
              compileMessages={compileMessages}
              advancedText={advancedText}
              advancedError={advancedError}
              onAdvancedTextChange={setAdvancedText}
              onApplyAdvancedJson={applyAdvancedJson}
              onSelectScenario={selectScenario}
              onUpdateScenario={updateSelectedScenario}
              onAddScenario={addScenario}
              onImportCases={() => setScenarioImportOpen(true)}
              onRemoveScenario={removeSelectedScenario}
              onRun={runSelectedScenario}
              onRunRemediation={remediateRun}
              onViewChange={setScenarioView}
              onTableSelectionChange={setTableSelection}
              onTableCellEdit={updateScenarioFromMatrix}
              onRunTableSelection={runTableSelection}
              onCancelTableRun={cancelTableBatch}
              onRetryFailedTableRun={retryFailedTableBatch}
              onAcceptCoverageCandidate={acceptGeneratedCoverageCandidate}
            />
            <ScenarioImportWorkbench
              open={scenarioImportOpen}
              draftSet={scenarioDraftSet}
              executeMaterialization={materializeScenarioImportOnServer}
              onMaterialize={acceptScenarioImport}
              onClose={() => setScenarioImportOpen(false)}
            />
            </>
          )}
          {activeTab === 'compatibility' && (
            <CompatibilityTab
              contract={contract}
              scenarioDraftSet={scenarioDraftSet}
              contractFingerprint={contractFingerprint}
              current={current}
              report={compatibilityReport}
              loading={compatibilityLoading}
              error={compatibilityError}
              reviewed={compatibilityReviewed}
              onReviewedChange={setCompatibilityReviewed}
              onApplyMigrations={applyCompatibilityMigrations}
              onResolve={resolveCompatibility}
            />
          )}
          {activeTab === 'evidence' && (
            <EvidenceTab
              response={visibleRun}
              comparison={visibleComparison}
              compileMessages={compileMessages}
              trustContext={trustContext}
              commandReceipt={evidenceCommandReceipt}
              onBackToScenario={() => navigateWorkspace('scenarios')}
              onOpenTab={navigateWorkspace}
              onOpenCompose={onClose}
              onSelectDiagnostic={onSelectEvidenceDiagnostic}
            />
          )}
        </div>
      </section>
    </div>
  );
}

function InterfaceTab({
  contract,
  contractFingerprint,
  onContractChange,
  contractEditable,
}: {
  contract: ContractDraft;
  contractFingerprint: string;
  onContractChange: (contract: ContractDraft) => void;
  contractEditable: boolean;
}) {
  const { t } = useI18n();
  return (
    <div className="contract-interface-tab">
      <div className="contract-interface-summary">
        <span><small>{t('Source')}</small><strong>{contract.source}</strong></span>
        <span><small>{t('Effect')}</small><strong>{contract.executionSemantics.effect}</strong></span>
        <span><small>{t('Compatibility')}</small><strong>{contract.compatibilityPolicy.mode}</strong></span>
        <span><small>{t('Fingerprint')}</small><code title={contractFingerprint}>{shortFingerprint(contractFingerprint)}</code></span>
      </div>
      <div className="contract-schema-columns">
        <SchemaFieldTree envelope={contract.inputSchema} label={t('Input')} rootLabel="ctx" />
        <SchemaFieldTree envelope={contract.outputSchema} label={t('Output')} rootLabel={t('public result')} />
      </div>
      <section className="contract-lineage" aria-label={t('Contract lineage')}>
        <header>
          <div>
            <span>{t('Lineage')}</span>
            <h3>{t('Exact Contract coordinate')}</h3>
          </div>
          <strong>{contract.target.kind}</strong>
        </header>
        <dl>
          <div><dt>{t('Target ID')}</dt><dd title={contract.target.id}>{contract.target.id}</dd></div>
          <div><dt>{t('Revision')}</dt><dd>{contract.target.revision}</dd></div>
          <div>
            <dt>{t('Target fingerprint')}</dt>
            <dd><code title={contract.target.fingerprint}>{shortFingerprint(contract.target.fingerprint)}</code></dd>
          </div>
          <div>
            <dt>{t('Contract fingerprint')}</dt>
            <dd><code title={contractFingerprint}>{shortFingerprint(contractFingerprint)}</code></dd>
          </div>
          <div><dt>{t('Source')}</dt><dd>{contract.source}</dd></div>
          <div><dt>{t('Confidence')}</dt><dd>{contract.confidence}</dd></div>
        </dl>
      </section>
      {contractEditable ? (
        <ContractSemanticsEditor contract={contract} onChange={onContractChange} />
      ) : (
        <div className="contract-stale-banner operator-contract-source" role="note">
          <div>
            <strong>{t('Operator Contract is projected from the catalog.')}</strong>
            <span>{t('Edit the operator library definition to change ports or runtime semantics.')}</span>
          </div>
        </div>
      )}
      <details className="contract-advanced-json">
        <summary>{t('Advanced Contract JSON')}</summary>
        <pre>{JSON.stringify(contract, null, 2)}</pre>
      </details>
    </div>
  );
}

interface ScenarioTabProps {
  graphDraft: GraphDraft;
  contract: ContractDraft;
  scenarioDraftSet: ScenarioDraftSet;
  scenarios: ScenarioDraft[];
  selectedScenario: ScenarioDraft | null;
  selectedScenarioId: string;
  nodes: ScenarioNodeOption[];
  running: boolean;
  runCommand: AuthorCommandAvailability;
  view: 'matrix' | 'case' | 'coverage';
  tableProjection: ScenarioTableProjection | null;
  tableEvidence: ScenarioTableEvidenceByCase;
  tableSelection: ScenarioTableSelection;
  previousRunCaseIds: string[];
  runningCaseIds: string[];
  tableBatch: TableSuiteRunBatch | null;
  tableCommandReceipt: ScenarioCommandReceipt | null;
  tableRunError: string;
  baselineAvailable: boolean;
  differentialCounts: TableSuiteDifferentialCounts | null;
  importDisabled: boolean;
  importDisabledReason: string;
  compileMessages: string[];
  advancedText: string;
  advancedError: string;
  onAdvancedTextChange: (value: string) => void;
  onApplyAdvancedJson: () => void;
  onSelectScenario: (scenarioId: string) => void;
  onUpdateScenario: (update: (scenario: ScenarioDraft) => ScenarioDraft) => void;
  onAddScenario: (caseType?: ScenarioCaseType) => void;
  onImportCases: () => void;
  onRemoveScenario: () => void;
  onRun: () => void;
  onRunRemediation: () => void;
  onViewChange: (view: 'matrix' | 'case' | 'coverage') => void;
  onTableSelectionChange: (selection: ScenarioTableSelection) => void;
  onTableCellEdit: (caseId: string, column: ScenarioTableColumn, value: unknown) => void;
  onRunTableSelection: (mode: ScenarioRunSelectionMode) => void;
  onCancelTableRun: () => void;
  onRetryFailedTableRun: () => void;
  onAcceptCoverageCandidate: (
    candidate: CoverageCandidate,
    projection: CoverageProjection,
  ) => void;
}

function ScenarioTab({
  graphDraft,
  contract,
  scenarioDraftSet,
  scenarios,
  selectedScenario,
  selectedScenarioId,
  nodes,
  running,
  runCommand,
  view,
  tableProjection,
  tableEvidence,
  tableSelection,
  previousRunCaseIds,
  runningCaseIds,
  tableBatch,
  tableCommandReceipt,
  tableRunError,
  baselineAvailable,
  differentialCounts,
  importDisabled,
  importDisabledReason,
  compileMessages,
  advancedText,
  advancedError,
  onAdvancedTextChange,
  onApplyAdvancedJson,
  onSelectScenario,
  onUpdateScenario,
  onAddScenario,
  onImportCases,
  onRemoveScenario,
  onRun,
  onRunRemediation,
  onViewChange,
  onTableSelectionChange,
  onTableCellEdit,
  onRunTableSelection,
  onCancelTableRun,
  onRetryFailedTableRun,
  onAcceptCoverageCandidate,
}: ScenarioTabProps) {
  const { m, t, d } = useI18n();
  const compactTaskViewport = useCompactTaskViewport();
  const [mobileIntent, setMobileIntent] = useState<ScenarioTaskIntent>('RUNNER');
  const [mobileStep, setMobileStep] = useState<ScenarioEditorStep>('GIVEN');
  const selectedEvidence = selectedScenario ? tableEvidence[selectedScenario.scenarioId] : undefined;
  const caseAnchorPrefix = `graph-case-${selectedScenarioId.replace(/[^a-zA-Z0-9_-]/g, '-')}`;
  const reviewState = running
    ? 'RUNNING'
    : runCommand.state === 'BLOCKED'
      ? 'BLOCKED'
      : selectedEvidence?.execution === 'SUCCESS' && selectedEvidence.assertions === 'PASSED'
        ? 'PASSED'
        : selectedEvidence ? 'FAILED' : 'NOT_RUN';
  const responsiveProjection = projectResponsiveTask({
    viewportWidth: compactTaskViewport ? MOBILE_TASK_BREAKPOINT : MOBILE_TASK_BREAKPOINT + 1,
    pointer: 'FINE',
    surface: view === 'matrix'
      ? 'SCENARIO_MATRIX'
      : view === 'coverage' ? 'SCENARIO_COVERAGE' : 'SCENARIO_CASE',
    intent: mobileIntent,
    activeStep: mobileStep,
  });
  const mobileTask = responsiveProjection.layout === 'MOBILE_TASK';
  const changeMobileIntent = (intent: ScenarioTaskIntent) => {
    setMobileIntent(intent);
    if (intent === 'EDITOR' && view !== 'case') onViewChange('case');
  };
  const openMobileEditor = (step: ScenarioEditorStep) => {
    setMobileIntent('EDITOR');
    setMobileStep(step);
    if (view !== 'case') onViewChange('case');
  };
  return (
    <div
      className="scenario-table-workspace"
      data-responsive-task={responsiveProjection.taskId}
      data-responsive-layout={responsiveProjection.layout}
      data-responsive-continuity={responsiveProjection.continuityKey}
      data-result-projection={responsiveProjection.resultProjection}
    >
      <header className="scenario-viewbar">
        <div className="scenario-view-switch" role="group" aria-label={t('Scenario view')}>
          <button
            type="button"
            aria-pressed={view === 'matrix'}
            onClick={() => onViewChange('matrix')}
          >
            {t('Matrix')}
          </button>
          <button
            type="button"
            aria-pressed={view === 'case'}
            onClick={() => onViewChange('case')}
          >
            {t('Case')}
          </button>
          <button
            type="button"
            aria-pressed={view === 'coverage'}
            onClick={() => onViewChange('coverage')}
          >
            {t('Coverage')}
          </button>
        </div>
        <div className="scenario-view-coordinate">
          <span>{contract.target.kind}</span>
          <strong title={contract.target.id}>{contract.target.id}</strong>
          <code>r{contract.target.revision}</code>
        </div>
      </header>

      {mobileTask && (
        <MobileScenarioTaskBar
          projection={responsiveProjection}
          intent={mobileIntent}
          onIntentChange={changeMobileIntent}
        />
      )}

      {view === 'coverage' ? (
        <CoverageLensSurface
          graphDraft={graphDraft}
          contract={contract}
          draftSet={scenarioDraftSet}
          evidenceByCase={tableEvidence}
          disabled={running}
          onAcceptCandidate={onAcceptCoverageCandidate}
        />
      ) : view === 'matrix' && tableProjection ? (
        <ScenarioMatrixSurface
          projection={tableProjection}
          selection={tableSelection}
          previousRunCaseIds={previousRunCaseIds}
          runningCaseIds={runningCaseIds}
          batch={tableBatch}
          commandReceipt={tableCommandReceipt}
          runError={tableRunError}
          baselineAvailable={baselineAvailable}
          differentialCounts={differentialCounts}
          disabled={running || Boolean(tableBatch && !tableSuiteBatchTerminal(tableBatch))}
          runCommand={runCommand}
          importDisabled={importDisabled}
          importDisabledReason={importDisabledReason}
          compactCommands={mobileTask}
          onSelectionChange={onTableSelectionChange}
          onOpenCase={(caseId) => {
            onSelectScenario(caseId);
            onViewChange('case');
          }}
          onCellEdit={onTableCellEdit}
          onAddCase={(caseType) => {
            onAddScenario(caseType);
            onViewChange('case');
          }}
          onImportCases={onImportCases}
          onRunSelection={onRunTableSelection}
          onCancelRun={onCancelTableRun}
          onRetryFailed={onRetryFailedTableRun}
        />
      ) : (
      <div className="scenario-workbench">
      {mobileTask ? (
        <MobileScenarioCasePicker
          scenarios={scenarios}
          selectedScenarioId={selectedScenarioId}
          onSelectScenario={onSelectScenario}
          onAddScenario={() => onAddScenario()}
        />
      ) : (
      <aside className="scenario-list">
        <div className="scenario-list-head">
          <strong>{t('Scenarios')}</strong>
          <button type="button" className="icon-button" title={t('Add Scenario')} aria-label={t('Add Scenario')} onClick={() => onAddScenario()}>
            <Plus size={14} aria-hidden="true" />
          </button>
        </div>
        {scenarios.map((scenario) => (
          <button
            type="button"
            className={`scenario-list-row ${scenario.scenarioId === selectedScenarioId ? 'selected' : ''}`}
            key={scenario.scenarioId}
            onClick={() => onSelectScenario(scenario.scenarioId)}
          >
            <span>{d(scenario.caseType)}</span>
            <strong>{scenario.name}</strong>
            <small>
              {scenario.dependencies.filter((entry) => entry.behavior.kind !== 'REAL').length}
              {' '}{t('controlled dependencies')}
            </small>
          </button>
        ))}
        {scenarios.length === 0 && <p className="scenario-list-empty">{t('No Scenarios yet.')}</p>}
      </aside>
      )}

      <div className="scenario-editor">
        {selectedScenario ? (
          mobileTask && responsiveProjection.taskId === 'CASE_RUN' ? (
            <MobileScenarioRunSummary
              scenario={selectedScenario}
              inputCount={scenarioInputFieldCount(selectedScenario.given.input)}
              controlledDependencyCount={selectedScenario.dependencies.filter((entry) => entry.behavior.kind !== 'REAL').length}
              assertionCount={selectedScenario.then.assertions.length}
              evidence={selectedEvidence}
              runCommand={runCommand}
              onRun={onRun}
              onRunRemediation={onRunRemediation}
              onEditStep={openMobileEditor}
            />
          ) : (
          <>
            <div className="scenario-editor-head">
              <label>
                <span>{t('Name')}</span>
                <input
                  value={selectedScenario.name}
                  onChange={(event) => onUpdateScenario((scenario) => ({
                    ...scenario,
                    name: event.target.value,
                  }))}
                />
              </label>
              <label>
                <span>{t('Case type')}</span>
                <select
                  value={selectedScenario.caseType}
                  onChange={(event) => onUpdateScenario((scenario) => ({
                    ...scenario,
                    caseType: event.target.value as ScenarioDraft['caseType'],
                  }))}
                >
                  <option value="GOLDEN">{t('Golden')}</option>
                  <option value="NEGATIVE">{t('Negative')}</option>
                  <option value="BOUNDARY">{t('Boundary')}</option>
                  <option value="REGRESSION">{t('Regression')}</option>
                  <option value="PROPERTY">{t('Property')}</option>
                </select>
              </label>
              <button type="button" className="secondary compact danger" onClick={onRemoveScenario}>
                {t('Delete')}
              </button>
            </div>

            {mobileTask ? (
              <MobileScenarioStepNav
                activeStep={mobileStep}
                inputCount={scenarioInputFieldCount(selectedScenario.given.input)}
                dependencyCount={selectedScenario.dependencies.filter((entry) => entry.behavior.kind !== 'REAL').length}
                assertionCount={selectedScenario.then.assertions.length}
                onStepChange={setMobileStep}
              />
            ) : (
              <ScenarioCaseStepRail
                anchorPrefix={caseAnchorPrefix}
                givenCount={scenarioInputFieldCount(selectedScenario.given.input)}
                dependencyCount={selectedScenario.dependencies.filter((entry) => entry.behavior.kind !== 'REAL').length}
                assertionCount={selectedScenario.then.assertions.length}
                reviewState={reviewState}
              />
            )}

            <section
              className="scenario-stage"
              id={`${caseAnchorPrefix}-given`}
              hidden={!projectionIncludes(responsiveProjection, 'GIVEN_EDITOR')}
              role={mobileTask ? 'tabpanel' : undefined}
              aria-label={mobileTask ? t('Input') : undefined}
            >
              <div className="scenario-stage-title">
                <span>1</span>
                <div><strong>{t('Given')}</strong><small>{t('Target input from the Contract')}</small></div>
              </div>
              <SchemaValueForm
                envelope={contract.inputSchema}
                value={selectedScenario.given.input}
                onChange={(input) => onUpdateScenario((scenario) => ({
                  ...scenario,
                  given: { input, provenance: 'AUTHORED' },
                }))}
                label={t('Target input')}
              />
            </section>

            <section
              className="scenario-stage"
              id={`${caseAnchorPrefix}-dependencies`}
              hidden={!projectionIncludes(responsiveProjection, 'DEPENDENCY_EDITOR')}
              role={mobileTask ? 'tabpanel' : undefined}
              aria-label={mobileTask ? t('Fixtures') : undefined}
            >
              <div className="scenario-stage-title">
                <span>2</span>
                <div>
                  <strong>{t('Dependencies')}</strong>
                  <small>{t('Override controlled calls; omitted nodes run normally')}</small>
                </div>
                <button
                  type="button"
                  className="secondary compact"
                  onClick={() => onUpdateScenario((scenario) => ({
                    ...scenario,
                    dependencies: [
                      ...scenario.dependencies,
                      newDependency(scenario.dependencies, nodes),
                    ],
                  }))}
                >
                  {t('+ Dependency')}
                </button>
              </div>
              <div className="scenario-dependencies">
                {selectedScenario.dependencies.map((dependency, index) => {
                  return (
                    <DependencyBehaviorEditor
                      dependency={dependency}
                      nodes={nodes}
                      key={dependency.dependencyId}
                      defaultSelectorKind={contract.target.kind === 'OPERATOR' ? 'OPERATOR' : 'NODE'}
                      defaultOpen={dependencyNeedsAttention(dependency)}
                      onChange={(next) => onUpdateScenario((scenario) => ({
                        ...scenario,
                        dependencies: scenario.dependencies.map((entry, candidate) => (
                          candidate === index ? next : entry
                        )),
                      }))}
                      onRemove={() => onUpdateScenario((scenario) => ({
                        ...scenario,
                        dependencies: scenario.dependencies.filter((_, candidate) => (
                          candidate !== index
                        )),
                      }))}
                    />
                  );
                })}
              </div>
            </section>

            <section
              className="scenario-stage"
              id={`${caseAnchorPrefix}-then`}
              hidden={!projectionIncludes(responsiveProjection, 'ASSERTION_EDITOR')}
              role={mobileTask ? 'tabpanel' : undefined}
              aria-label={mobileTask ? t('Expected') : undefined}
            >
              <div className="scenario-stage-title">
                <span>3</span>
                <div><strong>{t('Then')}</strong><small>{t('Compare public output by whole value or path')}</small></div>
                <button
                  type="button"
                  className="secondary compact"
                  onClick={() => onUpdateScenario((scenario) => ({
                    ...scenario,
                    then: {
                      assertions: [
                        ...scenario.then.assertions,
                        newOutputAssertion(scenario.then.assertions.length + 1, contract),
                      ],
                    },
                  }))}
                >
                  {t('+ Assertion')}
                </button>
              </div>
              <div className="scenario-assertions">
                {selectedScenario.then.assertions.map((assertion, index) => (
                  <AssertionBuilder
                    assertion={assertion}
                    contract={contract}
                    dependencies={selectedScenario.dependencies}
                    nodes={nodes}
                    key={assertion.assertionId}
                    onChange={(next) => onUpdateScenario((scenario) => ({
                      ...scenario,
                      then: {
                        assertions: scenario.then.assertions.map((entry, candidate) => (
                          candidate === index ? next : entry
                        )),
                      },
                    }))}
                    onRemove={() => onUpdateScenario((scenario) => ({
                      ...scenario,
                      then: {
                        assertions: scenario.then.assertions.filter((_, candidate) => candidate !== index),
                      },
                    }))}
                  />
                ))}
                {selectedScenario.then.assertions.length === 0 && (
                  <p className="scenario-assertion-empty" role="status">
                    <strong>{m('correctness.verdict.unproven.label')}</strong>
                    <span>{m('correctness.verdict.unproven.detail')}</span>
                  </p>
                )}
              </div>
            </section>

            <section
              className="scenario-stage scenario-review-stage"
              id={`${caseAnchorPrefix}-review`}
              hidden={!projectionIncludes(responsiveProjection, 'REVIEW_EDITOR')}
              role={mobileTask ? 'tabpanel' : undefined}
              aria-label={mobileTask ? t('Run') : undefined}
            >
              <div className="scenario-stage-title">
                <span>4</span>
                <div><strong>{t('Review & run')}</strong><small>{t('Confirm the oracle, then validate this Case')}</small></div>
              </div>

              {compileMessages.length > 0 && (
                <div className="scenario-run-errors" role="alert">
                  {compileMessages.map((message, index) => (
                    <span key={`${index}:${message}`}>{message}</span>
                  ))}
                </div>
              )}

              <details className="contract-advanced-json">
                <summary>{t('Advanced Scenario JSON')}</summary>
                <textarea
                  aria-label={t('Advanced Scenario JSON')}
                  value={advancedText}
                  onChange={(event) => onAdvancedTextChange(event.target.value)}
                  rows={18}
                />
                {advancedError && <p className="scenario-run-errors">{advancedError}</p>}
                <button type="button" className="secondary compact" onClick={onApplyAdvancedJson}>
                  {t('Apply valid JSON')}
                </button>
              </details>

              <footer className="scenario-run-bar">
                <div>
                  <strong>{selectedScenario.name}</strong>
                  <span>{t('{dependencies} dependencies · {assertions} assertions', {
                    dependencies: selectedScenario.dependencies.length,
                    assertions: selectedScenario.then.assertions.length,
                  })}</span>
                  {runCommand.state === 'BLOCKED' && (
                    <span className="scenario-command-explanation" id="scenario-run-blocker" role="status">
                      {runCommand.messageId ? m(runCommand.messageId) : d(runCommand.message)}
                      {runCommand.remediation && (
                        <button type="button" onClick={onRunRemediation}>
                          {runCommand.remediation.labelId
                            ? m(runCommand.remediation.labelId)
                            : d(runCommand.remediation.label)}
                        </button>
                      )}
                    </span>
                  )}
                </div>
                <button
                  type="button"
                  className="primary"
                  onClick={onRun}
                  disabled={!runCommand.enabled}
                  data-testid="scenario-run"
                  data-command-scope="CASE"
                  data-scope-count="1"
                  data-scope-target={selectedScenario.scenarioId}
                  data-scope-fingerprint={runCommand.scope?.fingerprint ?? ''}
                  aria-describedby={runCommand.state === 'BLOCKED' ? 'scenario-run-blocker' : undefined}
                >
                  {runCommand.state === 'RUNNING'
                    ? t('Running current case...')
                    : runCommand.labelId === 'author.command.rerun'
                      ? t('Rerun current case')
                      : t('Run current case')}
                </button>
              </footer>
            </section>
          </>
          )
        ) : (
          <div className="scenario-empty-state">
            <strong>{t('Create the first Scenario')}</strong>
            <button type="button" className="primary" onClick={() => onAddScenario()}>{t('Add Scenario')}</button>
          </div>
        )}
      </div>
      </div>
      )}
    </div>
  );
}

function CompatibilityTab({
  contract,
  scenarioDraftSet,
  contractFingerprint,
  current,
  report,
  loading,
  error,
  reviewed,
  onReviewedChange,
  onApplyMigrations,
  onResolve,
}: {
  contract: ContractDraft;
  scenarioDraftSet: ScenarioDraftSet;
  contractFingerprint: string;
  current: boolean;
  report: ContractCompatibilityReport | null;
  loading: boolean;
  error: string;
  reviewed: boolean;
  onReviewedChange: (reviewed: boolean) => void;
  onApplyMigrations: () => void;
  onResolve: () => void;
}) {
  const { t, d } = useI18n();
  const checks = [
    {
      label: t('{target} target', { target: t(contract.target.kind === 'OPERATOR' ? 'Operator' : 'Graph') }),
      current: scenarioDraftSet.target.fingerprint === contract.target.fingerprint,
      expected: contract.target.fingerprint,
      actual: scenarioDraftSet.target.fingerprint,
    },
    {
      label: t('Contract'),
      current: scenarioDraftSet.contractFingerprint === contractFingerprint,
      expected: contractFingerprint,
      actual: scenarioDraftSet.contractFingerprint,
    },
  ];
  const safeMigrations = report?.migrations.filter((migration) => migration.automatic) ?? [];
  const manualMigrations = report?.migrations.filter((migration) => !migration.automatic) ?? [];
  const requiresAcknowledgement = report?.classification === 'BREAKING'
    || report?.classification === 'REVIEW_REQUIRED';
  const reportCurrent = Boolean(
    report
      && report.scenarioDraftSetId === scenarioDraftSet.scenarioDraftSetId
      && report.scenarioRevision === scenarioDraftSet.revision
      && report.currentContractFingerprint === contractFingerprint,
  );
  return (
    <div className="compatibility-workbench">
      <header>
        <span className={`contract-current-badge ${current ? 'current' : 'stale'}`}>
          {d(report?.classification ?? (current ? 'UNCHANGED' : 'REVIEW REQUIRED'))}
        </span>
        <h3>{t('{mode} compatibility policy', { mode: contract.compatibilityPolicy.mode })}</h3>
        <p>{t('Unknown semantics block automatic migration; a rebase never claims a test pass.')}</p>
      </header>
      <div className="compatibility-table">
        {checks.map((check) => (
          <div className="compatibility-row" key={check.label}>
            <strong>{check.label}</strong>
            <span className={check.current ? 'current' : 'stale'}>
              {t(check.current ? 'Current' : 'Stale')}
            </span>
            <code title={check.actual}>{t('Scenario')} {shortFingerprint(check.actual)}</code>
            <code title={check.expected}>{t('Current')} {shortFingerprint(check.expected)}</code>
          </div>
        ))}
      </div>
      {loading && (
        <div className="compatibility-report-state" role="status">
          <strong>{t('Analyzing retained Contract baseline...')}</strong>
        </div>
      )}
      {error && (
        <div className="compatibility-report-state error" role="alert">
          <strong>{t('Compatibility report unavailable')}</strong>
          <span>{error}</span>
        </div>
      )}
      {!loading && !error && scenarioDraftSet.revision < 1 && (
        <>
          <div className="compatibility-report-state">
            <strong>{t('Review this local draft before establishing its first baseline')}</strong>
            <span>{t('Semantic comparison starts after revision 1; the current draft has no retained Contract snapshot.')}</span>
          </div>
          {!current && (
            <section className="compatibility-resolution">
              <label>
                <input
                  type="checkbox"
                  checked={reviewed}
                  onChange={(event) => onReviewedChange(event.target.checked)}
                />
                <span>{t('I reviewed the current Contract and this unsaved local Scenario.')}</span>
              </label>
              <button
                type="button"
                className="primary"
                disabled={!reviewed}
                onClick={onResolve}
              >
                {t('Rebase local draft')}
              </button>
              <small>{t('Saving the rebased draft creates revision 1 and its immutable Contract baseline.')}</small>
            </section>
          )}
        </>
      )}
      {report && reportCurrent && (
        <>
          <div className="compatibility-summary">
            <span><small>{t('Findings')}</small><strong>{report.findings.length}</strong></span>
            <span><small>{t('Impacted Scenarios')}</small><strong>{report.impactedScenarios.length}</strong></span>
            <span><small>{t('Safe migrations')}</small><strong>{safeMigrations.length}</strong></span>
            <span><small>{t('Manual actions')}</small><strong>{manualMigrations.length}</strong></span>
          </div>
          {report.findings.length > 0 ? (
            <section className="compatibility-findings">
              <h4>{t('Contract findings')}</h4>
              {report.findings.map((finding) => (
                <article className="compatibility-finding" key={finding.findingId}>
                  <span className={`compatibility-severity ${finding.classification.toLowerCase()}`}>
                    {d(finding.classification)}
                  </span>
                  <div>
                    <strong>{d(finding.message)}</strong>
                    <code>{finding.scope} {finding.previousPath && `${finding.previousPath} -> `}{finding.path || '/'}</code>
                  </div>
                  <small>{finding.findingId}</small>
                </article>
              ))}
            </section>
          ) : (
            <div className="compatibility-report-state current">
              <strong>{t('No semantic Contract drift')}</strong>
              <span>{t('The retained baseline and current Contract are identical.')}</span>
            </div>
          )}
          {report.impactedScenarios.length > 0 && (
            <section className="compatibility-impacts">
              <h4>{t('Scenario impact')}</h4>
              {report.impactedScenarios.map((impact) => (
                <div className="compatibility-impact-row" key={impact.scenarioId}>
                  <strong>{scenarioDraftSet.scenarios.find(
                    (scenario) => scenario.scenarioId === impact.scenarioId,
                  )?.name ?? impact.scenarioId}</strong>
                  <span>{d(impact.status)}</span>
                  <code>{impact.paths.join(', ') || '/'}</code>
                </div>
              ))}
            </section>
          )}
          {report.migrations.length > 0 && (
            <section className="compatibility-migrations">
              <h4>{t('Migration plan')}</h4>
              {report.migrations.map((migration) => (
                <div className="compatibility-migration-row" key={migration.actionId}>
                  <span>{t(migration.automatic ? 'SAFE EDIT' : 'MANUAL')}</span>
                  <div>
                    <strong>{d(migration.kind)}</strong>
                    <small>{d(migration.rationale)}</small>
                  </div>
                  <code>{migration.fromPath && `${migration.fromPath} -> `}{migration.toPath || '/'}</code>
                </div>
              ))}
            </section>
          )}
          {!current && (
            <section className="compatibility-resolution">
              {safeMigrations.length > 0 && (
                <button type="button" className="secondary" onClick={onApplyMigrations}>
                  {t('Apply {count} safe migrations', { count: safeMigrations.length })}
                </button>
              )}
              {requiresAcknowledgement && (
                <label>
                  <input
                    type="checkbox"
                    checked={reviewed}
                    onChange={(event) => onReviewedChange(event.target.checked)}
                  />
                  <span>{t('I reviewed unresolved Contract changes and updated affected Scenario values.')}</span>
                </label>
              )}
              <button
                type="button"
                className="primary"
                disabled={Boolean(requiresAcknowledgement && !reviewed)}
                onClick={onResolve}
              >
                {t('Record review & rebase')}
              </button>
              <small>{t('Save and rerun are still required before publication.')}</small>
            </section>
          )}
        </>
      )}
      <div className="compatibility-unknowns">
        <strong>{t('Semantic facts still unknown')}</strong>
        <span>{t('Effect:')} {contract.executionSemantics.effect}</span>
        <span>{t('Idempotency:')} {contract.executionSemantics.idempotency}</span>
        <span>{t('Streaming:')} {String(contract.executionSemantics.streaming ?? 'UNKNOWN')}</span>
        <span>{t('Durable:')} {String(contract.executionSemantics.durable ?? 'UNKNOWN')}</span>
      </div>
    </div>
  );
}

function EvidenceTab({
  response,
  comparison,
  compileMessages,
  trustContext,
  commandReceipt,
  onBackToScenario,
  onOpenTab,
  onOpenCompose,
  onSelectDiagnostic,
}: {
  response: SimulationResponse | null;
  comparison: ScenarioComparison | null;
  compileMessages: string[];
  trustContext?: ScenarioEvidenceTrustContext;
  commandReceipt?: ScenarioCommandReceipt | null;
  onBackToScenario: () => void;
  onOpenTab: (tab: WorkspaceTab) => void;
  onOpenCompose: () => void;
  onSelectDiagnostic?: (diagnostic: ScenarioEvidenceDiagnostic) => void;
}) {
  const { t, d } = useI18n();
  if (!response) {
    return (
      <div className="scenario-empty-state">
        <strong>{t('No Scenario run yet')}</strong>
        <span>{t('Run the selected Scenario to compare actual and expected output.')}</span>
        <EvidenceCommandReceiptPanel receipt={commandReceipt} />
        {compileMessages.map((message) => <p className="scenario-run-errors" key={message}>{message}</p>)}
        <button type="button" className="primary" onClick={onBackToScenario}>{t('Open Scenarios')}</button>
      </div>
    );
  }
  const evidence = scenarioEvidenceView(response, comparison, trustContext);
  const actions = evidenceRemediationActions(
    evidence,
    trustContext,
    typeof window === 'undefined' ? 'http://localhost/author/' : window.location.href,
  );
  const invokeRemediation = (action: RemediationAction) => {
    if (action.navigation === 'SCENARIOS') {
      onBackToScenario();
      return;
    }
    if (action.navigation === 'INTERFACE') {
      onOpenTab('interface');
      return;
    }
    if (action.navigation === 'COMPOSE') {
      onOpenCompose();
      return;
    }
    if (action.navigation === 'DIAGNOSTIC') {
      const diagnostic = trustContext?.diagnostics?.find(
        (candidate) => candidate.id === action.diagnosticId,
      );
      if (diagnostic && onSelectDiagnostic) {
        onSelectDiagnostic(diagnostic);
        return;
      }
    }
    onBackToScenario();
  };
  return (
    <div className="scenario-evidence" data-testid="scenario-evidence">
      <header className={`scenario-evidence-heading ${evidence.tone}`}>
        <span className={`contract-current-badge ${evidence.tone === 'success' ? 'current' : 'stale'}`}>
          {d(evidence.headline)}
        </span>
        <h3>{response.graphName}</h3>
        <p>{localizedEvidenceText(d, evidence.summary, evidence.summaryValues)}</p>
      </header>

      <EvidenceCommandReceiptPanel receipt={commandReceipt} />

      <RemediationActionList actions={actions} onInvoke={invokeRemediation} />

      <div className="scenario-trust-dimensions" aria-label={t('Evidence trust dimensions')}>
        {evidence.dimensions.map((dimension) => (
          <section
            key={dimension.key}
            data-state={dimension.state}
            data-testid={`scenario-trust:${dimension.key}`}
          >
            <span>{d(dimension.label)}</span>
            <strong>{d(dimension.status)}</strong>
            <small>{localizedEvidenceText(d, dimension.detail, dimension.detailValues)}</small>
          </section>
        ))}
      </div>

      {evidence.blockers.length > 0 && (
        <EvidenceIssueList
          title={t('Blocking findings ({count})', { count: evidence.blockers.length })}
          issues={evidence.blockers}
          tone="danger"
          onSelectDiagnostic={onSelectDiagnostic}
        />
      )}
      {evidence.warnings.length > 0 && (
        <EvidenceIssueList
          title={t('Warnings ({count})', { count: evidence.warnings.length })}
          issues={evidence.warnings}
          tone="warning"
          onSelectDiagnostic={onSelectDiagnostic}
        />
      )}

      {trustContext?.coordinate && (
        <details className="scenario-evidence-technical">
          <summary>{t('Technical coordinates')}</summary>
          <dl className="scenario-evidence-coordinate" data-testid="scenario-evidence-coordinate">
            <div>
              <dt>{t(trustContext.coordinate.targetKind === 'OPERATOR' ? 'Operator' : 'Graph')}</dt>
              <dd>
                {trustContext.coordinate.targetId || trustContext.coordinate.draftId || 'exploratory'}
                {' '}r{trustContext.coordinate.targetRevision ?? trustContext.coordinate.draftRevision}
              </dd>
            </div>
            <div><dt>{t('Draft fingerprint')}</dt><dd><code>{trustContext.coordinate.draftFingerprint || t('not saved')}</code></dd></div>
            <div><dt>{t('Contract')}</dt><dd><code>{trustContext.coordinate.contractFingerprint || t('not checked')}</code></dd></div>
            <div><dt>{t('Scenario')}</dt><dd>{trustContext.coordinate.scenarioId} r{trustContext.coordinate.scenarioRevision}</dd></div>
            <div><dt>{t('Scenario fingerprint')}</dt><dd><code>{trustContext.coordinate.scenarioFingerprint || t('not projected')}</code></dd></div>
            <div><dt>{t('Dependency closure')}</dt><dd><code>{trustContext.coordinate.closureFingerprint || t('not projected')}</code></dd></div>
            <div><dt>{t('Execution request')}</dt><dd><code>{trustContext.coordinate.requestFingerprint || t('not captured')}</code></dd></div>
          </dl>
        </details>
      )}

      {evidence.failedAssertions.length > 0 && (
        <section className="scenario-assertion-evidence failed" data-testid="failed-assertions">
          <header>
            <div>
              <span>{t('Failed assertions')}</span>
              <strong>{t('{count} need repair', { count: evidence.failedAssertions.length })}</strong>
            </div>
            <button type="button" className="secondary compact" onClick={onBackToScenario}>
              {t('Edit assertions')}
            </button>
          </header>
          {evidence.failedAssertions.map((entry) => (
            <AssertionEvidence key={entry.assertionId} entry={entry} />
          ))}
        </section>
      )}

      {evidence.passedAssertions.length > 0 && (
        <details className="scenario-passed-evidence" data-testid="passed-assertions">
          <summary>{t('Passed assertions ({count})', { count: evidence.passedAssertions.length })}</summary>
          <div>
            {evidence.passedAssertions.map((entry) => (
              <AssertionEvidence key={entry.assertionId} entry={entry} />
            ))}
          </div>
        </details>
      )}

      {comparison && comparison.results.length === 0 && (
        <div className="scenario-no-assertions">
          <strong>{t('No business assertions configured')}</strong>
          <span>{t('A successful process run is not sufficient promotion evidence.')}</span>
          <button type="button" className="secondary compact" onClick={onBackToScenario}>
            {t('Add assertion')}
          </button>
        </div>
      )}

      <div className="scenario-evidence-grid">
        <section>
          <strong>{t('Terminal output')}</strong>
          <pre>{JSON.stringify(response.output, null, 2)}</pre>
        </section>
        <section>
          <strong>{t('Node status')}</strong>
          <div className="scenario-node-statuses">
            {Object.entries(response.statusMap).map(([nodeId, status]) => (
              <span key={nodeId}><code>{nodeId}</code><strong>{status}</strong></span>
            ))}
          </div>
        </section>
      </div>
    </div>
  );
}

function EvidenceCommandReceiptPanel({
  receipt,
}: {
  receipt?: ScenarioCommandReceipt | null;
}) {
  const { t, d } = useI18n();
  if (!receipt) return null;
  return (
    <section
      className="scenario-command-receipt"
      data-state={receipt.state}
      data-testid="scenario-evidence-command-receipt"
    >
      <header>
        <span>{t('Command receipt')}</span>
        <strong>{d(receipt.state)}</strong>
      </header>
      <dl>
        <div><dt>{t('Correlation ID')}</dt><dd><code>{receipt.correlationId}</code></dd></div>
        <div><dt>{t('Scope')}</dt><dd>{d(receipt.mode)} · {t('{count} cases', { count: receipt.caseCount })}</dd></div>
        <div><dt>{t('Intent fingerprint')}</dt><dd><code>{receipt.previewFingerprint || t('server resolved')}</code></dd></div>
        <div><dt>{t('Canonical fingerprint')}</dt><dd><code>{receipt.canonicalFingerprint || t('local exact scope')}</code></dd></div>
      </dl>
    </section>
  );
}

function EvidenceIssueList({
  title,
  issues,
  tone,
  onSelectDiagnostic,
}: {
  title: string;
  issues: EvidenceIssue[];
  tone: 'danger' | 'warning';
  onSelectDiagnostic?: (diagnostic: ScenarioEvidenceDiagnostic) => void;
}) {
  const { t, d } = useI18n();
  return (
    <section className={`scenario-evidence-issues ${tone}`}>
      <strong>{title}</strong>
      <ul>
        {issues.map((issue) => (
          <li key={issue.id}>
            <span>
              <b>{localizedEvidenceText(d, issue.message, issue.messageValues)}</b>
              {(issue.occurrences ?? 1) > 1 && (
                <small>{t('{count} occurrences', { count: issue.occurrences ?? 1 })}</small>
              )}
              <details>
                <summary>{t('Technical details')}</summary>
                <code>{issue.scope} · {issue.code}</code>
                {issue.coordinate && <code>{issue.coordinate}</code>}
              </details>
            </span>
            {issue.diagnostic && onSelectDiagnostic && (
              <button
                type="button"
                className="secondary compact"
                onClick={() => onSelectDiagnostic(issue.diagnostic as ScenarioEvidenceDiagnostic)}
              >
                {t('Open source')}
              </button>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}

function AssertionEvidence({
  entry,
}: {
  entry: ScenarioComparison['results'][number];
}) {
  const { t } = useI18n();
  const diff = scenarioAssertionDiff(entry.expected, entry.actual, entry.path || '$');
  return (
    <article className={`scenario-assertion-result ${entry.passed ? 'passed' : 'failed'}`}>
      <header>
        <code>{entry.path || '$'}</code>
        <strong>{t(entry.passed ? 'Pass' : 'Fail')}</strong>
      </header>
      <div>
        <label>
          <span>{t('Expected')}</span>
          <pre>{JSON.stringify(entry.expected, null, 2)}</pre>
        </label>
        <label>
          <span>{t('Actual')}</span>
          <pre>{evidenceValue(entry.actual)}</pre>
        </label>
        <label className="scenario-assertion-diff">
          <span>{t('Diff')}</span>
          {diff.length === 0
            ? <strong>{t('No difference')}</strong>
            : (
              <div>
                {diff.map((row) => (
                  <span key={row.path}>
                    <code>{row.path}</code>
                    <del>{evidenceValue(row.expected)}</del>
                    <ins>{evidenceValue(row.actual)}</ins>
                  </span>
                ))}
              </div>
            )}
        </label>
      </div>
    </article>
  );
}

function evidenceValue(value: unknown): string {
  const serialized = JSON.stringify(value, null, 2);
  return serialized === undefined ? String(value) : serialized;
}

function localizedEvidenceText(
  d: (source: string, values?: TranslationValues) => string,
  source: string,
  values?: TranslationValues,
): string {
  if (!values || !Object.prototype.hasOwnProperty.call(values, 'label')) {
    return d(source, values);
  }
  return d(source, { ...values, label: d(String(values.label)) });
}

function newOutputAssertion(sequence: number, contract: ContractDraft): AssertionDraft {
  return {
    assertionId: `output-${sequence}`,
    scope: 'OUTPUT_PATH',
    nodeId: '',
    fromNodeId: '',
    toNodeId: '',
    path: '',
    operator: 'EQUALS',
    expected: sampleFromSchemaEnvelope(contract.outputSchema),
  };
}

function newDependency(
  dependencies: ScenarioDraft['dependencies'],
  nodes: ScenarioNodeOption[],
): ScenarioDraft['dependencies'][number] {
  const usedIds = new Set(dependencies.map((dependency) => dependency.dependencyId));
  let sequence = dependencies.length + 1;
  while (usedIds.has(`dependency-${sequence}`)) {
    sequence += 1;
  }
  return {
    dependencyId: `dependency-${sequence}`,
    selector: {
      graphPath: '',
      nodeId: nodes[0]?.id ?? '',
      operatorRef: '',
      resourceRef: '',
      functionRef: '',
      attempts: [],
      occurrences: [],
      correlationKey: '',
      pathEquals: {},
    },
    behavior: behaviorForKind('RETURN', nodes[0]),
    consumption: {
      required: true,
      minUses: 1,
      maxUses: 1,
      onExhausted: 'FAIL',
      onUnmatched: 'FAIL',
    },
    schemaCheck: { mode: 'STRICT', waiverReason: '' },
    origin: 'AUTHORED',
  };
}

function localCommandReceipt(
  scope: { mode: ScenarioRunSelectionMode; caseIds: string[]; selectionFingerprint: string },
  source: 'LOCAL',
): ScenarioCommandReceipt {
  return {
    correlationId: commandCorrelationId(),
    source,
    state: 'SUBMITTED',
    mode: scope.mode,
    caseIds: [...scope.caseIds],
    caseCount: scope.caseIds.length,
    previewFingerprint: scope.selectionFingerprint,
    canonicalFingerprint: scope.selectionFingerprint,
    batchId: '',
  };
}

function serverSubmittedReceipt(
  requestId: string,
  mode: ScenarioRunSelectionMode,
  scope: { caseIds: string[]; selectionFingerprint: string } | null,
  differentialCaseCount: number,
): ScenarioCommandReceipt {
  const caseIds = scope?.caseIds ?? [];
  return {
    correlationId: requestId,
    source: 'SERVER',
    state: 'SUBMITTED',
    mode,
    caseIds: [...caseIds],
    caseCount: differentialCaseCount || caseIds.length,
    previewFingerprint: scope?.selectionFingerprint ?? '',
    canonicalFingerprint: '',
    batchId: '',
  };
}

function differentialCount(
  mode: ScenarioRunSelectionMode,
  counts: TableSuiteDifferentialCounts | null,
): number {
  if (!counts) return 0;
  if (mode === 'FAILED') return counts.failed;
  if (mode === 'CHANGED') return counts.changed;
  if (mode === 'AFFECTED') return counts.affected;
  return 0;
}

function commandCorrelationId(): string {
  const randomUuid = globalThis.crypto?.randomUUID;
  return randomUuid
    ? `scenario-${randomUuid.call(globalThis.crypto)}`
    : `scenario-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

function emptyQueuedEvidence(
  caseId: string,
  commandReceipt?: ScenarioCommandReceipt,
): TableCaseEvidenceProjection {
  return {
    caseId,
    runId: '',
    attempt: 0,
    execution: 'QUEUED',
    assertions: 'NONE',
    freshness: 'CURRENT',
    proofStrength: 'SCHEMA',
    subjectMode: 'REAL',
    durationMs: null,
    firstFailure: null,
    commandReceipt,
  };
}

function evidenceFromRun(
  caseId: string,
  response: SimulationResponse,
  comparison: ScenarioComparison,
  attempt: number,
  durationMs: number,
  commandReceipt?: ScenarioCommandReceipt,
): TableCaseEvidenceProjection {
  const executionSucceeded = response.validated && response.compiled && response.success;
  const firstAssertionFailure = comparison.results.find((result) => !result.passed);
  const firstDiagnostic = comparison.diagnostics[0];
  return {
    caseId,
    runId: `local:${caseId}:${attempt}`,
    attempt,
    execution: executionSucceeded ? 'SUCCESS' : 'ERROR',
    assertions: comparison.results.length === 0
      ? 'NONE'
      : comparison.passed ? 'PASSED' : 'FAILED',
    freshness: 'CURRENT',
    proofStrength: response.mockedNodeIds.length > 0 ? 'MOCK' : 'RUNTIME',
    subjectMode: 'REAL',
    durationMs,
    commandReceipt,
    assertionDiffs: comparison.results.map((result) => ({
      assertionId: result.assertionId,
      path: result.path,
      passed: result.passed,
      expected: result.expected,
      actual: result.actual,
      detail: result.detail,
    })),
    firstFailure: firstAssertionFailure
      ? {
          category: 'ASSERTION',
          target: firstAssertionFailure.path || '$',
          message: firstAssertionFailure.detail,
        }
      : firstDiagnostic
        ? {
            category: 'EXECUTION',
            target: firstDiagnostic.target,
            message: firstDiagnostic.message,
          }
        : null,
  };
}

function markTableEvidenceStale(
  caseId: string,
  setEvidence: (
    update: (current: ScenarioTableEvidenceByCase) => ScenarioTableEvidenceByCase,
  ) => void,
): void {
  setEvidence((current) => current[caseId]
    ? {
        ...current,
        [caseId]: { ...current[caseId]!, freshness: 'STALE' },
      }
    : current);
}

function requireLoadedScenarioCoordinate(
  loaded: ScenarioDraftSet,
  expected: ScenarioDraftSet,
): void {
  if (loaded.scenarioDraftSetId !== expected.scenarioDraftSetId
    || loaded.target.kind !== expected.target.kind
    || loaded.target.id !== expected.target.id) {
    throw new Error('Stored Scenario target does not match the open Contract workspace.');
  }
}

function shortFingerprint(fingerprint: string): string {
  return fingerprint ? `${fingerprint.slice(0, 13)}…${fingerprint.slice(-6)}` : 'missing';
}

function scenarioInputFieldCount(value: unknown): number {
  if (Array.isArray(value)) {
    return value.reduce((count, entry) => count + scenarioInputFieldCount(entry), 0);
  }
  if (value !== null && typeof value === 'object') {
    return Object.values(value as Record<string, unknown>)
      .reduce<number>((count, entry) => count + scenarioInputFieldCount(entry), 0);
  }
  return value === undefined ? 0 : 1;
}

function focusSchemaPath(path: string | undefined): void {
  if (!path) {
    return;
  }
  const control = Array.from(
    document.querySelectorAll<HTMLElement>('[data-schema-path]'),
  ).find((candidate) => candidate.dataset.schemaPath === path);
  control?.focus();
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause);
}

function isScenarioNotFound(cause: unknown): boolean {
  return cause instanceof BlogeApiRequestError && cause.status === 404;
}

interface TableRunSession {
  activeBatchId: string;
  baselineBatchId: string;
  baselineSummary: TableSuiteBaselineSummary | null;
}

function readTableRunSession(key: string): TableRunSession {
  if (!key || typeof sessionStorage === 'undefined') {
    return { activeBatchId: '', baselineBatchId: '', baselineSummary: null };
  }
  try {
    const parsed = JSON.parse(sessionStorage.getItem(key) ?? '{}') as Partial<TableRunSession>;
    return {
      activeBatchId: parsed.activeBatchId ?? '',
      baselineBatchId: parsed.baselineBatchId ?? '',
      baselineSummary: validBaselineSummary(parsed.baselineSummary) ? parsed.baselineSummary : null,
    };
  } catch {
    return { activeBatchId: '', baselineBatchId: '', baselineSummary: null };
  }
}

function writeTableRunSession(
  key: string,
  activeBatchId: string,
  baselineBatchId: string,
  baselineSummary: TableSuiteBaselineSummary | null,
): void {
  if (!key || typeof sessionStorage === 'undefined') return;
  try {
    sessionStorage.setItem(key, JSON.stringify({ activeBatchId, baselineBatchId, baselineSummary }));
  } catch {
    // The durable batch remains recoverable by batchId even when browser storage is unavailable.
  }
}

function validBaselineSummary(value: unknown): value is TableSuiteBaselineSummary {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const summary = value as Partial<TableSuiteBaselineSummary>;
  return typeof summary.batchId === 'string'
    && Boolean(summary.target && typeof summary.target === 'object')
    && typeof summary.contractFingerprint === 'string'
    && Boolean(summary.caseFingerprints && typeof summary.caseFingerprints === 'object')
    && Object.values(summary.caseFingerprints ?? {}).every((entry) => typeof entry === 'string')
    && Array.isArray(summary.failedCaseIds)
    && summary.failedCaseIds.every((entry) => typeof entry === 'string');
}

function clearTableRunSession(key: string): void {
  if (!key || typeof sessionStorage === 'undefined') return;
  try {
    sessionStorage.removeItem(key);
  } catch {
    // A blocked storage API must not block the authoring workspace.
  }
}
