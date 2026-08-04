import { useEffect, useMemo, useRef, useState } from 'react';

import useDialogFocusTrap from '../author/accessibility/useDialogFocusTrap';
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
import {
  compareScenarioRun,
  newScenarioDraft,
  scenarioSetIsCurrent,
  type ScenarioComparison,
  type ScenarioNodeOption,
} from './scenarioAuthoring';
import {
  createWorkspaceBundle,
  parseWorkspaceBundle,
} from './workspaceBundle';
import ScenarioMatrixSurface from './table/ScenarioMatrixSurface';
import ScenarioImportWorkbench from './import/ScenarioImportWorkbench';
import type { ScenarioMaterializationResult } from './import/scenarioImportModel';
import {
  applyScenarioTableCellEdit,
  buildScenarioTableProjection,
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
  ) => void;
  initialTab?: WorkspaceTab;
  initialScenarioId?: string;
  lastRunScenarioId?: string;
  lastComparison?: ScenarioComparison | null;
  presentation?: ContractWorkspacePresentation;
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
}: ContractScenarioWorkspaceProps) {
  const [activeTab, setActiveTab] = useState<WorkspaceTab>(initialTab);
  const [selectedScenarioId, setSelectedScenarioId] = useState(
    initialScenarioId || lastRunScenarioId,
  );
  const [scenarioView, setScenarioView] = useState<'matrix' | 'case'>(
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

  const adoptTableBatch = (batch: TableSuiteRunBatch) => {
    setTableBatch(batch);
    setPreviousRunCaseIds(batch.selection.caseIds);
    setRunningCaseIds(batch.rows
      .filter((row) => row.status === 'QUEUED' || row.status === 'RUNNING')
      .map((row) => row.caseId));
    setTableEvidence((currentEvidence) => ({
      ...currentEvidence,
      ...tableSuiteEvidenceByCase(batch),
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
    setTableEvidence((currentEvidence) => ({
      ...currentEvidence,
      [lastRunScenarioId]: evidenceFromRun(lastRunScenarioId, lastRun, lastComparison, 1, 0),
    }));
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

  const addScenario = () => {
    const usedIds = new Set(scenarios.map((scenario) => scenario.scenarioId));
    let sequence = scenarios.length + 1;
    while (usedIds.has(`scenario-${sequence}`)) {
      sequence += 1;
    }
    const next = newScenarioDraft(sequence, graphDraft, nodes);
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

  const runScenarioClosure = async (caseIds: string[], openEvidenceAfterRun: boolean) => {
    const closure = scenarios.filter((scenario) => caseIds.includes(scenario.scenarioId));
    if (closure.length === 0) return;
    setRunning(true);
    setPreviousRunCaseIds(closure.map((scenario) => scenario.scenarioId));
    setRunningCaseIds(closure.map((scenario) => scenario.scenarioId));
    setTableEvidence((currentEvidence) => ({
      ...currentEvidence,
      ...Object.fromEntries(closure.map((scenario) => [scenario.scenarioId, {
        ...(currentEvidence[scenario.scenarioId] ?? emptyQueuedEvidence(scenario.scenarioId)),
        caseId: scenario.scenarioId,
        execution: 'QUEUED' as const,
        assertions: 'NONE' as const,
        freshness: 'CURRENT' as const,
        firstFailure: null,
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
          ...(currentEvidence[scenario.scenarioId] ?? emptyQueuedEvidence(scenario.scenarioId)),
          execution: 'RUNNING',
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
              ...emptyQueuedEvidence(scenario.scenarioId),
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
          ),
        }));
        onRunEvidence?.(
          scenario.scenarioId,
          nextComparison,
          compilation.request,
          compilation.proof,
        );
        lastCompleted = { scenario, response, comparison: nextComparison };
      } catch (cause: unknown) {
        const message = cause instanceof Error ? cause.message : String(cause);
        messages.push(`${scenario.name}: ${message}`);
        setTableEvidence((currentEvidence) => ({
          ...currentEvidence,
          [scenario.scenarioId]: {
            ...emptyQueuedEvidence(scenario.scenarioId),
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
    if (focusTarget) {
      queueMicrotask(() => focusSchemaPath(focusTarget));
    }
    if (openEvidenceAfterRun && lastCompleted) {
      setSelectedScenarioId(lastCompleted.scenario.scenarioId);
      navigateWorkspace('evidence', lastCompleted.scenario.scenarioId);
    }
  };

  const runSelectedScenario = () => {
    if (selectedScenario) void runScenarioClosure([selectedScenario.scenarioId], true);
  };

  const runTableSelection = async (mode: ScenarioRunSelectionMode) => {
    if (!scenarioDraftSet || !contract || !assetStored || !current) return;
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
      adoptTableBatch(await submitTableSuiteRun(command));
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
        ['scenarios', `Scenarios ${scenarios.length}`],
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
      ? 'Projected Contract'
      : !assetStored
        ? 'Exploratory draft'
        : `Graph r${contract.target.revision}`
    : !assetStored
      ? 'Exploratory draft'
      : dirty
        ? evidenceTask
          ? 'Evidence from unsaved Scenario'
          : 'Unsaved Scenario changes'
        : current
          ? `Scenario r${scenarioDraftSet.revision} saved`
          : 'Contract changed';

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
        aria-label="Contract and Scenario workspace"
        tabIndex={surfacePresentation ? undefined : -1}
        data-testid="contract-workspace"
        data-presentation={presentation}
      >
        <header className="contract-workspace-header">
          <div>
            <span>{targetLabel} Contract</span>
            <h2 title={contract.target.id}>{contract.target.id}</h2>
            <p>
              Revision {contract.target.revision} · {contract.confidence.toLowerCase()} projection
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
                {assetBusy === 'graph' ? 'Saving Graph...' : 'Save Graph'}
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
                  {assetBusy === 'load' ? 'Loading...' : 'Load Scenario'}
                </button>
                <button
                  type="button"
                  className="secondary compact"
                  onClick={() => void saveDraftSet()}
                  disabled={Boolean(assetBusy) || !assetStored || !current || !dirty || scenarios.length === 0}
                >
                  {assetBusy === 'save' ? 'Saving...' : 'Save Scenario'}
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
                {assetBusy === 'publish' ? 'Publishing...' : 'Publish'}
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
                  {assetBusy === 'export' ? 'Exporting...' : 'Export Workspace'}
                </button>
                <button
                  type="button"
                  className="secondary compact"
                  onClick={() => workspaceInputRef.current?.click()}
                  disabled={Boolean(assetBusy)}
                >
                  {assetBusy === 'import' ? 'Importing...' : 'Import Workspace'}
                </button>
                <input
                  ref={workspaceInputRef}
                  className="visually-hidden"
                  type="file"
                  accept="application/json,.json"
                  aria-label="Workspace bundle file"
                  onChange={(event) => void importWorkspace(event.target.files?.[0])}
                />
              </>
            )}
            {!surfacePresentation && (
              <button
                type="button"
                className="icon-button"
                title="Close Contract workspace"
                aria-label="Close Contract workspace"
                onClick={onClose}
              >
                ×
              </button>
            )}
          </div>
        </header>

        {!current && (
          <div className="contract-stale-banner" role="alert">
            <div>
              <strong>Scenarios target an older {targetLabel.toLowerCase()} or Contract.</strong>
              <span>Review the interface change, then explicitly rebase before running.</span>
            </div>
            <button type="button" className="secondary compact" onClick={() => {
              navigateWorkspace('compatibility');
            }}>
              Review compatibility
            </button>
          </div>
        )}

        {assetNotice && (
          <div className={`scenario-asset-notice ${assetNotice.level}`} role={assetNotice.level === 'error' ? 'alert' : 'status'}>
            <strong>{assetNotice.level === 'ok' ? 'Asset state' : 'Action blocked'}</strong>
            <span>{assetNotice.message}</span>
            {publication && (
              <code title={publication.report.publicationId}>
                {publication.report.status} · attempt {publication.report.attempt}
              </code>
            )}
          </div>
        )}

        {projectionDiagnostics.length > 0 && (
          <div className="scenario-asset-notice error" role="alert" data-testid="scenario-projection-diagnostics">
            <strong>{projectionDiagnostics.length} legacy case{projectionDiagnostics.length === 1 ? '' : 's'} need review</strong>
            <span>
              Valid rows were migrated to Scenarios. Unprojectable source is preserved below and is
              never treated as passing evidence.
            </span>
            <details>
              <summary>Advanced migration details</summary>
              <pre>{JSON.stringify(projectionDiagnostics, null, 2)}</pre>
            </details>
          </div>
        )}

        {workspaceTabs.length > 0 && (
          <nav className="contract-tabs" role="tablist" aria-label="Contract workspace views">
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
                {label}
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
              scenarios={scenarios}
              selectedScenario={selectedScenario}
              selectedScenarioId={selectedScenarioId}
              nodes={nodes}
              running={running}
              view={scenarioView}
              tableProjection={tableProjection}
              tableSelection={tableSelection}
              previousRunCaseIds={previousRunCaseIds}
              runningCaseIds={runningCaseIds}
              tableBatch={tableBatch}
              tableRunError={tableRunError}
              baselineAvailable={Boolean(baselineBatchId && tableBaselineSummary)}
              differentialCounts={differentialCounts}
              importDisabled={!assetStored || !current}
              importDisabledReason={!assetStored
                ? `Save ${targetLabel} before importing cases.`
                : 'Rebase Scenarios to the current Contract before importing cases.'}
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
              onViewChange={setScenarioView}
              onTableSelectionChange={setTableSelection}
              onTableCellEdit={updateScenarioFromMatrix}
              onRunTableSelection={runTableSelection}
              onCancelTableRun={cancelTableBatch}
              onRetryFailedTableRun={retryFailedTableBatch}
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
  return (
    <div className="contract-interface-tab">
      <div className="contract-interface-summary">
        <span><small>Source</small><strong>{contract.source}</strong></span>
        <span><small>Effect</small><strong>{contract.executionSemantics.effect}</strong></span>
        <span><small>Compatibility</small><strong>{contract.compatibilityPolicy.mode}</strong></span>
        <span><small>Fingerprint</small><code title={contractFingerprint}>{shortFingerprint(contractFingerprint)}</code></span>
      </div>
      <div className="contract-schema-columns">
        <SchemaFieldTree envelope={contract.inputSchema} label="Input" rootLabel="ctx" />
        <SchemaFieldTree envelope={contract.outputSchema} label="Output" rootLabel="public result" />
      </div>
      <section className="contract-lineage" aria-label="Contract lineage">
        <header>
          <div>
            <span>Lineage</span>
            <h3>Exact Contract coordinate</h3>
          </div>
          <strong>{contract.target.kind}</strong>
        </header>
        <dl>
          <div><dt>Target ID</dt><dd title={contract.target.id}>{contract.target.id}</dd></div>
          <div><dt>Revision</dt><dd>{contract.target.revision}</dd></div>
          <div>
            <dt>Target fingerprint</dt>
            <dd><code title={contract.target.fingerprint}>{shortFingerprint(contract.target.fingerprint)}</code></dd>
          </div>
          <div>
            <dt>Contract fingerprint</dt>
            <dd><code title={contractFingerprint}>{shortFingerprint(contractFingerprint)}</code></dd>
          </div>
          <div><dt>Source</dt><dd>{contract.source}</dd></div>
          <div><dt>Confidence</dt><dd>{contract.confidence}</dd></div>
        </dl>
      </section>
      {contractEditable ? (
        <ContractSemanticsEditor contract={contract} onChange={onContractChange} />
      ) : (
        <div className="contract-stale-banner operator-contract-source" role="note">
          <div>
            <strong>Operator Contract is projected from the catalog.</strong>
            <span>Edit the operator library definition to change ports or runtime semantics.</span>
          </div>
        </div>
      )}
      <details className="contract-advanced-json">
        <summary>Advanced Contract JSON</summary>
        <pre>{JSON.stringify(contract, null, 2)}</pre>
      </details>
    </div>
  );
}

interface ScenarioTabProps {
  graphDraft: GraphDraft;
  contract: ContractDraft;
  scenarios: ScenarioDraft[];
  selectedScenario: ScenarioDraft | null;
  selectedScenarioId: string;
  nodes: ScenarioNodeOption[];
  running: boolean;
  view: 'matrix' | 'case';
  tableProjection: ScenarioTableProjection | null;
  tableSelection: ScenarioTableSelection;
  previousRunCaseIds: string[];
  runningCaseIds: string[];
  tableBatch: TableSuiteRunBatch | null;
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
  onAddScenario: () => void;
  onImportCases: () => void;
  onRemoveScenario: () => void;
  onRun: () => void;
  onViewChange: (view: 'matrix' | 'case') => void;
  onTableSelectionChange: (selection: ScenarioTableSelection) => void;
  onTableCellEdit: (caseId: string, column: ScenarioTableColumn, value: unknown) => void;
  onRunTableSelection: (mode: ScenarioRunSelectionMode) => void;
  onCancelTableRun: () => void;
  onRetryFailedTableRun: () => void;
}

function ScenarioTab({
  contract,
  scenarios,
  selectedScenario,
  selectedScenarioId,
  nodes,
  running,
  view,
  tableProjection,
  tableSelection,
  previousRunCaseIds,
  runningCaseIds,
  tableBatch,
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
  onViewChange,
  onTableSelectionChange,
  onTableCellEdit,
  onRunTableSelection,
  onCancelTableRun,
  onRetryFailedTableRun,
}: ScenarioTabProps) {
  return (
    <div className="scenario-table-workspace">
      <header className="scenario-viewbar">
        <div className="scenario-view-switch" role="group" aria-label="Scenario view">
          <button
            type="button"
            aria-pressed={view === 'matrix'}
            onClick={() => onViewChange('matrix')}
          >
            Matrix
          </button>
          <button
            type="button"
            aria-pressed={view === 'case'}
            onClick={() => onViewChange('case')}
          >
            Case
          </button>
        </div>
        <div className="scenario-view-coordinate">
          <span>{contract.target.kind}</span>
          <strong title={contract.target.id}>{contract.target.id}</strong>
          <code>r{contract.target.revision}</code>
        </div>
      </header>

      {view === 'matrix' && tableProjection ? (
        <ScenarioMatrixSurface
          projection={tableProjection}
          selection={tableSelection}
          previousRunCaseIds={previousRunCaseIds}
          runningCaseIds={runningCaseIds}
          batch={tableBatch}
          runError={tableRunError}
          baselineAvailable={baselineAvailable}
          differentialCounts={differentialCounts}
          disabled={running || importDisabled || Boolean(tableBatch && !tableSuiteBatchTerminal(tableBatch))}
          importDisabled={importDisabled}
          importDisabledReason={importDisabledReason}
          onSelectionChange={onTableSelectionChange}
          onOpenCase={(caseId) => {
            onSelectScenario(caseId);
            onViewChange('case');
          }}
          onCellEdit={onTableCellEdit}
          onAddCase={() => {
            onAddScenario();
            onViewChange('case');
          }}
          onImportCases={onImportCases}
          onRunSelection={onRunTableSelection}
          onCancelRun={onCancelTableRun}
          onRetryFailed={onRetryFailedTableRun}
        />
      ) : (
      <div className="scenario-workbench">
      <aside className="scenario-list">
        <div className="scenario-list-head">
          <strong>Scenarios</strong>
          <button type="button" className="icon-button" title="Add Scenario" aria-label="Add Scenario" onClick={onAddScenario}>
            +
          </button>
        </div>
        {scenarios.map((scenario) => (
          <button
            type="button"
            className={`scenario-list-row ${scenario.scenarioId === selectedScenarioId ? 'selected' : ''}`}
            key={scenario.scenarioId}
            onClick={() => onSelectScenario(scenario.scenarioId)}
          >
            <span>{scenario.caseType}</span>
            <strong>{scenario.name}</strong>
            <small>
              {scenario.dependencies.filter((entry) => entry.behavior.kind !== 'REAL').length}
              {' controlled dependencies'}
            </small>
          </button>
        ))}
        {scenarios.length === 0 && <p className="scenario-list-empty">No Scenarios yet.</p>}
      </aside>

      <div className="scenario-editor">
        {selectedScenario ? (
          <>
            <div className="scenario-editor-head">
              <label>
                <span>Name</span>
                <input
                  value={selectedScenario.name}
                  onChange={(event) => onUpdateScenario((scenario) => ({
                    ...scenario,
                    name: event.target.value,
                  }))}
                />
              </label>
              <label>
                <span>Case type</span>
                <select
                  value={selectedScenario.caseType}
                  onChange={(event) => onUpdateScenario((scenario) => ({
                    ...scenario,
                    caseType: event.target.value as ScenarioDraft['caseType'],
                  }))}
                >
                  <option value="GOLDEN">Golden</option>
                  <option value="NEGATIVE">Negative</option>
                  <option value="BOUNDARY">Boundary</option>
                  <option value="REGRESSION">Regression</option>
                  <option value="PROPERTY">Property</option>
                </select>
              </label>
              <button type="button" className="secondary compact danger" onClick={onRemoveScenario}>
                Delete
              </button>
            </div>

            <section className="scenario-stage">
              <div className="scenario-stage-title">
                <span>1</span>
                <div><strong>Given</strong><small>Target input from the Contract</small></div>
              </div>
              <SchemaValueForm
                envelope={contract.inputSchema}
                value={selectedScenario.given.input}
                onChange={(input) => onUpdateScenario((scenario) => ({
                  ...scenario,
                  given: { input, provenance: 'AUTHORED' },
                }))}
                label="Target input"
              />
            </section>

            <section className="scenario-stage">
              <div className="scenario-stage-title">
                <span>2</span>
                <div>
                  <strong>Dependencies</strong>
                  <small>Override controlled calls; omitted nodes run normally</small>
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
                  + Dependency
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

            <section className="scenario-stage">
              <div className="scenario-stage-title">
                <span>3</span>
                <div><strong>Then</strong><small>Compare public output by whole value or path</small></div>
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
                  + Assertion
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
                  <p className="scenario-assertion-empty">Run success is enough until an assertion is added.</p>
                )}
              </div>
            </section>

            {compileMessages.length > 0 && (
              <div className="scenario-run-errors" role="alert">
                {compileMessages.map((message, index) => (
                  <span key={`${index}:${message}`}>{message}</span>
                ))}
              </div>
            )}

            <details className="contract-advanced-json">
              <summary>Advanced Scenario JSON</summary>
              <textarea
                aria-label="Advanced Scenario JSON"
                value={advancedText}
                onChange={(event) => onAdvancedTextChange(event.target.value)}
                rows={18}
              />
              {advancedError && <p className="scenario-run-errors">{advancedError}</p>}
              <button type="button" className="secondary compact" onClick={onApplyAdvancedJson}>
                Apply valid JSON
              </button>
            </details>

            <footer className="scenario-run-bar">
              <div>
                <strong>{selectedScenario.name}</strong>
                <span>{selectedScenario.dependencies.length} dependencies · {selectedScenario.then.assertions.length} assertions</span>
              </div>
              <button
                type="button"
                className="primary"
                onClick={onRun}
                disabled={running}
                data-testid="scenario-run"
              >
                {running ? 'Running...' : 'Run & Compare'}
              </button>
            </footer>
          </>
        ) : (
          <div className="scenario-empty-state">
            <strong>Create the first Scenario</strong>
            <button type="button" className="primary" onClick={onAddScenario}>Add Scenario</button>
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
  const checks = [
    {
      label: `${contract.target.kind === 'OPERATOR' ? 'Operator' : 'Graph'} target`,
      current: scenarioDraftSet.target.fingerprint === contract.target.fingerprint,
      expected: contract.target.fingerprint,
      actual: scenarioDraftSet.target.fingerprint,
    },
    {
      label: 'Contract',
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
          {report?.classification ?? (current ? 'UNCHANGED' : 'REVIEW REQUIRED')}
        </span>
        <h3>{contract.compatibilityPolicy.mode} compatibility policy</h3>
        <p>Unknown semantics block automatic migration; a rebase never claims a test pass.</p>
      </header>
      <div className="compatibility-table">
        {checks.map((check) => (
          <div className="compatibility-row" key={check.label}>
            <strong>{check.label}</strong>
            <span className={check.current ? 'current' : 'stale'}>
              {check.current ? 'Current' : 'Stale'}
            </span>
            <code title={check.actual}>Scenario {shortFingerprint(check.actual)}</code>
            <code title={check.expected}>Current {shortFingerprint(check.expected)}</code>
          </div>
        ))}
      </div>
      {loading && (
        <div className="compatibility-report-state" role="status">
          <strong>Analyzing retained Contract baseline...</strong>
        </div>
      )}
      {error && (
        <div className="compatibility-report-state error" role="alert">
          <strong>Compatibility report unavailable</strong>
          <span>{error}</span>
        </div>
      )}
      {!loading && !error && scenarioDraftSet.revision < 1 && (
        <>
          <div className="compatibility-report-state">
            <strong>Review this local draft before establishing its first baseline</strong>
            <span>Semantic comparison starts after revision 1; the current draft has no retained Contract snapshot.</span>
          </div>
          {!current && (
            <section className="compatibility-resolution">
              <label>
                <input
                  type="checkbox"
                  checked={reviewed}
                  onChange={(event) => onReviewedChange(event.target.checked)}
                />
                <span>I reviewed the current Contract and this unsaved local Scenario.</span>
              </label>
              <button
                type="button"
                className="primary"
                disabled={!reviewed}
                onClick={onResolve}
              >
                Rebase local draft
              </button>
              <small>Saving the rebased draft creates revision 1 and its immutable Contract baseline.</small>
            </section>
          )}
        </>
      )}
      {report && reportCurrent && (
        <>
          <div className="compatibility-summary">
            <span><small>Findings</small><strong>{report.findings.length}</strong></span>
            <span><small>Impacted Scenarios</small><strong>{report.impactedScenarios.length}</strong></span>
            <span><small>Safe migrations</small><strong>{safeMigrations.length}</strong></span>
            <span><small>Manual actions</small><strong>{manualMigrations.length}</strong></span>
          </div>
          {report.findings.length > 0 ? (
            <section className="compatibility-findings">
              <h4>Contract findings</h4>
              {report.findings.map((finding) => (
                <article className="compatibility-finding" key={finding.findingId}>
                  <span className={`compatibility-severity ${finding.classification.toLowerCase()}`}>
                    {finding.classification}
                  </span>
                  <div>
                    <strong>{finding.message}</strong>
                    <code>{finding.scope} {finding.previousPath && `${finding.previousPath} -> `}{finding.path || '/'}</code>
                  </div>
                  <small>{finding.findingId}</small>
                </article>
              ))}
            </section>
          ) : (
            <div className="compatibility-report-state current">
              <strong>No semantic Contract drift</strong>
              <span>The retained baseline and current Contract are identical.</span>
            </div>
          )}
          {report.impactedScenarios.length > 0 && (
            <section className="compatibility-impacts">
              <h4>Scenario impact</h4>
              {report.impactedScenarios.map((impact) => (
                <div className="compatibility-impact-row" key={impact.scenarioId}>
                  <strong>{scenarioDraftSet.scenarios.find(
                    (scenario) => scenario.scenarioId === impact.scenarioId,
                  )?.name ?? impact.scenarioId}</strong>
                  <span>{impact.status}</span>
                  <code>{impact.paths.join(', ') || '/'}</code>
                </div>
              ))}
            </section>
          )}
          {report.migrations.length > 0 && (
            <section className="compatibility-migrations">
              <h4>Migration plan</h4>
              {report.migrations.map((migration) => (
                <div className="compatibility-migration-row" key={migration.actionId}>
                  <span>{migration.automatic ? 'SAFE EDIT' : 'MANUAL'}</span>
                  <div>
                    <strong>{migration.kind}</strong>
                    <small>{migration.rationale}</small>
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
                  Apply {safeMigrations.length} safe migration{safeMigrations.length === 1 ? '' : 's'}
                </button>
              )}
              {requiresAcknowledgement && (
                <label>
                  <input
                    type="checkbox"
                    checked={reviewed}
                    onChange={(event) => onReviewedChange(event.target.checked)}
                  />
                  <span>I reviewed unresolved Contract changes and updated affected Scenario values.</span>
                </label>
              )}
              <button
                type="button"
                className="primary"
                disabled={Boolean(requiresAcknowledgement && !reviewed)}
                onClick={onResolve}
              >
                Record review & rebase
              </button>
              <small>Save and rerun are still required before publication.</small>
            </section>
          )}
        </>
      )}
      <div className="compatibility-unknowns">
        <strong>Semantic facts still unknown</strong>
        <span>Effect: {contract.executionSemantics.effect}</span>
        <span>Idempotency: {contract.executionSemantics.idempotency}</span>
        <span>Streaming: {String(contract.executionSemantics.streaming ?? 'UNKNOWN')}</span>
        <span>Durable: {String(contract.executionSemantics.durable ?? 'UNKNOWN')}</span>
      </div>
    </div>
  );
}

function EvidenceTab({
  response,
  comparison,
  compileMessages,
  trustContext,
  onBackToScenario,
  onOpenTab,
  onOpenCompose,
  onSelectDiagnostic,
}: {
  response: SimulationResponse | null;
  comparison: ScenarioComparison | null;
  compileMessages: string[];
  trustContext?: ScenarioEvidenceTrustContext;
  onBackToScenario: () => void;
  onOpenTab: (tab: WorkspaceTab) => void;
  onOpenCompose: () => void;
  onSelectDiagnostic?: (diagnostic: ScenarioEvidenceDiagnostic) => void;
}) {
  if (!response) {
    return (
      <div className="scenario-empty-state">
        <strong>No Scenario run yet</strong>
        <span>Run the selected Scenario to compare actual and expected output.</span>
        {compileMessages.map((message) => <p className="scenario-run-errors" key={message}>{message}</p>)}
        <button type="button" className="primary" onClick={onBackToScenario}>Open Scenarios</button>
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
          {evidence.headline}
        </span>
        <h3>{response.graphName}</h3>
        <p>{evidence.summary}</p>
      </header>

      <RemediationActionList actions={actions} onInvoke={invokeRemediation} />

      <div className="scenario-trust-dimensions" aria-label="Evidence trust dimensions">
        {evidence.dimensions.map((dimension) => (
          <section
            key={dimension.key}
            data-state={dimension.state}
            data-testid={`scenario-trust:${dimension.key}`}
          >
            <span>{dimension.label}</span>
            <strong>{dimension.status}</strong>
            <small>{dimension.detail}</small>
          </section>
        ))}
      </div>

      {evidence.blockers.length > 0 && (
        <EvidenceIssueList
          title={`Blocking findings (${evidence.blockers.length})`}
          issues={evidence.blockers}
          tone="danger"
          onSelectDiagnostic={onSelectDiagnostic}
        />
      )}
      {evidence.warnings.length > 0 && (
        <EvidenceIssueList
          title={`Warnings (${evidence.warnings.length})`}
          issues={evidence.warnings}
          tone="warning"
          onSelectDiagnostic={onSelectDiagnostic}
        />
      )}

      {trustContext?.coordinate && (
        <details className="scenario-evidence-technical">
          <summary>Technical coordinates</summary>
          <dl className="scenario-evidence-coordinate" data-testid="scenario-evidence-coordinate">
            <div><dt>Draft</dt><dd>{trustContext.coordinate.draftId || 'exploratory'} r{trustContext.coordinate.draftRevision}</dd></div>
            <div><dt>Draft fingerprint</dt><dd><code>{trustContext.coordinate.draftFingerprint || 'not saved'}</code></dd></div>
            <div><dt>Contract</dt><dd><code>{trustContext.coordinate.contractFingerprint || 'not checked'}</code></dd></div>
            <div><dt>Scenario</dt><dd>{trustContext.coordinate.scenarioId} r{trustContext.coordinate.scenarioRevision}</dd></div>
            <div><dt>Scenario fingerprint</dt><dd><code>{trustContext.coordinate.scenarioFingerprint || 'not projected'}</code></dd></div>
            <div><dt>Dependency closure</dt><dd><code>{trustContext.coordinate.closureFingerprint || 'not projected'}</code></dd></div>
            <div><dt>Execution request</dt><dd><code>{trustContext.coordinate.requestFingerprint || 'not captured'}</code></dd></div>
          </dl>
        </details>
      )}

      {evidence.failedAssertions.length > 0 && (
        <section className="scenario-assertion-evidence failed" data-testid="failed-assertions">
          <header>
            <div>
              <span>Failed assertions</span>
              <strong>{evidence.failedAssertions.length} need repair</strong>
            </div>
            <button type="button" className="secondary compact" onClick={onBackToScenario}>
              Edit assertions
            </button>
          </header>
          {evidence.failedAssertions.map((entry) => (
            <AssertionEvidence key={entry.assertionId} entry={entry} />
          ))}
        </section>
      )}

      {evidence.passedAssertions.length > 0 && (
        <details className="scenario-passed-evidence" data-testid="passed-assertions">
          <summary>Passed assertions ({evidence.passedAssertions.length})</summary>
          <div>
            {evidence.passedAssertions.map((entry) => (
              <AssertionEvidence key={entry.assertionId} entry={entry} />
            ))}
          </div>
        </details>
      )}

      {comparison && comparison.results.length === 0 && (
        <div className="scenario-no-assertions">
          <strong>No business assertions configured</strong>
          <span>A successful process run is not sufficient promotion evidence.</span>
          <button type="button" className="secondary compact" onClick={onBackToScenario}>
            Add assertion
          </button>
        </div>
      )}

      <div className="scenario-evidence-grid">
        <section>
          <strong>Terminal output</strong>
          <pre>{JSON.stringify(response.output, null, 2)}</pre>
        </section>
        <section>
          <strong>Node status</strong>
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
  return (
    <section className={`scenario-evidence-issues ${tone}`}>
      <strong>{title}</strong>
      <ul>
        {issues.map((issue) => (
          <li key={issue.id}>
            <span>
              <b>{issue.message}</b>
              <small>
                {issue.scope} · {issue.code}
                {(issue.occurrences ?? 1) > 1 ? ` · ${issue.occurrences} occurrences` : ''}
              </small>
              {issue.coordinate && (
                <details>
                  <summary>Technical target</summary>
                  <code>{issue.coordinate}</code>
                </details>
              )}
            </span>
            {issue.diagnostic && onSelectDiagnostic && (
              <button
                type="button"
                className="secondary compact"
                onClick={() => onSelectDiagnostic(issue.diagnostic as ScenarioEvidenceDiagnostic)}
              >
                Open source
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
  const diff = scenarioAssertionDiff(entry.expected, entry.actual, entry.path || '$');
  return (
    <article className={`scenario-assertion-result ${entry.passed ? 'passed' : 'failed'}`}>
      <header>
        <code>{entry.path || '$'}</code>
        <strong>{entry.passed ? 'Pass' : 'Fail'}</strong>
      </header>
      <div>
        <label>
          <span>Expected</span>
          <pre>{JSON.stringify(entry.expected, null, 2)}</pre>
        </label>
        <label>
          <span>Actual</span>
          <pre>{evidenceValue(entry.actual)}</pre>
        </label>
        <label className="scenario-assertion-diff">
          <span>Diff</span>
          {diff.length === 0
            ? <strong>No difference</strong>
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

function emptyQueuedEvidence(caseId: string): TableCaseEvidenceProjection {
  return {
    caseId,
    runId: '',
    attempt: 0,
    execution: 'QUEUED',
    assertions: 'NONE',
    freshness: 'CURRENT',
    proofStrength: 'SCHEMA',
    durationMs: null,
    firstFailure: null,
  };
}

function evidenceFromRun(
  caseId: string,
  response: SimulationResponse,
  comparison: ScenarioComparison,
  attempt: number,
  durationMs: number,
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
    durationMs,
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
