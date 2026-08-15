import { BadgeCheck, Eye, FileKey2, Save, ShieldCheck } from 'lucide-react';
import { useEffect, useState } from 'react';

import { useI18n } from '../../i18n/I18nProvider';
import {
  approveFixtureAsset,
  fetchFixtureAsset,
  fetchFixtureMaterial,
  saveFixtureAsset,
  transitionFixtureAsset,
  writeFixtureMaterial,
} from '../api/correctnessAuthoringApi';
import type {
  FixtureAssetDescriptor,
  FixtureMaterial,
  FixtureMaterialReceipt,
  StoredFixtureAsset,
} from '../model/authoring';
import type { CorrectnessWorkspaceProjection } from '../model/domain';
import {
  AssetState,
  AuthoringBoundary,
  commandId,
  MutationState,
  useExactAsset,
} from './shared';

export default function FixtureStudio({ workspace, catalogAvailable, materialAvailable }: {
  workspace: CorrectnessWorkspaceProjection;
  catalogAvailable: boolean;
  materialAvailable: boolean;
}) {
  const { t } = useI18n();
  const [selectedId, setSelectedId] = useState(workspace.fixtures.rows[0]?.descriptorRef.id ?? '');
  const selectedSummary = workspace.fixtures.rows.find((item) => item.descriptorRef.id === selectedId)
    ?? workspace.fixtures.rows[0] ?? null;
  const asset = useExactAsset<StoredFixtureAsset>(
    catalogAvailable,
    selectedSummary?.descriptorRef ?? null,
    fetchFixtureAsset,
  );
  const [draft, setDraft] = useState<FixtureAssetDescriptor | null>(null);
  const [material, setMaterial] = useState<FixtureMaterial | null>(null);
  const [payload, setPayload] = useState<Record<string, unknown>>({});
  const [materialState, setMaterialState] = useState<'IDLE' | 'LOADING' | 'READY' | 'ERROR'>('IDLE');
  const [materialError, setMaterialError] = useState('');
  const [reviewComment, setReviewComment] = useState('');
  const [mutation, setMutation] = useState<{
    tone: 'idle' | 'busy' | 'success' | 'error'; message: string;
  }>({ tone: 'idle', message: '' });

  useEffect(() => {
    setDraft(asset.value?.descriptor ?? null);
    setMaterial(null);
    setPayload({});
    setMaterialState('IDLE');
  }, [asset.value]);

  const loadMaterial = async () => {
    if (!draft || !materialAvailable) return;
    setMaterialState('LOADING');
    setMaterialError('');
    try {
      const value = await fetchFixtureMaterial(draft.fixtureAssetId, draft.materialRef);
      setMaterial(value);
      setPayload(objectValue(value.payload));
      setMaterialState('READY');
    } catch (cause) {
      setMaterialState('ERROR');
      setMaterialError(errorMessage(cause));
    }
  };

  const saveDescriptor = () => mutate(t('Saving Fixture descriptor'), async () => {
    if (!draft) return;
    const response = await saveFixtureAsset(draft);
    asset.setValue(response.data);
    setDraft(response.data.descriptor);
    return t('Fixture descriptor saved');
  });

  const saveMaterial = () => mutate(t('Writing protected Fixture material'), async () => {
    if (!draft || !material) return;
    const receipt = material.receipt as FixtureMaterialReceipt & MaterialAuthorityFields;
    const written = await writeFixtureMaterial({
      schemaVersion: 'bloge.fixtureMaterialWriteRequest.v2',
      fixtureAssetId: draft.fixtureAssetId,
      expectedRevision: draft.materialRef.revision,
      source: receipt.source,
      subject: receipt.subject,
      target: receipt.target,
      schemaRef: receipt.schemaRef,
      classification: draft.classification,
      retention: draft.retention,
      redaction: draft.redaction,
      payload,
    });
    const response = await saveFixtureAsset({ ...draft, materialRef: written.materialRef });
    asset.setValue(response.data);
    setDraft(response.data.descriptor);
    setMaterial({
      schemaVersion: 'bloge.fixtureMaterial.v2',
      receipt: written,
      payload,
      payloadReturned: true,
    });
    return t('Protected material saved and rebound');
  });

  const transition = (command: 'review-ready' | 'activate' | 'revoke') => mutate(
    t('Updating Fixture lifecycle'),
    async () => {
      if (!draft) return;
      const response = await transitionFixtureAsset(draft.fixtureAssetId, draft.revision, command);
      asset.setValue(response.data);
      setDraft(response.data.descriptor);
      return t('Fixture lifecycle updated');
    },
  );

  const approve = () => mutate(t('Approving Fixture descriptor'), async () => {
    if (!draft || !reviewComment.trim()) return;
    const response = await approveFixtureAsset(
      draft.fixtureAssetId,
      draft.revision,
      reviewComment.trim(),
      commandId(`approve:${draft.fixtureAssetId}`, draft.revision),
    );
    asset.setValue(response.data.stored);
    setDraft(response.data.stored.descriptor);
    return t('Fixture descriptor approved');
  });

  async function mutate(pending: string, operation: () => Promise<string | undefined>) {
    setMutation({ tone: 'busy', message: pending });
    try {
      const success = await operation();
      setMutation(success ? { tone: 'success', message: success } : { tone: 'idle', message: '' });
    } catch (cause) {
      setMutation({ tone: 'error', message: errorMessage(cause) });
    }
  }

  return (
    <AuthoringBoundary available={catalogAvailable}>
      <section className="correctness-authoring-panel" data-testid="fixture-studio">
        <header className="correctness-authoring-heading"><div><strong>{t('Fixture Editor')}</strong><span>{t('Catalog metadata and protected material use separate authorities.')}</span></div></header>
        <div className="correctness-authoring-split">
          <div className="correctness-authoring-list" role="listbox" aria-label={t('Fixture catalog')}>
            {workspace.fixtures.rows.map((item) => <button key={`${item.descriptorRef.id}:${item.descriptorRef.revision}`} type="button" role="option" aria-selected={item.descriptorRef.id === selectedId} onClick={() => setSelectedId(item.descriptorRef.id)}><span className="correctness-risk" data-risk={item.lifecycle === 'STALE' ? 'HIGH' : 'LOW'}>{t(item.lifecycle)}</span><strong>{item.name}</strong><small>{item.variantKey} · {t(item.classification)}</small></button>)}
          </div>
          <div className="correctness-fixture-editor">
            <AssetState state={asset.state} error={asset.error} />
            {draft && <>
              <div className="correctness-form-grid">
                <label>{t('Fixture name')}<input value={draft.name} onChange={(event) => setDraft({ ...draft, name: event.target.value })} /></label>
                <label>{t('Variant')}<input value={draft.variantKey} onChange={(event) => setDraft({ ...draft, variantKey: event.target.value })} /></label>
                <label>{t('Classification')}<select value={draft.classification} onChange={(event) => setDraft({ ...draft, classification: event.target.value })}>{['PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED'].map((item) => <option key={item}>{item}</option>)}</select></label>
                <label>{t('Retention days')}<input type="number" min="1" value={draft.retention.retentionDays} onChange={(event) => setDraft({ ...draft, retention: { ...draft.retention, retentionDays: Number(event.target.value) } })} /></label>
                <label className="wide">{t('Redacted paths')}<input value={draft.redaction.redactedPaths.join(', ')} onChange={(event) => setDraft({ ...draft, redaction: { ...draft.redaction, redactedPaths: splitValues(event.target.value) } })} /></label>
                <p className="correctness-exact-ref wide">{draft.schemaRef.id} · r{draft.schemaRef.revision} · {t(draft.lifecycle)}</p>
                <div className="correctness-form-commands wide"><button type="button" onClick={saveDescriptor} disabled={mutation.tone === 'busy'}><Save size={16} />{t('Save metadata')}</button></div>
              </div>
              <section className="correctness-material-authority">
                <header><div><FileKey2 size={18} /><span><strong>{t('Protected material')}</strong><small>{t('Never included in Workspace or governance exports.')}</small></span></div><button type="button" onClick={loadMaterial} disabled={!materialAvailable || materialState === 'LOADING'}><Eye size={16} />{t('Load protected data')}</button></header>
                {!materialAvailable && <p>{t('The deployment has not advertised Fixture material access.')}</p>}
                {materialState === 'LOADING' && <p>{t('Loading protected material')}</p>}
                {materialState === 'ERROR' && <p className="error" role="alert">{materialError}</p>}
                {materialState === 'READY' && <><ProtectedKeyValueEditor value={payload} onChange={setPayload} /><div className="correctness-form-commands"><button type="button" className="correctness-primary-command" onClick={saveMaterial} disabled={mutation.tone === 'busy'}><ShieldCheck size={16} />{t('Save protected data')}</button></div></>}
              </section>
            </>}
          </div>
        </div>
        {draft && <footer className="correctness-authoring-footer">
          <div className="correctness-review-command"><label>{t('Review comment')}<input value={reviewComment} onChange={(event) => setReviewComment(event.target.value)} placeholder={t('Record data governance approval')} /></label></div>
          <MutationState state={mutation} />
          <button type="button" onClick={() => transition('review-ready')} disabled={mutation.tone === 'busy' || draft.lifecycle !== 'DRAFT'}>{t('Send to review')}</button>
          <button type="button" onClick={approve} disabled={mutation.tone === 'busy' || draft.lifecycle !== 'PROPOSED' || !reviewComment.trim()}><BadgeCheck size={16} />{t('Approve metadata')}</button>
          <button type="button" className="correctness-primary-command" onClick={() => transition('activate')} disabled={mutation.tone === 'busy' || draft.lifecycle !== 'APPROVED'}>{t('Activate Fixture')}</button>
        </footer>}
      </section>
    </AuthoringBoundary>
  );
}

