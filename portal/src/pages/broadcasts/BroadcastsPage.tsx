// Lista de transmissões do portal (Spec 80/F0): GET /api/v1/portal/broadcasts
// (Spec 79) com badges de modo/status, filtros, empty state e ações
// Cancelar (ConfirmDialog danger — apenas PENDING → DELETE) e Duplicar
// (abre o editor pré-preenchido). O título do destino é resolvido via
// GET /api/v1/portal/bots/{id}/chats (Spec 79/D5 — cache de 60s no backend).

import { useEffect, useMemo, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { ErrorBanner, Spinner, SuccessBanner, useAsync } from '../../components/ui';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { api } from '../../apiClient';
import type {
  BroadcastChat,
  BroadcastMode,
  BroadcastStatus,
  BroadcastSummary,
  StoreBot,
} from './types';

const MODE_LABEL: Record<BroadcastMode, string> = {
  NOW: 'Agora',
  SCHEDULED: 'Agendada',
  WELCOME: 'Bem-vindos',
  REPOST: 'Repost',
};
const STATUS_LABEL: Record<BroadcastStatus, string> = {
  PENDING: 'Pendente',
  PROCESSING: 'Enviando',
  COMPLETED: 'Concluída',
  FAILED: 'Falhou',
};
const STATUS_CLASS: Record<BroadcastStatus, string> = {
  PENDING: 'tag-neutral',
  PROCESSING: 'tag-warning',
  COMPLETED: 'tag-success',
  FAILED: 'tag-danger',
};

function formatDate(iso: string | null): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleString('pt-BR');
}

