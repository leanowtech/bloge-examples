import {
  type ChangeEvent,
  type FocusEvent,
  type KeyboardEvent,
  type ReactNode,
  useCallback,
  useEffect,
  useId,
  useRef,
  useState,
} from 'react';

import type {
  ReferenceCandidate,
  ReferenceCandidateSearch,
  ReferenceErrorStatus,
  ReferenceLoadState,
  ReferencePage,
  ReferenceQuery,
} from './types';
import './referencePicker.css';

export interface AsyncReferenceComboboxLabels {
  inputLabel: string;
  placeholder: string;
  loading: string;
  empty: string;
  error: string;
  unavailable: string;
  retry: string;
  loadMore: string;
  loadingMore: string;
  selected: string;
  exactReference: string;
  disabled: string;
}

const DEFAULT_LABELS: AsyncReferenceComboboxLabels = {
  inputLabel: 'Search references',
  placeholder: 'Search by name, ID, owner, or scope',
  loading: 'Loading references...',
  empty: 'No matching references.',
  error: 'References could not be loaded.',
  unavailable: 'The reference directory is unavailable.',
  retry: 'Retry',
  loadMore: 'Load more',
  loadingMore: 'Loading more...',
  selected: 'Selected reference',
  exactReference: 'Exact reference',
  disabled: 'Unavailable for selection',
};

export interface AsyncReferenceComboboxProps {
  loadCandidates: ReferenceCandidateSearch;
  value?: ReferenceCandidate | null;
  onChange?: (candidate: ReferenceCandidate | null) => void;
  labels?: Partial<AsyncReferenceComboboxLabels>;
  id?: string;
  name?: string;
  disabled?: boolean;
  placeholder?: string;
  debounceMs?: number;
  minQueryLength?: number;
  pageSize?: number;
  className?: string;
  unavailableFallback?: ReactNode;
}

interface FailedRequest {
  query: ReferenceQuery;
  append: boolean;
  status: ReferenceErrorStatus;
}

const DEFAULT_DEBOUNCE_MS = 250;
const DEFAULT_PAGE_SIZE = 20;

