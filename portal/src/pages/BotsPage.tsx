// Painel "Meus Bots" do portal (Spec 76) — CRUD dos bots de venda do lojista
// (Spec 74) + métricas por bot (Spec 75):
//   - GET /api/v1/portal/bots           → lista (filtro por plataforma no cliente)
//   - GET /api/v1/portal/bots/metrics   → leads/vendas/conversão por bot
//   - POST /api/v1/portal/bots          → conectar (integração real Spec 74)
//   - PUT /api/v1/portal/bots/{id}      → editar display_name
//   - POST /api/v1/portal/bots/{id}/pause|resume → pausar/reativar
//   - DELETE /api/v1/portal/bots/{id}   → excluir (ConfirmDialog danger — Spec 73)
//
// Spec 73: banners de sucesso/erro em TODA ação de escrita (sem unhandled
// rejection), Spinner no primeiro carregamento (sem blink), ConfirmDialog
// acessível para a ação irreversível, empty state com CTA.

import { useMemo, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { ErrorBanner, Field, Spinner, SuccessBanner, useAsync } from '../components/ui';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { useDialogFocus } from '../hooks/useDialogFocus';
import { api } from '../apiClient';
import { useProfile } from '../profile';

interface StoreBot {
  id: string;
  platform: 'TELEGRAM' | 'DISCORD';
  status: 'REGISTERING' | 'ACTIVE' | 'PAUSED' | 'ERROR';
  displayName: string;
  username: string | null;
  applicationId: string | null;
  tokenMask: string | null;
  errorMessage: string | null;
  lastHealthCheckAt: string | null;
  createdAt: string;
  updatedAt: string;
}

interface BotMetrics {
  botId: string;
  merchantId: string | null;
  merchantName: string | null;
  leads: number;
  vendas: number;
  receita: number | string;
  conversao: number;
}

interface BotMetricsAggregate {
  bots: BotMetrics[];
  total: { leads: number; vendas: number; receita: number | string; conversao: number };
}

type PlatformFilter = 'ALL' | 'TELEGRAM' | 'DISCORD';

/** Status exibido no card (Spec 76/D2.1): ONLINE=ACTIVE, OFFLINE=PAUSED/REGISTERING, ERRO=ERROR. */
const STATUS_LABEL: Record<StoreBot['status'], string> = {
  ACTIVE: 'ONLINE',
  PAUSED: 'OFFLINE',
  REGISTERING: 'OFFLINE',
  ERROR: 'ERRO',
};

const STATUS_CLASS: Record<StoreBot['status'], string> = {
  ACTIVE: 'tag-success',
  PAUSED: 'tag-neutral',
  REGISTERING: 'tag-warning',
  ERROR: 'tag-danger',
};

const PLATFORM_LABEL: Record<string, string> = { TELEGRAM: 'Telegram', DISCORD: 'Discord' };

function formatMetrics(n: number): string {
  return n.toLocaleString('pt-BR');
}

export default function BotsPage() {
  // Specs 122/123: bots do PERFIL ATIVO recebem destaque (fase 1 — nada é
  // escondido; o filtro rígido é a Spec 124).
  const { activeProfile } = useProfile();
  const profileBotIds = useMemo(
    () => new Set((activeProfile?.bots || []).map((b) => b.id)),
    [activeProfile],
  );

  // Busca bots + métricas em paralelo; o reload recarrega os dois (sem blink —
  // Spec 73: useAsync mantém os dados anteriores visíveis).
  const { data, error, loading, reload } = useAsync(
    async () => {
      const [bots, metrics] = await Promise.all([
        api.get<StoreBot[]>('/api/v1/portal/bots'),
        api.get<BotMetricsAggregate>('/api/v1/portal/bots/metrics'),
      ]);
      return { bots, metrics };
    },
    [],
  );

  const [filter, setFilter] = useState<PlatformFilter>('ALL');
  const [connectOpen, setConnectOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<StoreBot | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<StoreBot | null>(null);
  const [actionError, setActionError] = useState<unknown>(null);
  const [feedback, setFeedback] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const metricsByBot = new Map((data?.metrics.bots || []).map((m) => [m.botId, m]));

  const bots = (data?.bots || []).filter((b) => filter === 'ALL' || b.platform === filter);

  /** Executa uma ação de escrita com feedback uniforme (Spec 73). */
  const runAction = async (
    fn: () => Promise<unknown>,
    successMessage: string,
  ): Promise<boolean> => {
    setBusy(true);
    setActionError(null);
    setFeedback(null);
    try {
      await fn();
      setFeedback(successMessage);
      return true;
    } catch (err) {
      setActionError(err);
      return false;
    } finally {
      setBusy(false);
    }
  };

  const runPause = (bot: StoreBot) =>
    runAction(() => api.post(`/api/v1/portal/bots/${bot.id}/pause`), `Bot ${bot.displayName} pausado.`)
      .then((ok) => ok && reload());

  const runResume = (bot: StoreBot) =>
    runAction(() => api.post(`/api/v1/portal/bots/${bot.id}/resume`), `Bot ${bot.displayName} reativado.`)
      .then((ok) => ok && reload());

  const runDelete = async () => {
    if (!deleteTarget) return;
    const ok = await runAction(
      () => api.del(`/api/v1/portal/bots/${deleteTarget.id}`),
      `Bot ${deleteTarget.displayName} removido.`,
    );
    if (ok) {
      setDeleteTarget(null);
      reload();
    }
  };

  return (
    <div className="page">
      <h1>Meus Bots</h1>

      <SuccessBanner message={feedback} />
      <ErrorBanner error={actionError} />

      <div className="bots-toolbar">
        <div className="bots-filters" role="group" aria-label="Filtrar por plataforma">
          {(['ALL', 'TELEGRAM', 'DISCORD'] as PlatformFilter[]).map((f) => (
            <button
              key={f}
              type="button"
              className={filter === f ? 'btn btn-small btn-primary' : 'btn btn-small'}
              aria-pressed={filter === f}
              onClick={() => setFilter(f)}
            >
              {f === 'ALL' ? 'Todas' : PLATFORM_LABEL[f]}
            </button>
          ))}
        </div>
        <div className="bots-toolbar-actions">
          <Link to="/bots/creator/telegram" className="btn">
            Criar bot Telegram
          </Link>
          <Link to="/bots/creator/discord" className="btn">
            Criar bot Discord
          </Link>
          <button type="button" className="btn btn-primary" onClick={() => setConnectOpen(true)}>
            Conectar bot
          </button>
        </div>
      </div>

      {loading ? (
        <Spinner />
      ) : error ? (
        <ErrorBanner error={error} />
      ) : bots.length === 0 ? (
        <div className="card empty-state">
          <p>
            {filter === 'ALL'
              ? 'Nenhum bot conectado ainda.'
              : `Nenhum bot de ${PLATFORM_LABEL[filter]} conectado.`}
          </p>
          <div className="empty-state-actions">
            <Link to="/bots/creator/telegram" className="btn btn-primary">
              Criar bot Telegram
            </Link>
            <Link to="/bots/creator/discord" className="btn">
              Criar bot Discord
            </Link>
            <button type="button" className="btn" onClick={() => setConnectOpen(true)}>
              Conectar bot
            </button>
          </div>
        </div>
      ) : (
        <div className="bot-grid">
          {bots.map((bot) => {
            const metrics = metricsByBot.get(bot.id);
            return (
              <section
                key={bot.id}
                className={profileBotIds.has(bot.id) ? 'card bot-card is-active' : 'card bot-card'}
              >
                <div className="bot-card-header">
                  <span className="bot-avatar" aria-hidden="true">
                    {bot.platform === 'TELEGRAM' ? 'TG' : 'DC'}
                  </span>
                  <div className="bot-card-title">
                    <strong>{bot.displayName}</strong>
                    <span className="muted">@{bot.username || 'sem @'}</span>
                  </div>
                  {profileBotIds.has(bot.id) && (
                    <span className="tag tag-active">perfil ativo</span>
                  )}
                  <span className={`tag ${STATUS_CLASS[bot.status]}`}>{STATUS_LABEL[bot.status]}</span>
                </div>
                {bot.status === 'ERROR' && bot.errorMessage && (
                  <div className="alert alert-error" role="alert">
                    {bot.errorMessage}
                  </div>
                )}
                <div className="bot-metrics">
                  <span>
                    <strong>{formatMetrics(metrics?.leads ?? 0)}</strong> leads
                  </span>
                  <span>
                    <strong>{formatMetrics(metrics?.vendas ?? 0)}</strong> vendas
                  </span>
                  <span>
                    <strong>{formatMetrics(metrics?.conversao ?? 0)}</strong> conv.
                  </span>
                </div>
                <div className="bot-actions">
                  {bot.status === 'ACTIVE' ? (
                    <button type="button" className="btn btn-small" onClick={() => void runPause(bot)}>
                      Pausar
                    </button>
                  ) : bot.status === 'PAUSED' || bot.status === 'ERROR' ? (
                    <button type="button" className="btn btn-small" onClick={() => void runResume(bot)}>
                      Reativar
                    </button>
                  ) : null}
                  <button type="button" className="btn btn-small" onClick={() => setEditTarget(bot)}>
                    Editar
                  </button>
                  <button
                    type="button"
                    className="btn btn-small btn-danger"
                    onClick={() => {
                      setActionError(null);
                      setDeleteTarget(bot);
                    }}
                  >
                    Excluir
                  </button>
                </div>
              </section>
            );
          })}
        </div>
      )}

      {connectOpen && (
        <ConnectBotModal
          onClose={() => {
            setConnectOpen(false);
            setActionError(null);
          }}
          onConnected={async (message) => {
            setConnectOpen(false);
            setFeedback(message);
            reload();
          }}
        />
      )}

      {editTarget && (
        <EditBotModal
          bot={editTarget}
          onClose={() => {
            setEditTarget(null);
            setActionError(null);
          }}
          onSaved={async (message) => {
            setEditTarget(null);
            setFeedback(message);
            reload();
          }}
        />
      )}

      <ConfirmDialog
        open={deleteTarget !== null}
        title="Excluir bot"
        message={`Excluir o bot ${deleteTarget?.displayName || ''}? O registro será removido e o webhook deixará de receber updates. Ação irreversível.`}
        confirmLabel="Excluir"
        busy={busy}
        error={actionError}
        onConfirm={() => void runDelete()}
        onCancel={() => {
          setDeleteTarget(null);
          setActionError(null);
        }}
      />
    </div>
  );
}

/** Modal de conexão (Spec 76/D4.1): plataforma + token (+ Application ID no Discord). */
function ConnectBotModal({
  onClose,
  onConnected,
}: {
  onClose: () => void;
  onConnected: (message: string) => Promise<void>;
}) {
  const [platform, setPlatform] = useState<'TELEGRAM' | 'DISCORD'>('TELEGRAM');
  const [token, setToken] = useState('');
  const [applicationId, setApplicationId] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);
  const dialogRef = useDialogFocus({ open: true, onClose, initialFocus: 'first' });

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const body =
        platform === 'DISCORD'
          ? { platform, token, applicationId, displayName: displayName || undefined }
          : { platform, token, displayName: displayName || undefined };
      await api.post('/api/v1/portal/bots', body);
      await onConnected(
        platform === 'DISCORD' ? 'Bot do Discord conectado!' : 'Bot do Telegram conectado!',
      );
    } catch (err) {
      setError(err);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div ref={dialogRef} className="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="connect-bot-title">
      <div className="modal">
        <h2 id="connect-bot-title" style={{ margin: 0, fontSize: 18 }}>
          Conectar bot
        </h2>
        <form className="form" onSubmit={handleSubmit}>
          <ErrorBanner error={error} />
          <div className="field">
            <span className="field-label">Plataforma</span>
            <select
              className="input"
              aria-label="Plataforma"
              value={platform}
              onChange={(e) => setPlatform(e.target.value as 'TELEGRAM' | 'DISCORD')}
            >
              <option value="TELEGRAM">Telegram</option>
              <option value="DISCORD">Discord</option>
            </select>
          </div>
          <Field
            label={platform === 'TELEGRAM' ? 'Token do bot (BotFather)' : 'Token do bot (Developer Portal)'}
            required
            type="password"
            autoComplete="off"
            value={token}
            onChange={(e) => setToken(e.target.value)}
          />
          {platform === 'DISCORD' && (
            <Field
              label="Application ID"
              required
              value={applicationId}
              onChange={(e) => setApplicationId(e.target.value)}
            />
          )}
          <Field
            label="Nome de exibição (opcional)"
            maxLength={120}
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
          />
          <div className="modal-actions">
            <button type="button" className="btn" onClick={onClose} disabled={submitting}>
              Cancelar
            </button>
            <button type="submit" className="btn btn-primary" disabled={submitting}>
              {submitting ? 'Validando…' : 'Validar e conectar'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

/** Modal de edição (Spec 76/D4.2): display_name — o contrato da Spec 74 suporta
 *  apenas displayName (descrição/comandos são dos criadores, Specs 77/78). */
function EditBotModal({
  bot,
  onClose,
  onSaved,
}: {
  bot: StoreBot;
  onClose: () => void;
  onSaved: (message: string) => Promise<void>;
}) {
  const [displayName, setDisplayName] = useState(bot.displayName);
  const [error, setError] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);
  const dialogRef = useDialogFocus({ open: true, onClose, initialFocus: 'first' });

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await api.put(`/api/v1/portal/bots/${bot.id}`, { displayName });
      await onSaved(`Bot ${displayName} atualizado.`);
    } catch (err) {
      setError(err);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div ref={dialogRef} className="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="edit-bot-title">
      <div className="modal">
        <h2 id="edit-bot-title" style={{ margin: 0, fontSize: 18 }}>
          Editar bot
        </h2>
        <form className="form" onSubmit={handleSubmit}>
          <ErrorBanner error={error} />
          <Field
            label="Nome de exibição"
            required
            maxLength={120}
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
          />
          <div className="muted">Plataforma: {PLATFORM_LABEL[bot.platform]} · @{bot.username || 'sem @'}</div>
          <div className="modal-actions">
            <button type="button" className="btn" onClick={onClose} disabled={submitting}>
              Cancelar
            </button>
            <button type="submit" className="btn btn-primary" disabled={submitting}>
              {submitting ? 'Salvando…' : 'Salvar'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
