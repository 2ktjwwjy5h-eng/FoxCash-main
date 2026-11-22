// CRM — central de clientes e atendimento (Specs 83/84/90).
//
// Endpoints consumidos (Spec 83): summary (cards), leads (busca/filtros),
// leads/{id}/messages (histórico + envio), leads/{id}/read, leads/{id}/handoff
// (Spec 90), e o stream SSE do chat (fetch + ReadableStream — o EventSource
// nativo não envia o header Authorization do Bearer).
//
// Layout: central de atendimento estilo Telegram (2 painéis) — sidebar com a
// lista de conversas (busca, não lidas, paginação) + painel direito com
// cabeçalho de navegação (posição, pausar/retomar bot, som, fechar), histórico
// (bolhas por remetente + mídia + confirmação de leitura) e envio. O SSE é
// mantido no nível da página: mensagem do lead aberto vai para o painel, dos
// demais incrementa a não lida na lista.
//
// Padrão UX da Spec 73: useAsync sem blink, banners anunciáveis, spinner,
// empty states.

import { useCallback, useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api, getToken, ApiError } from '../../apiClient';
import { ErrorBanner, SuccessBanner, Spinner, useAsync } from '../../components/ui';

// ---- Tipos (espelham os contratos da Specs 83/90) ----

export type CrmLeadStatus = 'NOVO' | 'PENDENTE' | 'PAGO' | 'BLOQUEADO';
export type HandoffMode = 'BOT' | 'HUMAN';
export type ChatSender = 'CLIENT' | 'ADMIN' | 'BOT_AUTO';

export interface CrmLead {
  id: string;
  botId: string;
  platformUserId: string;
  name: string | null;
  username: string | null;
  email: string | null;
  phone: string | null;
  status: CrmLeadStatus;
  handoffStatus: HandoffMode;
  firstSeenAt: string;
  createdAt: string;
  unreadCount: number;
  // Spec 119 (aditivo): colunas Plano/Última interação/Fluxo.
  lastPlanName?: string | null;
  lastInteractionAt?: string | null;
  flowName?: string | null;
}

export interface ChatMessage {
  id: string;
  leadId: string;
  senderType: ChatSender;
  textContent: string | null;
  mediaUrl: string | null;
  read: boolean;
  createdAt: string;
}

export interface CrmSummary {
  novos: number;
  pendentes: number;
  pagos: number;
  bloqueados: number;
  total: number;
}

interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

interface BotOption {
  id: string;
  displayName: string;
}

// ---- Helpers ----

const STATUS_LABEL: Record<CrmLeadStatus, string> = {
  NOVO: 'Novo',
  PENDENTE: 'Pendente',
  PAGO: 'Pago',
  BLOQUEADO: 'Bloqueado',
};

function StatusBadge({ status }: { status: CrmLeadStatus }) {
  return <span className={`status-badge status-${status.toLowerCase()}`}>{STATUS_LABEL[status]}</span>;
}

function SummaryCard({ label, value, className }: { label: string; value: number; className: string }) {
  return (
    <div className={`summary-card ${className}`}>
      <div className="summary-value">{value}</div>
      <div className="summary-label">{label}</div>
    </div>
  );
}

/** Iniciais para o avatar (nome, senão @username, senão o ID da plataforma). */
function initialsOf(lead: CrmLead): string {
  const base = (lead.name || lead.username?.replace(/^@/, '') || lead.platformUserId || '?').trim();
  const parts = base.split(/\s+/).filter(Boolean);
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

/** Data curta (dd/mm) ou hora (hoje) — padrão da lista de conversas. */
function chatDate(iso: string | null | undefined): string {
  if (!iso) return '';
  const d = new Date(iso);
  const sameDay = d.toDateString() === new Date().toDateString();
  if (sameDay) return d.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });
  return d.toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' });
}

