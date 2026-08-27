import type { OperatorCatalogResponse } from '../types';
import {
  externalApiFormToDescriptor,
  toDesignContract,
  type ExternalApiFormModel,
  type ResourceDescriptorPayload,
  type ResourceDesignContractPayload,
} from './externalApiModel';

/** Injectable same-origin request function used by the external API authoring flow. */
export type ExternalApiRequester = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

/** Result of the complete external API save-and-refresh orchestration. */
export interface ExternalApiSaveResult {
  descriptor: ResourceDescriptorPayload;
  contract: ResourceDesignContractPayload;
  catalog: OperatorCatalogResponse;
}

/**
 * Persist one external API in the only valid order, then refresh the operator catalog.
 *
 * <p>The descriptor and visual contract are intentionally separate wire calls but
 * remain one user operation. A failed call throws a safe, step-specific message;
 * response bodies are never copied into an error, preventing API payloads or
 * credentials from appearing in the authoring surface.</p>
 *
 * @param form pure external API form state
 * @param request injectable same-origin request function
 * @returns both persisted projections and the refreshed catalog
 * @throws Error when either PUT or the final catalog GET fails
 */
export async function saveExternalApi(
  form: ExternalApiFormModel,
  request: ExternalApiRequester = (input, init) => fetch(input, init),
): Promise<ExternalApiSaveResult> {
  const descriptor = externalApiFormToDescriptor(form);
  const contract = toDesignContract(form);
  const resourcePath = encodeURIComponent(form.resourceId);

  await requireOk(
    request(`/admin/resources/${resourcePath}`, jsonPut(descriptor)),
    'descriptor',
  );
  await requireOk(
    request(`/admin/resource-design-contracts/${resourcePath}`, jsonPut(contract)),
    'contract',
  );
  const catalogResponse = await request('/api/visual/operators');
  if (!catalogResponse.ok) {
    throw new Error(`External API catalog refresh failed (${catalogResponse.status}).`);
  }
  let catalog: unknown;
  try {
    catalog = await catalogResponse.json();
  } catch {
    throw new Error('External API catalog refresh failed (invalid response).');
  }
  if (Array.isArray(catalog)) {
    return { descriptor, contract, catalog: { operators: catalog as OperatorCatalogResponse['operators'] } };
  }
  if (!catalog || typeof catalog !== 'object' || !Array.isArray((catalog as OperatorCatalogResponse).operators)) {
    throw new Error('External API catalog refresh failed (invalid response).');
  }
  return { descriptor, contract, catalog: catalog as OperatorCatalogResponse };
}

function jsonPut(body: unknown): RequestInit {
  return {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  };
}

async function requireOk(responsePromise: Promise<Response>, step: 'descriptor' | 'contract'): Promise<void> {
  let response: Response;
  try {
    response = await responsePromise;
  } catch {
    throw new Error(`External API ${step} save failed (network).`);
  }
  if (!response.ok) {
    throw new Error(`External API ${step} save failed (${response.status}).`);
  }
}
