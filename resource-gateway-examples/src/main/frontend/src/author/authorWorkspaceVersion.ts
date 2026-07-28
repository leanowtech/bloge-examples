export type AuthorWorkspaceVersion = 'v1' | 'v2';

export const AUTHOR_WORKSPACE_VERSION_QUERY = 'authorWorkspace';

/**
 * Resolves the authoring shell version from an explicit URL query.
 *
 * The default remains v1 until the v2 vertical slice passes its browser gates. Keeping the
 * decision outside GraphDraft guarantees that UI rollout and rollback never mutate business data.
 */
export function resolveAuthorWorkspaceVersion(search: string): AuthorWorkspaceVersion {
  const requested = new URLSearchParams(search).get(AUTHOR_WORKSPACE_VERSION_QUERY)?.trim();
  return requested === 'v2' ? 'v2' : 'v1';
}
