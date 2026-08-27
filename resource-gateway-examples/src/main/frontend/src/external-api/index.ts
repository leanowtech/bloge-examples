export {
  externalApiFormToDescriptor,
  inferSchema,
  MAX_DEPTH,
  MAX_NODES,
  toDesignContract,
} from './externalApiModel';
export { saveExternalApi } from './externalApiTransport';
export type {
  ExternalApiFormModel,
  ExternalApiOutputSchema,
  ExternalApiParameter,
  ExternalApiResponseProtocol,
  JsonSchema,
  ResourceDescriptorPayload,
  ResourceDesignContractPayload,
  SchemaEnvelopePayload,
} from './externalApiModel';
export type { ExternalApiRequester, ExternalApiSaveResult } from './externalApiTransport';
