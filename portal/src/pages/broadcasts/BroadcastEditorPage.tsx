// Editor + preview do broadcast no portal (Spec 80/F1+F2): compõe a
// transmissão com formatação rich-text da plataforma do bot, tags dinâmicas,
// mídia e botões inline, com preview em tempo real, e envia via
// POST /api/v1/portal/broadcasts (Spec 79). Rotas: /broadcasts/new e
// /broadcasts/:id/edit (pré-preenchido — usado pelo "Duplicar" da lista).

import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ErrorBanner, Spinner, SuccessBanner, useAsync } from '../../components/ui';
import { api } from '../../apiClient';
import { useProfile } from '../../profile';
import { insertAtCursor, RichTextArea, RichTextToolbar } from './RichTextToolbar';
import { TagInserter } from './TagInserter';
import { MediaUploader } from './MediaUploader';
import { ButtonsGridEditor } from './ButtonsGridEditor';
import { ModeBar } from './ModeBar';
import { BroadcastPreview } from './BroadcastPreview';
import { useBroadcastForm } from './useBroadcastForm';
import type {
  BotPlatform,
  BroadcastChat,
  BroadcastDetail,
  BroadcastMode,
  ButtonGrid,
  StoreBot,
} from './types';

const SUBMIT_LABEL: Record<BroadcastMode, string> = {
  NOW: 'Enviar agora',
  SCHEDULED: 'Agendar',
  WELCOME: 'Salvar',
  REPOST: 'Iniciar repost',
};

