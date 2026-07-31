import { useEffect, useRef, useState } from 'react';

import useDialogFocusTrap from '../author/accessibility/useDialogFocusTrap';
import { sampleFromSchemaEnvelope } from '../draftModel';
import {
  BlogeApiRequestError,
  fetchScenarioCompatibility,
  fetchScenarioDraftSet,
  publishScenarioDraftSet,
  saveScenarioDraftSet,
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
  scenarioEvidenceView,
  type EvidenceIssue,
  type ScenarioEvidenceDiagnostic,
  type ScenarioEvidenceTrustContext,
} from './evidenceModel';
import {
  compileScenarioEditorSnapshotForSimulation,
  type ScenarioCompilationProof,
} from './scenarioCompiler';
import { captureScenarioEditorSnapshot } from './scenarioEditorModel';
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

export type WorkspaceTab = 'interface' | 'scenarios' | 'compatibility' | 'evidence';

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
  onRun: (request: SimulationRequest) => Promise<SimulationResponse>;
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
}: ContractScenarioWorkspaceProps) {
  const [activeTab, setActiveTab] = useState<WorkspaceTab>(initialTab);
  const [selectedScenarioId, setSelectedScenarioId] = useState(
    initialScenarioId || lastRunScenarioId,
  );
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

  useDialogFocusTrap({
    open,
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

  const runSelectedScenario = async () => {
    if (!selectedScenario) {
      return;
    }
    setRunning(true);
    setCompileMessages([]);
    setComparison(null);
    try {
      const snapshot = captureScenarioEditorSnapshot(
        scenarioDraftSet,
        selectedScenario.scenarioId,
        contract,
        nodes,
      );
      const compilation = await compileScenarioEditorSnapshotForSimulation(
        graphDraft,
        snapshot,
        contract.target.fingerprint,
        contractFingerprint,
      );
      if (!compilation.compiled || !compilation.request) {
        setCompileMessages(compilation.diagnostics.map((diagnostic) => diagnostic.message));
        focusSchemaPath(compilation.diagnostics[0]?.target);
        return;
      }
      if (!compilation.proof) {
        setCompileMessages(['Scenario compilation did not produce fingerprint closure proof.']);
        return;
      }
      const response = await onRun(compilation.request);
      const nextComparison = compareScenarioRun(selectedScenario, response);
      setRunResponse(response);
      setComparison(nextComparison);
      onRunEvidence?.(
        selectedScenario.scenarioId,
        nextComparison,
        compilation.request,
        compilation.proof,
      );
      navigateWorkspace('evidence', selectedScenario.scenarioId);
    } catch (cause: unknown) {
      setCompileMessages([String(cause)]);
    } finally {
      setRunning(false);
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

  return (
    <div className="contract-workspace-backdrop" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget) {
        onClose();
      }
    }}>
      <section
        ref={workspaceDialogRef}
        className="contract-workspace"
        role="dialog"
        aria-modal="true"
        aria-label="Contract and Scenario workspace"
        tabIndex={-1}
        data-testid="contract-workspace"
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
              {!assetStored
                ? 'Exploratory draft'
                : dirty
                  ? 'Unsaved Scenario changes'
                  : current
                    ? `Scenario r${scenarioDraftSet.revision} saved`
                    : 'Contract changed'}
            </span>
            {targetKind === 'GRAPH' && (
              <button
                type="button"
                className="secondary compact"
                onClick={() => void saveGraph()}
                disabled={Boolean(assetBusy)}
              >
                {assetBusy === 'graph' ? 'Saving Graph...' : 'Save Graph'}
              </button>
            )}
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
            <button
              type="button"
              className="primary compact"
              onClick={() => void publishDraftSet()}
              disabled={Boolean(assetBusy) || dirty || !current || scenarioDraftSet.revision < 1}
            >
              {assetBusy === 'publish' ? 'Publishing...' : 'Publish'}
            </button>
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
            <button
              type="button"
              className="icon-button"
              title="Close Contract workspace"
              aria-label="Close Contract workspace"
              onClick={onClose}
            >
              ×
            </button>
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

        <nav className="contract-tabs" role="tablist" aria-label="Contract workspace views">
          {([
            ['interface', 'Interface'],
            ['scenarios', `Scenarios ${scenarios.length}`],
            ['compatibility', 'Compatibility'],
            ['evidence', 'Run Evidence'],
          ] as Array<[WorkspaceTab, string]>).map(([tab, label]) => (
            <button
              type="button"
              role="tab"
              {...(tab === initialTab ? { 'data-dialog-initial-focus': true } : {})}
              aria-selected={activeTab === tab}
              className={activeTab === tab ? 'active' : ''}
              key={tab}
              onClick={() => navigateWorkspace(tab)}
            >
              {label}
            </button>
          ))}
        </nav>

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
            <ScenarioTab
              graphDraft={graphDraft}
              contract={contract}
              scenarios={scenarios}
              selectedScenario={selectedScenario}
              selectedScenarioId={selectedScenarioId}
              nodes={nodes}
              running={running}
              compileMessages={compileMessages}
              advancedText={advancedText}
              advancedError={advancedError}
              onAdvancedTextChange={setAdvancedText}
              onApplyAdvancedJson={applyAdvancedJson}
              onSelectScenario={selectScenario}
              onUpdateScenario={updateSelectedScenario}
              onAddScenario={addScenario}
              onRemoveScenario={removeSelectedScenario}
              onRun={runSelectedScenario}
            />
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
  compileMessages: string[];
  advancedText: string;
  advancedError: string;
  onAdvancedTextChange: (value: string) => void;
  onApplyAdvancedJson: () => void;
  onSelectScenario: (scenarioId: string) => void;
  onUpdateScenario: (update: (scenario: ScenarioDraft) => ScenarioDraft) => void;
  onAddScenario: () => void;
  onRemoveScenario: () => void;
  onRun: () => void;
}

function ScenarioTab({
  contract,
  scenarios,
  selectedScenario,
  selectedScenarioId,
  nodes,
  running,
  compileMessages,
  advancedText,
  advancedError,
  onAdvancedTextChange,
  onApplyAdvancedJson,
  onSelectScenario,
  onUpdateScenario,
  onAddScenario,
  onRemoveScenario,
  onRun,
}: ScenarioTabProps) {
  return (
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
              {' controlled / '}
              {scenario.dependencies.length}
              {' total dependencies'}
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
                <div><strong>Dependencies</strong><small>Choose real calls or deterministic returns</small></div>
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
  onSelectDiagnostic,
}: {
  response: SimulationResponse | null;
  comparison: ScenarioComparison | null;
  compileMessages: string[];
  trustContext?: ScenarioEvidenceTrustContext;
  onBackToScenario: () => void;
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
  return (
    <div className="scenario-evidence" data-testid="scenario-evidence">
      <header className={`scenario-evidence-heading ${evidence.tone}`}>
        <span className={`contract-current-badge ${evidence.tone === 'success' ? 'current' : 'stale'}`}>
          {evidence.headline}
        </span>
        <h3>{response.graphName}</h3>
        <p>{evidence.summary}</p>
      </header>

      {trustContext?.coordinate && (
        <dl className="scenario-evidence-coordinate" data-testid="scenario-evidence-coordinate">
          <div><dt>Draft</dt><dd>{trustContext.coordinate.draftId || 'exploratory'} r{trustContext.coordinate.draftRevision}</dd></div>
          <div><dt>Draft fingerprint</dt><dd><code>{trustContext.coordinate.draftFingerprint || 'not saved'}</code></dd></div>
          <div><dt>Contract</dt><dd><code>{trustContext.coordinate.contractFingerprint || 'not checked'}</code></dd></div>
          <div><dt>Scenario</dt><dd>{trustContext.coordinate.scenarioId} r{trustContext.coordinate.scenarioRevision}</dd></div>
          <div><dt>Scenario fingerprint</dt><dd><code>{trustContext.coordinate.scenarioFingerprint || 'not projected'}</code></dd></div>
          <div><dt>Dependency closure</dt><dd><code>{trustContext.coordinate.closureFingerprint || 'not projected'}</code></dd></div>
          <div><dt>Execution request</dt><dd><code>{trustContext.coordinate.requestFingerprint || 'not captured'}</code></dd></div>
        </dl>
      )}

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
              <b>{issue.code}</b>
              <small>
                {issue.scope}
                {issue.coordinate ? ` · ${issue.coordinate}` : ''}
                {(issue.occurrences ?? 1) > 1 ? ` · ${issue.occurrences} occurrences` : ''}
              </small>
              <span>{issue.message}</span>
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
          <pre>{JSON.stringify(entry.actual, null, 2)}</pre>
        </label>
      </div>
    </article>
  );
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
    behavior: { kind: 'REAL', boundary: 'NODE' },
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
