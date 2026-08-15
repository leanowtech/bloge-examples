import { afterEach, describe, expect, it, vi } from 'vitest';

import { canonicalJson, sha256Fingerprint, sha256FingerprintSync } from './fingerprint';

describe('sha256Fingerprint', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('preserves the exact SHA-256 result when Web Crypto is unavailable', async () => {
    const value = { z: 2, a: { message: 'customer service', count: 3 } };
    const expected = await sha256Fingerprint(value);

    vi.stubGlobal('crypto', {});

    expect(await sha256Fingerprint(value)).toBe(expected);
  });

  it.each([
    [null, 'sha256:74234e98afe7498fb5daf1f36ac2d78acc339464f950703b8c019892f982b90b'],
    ['', 'sha256:12ae32cb1ec02d01eda3581b127c1fee3b0dc53572ed6baf239721a03d82e126'],
    ['客户业务正确性', 'sha256:7b566fd90190b62e6ed995ad45a4832e071849ba5c4d86d45d8621690c376676'],
  ])('matches a fixed canonical SHA-256 vector without Web Crypto for %j', async (value, expected) => {
    vi.stubGlobal('crypto', {});

    expect(await sha256Fingerprint(value)).toBe(expected);
  });

  it('matches Web Crypto for a payload spanning multiple SHA-256 blocks', async () => {
    const value = {
      payload: 'fixture-boundary-'.repeat(80),
      nested: { outcome: 'APPROVED', score: 720 },
    };
    const expected = await sha256Fingerprint(value);

    vi.stubGlobal('crypto', {});

    expect(await sha256Fingerprint(value)).toBe(expected);
  });

  it('keeps the synchronous recovery coordinate identical to Web Crypto', async () => {
    const value = {
      graph: 'customer-refund',
      nodes: Array.from({ length: 64 }, (_, index) => ({ id: `node-${index}`, enabled: true })),
    };

    expect(sha256FingerprintSync(value)).toBe(await sha256Fingerprint(value));
  });

  it('keeps canonical object ordering identical across native and fallback implementations', async () => {
    const left = { z: 3, nested: { b: 2, a: 1 }, a: ['x', 'y'] };
    const right = { a: ['x', 'y'], nested: { a: 1, b: 2 }, z: 3 };
    expect(canonicalJson(left)).toBe(canonicalJson(right));
    const expected = await sha256Fingerprint(left);

    vi.stubGlobal('crypto', {});

    expect(await sha256Fingerprint(right)).toBe(expected);
  });
});
