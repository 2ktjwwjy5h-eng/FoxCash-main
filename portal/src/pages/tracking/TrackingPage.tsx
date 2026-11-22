// Painel de Tracking do portal (Spec 82) — padrão da WebhooksPage (Spec 35) +
// UX da Spec 73, sobre os endpoints da Spec 81. Cards de perfis com badges de
// serviço, chips de bots, linha "X ok · Y erros", ações (editar/duplicar/
// ativar/sincronizar/excluir) e modais (configuração com tokens mascarados,
// resultado do sync, log de eventos paginado). Sem rota de backend nova.

import { useMemo, useState } from 'react';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { useDialogFocus } from '../../hooks/useDialogFocus';
import { api } from '../../apiClient';
import { ErrorBanner, Spinner, SuccessBanner, useAsync } from '../../components/ui';
import type { StoreBot } from '../broadcasts/types';
import type { PageResult, TrackingEventLog, TrackingProfile, TrackingSyncResult } from './types';

const SERVICE_LABEL: Record<string, string> = {
  facebook: 'Pixel',
  tiktok: 'TikTok',
  utmify: 'UTMify',
  otimizey: 'Otimizey',
};

function formatDate(iso: string | null): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleString('pt-BR');
}

function formatCount(n: number): string {
  return n.toLocaleString('pt-BR');
}

/** Campo token com placeholder mascarado quando já configurado (Spec 82/D4). */
function tokenPlaceholder(existingMask: string | null): string | undefined {
  return existingMask ? `${'•'.repeat(4)} (manter atual)` : undefined;
}

/** Validação por serviço de pixel: pixel sem token (e sem token existente) → aviso. */
function pixelTokenInvalid(pixel: string, token: string, existingMask: string | null): boolean {
  return pixel.trim() !== '' && token.trim() === '' && !existingMask;
}

interface ProfileForm {
  name: string;
  facebookPixelId: string;
  facebookAccessToken: string;
  tiktokPixelId: string;
  tiktokAccessToken: string;
  utmifyToken: string;
  otimizeyToken: string;
  botIds: string[];
}

function emptyForm(): ProfileForm {
  return {
    name: '',
    facebookPixelId: '',
    facebookAccessToken: '',
    tiktokPixelId: '',
    tiktokAccessToken: '',
    utmifyToken: '',
    otimizeyToken: '',
    botIds: [],
  };
}

function formFrom(profile: TrackingProfile): ProfileForm {
  return {
    name: profile.name,
    facebookPixelId: profile.facebookPixelId || '',
    facebookAccessToken: '',
    tiktokPixelId: profile.tiktokPixelId || '',
    tiktokAccessToken: '',
    utmifyToken: '',
    otimizeyToken: '',
    botIds: [...profile.botIds],
  };
}

