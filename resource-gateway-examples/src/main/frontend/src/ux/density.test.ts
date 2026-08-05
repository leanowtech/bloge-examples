import { describe, expect, it, vi } from 'vitest';

import {
  DEFAULT_UI_DENSITY,
  normalizeUiDensity,
  readStoredUiDensity,
  UI_DENSITY_STORAGE_KEY,
  writeStoredUiDensity,
} from './density';

describe('UI density persistence', () => {
  it('accepts only supported density values', () => {
    expect(normalizeUiDensity('comfortable')).toBe('comfortable');
    expect(normalizeUiDensity('compact')).toBe('compact');
    expect(normalizeUiDensity('dense')).toBeNull();
    expect(normalizeUiDensity(null)).toBeNull();
  });

  it('defaults to comfortable when storage is empty or invalid', () => {
    vi.stubGlobal('window', { localStorage: { getItem: () => null } });
    expect(readStoredUiDensity()).toBe(DEFAULT_UI_DENSITY);

    vi.stubGlobal('window', { localStorage: { getItem: () => 'unknown' } });
    expect(readStoredUiDensity()).toBe(DEFAULT_UI_DENSITY);
    vi.unstubAllGlobals();
  });

  it('persists a selected density under a stable key', () => {
    const setItem = vi.fn();
    vi.stubGlobal('window', { localStorage: { setItem } });
    writeStoredUiDensity('compact');
    expect(setItem).toHaveBeenCalledWith(UI_DENSITY_STORAGE_KEY, 'compact');
    vi.unstubAllGlobals();
  });

  it('fails open when browser storage is unavailable', () => {
    vi.stubGlobal('window', {
      localStorage: {
        getItem: () => { throw new Error('blocked'); },
        setItem: () => { throw new Error('blocked'); },
      },
    });
    expect(readStoredUiDensity()).toBe(DEFAULT_UI_DENSITY);
    expect(() => writeStoredUiDensity('compact')).not.toThrow();
    vi.unstubAllGlobals();
  });
});
