import { useEffect, useMemo, useState } from 'react';

import { sampleFromSchemaEnvelope } from '../draftModel';
import type { GraphDraft, SimulationRequest, SimulationResponse } from '../types';
import type {
  AssertionDraft,
  ContractDraft,
  DependencyBehaviorKind,
  ScenarioDraft,
  ScenarioDraftSet,
} from './domain';
import { compileScenarioForSimulation } from './scenarioCompiler';
import SchemaFieldTree from './SchemaFieldTree';
import SchemaValueForm from './SchemaValueForm';
import {
  compareScenarioRun,
  newScenarioDraft,
  scenarioSetIsCurrent,
  type ScenarioComparison,
  type ScenarioNodeOption,
} from './scenarioAuthoring';
import { schemaAtPath } from './schemaWorkbench';

type WorkspaceTab = 'interface' | 'scenarios' | 'compatibility' | 'evidence';

interface ContractScenarioWorkspaceProps {
  open: boolean;
  graphDraft: GraphDraft;
  contract: ContractDraft | null;
  contractFingerprint: string;
  scenarioDraftSet: ScenarioDraftSet | null;
  nodes: ScenarioNodeOption[];
  lastRun: SimulationResponse | null;
  onScenarioDraftSetChange: (draftSet: ScenarioDraftSet) => void;
  onRebase: () => void;
  onRun: (request: SimulationRequest) => Promise<SimulationResponse>;
  onClose: () => void;
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
  onRebase,
  onRun,
  onClose,
}: ContractScenarioWorkspaceProps) {
  const [activeTab, setActiveTab] = useState<WorkspaceTab>('interface');
  const [selectedScenarioId, setSelectedScenarioId] = useState('');
  const [running, setRunning] = useState(false);
  const [runResponse, setRunResponse] = useState<SimulationResponse | null>(null);
  const [comparison, setComparison] = useState<ScenarioComparison | null>(null);
  const [compileMessages, setCompileMessages] = useState<string[]>([]);
  const [advancedText, setAdvancedText] = useState('');
  const [advancedError, setAdvancedError] = useState('');

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
  const visibleRun = runResponse ?? lastRun;

  useEffect(() => {
    if (!open) {
      return undefined;
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose, open]);

  useEffect(() => {
    if (selectedScenario && selectedScenario.scenarioId !== selectedScenarioId) {
      setSelectedScenarioId(selectedScenario.scenarioId);
    }
  }, [selectedScenario?.scenarioId, selectedScenarioId]);

  useEffect(() => {
    setAdvancedText(selectedScenario ? JSON.stringify(selectedScenario, null, 2) : '');
    setAdvancedError('');
  }, [selectedScenario]);

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
      revision: scenarioDraftSet.revision + 1,
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
      revision: scenarioDraftSet.revision + 1,
      scenarios: [...scenarioDraftSet.scenarios, next],
    });
    setSelectedScenarioId(next.scenarioId);
    setActiveTab('scenarios');
  };

  const removeSelectedScenario = () => {
    if (!selectedScenario) {
      return;
    }
    const nextScenarios = scenarios.filter((scenario) => scenario.scenarioId !== selectedScenario.scenarioId);
    onScenarioDraftSetChange({
      ...scenarioDraftSet,
      revision: scenarioDraftSet.revision + 1,
      scenarios: nextScenarios,
    });
    setSelectedScenarioId(nextScenarios[0]?.scenarioId ?? '');
  };

  const runSelectedScenario = async () => {
    if (!selectedScenario) {
      return;
    }
    setRunning(true);
    setCompileMessages([]);
    setComparison(null);
    try {
      const compilation = compileScenarioForSimulation(
        graphDraft,
        scenarioDraftSet,
        selectedScenario.scenarioId,
        contract.target.fingerprint,
        contractFingerprint,
      );
      if (!compilation.compiled || !compilation.request) {
        setCompileMessages(compilation.diagnostics.map((diagnostic) => diagnostic.message));
        return;
      }
      const response = await onRun(compilation.request);
      setRunResponse(response);
      setComparison(compareScenarioRun(selectedScenario, response));
      setActiveTab('evidence');
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
      setAdvancedError('');
    } catch (cause: unknown) {
      setAdvancedError(cause instanceof Error ? cause.message : String(cause));
    }
  };

  return (
    <div className="contract-workspace-backdrop" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget) {
        onClose();
      }
    }}>
      <section
        className="contract-workspace"
        role="dialog"
        aria-modal="true"
        aria-label="Contract and Scenario workspace"
        data-testid="contract-workspace"
      >
        <header className="contract-workspace-header">
          <div>
            <span>Graph Contract</span>
            <h2>{contract.target.id}</h2>
            <p>
              Revision {contract.target.revision} · {contract.confidence.toLowerCase()} projection
            </p>
          </div>
          <div className="contract-workspace-header-actions">
            <span className={`contract-current-badge ${current ? 'current' : 'stale'}`}>
              {current ? 'Exact inputs current' : 'Contract changed'}
            </span>
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
              <strong>Scenarios target an older graph or Contract.</strong>
              <span>Review the interface change, then explicitly rebase before running.</span>
            </div>
            <button type="button" className="secondary compact" onClick={onRebase}>
              Rebase scenarios
            </button>
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
              aria-selected={activeTab === tab}
              className={activeTab === tab ? 'active' : ''}
              key={tab}
              onClick={() => setActiveTab(tab)}
            >
              {label}
            </button>
          ))}
        </nav>

        <div className="contract-workspace-body">
          {activeTab === 'interface' && (
            <InterfaceTab contract={contract} contractFingerprint={contractFingerprint} />
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
              onSelectScenario={setSelectedScenarioId}
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
            />
          )}
          {activeTab === 'evidence' && (
            <EvidenceTab
              response={visibleRun}
              comparison={comparison}
              compileMessages={compileMessages}
              onBackToScenario={() => setActiveTab('scenarios')}
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
}: {
  contract: ContractDraft;
  contractFingerprint: string;
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
            <small>{scenario.dependencies.filter((entry) => entry.behavior.kind !== 'REAL').length} controlled dependencies</small>
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
                <div><strong>Given</strong><small>Graph input from the Contract</small></div>
              </div>
              <SchemaValueForm
                envelope={contract.inputSchema}
                value={selectedScenario.given.input}
                onChange={(input) => onUpdateScenario((scenario) => ({
                  ...scenario,
                  given: { input, provenance: 'AUTHORED' },
                }))}
                label="Graph input"
              />
            </section>

            <section className="scenario-stage">
              <div className="scenario-stage-title">
                <span>2</span>
                <div><strong>Dependencies</strong><small>Choose real calls or deterministic returns</small></div>
              </div>
              <div className="scenario-dependencies">
                {selectedScenario.dependencies.map((dependency, index) => {
                  const node = nodes.find((candidate) => candidate.id === dependency.selector.nodeId);
                  return (
                    <div className="scenario-dependency-row" key={dependency.dependencyId}>
                      <div className="scenario-dependency-identity">
                        <strong>{node?.label ?? dependency.selector.nodeId}</strong>
                        <code>{node?.operatorRef ?? 'unknown operator'}</code>
                      </div>
                      <select
                        aria-label={`Behavior for ${node?.label ?? dependency.selector.nodeId}`}
                        value={dependency.behavior.kind}
                        onChange={(event) => {
                          const kind = event.target.value as DependencyBehaviorKind;
                          onUpdateScenario((scenario) => ({
                            ...scenario,
                            dependencies: scenario.dependencies.map((entry, candidate) => (
                              candidate === index
                                ? {
                                    ...entry,
                                    behavior: kind === 'RETURN'
                                      ? {
                                          kind,
                                          boundary: 'NODE',
                                          output: node?.outputSchema
                                            ? sampleFromSchemaEnvelope(node.outputSchema)
                                            : {},
                                        }
                                      : { kind, boundary: 'NODE' },
                                  }
                                : entry
                            )),
                          }));
                        }}
                      >
                        <option value="REAL">Real call</option>
                        <option value="RETURN">Return fixture</option>
                        <option value="ERROR">Error (governed)</option>
                        <option value="DELAY">Delay (governed)</option>
                        <option value="TIMEOUT">Timeout (governed)</option>
                        <option value="REPLAY">Replay (governed)</option>
                        <option value="OBSERVE">Observe (governed)</option>
                        <option value="MUST_NOT_CALL">Must not call (governed)</option>
                      </select>
                      {dependency.behavior.kind === 'RETURN' && (
                        <SchemaValueForm
                          envelope={node?.outputSchema}
                          value={dependency.behavior.output}
                          onChange={(output) => onUpdateScenario((scenario) => ({
                            ...scenario,
                            dependencies: scenario.dependencies.map((entry, candidate) => (
                              candidate === index
                                ? { ...entry, behavior: { ...entry.behavior, output } }
                                : entry
                            )),
                          }))}
                          label="Returned output"
                          compact
                        />
                      )}
                      {!['REAL', 'RETURN'].includes(dependency.behavior.kind) && (
                        <p className="governed-behavior-note">
                          This behavior is preserved but runs only through the governed Scenario engine.
                        </p>
                      )}
                    </div>
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
                  <AssertionEditor
                    assertion={assertion}
                    contract={contract}
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
                {compileMessages.map((message) => <span key={message}>{message}</span>)}
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

function AssertionEditor({
  assertion,
  contract,
  onChange,
  onRemove,
}: {
  assertion: AssertionDraft;
  contract: ContractDraft;
  onChange: (assertion: AssertionDraft) => void;
  onRemove: () => void;
}) {
  const expectedSchema = useMemo(
    () => schemaAtPath(contract.outputSchema, assertion.path),
    [assertion.path, contract.outputSchema],
  );
  return (
    <div className="scenario-assertion-row">
      <label>
        <span>Output path</span>
        <input
          value={assertion.path}
          placeholder="Whole output"
          onChange={(event) => onChange({ ...assertion, path: event.target.value })}
        />
      </label>
      <SchemaValueForm
        schema={expectedSchema}
        value={assertion.expected}
        onChange={(expected) => onChange({ ...assertion, expected })}
        label="Expected value"
        compact
      />
      <button
        type="button"
        className="icon-button danger"
        title="Remove assertion"
        aria-label="Remove assertion"
        onClick={onRemove}
      >
        ×
      </button>
    </div>
  );
}

function CompatibilityTab({
  contract,
  scenarioDraftSet,
  contractFingerprint,
  current,
}: {
  contract: ContractDraft;
  scenarioDraftSet: ScenarioDraftSet;
  contractFingerprint: string;
  current: boolean;
}) {
  const checks = [
    {
      label: 'Graph target',
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
  return (
    <div className="compatibility-workbench">
      <header>
        <span className={`contract-current-badge ${current ? 'current' : 'stale'}`}>
          {current ? 'No coordinate drift' : 'Review required'}
        </span>
        <h3>{contract.compatibilityPolicy.mode} compatibility policy</h3>
        <p>Unknown semantic changes block automatic migration.</p>
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
  onBackToScenario,
}: {
  response: SimulationResponse | null;
  comparison: ScenarioComparison | null;
  compileMessages: string[];
  onBackToScenario: () => void;
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
  return (
    <div className="scenario-evidence">
      <header>
        <span className={`contract-current-badge ${comparison?.passed ? 'current' : 'stale'}`}>
          {comparison ? (comparison.passed ? 'All assertions passed' : 'Comparison failed') : 'Latest canvas run'}
        </span>
        <h3>{response.graphName}</h3>
        <p>{response.mockedNodeIds.length} mocked · {response.realNodeIds.length} real</p>
      </header>
      {comparison && comparison.results.length > 0 && (
        <div className="scenario-comparison-table">
          <div className="scenario-comparison-row heading">
            <span>Path</span><span>Result</span><span>Expected</span><span>Actual</span>
          </div>
          {comparison.results.map((entry) => (
            <div className="scenario-comparison-row" key={entry.assertionId}>
              <code>{entry.path || '$'}</code>
              <strong className={entry.passed ? 'passed' : 'failed'}>{entry.passed ? 'Pass' : 'Fail'}</strong>
              <pre>{JSON.stringify(entry.expected, null, 2)}</pre>
              <pre>{JSON.stringify(entry.actual, null, 2)}</pre>
            </div>
          ))}
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

function shortFingerprint(fingerprint: string): string {
  return fingerprint ? `${fingerprint.slice(0, 13)}…${fingerprint.slice(-6)}` : 'missing';
}
