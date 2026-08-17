import {
  parseCapabilityStudioDemoPack,
  type CapabilityStudioModel,
} from './domain';

export type CapabilityStudioFetcher = (
  input: RequestInfo | URL,
  init?: RequestInit,
) => Promise<Response>;

export async function fetchCapabilityStudioDemoPack(
  fetcher: CapabilityStudioFetcher = fetch,
): Promise<CapabilityStudioModel> {
  let response: Response;
  try {
    response = await fetcher('/api/capability-studio/demo-pack', {
      headers: { Accept: 'application/json' },
    });
  } catch (error) {
    throw new Error(error instanceof Error ? error.message : 'Network request failed.');
  }
  if (!response.ok) {
    throw new Error(`Capability Studio demo pack request failed with HTTP ${response.status}.`);
  }
  let payload: unknown;
  try {
    payload = await response.json();
  } catch (error) {
    throw new Error(error instanceof Error ? error.message : 'The response was not valid JSON.');
  }
  return parseCapabilityStudioDemoPack(payload);
}

