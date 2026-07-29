import type {
  EffectiveContractField,
  EffectiveContractProjection,
  EffectiveInputBinding,
} from './effectiveContractProjection';

interface EffectiveContractPanelProps {
  projection: EffectiveContractProjection;
  compact?: boolean;
  acceptInferenceLabel?: string;
  onTraceBinding?: (binding: EffectiveInputBinding) => void;
  onTraceField?: (field: EffectiveContractField) => void;
  onAcceptInference?: () => void;
}

/**
 * Presents the four effective-contract sources without collapsing inference or observation into
 * declared schema. The same projection is reusable in the inspector and node Contract editor.
 */
export default function EffectiveContractPanel({
  projection,
  compact = false,
  acceptInferenceLabel = 'Accept inferred output',
  onTraceBinding,
  onTraceField,
  onAcceptInference,
}: EffectiveContractPanelProps) {
  const outputFields = [
    ...projection.declaredOutputs,
    ...projection.inferredOutputs,
    ...projection.observedOutputs,
  ];
  return (
    <section
      className={`effective-contract-panel ${compact ? 'compact' : ''}`}
      data-testid="effective-contract-panel"
    >
      <header className="effective-contract-heading">
        <div>
          <strong>Effective data contract</strong>
          <span>Declared, inferred, bound, and observed stay separate.</span>
        </div>
        <span
          className={`effective-confidence ${projection.confidence.toLowerCase()}`}
          data-testid="effective-contract-confidence"
        >
          {projection.confidence}
        </span>
      </header>

      <div className="effective-source-summary" aria-label="Effective Contract sources">
        <span><strong>{projection.declaredOutputs.length}</strong> declared</span>
        <span><strong>{projection.inferredOutputs.length}</strong> inferred</span>
        <span><strong>{projection.activeBindings.filter((item) => item.status !== 'UNBOUND').length}</strong> bound</span>
        <span><strong>{projection.observedOutputs.length}</strong> observed</span>
      </div>

      <section className="effective-contract-section">
        <div className="effective-contract-section-title">
          <strong>Input sources</strong>
          <span>{projection.activeBindings.length} target fields</span>
        </div>
        {projection.activeBindings.length > 0 ? (
          compact ? (
            <div
              className="effective-binding-list"
              data-testid="effective-input-sources"
              aria-label="Effective input sources"
            >
              {projection.activeBindings.map((binding) => (
                <div
                  className="effective-binding-row"
                  key={`${binding.id}:${binding.targetPath}`}
                  data-state={binding.status.toLowerCase()}
                  data-testid="effective-input-source-row"
                >
                  <div className="effective-binding-paths">
                    <strong><code title={binding.targetPath}>{binding.targetPath}</code></strong>
                    <span><code title={binding.sourcePath}>{binding.sourcePath}</code></span>
                  </div>
                  <div className="effective-binding-meta">
                    <span>{binding.kind}</span>
                    <span>{binding.type}</span>
                    <span>{binding.confidence}</span>
                    <strong>{binding.status}</strong>
                    {onTraceBinding && (binding.sourceNodeId || binding.edgeId) && (
                      <button
                        type="button"
                        className="secondary compact effective-trace-button"
                        aria-label={`Trace source ${binding.sourcePath}`}
                        title="Focus the upstream source"
                        onClick={() => onTraceBinding(binding)}
                      >
                        <span aria-hidden="true">&gt;</span>
                      </button>
                    )}
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <div className="effective-contract-table-wrap">
              <table className="effective-contract-table" data-testid="effective-input-sources">
                <thead>
                  <tr>
                    <th>Target field</th>
                    <th>Source</th>
                    <th>Kind</th>
                    <th>Type</th>
                    <th>Confidence</th>
                    <th>Status</th>
                    {onTraceBinding && <th aria-label="Trace input source" />}
                  </tr>
                </thead>
                <tbody>
                  {projection.activeBindings.map((binding) => (
                    <tr
                      key={`${binding.id}:${binding.targetPath}`}
                      data-state={binding.status.toLowerCase()}
                    >
                      <th><code>{binding.targetPath}</code></th>
                      <td><code title={binding.sourcePath}>{binding.sourcePath}</code></td>
                      <td>{binding.kind}</td>
                      <td>{binding.type}</td>
                      <td>{binding.confidence}</td>
                      <td><strong>{binding.status}</strong></td>
                      {onTraceBinding && (
                        <td>
                          {binding.sourceNodeId || binding.edgeId ? (
                            <button
                              type="button"
                              className="secondary compact"
                              onClick={() => onTraceBinding(binding)}
                            >
                              Trace
                            </button>
                          ) : null}
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )
        ) : (
          <p className="muted">No edge, context, constant, or expression source is bound.</p>
        )}
      </section>

      <details className="effective-contract-section effective-output-sources" open={!compact}>
        <summary>
          <strong>Output sources</strong>
          <span>{outputFields.length} source records</span>
        </summary>
        {outputFields.length > 0 ? (
          <div className="effective-contract-table-wrap">
            <table className="effective-contract-table" data-testid="effective-output-sources">
              <thead>
                <tr>
                  <th>Field</th>
                  <th>Type</th>
                  <th>Source</th>
                  <th>Confidence</th>
                  <th>Origin</th>
                  {onTraceField && <th aria-label="Trace output field" />}
                </tr>
              </thead>
              <tbody>
                {outputFields.map((field) => (
                  <tr key={`${field.source}:${field.path}:${field.trace.coordinate}`}>
                    <th><code>{field.path}</code></th>
                    <td>{field.type}</td>
                    <td>{field.source}</td>
                    <td>{field.confidence}</td>
                    <td title={field.trace.detail}><code>{field.trace.kind}</code></td>
                    {onTraceField && (
                      <td>
                        <button
                          type="button"
                          className="secondary compact"
                          onClick={() => onTraceField(field)}
                        >
                          Trace
                        </button>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="muted">No declared, inferred, or observed output fields.</p>
        )}
        {onAcceptInference && projection.inferredOutputs.length > 0 && (
          <div className="effective-contract-actions">
            <button type="button" className="secondary compact" onClick={onAcceptInference}>
              {acceptInferenceLabel}
            </button>
            <span>Creates an open authored schema; requiredness is never inferred.</span>
          </div>
        )}
      </details>

      {projection.conflicts.length > 0 && (
        <section className="effective-contract-conflicts" role="alert">
          <strong>{projection.conflicts.length} Contract conflict{projection.conflicts.length === 1 ? '' : 's'}</strong>
          {projection.conflicts.map((conflict) => (
            <span key={`${conflict.code}:${conflict.path}`}>
              <code>{conflict.path}</code> {conflict.message}
            </span>
          ))}
        </section>
      )}
    </section>
  );
}