/** Hora relativa pt-BR (Spec 119 D7.1 — "Última interação" na conversa). */
function relativeTime(iso: string): string {
  const then = new Date(iso).getTime();
  const diffSeconds = Math.round((then - Date.now()) / 1000);
  const rtf = new Intl.RelativeTimeFormat('pt-BR', { numeric: 'auto' });
  const abs = Math.abs(diffSeconds);
  if (abs < 60) return rtf.format(diffSeconds, 'second');
  if (abs < 3600) return rtf.format(Math.round(diffSeconds / 60), 'minute');
  if (abs < 86400) return rtf.format(Math.round(diffSeconds / 3600), 'hour');
  if (abs < 604800) return rtf.format(Math.round(diffSeconds / 86400), 'day');
  return new Date(iso).toLocaleDateString('pt-BR');
}

/** Período → from/to ISO (Hoje/Ontem/Semana/Mês/Tudo). */
function periodRange(period: string): { from?: string; to?: string } {
  if (period === 'today') {
    const d = new Date();
    d.setHours(0, 0, 0, 0);
    return { from: d.toISOString() };
  }
  if (period === 'yesterday') {
    const d = new Date();
    d.setDate(d.getDate() - 1);
    d.setHours(0, 0, 0, 0);
    const end = new Date(d);
    end.setHours(23, 59, 59, 999);
    return { from: d.toISOString(), to: end.toISOString() };
  }
  if (period === 'week') {
    const d = new Date();
    d.setDate(d.getDate() - 7);
    return { from: d.toISOString() };
  }
  if (period === 'month') {
    const d = new Date();
    d.setMonth(d.getMonth() - 1);
    return { from: d.toISOString() };
  }
  return {};
}

// ---- Hook do stream SSE (fetch + ReadableStream — Bearer no header) ----

interface StreamEvent {
  type: string;
  leadId: string;
  message: ChatMessage;
}

function useCrmStream(onEvent: (event: StreamEvent) => void) {
  const onEventRef = useRef(onEvent);
  onEventRef.current = onEvent;

  useEffect(() => {
    let cancelled = false;
    let retryTimer: number | undefined;
    let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;

    const connect = async () => {
      try {
        const response = await fetch('/api/v1/portal/crm/stream', {
          headers: { Authorization: `Bearer ${getToken() ?? ''}` },
        });
        if (cancelled || !response.ok || !response.body) return;
        reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        for (;;) {
          const { done, value } = await reader.read();
          if (done || cancelled) break;
          buffer += decoder.decode(value, { stream: true });
          // SSE: eventos separados por linha em branco; data: JSON do ChatEvent.
          let sep: number;
          while ((sep = buffer.indexOf('\n\n')) >= 0) {
            const block = buffer.slice(0, sep);
            buffer = buffer.slice(sep + 2);
            const dataLine = block.split('\n').find((l) => l.startsWith('data:'));
            if (dataLine) {
              try {
                const parsed = JSON.parse(dataLine.slice(5).trim()) as StreamEvent;
                onEventRef.current(parsed);
              } catch {
                /* evento inválido — ignora */
              }
            }
          }
        }
      } catch {
        // Reconexão com backoff simples (o EventSource nativo reconectaria).
      } finally {
        if (!cancelled) {
          retryTimer = window.setTimeout(connect, 3000);
        }
      }
    };

    void connect();
    return () => {
      cancelled = true;
      if (reader) void reader.cancel();
      if (retryTimer) window.clearTimeout(retryTimer);
    };
  }, []);
}

// ---- Item da lista de conversas (sidebar) ----

