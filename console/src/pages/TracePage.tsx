// Trace de transação (Spec 127 — fases 3–4 da Spec 125): colar txid /
// idempotencyKey / externalReference → jornada completa (Bot → Gateway → PSP →
// Ledger → Webhook). Reusa GET /api/v1/master/trace/transactions/{ref} — a UI
// é uma timeline vertical com stage colorido; o traceId (fio condutor dos logs)
// fica no cabeçalho com botão de copiar.

import { useState, type FormEvent, type ReactNode } from 'react';
import { api, ApiError } from '../apiClient';
import { Card, ErrorBanner, Spinner, copyToClipboard, formatDate, formatMoney, useAsync } from '../components/ui';
import type { TransactionTraceResponse } from '../types';

const STAGE_LABELS: Record<string, string> = {
  BOT: 'Bot',
  GATEWAY: 'Gateway',
  PSP: 'PSP',
  LEDGER: 'Ledger',
  WEBHOOK: 'Webhook',
};

function stageClass(stage: string): string {
  return `trace-stage-badge trace-stage-${stage.toLowerCase()}`;
}

function renderDetailValue(value: unknown): ReactNode {
  if (value === null || value === undefined) return '—';
  if (typeof value === 'object') {
    return <pre className="trace-json">{JSON.stringify(value, null, 2)}</pre>;
  }
  return String(value);
}

export default function TracePage() {
  const [ref, setRef] = useState('');
  const [searched, setSearched] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  const { data, error, loading } = useAsync<TransactionTraceResponse | null>(
    async () => {
      if (!searched) return null;
      return api.get<TransactionTraceResponse>(
        `/api/v1/master/trace/transactions/${encodeURIComponent(searched)}`,
      );
    },
    [searched],
  );

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    const value = ref.trim();
    if (!value) return;
    setSearched(value);
    setCopied(false);
  };

  const isNotFound = error instanceof ApiError && error.status === 404;

  const handleCopyTraceId = async () => {
    if (!data) return;
    const ok = await copyToClipboard(data.traceId);
    if (ok) {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    }
  };

  return (
    <div className="page">
      <h1>Trace de transação</h1>
      <Card>
        <form className="form" onSubmit={handleSubmit}>
          <label className="field">
            <span className="field-label">txid / idempotencyKey / externalReference</span>
            <input
              className="input"
              placeholder="cole o txid do PSP, a idempotencyKey do bot ou o UUID interno"
              value={ref}
              onChange={(e) => setRef(e.target.value)}
              required
            />
          </label>
          <div>
            <button type="submit" className="btn btn-primary" disabled={loading}>
              {loading ? 'Buscando…' : 'Buscar'}
            </button>
          </div>
        </form>
        {loading && <Spinner />}
        {!searched && !loading && (
          <p className="muted">
            Cole o txid do PSP, a idempotencyKey do bot ou o UUID interno da transação para ver a
            jornada completa.
          </p>
        )}
        {searched && isNotFound && (
          <div className="alert alert-warning" role="alert">
            Transação não encontrada para “{searched}”.
          </div>
        )}
        {searched && !isNotFound && <ErrorBanner error={error} />}
      </Card>

      {data && (
        <>
          <Card title="Cabeçalho">
            <ul className="plain-list">
              <li>
                <span>traceId</span>{' '}
                <strong>
                  {data.traceId}{' '}
                  <button type="button" className="btn btn-small" onClick={handleCopyTraceId}>
                    {copied ? 'Copiado!' : 'Copiar'}
                  </button>
                </strong>
              </li>
              <li>
                <span>transactionId</span> <strong>{data.transactionId}</strong>
              </li>
              <li>
                <span>Valor</span> <strong>{formatMoney(data.amount)}</strong>
              </li>
              <li>
                <span>Status</span> <strong>{data.status}</strong>
              </li>
              <li>
                <span>merchantId</span> <strong>{data.merchantId}</strong>
              </li>
              <li>
                <span>Adquirente</span> <strong>{data.provider || '—'}</strong>
              </li>
              <li>
                <span>Perfil (operação)</span> <strong>{data.profileId || '—'}</strong>
              </li>
              <li>
                <span>idempotencyKey</span> <strong>{data.idempotencyKey || '—'}</strong>
              </li>
              <li>
                <span>externalReference</span> <strong>{data.externalReference || '—'}</strong>
              </li>
              <li>
                <span>endToEndId</span> <strong>{data.endToEndId || '—'}</strong>
              </li>
              <li>
                <span>Criada em</span> <strong>{formatDate(data.createdAt)}</strong>
              </li>
              <li>
                <span>Paga em</span> <strong>{formatDate(data.paidAt)}</strong>
              </li>
              <li>
                <span>Bot</span>{' '}
                <strong>
                  {data.bot ? `${data.bot.botId} · ${data.bot.platformUserId}` : '—'}
                </strong>
              </li>
            </ul>
          </Card>

          <Card title="Jornada">
            {data.timeline.length === 0 && <p className="muted">Sem eventos na jornada.</p>}
            <ol className="trace-timeline">
              {data.timeline.map((item, idx) => (
                <li key={idx} className="trace-item">
                  <span className="trace-time">{formatDate(item.at)}</span>
                  <span className={stageClass(item.stage)}>
                    {STAGE_LABELS[item.stage] ?? item.stage}
                  </span>
                  <div className="trace-body">
                    <strong className="trace-event">{item.event}</strong>
                    {Object.keys(item.detail).length > 0 && (
                      <dl className="trace-detail">
                        {Object.entries(item.detail).map(([key, value]) => (
                          <div key={key} className="trace-detail-row">
                            <dt>{key}</dt>
                            <dd>{renderDetailValue(value)}</dd>
                          </div>
                        ))}
                      </dl>
                    )}
                  </div>
                </li>
              ))}
            </ol>
          </Card>
        </>
      )}
    </div>
  );
}
