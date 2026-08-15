import { FilePlus2, Save, Snowflake } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';

import { useI18n } from '../../i18n/I18nProvider';
import {
  fetchCoverageInventory,
  freezeCoverageInventory,
  saveCoverageInventory,
} from '../api/correctnessAuthoringApi';
import type {
  CoverageInventory,
  CoverageObligation,
  StoredCoverageInventory,
} from '../model/authoring';
import type { CorrectnessWorkspaceProjection } from '../model/domain';
import {
  AssetState,
  AuthoringBoundary,
  commandId,
  MutationState,
  useExactAsset,
} from './shared';

export default function CoverageStudio({ workspace, available }: {
  workspace: CorrectnessWorkspaceProjection;
  available: boolean;
}) {
  const { t } = useI18n();
  const asset = useExactAsset<StoredCoverageInventory>(
    available,
    workspace.coverage.inventoryRef,
    fetchCoverageInventory,
  );
  const [draft, setDraft] = useState<CoverageInventory | null>(null);
  const [selectedId, setSelectedId] = useState('');
  const [reviewComment, setReviewComment] = useState('');
  const [mutation, setMutation] = useState<{
    tone: 'idle' | 'busy' | 'success' | 'error'; message: string;
  }>({ tone: 'idle', message: '' });

  useEffect(() => {
    if (!asset.value) return;
    setDraft(asset.value.inventory);
    setSelectedId((current) => current || asset.value?.inventory.obligations[0]?.obligationId || '');
  }, [asset.value]);

  const selected = draft?.obligations.find((item) => item.obligationId === selectedId) ?? null;
  const freezeBlockers = useMemo(() => {
    if (!draft) return [];
    const blockers: string[] = [];
    if (draft.obligations.length === 0) blockers.push('At least one obligation is required.');
    if (draft.obligations.some((item) => item.lifecycle === 'PROPOSED')) {
      blockers.push('Resolve every proposed obligation before freezing.');
    }
    if (draft.derivationSources.length === 0) blockers.push('An exact derivation source is required.');
    return blockers;
  }, [draft]);

  const updateSelected = (change: Partial<CoverageObligation>) => {
    if (!draft || !selected) return;
    setDraft({
      ...draft,
      obligations: draft.obligations.map((item) => (
        item.obligationId === selected.obligationId ? { ...item, ...change } : item
      )),
    });
    setMutation({ tone: 'idle', message: '' });
  };

  const addObligation = () => {
    if (!draft) return;
    const suffix = String(draft.obligations.length + 1).padStart(2, '0');
    const obligation: CoverageObligation = {
      obligationId: `business-obligation-${suffix}`,
      dimension: 'POLICY',
      title: 'New business obligation',
      statement: 'Describe the observable business behavior that must be proven.',
      risk: 'MEDIUM',
      owner: workspace.definition.owner,
      source: 'BUSINESS',
      lifecycle: 'PROPOSED',
      waiver: null,
      tags: [],
    };
    setDraft({ ...draft, obligations: [...draft.obligations, obligation] });
    setSelectedId(obligation.obligationId);
  };

  const save = async () => {
    if (!draft) return;
    setMutation({ tone: 'busy', message: t('Saving exact revision') });
    try {
      const response = await saveCoverageInventory(draft);
      asset.setValue(response.data);
      setDraft(response.data.inventory);
      setMutation({ tone: 'success', message: t('Coverage Inventory saved') });
    } catch (cause) {
      setMutation({ tone: 'error', message: errorMessage(cause) });
    }
  };

  const freeze = async () => {
    if (!draft || freezeBlockers.length > 0 || !reviewComment.trim()) return;
    setMutation({ tone: 'busy', message: t('Freezing reviewed denominator') });
    try {
      const response = await freezeCoverageInventory(
        draft.inventoryId,
        draft.revision,
        reviewComment.trim(),
        commandId(`freeze:${draft.inventoryId}`, draft.revision),
      );
      asset.setValue(response.data.stored);
      setDraft(response.data.stored.inventory);
      setMutation({ tone: 'success', message: t('Coverage denominator frozen') });
    } catch (cause) {
      setMutation({ tone: 'error', message: errorMessage(cause) });
    }
  };

  return (
    <AuthoringBoundary available={available}>
      <section className="correctness-authoring-panel" data-testid="coverage-studio">
        <header className="correctness-authoring-heading">
          <div><strong>{t('Coverage Studio')}</strong><span>{t('Define the denominator before measuring proof.')}</span></div>
          <button type="button" className="correctness-icon-command" onClick={addObligation} disabled={!draft || draft.lifecycle !== 'DRAFT'} title={t('Add obligation')}>
            <FilePlus2 size={17} /><span>{t('Add obligation')}</span>
          </button>
        </header>
        <AssetState state={asset.state} error={asset.error} />
        {draft && (
          <div className="correctness-authoring-split">
            <div className="correctness-authoring-list" role="listbox" aria-label={t('Coverage obligations')}>
              {draft.obligations.map((item) => (
                <button key={item.obligationId} type="button" role="option" aria-selected={item.obligationId === selectedId} onClick={() => setSelectedId(item.obligationId)}>
                  <span className="correctness-risk" data-risk={item.risk}>{t(item.risk)}</span>
                  <strong>{item.title}</strong><small>{t(item.dimension)} · {t(item.lifecycle)}</small>
                </button>
              ))}
            </div>
            {selected && (
              <div className="correctness-form-grid">
                <label>{t('Title')}<input value={selected.title} onChange={(event) => updateSelected({ title: event.target.value })} /></label>
                <label>{t('Dimension')}<select value={selected.dimension} onChange={(event) => updateSelected({ dimension: event.target.value as CoverageObligation['dimension'] })}>
                  {['CONTRACT', 'PATH', 'POLICY', 'RISK', 'INCIDENT', 'BOUNDARY'].map((value) => <option key={value}>{value}</option>)}
                </select></label>
                <label className="wide">{t('Business obligation')}<textarea rows={3} value={selected.statement} onChange={(event) => updateSelected({ statement: event.target.value })} /></label>
                <label>{t('Risk')}<select value={selected.risk} onChange={(event) => updateSelected({ risk: event.target.value as CoverageObligation['risk'] })}>
                  {['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'].map((value) => <option key={value}>{value}</option>)}
                </select></label>
                <label>{t('Source')}<select value={selected.source} onChange={(event) => updateSelected({ source: event.target.value as CoverageObligation['source'] })}>
                  {['AUTOMATED', 'BUSINESS', 'INCIDENT', 'MIGRATED'].map((value) => <option key={value}>{value}</option>)}
                </select></label>
                <label>{t('Lifecycle')}<select value={selected.lifecycle} onChange={(event) => updateSelected({ lifecycle: event.target.value as CoverageObligation['lifecycle'] })}>
                  {['PROPOSED', 'FROZEN', 'RETIRED'].map((value) => <option key={value}>{value}</option>)}
                </select></label>
                <label>{t('Tags')}<input value={selected.tags.join(', ')} onChange={(event) => updateSelected({ tags: splitValues(event.target.value) })} /></label>
              </div>
            )}
          </div>
        )}
        {draft && (
          <footer className="correctness-authoring-footer">
            <div className="correctness-review-command">
              <label>{t('Review comment')}<input value={reviewComment} onChange={(event) => setReviewComment(event.target.value)} placeholder={t('Explain why this denominator is complete')} /></label>
              {freezeBlockers.map((blocker) => <small key={blocker}>{t(blocker)}</small>)}
            </div>
            <MutationState state={mutation} />
            <button type="button" onClick={save} disabled={mutation.tone === 'busy' || draft.lifecycle !== 'DRAFT'}><Save size={17} />{t('Save draft')}</button>
            <button type="button" className="correctness-primary-command" onClick={freeze} disabled={mutation.tone === 'busy' || draft.lifecycle !== 'DRAFT' || freezeBlockers.length > 0 || !reviewComment.trim()}>
              <Snowflake size={17} />{t('Freeze denominator')}
            </button>
          </footer>
        )}
      </section>
    </AuthoringBoundary>
  );
}

function splitValues(value: string): string[] {
  return [...new Set(value.split(',').map((item) => item.trim()).filter(Boolean))].sort();
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause);
}