function ChatItem({
  chat,
  selected,
  onSelect,
  botName,
}: {
  chat: CrmLead;
  selected: boolean;
  onSelect: (chat: CrmLead) => void;
  botName: (id: string) => string;
}) {
  const name = chat.name || `@${chat.username}` || chat.platformUserId;
  const sub = chat.username ? `@${chat.username}` : chat.phone || chat.platformUserId;
  const classes = [
    'crm-chat-item',
    selected ? 'selected' : '',
    chat.unreadCount > 0 ? 'unread' : '',
    chat.handoffStatus === 'HUMAN' ? 'human' : '',
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <div
      className={classes}
      role="button"
      tabIndex={0}
      onClick={() => onSelect(chat)}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onSelect(chat);
        }
      }}
      aria-label={`Abrir conversa com ${name}`}
    >
      <div className="crm-chat-avatar">{initialsOf(chat)}</div>
      <div className="crm-chat-item-body">
        <div className="crm-chat-item-top">
          <span className="crm-chat-item-name">{name}</span>
          <span className="crm-chat-item-date">{chatDate(chat.lastInteractionAt)}</span>
        </div>
        <span className="crm-chat-item-sub">{sub}</span>
        <span className="crm-chat-item-bot">🤖 {botName(chat.botId)}</span>
      </div>
      <div className="crm-chat-item-meta">
        {chat.unreadCount > 0 && <span className="crm-unread">{chat.unreadCount}</span>}
        <StatusBadge status={chat.status} />
      </div>
    </div>
  );
}

// ---- Painel direito: conversa ativa ----

