// Webhooks de saída para o bot (Spec 35) — CRUD + ativar/desativar via
// /api/v1/portal/webhooks.
// Spec 73: feedback de sucesso/erro em TODAS as ações (create + toggle) e
// Spinner no carregamento (paridade com o console).
// Spec 119: colunas status/falhas/lastError + drawer "Ver payload" (sample do
// contrato sem PII) + aviso de segurança (HMAC/HTTPS/desativação após 5 falhas).

import { useState, type FormEvent } from 'react';
import { Card, ErrorBanner, Field, Spinner, SuccessBanner, useAsync } from '../components/ui';
import { api } from '../apiClient';

interface Webhook {
  id: string;
  url: string;
  hasSecret: boolean;
  active: boolean;
  events: string[];
  // Spec 119 (aditivo): status ACTIVE/INACTIVE + última falha de entrega.
  status?: string;
  attempts?: number;
  lastError?: string | null;
}

interface WebhookSample {
  type: string;
  payload: Record<string, unknown>;
}

const EVENT_OPTIONS = ['SALE_PAID', 'SALE_REJECTED', 'SALE_REFUNDED', 'WITHDRAWAL_COMPLETED', 'INVOICE_GENERATED'];

/** Avio de segurança (Spec 119 D7.2) — texto informativo fixo, sem ação. */
function SecurityNotice() {
  return (
    <div className="alert alert-info" role="status">
      <strong>Segurança:</strong> autenticação por assinatura <code>HMAC-SHA256</code> + HTTPS
      obrigatório. Desativação automática após 5 falhas de entrega.
    </div>
  );
}

