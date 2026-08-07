import type { MessageId } from './messageCatalog';

export type DynamicProductTextTranslator = (
  source: string,
  values?: Record<string, string | number>,
) => string;

export type CatalogMessageTranslator = (
  messageId: MessageId,
  values?: Record<string, string | number>,
) => string;

export function localizeRehearsalText(
  d: DynamicProductTextTranslator,
  m: CatalogMessageTranslator,
  source: string,
): string {
  const blockerSummary = source.match(/^(\d+) failed and (\d+) indeterminate blocker assertions$/);
  if (blockerSummary) {
    return m('rehearsal.generated.blockerSummary', {
      failed: blockerSummary[1],
      indeterminate: blockerSummary[2],
    });
  }
  const warningSummary = source.match(/^(\d+) failed and (\d+) indeterminate warnings$/);
  if (warningSummary) {
    return m('rehearsal.generated.warningSummary', {
      failed: warningSummary[1],
      indeterminate: warningSummary[2],
    });
  }
  const mutableProjection = source.match(/^Mutable ([a-z]+) projection$/);
  if (mutableProjection) {
    const wireStatus = mutableProjection[1].toUpperCase();
    const statusLabel = d(wireStatus);
    return m('rehearsal.generated.mutableProjection', {
      status: statusLabel === wireStatus ? mutableProjection[1] : statusLabel,
    });
  }
  const rehearsalOwner = source.match(/^(.+) rehearsal owner$/);
  if (rehearsalOwner) {
    return m('rehearsal.generated.rehearsalOwner', { project: rehearsalOwner[1] });
  }
  const projectOwner = source.match(/^(.+) owner$/);
  if (projectOwner) {
    return m('rehearsal.generated.projectOwner', { project: projectOwner[1] });
  }
  const missingAuthorSource = source.match(/^This plan does not advertise an Author source\. Contact (.+)\.$/);
  if (missingAuthorSource) {
    return m('rehearsal.generated.missingAuthorSource', {
      owner: localizeRehearsalText(d, m, missingAuthorSource[1]),
    });
  }
  return d(source);
}
