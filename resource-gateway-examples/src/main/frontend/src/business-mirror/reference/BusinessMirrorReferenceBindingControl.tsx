import { Check, CircleAlert, LoaderCircle, X } from 'lucide-react';
import { useCallback, useRef, useState } from 'react';

import {
  fetchBusinessMirrorReferenceCandidates,
  resolveBusinessMirrorReferenceCandidate,
} from '../../api';
import { useI18n } from '../../i18n/I18nProvider';
import type { MessageId } from '../../i18n/messageCatalog';
import type { ReferenceCandidate, ReferenceCandidateSearch } from '../../shared/reference-picker/types';
import type { BusinessMirrorArtifactRef, BusinessMirrorPackageDraft } from '../domain';
import BusinessMirrorReferenceField from './BusinessMirrorReferenceField';
import {
  applyResolvedBusinessMirrorReference,
  BUSINESS_MIRROR_REFERENCE_KINDS,
  businessMirrorBindableCandidateKinds,
  type BusinessMirrorReferenceField as BindingField,
  clearBusinessMirrorReference,
  referenceBindingIntent,
} from './businessMirrorReferenceBinding';

type BindingState = 'idle' | 'resolving' | 'resolved' | 'drifted' | 'error';

export interface BusinessMirrorReferenceBindingControlProps {
  field: BindingField;
  label: MessageId;
  help: MessageId;
  draft: BusinessMirrorPackageDraft;
  currentStableId?: string;
  currentReferences?: BusinessMirrorArtifactRef[];
  editable: boolean;
  capabilityAvailable?: boolean;
  remediationAnchor: string;
  onDraft(draft: BusinessMirrorPackageDraft): void;
}

export default function BusinessMirrorReferenceBindingControl({
  field,
  label,
  help,
  draft,
  currentStableId = '',
  currentReferences = [],
  editable,
  capabilityAvailable = true,
  remediationAnchor,
  onDraft,
}: BusinessMirrorReferenceBindingControlProps) {
  const { m } = useI18n();
  const [selected, setSelected] = useState<ReferenceCandidate | null>(null);
  const [state, setState] = useState<BindingState>('idle');
  const [detail, setDetail] = useState('');
  const requestSequence = useRef(0);
  const kind = BUSINESS_MIRROR_REFERENCE_KINDS[field];
  const loadCandidates = useCallback<ReferenceCandidateSearch>(
    (request, signal) => fetchBusinessMirrorReferenceCandidates(kind, request, signal),
    [kind],
  );
  const hasCurrent = Boolean(currentStableId) || currentReferences.length > 0;

  const changeCandidate = async (candidate: ReferenceCandidate | null) => {
    requestSequence.current += 1;
    const requestId = requestSequence.current;
    setDetail('');
    if (!candidate) {
      setSelected(null);
      setState('idle');
      onDraft(clearBusinessMirrorReference(draft, field));
      return;
    }
    setState('resolving');
    try {
      const result = await resolveBusinessMirrorReferenceCandidate(
        candidate,
        referenceBindingIntent(field),
      );
      if (requestSequence.current !== requestId) return;
      if (result.status !== 'RESOLVED' || !result.candidate) {
        setSelected(null);
        setState(result.status === 'DRIFTED' ? 'drifted' : 'error');
        setDetail(result.errorCode || result.status);
        return;
      }
      onDraft(applyResolvedBusinessMirrorReference(draft, field, result));
      setSelected(result.candidate);
      setState('resolved');
    } catch (failure) {
      if (requestSequence.current !== requestId) return;
      setSelected(null);
      setState('error');
      setDetail(failure instanceof Error ? failure.message : 'RG.REFERENCE.UNKNOWN');
    }
  };
  const clearCurrent = () => {
    requestSequence.current += 1;
    setSelected(null);
    setState('idle');
    setDetail('');
    onDraft(clearBusinessMirrorReference(draft, field));
  };

  return (
    <section
      className="business-mirror-reference-binding"
      data-remediation-anchor={remediationAnchor}
      data-binding-state={state}
    >
      {hasCurrent && !selected && (
        <div className="business-mirror-current-reference">
          <Check aria-hidden="true" size={17} />
          <span>
            <small>{m('businessMirror.reference.current')}</small>
            <strong>{currentStableId || currentReferences.map((reference) => reference.id).join(', ')}</strong>
          </span>
          {currentReferences.length > 0 && (
            <details>
              <summary>{m('businessMirror.reference.technical')}</summary>
              {currentReferences.map((reference) => (
                <code key={`${reference.kind}:${reference.id}:${reference.revision}:${reference.fingerprint}`}>
                  {reference.kind} · {reference.id} · r{reference.revision} · {reference.fingerprint}
                </code>
              ))}
            </details>
          )}
          {editable && (
            <button
              aria-label={m('businessMirror.reference.clear')}
              onClick={clearCurrent}
              title={m('businessMirror.reference.clear')}
              type="button"
            >
              <X aria-hidden="true" size={15} />
            </button>
          )}
        </div>
      )}
      <BusinessMirrorReferenceField
        acceptedKinds={field === 'carrier' || field === 'channel'
          ? businessMirrorBindableCandidateKinds(field) : undefined}
        capabilityAvailable={capabilityAvailable}
        clearable
        disabled={!editable || state === 'resolving'}
        fallback={m('businessMirror.reference.unavailableFallback')}
        help={m(help)}
        id={`business-mirror-reference-${field}`}
        kind={kind}
        label={m(label)}
        labels={{
          capabilityUnavailable: m('businessMirror.reference.unavailable'),
          clear: m('businessMirror.reference.clear'),
          disabled: m('businessMirror.reference.disabled'),
          empty: m('businessMirror.reference.empty'),
          error: m('businessMirror.reference.error'),
          exactReference: m('businessMirror.reference.technical'),
          inputLabel: m(label),
          lifecycle: m('businessMirror.reference.lifecycle'),
          loadMore: m('businessMirror.reference.loadMore'),
          loading: m('businessMirror.reference.loading'),
          loadingMore: m('businessMirror.reference.loadingMore'),
          missingOwner: m('businessMirror.reference.missingOwner'),
          owner: m('businessMirror.reference.owner'),
          placeholder: m('businessMirror.reference.placeholder'),
          retry: m('businessMirror.reference.retry'),
          scope: m('businessMirror.reference.scope'),
          selected: m('businessMirror.reference.selected'),
          unavailable: m('businessMirror.reference.unavailable'),
        }}
        loadCandidates={loadCandidates}
        onChange={(candidate) => { void changeCandidate(candidate); }}
        value={selected}
      />
      {state === 'resolving' && (
        <p className="business-mirror-reference-status" role="status">
          <LoaderCircle aria-hidden="true" className="spin" size={16} />
          {m('businessMirror.reference.resolving')}
        </p>
      )}
      {state === 'resolved' && (
        <p className="business-mirror-reference-status success" role="status">
          <Check aria-hidden="true" size={16} />{m('businessMirror.reference.resolved')}
        </p>
      )}
      {(state === 'drifted' || state === 'error') && (
        <p className="business-mirror-reference-status error" role="alert">
          <CircleAlert aria-hidden="true" size={16} />
          {m(state === 'drifted'
            ? 'businessMirror.reference.drifted' : 'businessMirror.reference.resolveFailed')}
          {detail && <code>{detail}</code>}
        </p>
      )}
    </section>
  );
}
