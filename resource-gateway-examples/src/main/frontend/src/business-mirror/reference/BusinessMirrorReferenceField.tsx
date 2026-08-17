import { X } from 'lucide-react';
import type { ReactNode } from 'react';
import { useCallback, useEffect, useState } from 'react';

import AsyncReferenceCombobox, {
  type AsyncReferenceComboboxLabels,
} from '../../shared/reference-picker/AsyncReferenceCombobox';
import type {
  ReferenceCandidate,
  ReferenceCandidateSearch,
} from '../../shared/reference-picker/types';
import './BusinessMirrorReferenceField.css';

export interface BusinessMirrorReferenceFieldLabels extends Partial<AsyncReferenceComboboxLabels> {
  capabilityUnavailable: string;
  clear: string;
  owner: string;
  scope: string;
  lifecycle: string;
  missingOwner: string;
}

export interface BusinessMirrorReferenceFieldProps {
  label: string;
  help?: ReactNode;
  kind: string;
  acceptedKinds?: readonly string[];
  value?: ReferenceCandidate | null;
  loadCandidates: ReferenceCandidateSearch;
  onChange: (candidate: ReferenceCandidate | null) => void;
  disabled?: boolean;
  clearable?: boolean;
  capabilityAvailable: boolean;
  fallback?: ReactNode;
  labels?: Partial<BusinessMirrorReferenceFieldLabels>;
  id?: string;
  name?: string;
  className?: string;
}

const DEFAULT_LABELS: BusinessMirrorReferenceFieldLabels = {
  capabilityUnavailable: 'Reference discovery is unavailable in this deployment.',
  clear: 'Clear reference',
  owner: 'Owner',
  scope: 'Scope',
  lifecycle: 'Lifecycle',
  missingOwner: 'Unassigned',
};

export default function BusinessMirrorReferenceField({
  label,
  help,
  kind,
  acceptedKinds,
  value,
  loadCandidates,
  onChange,
  disabled = false,
  clearable = true,
  capabilityAvailable,
  fallback,
  labels: labelOverrides,
  id,
  name,
  className,
}: BusinessMirrorReferenceFieldProps) {
  const labels = { ...DEFAULT_LABELS, ...labelOverrides };
  const pickerLabels = { ...labels, inputLabel: labels.inputLabel ?? label };
  const [internalValue, setInternalValue] = useState<ReferenceCandidate | null>(value ?? null);
  const selectedValue = value === undefined ? internalValue : value;
  useEffect(() => {
    if (value !== undefined) setInternalValue(value);
  }, [value]);
  const loadCandidatesForKind = useCallback<ReferenceCandidateSearch>(async (query, signal) => {
    const page = await loadCandidates(query, signal);
    return {
      ...page,
      items: page.items.filter((candidate) => (
        acceptedKinds ? acceptedKinds.includes(candidate.kind) : candidate.kind === kind
      )),
    };
  }, [acceptedKinds, kind, loadCandidates]);
  const notifyChange = useCallback((candidate: ReferenceCandidate | null) => {
    setInternalValue(candidate);
    onChange(candidate);
  }, [onChange]);
  const handleChange = useCallback((candidate: ReferenceCandidate | null) => {
    if (candidate === null) {
      if (clearable) notifyChange(null);
      return;
    }
    if (acceptedKinds ? acceptedKinds.includes(candidate.kind) : candidate.kind === kind) {
      notifyChange(candidate);
    }
  }, [acceptedKinds, clearable, kind, notifyChange]);
  const classNames = ['business-mirror-reference-field', className].filter(Boolean).join(' ');
  const pickerDisabled = disabled || (selectedValue !== null && !clearable);
  const helpId = id ? `${id}-help` : undefined;

  return (
    <div className={classNames} data-reference-kind={kind} data-testid="business-mirror-reference-field">
      <div className="business-mirror-reference-field-heading">
        <span className="business-mirror-reference-field-label">{label}</span>
        {help && <span className="business-mirror-reference-field-help" id={helpId}>{help}</span>}
      </div>

      {capabilityAvailable ? (
        <div className="business-mirror-reference-field-picker">
          <AsyncReferenceCombobox
            className="business-mirror-reference-combobox"
            disabled={pickerDisabled}
            id={id}
            labels={pickerLabels}
            loadCandidates={loadCandidatesForKind}
            name={name}
            onChange={handleChange}
            unavailableFallback={fallback}
            value={selectedValue}
          />
          {selectedValue && clearable && (
            <button
              aria-label={labels.clear}
              className="business-mirror-reference-clear"
              data-testid="business-mirror-reference-clear"
              disabled={disabled}
              title={labels.clear}
              type="button"
              onClick={() => notifyChange(null)}
            >
              <X aria-hidden="true" size={15} />
            </button>
          )}
          {selectedValue && <ReferenceBusinessMetadata candidate={selectedValue} labels={labels} />}
        </div>
      ) : (
        <div
          aria-live="polite"
          className="business-mirror-reference-unavailable"
          data-testid="business-mirror-reference-unavailable"
          role="status"
        >
          <strong>{labels.capabilityUnavailable}</strong>
          {fallback && <span>{fallback}</span>}
        </div>
      )}
    </div>
  );
}

interface ReferenceBusinessMetadataProps {
  candidate: ReferenceCandidate;
  labels: BusinessMirrorReferenceFieldLabels;
}

function ReferenceBusinessMetadata({ candidate, labels }: ReferenceBusinessMetadataProps) {
  return (
    <dl className="business-mirror-reference-metadata" data-testid="business-mirror-reference-metadata">
      <div>
        <dt>{labels.owner}</dt>
        <dd>{candidate.owner?.displayName || labels.missingOwner}</dd>
      </div>
      <div>
        <dt>{labels.scope}</dt>
        <dd>{formatScope(candidate)}</dd>
      </div>
      <div>
        <dt>{labels.lifecycle}</dt>
        <dd>{candidate.lifecycle}</dd>
      </div>
    </dl>
  );
}

function formatScope(candidate: ReferenceCandidate): string {
  const { organizationId, projectId, environmentId, region } = candidate.scope;
  return [organizationId, projectId, environmentId, region].filter(Boolean).join(' / ');
}