export default function TrackingPage() {
  const { data: profiles, error, loading, reload } = useAsync<TrackingProfile[]>(
    () => api.get<TrackingProfile[]>('/api/v1/portal/tracking'),
    [],
  );
  const { data: bots } = useAsync<StoreBot[]>(() => api.get<StoreBot[]>('/api/v1/portal/bots'), []);

  const [form, setForm] = useState<ProfileForm | null>(null);
  const [editing, setEditing] = useState<TrackingProfile | null>(null);
  const [formError, setFormError] = useState<unknown>(null);
  const [formBusy, setFormBusy] = useState(false);

  const [syncTarget, setSyncTarget] = useState<TrackingProfile | null>(null);
  const [syncResults, setSyncResults] = useState<TrackingSyncResult[] | null>(null);
  const [syncBusy, setSyncBusy] = useState(false);
  const [syncError, setSyncError] = useState<unknown>(null);

  const [logTarget, setLogTarget] = useState<TrackingProfile | null>(null);

  const [duplicateTarget, setDuplicateTarget] = useState<TrackingProfile | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<TrackingProfile | null>(null);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<unknown>(null);
  const [feedback, setFeedback] = useState<string | null>(null);

  const botById = useMemo(() => new Map((bots || []).map((b) => [b.id, b])), [bots]);

  const summary = useMemo(() => {
    const list = profiles || [];
    const active = list.filter((p) => p.status === 'ACTIVE').length;
    const flows = new Set(list.flatMap((p) => p.botIds)).size;
    const okEvents = list.reduce((acc, p) => acc + (p.okEvents || 0), 0);
    return { active, flows, okEvents };
  }, [profiles]);

  const openCreate = () => {
    setActionError(null);
    setEditing(null);
    setForm(emptyForm());
    setFormError(null);
  };

  const openEdit = (profile: TrackingProfile) => {
    setActionError(null);
    setEditing(profile);
    setForm(formFrom(profile));
    setFormError(null);
  };

  const saveForm = async () => {
    if (!form) return;
    if (form.name.trim() === '') {
      setFormError(new Error('Informe o nome do perfil.'));
      return;
    }
    if (
      pixelTokenInvalid(form.facebookPixelId, form.facebookAccessToken, editing?.facebookTokenMask ?? null) ||
      pixelTokenInvalid(form.tiktokPixelId, form.tiktokAccessToken, editing?.tiktokTokenMask ?? null)
    ) {
      setFormError(new Error('Serviço com pixel/código exige token (ou deixe o campo vazio para desativá-lo).'));
      return;
    }
    const body = {
      name: form.name.trim(),
      facebookPixelId: form.facebookPixelId.trim() || null,
      facebookAccessToken: form.facebookAccessToken.trim() || null,
      tiktokPixelId: form.tiktokPixelId.trim() || null,
      tiktokAccessToken: form.tiktokAccessToken.trim() || null,
      utmifyToken: form.utmifyToken.trim() || null,
      otimizeyToken: form.otimizeyToken.trim() || null,
      botIds: form.botIds,
    };
    setFormBusy(true);
    setFormError(null);
    try {
      if (editing) {
        await api.put(`/api/v1/portal/tracking/${editing.id}`, body);
        setFeedback('Perfil atualizado.');
      } else {
        await api.post('/api/v1/portal/tracking', body);
        setFeedback('Perfil criado.');
      }
      setForm(null);
      reload();
    } catch (err) {
      setFormError(err);
    } finally {
      setFormBusy(false);
    }
  };

  const runToggle = async (profile: TrackingProfile) => {
    setBusy(true);
    setActionError(null);
    setFeedback(null);
    try {
      await api.post(`/api/v1/portal/tracking/${profile.id}/${profile.status === 'ACTIVE' ? 'deactivate' : 'activate'}`);
      setFeedback(profile.status === 'ACTIVE' ? 'Perfil desativado.' : 'Perfil ativado.');
      reload();
    } catch (err) {
      setActionError(err);
    } finally {
      setBusy(false);
    }
  };

  const runDuplicate = async () => {
    if (!duplicateTarget) return;
    setBusy(true);
    setActionError(null);
    setFeedback(null);
    try {
      await api.post(`/api/v1/portal/tracking/${duplicateTarget.id}/duplicate`);
      setFeedback('Perfil duplicado.');
      setDuplicateTarget(null);
      reload();
    } catch (err) {
      setActionError(err);
    } finally {
      setBusy(false);
    }
  };

  const runDelete = async () => {
    if (!deleteTarget) return;
    setBusy(true);
    setActionError(null);
    setFeedback(null);
    try {
      await api.del(`/api/v1/portal/tracking/${deleteTarget.id}`);
      setFeedback('Perfil excluído.');
      setDeleteTarget(null);
      reload();
    } catch (err) {
      setActionError(err);
    } finally {
      setBusy(false);
    }
  };

  const runSync = async (profile: TrackingProfile) => {
    setSyncTarget(profile);
    setSyncResults(null);
    setSyncError(null);
    setSyncBusy(true);
    try {
      const results = await api.post<TrackingSyncResult[]>(`/api/v1/portal/tracking/${profile.id}/sync`);
      setSyncResults(results);
    } catch (err) {
      setSyncError(err);
    } finally {
      setSyncBusy(false);
    }
  };

  return (
    <div className="page">
      <div className="bots-toolbar">
        <h1 style={{ margin: 0 }}>Tracking</h1>
        <div className="bots-toolbar-actions">
          <button type="button" className="btn btn-primary" onClick={openCreate}>
            Criar perfil
          </button>
        </div>
      </div>

      <SuccessBanner message={feedback} />
      <ErrorBanner error={actionError} />

      {loading ? (
        <Spinner />
      ) : error ? (
        <ErrorBanner error={error} />
      ) : (
        <>
          <div className="summary-cards" aria-label="Resumo de tracking">
            <div className="card summary-card">
              <div className="summary-value">{summary.active}</div>
              <div className="summary-label">Perfis ativos</div>
            </div>
            <div className="card summary-card">
              <div className="summary-value">{summary.flows}</div>
              <div className="summary-label">Fluxos rastreados</div>
            </div>
            <div className="card summary-card">
              <div className="summary-value">{formatCount(summary.okEvents)}</div>
              <div className="summary-label">Eventos com sucesso</div>
            </div>
          </div>

          {(profiles || []).length === 0 ? (
            <div className="card empty-state">
              <p>Nenhum perfil de tracking ainda.</p>
              <div className="empty-state-actions">
                <button type="button" className="btn btn-primary" onClick={openCreate}>
                  Criar perfil
                </button>
              </div>
            </div>
          ) : (
            <div className="trk-grid">
              {(profiles || []).map((profile) => (
                <section key={profile.id} className="card trk-card">
                  <div className="trk-card-header">
                    <div className="trk-card-title">
                      <strong>{profile.name}</strong>
                      <span className={`tag ${profile.status === 'ACTIVE' ? 'tag-success' : 'tag-neutral'}`}>
                        {profile.status === 'ACTIVE' ? 'Ativo' : 'Inativo'}
                      </span>
                    </div>
                    <div className="trk-badges">
                      {(profile.services || []).map((service) => (
                        <span key={service} className="tag tag-primary">
                          {SERVICE_LABEL[service] || service}
                        </span>
                      ))}
                    </div>
                  </div>
                  <div className="muted" style={{ margin: '8px 0' }}>
                    Criado em {formatDate(profile.createdAt)}
                  </div>
                  {(profile.botIds || []).length > 0 && (
                    <div className="trk-chips" aria-label="Bots vinculados">
                      {profile.botIds.map((botId) => (
                        <span key={botId} className="chip">
                          {botById.get(botId)?.displayName || 'Bot'}
                        </span>
                      ))}
                    </div>
                  )}
                  <div className="trk-logline">
                    <span className={profile.okEvents > 0 ? 'trk-ok' : 'muted'}>{formatCount(profile.okEvents)} ok</span>
                    <span className="muted"> · </span>
                    <span className={profile.errorEvents > 0 ? 'trk-error' : 'muted'}>{formatCount(profile.errorEvents)} erros</span>
                  </div>
                  <div className="dest-actions trk-actions">
                    <button type="button" className="btn btn-small" onClick={() => openEdit(profile)}>
                      Editar
                    </button>
                    <button type="button" className="btn btn-small" onClick={() => setLogTarget(profile)}>
                      Log
                    </button>
                    <button
                      type="button"
                      className="btn btn-small"
                      onClick={() => {
                        setActionError(null);
                        setDuplicateTarget(profile);
                      }}
                    >
                      Duplicar
                    </button>
                    <button type="button" className="btn btn-small" onClick={() => void runSync(profile)}>
                      Sincronizar
                    </button>
                    <button type="button" className="btn btn-small" onClick={() => void runToggle(profile)}>
                      {profile.status === 'ACTIVE' ? 'Desativar' : 'Ativar'}
                    </button>
                    <button
                      type="button"
                      className="btn btn-small btn-danger"
                      onClick={() => {
                        setActionError(null);
                        setDeleteTarget(profile);
                      }}
                    >
                      Excluir
                    </button>
                  </div>
                </section>
              ))}
            </div>
          )}
        </>
      )}

      {form && (
        <TrackingProfileModal
          editing={editing}
          bots={bots || []}
          form={form}
          setForm={setForm}
          error={formError}
          busy={formBusy}
          onSave={() => void saveForm()}
          onClose={() => setForm(null)}
        />
      )}

      {(syncTarget || syncBusy) && (
        <SyncResultModal
          profileName={syncTarget?.name || '…'}
          results={syncResults}
          error={syncError}
          busy={syncBusy}
          onClose={() => setSyncTarget(null)}
        />
      )}

      {logTarget && <EventLogModal profile={logTarget} onClose={() => setLogTarget(null)} />}

      <ConfirmDialog
        open={duplicateTarget !== null}
        title="Duplicar perfil"
        message={`Duplicar o perfil "${duplicateTarget?.name || ''}"? Um novo perfil será criado com a mesma configuração.`}
        confirmLabel="Duplicar"
        tone="primary"
        busy={busy}
        error={actionError}
        onConfirm={() => void runDuplicate()}
        onCancel={() => {
          setDuplicateTarget(null);
          setActionError(null);
        }}
      />

      <ConfirmDialog
        open={deleteTarget !== null}
        title="Excluir perfil"
        message={`Excluir o perfil "${deleteTarget?.name || ''}"? A configuração e o log de disparos serão removidos.`}
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

/** Modal de criação/edição de perfil (Spec 82/D3) — tokens mascarados. */
function TrackingProfileModal({
  editing,
  bots,
  form,
  setForm,
  error,
  busy,
  onSave,
  onClose,
}: {
  editing: TrackingProfile | null;
  bots: StoreBot[];
  form: ProfileForm;
  setForm: React.Dispatch<React.SetStateAction<ProfileForm | null>>;
  error: unknown;
  busy: boolean;
  onSave: () => void;
  onClose: () => void;
}) {
  const ref = useDialogFocus({ open: true, onClose });
  const set = (patch: Partial<ProfileForm>) => setForm((prev) => (prev ? { ...prev, ...patch } : prev));

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="trk-modal-title">
      <div className="modal" ref={ref}>
        <h2 id="trk-modal-title" style={{ margin: 0, fontSize: 18 }}>
          {editing ? `Editar perfil — ${editing.name}` : 'Novo perfil de tracking'}
        </h2>

        <label className="field">
          <span className="field-label">Nome (obrigatório)</span>
          <input
            className="input"
            value={form.name}
            onChange={(e) => set({ name: e.target.value })}
            placeholder="Ex.: Pixel Modelo"
          />
        </label>

        <fieldset className="trk-service-section">
          <legend>Facebook (CAPI)</legend>
          <label className="field">
            <span className="field-label">Pixel ID</span>
            <input
              className="input"
              value={form.facebookPixelId}
              onChange={(e) => set({ facebookPixelId: e.target.value })}
              placeholder="Ex.: 123456789012345"
            />
          </label>
          <label className="field">
            <span className="field-label">Token de acesso</span>
            <input
              className="input"
              type="password"
              value={form.facebookAccessToken}
              onChange={(e) => set({ facebookAccessToken: e.target.value })}
              placeholder={tokenPlaceholder(editing?.facebookTokenMask ?? null)}
            />
          </label>
        </fieldset>

        <fieldset className="trk-service-section">
          <legend>TikTok</legend>
          <label className="field">
            <span className="field-label">Pixel Code</span>
            <input
              className="input"
              value={form.tiktokPixelId}
              onChange={(e) => set({ tiktokPixelId: e.target.value })}
              placeholder="Ex.: ABC123"
            />
          </label>
          <label className="field">
            <span className="field-label">Token de acesso</span>
            <input
              className="input"
              type="password"
              value={form.tiktokAccessToken}
              onChange={(e) => set({ tiktokAccessToken: e.target.value })}
              placeholder={tokenPlaceholder(editing?.tiktokTokenMask ?? null)}
            />
          </label>
        </fieldset>

        <fieldset className="trk-service-section">
          <legend>UTMify / Otimizey</legend>
          <label className="field">
            <span className="field-label">Token UTMify</span>
            <input
              className="input"
              type="password"
              value={form.utmifyToken}
              onChange={(e) => set({ utmifyToken: e.target.value })}
              placeholder={tokenPlaceholder(editing?.utmifyTokenMask ?? null)}
            />
          </label>
          <label className="field">
            <span className="field-label">Token Otimizey</span>
            <input
              className="input"
              type="password"
              value={form.otimizeyToken}
              onChange={(e) => set({ otimizeyToken: e.target.value })}
              placeholder={tokenPlaceholder(editing?.otimizeyTokenMask ?? null)}
            />
          </label>
        </fieldset>

        <fieldset className="trk-service-section">
          <legend>Bots vinculados</legend>
          {bots.length === 0 ? (
            <p className="muted">Nenhum bot cadastrado (crie em “Bots”).</p>
          ) : (
            <div className="trk-chips">
              {bots.map((bot) => {
                const selected = form.botIds.includes(bot.id);
                return (
                  <button
                    key={bot.id}
                    type="button"
                    className={`chip chip-toggle ${selected ? 'chip-selected' : ''}`}
                    aria-pressed={selected}
                    onClick={() =>
                      set({
                        botIds: selected ? form.botIds.filter((id) => id !== bot.id) : [...form.botIds, bot.id],
                      })
                    }
                  >
                    {bot.displayName}
                  </button>
                );
              })}
            </div>
          )}
        </fieldset>

        <ErrorBanner error={error} />
        <div className="modal-actions">
          <button type="button" className="btn" onClick={onClose} disabled={busy}>
            Cancelar
          </button>
          <button type="button" className="btn btn-primary" onClick={onSave} disabled={busy}>
            {busy ? 'Salvando…' : 'Salvar'}
          </button>
        </div>
      </div>
    </div>
  );
}

/** Resultado do sincronizar por serviço (Spec 82/D3 — SyncResultModal). */
function SyncResultModal({
  profileName,
  results,
  error,
  busy,
  onClose,
}: {
  profileName: string;
  results: TrackingSyncResult[] | null;
  error: unknown;
  busy: boolean;
  onClose: () => void;
}) {
  const ref = useDialogFocus({ open: true, onClose });
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="trk-sync-title">
      <div className="modal" ref={ref}>
        <h2 id="trk-sync-title" style={{ margin: 0, fontSize: 18 }}>
          Sincronizar — {profileName}
        </h2>
        {busy ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : error ? (
          <ErrorBanner error={error} />
        ) : results && results.length === 0 ? (
          <p className="muted">Nenhum serviço configurado neste perfil.</p>
        ) : (
          <ul className="plain-list" style={{ marginTop: 12 }}>
            {(results || []).map((r) => (
              <li key={r.service} className="dest-item">
                <div>
                  <span className={`tag ${r.ok ? 'tag-success' : 'tag-danger'}`}>{SERVICE_LABEL[r.service] || r.service}</span>
                </div>
                <div className="muted">{r.message || (r.ok ? 'Conectado.' : 'Falha.')}</div>
              </li>
            ))}
          </ul>
        )}
        <div className="modal-actions">
          <button type="button" className="btn" onClick={onClose}>
            Fechar
          </button>
        </div>
      </div>
    </div>
  );
}