function ChatPanel({
  lead,
  position,
  total,
  onPrev,
  onNext,
  onClose,
  onHandoffChanged,
  onLeadUpdated,
  onRegisterAppend,
  botName,
}: {
  lead: CrmLead;
  position: number;
  total: number;
  onPrev: () => void;
  onNext: () => void;
  onClose: () => void;
  onHandoffChanged: (lead: CrmLead) => void;
  onLeadUpdated: (id: string, updater: (lead: CrmLead) => CrmLead) => void;
  onRegisterAppend: (cb: ((message: ChatMessage) => void) | null) => void;
  botName: (id: string) => string;
}) {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [loading, setLoading] = useState(true);
  const [draft, setDraft] = useState('');
  const [sending, setSending] = useState(false);
  const [handoffBusy, setHandoffBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [handoff, setHandoff] = useState<HandoffMode>(lead.handoffStatus);
  const [current, setCurrent] = useState<CrmLead>(lead);
  const [muted, setMuted] = useState(() => localStorage.getItem('foxcash.crm.sound.muted') === '1');

  const bodyRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const audioRef = useRef<AudioContext | null>(null);
  const handoffRef = useRef(handoff);
  handoffRef.current = handoff;
  const currentRef = useRef(current);
  currentRef.current = current;
  const mutedRef = useRef(muted);
  mutedRef.current = muted;

  // Sinal sonoro leve ao receber mensagem do lead aberto (Spec 84 C6/D5) —
  // Web Audio (oscilador curto ~500ms, volume baixo); o AudioContext só é
  // criado no primeiro gesto do usuário (sem autoplay problemático). Mute
  // persistido em localStorage.
  const playBeep = useCallback(() => {
    if (mutedRef.current) return;
    try {
      const Ctx = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
      if (!Ctx) return;
      audioRef.current ??= new Ctx();
      const ctx = audioRef.current;
      if (ctx.state === 'suspended') void ctx.resume();
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = 'sine';
      osc.frequency.value = 880;
      gain.gain.setValueAtTime(0.08, ctx.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + 0.5);
      osc.connect(gain).connect(ctx.destination);
      osc.start();
      osc.stop(ctx.currentTime + 0.5);
    } catch {
      /* áudio indisponível — o indicador visual cobre */
    }
  }, []);

  const playBeepRef = useRef(playBeep);
  playBeepRef.current = playBeep;

  const toggleMute = useCallback(() => {
    setMuted((m) => {
      const next = !m;
      localStorage.setItem('foxcash.crm.sound.muted', next ? '1' : '0');
      return next;
    });
  }, []);

  // Abrir conversa: reseta o estado, carrega o histórico, marca lida (Spec 84
  // D4) e foca o campo de envio.
  useEffect(() => {
    let cancelled = false;
    setCurrent(lead);
    setHandoff(lead.handoffStatus);
    setError(null);
    setSuccess(null);
    setDraft('');
    setMessages([]);
    setLoading(true);
    api
      .get<Page<ChatMessage>>(`/api/v1/portal/crm/leads/${lead.id}/messages?size=100`)
      .then((page) => {
        if (cancelled) return;
        setMessages(page.items);
        void api.post(`/api/v1/portal/crm/leads/${lead.id}/read`).catch(() => undefined);
        onLeadUpdated(lead.id, (l) => ({ ...l, unreadCount: 0 }));
      })
      .catch((err) => {
        if (!cancelled) setError(err);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    inputRef.current?.focus();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reload só ao trocar de lead
  }, [lead.id]);

  // Scroll no fim quando há mensagens novas.
  useEffect(() => {
    bodyRef.current?.scrollTo({ top: bodyRef.current.scrollHeight });
  }, [messages.length]);

  // Registra o append do SSE no nível da página (mensagem do lead aberto).
  const handleLiveMessage = useCallback((m: ChatMessage) => {
    setMessages((prev) => (prev.some((x) => x.id === m.id) ? prev : [...prev, m]));
    if (m.senderType === 'CLIENT') playBeepRef.current();
  }, []);

  useEffect(() => {
    onRegisterAppend(handleLiveMessage);
    return () => onRegisterAppend(null);
  }, [onRegisterAppend, handleLiveMessage]);

  const send = async () => {
    const text = draft.trim();
    if (!text || sending) return;
    setSending(true);
    setError(null);
    setSuccess(null);
    try {
      const sent = await api.post<ChatMessage>(`/api/v1/portal/crm/leads/${currentRef.current.id}/messages`, { text });
      setMessages((prev) => [...prev, sent]);
      setDraft('');
    } catch (err) {
      setError(err);
      // lead_blocked → atualiza o badge na lista (Spec 84 D4).
      if (err instanceof ApiError && err.code === 'lead_blocked') {
        onLeadUpdated(currentRef.current.id, (l) => ({ ...l, status: 'BLOQUEADO' }));
        setCurrent((c) => ({ ...c, status: 'BLOQUEADO' }));
      }
    } finally {
      setSending(false);
    }
  };

  const toggleHandoff = async () => {
    const next: HandoffMode = handoffRef.current === 'HUMAN' ? 'BOT' : 'HUMAN';
    setHandoffBusy(true);
    setError(null);
    setSuccess(null);
    try {
      const updated = await api.put<CrmLead>(`/api/v1/portal/crm/leads/${currentRef.current.id}/handoff`, { mode: next });
      setHandoff(updated.handoffStatus);
      setCurrent((c) => ({ ...c, handoffStatus: updated.handoffStatus }));
      onHandoffChanged(updated);
      setSuccess(updated.handoffStatus === 'HUMAN' ? 'Atendimento manual — bot pausado para este cliente' : 'Bot retomado');
    } catch (err) {
      setError(err);
    } finally {
      setHandoffBusy(false);
    }
  };

  const blocked = current.status === 'BLOQUEADO';
  const name = current.name || `@${current.username}` || current.platformUserId;

  return (
    <>
      <div className="crm-panel-header">
        <div className="crm-panel-header-info">
          <div className="crm-chat-avatar crm-chat-avatar-lg">{initialsOf(current)}</div>
          <div style={{ minWidth: 0 }}>
            <div className="crm-panel-header-name">{name}</div>
            <div className="crm-panel-header-sub">
              <span>
                ID: {current.platformUserId} · via {botName(current.botId)}
              </span>
              {current.lastPlanName && <span>· Plano: {current.lastPlanName}</span>}
              {current.lastInteractionAt && <span>· Últ. interação: {relativeTime(current.lastInteractionAt)}</span>}
              {handoff === 'HUMAN' && <span className="crm-handoff-badge">Atendimento manual — bot pausado</span>}
            </div>
          </div>
        </div>
        <div className="crm-panel-header-actions">
          <StatusBadge status={current.status} />
          <button type="button" className="btn btn-small" onClick={toggleHandoff} disabled={handoffBusy} aria-label={handoff === 'HUMAN' ? 'Retomar bot' : 'Pausar bot'}>
            {handoff === 'HUMAN' ? 'Retomar bot' : 'Pausar bot'}
          </button>
          <button type="button" className="btn btn-small btn-ghost" onClick={toggleMute} aria-label={muted ? 'Ativar som de novas mensagens' : 'Silenciar som de novas mensagens'} aria-pressed={muted}>
            {muted ? 'Som desligado' : 'Som ligado'}
          </button>
          <button type="button" className="crm-nav-btn" onClick={onPrev} aria-label="Conversa anterior" title="Conversa anterior">
            ‹
          </button>
          <span className="crm-nav-pos">
            {position}/{total}
          </span>
          <button type="button" className="crm-nav-btn" onClick={onNext} aria-label="Próxima conversa" title="Próxima conversa">
            ›
          </button>
          <button type="button" className="crm-icon-btn" onClick={onClose} aria-label="Fechar" title="Fechar conversa">
            ✕
          </button>
        </div>
      </div>

      <ErrorBanner error={error} />
      <SuccessBanner message={success} />

      <div className="crm-chat-history" ref={bodyRef} role="log" aria-label="Histórico de mensagens">
        {loading ? (
          <Spinner />
        ) : messages.length === 0 ? (
          <div className="crm-chat-empty">Nenhuma mensagem ainda — o histórico aparece aqui.</div>
        ) : (
          messages.map((m) => (
            <div key={m.id} className={`chat-bubble chat-${m.senderType.toLowerCase()}`}>
              {m.senderType === 'BOT_AUTO' && <div className="chat-bubble-sender">Bot</div>}
              {m.mediaUrl && <img src={m.mediaUrl} alt="mídia recebida" className="chat-media" />}
              {m.textContent && <div>{m.textContent}</div>}
              <div className="chat-bubble-time">
                {new Date(m.createdAt).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' })}
                {m.senderType === 'ADMIN' && (
                  <span className="chat-read-mark" aria-label={m.read ? 'lida' : 'enviada'}>
                    {m.read ? '✓✓' : '✓'}
                  </span>
                )}
              </div>
            </div>
          ))
        )}
      </div>

      <div className="crm-panel-footer">
        {blocked ? (
          <div className="alert alert-warning" role="alert" style={{ flex: 1 }}>
            Lead bloqueou o bot — envio desabilitado.
          </div>
        ) : (
          <>
            <input
              className="input"
              placeholder="Mensagem"
              aria-label="Mensagem"
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  void send();
                }
              }}
              ref={inputRef}
            />
            <button type="button" className="btn btn-primary" onClick={send} disabled={sending || !draft.trim()}>
              {sending ? 'Enviando…' : 'Enviar'}
            </button>
          </>
        )}
      </div>
    </>
  );
}

// ---- Página (Spec 84) ----

export default function CrmPage() {
  const [q, setQ] = useState('');
  const [qDebounced, setQDebounced] = useState('');
  const [botId, setBotId] = useState('');
  const [status, setStatus] = useState('');
  const [period, setPeriod] = useState('');
  const [page, setPage] = useState(0);
  const [leads, setLeads] = useState<CrmLead[]>([]);
  const [selectedLead, setSelectedLead] = useState<CrmLead | null>(null);
  const [searchParams, setSearchParams] = useSearchParams();

  // Spec 102 C2: o carrossel do header navega /crm?lead={id} — abre o Live
  // Chat do lead direto (e limpa o param para não reabrir no reload).
  useEffect(() => {
    const leadParam = searchParams.get('lead');
    if (!leadParam) return;
    api
      .get<CrmLead>(`/api/v1/portal/crm/leads/${leadParam}`)
      .then((lead) => {
        setSelectedLead(lead);
        setLeads((prev) => (prev.some((l) => l.id === lead.id) ? prev : [lead, ...prev]));
      })
      .catch(() => undefined)
      .finally(() => setSearchParams({}, { replace: true }));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams]);

  // Debounce da busca (Spec 84 D3 — recarga sem zerar a lista).
  useEffect(() => {
    const t = window.setTimeout(() => setQDebounced(q), 350);
    return () => window.clearTimeout(t);
  }, [q]);

  const { data: summary, reload: reloadSummary } = useAsync<CrmSummary>(
    () => api.get<CrmSummary>('/api/v1/portal/crm/summary'),
    [],
  );

  const { data: bots } = useAsync<BotOption[]>(() => api.get<BotOption[]>('/api/v1/portal/bots'), []);

  const range = periodRange(period);
  const query = useCallback(() => {
    const params = new URLSearchParams({ page: String(page), size: '20' });
    if (qDebounced) params.set('q', qDebounced);
    if (botId) params.set('botId', botId);
    if (status) params.set('status', status);
    if (range.from) params.set('from', range.from);
    if (range.to) params.set('to', range.to);
    return api.get<Page<CrmLead>>(`/api/v1/portal/crm/leads?${params.toString()}`);
  }, [qDebounced, botId, status, range.from, range.to, page]);

  const { data, error, loading } = useAsync<Page<CrmLead>>(query, [qDebounced, botId, status, range.from, range.to, page]);

  // Lista local = servidor + atualizações locais (não lidas, handoff, status).
  // Overrides locais são preservados entre refetches do mesmo lead.
  useEffect(() => {
    if (!data) return;
    setLeads((prev) => {
      const prevMap = new Map(prev.map((l) => [l.id, l]));
      return data.items.map((server) => prevMap.get(server.id) ?? server);
    });
  }, [data]);

  const updateLead = useCallback((id: string, updater: (l: CrmLead) => CrmLead) => {
    setLeads((prev) => prev.map((l) => (l.id === id ? updater(l) : l)));
    setSelectedLead((sel) => (sel?.id === id ? updater(sel) : sel));
  }, []);

  const openChat = useCallback(
    (chat: CrmLead) => {
      setSelectedLead(chat);
      updateLead(chat.id, (l) => ({ ...l, unreadCount: 0 }));
    },
    [updateLead],
  );

  const botName = (id: string) => bots?.find((b) => b.id === id)?.displayName || '…';

  // SSE no nível da página: mensagem do lead aberto → painel (append + beep);
  // dos demais leads → incrementa a não lida na lista.
  const selectedRef = useRef<CrmLead | null>(null);
  selectedRef.current = selectedLead;
  const appendRef = useRef<((m: ChatMessage) => void) | null>(null);
  const registerAppend = useCallback((cb: ((m: ChatMessage) => void) | null) => {
    appendRef.current = cb;
  }, []);

  useCrmStream((event) => {
    const selected = selectedRef.current;
    if (selected && event.leadId === selected.id) {
      appendRef.current?.(event.message);
    } else {
      updateLead(event.leadId, (l) => ({ ...l, unreadCount: l.unreadCount + 1 }));
    }
  });

  // Navegação ‹ › dentro da página atual da lista (volta ao fim se passar).
  const nav = (delta: number) => {
    if (!leads.length) return;
    const idx = leads.findIndex((l) => l.id === selectedLead?.id);
    const next = idx < 0 ? 0 : (idx + delta + leads.length) % leads.length;
    setSelectedLead(leads[next]);
  };

  const selectedIndex = leads.findIndex((l) => l.id === selectedLead?.id);
  const position = selectedIndex >= 0 ? page * (data?.size ?? 20) + selectedIndex + 1 : 0;
  const totalElements = data?.totalElements ?? 0;

  return (
    <section className="page">
      <h1>Clientes</h1>

      <ErrorBanner error={error} />

      <div className="summary-cards">
        <SummaryCard label="Novos" value={summary?.novos ?? 0} className="summary-novos" />
        <SummaryCard label="Pendentes" value={summary?.pendentes ?? 0} className="summary-pendentes" />
        <SummaryCard label="Pagos" value={summary?.pagos ?? 0} className="summary-pagos" />
        <SummaryCard label="Bloqueados" value={summary?.bloqueados ?? 0} className="summary-bloqueados" />
        <SummaryCard label="Total" value={summary?.total ?? 0} className="summary-total" />
      </div>

      <div className="crm-filters">
        <input
          className="input input-small"
          type="search"
          placeholder="Buscar por nome, @username, e-mail, telefone ou ID…"
          value={q}
          onChange={(e) => {
            setQ(e.target.value);
            setPage(0);
          }}
          aria-label="Buscar clientes"
        />
        <select className="input input-small" value={botId} onChange={(e) => { setBotId(e.target.value); setPage(0); }} aria-label="Filtrar por bot">
          <option value="">Todos os bots</option>
          {(bots ?? []).map((b) => (
            <option key={b.id} value={b.id}>{b.displayName}</option>
          ))}
        </select>
        <select className="input input-small" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }} aria-label="Filtrar por status">
          <option value="">Todos os status</option>
          <option value="NOVO">Novo</option>
          <option value="PENDENTE">Pendente</option>
          <option value="PAGO">Pago</option>
          <option value="BLOQUEADO">Bloqueado</option>
        </select>
        <select className="input input-small" value={period} onChange={(e) => { setPeriod(e.target.value); setPage(0); }} aria-label="Filtrar por período">
          <option value="">Todo o período</option>
          <option value="today">Hoje</option>
          <option value="yesterday">Ontem</option>
          <option value="week">Semana</option>
          <option value="month">Mês</option>
        </select>
      </div>

      <div className="crm-inbox">
        {/* ================= COLUNA ESQUERDA: LISTA DE CONVERSAS ================= */}
        <div className="crm-inbox-sidebar">
          <div className="crm-inbox-sidebar-head">
            <div>
              <span className="crm-inbox-sidebar-title">Chats</span>
              <span className="crm-inbox-count">{totalElements}</span>
            </div>
            <button type="button" className="crm-icon-btn" onClick={() => setSelectedLead(null)} aria-label="Fechar painel" title="Fechar painel">
              ✕
            </button>
          </div>

          <div className="crm-inbox-search">
            <input
              className="input"
              type="search"
              placeholder="Buscar..."
              value={q}
              onChange={(e) => {
                setQ(e.target.value);
                setPage(0);
              }}
              aria-label="Buscar conversas"
            />
          </div>

          <div className="crm-chat-list">
            {loading && leads.length === 0 ? (
              <Spinner />
            ) : leads.length === 0 ? (
              <div className="empty-state">Nenhum cliente ainda.</div>
            ) : (
              leads.map((chat) => (
                <ChatItem key={chat.id} chat={chat} selected={chat.id === selectedLead?.id} onSelect={openChat} botName={botName} />
              ))
            )}
          </div>

          {(data?.totalPages ?? 1) > 1 && (
            <div className="crm-inbox-sidebar-foot">
              <button type="button" className="btn btn-small" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                Anterior
              </button>
              <span className="muted">Página {page + 1} de {data?.totalPages}</span>
              <button type="button" className="btn btn-small" disabled={page >= (data?.totalPages ?? 1) - 1} onClick={() => setPage((p) => p + 1)}>
                Próxima
              </button>
            </div>
          )}
        </div>

        {/* ================= PAINEL DIREITO: CONVERSA ATIVA ================= */}
        <div className="crm-inbox-chat">
          {selectedLead ? (
            <ChatPanel
              lead={selectedLead}
              position={position}
              total={totalElements}
              onPrev={() => nav(-1)}
              onNext={() => nav(1)}
              onClose={() => setSelectedLead(null)}
              onHandoffChanged={(updated) => {
                updateLead(updated.id, () => updated);
                void reloadSummary();
              }}
              onLeadUpdated={updateLead}
              onRegisterAppend={registerAppend}
              botName={botName}
            />
          ) : (
            <div className="crm-chat-empty">Selecione uma conversa à esquerda para começar o atendimento.</div>
          )}
        </div>
      </div>
    </section>
  );
}