function toDatetimeLocal(iso: string): string {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function parseButtonsJson(json: string | null): ButtonGrid {
  if (!json) return [];
  try {
    const parsed = JSON.parse(json) as {
      rows?: Array<Array<{ label?: string; url?: string; callback?: string }>>;
    };
    if (!parsed || !Array.isArray(parsed.rows)) return [];
    return parsed.rows.map((row) =>
      (row || []).map((b) => ({
        label: b.label || '',
        type: b.url ? ('link' as const) : ('callback' as const),
        url: b.url || '',
        callback: b.callback || '',
      })),
    );
  } catch {
    return [];
  }
}

function buildButtonsJson(rows: ButtonGrid): string | null {
  if (rows.length === 0) return null;
  return JSON.stringify({
    rows: rows.map((r) =>
      r.map((b) =>
        b.type === 'link'
          ? { label: b.label, url: b.url.trim() }
          : { label: b.label, callback: b.callback.trim() },
      ),
    ),
  });
}

export default function BroadcastEditorPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);
  const [selectedPlatform, setSelectedPlatform] = useState<BotPlatform | null>(null);
  const form = useBroadcastForm(selectedPlatform);
  const { draft, errors, valid, patch } = form;
  const { activeProfile, loading: profileLoading } = useProfile();

  const { data: bots, loading: botsLoading, error: botsError } = useAsync(
    () => api.get<StoreBot[]>('/api/v1/portal/bots'),
    [],
  );
  const {
    data: prefill,
    loading: prefillLoading,
    error: prefillError,
  } = useAsync(
    () => (id ? api.get<BroadcastDetail>(`/api/v1/portal/broadcasts/${id}`) : Promise.resolve(null)),
    [id],
  );

  const [chats, setChats] = useState<BroadcastChat[] | null>(null);
  const [chatsError, setChatsError] = useState<unknown>(null);
  const [submitError, setSubmitError] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);
  const [feedback, setFeedback] = useState<string | null>(null);

  const selectedBot = useMemo(
    () => (bots || []).find((b) => b.id === draft.botId) || null,
    [bots, draft.botId],
  );

  // Mantém a plataforma (markup/preview/limites) sincronizada com o bot.
  useEffect(() => {
    if (selectedBot) setSelectedPlatform(selectedBot.platform);
  }, [selectedBot]);

  // Carrega os chats do bot selecionado (destino dinâmico — Spec 79/D5).
  useEffect(() => {
    if (!draft.botId) {
      setChats(null);
      setChatsError(null);
      return;
    }
    let cancelled = false;
    setChats(null);
    setChatsError(null);
    api
      .get<BroadcastChat[]>(`/api/v1/portal/bots/${draft.botId}/chats`)
      .then((list) => {
        if (!cancelled) setChats(list);
      })
      .catch((err) => {
        if (!cancelled) setChatsError(err);
      });
    return () => {
      cancelled = true;
    };
  }, [draft.botId]);

  // Pré-preenchimento na rota /broadcasts/:id/edit (duplicar).
  const initializedRef = useRef(false);
  useEffect(() => {
    if (initializedRef.current) return;
    if (prefillLoading || botsLoading) return;
    initializedRef.current = true;
    if (!prefill) return;
    patch({
      botId: prefill.storeBotId,
      targetChatId: prefill.targetChatId || '',
      mode: prefill.mode,
      content: prefill.content || '',
      media: prefill.mediaType
        ? {
            mediaUrl: prefill.mediaUrl,
            mediaFileId: prefill.mediaFileId,
            mediaType: prefill.mediaType,
          }
        : null,
      buttons: parseButtonsJson(prefill.buttonsJson),
      scheduledAt: prefill.scheduledAt ? toDatetimeLocal(prefill.scheduledAt) : '',
      intervalSeconds: prefill.intervalSeconds,
    });
    const bot = (bots || []).find((b) => b.id === prefill.storeBotId);
    if (bot) setSelectedPlatform(bot.platform);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- roda 1× na inicialização
  }, [prefill, prefillLoading, botsLoading, bots, patch]);

  // Specs 122/123: pré-seleção do bot do PERFIL ATIVO na rota /broadcasts/new
  // (escopo de dados — fase 1: pré-seleciona, não esconde). Só quando não há
  // bot escolhido (draft novo) e o perfil tem frota.
  const preSelectedRef = useRef(false);
  useEffect(() => {
    if (id || preSelectedRef.current) return; // só na rota "novo"
    if (botsLoading || profileLoading) return; // aguarda bots + perfil
    preSelectedRef.current = true;
    if (!draft.botId && activeProfile?.bots?.length) {
      patch({ botId: activeProfile.bots[0].id });
      setSelectedPlatform(activeProfile.bots[0].platform);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- roda 1× na inicialização
  }, [id, botsLoading, profileLoading, activeProfile, draft.botId, patch]);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!valid || submitting) return;
    setSubmitError(null);
    setSubmitting(true);
    try {
      await api.post('/api/v1/portal/broadcasts', {
        storeBotId: draft.botId,
        mode: draft.mode,
        targetChatId: draft.mode === 'WELCOME' ? undefined : draft.targetChatId,
        content: draft.content.trim() || undefined,
        mediaUrl: draft.media?.mediaUrl || undefined,
        mediaType: draft.media?.mediaType || undefined,
        mediaFileId: draft.media?.mediaFileId || undefined,
        buttonsJson: buildButtonsJson(draft.buttons),
        scheduledAt:
          draft.mode === 'SCHEDULED' ? new Date(draft.scheduledAt).toISOString() : undefined,
        intervalSeconds: draft.mode === 'REPOST' ? draft.intervalSeconds : undefined,
      });
      setFeedback('Transmissão enviada!');
      navigate('/broadcasts', { state: { created: true } });
    } catch (err) {
      setSubmitError(err);
    } finally {
      setSubmitting(false);
    }
  };

  const targetTitle =
    draft.mode === 'WELCOME'
      ? null
      : chats?.find((c) => c.id === draft.targetChatId)?.title || null;

  return (
    <div className="page">
      <div className="bots-toolbar">
        <h1 style={{ margin: 0 }}>{id ? 'Duplicar transmissão' : 'Nova transmissão'}</h1>
        <Link to="/broadcasts" className="btn">
          Voltar
        </Link>
      </div>

      <SuccessBanner message={feedback} />
      <ErrorBanner error={submitError} />
      <ErrorBanner error={prefillError} />

      {botsLoading || prefillLoading ? (
        <Spinner />
      ) : botsError ? (
        <ErrorBanner error={botsError} />
      ) : (
        <form className="bc-editor" onSubmit={(e) => void handleSubmit(e)}>
          <div className="bc-editor-main">
            <label className="field">
              <span className="field-label">Bot</span>
              <select
                className="input"
                aria-label="Bot"
                value={draft.botId}
                onChange={(e) => {
                  const bot = (bots || []).find((b) => b.id === e.target.value);
                  patch({ botId: e.target.value, targetChatId: '' });
                  if (bot) setSelectedPlatform(bot.platform);
                }}
              >
                <option value="">Selecione o bot…</option>
                {(bots || []).map((b) => (
                  <option key={b.id} value={b.id}>
                    {b.displayName} · {b.platform === 'TELEGRAM' ? 'Telegram' : 'Discord'}
                    {b.status !== 'ACTIVE' ? ` (${b.status.toLowerCase()})` : ''}
                  </option>
                ))}
              </select>
              {errors.bot && (
                <span className="field-error" role="alert">
                  {errors.bot}
                </span>
              )}
            </label>

            {draft.botId && draft.mode !== 'WELCOME' ? (
              <label className="field">
                <span className="field-label">Destino</span>
                {chatsError ? (
                  <span className="field-error" role="alert">
                    Não foi possível carregar os destinos do bot. Tente novamente.
                  </span>
                ) : chats === null ? (
                  <Spinner />
                ) : (
                  <select
                    className="input"
                    aria-label="Destino"
                    value={draft.targetChatId}
                    onChange={(e) => patch({ targetChatId: e.target.value })}
                  >
                    <option value="">Selecione o destino…</option>
                    {chats.map((c) => (
                      <option key={c.id} value={c.id}>
                        {c.title} ({c.type})
                      </option>
                    ))}
                  </select>
                )}
                {errors.target && (
                  <span className="field-error" role="alert">
                    {errors.target}
                  </span>
                )}
              </label>
            ) : (
              draft.botId && (
                <div className="muted">
                  O modo Bem-vindos dispara no evento de entrada do membro (sem destino fixo).
                </div>
              )
            )}

            <ModeBar
              mode={draft.mode}
              onChange={(mode) => patch({ mode })}
              scheduledAt={draft.scheduledAt}
              onScheduledAt={(v) => patch({ scheduledAt: v })}
              intervalSeconds={draft.intervalSeconds}
              onInterval={(seconds) => patch({ intervalSeconds: seconds })}
              errors={errors}
            />

            <RichTextToolbar
              platform={selectedPlatform || 'TELEGRAM'}
              textareaRef={textareaRef}
              onApply={(value) => patch({ content: value })}
            />
            <RichTextArea
              textareaRef={textareaRef}
              value={draft.content}
              onChange={(value) => patch({ content: value })}
              disabled={!selectedBot}
            />
            {errors.content && (
              <span className="field-error" role="alert">
                {errors.content}
              </span>
            )}

            <TagInserter
              onInsert={(tag) => {
                const el = textareaRef.current;
                if (el) patch({ content: insertAtCursor(el, tag) });
              }}
            />

            <MediaUploader
              botId={draft.botId}
              media={draft.media}
              onUploaded={(media) => patch({ media })}
              onRemoved={() => patch({ media: null })}
            />

            <ButtonsGridEditor
              platform={selectedPlatform}
              rows={draft.buttons}
              onChange={(rows) => patch({ buttons: rows })}
              error={errors.buttons}
            />

            <div className="bc-errors">
              {errors.bot && (
                <span className="field-error" role="alert">
                  {errors.bot}
                </span>
              )}
              {errors.target && (
                <span className="field-error" role="alert">
                  {errors.target}
                </span>
              )}
              {errors.content && (
                <span className="field-error" role="alert">
                  {errors.content}
                </span>
              )}
            </div>

            <div className="modal-actions">
              <Link to="/broadcasts" className="btn">
                Cancelar
              </Link>
              <button type="submit" className="btn btn-primary" disabled={!valid || submitting}>
                {submitting ? 'Enviando…' : SUBMIT_LABEL[draft.mode]}
              </button>
            </div>
          </div>

          <BroadcastPreview
            platform={selectedPlatform}
            bot={selectedBot}
            targetTitle={targetTitle}
            content={draft.content}
            media={draft.media}
            buttons={draft.buttons}
            mode={draft.mode}
          />
        </form>
      )}
    </div>
  );
}
