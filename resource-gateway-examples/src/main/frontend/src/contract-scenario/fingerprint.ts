/** Produces canonical JSON with recursively sorted object keys. */
export function canonicalJson(value: unknown): string {
  return canonicalValue(value, new Set<object>());
}

/** Computes the exact browser-side SHA-256 coordinate used by Contract and Scenario drafts. */
export async function sha256Fingerprint(value: unknown): Promise<string> {
  const bytes = new TextEncoder().encode(canonicalJson(value));
  const digest = await globalThis.crypto.subtle.digest('SHA-256', bytes);
  const hex = Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('');
  return `sha256:${hex}`;
}

function canonicalValue(value: unknown, ancestors: Set<object>): string {
  if (value === null) {
    return 'null';
  }
  if (typeof value === 'string' || typeof value === 'boolean') {
    return JSON.stringify(value);
  }
  if (typeof value === 'number') {
    return Number.isFinite(value) ? JSON.stringify(value) : 'null';
  }
  if (Array.isArray(value)) {
    rejectCycle(value, ancestors);
    const nextAncestors = new Set(ancestors).add(value);
    return `[${value.map((entry) => canonicalValue(entry, nextAncestors)).join(',')}]`;
  }
  if (typeof value === 'object') {
    rejectCycle(value, ancestors);
    const nextAncestors = new Set(ancestors).add(value);
    const record = value as Record<string, unknown>;
    const entries = Object.keys(record)
      .filter((key) => record[key] !== undefined)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonicalValue(record[key], nextAncestors)}`);
    return `{${entries.join(',')}}`;
  }
  return 'null';
}

function rejectCycle(value: object, ancestors: Set<object>): void {
  if (ancestors.has(value)) {
    throw new TypeError('Cannot fingerprint a cyclic JSON value.');
  }
}