function ProtectedKeyValueEditor({ value, onChange }: { value: Record<string, unknown>; onChange(value: Record<string, unknown>): void }) {
  const { t } = useI18n();
  const rows = Object.entries(value);
  return <div className="correctness-kv-editor protected">{rows.map(([key, current]) => <div key={key}><code>{key}</code><input aria-label={`${key} value`} value={String(current ?? '')} onChange={(event) => onChange({ ...value, [key]: inferPrimitive(event.target.value) })} /><button type="button" title={t('Remove field')} onClick={() => { const next = { ...value }; delete next[key]; onChange(next); }}>×</button></div>)}<button type="button" onClick={() => onChange({ ...value, [`field${rows.length + 1}`]: '' })}>{t('Add data field')}</button></div>;
}

interface MaterialAuthorityFields {
  source: unknown;
  subject: unknown;
  target: unknown;
  schemaRef: unknown;
}

function objectValue(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown> : {};
}

function inferPrimitive(value: string): string | number | boolean | null {
  if (value === 'true') return true;
  if (value === 'false') return false;
  if (value === 'null') return null;
  const number = Number(value);
  return value.trim() && Number.isFinite(number) ? number : value;
}

function splitValues(value: string): string[] {
  return [...new Set(value.split(',').map((item) => item.trim()).filter(Boolean))].sort();
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause);
}
