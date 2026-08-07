export function formatRehearsalDemoTime(
  value: string,
  anchor: string,
  locale: string,
): string {
  const timestamp = new Date(value).getTime();
  const anchorTimestamp = new Date(anchor).getTime();
  if (!Number.isFinite(timestamp) || !Number.isFinite(anchorTimestamp)) return value;

  const seconds = Math.round((timestamp - anchorTimestamp) / 1000);
  const absoluteSeconds = Math.abs(seconds);
  const [amount, unit] = absoluteSeconds < 60
    ? [seconds, 'second'] as const
    : absoluteSeconds < 3_600
      ? [Math.round(seconds / 60), 'minute'] as const
      : absoluteSeconds < 86_400
        ? [Math.round(seconds / 3_600), 'hour'] as const
        : [Math.round(seconds / 86_400), 'day'] as const;
  return new Intl.RelativeTimeFormat(locale, { numeric: 'auto' }).format(amount, unit);
}
