import type {
  VisualLibraryAuthoringCompileResult,
  VisualLibraryAuthoringDocument,
} from '../../types';
import { useI18n } from '../../i18n/I18nProvider';
import type {
  LibraryResponsiveTaskProjection,
  LibraryTaskIntent,
} from '../../ux/responsiveTaskProjection';
import type { LibraryAssetSelection } from '../model';
import { typeFields } from '../model';
import { presentLibraryReadiness, presentRuntimeParity } from '../readinessPresentation';

interface MobileLibraryTaskSurfaceProps {
  document: VisualLibraryAuthoringDocument;
  selection: LibraryAssetSelection;
  intent: LibraryTaskIntent;
  projection: LibraryResponsiveTaskProjection;
  preview: VisualLibraryAuthoringCompileResult | null;
  previewBusy: boolean;
  desktopHref: string;
  onSelectionChange: (selection: LibraryAssetSelection) => void;
  onIntentChange: (intent: LibraryTaskIntent) => void;
  onDocumentChange: (
    update: (current: VisualLibraryAuthoringDocument) => VisualLibraryAuthoringDocument,
  ) => void;
  onValidate: () => void;
  onOpenTests: () => void;
}

export default function MobileLibraryTaskSurface({
  document,
  selection,
  intent,
  projection,
  preview,
  previewBusy,
  desktopHref,
  onSelectionChange,
  onIntentChange,
  onDocumentChange,
  onValidate,
  onOpenTests,
}: MobileLibraryTaskSurfaceProps) {
  const { t, m } = useI18n();
  const selected = selectedAsset(document, selection);
  const readiness = presentLibraryReadiness(preview);
  const testable = selection.kind === 'operator' || selection.kind === 'function';
  const runtime = preview?.runtimeParity?.find((entry) => (
    entry.assetRef === selection.key
    && entry.assetKind.toLocaleLowerCase() === selection.kind
  ));
  const diagnostics = preview?.diagnostics.filter((entry) => (
    selection.kind === 'library'
    || entry.authoringPath.startsWith(`/${selection.kind}s/${pointer(selection.key)}`)
  )) ?? [];

  return (
    <section
      className="mobile-library-task"
      data-testid="mobile-library-task"
      data-task-id={projection.taskId}
      data-max-primary-actions={projection.maxPrimaryActions}
    >
      <header className="mobile-library-taskbar">
        <div>
          <span>{t('Mobile task')}</span>
          <strong>{t(intent === 'REVIEW' ? 'Review library asset' : 'Edit basic metadata')}</strong>
        </div>
        <div className="mobile-library-intent-switch" role="tablist" aria-label={t('Library task intent')}>
          <button
            type="button"
            role="tab"
            aria-selected={intent === 'REVIEW'}
            onClick={() => onIntentChange('REVIEW')}
          >
            {t('Review')}
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={intent === 'LIGHT_EDIT'}
            disabled={!projection.lightEditingSupported && intent === 'REVIEW'}
            onClick={() => onIntentChange('LIGHT_EDIT')}
          >
            {t('Edit basics')}
          </button>
        </div>
      </header>

      <label className="mobile-library-asset-picker">
        <span>{t('Current asset')}</span>
        <select
          aria-label={t('Current asset')}
          value={selectionValue(selection)}
          onChange={(event) => onSelectionChange(selectionFromValue(event.target.value))}
        >
          <option value={selectionValue({ kind: 'library', key: '' })}>
            {t('Library')} · {document.library.name || document.library.id}
          </option>
          {assetOptions('type', document.types ?? {}, t)}
          {assetOptions('operator', document.operators ?? {}, t)}
          {assetOptions('function', document.functions ?? {}, t)}
        </select>
      </label>

      {intent === 'REVIEW' ? (
        <section className="mobile-library-review" data-testid="mobile-library-review">
          <header>
            <div>
              <span>{t(assetKindLabel(selection.kind))}</span>
              <h2>{selected.title}</h2>
            </div>
            <strong data-tone={readiness.tone}>
              {m(readiness.title.messageId, readiness.title.params)}
            </strong>
          </header>
          <dl>
            {selected.facts.map((fact) => (
              <div key={fact.label}>
                <dt>{t(fact.label)}</dt>
                <dd>{typeof fact.value === 'string' ? t(fact.value) : fact.value}</dd>
              </div>
            ))}
          </dl>
          <section className="mobile-library-readiness" data-tone={readiness.tone}>
            <div>
              <span>{t('Contract status')}</span>
              <strong>{m(readiness.summary.messageId, readiness.summary.params)}</strong>
            </div>
            <dl>
              <div><dt>{t('Diagnostics')}</dt><dd>{diagnostics.length}</dd></div>
              <div>
                <dt>{t('Runtime')}</dt>
                <dd>{runtime
                  ? m(presentRuntimeParity(runtime).state.messageId)
                  : t('Not checked')}</dd>
              </div>
            </dl>
            <p>
              <strong>{t('Next')}</strong>
              {m(readiness.nextAction.messageId, readiness.nextAction.params)}
            </p>
          </section>
          <div className="mobile-library-review-actions">
            {projection.lightEditingSupported ? (
              <button type="button" className="primary" onClick={() => onIntentChange('LIGHT_EDIT')}>
                {t('Edit basics')}
              </button>
            ) : (
              <a className="primary" href={desktopHref}>{t('Open desktop editor')}</a>
            )}
            <button type="button" className="secondary" onClick={onValidate} disabled={previewBusy}>
              {previewBusy ? t('Validating...') : t('Validate')}
            </button>
            {testable && (
              <button type="button" className="secondary" onClick={onOpenTests}>
                {t('Open test table')}
              </button>
            )}
          </div>
          {projection.lightEditingSupported && (
            <a className="mobile-library-desktop-link" href={desktopHref}>
              {t('Open exact asset in desktop editor')}
            </a>
          )}
        </section>
      ) : projection.lightEditingSupported ? (
        <MobileLibraryLightEditor
          document={document}
          selection={selection}
          desktopHref={desktopHref}
          onDocumentChange={onDocumentChange}
          onDone={() => onIntentChange('REVIEW')}
        />
      ) : (
        <section className="mobile-library-complex-handoff" data-testid="mobile-library-complex-handoff">
          <span>{t('Complex edit')}</span>
          <h2>{selected.title}</h2>
          <p>{t('Named type fields and nested schemas require the desktop editor.')}</p>
          <a className="primary" href={desktopHref}>{t('Open desktop editor')}</a>
          <button type="button" className="secondary" onClick={() => onIntentChange('REVIEW')}>
            {t('Back to review')}
          </button>
        </section>
      )}
    </section>
  );
}

