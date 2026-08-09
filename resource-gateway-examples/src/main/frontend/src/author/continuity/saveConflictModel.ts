export interface SaveConflictFact {
  id: string;
  label: string;
  value: string | number;
}

export interface SaveConflictSnapshot {
  revision: number;
  fingerprint: string;
  facts: SaveConflictFact[];
}

export interface SaveConflictComparisonRow {
  id: string;
  label: string;
  localValue: string;
  authoritativeValue: string;
  changed: boolean;
}

/** Projects domain facts into a stable, human-readable comparison without exposing raw draft JSON. */
export function projectSaveConflictComparison(
  local: SaveConflictSnapshot,
  authoritative: SaveConflictSnapshot,
): SaveConflictComparisonRow[] {
  const localById = new Map(local.facts.map((fact) => [fact.id, fact]));
  const authoritativeById = new Map(authoritative.facts.map((fact) => [fact.id, fact]));
  const localIds = new Set(local.facts.map((fact) => fact.id));
  const orderedFacts = [
    ...local.facts,
    ...authoritative.facts.filter((fact) => !localIds.has(fact.id)),
  ];
  return orderedFacts.map((fact) => {
    const localFact = localById.get(fact.id);
    const authoritativeFact = authoritativeById.get(fact.id);
    const localValue = localFact ? String(localFact.value) : '-';
    const authoritativeValue = authoritativeFact ? String(authoritativeFact.value) : '-';
    return {
      id: fact.id,
      label: fact.label,
      localValue,
      authoritativeValue,
      changed: localValue !== authoritativeValue,
    };
  });
}

export function shortConflictFingerprint(fingerprint: string): string {
  const normalized = fingerprint.trim();
  if (!normalized) return '-';
  return normalized.length > 18
    ? `${normalized.slice(0, 11)}...${normalized.slice(-6)}`
    : normalized;
}
