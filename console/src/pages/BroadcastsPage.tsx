// Auditoria de Broadcasts no Master Console (Spec 89) — visão global
// cross-merchant (todos os lojistas) com inspeção de conteúdo + logs e o
// **force-cancel** de campanhas PENDING (ação de OPERATOR, Spec 69).
//   - GET /api/v1/master/broadcasts          → lista paginada (filtros)
//   - GET /api/v1/master/broadcasts/{id}     → detalhe (conteúdo + logs)
//   - POST /api/v1/master/broadcasts/{id}/force-cancel → PENDING → FAILED
//
// RBAC: leitura = OPERATOR (todo master ativo); force-cancel = OPERATOR.
// O botão "Cancelar Campanha" só aparece quando status = PENDING.

import { useState } from 'react';
import { api } from '../apiClient';
import { Card, ErrorBanner, Pagination, Spinner, formatDate, useAsync } from '../components/ui';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { useDialogFocus } from '../hooks/useDialogFocus';
import type { PageResponse } from '../types';

interface MasterBroadcastSummary {
  id: string;
  merchantId: string | null;
  merchantName: string | null;
  botId: string;
  botDisplayName: string | null;
  botPlatform: string | null;
  mode: string;
  status: string;
  targetChatId: string | null;
  scheduledAt: string | null;
  createdAt: string;
}

interface BroadcastLog {
  recipientId: string;
  status: string;
  errorMessage: string | null;
  sentAt: string | null;
}

interface MasterBroadcastDetail extends MasterBroadcastSummary {
  content: string;
  mediaUrl: string | null;
  mediaType: string | null;
  buttonsJson: string | null;
  errorMessage: string | null;
  successCount: number;
  blockedCount: number;
  failedCount: number;
  logs: BroadcastLog[];
}

const MODE_LABEL: Record<string, string> = {
  NOW: 'Agora',
  SCHEDULED: 'Agendado',
  REPOST: 'Repost',
  WELCOME: 'Boas-vindas',
};

const STATUS_CLASS: Record<string, string> = {
  PENDING: 'tag-warning',
  PROCESSING: 'tag-primary',
  COMPLETED: 'tag-success',
  FAILED: 'tag-danger',
};

const LOG_STATUS_CLASS: Record<string, string> = {
  SUCCESS: 'tag-success',
  BLOCKED: 'tag-warning',
  FAILED: 'tag-danger',
};

