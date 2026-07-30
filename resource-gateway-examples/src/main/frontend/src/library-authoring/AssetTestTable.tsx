import {
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';

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
  VisualOperatorContractTestCase,
  VisualOperatorContractTestSuite,
} from '../types';
import GovernedFixtureSavePanel, {
  type GovernedFixtureSaveLaunch,
} from './GovernedFixtureSavePanel';

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
  inputs: string;
  config: string;
  outputs: string;
};

type FunctionEditor = {
  source: VisualFunctionTestCase;
  id: string;
  kind: VisualFunctionTestKind;
  args: string;
  assertion: VisualFunctionTestAssertion;
  expected: string;
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
  const dialogRef = useRef<HTMLDivElement>(null);
  const [exactDraft, setExactDraft] = useState<VisualLibraryAuthoringDraft | null>(null);
  const [operatorDraft, setOperatorDraft] = useState<VisualAuthoringOperatorTestDraft | null>(null);
  const [functionDraft, setFunctionDraft] = useState<VisualAuthoringFunctionTestDraft | null>(null);
  const [operatorRows, setOperatorRows] = useState<OperatorEditor[]>([]);
  const [functionRows, setFunctionRows] = useState<FunctionEditor[]>([]);
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

  const title = kind === 'operator' ? 'Operator contract tests' : 'Function tests';
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

  const runOperators = async (indices: number[]) => {
    if (!exactDraft || !operatorDraft) {
      return;
    }
    setBusy(true);
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
      setBusy(false);
    }
  };

  const runFunctions = async (indices: number[]) => {
    if (!exactDraft || !functionDraft) {
      return;
    }
    setBusy(true);
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
              aria-label="Close test table"
              title="Close"
              onClick={onClose}
            >
              x
            </button>
          </div>
        </header>

        <div className="library-test-meta">
          <span>Draft revision <strong>{exactDraft?.revision ?? '-'}</strong></span>
          <span>Cases <strong>{kind === 'operator' ? operatorRows.length : functionRows.length}</strong></span>
          {(evidenceView || lastEvidence) && (
            <span title={evidenceView?.evidence.materialFingerprint ?? lastEvidence}>
              Evidence <strong>{shortFingerprint(
                evidenceView?.evidence.materialFingerprint ?? lastEvidence,
              )}</strong>
            </span>
          )}
          {evidenceView && (
            <span
              className={`library-test-trust ${evidenceView.freshness.toLowerCase()}`}
              data-testid="library-test-evidence-trust"
              title={`Signed by ${evidenceView.evidence.seal.keyId}`}
            >
              <strong>SIGNED</strong> {evidenceView.freshness}
            </span>
          )}
          {draftGate && (
            <span
              className={`library-test-gate ${draftGate.status.toLowerCase()}`}
              data-testid="library-test-draft-gate"
              title="Conservative authoring-test baseline; not production readiness"
            >
              Draft gate <strong>{draftGate.satisfiedAssets}/{draftGate.requiredAssets}</strong>
            </span>
          )}
          {kind === 'function' && functionDraft?.executionProfile && (
            <span title={functionDraft.executionProfile}>
              Runner <strong>{executionProfileLabel(functionDraft.executionProfile)}</strong>
            </span>
          )}
        </div>

        <div className="library-test-alerts">
          {diagnostic && <p className="library-test-notice">{diagnostic}</p>}
          {evidenceView?.freshness === 'STALE' && (
            <p className="library-test-notice warning">
              This signed result no longer matches the current draft: {' '}
              {evidenceView.staleReasons.map(reasonLabel).join(', ')}.
            </p>
          )}
          {assetGate?.status === 'BLOCKED' && assetGate.reasons.length > 0 && (
            <p className="library-test-notice warning" data-testid="library-test-gate-reasons">
              This asset is not test-evidenced: {assetGate.reasons.map(reasonLabel).join(', ')}.
            </p>
          )}
          {!fixtureAvailable && (
            <p className="library-test-notice">
              Governed fixture persistence is not advertised by this deployment.
            </p>
          )}
          {error && <p className="library-inline-error" role="alert">{error}</p>}
        </div>

        <div className="library-test-table-scroll">
          {kind === 'operator' ? (
            <OperatorTable
              rows={operatorRows}
              results={operatorResults}
              busy={busy}
              fixtureAvailable={fixtureAvailable}
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
              busy={busy}
              fixtureAvailable={fixtureAvailable}
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
              } else {
                setFunctionRows([...functionRows, newFunctionRow(functionRows.length)]);
              }
            }}
            disabled={busy}
          >
            + Add case
          </button>
          <div>
            <button type="button" className="secondary" onClick={onClose}>Done</button>
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
              {busy ? 'Running...' : 'Run all'}
            </button>
          </div>
        </footer>
        {fixtureLaunch && (
          <GovernedFixtureSavePanel
            {...fixtureLaunch}
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
  busy,
  fixtureAvailable,
  onRowsChange,
  onRun,
  onSaveFixture,
}: {
  rows: OperatorEditor[];
  results: Record<number, { passed: boolean; message: string }>;
  busy: boolean;
  fixtureAvailable: boolean;
  onRowsChange: (rows: OperatorEditor[]) => void;
  onRun: (index: number) => void;
  onSaveFixture: (index: number) => void;
}) {
  const patch = (index: number, value: Partial<OperatorEditor>) => onRowsChange(
    rows.map((row, rowIndex) => rowIndex === index ? { ...row, ...value } : row),
  );
  return (
    <table className="library-test-table operator">
      <thead>
        <tr>
          <th>Case</th>
          <th>Inputs</th>
          <th>Config</th>
          <th>Mocked outputs</th>
          <th>Result</th>
          <th aria-label="Actions" />
        </tr>
      </thead>
      <tbody>
        {rows.map((row, index) => (
          <tr key={`${index}:${row.source.name}`}>
            <td>
              <input
                aria-label={`Operator case ${index + 1} name`}
                value={row.name}
                onChange={(event) => patch(index, { name: event.target.value })}
              />
            </td>
            <td>
              <textarea
                aria-label={`Operator case ${index + 1} inputs JSON`}
                value={row.inputs}
                onChange={(event) => patch(index, { inputs: event.target.value })}
                spellCheck={false}
              />
            </td>
            <td>
              <textarea
                aria-label={`Operator case ${index + 1} config JSON`}
                value={row.config}
                onChange={(event) => patch(index, { config: event.target.value })}
                spellCheck={false}
              />
            </td>
            <td>
              <textarea
                aria-label={`Operator case ${index + 1} outputs JSON`}
                value={row.outputs}
                onChange={(event) => patch(index, { outputs: event.target.value })}
                spellCheck={false}
              />
            </td>
            <td><TestResult result={results[index]} /></td>
            <td>
              <div className="library-test-row-actions">
                <button
                  type="button"
                  className="secondary compact"
                  onClick={() => onRun(index)}
                  disabled={busy}
                >
                  Run
                </button>
                <button
                  type="button"
                  className="secondary compact"
                  onClick={() => onSaveFixture(index)}
                  disabled={busy || !fixtureAvailable}
                  title={fixtureAvailable
                    ? 'Save this test row as a governed fixture'
                    : 'Fixture persistence is unavailable in this deployment'}
                  data-testid={`operator-fixture-save-${index}`}
                >
                  Save fixture
                </button>
                <button
                  type="button"
                  className="icon-button"
                  aria-label={`Remove operator case ${index + 1}`}
                  title="Remove case"
                  onClick={() => onRowsChange(rows.filter((_, rowIndex) => rowIndex !== index))}
                  disabled={busy}
                >
                  x
                </button>
              </div>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function FunctionTable({
  rows,
  results,
  busy,
  fixtureAvailable,
  onRowsChange,
  onRun,
  onSaveFixture,
}: {
  rows: FunctionEditor[];
  results: Record<number, { passed: boolean; status: string; actual: unknown; message: string }>;
  busy: boolean;
  fixtureAvailable: boolean;
  onRowsChange: (rows: FunctionEditor[]) => void;
  onRun: (index: number) => void;
  onSaveFixture: (index: number) => void;
}) {
  const patch = (index: number, value: Partial<FunctionEditor>) => onRowsChange(
    rows.map((row, rowIndex) => rowIndex === index ? { ...row, ...value } : row),
  );
  return (
    <table className="library-test-table function">
      <thead>
        <tr>
          <th>Case</th>
          <th>Kind</th>
          <th>Arguments</th>
          <th>Assertion</th>
          <th>Expected</th>
          <th>Result</th>
          <th aria-label="Actions" />
        </tr>
      </thead>
      <tbody>
        {rows.map((row, index) => (
          <tr key={`${index}:${row.source.id}`}>
            <td>
              <input
                aria-label={`Function case ${index + 1} name`}
                value={row.id}
                onChange={(event) => patch(index, { id: event.target.value })}
              />
            </td>
            <td>
              <select
                aria-label={`Function case ${index + 1} kind`}
                value={row.kind}
                onChange={(event) => patch(index, {
                  kind: event.target.value as VisualFunctionTestKind,
                })}
              >
                <option value="GOLDEN">Golden</option>
                <option value="NEGATIVE">Negative</option>
                <option value="BOUNDARY">Boundary</option>
                <option value="REGRESSION">Regression</option>
              </select>
            </td>
            <td>
              <textarea
                aria-label={`Function case ${index + 1} arguments JSON`}
                value={row.args}
                onChange={(event) => patch(index, { args: event.target.value })}
                spellCheck={false}
              />
            </td>
            <td>
              <select
                aria-label={`Function case ${index + 1} assertion`}
                value={row.assertion}
                onChange={(event) => patch(index, {
                  assertion: event.target.value as VisualFunctionTestAssertion,
                })}
              >
                <option value="EQUALS">Equals</option>
                <option value="RETURN_TYPE">Return type</option>
                <option value="EXPECT_ERROR">Expected error</option>
              </select>
            </td>
            <td>
              {row.assertion === 'EXPECT_ERROR' ? (
                <input
                  aria-label={`Function case ${index + 1} expected error`}
                  value={row.errorCode}
                  onChange={(event) => patch(index, { errorCode: event.target.value })}
                />
              ) : row.assertion === 'RETURN_TYPE' ? (
                <span className="library-test-derived">Declared type</span>
              ) : (
                <textarea
                  aria-label={`Function case ${index + 1} expected JSON`}
                  value={row.expected}
                  onChange={(event) => patch(index, { expected: event.target.value })}
                  spellCheck={false}
                />
              )}
            </td>
            <td>
              <TestResult result={results[index]} showActual />
            </td>
            <td>
              <div className="library-test-row-actions">
                <button
                  type="button"
                  className="secondary compact"
                  onClick={() => onRun(index)}
                  disabled={busy}
                >
                  Run
                </button>
                <button
                  type="button"
                  className="secondary compact"
                  onClick={() => onSaveFixture(index)}
                  disabled={busy || !fixtureAvailable}
                  title={fixtureAvailable
                    ? 'Save this test row as a governed fixture'
                    : 'Fixture persistence is unavailable in this deployment'}
                  data-testid={`function-fixture-save-${index}`}
                >
                  Save fixture
                </button>
                <button
                  type="button"
                  className="icon-button"
                  aria-label={`Remove function case ${index + 1}`}
                  title="Remove case"
                  onClick={() => onRowsChange(rows.filter((_, rowIndex) => rowIndex !== index))}
                  disabled={busy}
                >
                  x
                </button>
              </div>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function TestResult({
  result,
  showActual = false,
}: {
  result?: {
    passed: boolean;
    status?: string;
    actual?: unknown;
    message: string;
  };
  showActual?: boolean;
}) {
  if (!result) {
    return <span className="library-test-result idle">Not run</span>;
  }
  return (
    <div className={`library-test-result ${result.passed ? 'passed' : 'failed'}`}>
      <strong>{result.passed ? 'Passed' : result.status ?? 'Failed'}</strong>
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
    inputs: pretty(testCase.inputs),
    config: pretty(testCase.config),
    outputs: pretty(testCase.mockedOutputs),
  };
}

function functionEditor(testCase: VisualFunctionTestCase): FunctionEditor {
  return {
    source: testCase,
    id: testCase.id,
    kind: testCase.kind,
    args: pretty(testCase.args),
    assertion: testCase.assertion,
    expected: pretty(testCase.expect),
    errorCode: testCase.expectError?.code ?? 'INVALID_ARGUMENT',
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

function newFunctionRow(index: number): FunctionEditor {
  return functionEditor({
    schemaVersion: 'bloge.visualAuthoringFunctionTestCase.v1',
    id: `case-${index + 1}`,
    kind: 'GOLDEN',
    args: [],
    assertion: 'EQUALS',
    expect: null,
    expectError: null,
  });
}

function parseOperatorRow(row: OperatorEditor | undefined, index: number): VisualOperatorContractTestCase {
  if (!row) {
    throw new Error(`Operator case ${index + 1} is missing.`);
  }
  return {
    ...row.source,
    name: row.name.trim() || `case-${index + 1}`,
    inputs: parseObject(row.inputs, `Case ${index + 1} inputs`),
    config: parseObject(row.config, `Case ${index + 1} config`),
    mockedOutputs: parseObject(row.outputs, `Case ${index + 1} mocked outputs`),
  };
}

function parseFunctionRow(row: FunctionEditor | undefined, index: number): VisualFunctionTestCase {
  if (!row) {
    throw new Error(`Function case ${index + 1} is missing.`);
  }
  const args = parseJson(row.args, `Case ${index + 1} arguments`);
  if (!Array.isArray(args)) {
    throw new Error(`Case ${index + 1} arguments must be a JSON array.`);
  }
  return {
    ...row.source,
    id: row.id.trim() || `case-${index + 1}`,
    kind: row.kind,
    args,
    assertion: row.assertion,
    expect: row.assertion === 'EQUALS'
      ? parseJson(row.expected, `Case ${index + 1} expected value`)
      : null,
    expectError: row.assertion === 'EXPECT_ERROR'
      ? { code: row.errorCode.trim() || 'FUNCTION_INVOCATION_FAILED' }
      : null,
  };
}

function parseObject(source: string, label: string): Record<string, unknown> {
  const value = parseJson(source, label);
  if (!value || Array.isArray(value) || typeof value !== 'object') {
    throw new Error(`${label} must be a JSON object.`);
  }
  return value as Record<string, unknown>;
}

function parseJson(source: string, label: string): unknown {
  try {
    return JSON.parse(source);
  } catch {
    throw new Error(`${label} is not valid JSON.`);
  }
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
