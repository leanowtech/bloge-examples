import { useMemo, useState } from 'react';

import { useI18n } from '../i18n/I18nProvider';
import {
  promoteRequestFrom,
  provenanceOf,
  governedRefFromReceipt,
  type GraphNodeFixtureClassification,
  type GraphNodeFixturePromoteRequest,
  type GraphNodeFixturePromotionReceipt,
  type GovernedGraphNodeFixtureRef,
  type ResourceFidelity,
} from './graphNodeFixtureModel';
import './graphNodeFixture.css';

interface ProvenanceBadgeProps {
  fixture?: Parameters<typeof provenanceOf>[0];
}

/** Renders the capture lifecycle without exposing business material. */
export function ProvenanceBadge({ fixture }: ProvenanceBadgeProps) {
  const provenance = provenanceOf(fixture);
  return (
    <span className="fixture-provenance" data-testid="fixture-provenance" data-provenance={provenance}>
      {provenance === 'governed' ? 'Governed' : provenance === 'pinned' ? 'Pinned' : 'Sample'}
    </span>
  );
}

/** Injectable transport used by graph-node fixture promotion. */
export type FixturePromoter = (
  draftId: string,
  nodeId: string,
  request: GraphNodeFixturePromoteRequest,
) => Promise<GraphNodeFixturePromotionReceipt>;

interface SimulationFixtureControlsProps {
  draftId?: string;
  nodeId: string;
  label: string;
  operatorRef: string;
  output: unknown;
  opaque?: boolean;
  fixture?: Parameters<typeof provenanceOf>[0];
  schemaStale?: boolean;
  onPin?: () => void;
  promoter?: FixturePromoter;
  onGoverned?: (reference: GovernedGraphNodeFixtureRef & { nodeId: string }) => void;
  testIdPrefix?: string;
}

/**
 * Compact simulated-row actions for pinning an output or submitting it to governance.
 *
 * <p>The component never renders captured output in its action area; trace preview remains
 * the sole presentation surface owned by AuthorCanvas.</p>
 */
export function SimulationFixtureControls({
  draftId,
  nodeId,
  label,
  operatorRef,
  output,
  opaque = false,
  fixture,
  schemaStale = false,
  onPin,
  promoter,
  onGoverned,
  testIdPrefix = 'fixture',
}: SimulationFixtureControlsProps) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  // `0`, `false`, and an empty string are valid schema-shaped outputs. Only an
  // absent value means that the simulation did not produce an output.
  const hasOutput = output !== undefined;
  const canPin = hasOutput && typeof onPin === 'function';
  const canPromote = hasOutput && Boolean(draftId?.trim() && promoter);
  const controlTestId = (action: string) => testIdPrefix === 'fixture'
    ? `${action}-fixture-${nodeId}`
    : `${testIdPrefix}-${action}-fixture-${nodeId}`;
  return (
    <span className="simulation-fixture-controls">
      <ProvenanceBadge fixture={fixture} />
      {opaque && (
        <span className="fixture-stale" data-testid="fixture-opaque-warning">
          {t('Opaque output schema; typed composition is unavailable.')}
        </span>
      )}
      {schemaStale && (
        <span className="fixture-stale" data-testid="fixture-schema-stale">{t('Schema changed')}</span>
      )}
      <button
        type="button"
        className="secondary compact"
        data-testid={controlTestId('pin')}
        disabled={!canPin || provenanceOf(fixture) !== 'sample'}
        onClick={onPin}
      >
        {t('Pin')}
      </button>
      <button
        type="button"
        className="secondary compact"
        data-testid={controlTestId('promote')}
        disabled={!canPromote}
        onClick={() => setOpen(true)}
      >
        {t('Promote')}
      </button>
      {open && promoter && (
        <GovernedFixturePromoteDialog
          title={`${label} · ${operatorRef}`}
          onCancel={() => setOpen(false)}
          onSubmit={async (request) => {
            const receipt = await promoter(draftId!, nodeId, request);
            onGoverned?.(governedRefFromReceipt(nodeId, receipt));
            setOpen(false);
            return receipt;
          }}
        />
      )}
    </span>
  );
}

interface GovernedFixturePromoteDialogProps {
  title: string;
  onSubmit: (request: GraphNodeFixturePromoteRequest) => Promise<unknown>;
  onCancel: () => void;
}