export default function BroadcastsPage() {
  const [merchant, setMerchant] = useState('');
  const [bot, setBot] = useState('');
  const [status, setStatus] = useState('');
  const [mode, setMode] = useState('');
  const [page, setPage] = useState(0);

  const params = new URLSearchParams({ page: String(page), size: '20' });
  if (merchant.trim()) params.set('merchantId', merchant.trim());
  if (bot.trim()) params.set('botId', bot.trim());
  if (status) params.set('status', status);
  if (mode) params.set('mode', mode);

  const { data, error, loading, reload } = useAsync<PageResponse<MasterBroadcastSummary>>(
    () => api.get<PageResponse<MasterBroadcastSummary>>(`/api/v1/master/broadcasts?${params.toString()}`),
    [params.toString()],
  );

  const [detail, setDetail] = useState<MasterBroadcastDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<unknown>(null);

  const [cancelTarget, setCancelTarget] = useState<MasterBroadcastDetail | null>(null);
  const [cancelBusy, setCancelBusy] = useState(false);
  const [cancelError, setCancelError] = useState<unknown>(null);
  const [feedback, setFeedback] = useState<string | null>(null);

  const openDetail = async (id: string) => {
    setDetailLoading(true);
    setDetailError(null);
    try {
      const d = await api.get<MasterBroadcastDetail>(`/api/v1/master/broadcasts/${id}`);
      setDetail(d);
    } catch (err) {
      setDetailError(err);
    } finally {
      setDetailLoading(false);
    }
  };

  const runForceCancel = async () => {
    if (!cancelTarget) return;
    setCancelBusy(true);
    setCancelError(null);
    try {
      const updated = await api.post<MasterBroadcastDetail>(
        `/api/v1/master/broadcasts/${cancelTarget.id}/force-cancel`,
      );
      setDetail(updated);
      setCancelTarget(null);
      setFeedback(`Campanha "${cancelTarget.botDisplayName || cancelTarget.id}" cancelada.`);
      reload();
    } catch (err) {
      setCancelError(err);
    } finally {
      setCancelBusy(false);
    }
  };

  return (
    <div className="page">
      <h1>Broadcasts</h1>

      <SuccessBanner message={feedback} />

      <Card>
        <div className="filter-row">
          <input
            className="input input-small"
            type="search"
            placeholder="Lojista (id)…"
            value={merchant}
            onChange={(e) => {
              setMerchant(e.target.value);
              setPage(0);
            }}
          />
          <input
            className="input input-small"
            type="search"
            placeholder="Bot (id)…"
            value={bot}
            onChange={(e) => {
              setBot(e.target.value);
              setPage(0);
            }}
          />
          <select
            className="input input-small"
            value={status}
            aria-label="Filtrar por status"
            onChange={(e) => {
              setStatus(e.target.value);
              setPage(0);
            }}
          >
            <option value="">Status (todos)</option>
            <option value="PENDING">PENDING</option>
            <option value="PROCESSING">PROCESSING</option>
            <option value="COMPLETED">COMPLETED</option>
            <option value="FAILED">FAILED</option>
          </select>
          <select
            className="input input-small"
            value={mode}
            aria-label="Filtrar por modo"
            onChange={(e) => {
              setMode(e.target.value);
              setPage(0);
            }}
          >
            <option value="">Modo (todos)</option>
            <option value="NOW">Agora</option>
            <option value="SCHEDULED">Agendado</option>
            <option value="REPOST">Repost</option>
            <option value="WELCOME">Boas-vindas</option>
          </select>
        </div>

        {loading && <Spinner />}
        <ErrorBanner error={error} />
        {data && (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Lojista</th>
                  <th>Bot</th>
                  <th>Modo</th>
                  <th>Status</th>
                  <th>Agendado</th>
                  <th>Criado</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {data.items.length === 0 && (
                  <tr>
                    <td colSpan={7} className="muted">
                      Nenhum broadcast encontrado.
                    </td>
                  </tr>
                )}
                {data.items.map((b) => (
                  <tr key={b.id} className="clickable-row" onClick={() => void openDetail(b.id)}>
                    <td>{b.merchantName || (b.merchantId || '').slice(0, 8)}</td>
                    <td>
                      <strong>{b.botDisplayName || '—'}</strong>
                      {b.botPlatform ? <div className="muted">{b.botPlatform}</div> : null}
                    </td>
                    <td>
                      <span className="tag tag-neutral">{MODE_LABEL[b.mode] || b.mode}</span>
                    </td>
                    <td>
                      <span className={`tag ${STATUS_CLASS[b.status] || ''}`}>{b.status}</span>
                    </td>
                    <td>{b.scheduledAt ? formatDate(b.scheduledAt) : '—'}</td>
                    <td>{formatDate(b.createdAt)}</td>
                    <td>
                      <button
                        type="button"
                        className="btn btn-small"
                        onClick={(e) => {
                          e.stopPropagation();
                          void openDetail(b.id);
                        }}
                      >
                        Inspecionar
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <Pagination page={data.page} pageCount={Math.max(data.totalPages, 1)} onChange={setPage} />
          </div>
        )}
      </Card>

      {detail && (
        <BroadcastDetailModal
          detail={detail}
          loading={detailLoading}
          error={detailError}
          onClose={() => setDetail(null)}
          onCancel={() => {
            setCancelError(null);
            setCancelTarget(detail);
          }}
        />
      )}

      <ConfirmDialog
        open={cancelTarget !== null}
        title="Cancelar campanha"
        message={`Cancelar a campanha do bot "${cancelTarget?.botDisplayName || ''}"? O broadcast será marcado como FAILED e NÃO poderá ser reenviado. A ação fica registrada na auditoria com o seu operador.`}
        confirmLabel="Cancelar campanha"
        tone="danger"
        busy={cancelBusy}
        error={cancelError}
        onConfirm={() => void runForceCancel()}
        onCancel={() => {
          setCancelTarget(null);
          setCancelError(null);
        }}
      />
    </div>
  );
}

function SuccessBanner({ message }: { message: string | null }) {
  if (!message) return null;
  return <div className="alert alert-success">{message}</div>;
}

/** Modal de inspeção (detalhe) — conteúdo completo + métricas de envio + logs. */
function BroadcastDetailModal({
  detail,
  loading,
  error,
  onClose,
  onCancel,
}: {
  detail: MasterBroadcastDetail;
  loading: boolean;
  error: unknown;
  onClose: () => void;
  onCancel: () => void;
}) {
  // Spec 73: focus trap/Escape/restauração de foco (padrão de todos os modais do console).
  const dialogRef = useDialogFocus({ open: true, onClose });
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="bc-detail-title">
      <div className="modal modal-wide" ref={dialogRef}>
        <h2 id="bc-detail-title" style={{ margin: 0, fontSize: 18 }}>
          Broadcast — {detail.botDisplayName || detail.id}
        </h2>
        <div className="muted" style={{ margin: '8px 0' }}>
          Lojista: {detail.merchantName || detail.merchantId} · Bot: {detail.botDisplayName} (
          {detail.botPlatform}) · Modo: {MODE_LABEL[detail.mode] || detail.mode} · Status:{' '}
          <span className={`tag ${STATUS_CLASS[detail.status] || ''}`}>{detail.status}</span>
        </div>

        {loading && <Spinner />}
        <ErrorBanner error={error} />

        <div className="bc-content" style={{ marginTop: 12 }}>
          {detail.content ? (
            <div style={{ whiteSpace: 'pre-wrap', marginBottom: 12 }}>{detail.content}</div>
          ) : null}
          {detail.mediaUrl ? (
            <div style={{ marginBottom: 12 }}>
              <strong>Mídia:</strong> <a href={detail.mediaUrl} target="_blank" rel="noreferrer">{detail.mediaUrl}</a>
            </div>
          ) : null}
          {detail.buttonsJson ? (
            <div className="muted" style={{ marginBottom: 12 }}>
              <strong>Botões:</strong> {detail.buttonsJson}
            </div>
          ) : null}
          {detail.errorMessage ? (
            <div className="alert alert-danger" style={{ marginBottom: 12 }}>
              {detail.errorMessage}
            </div>
          ) : null}

          <div className="bc-stats" style={{ display: 'flex', gap: 16, marginBottom: 12 }}>
            <span className="tag tag-success">{detail.successCount} ok</span>
            <span className="tag tag-warning">{detail.blockedCount} bloqueados</span>
            <span className="tag tag-danger">{detail.failedCount} falhas</span>
          </div>

          {detail.logs.length > 0 && (
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th>Destinatário</th>
                    <th>Status</th>
                    <th>Erro</th>
                    <th>Enviado</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.logs.map((l, i) => (
                    <tr key={`${l.recipientId}-${i}`}>
                      <td>{l.recipientId}</td>
                      <td>
                        <span className={`tag ${LOG_STATUS_CLASS[l.status] || ''}`}>{l.status}</span>
                      </td>
                      <td className="muted">{l.errorMessage || '—'}</td>
                      <td>{l.sentAt ? formatDate(l.sentAt) : '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {detail.status === 'PENDING' && (
            <button type="button" className="btn btn-danger" style={{ marginTop: 12 }} onClick={onCancel}>
              Cancelar campanha
            </button>
          )}
        </div>

        <div className="modal-actions">
          <button type="button" className="btn" onClick={onClose}>
            Fechar
          </button>
        </div>
      </div>
    </div>
  );
}
