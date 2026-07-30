export const DSL_AUTHOR_HANDOFF_KEY = 'bloge.visualAuthoring.dslHandoff.v1';
export const MAXIMUM_DSL_HANDOFF_CHARACTERS = 500_000;

const HANDOFF_TTL_MILLIS = 10 * 60 * 1000;

export interface DslAuthorHandoff {
  schemaVersion: 'bloge.dslAuthorHandoff.v1';
  sourceId: string;
  dsl: string;
  expiresAt: number;
}

export interface DslAuthorHandoffResult {
  accepted: boolean;
  message: string;
}

export function stageDslAuthorHandoff(
  sourceId: string,
  dsl: string,
  now = Date.now(),
): DslAuthorHandoffResult {
  const normalizedSourceId = sourceId.trim() || 'inline.dsl';
  if (!dsl.trim()) {
    return { accepted: false, message: 'DSL source is empty.' };
  }
  if (dsl.length > MAXIMUM_DSL_HANDOFF_CHARACTERS) {
    return {
      accepted: false,
      message: 'DSL is too large for browser handoff. Open Author and use its DSL import form.',
    };
  }
  if (typeof window === 'undefined') {
    return { accepted: false, message: 'Browser session storage is unavailable.' };
  }
  try {
    const handoff: DslAuthorHandoff = {
      schemaVersion: 'bloge.dslAuthorHandoff.v1',
      sourceId: normalizedSourceId,
      dsl,
      expiresAt: now + HANDOFF_TTL_MILLIS,
    };
    window.sessionStorage.setItem(DSL_AUTHOR_HANDOFF_KEY, JSON.stringify(handoff));
    return { accepted: true, message: 'DSL handoff staged.' };
  } catch {
    return {
      accepted: false,
      message: 'Browser storage could not stage this DSL. Open Author and use its DSL import form.',
    };
  }
}

export function takeDslAuthorHandoff(now = Date.now()): DslAuthorHandoff | null {
  return readDslAuthorHandoff(now, true);
}

export function peekDslAuthorHandoff(now = Date.now()): DslAuthorHandoff | null {
  return readDslAuthorHandoff(now, false);
}

export function clearDslAuthorHandoff(): void {
  if (typeof window === 'undefined') {
    return;
  }
  try {
    window.sessionStorage.removeItem(DSL_AUTHOR_HANDOFF_KEY);
  } catch {
    // Restricted browser contexts may deny session storage.
  }
}

function readDslAuthorHandoff(now: number, consume: boolean): DslAuthorHandoff | null {
  if (typeof window === 'undefined') {
    return null;
  }
  let raw = '';
  try {
    raw = window.sessionStorage.getItem(DSL_AUTHOR_HANDOFF_KEY) ?? '';
    if (consume) {
      window.sessionStorage.removeItem(DSL_AUTHOR_HANDOFF_KEY);
    }
  } catch {
    return null;
  }
  if (!raw) {
    return null;
  }
  try {
    const candidate = JSON.parse(raw) as Partial<DslAuthorHandoff>;
    if (
      candidate.schemaVersion !== 'bloge.dslAuthorHandoff.v1'
      || typeof candidate.sourceId !== 'string'
      || !candidate.sourceId.trim()
      || typeof candidate.dsl !== 'string'
      || !candidate.dsl.trim()
      || candidate.dsl.length > MAXIMUM_DSL_HANDOFF_CHARACTERS
      || typeof candidate.expiresAt !== 'number'
      || candidate.expiresAt < now
    ) {
      clearDslAuthorHandoff();
      return null;
    }
    return candidate as DslAuthorHandoff;
  } catch {
    clearDslAuthorHandoff();
    return null;
  }
}
