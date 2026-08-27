import { useState, type FormEvent } from 'react';

import type { OperatorCatalogResponse } from '../types';
import { useI18n } from '../i18n/I18nProvider';
import {
  inferSchema,
  type ExternalApiFormModel,
  type ExternalApiParameter,
  type ExternalApiResponseProtocol,
  type JsonSchema,
} from './externalApiModel';
import {
  saveExternalApi,
  type ExternalApiRequester,
  type ExternalApiSaveResult,
} from './externalApiTransport';
import './externalApi.css';
import '../tool/toolAuthoring.css';

interface ExternalApiAuthoringProps {
  /** Injectable save seam for tests and host-specific transport adapters. */
  save?: (form: ExternalApiFormModel, request?: ExternalApiRequester) => Promise<ExternalApiSaveResult>;
  /** Receives the refreshed catalog after both persistence calls succeed. */
  onCatalogRefresh?: (catalog: OperatorCatalogResponse) => void;
  /** Adds the refreshed resource operator through the existing canvas insertion path. */
  onAddOperator?: (operatorRef: string) => void;
}

const EMPTY_SCHEMA = { type: 'object', additionalProperties: true };

/**
 * Inline external API authoring section for the spine-enabled Author palette.
 *
 * <p>The UI presents one external API object. Descriptor and visual-contract
 * persistence details stay behind the transport seam.</p>
 */