/** Bounded authoring form for confidentiality, short-term retention, and redaction paths. */
function GovernedFixturePromoteDialog({
  title,
  onSubmit,
  onCancel,
}: GovernedFixturePromoteDialogProps) {
  const { t } = useI18n();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  /**
   * Read author values from the live form on submit.
   *
   * Keeping values outside React state avoids two retry hazards: captured
   * edits survive safe transport failures, and browser-dispatched revision
   * values cannot race their corresponding React state updates.
   */
  const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const value = (name: string) => String(new FormData(event.currentTarget).get(name) ?? '');
    setBusy(true);
    setError('');
    try {
      await onSubmit(promoteRequestFrom({
        fixtureAssetId: value('fixtureAssetId'),
        classification: value('classification') as GraphNodeFixtureClassification,
        retentionDays: Number(value('retentionDays')),
        redactionPaths: value('redactionPaths').trim()
          ? value('redactionPaths').split(/[\n,]/)
          : [],
      }));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t('Governed fixture promotion failed.'));
    } finally {
      setBusy(false);
    }
  };
  return (
    <form
      className="governed-fixture-dialog"
      data-testid="governed-fixture-promote-dialog"
      onSubmit={handleSubmit}
    >
      <h4>{title}</h4>
      <label><span>{t('Fixture asset ID')}</span>
        <input
          id="promote-fixture-id"
          data-testid="promote-fixture-id"
          name="fixtureAssetId"
          required
        />
      </label>
      <label><span>{t('Classification')}</span>
        <select
          id="promote-fixture-classification"
          data-testid="promote-fixture-classification"
          name="classification"
        >
          {['PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED'].map((value) => (
            <option key={value} value={value}>{value}</option>
          ))}
        </select>
      </label>
      <label><span>{t('Retention days')}</span>
        <input
          id="promote-fixture-retention"
          data-testid="promote-fixture-retention"
          type="number"
          min={1}
          max={30}
          name="retentionDays"
          defaultValue={7}
        />
      </label>
      <label><span>{t('Redaction paths')}</span>
        <textarea
          id="promote-fixture-redactions"
          data-testid="promote-fixture-redactions"
          name="redactionPaths"
          placeholder="/phone"
        />
      </label>
      {error && <p role="alert" data-testid="promote-fixture-error">{error}</p>}
      <div>
        <button type="button" onClick={onCancel}>{t('Cancel')}</button>
        <button type="submit" data-testid="submit-promote-fixture" disabled={busy}>
          {busy ? t('Submitting') : t('Submit to governance')}
        </button>
      </div>
    </form>
  );
}

export interface PickerAsset {
  fixtureAssetId: string;
  revision: number;
  name: string;
  schemaFingerprint: string;
  usageCount?: number;
  lifecycle?: string;
  compatible?: boolean;
  currentSchemaFingerprint?: string;
}

interface GraphNodeFixturePickerProps {
  assets: readonly PickerAsset[];
  onSelect: (asset: PickerAsset) => void;
}

/** Selects an ACTIVE governed fixture for reuse while keeping identity explicit. */
export function GraphNodeFixturePicker({ assets, onSelect }: GraphNodeFixturePickerProps) {
  const { t } = useI18n();
  const [query, setQuery] = useState('');
  const visible = useMemo(() => assets.filter((asset) => (
    (asset.lifecycle === undefined || asset.lifecycle === 'ACTIVE')
    && (asset.compatible === undefined || asset.compatible)
  )).filter((asset) => `${asset.name} ${asset.fixtureAssetId}`.toLowerCase().includes(
    query.trim().toLowerCase(),
  )).sort((left, right) => left.name.localeCompare(right.name)), [assets, query]);
  return (
    <div className="graph-node-fixture-picker" data-testid="graph-node-fixture-picker">
      <input
        aria-label={t('Search governed fixtures')}
        data-testid="graph-node-fixture-search"
        value={query}
        onChange={(event) => setQuery(event.target.value)}
      />
      <ul>
        {visible.map((asset) => (
          <li key={`${asset.fixtureAssetId}:${asset.revision}`}>
            <button
              type="button"
              data-testid={`reuse-fixture-${asset.fixtureAssetId}`}
              onClick={() => onSelect(asset)}
            >
              <strong>{asset.name}</strong>
              <code>{`${asset.fixtureAssetId} r${asset.revision} · ${asset.schemaFingerprint.slice(0, 10)}`}</code>
              <small>{`${t('Used')} ${asset.usageCount ?? 0}`}</small>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}

interface ResourceFidelitySelectProps {
  value?: ResourceFidelity;
  onChange: (value: ResourceFidelity) => void;
}

const FIDELITY_VALUES: readonly ResourceFidelity[] = [
  'OUTPUT_LEVEL', 'PROTOCOL_DERIVED', 'TRANSPORT_LEVEL',
];

/** Chooses one of the runtime's three declared resource simulation fidelities. */
export function ResourceFidelitySelect({
  value = 'OUTPUT_LEVEL',
  onChange,
}: ResourceFidelitySelectProps) {
  const { t } = useI18n();
  return (
    <select
      aria-label={t('Resource fidelity')}
      data-testid="resource-fidelity-select"
      value={value}
      onChange={(event) => onChange(event.target.value as ResourceFidelity)}
    >
      {FIDELITY_VALUES.map((candidate) => (
        <option
          key={candidate}
          value={candidate}
        >
          {candidate}
        </option>
      ))}
    </select>
  );
}

interface FixtureStalenessNoticeProps {
  stale?: boolean;
  onRecapture?: () => void;
}

/** Calls attention to a governed fixture whose operator contract has moved. */
export function FixtureStalenessNotice({ stale = false, onRecapture }: FixtureStalenessNoticeProps) {
  const { t } = useI18n();
  if (!stale) return null;
  return (
    <p role="status" className="fixture-stale" data-testid="fixture-staleness-notice">
      {t('The governed fixture schema is stale.')}
      {onRecapture && (
        <button type="button" data-testid="recapture-fixture" onClick={onRecapture}>
          {t('Capture again')}
        </button>
      )}
    </p>
  );
}
