import { useI18n } from '../../i18n/I18nProvider';
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
  const { t , d } = useI18n();
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
          <strong>{t('Effective data contract')}</strong>
          <span>{t('Declared, inferred, bound, and observed stay separate.')}</span>
        </div>
        <span
          className={`effective-confidence ${projection.confidence.toLowerCase()}`}
          data-testid="effective-contract-confidence"
        >
          {d(projection.confidence)}
        </span>
      </header>

      <div className="effective-source-summary" aria-label={t('Effective Contract sources')}>
        <span><strong>{projection.declaredOutputs.length}</strong> {t('declared')}</span>
        <span><strong>{projection.inferredOutputs.length}</strong> {t('inferred')}</span>
        <span><strong>{projection.activeBindings.filter((item) => item.status !== 'UNBOUND').length}</strong> {t('bound')}</span>
        <span><strong>{projection.observedOutputs.length}</strong> {t('observed')}</span>
      </div>

      <section className="effective-contract-section">
        <div className="effective-contract-section-title">
          <strong>{t('Input sources')}</strong>
          <span>{t('{count} target fields', { count: projection.activeBindings.length })}</span>
        </div>
        {projection.activeBindings.length > 0 ? (
          compact ? (
            <div
              className="effective-binding-list"
              data-testid="effective-input-sources"
              aria-label={t('Effective input sources')}
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
                    <span>
                      <code title={binding.sourcePath}>
                        {binding.status === 'UNBOUND' ? t('No source') : binding.sourcePath}
                      </code>
                    </span>
                  </div>
                  <div className="effective-binding-meta">
                    <span>{d(binding.kind)}</span>
                    <span>{d(binding.type)}</span>
                    <span>{d(binding.confidence)}</span>
                    <strong>{d(binding.status)}</strong>
                    {onTraceBinding && (binding.sourceNodeId || binding.edgeId) && (
                      <button
                        type="button"
                        className="secondary compact effective-trace-button"
                        aria-label={t('Trace source {path}', { path: binding.sourcePath })}
                        title={t('Focus the upstream source')}
                        onClick={() => onTraceBinding(binding)}
                      >
                        <span aria-hidden="true">{'>'}</span>
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
                    <th>{t('Target field')}</th>
                    <th>{t('Source')}</th>
                    <th>{t('Kind')}</th>
                    <th>{t('Type')}</th>
                    <th>{t('Confidence')}</th>
                    <th>{t('Status')}</th>
                    {onTraceBinding && <th aria-label={t('Trace input source')} />}
                  </tr>
                </thead>
                <tbody>
                  {projection.activeBindings.map((binding) => (
                    <tr
                      key={`${binding.id}:${binding.targetPath}`}
                      data-state={binding.status.toLowerCase()}
                    >
                      <th><code>{binding.targetPath}</code></th>
                      <td>
                        <code title={binding.sourcePath}>
                          {binding.status === 'UNBOUND' ? t('No source') : binding.sourcePath}
                        </code>
                      </td>
                      <td>{d(binding.kind)}</td>
                      <td>{d(binding.type)}</td>
                      <td>{d(binding.confidence)}</td>
                      <td><strong>{d(binding.status)}</strong></td>
                      {onTraceBinding && (
                        <td>
                          {binding.sourceNodeId || binding.edgeId ? (
                            <button
                              type="button"
                              className="secondary compact"
                              onClick={() => onTraceBinding(binding)}
                            >
                              {t('Trace')}
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
          <p className="muted">{t('No edge, context, constant, or expression source is bound.')}</p>
        )}
      </section>

      <details className="effective-contract-section effective-output-sources" open={!compact}>
        <summary>
          <strong>{t('Output sources')}</strong>
          <span>{t('{count} source records', { count: outputFields.length })}</span>
        </summary>
        {outputFields.length > 0 ? (
          <div className="effective-contract-table-wrap">
            <table className="effective-contract-table" data-testid="effective-output-sources">
              <thead>
                <tr>
                  <th>{t('Field')}</th>
                  <th>{t('Type')}</th>
                  <th>{t('Source')}</th>
                  <th>{t('Confidence')}</th>
                  <th>{t('Origin')}</th>
                  {onTraceField && <th aria-label={t('Trace output field')} />}
                </tr>
              </thead>
              <tbody>
                {outputFields.map((field) => (
                  <tr key={`${field.source}:${field.path}:${field.trace.coordinate}`}>
                    <th><code>{field.path}</code></th>
                    <td>{d(field.type)}</td>
                    <td>{d(field.source)}</td>
                    <td>{d(field.confidence)}</td>
                    <td title={`${field.trace.kind}: ${field.trace.detail}`}><code>{d(field.trace.kind)}</code></td>
                    {onTraceField && (
                      <td>
                        <button
                          type="button"
                          className="secondary compact"
                          onClick={() => onTraceField(field)}
                        >
                          {t('Trace')}
                        </button>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="muted">{t('No declared, inferred, or observed output fields.')}</p>
        )}
        {onAcceptInference && projection.inferredOutputs.length > 0 && (
          <div className="effective-contract-actions">
            <button type="button" className="secondary compact" onClick={onAcceptInference}>
              {d(acceptInferenceLabel)}
            </button>
            <span>{t('Creates an open authored schema; requiredness is never inferred.')}</span>
          </div>
        )}
      </details>

      {projection.conflicts.length > 0 && (
        <section className="effective-contract-conflicts" role="alert">
          <strong>{t('{count} Contract conflicts', { count: projection.conflicts.length })}</strong>
          {projection.conflicts.map((conflict) => (
            <span key={`${conflict.code}:${conflict.path}`}>
              <code>{conflict.path}</code> {d(conflict.message)}
            </span>
          ))}
        </section>
      )}
    </section>
  );
}
