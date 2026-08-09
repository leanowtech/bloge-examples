import {
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { X } from 'lucide-react';

import {
  BlogeApiRequestError,
  draftLibraryAuthoringFunctionTest,
  draftLibraryAuthoringOperatorTest,
  fetchLibraryAuthoringTestEvidence,
  fetchLibraryAuthoringTestGate,
  runLibraryAuthoringFunctionTest,
  runLibraryAuthoringOperatorTest,
} from '../api';
import useDialogFocusTrap from '../author/accessibility/useDialogFocusTrap';
import SchemaValueEditor from '../author/shared/SchemaValueEditor';
import { useI18n } from '../i18n/I18nProvider';
import {
  functionArgsArray,
  functionArgsObject,
  functionSignatureSchema,
  operatorConfigSchema,
  operatorInputSchema,
  operatorOutputSchema,
} from '../author/shared/libraryAssetSchema';
import type {
  VisualAuthoringFunctionTestDraft,
  VisualAuthoringFunctionTestRunEvidence,
  VisualAuthoringOperatorTestDraft,
  VisualAuthoringOperatorTestRunEvidence,
  VisualAuthoringTestDraftGate,
  VisualAuthoringTestEvidenceView,
  VisualFunctionTestAssertion,
  VisualFunctionTestCase,
  VisualFunctionTestKind,
  VisualFunctionTestSuite,
  VisualLibraryAuthoringDraft,
  VisualLibraryAuthoringDocument,
  VisualOperatorContractTestCase,
  VisualOperatorContractTestSuite,
} from '../types';
import GovernedFixtureSavePanel, {
  type GovernedFixtureSaveLaunch,
} from './GovernedFixtureSavePanel';
import ScenarioCaseStepRail from '../contract-scenario/ScenarioCaseStepRail';
import ScenarioMatrixSurface from '../contract-scenario/table/ScenarioMatrixSurface';
import {
  functionTestScenarioTableProjection,
  operatorTestScenarioTableProjection,
} from '../contract-scenario/table/assetScenarioTableAdapter';
import {
  resolveExactScenarioRunSelection,
  type ScenarioRunSelectionMode,
  type ScenarioTableColumn,
  type ScenarioTableSelection,
} from '../contract-scenario/table/scenarioTableModel';

export interface AssetTestLaunch {
  kind: 'operator' | 'function';
  assetRef: string;
}

interface AssetTestTableProps extends AssetTestLaunch {
  prepareDraft: () => Promise<VisualLibraryAuthoringDraft>;
  fixtureAvailable: boolean;
  onConflict: () => void;
  onClose: () => void;
}

type OperatorEditor = {
  source: VisualOperatorContractTestCase;
  name: string;
  inputs: Record<string, unknown>;
  config: Record<string, unknown>;
  outputs: Record<string, unknown>;
};

type FunctionEditor = {
  source: VisualFunctionTestCase;
  id: string;
  kind: VisualFunctionTestKind;
  args: unknown[];
  assertion: VisualFunctionTestAssertion;
  expected: unknown;
  errorCode: string;
};

export default function AssetTestTable({
  kind,
  assetRef,
  prepareDraft,
  fixtureAvailable,
  onConflict,
  onClose,
}: AssetTestTableProps) {
  const { t, d } = useI18n();
  const dialogRef = useRef<HTMLDivElement>(null);
  const [exactDraft, setExactDraft] = useState<VisualLibraryAuthoringDraft | null>(null);
  const [operatorDraft, setOperatorDraft] = useState<VisualAuthoringOperatorTestDraft | null>(null);
  const [functionDraft, setFunctionDraft] = useState<VisualAuthoringFunctionTestDraft | null>(null);
  const [operatorRows, setOperatorRows] = useState<OperatorEditor[]>([]);
  const [functionRows, setFunctionRows] = useState<FunctionEditor[]>([]);
  const [selectedCaseIndex, setSelectedCaseIndex] = useState(0);
  const [testView, setTestView] = useState<'matrix' | 'case'>('case');
  const [tableSelection, setTableSelection] = useState<ScenarioTableSelection>({ selectedCaseIds: [] });
  const [previousRunCaseIds, setPreviousRunCaseIds] = useState<string[]>([]);
  const [runningCaseIds, setRunningCaseIds] = useState<string[]>([]);
  const [operatorResults, setOperatorResults] = useState<Record<number, {
    passed: boolean;
    message: string;
  }>>({});
  const [functionResults, setFunctionResults] = useState<Record<number, {
    passed: boolean;
    status: string;
    actual: unknown;
    message: string;
  }>>({});
  const [lastEvidence, setLastEvidence] = useState('');
  const [evidenceView, setEvidenceView] =
    useState<VisualAuthoringTestEvidenceView | null>(null);
  const [draftGate, setDraftGate] =
    useState<VisualAuthoringTestDraftGate | null>(null);
  const [fixtureLaunch, setFixtureLaunch] = useState<GovernedFixtureSaveLaunch | null>(null);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState('');
  useDialogFocusTrap({
    open: fixtureLaunch === null,
    dialogRef,
    onDismiss: onClose,
    initialFocusKey: `${kind}:${assetRef}`,
  });

  useEffect(() => {
    let active = true;
    setBusy(true);
    setError('');
    prepareDraft()
      .then(async (draft) => {
        if (kind === 'operator') {
          const generated = await draftLibraryAuthoringOperatorTest(
            draft.draftId,
            draft.revision,
            assetRef,
          );
          if (active) {
            setExactDraft(draft);
            setOperatorDraft(generated);
            setOperatorRows(generated.suite.cases.map(operatorEditor));
            setSelectedCaseIndex(0);
            setTestView(generated.suite.cases.length > 1 ? 'matrix' : 'case');
          }
        } else {
          const generated = await draftLibraryAuthoringFunctionTest(
            draft.draftId,
            draft.revision,
            assetRef,
          );
          if (active) {
            setExactDraft(draft);
            setFunctionDraft(generated);
            setFunctionRows(generated.suite.cases.map(functionEditor));
            setSelectedCaseIndex(0);
            setTestView(generated.suite.cases.length > 1 ? 'matrix' : 'case');
          }
        }
      })
      .catch((reason) => {
        if (active) {
          handleRequestError(reason, setError, onConflict);
        }
      })
      .finally(() => {
        if (active) {
          setBusy(false);
        }
      });
    return () => {
      active = false;
    };
  }, [assetRef, kind, onConflict, prepareDraft]);

  const title = kind === 'operator' ? t('Operator contract tests') : t('Function tests');
  const proof = kind === 'operator'
    ? 'SCHEMA CONTRACT'
    : functionDraft?.bindingStatus ?? 'CHECKING';
  const diagnostic = useMemo(() => (
    operatorDraft?.diagnostics?.[0]?.message
    ?? functionDraft?.diagnostics?.[0]?.message
    ?? ''
  ), [functionDraft, operatorDraft]);
  const assetGate = useMemo(() => draftGate?.assets.find((asset) => (
    asset.assetKind === kind.toUpperCase() && asset.assetRef === assetRef
  )) ?? null, [assetRef, draftGate, kind]);
  const tableProjection = useMemo(() => {
    if (!exactDraft) return null;
    const freshness = evidenceView?.freshness === 'STALE' ? 'STALE' : 'CURRENT';
    if (kind === 'operator' && operatorDraft) {
      return operatorTestScenarioTableProjection({
        assetRef,
        revision: exactDraft.revision,
        authoringFingerprint: operatorDraft.authoringFingerprint,
        artifactFingerprint: operatorDraft.artifactFingerprint,
      }, operatorRows.map(operatorCaseForProjection), operatorResults, freshness);
    }
    if (kind === 'function' && functionDraft) {
      return functionTestScenarioTableProjection({
        assetRef,
        revision: exactDraft.revision,
        authoringFingerprint: functionDraft.authoringFingerprint,
        artifactFingerprint: functionDraft.functionFingerprint,
      }, functionRows.map(functionCaseForProjection), functionResults, freshness);
    }
    return null;
  }, [
    assetRef,
    evidenceView?.freshness,
    exactDraft,
    functionDraft,
    functionResults,
    functionRows,
    kind,
    operatorDraft,
    operatorResults,
    operatorRows,
  ]);

  const runOperators = async (indices: number[]) => {
    if (!exactDraft || !operatorDraft) {
      return;
    }
    setBusy(true);
    setPreviousRunCaseIds(indices.map((index) => `operator-case-${index + 1}`));
    setRunningCaseIds(indices.map((index) => `operator-case-${index + 1}`));
    setError('');
    try {
      const cases = indices.map((index) => parseOperatorRow(operatorRows[index], index));
      const suite: VisualOperatorContractTestSuite = {
        ...operatorDraft.suite,
        cases,
      };
      const evidence = await runLibraryAuthoringOperatorTest(
        exactDraft.draftId,
        exactDraft.revision,
        suite,
      );
      installOperatorResults(evidence, indices, setOperatorResults);
      setLastEvidence(evidence.evidenceFingerprint);
      const [verified, gate] = await Promise.all([
        fetchLibraryAuthoringTestEvidence(exactDraft.draftId, evidence.runId),
        fetchLibraryAuthoringTestGate(exactDraft.draftId),
      ]);
      setEvidenceView(verified);
      setDraftGate(gate);
    } catch (reason) {
      handleRequestError(reason, setError, onConflict);
    } finally {
      setRunningCaseIds([]);
      setBusy(false);
    }
  };

  const runFunctions = async (indices: number[]) => {
    if (!exactDraft || !functionDraft) {
      return;
    }
    setBusy(true);
    setPreviousRunCaseIds(indices.map((index) => `function-case-${index + 1}`));
    setRunningCaseIds(indices.map((index) => `function-case-${index + 1}`));
    setError('');
    try {
      const cases = indices.map((index) => parseFunctionRow(functionRows[index], index));
      const suite: VisualFunctionTestSuite = {
        ...functionDraft.suite,
        cases,
      };
      const evidence = await runLibraryAuthoringFunctionTest(
        exactDraft.draftId,
        exactDraft.revision,
        suite,
      );
      installFunctionResults(evidence, indices, setFunctionResults);
      setLastEvidence(evidence.evidenceFingerprint);
      const [verified, gate] = await Promise.all([
        fetchLibraryAuthoringTestEvidence(exactDraft.draftId, evidence.runId),
        fetchLibraryAuthoringTestGate(exactDraft.draftId),
      ]);
      setEvidenceView(verified);
      setDraftGate(gate);
    } catch (reason) {
      handleRequestError(reason, setError, onConflict);
    } finally {
      setRunningCaseIds([]);
      setBusy(false);
    }
  };

  const saveOperatorFixture = (index: number) => {
    if (!exactDraft || !fixtureAvailable) {
      return;
    }
    try {
      const testCase = parseOperatorRow(operatorRows[index], index);
      setError('');
      setFixtureLaunch({
        draftId: exactDraft.draftId,
        authoringRevision: exactDraft.revision,
        sourceKind: 'OPERATOR_TEST_CASE',
        assetKind: 'OPERATOR',
        assetRef,
        payload: testCase,
        suggestedFixtureId: fixtureId('operator', assetRef, testCase.name),
      });
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Test case is invalid.');
    }
  };

  const saveFunctionFixture = (index: number) => {
    if (!exactDraft || !fixtureAvailable) {
      return;
    }
    try {
      const testCase = parseFunctionRow(functionRows[index], index);
      setError('');
      setFixtureLaunch({
        draftId: exactDraft.draftId,
        authoringRevision: exactDraft.revision,
        sourceKind: 'FUNCTION_TEST_CASE',
        assetKind: 'FUNCTION',
        assetRef,
        payload: testCase,
        suggestedFixtureId: fixtureId('function', assetRef, testCase.id),
      });
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Test case is invalid.');
    }
  };

  const runTableSelection = (mode: ScenarioRunSelectionMode) => {
    if (!tableProjection) return;
    const exact = resolveExactScenarioRunSelection(
      tableProjection,
      tableSelection,
      mode,
      previousRunCaseIds,
    );
    const indices = exact.caseIds.map((caseId) => Number(caseId.split('-').pop()) - 1)
      .filter((index) => Number.isInteger(index) && index >= 0);
    if (kind === 'operator') void runOperators(indices);
    else void runFunctions(indices);
  };

  const editMatrixCell = (caseId: string, column: ScenarioTableColumn, value: unknown) => {
    const index = Number(caseId.split('-').pop()) - 1;
    if (!Number.isInteger(index) || index < 0) return;
    if (kind === 'operator' && column.binding.kind === 'NAME') {
      setOperatorRows((rows) => rows.map((row, rowIndex) => (
        rowIndex === index ? { ...row, name: String(value) } : row
      )));
    }
    if (kind === 'function') {
      setFunctionRows((rows) => rows.map((row, rowIndex) => {
        if (rowIndex !== index) return row;
        if (column.binding.kind === 'NAME') return { ...row, id: String(value) };
        if (column.binding.kind === 'CASE_TYPE') {
          return { ...row, kind: value as VisualFunctionTestKind };
        }
        return row;
      }));
    }
    setLastEvidence('');
    setEvidenceView(null);
    setDraftGate(null);
  };

  return (
    <div className="library-test-overlay" role="presentation">
      <div
        ref={dialogRef}
        className="library-test-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="library-test-title"
        tabIndex={-1}
        data-testid="library-test-dialog"
      >
        <header className="library-test-heading">
          <div>
            <span>{title}</span>
            <h2 id="library-test-title">{assetRef}</h2>
          </div>
          <div className="library-test-heading-actions">
            <span className={`library-test-proof ${proof.toLowerCase()}`}>{proof}</span>
            <button
              type="button"
              className="icon-button"
              aria-label={t('Close test table')}
              title={t('Close')}
              onClick={onClose}
            >
              <X size={14} aria-hidden="true" />
            </button>
          </div>
        </header>

        <div className="library-test-meta">
          <span>{t('Draft revision')} <strong>{exactDraft?.revision ?? '-'}</strong></span>
          <span>{t('Cases')} <strong>{kind === 'operator' ? operatorRows.length : functionRows.length}</strong></span>
          {(evidenceView || lastEvidence) && (
            <span title={evidenceView?.evidence.materialFingerprint ?? lastEvidence}>
              {t('Evidence')} <strong>{shortFingerprint(
                evidenceView?.evidence.materialFingerprint ?? lastEvidence,
              )}</strong>
            </span>
          )}
          {evidenceView && (
            <span
              className={`library-test-trust ${evidenceView.freshness.toLowerCase()}`}
              data-testid="library-test-evidence-trust"
              title={t('Signed by {keyId}', { keyId: evidenceView.evidence.seal.keyId })}
            >
              <strong>{t('SIGNED')}</strong> {d(evidenceView.freshness)}
            </span>
          )}
          {draftGate && (
            <span
              className={`library-test-gate ${draftGate.status.toLowerCase()}`}
              data-testid="library-test-draft-gate"
              title={t('Conservative authoring-test baseline; not production readiness')}
            >
              {t('Draft gate')} <strong>{draftGate.satisfiedAssets}/{draftGate.requiredAssets}</strong>
            </span>
          )}
          {kind === 'function' && functionDraft?.executionProfile && (
            <span title={functionDraft.executionProfile}>
              {t('Runner')} <strong>{d(executionProfileLabel(functionDraft.executionProfile))}</strong>
            </span>
          )}
        </div>

        <div className="library-test-alerts">
          {diagnostic && <p className="library-test-notice">{diagnostic}</p>}
          {evidenceView?.freshness === 'STALE' && (
            <p className="library-test-notice warning">
              {t('This signed result no longer matches the current draft: {reasons}.', {
                reasons: evidenceView.staleReasons.map((reason) => d(reasonLabel(reason))).join(', '),
              })}
            </p>
          )}
          {assetGate?.status === 'BLOCKED' && assetGate.reasons.length > 0 && (
            <p className="library-test-notice warning" data-testid="library-test-gate-reasons">
              {t('This asset is not test-evidenced: {reasons}.', {
                reasons: assetGate.reasons.map((reason) => d(reasonLabel(reason))).join(', '),
              })}
            </p>
          )}
          {!fixtureAvailable && (
            <p className="library-test-notice">
              {t('Governed fixture persistence is not advertised by this deployment.')}
            </p>
          )}
          {error && <p className="library-inline-error" role="alert">{error}</p>}
        </div>

        <div className="library-test-viewbar">
          <div className="scenario-view-switch" role="group" aria-label={t('Test table view')}>
            <button type="button" aria-pressed={testView === 'matrix'} onClick={() => setTestView('matrix')}>{t('Matrix')}</button>
            <button type="button" aria-pressed={testView === 'case'} onClick={() => setTestView('case')}>{t('Case')}</button>
          </div>
          <span>{kind === 'operator' ? t('Schema contract proof') : t('Runtime behavior proof')}</span>
        </div>

        <div className="library-test-table-scroll">
          {testView === 'matrix' && tableProjection ? (
            <ScenarioMatrixSurface
              projection={tableProjection}
              selection={tableSelection}
              previousRunCaseIds={previousRunCaseIds}
              runningCaseIds={runningCaseIds}
              disabled={busy}
              onSelectionChange={setTableSelection}
              onOpenCase={(caseId) => {
                setSelectedCaseIndex(Number(caseId.split('-').pop()) - 1);
                setTestView('case');
              }}
              onCellEdit={editMatrixCell}
              onAddCase={(caseType) => {
                if (kind === 'operator') {
                  setOperatorRows([...operatorRows, newOperatorRow(operatorRows.length)]);
                  setSelectedCaseIndex(operatorRows.length);
                } else {
                  setFunctionRows([
                    ...functionRows,
                    newFunctionRow(functionRows.length, functionCaseType(caseType)),
                  ]);
                  setSelectedCaseIndex(functionRows.length);
                }
                setTestView('case');
              }}
              onRunSelection={runTableSelection}
            />
          ) : kind === 'operator' ? (
            <OperatorTable
              rows={operatorRows}
              results={operatorResults}
              document={exactDraft?.document ?? emptyAuthoringDocument()}
              assetRef={assetRef}
              selectedIndex={selectedCaseIndex}
              busy={busy}
              fixtureAvailable={fixtureAvailable}
              onSelect={setSelectedCaseIndex}
              onRowsChange={(rows) => {
                setOperatorRows(rows);
                setOperatorResults({});
                setLastEvidence('');
                setEvidenceView(null);
                setDraftGate(null);
              }}
              onRun={(index) => void runOperators([index])}
              onSaveFixture={saveOperatorFixture}
            />
          ) : (
            <FunctionTable
              rows={functionRows}
              results={functionResults}
              document={exactDraft?.document ?? emptyAuthoringDocument()}
              assetRef={assetRef}
              selectedIndex={selectedCaseIndex}
              bindingStatus={functionDraft?.bindingStatus ?? 'UNBOUND'}
              executionProfile={functionDraft?.executionProfile ?? ''}
              busy={busy}
              fixtureAvailable={fixtureAvailable}
              onSelect={setSelectedCaseIndex}
              onRowsChange={(rows) => {
                setFunctionRows(rows);
                setFunctionResults({});
                setLastEvidence('');
                setEvidenceView(null);
                setDraftGate(null);
              }}
              onRun={(index) => void runFunctions([index])}
              onSaveFixture={saveFunctionFixture}
            />
          )}
        </div>

        <footer className="library-test-footer">
          <button
            type="button"
            className="secondary"
            onClick={() => {
              if (kind === 'operator') {
                setOperatorRows([...operatorRows, newOperatorRow(operatorRows.length)]);
                setSelectedCaseIndex(operatorRows.length);
              } else {
                setFunctionRows([...functionRows, newFunctionRow(functionRows.length)]);
                setSelectedCaseIndex(functionRows.length);
              }
            }}
            disabled={busy}
          >
            {t('Add case')}
          </button>
          <div>
            <button type="button" className="secondary" onClick={onClose}>{t('Done')}</button>
            <button
              type="button"
              className="primary"
              data-dialog-initial-focus
              onClick={() => {
                const length = kind === 'operator' ? operatorRows.length : functionRows.length;
                const indices = Array.from({ length }, (_, index) => index);
                if (kind === 'operator') {
                  void runOperators(indices);
                } else {
                  void runFunctions(indices);
                }
              }}
              disabled={busy || (kind === 'operator' ? !operatorRows.length : !functionRows.length)}
              data-testid="library-test-run-all"
            >
              {busy ? t('Running...') : t('Run all')}
            </button>
          </div>
        </footer>
        {fixtureLaunch && (
          <GovernedFixtureSavePanel
            {...fixtureLaunch}
            presentation="sheet"
            onConflict={onConflict}
            onClose={() => setFixtureLaunch(null)}
          />
        )}
      </div>
    </div>
  );
}

function OperatorTable({
  rows,
  results,
  document,
  assetRef,
  selectedIndex,
  busy,
  fixtureAvailable,
  onSelect,
  onRowsChange,
  onRun,
  onSaveFixture,
}: {
  rows: OperatorEditor[];
  results: Record<number, { passed: boolean; message: string }>;
  document: VisualLibraryAuthoringDocument;
  assetRef: string;
  selectedIndex: number;
  busy: boolean;
  fixtureAvailable: boolean;
  onSelect: (index: number) => void;
  onRowsChange: (rows: OperatorEditor[]) => void;
  onRun: (index: number) => void;
  onSaveFixture: (index: number) => void;
}) {
  const { t } = useI18n();
  const patch = (index: number, value: Partial<OperatorEditor>) => onRowsChange(
    rows.map((row, rowIndex) => rowIndex === index ? { ...row, ...value } : row),
  );
  const row = rows[selectedIndex];
  if (!row) {
    return <EmptyCaseWorkspace />;
  }
  return (
    <div className="asset-scenario-workspace" data-testid="asset-scenario-workspace">
      <nav className="asset-scenario-cases" aria-label={t('Operator test cases')}>
        <header>
          <span>{t('Test cases')}</span>
          <strong>{rows.length}</strong>
        </header>
        {rows.map((row, index) => (
          <button
            type="button"
            key={`${index}:${row.source.name}`}
            className={selectedIndex === index ? 'selected' : ''}
            aria-current={selectedIndex === index ? 'true' : undefined}
            onClick={() => onSelect(index)}
          >
            <span>{row.name || t('Case {index}', { index: index + 1 })}</span>
            <CaseResultBadge result={results[index]} successLabel={t('Schema valid')} />
          </button>
        ))}
      </nav>
      <section className="asset-scenario-editor" aria-label={t('Selected operator test case')}>
        <header className="asset-scenario-editor-heading">
          <label>
            <span>{t('Case name')}</span>
            <input
              aria-label={t('Operator case {index} name', { index: selectedIndex + 1 })}
              value={row.name}
              onChange={(event) => patch(selectedIndex, { name: event.target.value })}
            />
          </label>
        </header>

        <ScenarioCaseStepRail
          anchorPrefix={`operator-case-editor-${selectedIndex + 1}`}
          givenCount={structuredFieldCount(row.inputs)}
          dependencyCount={structuredFieldCount(row.config) > 0 ? 1 : 0}
          assertionCount={structuredFieldCount(row.outputs) + structuredFieldCount(row.source.outputAssertions)}
          reviewState={assetReviewState(results[selectedIndex], busy)}
        />

        <section className="asset-scenario-stage" id={`operator-case-editor-${selectedIndex + 1}-given`}>
          <StageHeading step={t('Given')} title={t('Operator inputs')} />
          <SchemaValueEditor
            envelope={operatorInputSchema(document, assetRef)}
            value={row.inputs}
            onChange={(value) => patch(selectedIndex, {
              inputs: objectValue(value),
            })}
            label={t('Inputs')}
          />
        </section>

        <details className="asset-scenario-dependency" id={`operator-case-editor-${selectedIndex + 1}-dependencies`}>
          <summary>
            <span>{t('Dependencies')}</span>
            <strong>{t('Operator configuration')}</strong>
          </summary>
          <SchemaValueEditor
            envelope={operatorConfigSchema(document, assetRef)}
            value={row.config}
            onChange={(value) => patch(selectedIndex, {
              config: objectValue(value),
            })}
            label={t('Configuration')}
          />
        </details>

        <section className="asset-scenario-stage" id={`operator-case-editor-${selectedIndex + 1}-then`}>
          <StageHeading step={t('Then')} title={t('Mocked outputs')} />
          <SchemaValueEditor
            envelope={operatorOutputSchema(document, assetRef)}
            value={row.outputs}
            onChange={(value) => patch(selectedIndex, {
              outputs: objectValue(value),
            })}
            label={t('Outputs')}
          />
          <details className="asset-scenario-advanced">
            <summary>{t('Advanced output assertions')}</summary>
            <SchemaValueEditor
              value={row.source.outputAssertions}
              onChange={(value) => patch(selectedIndex, {
                source: {
                  ...row.source,
                  outputAssertions: objectValue(value) as VisualOperatorContractTestCase['outputAssertions'],
                },
              })}
              label={t('Output assertions')}
              advancedOnly
            />
          </details>
        </section>

        <section className="asset-scenario-stage asset-scenario-review" id={`operator-case-editor-${selectedIndex + 1}-review`}>
          <StageHeading step={t('Review')} title={t('Validate this case')} />
          <TestResult result={results[selectedIndex]} successLabel={t('Schema valid')} />
          <CaseActions
            kind="operator"
            index={selectedIndex}
            busy={busy}
            fixtureAvailable={fixtureAvailable}
            onRun={onRun}
            onSaveFixture={onSaveFixture}
            onRemove={(index) => {
              const next = rows.filter((_, rowIndex) => rowIndex !== index);
              onRowsChange(next);
              onSelect(Math.max(0, Math.min(index, next.length - 1)));
            }}
          />
        </section>
      </section>
    </div>
  );
}

function FunctionTable({
  rows,
  results,
  document,
  assetRef,
  selectedIndex,
  bindingStatus,
  executionProfile,
  busy,
  fixtureAvailable,
  onSelect,
  onRowsChange,
  onRun,
  onSaveFixture,
}: {
  rows: FunctionEditor[];
  results: Record<number, { passed: boolean; status: string; actual: unknown; message: string }>;
  document: VisualLibraryAuthoringDocument;
  assetRef: string;
  selectedIndex: number;
  bindingStatus: string;
  executionProfile: string;
  busy: boolean;
  fixtureAvailable: boolean;
  onSelect: (index: number) => void;
  onRowsChange: (rows: FunctionEditor[]) => void;
  onRun: (index: number) => void;
  onSaveFixture: (index: number) => void;
}) {
  const { t, d } = useI18n();
  const patch = (index: number, value: Partial<FunctionEditor>) => onRowsChange(
    rows.map((row, rowIndex) => rowIndex === index ? { ...row, ...value } : row),
  );
  const row = rows[selectedIndex];
  const projection = functionSignatureSchema(document, assetRef);
  if (!row) {
    return <EmptyCaseWorkspace />;
  }
  return (
    <div className="asset-scenario-workspace" data-testid="asset-scenario-workspace">
      <nav className="asset-scenario-cases" aria-label={t('Function test cases')}>
        <header>
          <span>{t('Test cases')}</span>
          <strong>{rows.length}</strong>
        </header>
        {rows.map((row, index) => (
          <button
            type="button"
            key={`${index}:${row.source.id}`}
            className={selectedIndex === index ? 'selected' : ''}
            aria-current={selectedIndex === index ? 'true' : undefined}
            onClick={() => onSelect(index)}
          >
            <span>{row.id || t('Case {index}', { index: index + 1 })}</span>
            <small>{d(caseKindLabel(row.kind))}</small>
            <CaseResultBadge result={results[index]} successLabel={t('Runtime passed')} />
          </button>
        ))}
      </nav>
      <section className="asset-scenario-editor" aria-label={t('Selected function test case')}>
        <header className="asset-scenario-editor-heading">
          <label>
            <span>{t('Case name')}</span>
            <input
              aria-label={t('Function case {index} name', { index: selectedIndex + 1 })}
              value={row.id}
              onChange={(event) => patch(selectedIndex, { id: event.target.value })}
            />
          </label>
          <label>
            <span>{t('Case type')}</span>
            <select
              aria-label={t('Function case {index} kind', { index: selectedIndex + 1 })}
              value={row.kind}
              onChange={(event) => patch(selectedIndex, {
                kind: event.target.value as VisualFunctionTestKind,
              })}
            >
              <option value="GOLDEN">{t('Golden')}</option>
              <option value="NEGATIVE">{t('Negative')}</option>
              <option value="BOUNDARY">{t('Boundary')}</option>
              <option value="REGRESSION">{t('Regression')}</option>
            </select>
          </label>
        </header>

        <ScenarioCaseStepRail
          anchorPrefix={`function-case-editor-${selectedIndex + 1}`}
          givenCount={row.args.length}
          dependencyCount={0}
          assertionCount={1}
          reviewState={assetReviewState(results[selectedIndex], busy)}
        />

        <section className="asset-scenario-stage" id={`function-case-editor-${selectedIndex + 1}-given`}>
          <StageHeading step={t('Given')} title={t('Function arguments')} />
          <SchemaValueEditor
            envelope={projection.inputSchema}
            value={functionArgsObject(row.args, projection)}
            onChange={(value) => patch(selectedIndex, {
              args: functionArgsArray(value, projection),
            })}
            label={t('Arguments')}
          />
        </section>

        <details className="asset-scenario-dependency" id={`function-case-editor-${selectedIndex + 1}-dependencies`}>
          <summary>
            <span>{t('Dependencies')}</span>
            <strong>{t('Runtime binding')}</strong>
          </summary>
          <dl className="asset-runtime-binding">
            <div>
              <dt>{t('Binding')}</dt>
              <dd>{bindingStatus}</dd>
            </div>
            <div>
              <dt>{t('Execution profile')}</dt>
              <dd>{d(executionProfileLabel(executionProfile || 'not advertised'))}</dd>
            </div>
          </dl>
          <p>{t('Function cases invoke the exact advertised runtime binding; dependency overrides are not inferred from a design-only signature.')}</p>
        </details>

        <section className="asset-scenario-stage" id={`function-case-editor-${selectedIndex + 1}-then`}>
          <StageHeading step={t('Then')} title={t('Expected outcome')} />
          <label className="asset-scenario-assertion">
            <span>{t('Assertion')}</span>
            <select
              aria-label={t('Function case {index} assertion', { index: selectedIndex + 1 })}
              value={row.assertion}
              onChange={(event) => patch(selectedIndex, {
                assertion: event.target.value as VisualFunctionTestAssertion,
              })}
            >
              <option value="EQUALS">{t('Equals')}</option>
              <option value="RETURN_TYPE">{t('Matches declared return type')}</option>
              <option value="EXPECT_ERROR">{t('Returns an error')}</option>
            </select>
          </label>
          {row.assertion === 'EXPECT_ERROR' ? (
            <label className="asset-scenario-assertion">
              <span>{t('Error code')}</span>
              <input
                aria-label={t('Function case {index} expected error', { index: selectedIndex + 1 })}
                value={row.errorCode}
                onChange={(event) => patch(selectedIndex, { errorCode: event.target.value })}
              />
            </label>
          ) : row.assertion === 'RETURN_TYPE' ? (
            <span className="library-test-derived">{t('Declared return schema')}</span>
          ) : (
            <SchemaValueEditor
              envelope={projection.outputSchema}
              value={row.expected}
              onChange={(expected) => patch(selectedIndex, { expected })}
              label={t('Expected value')}
            />
          )}
        </section>

        <section className="asset-scenario-stage asset-scenario-review" id={`function-case-editor-${selectedIndex + 1}-review`}>
          <StageHeading step={t('Review')} title={t('Validate this case')} />
          <TestResult
            result={results[selectedIndex]}
            successLabel={t('Runtime passed')}
            showActual
          />
          <CaseActions
            kind="function"
            index={selectedIndex}
            busy={busy}
            fixtureAvailable={fixtureAvailable}
            onRun={onRun}
            onSaveFixture={onSaveFixture}
            onRemove={(index) => {
              const next = rows.filter((_, rowIndex) => rowIndex !== index);
              onRowsChange(next);
              onSelect(Math.max(0, Math.min(index, next.length - 1)));
            }}
          />
        </section>
      </section>
    </div>
  );
}

function StageHeading({ step, title }: { step: string; title: string }) {
  return (
    <header className="asset-scenario-stage-heading">
      <span>{step}</span>
      <h3>{title}</h3>
    </header>
  );
}

function assetReviewState(
  result: { passed: boolean } | undefined,
  busy: boolean,
): 'NOT_RUN' | 'RUNNING' | 'PASSED' | 'FAILED' {
  if (busy) return 'RUNNING';
  if (!result) return 'NOT_RUN';
  return result.passed ? 'PASSED' : 'FAILED';
}

function structuredFieldCount(value: unknown): number {
  if (Array.isArray(value)) {
    return value.reduce((count, entry) => count + structuredFieldCount(entry), 0);
  }
  if (value !== null && typeof value === 'object') {
    return Object.values(value as Record<string, unknown>)
      .reduce<number>((count, entry) => count + structuredFieldCount(entry), 0);
  }
  return value === undefined ? 0 : 1;
}

function CaseActions({
  kind,
  index,
  busy,
  fixtureAvailable,
  onRun,
  onSaveFixture,
  onRemove,
}: {
  kind: 'operator' | 'function';
  index: number;
  busy: boolean;
  fixtureAvailable: boolean;
  onRun: (index: number) => void;
  onSaveFixture: (index: number) => void;
  onRemove: (index: number) => void;
}) {
  const { t, d } = useI18n();
  return (
    <footer className="asset-scenario-actions">
      <button
        type="button"
        className="danger"
        onClick={() => onRemove(index)}
        disabled={busy}
        aria-label={t('Remove {kind} case {index}', { kind: d(kind), index: index + 1 })}
      >
        {t('Delete case')}
      </button>
      <div>
        <button
          type="button"
          className="secondary"
          onClick={() => onSaveFixture(index)}
          disabled={busy || !fixtureAvailable}
          title={fixtureAvailable
            ? t('Save this test case as a governed fixture')
            : t('Fixture persistence is unavailable in this deployment')}
          data-testid={`${kind}-fixture-save-${index}`}
        >
          {t('Save fixture')}
        </button>
        <button
          type="button"
          className="primary"
          onClick={() => onRun(index)}
          disabled={busy}
          data-testid={`${kind}-case-run-${index}`}
        >
          {t('Run case')}
        </button>
      </div>
    </footer>
  );
}

function CaseResultBadge({
  result,
  successLabel,
}: {
  result?: { passed: boolean };
  successLabel: string;
}) {
  const { t } = useI18n();
  return (
    <strong className={`asset-case-status ${result ? result.passed ? 'passed' : 'failed' : 'idle'}`}>
      {result ? result.passed ? successLabel : t('Failed') : t('Not run')}
    </strong>
  );
}

function EmptyCaseWorkspace() {
  const { t } = useI18n();
  return (
    <div className="asset-scenario-empty">
      <strong>{t('No test cases yet')}</strong>
      <span>{t('Add a case to describe one meaningful business example.')}</span>
    </div>
  );
}

function TestResult({
  result,
  successLabel,
  showActual = false,
}: {
  result?: {
    passed: boolean;
    status?: string;
    actual?: unknown;
    message: string;
  };
  successLabel: string;
  showActual?: boolean;
}) {
  const { t, d } = useI18n();
  if (!result) {
    return <span className="library-test-result idle">{t('Not run')}</span>;
  }
  return (
    <div className={`library-test-result ${result.passed ? 'passed' : 'failed'}`}>
      <strong>{result.passed ? successLabel : result.status ? d(result.status) : t('Failed')}</strong>
      {showActual && result.actual !== undefined && (
        <code title={pretty(result.actual)}>{compactValue(result.actual)}</code>
      )}
      {result.message && <small>{result.message}</small>}
    </div>
  );
}

function operatorEditor(testCase: VisualOperatorContractTestCase): OperatorEditor {
  return {
    source: testCase,
    name: testCase.name,
    inputs: structuredClone(testCase.inputs),
    config: structuredClone(testCase.config),
    outputs: structuredClone(testCase.mockedOutputs),
  };
}

function functionEditor(testCase: VisualFunctionTestCase): FunctionEditor {
  return {
    source: testCase,
    id: testCase.id,
    kind: testCase.kind,
    args: structuredClone(testCase.args),
    assertion: testCase.assertion,
    expected: structuredClone(testCase.expect),
    errorCode: testCase.expectError?.code ?? 'INVALID_ARGUMENT',
  };
}

function operatorCaseForProjection(row: OperatorEditor): VisualOperatorContractTestCase {
  return {
    ...row.source,
    name: row.name,
    inputs: row.inputs,
    config: row.config,
    mockedOutputs: row.outputs,
  };
}

function functionCaseForProjection(row: FunctionEditor): VisualFunctionTestCase {
  return {
    ...row.source,
    id: row.id,
    kind: row.kind,
    args: row.args,
    assertion: row.assertion,
    expect: row.assertion === 'EQUALS' ? row.expected : null,
    expectError: row.assertion === 'EXPECT_ERROR' ? { code: row.errorCode } : null,
  };
}

function newOperatorRow(index: number): OperatorEditor {
  return operatorEditor({
    schemaVersion: 'bloge.visualOperatorContractTestCase.v1',
    name: `case-${index + 1}`,
    description: '',
    inputs: {},
    config: {},
    mockedOutputs: {},
    outputAssertions: {},
  });
}

function newFunctionRow(index: number, kind: VisualFunctionTestKind = 'GOLDEN'): FunctionEditor {
  return functionEditor({
    schemaVersion: 'bloge.visualAuthoringFunctionTestCase.v1',
    id: `case-${index + 1}`,
    kind,
    args: [],
    assertion: 'EQUALS',
    expect: null,
    expectError: null,
  });
}

function functionCaseType(value: string | undefined): VisualFunctionTestKind {
  return ['GOLDEN', 'NEGATIVE', 'BOUNDARY', 'REGRESSION'].includes(value ?? '')
    ? value as VisualFunctionTestKind
    : 'GOLDEN';
}

function parseOperatorRow(row: OperatorEditor | undefined, index: number): VisualOperatorContractTestCase {
  if (!row) {
    throw new Error(`Operator case ${index + 1} is missing.`);
  }
  return {
    ...row.source,
    name: row.name.trim() || `case-${index + 1}`,
    inputs: structuredClone(row.inputs),
    config: structuredClone(row.config),
    mockedOutputs: structuredClone(row.outputs),
  };
}

function parseFunctionRow(row: FunctionEditor | undefined, index: number): VisualFunctionTestCase {
  if (!row) {
    throw new Error(`Function case ${index + 1} is missing.`);
  }
  return {
    ...row.source,
    id: row.id.trim() || `case-${index + 1}`,
    kind: row.kind,
    args: structuredClone(row.args),
    assertion: row.assertion,
    expect: row.assertion === 'EQUALS'
      ? structuredClone(row.expected)
      : null,
    expectError: row.assertion === 'EXPECT_ERROR'
      ? { code: row.errorCode.trim() || 'FUNCTION_INVOCATION_FAILED' }
      : null,
  };
}

function installOperatorResults(
  evidence: VisualAuthoringOperatorTestRunEvidence,
  indices: number[],
  setResults: (value: Record<number, { passed: boolean; message: string }>) => void,
) {
  const next: Record<number, { passed: boolean; message: string }> = {};
  indices.forEach((sourceIndex, resultIndex) => {
    const result = evidence.result.results[resultIndex];
    next[sourceIndex] = {
      passed: result?.passed ?? false,
      message: result?.diagnostics?.[0]?.message
        ?? evidence.result.diagnostics?.[0]?.message
        ?? '',
    };
  });
  setResults(next);
}

function installFunctionResults(
  evidence: VisualAuthoringFunctionTestRunEvidence,
  indices: number[],
  setResults: (value: Record<number, {
    passed: boolean;
    status: string;
    actual: unknown;
    message: string;
  }>) => void,
) {
  const next: Record<number, {
    passed: boolean;
    status: string;
    actual: unknown;
    message: string;
  }> = {};
  indices.forEach((sourceIndex, resultIndex) => {
    const result = evidence.results[resultIndex];
    next[sourceIndex] = {
      passed: result?.passed ?? false,
      status: result?.status ?? evidence.bindingStatus,
      actual: result?.actual,
      message: result?.diagnostics?.[0]?.message
        ?? evidence.diagnostics?.[0]?.message
        ?? '',
    };
  });
  setResults(next);
}

function handleRequestError(
  reason: unknown,
  setError: (message: string) => void,
  onConflict: () => void,
) {
  if (reason instanceof BlogeApiRequestError && reason.status === 412) {
    onConflict();
    setError('This test table targets an older draft revision. Reload the draft.');
    return;
  }
  setError(reason instanceof Error ? reason.message : 'Test request failed.');
}

function pretty(value: unknown): string {
  return JSON.stringify(value, null, 2) ?? 'null';
}

function compactValue(value: unknown): string {
  const serialized = JSON.stringify(value) ?? 'null';
  return serialized.length > 48 ? `${serialized.slice(0, 45)}...` : serialized;
}

function fixtureId(kind: string, assetRef: string, caseId: string): string {
  return `${kind}:${assetRef}:${caseId}`
    .replace(/[^A-Za-z0-9._:-]+/g, '-')
    .replace(/^-+/, '')
    .slice(0, 160);
}

function shortFingerprint(value: string): string {
  return value.length > 18 ? `${value.slice(0, 14)}...` : value;
}

function reasonLabel(value: string): string {
  return value
    .toLowerCase()
    .replace(/_/g, ' ')
    .replace(/^\w/, (letter: string) => letter.toUpperCase());
}

function executionProfileLabel(value: string): string {
  return value.includes('isolated-process') ? 'ISOLATED PROCESS' : value;
}

function objectValue(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {};
}

function emptyAuthoringDocument(): VisualLibraryAuthoringDocument {
  return {
    schemaVersion: 'bloge.visualLibraryAuthoring.v1',
    library: { id: 'unknown' },
  };
}

function caseKindLabel(kind: VisualFunctionTestKind): string {
  return kind.charAt(0) + kind.slice(1).toLowerCase();
}