export default function AsyncReferenceCombobox({
  loadCandidates,
  value,
  onChange,
  labels: labelOverrides,
  id,
  name,
  disabled = false,
  placeholder,
  debounceMs = DEFAULT_DEBOUNCE_MS,
  minQueryLength = 2,
  pageSize = DEFAULT_PAGE_SIZE,
  className,
  unavailableFallback,
}: AsyncReferenceComboboxProps) {
  const labels = { ...DEFAULT_LABELS, ...labelOverrides };
  const generatedId = useId();
  const inputId = id ?? `reference-picker-${generatedId}`;
  const listboxId = `${inputId}-listbox`;
  const selectionId = `${inputId}-selection`;
  const [query, setQuery] = useState(value?.displayName ?? '');
  const [internalValue, setInternalValue] = useState<ReferenceCandidate | null>(value ?? null);
  const selected = value === undefined ? internalValue : value;
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState<ReferenceCandidate[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loadState, setLoadState] = useState<ReferenceLoadState>('idle');
  const [activeIndex, setActiveIndex] = useState(-1);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const controllerRef = useRef<AbortController | null>(null);
  const requestIdRef = useRef(0);
  const retryRef = useRef<FailedRequest | null>(null);

  useEffect(() => {
    if (value !== undefined) {
      setInternalValue(value);
      setQuery(value?.displayName ?? '');
    }
  }, [value]);

  useEffect(() => () => {
    controllerRef.current?.abort();
  }, []);

  const selectableIndexes = items.reduce<number[]>((indexes, candidate, index) => {
    if (!isCandidateDisabled(candidate)) indexes.push(index);
    return indexes;
  }, []);

  const selectCandidate = useCallback((candidate: ReferenceCandidate) => {
    if (isCandidateDisabled(candidate)) return;
    setInternalValue(candidate);
    setQuery(candidate.displayName);
    setOpen(false);
    setActiveIndex(-1);
    onChange?.(candidate);
  }, [onChange]);

  const mergePage = useCallback((page: ReferencePage, append: boolean) => {
    setItems((current) => {
      const merged = append ? [...current, ...page.items] : [...page.items];
      const seen = new Set<string>();
      return merged.filter((candidate) => {
        const key = exactReferenceKey(candidate);
        if (seen.has(key)) return false;
        seen.add(key);
        return true;
      });
    });
    setNextCursor(page.nextCursor);
  }, []);

  const runRequest = useCallback(async (request: ReferenceQuery, append: boolean) => {
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    const requestId = ++requestIdRef.current;
    if (append) setIsLoadingMore(true);
    else setLoadState('loading');

    try {
      const page = await loadCandidates(request, controller.signal);
      if (controller.signal.aborted || requestId !== requestIdRef.current) return;
      mergePage(page, append);
      setLoadState(page.items.length > 0 || append ? 'ready' : 'empty');
      setActiveIndex(-1);
    } catch (failure) {
      if (controller.signal.aborted || requestId !== requestIdRef.current) return;
      const status = referenceErrorStatus(failure);
      const failed = { query: request, append, status };
      retryRef.current = failed;
      setLoadState(status);
    } finally {
      if (requestId === requestIdRef.current) setIsLoadingMore(false);
    }
  }, [loadCandidates, mergePage]);

  useEffect(() => {
    if (!open || disabled) return undefined;
    const normalizedQuery = query.trim();
    if (normalizedQuery.length > 0 && normalizedQuery.length < minQueryLength) {
      controllerRef.current?.abort();
      setItems([]);
      setNextCursor(null);
      setLoadState('idle');
      setActiveIndex(-1);
      return undefined;
    }
    const timer = window.setTimeout(() => {
      void runRequest({ query: normalizedQuery, cursor: null, limit: pageSize }, false);
    }, debounceMs);
    return () => {
      window.clearTimeout(timer);
      controllerRef.current?.abort();
    };
  }, [debounceMs, disabled, minQueryLength, open, pageSize, query, runRequest]);

  const openPicker = () => {
    if (!disabled) setOpen(true);
  };

  const handleInput = (event: ChangeEvent<HTMLInputElement>) => {
    const nextQuery = event.target.value;
    setQuery(nextQuery);
    setOpen(true);
    setActiveIndex(-1);
    if (selected) {
      setInternalValue(null);
      onChange?.(null);
    }
  };

  const handleInputFocus = (_event: FocusEvent<HTMLInputElement>) => openPicker();

  const handleKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'Escape') {
      setOpen(false);
      setActiveIndex(-1);
      return;
    }
    if (!open) {
      if (event.key === 'ArrowDown' || event.key === 'Enter') {
        event.preventDefault();
        openPicker();
      }
      return;
    }
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setActiveIndex((current) => moveActiveIndex(current, 1, selectableIndexes));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      setActiveIndex((current) => moveActiveIndex(current, -1, selectableIndexes));
    } else if (event.key === 'Enter' && activeIndex >= 0) {
      event.preventDefault();
      const candidate = items[activeIndex];
      if (candidate) selectCandidate(candidate);
    }
  };

  const retry = () => {
    const request = retryRef.current;
    if (request) void runRequest(request.query, request.append);
  };

  const loadMore = () => {
    if (!nextCursor || isLoadingMore) return;
    void runRequest({ query: query.trim(), cursor: nextCursor, limit: pageSize }, true);
  };

  const statusMessage = loadState === 'loading'
    ? labels.loading
    : loadState === 'empty'
      ? labels.empty
      : loadState === 'error'
        ? labels.error
        : loadState === 'unavailable'
          ? labels.unavailable
          : '';
  const statusRole = loadState === 'error' || loadState === 'unavailable' ? 'alert' : 'status';
  const classNames = ['reference-picker', className].filter(Boolean).join(' ');

  return (
    <div className={classNames} data-testid="async-reference-combobox">
      <label className="reference-picker-label" htmlFor={inputId}>{labels.inputLabel}</label>
      <div className="reference-picker-control">
        <input
          aria-activedescendant={open && activeIndex >= 0 ? optionId(inputId, activeIndex) : undefined}
          aria-autocomplete="list"
          aria-controls={listboxId}
          aria-expanded={open}
          aria-haspopup="listbox"
          aria-label={labels.inputLabel}
          autoComplete="off"
          className="reference-picker-input"
          disabled={disabled}
          id={inputId}
          name={name}
          onChange={handleInput}
          onFocus={handleInputFocus}
          onKeyDown={handleKeyDown}
          placeholder={placeholder ?? labels.placeholder}
          role="combobox"
          value={query}
        />
      </div>

      {open && (
        <div className="reference-picker-menu" data-testid="reference-picker-menu">
          {loadState !== 'ready' && loadState !== 'idle' && (
            <div aria-live="polite" className="reference-picker-status" role={statusRole}>
              <span>{statusMessage}</span>
              {loadState === 'unavailable' && unavailableFallback && (
                <span className="reference-picker-fallback">{unavailableFallback}</span>
              )}
              {(loadState === 'error' || loadState === 'unavailable') && (
                <button data-testid="reference-picker-retry" type="button" onClick={retry}>{labels.retry}</button>
              )}
            </div>
          )}
          {loadState === 'idle' && query.trim().length > 0 && query.trim().length < minQueryLength && (
            <div aria-live="polite" className="reference-picker-status">{labels.empty}</div>
          )}
          {items.length > 0 && (
            <ul aria-label={labels.inputLabel} className="reference-picker-options" id={listboxId} role="listbox">
              {items.map((candidate, index) => {
                const optionIdValue = optionId(inputId, index);
                return (
                  <li
                    aria-disabled={isCandidateDisabled(candidate) || undefined}
                    aria-selected={activeIndex === index}
                    className={`reference-picker-option${activeIndex === index ? ' is-active' : ''}${isCandidateDisabled(candidate) ? ' is-disabled' : ''}`}
                    id={optionIdValue}
                    key={exactReferenceKey(candidate)}
                    onMouseDown={(event) => event.preventDefault()}
                    onClick={() => selectCandidate(candidate)}
                    role="option"
                  >
                    <span className="reference-picker-option-heading">
                      <strong>{candidate.displayName}</strong>
                      <span>{candidate.kind}</span>
                    </span>
                    <span className="reference-picker-option-meta">
                      {[formatScope(candidate), candidate.owner?.displayName, candidate.lifecycle, candidate.compatibility]
                        .filter(Boolean).join(' · ')}
                    </span>
                    {isCandidateDisabled(candidate) && (
                      <span className="reference-picker-option-disabled">
                        {candidate.disabledReasonCode || labels.disabled}
                      </span>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
          {nextCursor && loadState === 'ready' && (
            <button className="reference-picker-load-more" data-testid="reference-picker-load-more" disabled={isLoadingMore} type="button" onClick={loadMore}>
              {isLoadingMore ? labels.loadingMore : labels.loadMore}
            </button>
          )}
        </div>
      )}

      {selected && (
        <section aria-label={labels.selected} className="reference-picker-selection" data-testid="reference-picker-selection" id={selectionId}>
          <strong>{selected.displayName}</strong>
          <span>{selected.kind} · {formatScope(selected)}</span>
          <details>
            <summary>{labels.exactReference}</summary>
            <dl>
              <div><dt>kind</dt><dd>{selected.kind}</dd></div>
              <div><dt>id</dt><dd>{selected.id}</dd></div>
              <div><dt>revision</dt><dd>{selected.revision}</dd></div>
              <div><dt>fingerprint</dt><dd>{selected.fingerprint}</dd></div>
              <div><dt>authority</dt><dd>{selected.authority}</dd></div>
            </dl>
          </details>
        </section>
      )}
    </div>
  );
}

function exactReferenceKey(candidate: ReferenceCandidate): string {
  return `${candidate.kind}\u0000${candidate.id}\u0000${candidate.revision}\u0000${candidate.fingerprint}`;
}

function isCandidateDisabled(candidate: ReferenceCandidate): boolean {
  return Boolean(candidate.disabledReasonCode) || candidate.compatibility === 'INCOMPATIBLE';
}

function formatScope(candidate: ReferenceCandidate): string {
  const { organizationId, projectId, environmentId, region } = candidate.scope;
  return [organizationId, projectId, environmentId, region].filter(Boolean).join(' / ');
}

function optionId(inputId: string, index: number): string {
  return `${inputId}-option-${index}`;
}

function moveActiveIndex(current: number, direction: 1 | -1, selectableIndexes: readonly number[]): number {
  if (selectableIndexes.length === 0) return -1;
  const currentPosition = selectableIndexes.indexOf(current);
  if (currentPosition === -1) return direction === 1 ? selectableIndexes[0] : selectableIndexes[selectableIndexes.length - 1];
  const nextPosition = (currentPosition + direction + selectableIndexes.length) % selectableIndexes.length;
  return selectableIndexes[nextPosition];
}

function referenceErrorStatus(failure: unknown): ReferenceErrorStatus {
  if (isReferenceErrorStatus(failure) && failure.status === 'unavailable') return 'unavailable';
  return 'error';
}

function isReferenceErrorStatus(failure: unknown): failure is { status: ReferenceErrorStatus } {
  return typeof failure === 'object'
    && failure !== null
    && 'status' in failure
    && ((failure as { status?: unknown }).status === 'error' || (failure as { status?: unknown }).status === 'unavailable');
}