function MobileLibraryLightEditor({
  document,
  selection,
  desktopHref,
  onDocumentChange,
  onDone,
}: {
  document: VisualLibraryAuthoringDocument;
  selection: LibraryAssetSelection;
  desktopHref: string;
  onDocumentChange: (
    update: (current: VisualLibraryAuthoringDocument) => VisualLibraryAuthoringDocument,
  ) => void;
  onDone: () => void;
}) {
  const { t } = useI18n();
  const patchLibrary = (value: Record<string, unknown>) => onDocumentChange((current) => ({
    ...current,
    library: { ...current.library, ...value },
  }));
  const patchOperator = (value: Record<string, unknown>) => onDocumentChange((current) => ({
    ...current,
    operators: {
      ...(current.operators ?? {}),
      [selection.key]: { ...current.operators?.[selection.key], ...value },
    },
  }));
  const patchFunction = (value: Record<string, unknown>) => onDocumentChange((current) => ({
    ...current,
    functions: {
      ...(current.functions ?? {}),
      [selection.key]: { ...current.functions?.[selection.key], ...value },
    },
  }));
  const operator = selection.kind === 'operator' ? document.operators?.[selection.key] : undefined;
  const fn = selection.kind === 'function' ? document.functions?.[selection.key] : undefined;

  return (
    <section className="mobile-library-light-editor" data-testid="mobile-library-light-editor">
      <header>
        <span>{t(assetKindLabel(selection.kind))}</span>
        <h2>{selectedAsset(document, selection).title}</h2>
        <p>{t('Only basic metadata is editable on mobile. Schema structure remains unchanged.')}</p>
      </header>
      <div className="mobile-library-light-fields">
        {selection.kind === 'library' && (
          <>
            <label><span>{t('Library id')}</span><input value={document.library.id} readOnly /></label>
            <label><span>{t('Name')}</span><input value={document.library.name ?? ''} onChange={(event) => patchLibrary({ name: event.target.value })} /></label>
            <label><span>{t('Version')}</span><input value={document.library.version ?? ''} onChange={(event) => patchLibrary({ version: event.target.value })} /></label>
            <label><span>{t('Owner')}</span><input value={document.library.owner ?? ''} onChange={(event) => patchLibrary({ owner: event.target.value })} /></label>
          </>
        )}
        {operator && (
          <>
            <label><span>{t('Operator ref')}</span><input value={selection.key} readOnly /></label>
            <label><span>{t('Display name')}</span><input value={operator.name ?? ''} onChange={(event) => patchOperator({ name: event.target.value })} /></label>
            <label><span>{t('Description')}</span><textarea value={operator.description ?? ''} onChange={(event) => patchOperator({ description: event.target.value })} /></label>
          </>
        )}
        {fn && (
          <>
            <label><span>{t('Callable name')}</span><input value={selection.key} readOnly /></label>
            <label><span>{t('Category')}</span><input value={fn.category ?? ''} onChange={(event) => patchFunction({ category: event.target.value })} /></label>
            <label><span>{t('Description')}</span><textarea value={fn.description ?? ''} onChange={(event) => patchFunction({ description: event.target.value })} /></label>
          </>
        )}
      </div>
      <p className="mobile-library-schema-boundary">{t('Input/output schemas, nested fields, signatures, tests, and runtime governance stay in the desktop task.')}</p>
      <div className="mobile-library-light-actions">
        <button type="button" className="primary" onClick={onDone}>{t('Review changes')}</button>
        <a className="secondary" href={desktopHref}>{t('Open desktop editor')}</a>
      </div>
    </section>
  );
}