export default function ExternalApiAuthoring({
  save = saveExternalApi,
  onCatalogRefresh,
  onAddOperator,
}: ExternalApiAuthoringProps) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState<ExternalApiFormModel>(newForm());
  const [schemaText, setSchemaText] = useState(JSON.stringify(EMPTY_SCHEMA, null, 2));
  const [sampleText, setSampleText] = useState('');
  const [inferenceReady, setInferenceReady] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [savedResult, setSavedResult] = useState<ExternalApiSaveResult | null>(null);
  const [submittedSchema, setSubmittedSchema] = useState<JsonSchema | null>(null);

  const updateForm = (patch: Partial<ExternalApiFormModel>) => setForm((current) => ({ ...current, ...patch }));
  const updateParameter = (index: number, patch: Partial<ExternalApiParameter>) => {
    setForm((current) => ({
      ...current,
      params: current.params.map((parameter, candidate) => (
        candidate === index ? { ...parameter, ...patch } : parameter
      )),
    }));
  };
  const updateProtocol = (protocol: ExternalApiResponseProtocol) => updateForm({ responseProtocol: protocol });
  const parseSample = (): unknown => {
    try {
      return JSON.parse(sampleText);
    } catch {
      throw new Error(t('Sample response must be valid JSON.'));
    }
  };
  const inferOutputSchema = () => {
    try {
      const sample = parseSample();
      updateForm({ outputSchema: { source: 'inferred', sampleResponse: sample, schema: inferSchema(sample) } });
      setInferenceReady(true);
      setError('');
    } catch (cause) {
      setInferenceReady(false);
      setError(cause instanceof Error ? cause.message : t('Sample response must be valid JSON.'));
    }
  };
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!form.resourceId.trim() || !form.displayName.trim() || !form.urlTemplate.trim()) {
      setError(t('Resource ID, display name, and URL are required.'));
      return;
    }
    let outputSchema = form.outputSchema;
    if (outputSchema.source === 'manual') {
      try {
        const parsed = JSON.parse(schemaText) as unknown;
        if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error();
        outputSchema = { source: 'manual', schema: parsed as Record<string, unknown> };
      } catch {
        setError(t('Manual output schema must be a JSON object.'));
        return;
      }
    }
    const submitted = {
      ...form,
      resourceId: form.resourceId.trim(),
      displayName: form.displayName.trim(),
      urlTemplate: form.urlTemplate.trim(),
      payloadPath: form.payloadPath.trim(),
      params: form.params.filter((parameter) => parameter.name.trim() && parameter.from.trim()),
      outputSchema,
    } satisfies ExternalApiFormModel;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const result = await save(submitted);
      setSavedResult(result);
      setSubmittedSchema(outputSchema.schema);
      onCatalogRefresh?.(result.catalog);
      setNotice(t('External API saved.'));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t('External API save failed.'));
    } finally {
      setBusy(false);
    }
  };

  const selectedOperator = savedResult?.catalog.operators.find(
    (operator) => operator.operatorRef === `resource:${savedResult.descriptor.resourceId}`,
  );
  const outputIsOpaque = submittedSchema?.additionalProperties === true
    && (!submittedSchema.type
      || (submittedSchema.type === 'object' && !submittedSchema.properties));
  const bodyCodeProtocol = form.responseProtocol.kind === 'BodyCode' ? form.responseProtocol : null;

  return (
    <section className="external-api-authoring" data-testid="external-api-authoring">
      <button
        type="button"
        className="secondary compact external-api-add"
        data-testid="add-external-api"
        onClick={() => { setOpen(true); setError(''); setNotice(''); }}
      >
        {t('Add external API')}
      </button>
      {open && (
        <form className="external-api-form" data-testid="external-api-form" onSubmit={submit}>
          <div className="external-api-heading">
            <h3>{t('External API')}</h3>
            <button type="button" className="secondary compact" onClick={() => setOpen(false)}>
              {t('Close')}
            </button>
          </div>
          <section data-testid="external-api-request">
            <h4>{t('Request')}</h4>
            <div className="external-api-grid">
              <label><span>{t('Resource ID')}</span><input data-testid="external-api-resource-id" value={form.resourceId} onChange={(event) => updateForm({ resourceId: event.target.value })} /></label>
              <label><span>{t('Display name')}</span><input data-testid="external-api-display-name" value={form.displayName} onChange={(event) => updateForm({ displayName: event.target.value })} /></label>
              <label className="wide"><span>{t('URL template')}</span><input data-testid="external-api-url" value={form.urlTemplate} onChange={(event) => updateForm({ urlTemplate: event.target.value })} /></label>
              <label><span>{t('HTTP method')}</span><select data-testid="external-api-method" value={form.method} onChange={(event) => updateForm({ method: event.target.value as ExternalApiFormModel['method'] })}>{['GET', 'POST', 'PUT', 'DELETE'].map((method) => <option key={method}>{method}</option>)}</select></label>
            </div>
            <div className="external-api-params">
              <strong>{t('Request parameters')}</strong>
              {form.params.map((parameter, index) => (
                <div className="external-api-param" key={index} data-testid={`external-api-param-${index}`}>
                  <input aria-label={t('Parameter name')} value={parameter.name} onChange={(event) => updateParameter(index, { name: event.target.value })} />
                  <select aria-label={t('Parameter location')} data-testid={`external-api-param-location-${index}`} value={parameter.in} onChange={(event) => updateParameter(index, { in: event.target.value as ExternalApiParameter['in'] })}>
                    <option value="path">path</option><option value="query">query</option><option value="header">header</option>
                  </select>
                  <input aria-label={t('Expression')} placeholder="ctx.params.value" value={parameter.from} onChange={(event) => updateParameter(index, { from: event.target.value })} />
                  {form.params.length > 1 && <button type="button" className="secondary compact" onClick={() => setForm((current) => ({ ...current, params: current.params.filter((_, candidate) => candidate !== index) }))}>{t('Remove')}</button>}
                </div>
              ))}
              <button type="button" className="secondary compact" data-testid="external-api-add-param" onClick={() => setForm((current) => ({ ...current, params: [...current.params, { name: '', in: 'query', from: '' }] }))}>{t('Add parameter')}</button>
            </div>
          </section>
          <section data-testid="external-api-response">
            <h4>{t('Response')}</h4>
            <label><span>{t('Response protocol')}</span><select data-testid="external-api-protocol" value={form.responseProtocol.kind} onChange={(event) => updateProtocol(protocolFor(event.target.value))}>
              <option>HttpStatus</option><option>StatusCodes</option><option>BodyFlag</option><option>BodyCode</option>
            </select></label>
            {form.responseProtocol.kind === 'StatusCodes' && <label><span>{t('Success HTTP codes')}</span><input data-testid="external-api-protocol-status-codes" value={form.responseProtocol.success.join(',')} onChange={(event) => updateProtocol({ kind: 'StatusCodes', success: event.target.value.split(',').map((value) => Number(value.trim())).filter(Number.isInteger) })} /></label>}
            {form.responseProtocol.kind === 'BodyFlag' && <label data-testid="external-api-protocol-body-flag"><span>{t('Body flag path')}</span><input value={form.responseProtocol.flagField} onChange={(event) => updateProtocol({ kind: 'BodyFlag', flagField: event.target.value })} /></label>}
            {bodyCodeProtocol && <div data-testid="external-api-protocol-body-code"><label><span>{t('Body code path')}</span><input value={bodyCodeProtocol.codeField} onChange={(event) => updateProtocol({ kind: 'BodyCode', codeField: event.target.value, successCodes: bodyCodeProtocol.successCodes, messageField: bodyCodeProtocol.messageField })} /></label><label><span>{t('Success body values')}</span><input value={bodyCodeProtocol.successCodes.join(',')} onChange={(event) => updateProtocol({ kind: 'BodyCode', codeField: bodyCodeProtocol.codeField, successCodes: event.target.value.split(',').map((value) => value.trim()).filter(Boolean), messageField: bodyCodeProtocol.messageField })} /></label><label><span>{t('Message path')}</span><input value={bodyCodeProtocol.messageField ?? ''} onChange={(event) => updateProtocol({ kind: 'BodyCode', codeField: bodyCodeProtocol.codeField, successCodes: bodyCodeProtocol.successCodes, messageField: event.target.value })} /></label></div>}
            <label><span>{t('Payload path')}</span><input data-testid="external-api-payload-path" value={form.payloadPath} onChange={(event) => updateForm({ payloadPath: event.target.value })} /></label>
          </section>
          <section data-testid="external-api-output-schema">
            <h4>{t('Output schema')}</h4>
            <label><span>{t('Schema mode')}</span><select data-testid="external-api-schema-mode" value={form.outputSchema.source} onChange={(event) => { const source = event.target.value as 'manual' | 'inferred'; updateForm({ outputSchema: source === 'manual' ? { source, schema: EMPTY_SCHEMA } : { source, sampleResponse: null, schema: EMPTY_SCHEMA } }); setInferenceReady(false); }}>{<><option value="manual">{t('Manual')}</option><option value="inferred">{t('Inferred')}</option></>}</select></label>
            {form.outputSchema.source === 'manual' ? <label><span>{t('Manual JSON Schema')}</span><textarea data-testid="external-api-manual-schema" value={schemaText} onChange={(event) => setSchemaText(event.target.value)} /></label> : <><label><span>{t('Sample response JSON')}</span><textarea data-testid="external-api-inferred-sample" value={sampleText} onChange={(event) => { setSampleText(event.target.value); setInferenceReady(false); }} /></label><button type="button" className="secondary compact" data-testid="external-api-infer" onClick={inferOutputSchema}>{t('Infer schema')}</button>{inferenceReady && <span data-testid="external-api-inference-ready">{t('Inference ready')}</span>}</>}
          </section>
          {error && <p className="external-api-message error" role="alert" data-testid="external-api-error">{error}</p>}
          {notice && <p className="external-api-message success" role="status" data-testid="external-api-notice">{notice}</p>}
          <button type="submit" className="primary compact" data-testid="external-api-save" disabled={busy}>{busy ? t('Saving') : t('Save external API')}</button>
        </form>
      )}
      {savedResult && (
        <article className="external-api-card" data-testid="external-api-card">
          <strong>{savedResult.descriptor.resourceId}</strong>
          <code>resource:{savedResult.descriptor.resourceId}</code>
          {outputIsOpaque && <p data-testid="external-api-opaque-warning">{t('This external API has an opaque output schema; typed composition is unavailable.')}</p>}
          {selectedOperator && onAddOperator && <button type="button" className="secondary compact" onClick={() => onAddOperator(selectedOperator.operatorRef)}>{t('Add to canvas')}</button>}
        </article>
      )}
    </section>
  );
}

function newForm(): ExternalApiFormModel {
  return {
    resourceId: '', displayName: '', urlTemplate: '', method: 'GET',
    params: [{ name: '', in: 'path', from: '' }],
    responseProtocol: { kind: 'HttpStatus' }, payloadPath: '',
    outputSchema: { source: 'manual', schema: EMPTY_SCHEMA },
  };
}

function protocolFor(kind: string): ExternalApiResponseProtocol {
  if (kind === 'StatusCodes') return { kind: 'StatusCodes', success: [200] };
  if (kind === 'BodyFlag') return { kind: 'BodyFlag', flagField: 'ok' };
  if (kind === 'BodyCode') return { kind: 'BodyCode', codeField: 'code', successCodes: [0], messageField: 'message' };
  return { kind: 'HttpStatus' };
}