export default function WebhooksPage() {
  const { data, error, loading, reload } = useAsync<Webhook[]>(() => api.get('/api/v1/portal/webhooks'), []);
  const [url, setUrl] = useState('');
  const [events, setEvents] = useState<string[]>(['SALE_PAID']);
  const [formError, setFormError] = useState<unknown>(null);
  const [actionError, setActionError] = useState<unknown>(null);
  const [feedback, setFeedback] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // Spec 119: drawer do payload (webhook + tipo selecionado + JSON carregado).
  const [sampleHook, setSampleHook] = useState<Webhook | null>(null);
  const [sampleType, setSampleType] = useState('SALE_PAID');
  const [sample, setSample] = useState<WebhookSample | null>(null);
  const [sampleLoading, setSampleLoading] = useState(false);
  const [sampleError, setSampleError] = useState<unknown>(null);
  const [copied, setCopied] = useState(false);

  const toggleEvent = (ev: string) => {
    setEvents((prev) => (prev.includes(ev) ? prev.filter((e) => e !== ev) : [...prev, ev]));
  };

  const handleCreate = async (e: FormEvent) => {
    e.preventDefault();
    setFormError(null);
    setSubmitting(true);
    try {
      await api.post('/api/v1/portal/webhooks', { url, events });
      setUrl('');
      setFeedback('Webhook cadastrado.');
      reload();
    } catch (err) {
      setFormError(err);
    } finally {
      setSubmitting(false);
    }
  };

  const toggleActive = async (hook: Webhook) => {
    const action = hook.active ? 'deactivate' : 'activate';
    setActionError(null);
    setFeedback(null);
    try {
      await api.post(`/api/v1/portal/webhooks/${hook.id}/${action}`);
      setFeedback(hook.active ? 'Webhook desativado.' : 'Webhook ativado.');
      reload();
    } catch (err) {
      setActionError(err);
    }
  };

  // Spec 119 D7.2.1: abre o drawer e carrega o sample do tipo selecionado
  // (mock do contrato — sem PII).
  const openSample = async (hook: Webhook) => {
    setSampleHook(hook);
    setSampleError(null);
    const firstType = hook.events?.[0] || 'SALE_PAID';
    setSampleType(firstType);
    await loadSample(hook, firstType);
  };

  const loadSample = async (hook: Webhook, type: string) => {
    setSampleLoading(true);
    setSampleError(null);
    try {
      const data = await api.get<WebhookSample>(`/api/v1/portal/webhooks/${hook.id}/sample?type=${encodeURIComponent(type)}`);
      setSample(data);
    } catch (err) {
      setSampleError(err);
      setSample(null);
    } finally {
      setSampleLoading(false);
    }
  };

  const copyJson = async () => {
    if (!sample) return;
    try {
      await navigator.clipboard.writeText(JSON.stringify(sample.payload, null, 2));
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      /* clipboard indisponível — ignora */
    }
  };

  return (
    <div className="page">
      <h1>Webhooks</h1>
      <SuccessBanner message={feedback} />
      <ErrorBanner error={actionError} />
      <div className="grid-2">
        <Card title="Novo webhook">
          <form className="form" onSubmit={handleCreate}>
            <ErrorBanner error={formError} />
            <Field
              label="URL de destino"
              type="url"
              required
              maxLength={500}
              value={url}
              onChange={(e) => setUrl(e.target.value)}
            />
            <div className="field">
              <span className="field-label">Eventos</span>
              <div className="checkbox-row">
                {EVENT_OPTIONS.map((ev) => (
                  <label key={ev} className="checkbox-label">
                    <input type="checkbox" checked={events.includes(ev)} onChange={() => toggleEvent(ev)} />
                    {ev}
                  </label>
                ))}
              </div>
            </div>
            <button type="submit" className="btn btn-primary" disabled={submitting || events.length === 0}>
              {submitting ? 'Salvando…' : 'Cadastrar'}
            </button>
          </form>
        </Card>

        <Card title="Webhooks cadastrados">
          {loading ? (
            <Spinner />
          ) : error ? (
            <ErrorBanner error={error} />
          ) : (
            <>
              <SecurityNotice />
              <ul className="plain-list">
                {(data || []).map((hook) => {
                  const failed = (hook.attempts ?? 0) >= 5;
                  return (
                    <li key={hook.id} className="dest-item">
                      <div>
                        <div>
                          <strong>{hook.url}</strong>{' '}
                          <span className={failed && !hook.active ? 'tag tag-danger' : 'tag'}>
                            {hook.active ? 'ATIVO' : 'INATIVO'}
                          </span>
                          {/* Spec 119: contador de falhas (dados reais da Spec 35). */}
                          {(hook.attempts ?? 0) > 0 && (
                            <span className="tag tag-warning" title={hook.lastError || undefined}>
                              {hook.attempts} falha{(hook.attempts ?? 0) === 1 ? '' : 's'}
                            </span>
                          )}
                        </div>
                        <div className="muted">
                          {(hook.events || []).join(', ')} · {hook.hasSecret ? 'assinatura configurada' : 'sem assinatura'}
                          {hook.lastError && <div className="muted" title={hook.lastError}>último erro: {hook.lastError}</div>}
                        </div>
                      </div>
                      <div className="dest-actions">
                        <button type="button" className="btn btn-small" onClick={() => void openSample(hook)}>
                          Ver payload
                        </button>
                        <button type="button" className="btn btn-small" onClick={() => void toggleActive(hook)}>
                          {hook.active ? 'Desativar' : 'Ativar'}
                        </button>
                      </div>
                    </li>
                  );
                })}
                {(data || []).length === 0 && <li className="muted">Nenhum webhook cadastrado.</li>}
              </ul>
            </>
          )}
        </Card>
      </div>

      {/* Spec 119 D7.2.1: drawer do payload (mock do contrato, sem PII). */}
      {sampleHook && (
        <div className="modal-backdrop">
          <div className="modal modal-wide" role="dialog" aria-modal="true" aria-label="Payload do evento">
            <div className="crm-chat-header">
              <div>
                <h2 style={{ margin: 0, fontSize: 18 }}>Payload do evento</h2>
                <div className="crm-chat-sub">
                  Exemplo do contrato (Spec 35) — sem dados reais, valores mascarados.
                </div>
              </div>
              <div className="crm-chat-actions">
                <button
                  type="button"
                  className="btn btn-small btn-ghost"
                  onClick={() => {
                    void loadSample(sampleHook, sampleType);
                  }}
                >
                  Atualizar
                </button>
                <button type="button" className="btn btn-small" onClick={() => void copyJson()} disabled={!sample}>
                  {copied ? 'Copiado!' : 'Copiar JSON'}
                </button>
                <button type="button" className="btn btn-small btn-ghost" onClick={() => setSampleHook(null)}>
                  Fechar
                </button>
              </div>
            </div>

            <div className="field" style={{ marginTop: 12 }}>
              <span className="field-label">Evento</span>
              <select
                className="input"
                value={sampleType}
                onChange={(e) => {
                  const type = e.target.value;
                  setSampleType(type);
                  void loadSample(sampleHook, type);
                }}
                aria-label="Tipo de evento"
              >
                {(sampleHook.events?.length ? sampleHook.events : EVENT_OPTIONS).map((ev) => (
                  <option key={ev} value={ev}>{ev}</option>
                ))}
              </select>
            </div>

            {sampleLoading ? (
              <Spinner />
            ) : sampleError ? (
              <div className="empty-state">
                <p>Não foi possível carregar o exemplo.</p>
                <button type="button" className="btn btn-small" onClick={() => void loadSample(sampleHook, sampleType)}>
                  Tentar novamente
                </button>
              </div>
            ) : sample ? (
              <pre className="payload-pre">{JSON.stringify(sample.payload, null, 2)}</pre>
            ) : null}

            <div className="alert alert-info" role="status" style={{ marginTop: 12 }}>
              Este exemplo segue o contrato da Spec 35 (outbox). Campos sensíveis
              (buyerIdHash, payload original) nunca são exibidos em claro.
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
