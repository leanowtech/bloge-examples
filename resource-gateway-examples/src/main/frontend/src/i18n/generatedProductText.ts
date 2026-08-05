export type ProductTextTranslator = (
  source: string,
  values?: Record<string, string | number>,
) => string;

export function localizeRehearsalText(t: ProductTextTranslator, source: string): string {
  const blockerSummary = source.match(/^(\d+) failed and (\d+) indeterminate blocker assertions$/);
  if (blockerSummary) {
    return t('{failed} failed and {indeterminate} indeterminate blocker assertions', {
      failed: blockerSummary[1],
      indeterminate: blockerSummary[2],
    });
  }
  const warningSummary = source.match(/^(\d+) failed and (\d+) indeterminate warnings$/);
  if (warningSummary) {
    return t('{failed} failed and {indeterminate} indeterminate warnings', {
      failed: warningSummary[1],
      indeterminate: warningSummary[2],
    });
  }
  const mutableProjection = source.match(/^Mutable ([a-z]+) projection$/);
  if (mutableProjection) {
    const wireStatus = mutableProjection[1].toUpperCase();
    const statusLabel = t(wireStatus);
    return t('Mutable {status} projection', {
      status: statusLabel === wireStatus ? mutableProjection[1] : statusLabel,
    });
  }
  const rehearsalOwner = source.match(/^(.+) rehearsal owner$/);
  if (rehearsalOwner) {
    return t('{project} rehearsal owner', { project: rehearsalOwner[1] });
  }
  const projectOwner = source.match(/^(.+) owner$/);
  if (projectOwner) {
    return t('{project} owner', { project: projectOwner[1] });
  }
  const missingAuthorSource = source.match(/^This plan does not advertise an Author source\. Contact (.+)\.$/);
  if (missingAuthorSource) {
    return t('This plan does not advertise an Author source. Contact {owner}.', {
      owner: localizeRehearsalText(t, missingAuthorSource[1]),
    });
  }
  return t(source);
}
