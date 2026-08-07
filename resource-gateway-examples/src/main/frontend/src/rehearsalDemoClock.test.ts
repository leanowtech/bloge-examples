import { describe, expect, it } from 'vitest';

import { formatRehearsalDemoTime } from './rehearsalDemoClock';

describe('rehearsal demo clock', () => {
  it('formats demo evidence relative to its batch anchor in both locales', () => {
    const anchor = '2026-07-27T08:00:00Z';
    expect(formatRehearsalDemoTime('2026-07-27T08:04:12Z', anchor, 'en')).toBe('in 4 minutes');
    expect(formatRehearsalDemoTime('2026-07-27T09:00:00Z', anchor, 'zh-CN')).toBe('1小时后');
  });

  it('keeps invalid protocol coordinates visible for diagnosis', () => {
    expect(formatRehearsalDemoTime('not-a-date', '2026-07-27T08:00:00Z', 'en'))
      .toBe('not-a-date');
  });
});