/** Log de eventos do perfil (Spec 82/D3 — EventLogModal), paginado. */
function EventLogModal({ profile, onClose }: { profile: TrackingProfile; onClose: () => void }) {
  const [page, setPage] = useState(0);
  const size = 10;
  const { data, error, loading } = useAsync<PageResult<TrackingEventLog>>(
    () =>
      api.get<PageResult<TrackingEventLog>>(
        `/api/v1/portal/tracking/${profile.id}/events?page=${page}&size=${size}`,
      ),
    [profile.id, page],
  );
  const ref = useDialogFocus({ open: true, onClose });

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="trk-log-title">
      <div className="modal" ref={ref}>
        <h2 id="trk-log-title" style={{ margin: 0, fontSize: 18 }}>
          Log de eventos — {profile.name}
        </h2>
        {loading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : error ? (
          <ErrorBanner error={error} />
        ) : !data || data.items.length === 0 ? (
          <p className="muted">Nenhum evento disparado ainda.</p>
        ) : (
          <>
            <ul className="plain-list" style={{ marginTop: 12 }}>
              {data.items.map((log) => (
                <li key={`${log.eventId}-${log.eventType}`} className="dest-item">
                  <div>
                    <div className="bc-badges">
                      <span className={`tag ${log.status === 'OK' ? 'tag-success' : 'tag-danger'}`}>
                        {log.status}
                      </span>
                      <span className="tag tag-neutral">{log.eventType}</span>
                    </div>
                    <div className="muted">{formatDate(log.createdAt)}</div>
                    {log.responsePayload && (
                      <details className="trk-payload">
                        <summary>Ver detalhes</summary>
                        <pre>{log.responsePayload}</pre>
                      </details>
                    )}
                  </div>
                </li>
              ))}
            </ul>
            <div className="pagination-row">
              <button
                type="button"
                className="btn btn-small"
                disabled={page === 0}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
              >
                Anterior
              </button>
              <span className="muted">
                Página {data.page + 1} de {Math.max(1, data.totalPages)}
              </span>
              <button
                type="button"
                className="btn btn-small"
                disabled={page + 1 >= data.totalPages}
                onClick={() => setPage((p) => p + 1)}
              >
                Próxima
              </button>
            </div>
          </>
        )}
        <div className="modal-actions">
          <button type="button" className="btn" onClick={onClose}>
            Fechar
          </button>
        </div>
      </div>
    </div>
  );
}