export default function BroadcastsPage() {
  const location = useLocation();
  const { data, error, loading, reload } = useAsync<BroadcastSummary[]>(
    () => api.get<BroadcastSummary[]>('/api/v1/portal/broadcasts'),
    [],
  );
  const { data: bots } = useAsync<StoreBot[]>(() => api.get<StoreBot[]>('/api/v1/portal/bots'), []);
  const [chatTitles, setChatTitles] = useState<Record<string, string>>({});
  const [modeFilter, setModeFilter] = useState<'ALL' | BroadcastMode>('ALL');
  const [statusFilter, setStatusFilter] = useState<'ALL' | BroadcastStatus>('ALL');
  const [deleteTarget, setDeleteTarget] = useState<BroadcastSummary | null>(null);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<unknown>(null);
  const [feedback, setFeedback] = useState<string | null>(
    (location.state as { created?: boolean } | null)?.created ? 'Transmissão criada com sucesso.' : null,
  );

  // Resolve os títulos dos chats de destino (uma chamada por bot — cache 60s).
  useEffect(() => {
    if (!data || !bots) return;
    let cancelled = false;
    const botIds = [...new Set(data.filter((b) => b.targetChatId).map((b) => b.storeBotId))];
    void (async () => {
      const titles: Record<string, string> = {};
      for (const botId of botIds) {
        try {
          const chats = await api.get<BroadcastChat[]>(`/api/v1/portal/bots/${botId}/chats`);
          for (const c of chats) titles[c.id] = c.title;
        } catch {
          /* destino não resolvível → título fica "—" */
        }
      }
      if (!cancelled) setChatTitles(titles);
    })();
    return () => {
      cancelled = true;
    };
  }, [data, bots]);

  const botById = useMemo(() => new Map((bots || []).map((b) => [b.id, b])), [bots]);

  const filtered = useMemo(
    () =>
      (data || []).filter(
        (b) =>
          (modeFilter === 'ALL' || b.mode === modeFilter) &&
          (statusFilter === 'ALL' || b.status === statusFilter),
      ),
    [data, modeFilter, statusFilter],
  );

  const runCancel = async () => {
    if (!deleteTarget) return;
    setBusy(true);
    setActionError(null);
    setFeedback(null);
    try {
      await api.del(`/api/v1/portal/broadcasts/${deleteTarget.id}`);
      setFeedback('Transmissão cancelada.');
      setDeleteTarget(null);
      reload();
    } catch (err) {
      setActionError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page">
      <div className="bots-toolbar">
        <h1 style={{ margin: 0 }}>Transmissões</h1>
        <div className="bots-toolbar-actions">
          <Link to="/broadcasts/new" className="btn btn-primary">
            Nova transmissão
          </Link>
        </div>
      </div>

      <SuccessBanner message={feedback} />
      <ErrorBanner error={actionError} />

      {(data || []).length > 0 && (
        <div className="filter-row">
          <select
            className="input input-small"
            aria-label="Filtrar por modo"
            value={modeFilter}
            onChange={(e) => setModeFilter(e.target.value as 'ALL' | BroadcastMode)}
          >
            <option value="ALL">Todos os modos</option>
            <option value="NOW">Agora</option>
            <option value="SCHEDULED">Agendada</option>
            <option value="WELCOME">Bem-vindos</option>
            <option value="REPOST">Repost</option>
          </select>
          <select
            className="input input-small"
            aria-label="Filtrar por status"
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value as 'ALL' | BroadcastStatus)}
          >
            <option value="ALL">Todos os status</option>
            <option value="PENDING">Pendente</option>
            <option value="PROCESSING">Enviando</option>
            <option value="COMPLETED">Concluída</option>
            <option value="FAILED">Falhou</option>
          </select>
        </div>
      )}

      {loading ? (
        <Spinner />
      ) : error ? (
        <ErrorBanner error={error} />
      ) : filtered.length === 0 ? (
        <div className="card empty-state">
          <p>
            {data && data.length > 0
              ? 'Nenhuma transmissão com esses filtros.'
              : 'Nenhuma transmissão ainda.'}
          </p>
          {(!data || data.length === 0) && (
            <div className="empty-state-actions">
              <Link to="/broadcasts/new" className="btn btn-primary">
                Nova transmissão
              </Link>
            </div>
          )}
        </div>
      ) : (
        <div className="card">
          <ul className="plain-list">
            {filtered.map((b) => {
              const bot = botById.get(b.storeBotId);
              const title = b.targetChatId ? chatTitles[b.targetChatId] || null : null;
              return (
                <li key={b.id} className="dest-item">
                  <div>
                    <div className="bc-badges">
                      <span className={`tag ${STATUS_CLASS[b.status]}`}>{STATUS_LABEL[b.status]}</span>
                      <span className="tag tag-neutral">{MODE_LABEL[b.mode]}</span>
                    </div>
                    <div className="bc-broadcast-title">
                      <strong>{bot?.displayName || 'Bot'}</strong>
                      {b.targetChatId ? (
                        <span className="muted"> → {title || b.targetChatId}</span>
                      ) : (
                        <span className="muted"> → evento de entrada</span>
                      )}
                    </div>
                    <div className="muted">
                      Criada em {formatDate(b.createdAt)}
                      {b.scheduledAt ? ` · agendada para ${formatDate(b.scheduledAt)}` : ''}
                    </div>
                    {b.status === 'FAILED' && b.errorMessage && (
                      <div className="alert alert-error" role="alert">
                        {b.errorMessage}
                      </div>
                    )}
                  </div>
                  <div className="dest-actions">
                    <Link to={`/broadcasts/${b.id}/edit`} className="btn btn-small">
                      Duplicar
                    </Link>
                    {b.status === 'PENDING' && (
                      <button
                        type="button"
                        className="btn btn-small btn-danger"
                        onClick={() => {
                          setActionError(null);
                          setDeleteTarget(b);
                        }}
                      >
                        Cancelar
                      </button>
                    )}
                  </div>
                </li>
              );
            })}
          </ul>
        </div>
      )}

      <ConfirmDialog
        open={deleteTarget !== null}
        title="Cancelar transmissão"
        message={`Cancelar a transmissão ${deleteTarget ? MODE_LABEL[deleteTarget.mode].toLowerCase() : ''}? Ela será removida e não será enviada.`}
        confirmLabel="Cancelar transmissão"
        busy={busy}
        error={actionError}
        onConfirm={() => void runCancel()}
        onCancel={() => {
          setDeleteTarget(null);
          setActionError(null);
        }}
      />
    </div>
  );
}