function selectedAsset(
  document: VisualLibraryAuthoringDocument,
  selection: LibraryAssetSelection,
): { title: string; facts: Array<{ label: string; value: string | number }> } {
  if (selection.kind === 'operator') {
    const operator = document.operators?.[selection.key];
    return {
      title: operator?.name || selection.key,
      facts: [
        { label: 'Inputs', value: Object.keys(operator?.input ?? {}).length },
        { label: 'Outputs', value: Object.keys(operator?.output ?? {}).length },
        { label: 'Tests', value: operator?.tests?.length ?? 0 },
        { label: 'Archetype', value: operator?.archetype ?? 'pure' },
      ],
    };
  }
  if (selection.kind === 'function') {
    const fn = document.functions?.[selection.key];
    const signatures = fn?.signatures ?? (fn?.signature ? [fn.signature] : []);
    return {
      title: selection.key,
      facts: [
        { label: 'Signatures', value: signatures.length },
        { label: 'Examples', value: fn?.examples?.length ?? 0 },
        { label: 'Tests', value: fn?.tests?.length ?? 0 },
        { label: 'Category', value: fn?.category ?? 'business' },
      ],
    };
  }
  if (selection.kind === 'type') {
    return {
      title: selection.key,
      facts: [
        { label: 'Fields', value: Object.keys(typeFields(document.types?.[selection.key])).length },
        { label: 'Edit mode', value: 'Desktop required' },
        { label: 'Tests', value: 0 },
        { label: 'Runtime', value: 'Not applicable' },
      ],
    };
  }
  return {
    title: document.library.name || document.library.id,
    facts: [
      { label: 'Operators', value: Object.keys(document.operators ?? {}).length },
      { label: 'Functions', value: Object.keys(document.functions ?? {}).length },
      { label: 'Types', value: Object.keys(document.types ?? {}).length },
      { label: 'Owner', value: document.library.owner || 'Unresolved' },
    ],
  };
}

function assetOptions(
  kind: Exclude<LibraryAssetSelection['kind'], 'library'>,
  values: Record<string, unknown>,
  t: (source: string) => string,
) {
  const keys = Object.keys(values);
  if (keys.length === 0) return null;
  return (
    <optgroup label={t(assetKindPluralLabel(kind))}>
      {keys.map((key) => <option value={selectionValue({ kind, key })} key={key}>{key}</option>)}
    </optgroup>
  );
}

function selectionValue(selection: LibraryAssetSelection): string {
  return `${selection.kind}|${encodeURIComponent(selection.key)}`;
}

function selectionFromValue(value: string): LibraryAssetSelection {
  const separator = value.indexOf('|');
  const kind = value.slice(0, separator) as LibraryAssetSelection['kind'];
  return { kind, key: decodeURIComponent(value.slice(separator + 1)) };
}

function assetKindLabel(kind: LibraryAssetSelection['kind']): string {
  if (kind === 'type') return 'Named Type';
  if (kind === 'operator') return 'Operator';
  if (kind === 'function') return 'Built-in Function';
  return 'Library';
}

function assetKindPluralLabel(kind: Exclude<LibraryAssetSelection['kind'], 'library'>): string {
  if (kind === 'type') return 'Types';
  if (kind === 'operator') return 'Operators';
  return 'Functions';
}

function pointer(value: string): string {
  return value.replace(/~/g, '~0').replace(/\//g, '~1');
}
