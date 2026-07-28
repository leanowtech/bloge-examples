export type AuthorWorkspaceVersion = 'v1' | 'v2';

export const AUTHOR_WORKSPACE_VERSION_QUERY = 'authorWorkspace';
export const LEGACY_AUTHOR_WORKSPACE_QUERY_VALUE = 'legacy';

/**
 * Resolves the authoring shell version from an explicit URL query.
 *
 * Author Workspace v2 is the product default. An explicit legacy coordinate keeps rollback
 * independent from GraphDraft so changing the UI projection never mutates business data.
 * The former "v1" value remains readable for existing bookmarks.
 */
export function resolveAuthorWorkspaceVersion(search: string): AuthorWorkspaceVersion {
  const params = new URLSearchParams(search);
  if (!params.has(AUTHOR_WORKSPACE_VERSION_QUERY)) {
    return 'v2';
  }
  const requested = params.get(AUTHOR_WORKSPACE_VERSION_QUERY)?.trim();
  return requested === 'v2' ? 'v2' : 'v1';
}

/**
 * Builds an Author entry URL while retaining draft, node, run, and host integration coordinates.
 *
 * The canonical v2 URL omits the rollout parameter. Legacy uses a named value rather than making
 * users remember the implementation-era "v1" label.
 */
export function authorWorkspaceEntryHref(
  search: string,
  version: AuthorWorkspaceVersion,
): string {
  const params = new URLSearchParams(search);
  if (version === 'v2') {
    params.delete(AUTHOR_WORKSPACE_VERSION_QUERY);
  } else {
    params.set(AUTHOR_WORKSPACE_VERSION_QUERY, LEGACY_AUTHOR_WORKSPACE_QUERY_VALUE);
  }
  const query = params.toString();
  return `/author/${query ? `?${query}` : ''}`;
}
