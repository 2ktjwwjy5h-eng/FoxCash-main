// Estado + validação do formulário do editor de broadcast (Spec 80/D4).
// Validação espelha o contrato do backend (Spec 79/D3): bot obrigatório;
// destino obrigatório exceto no modo WELCOME; conteúdo OU mídia (pelo menos
// um); agendamento futuro no SCHEDULED; intervalo >= 1h no REPOST; botões com
// limites por plataforma (Telegram <=100/<=8 por linha; Discord <=5) e
// exatamente um de url (link) ou callback (bot:comando) por botão.

import { useMemo, useState } from 'react';
import type { BotPlatform, BroadcastDraft, BroadcastMode, ButtonGrid } from './types';

export const EMPTY_DRAFT: BroadcastDraft = {
  botId: '',
  targetChatId: '',
  mode: 'NOW',
  content: '',
  media: null,
  buttons: [],
  scheduledAt: '',
  intervalSeconds: null,
};

export const MODE_LABELS: Record<BroadcastMode, string> = {
  NOW: 'Agora',
  SCHEDULED: 'Agendada',
  WELCOME: 'Bem-vindos',
  REPOST: 'Repost',
};

export const MIN_REPOST_INTERVAL_SECONDS = 3600;

export function isValidUrl(url: string): boolean {
  try {
    const u = new URL(url);
    return u.protocol === 'http:' || u.protocol === 'https:';
  } catch {
    return false;
  }
}

/** Valida a grade de botões conforme a plataforma do bot (D8). */
export function validateButtons(rows: ButtonGrid, platform: BotPlatform): string | null {
  if (rows.length === 0) return null;
  const total = rows.reduce((n, r) => n + r.length, 0);
  const maxTotal = platform === 'TELEGRAM' ? 100 : 5;
  const maxPerRow = platform === 'TELEGRAM' ? 8 : 5;
  if (total > maxTotal) {
    return `Máximo de ${maxTotal} botões nesta plataforma (${platform}).`;
  }
  for (const [i, row] of rows.entries()) {
    if (row.length > maxPerRow) {
      return `Linha ${i + 1} com mais de ${maxPerRow} botões (limite da plataforma).`;
    }
    for (const b of row) {
      if (!b.label.trim()) {
        return 'Todo botão precisa de um rótulo.';
      }
      if (b.type === 'link') {
        if (!isValidUrl(b.url.trim())) {
          return `URL inválida no botão "${b.label}".`;
        }
      } else if (!b.callback.trim().startsWith('bot:')) {
        return `Callback do botão "${b.label}" deve começar com "bot:".`;
      }
    }
  }
  return null;
}

export interface FormErrors {
  bot?: string;
  target?: string;
  content?: string;
  scheduledAt?: string;
  interval?: string;
  buttons?: string;
}

export function useBroadcastForm(platform: BotPlatform | null) {
  const [draft, setDraft] = useState<BroadcastDraft>(EMPTY_DRAFT);

  const errors = useMemo<FormErrors>(() => {
    const e: FormErrors = {};
    if (!draft.botId) {
      e.bot = 'Selecione um bot.';
    }
    if (draft.mode !== 'WELCOME' && !draft.targetChatId) {
      e.target = 'Selecione um destino.';
    }
    const hasContent = draft.content.trim().length > 0;
    if (!hasContent && !draft.media) {
      e.content = 'Informe uma mensagem ou envie uma mídia.';
    }
    if (draft.mode === 'SCHEDULED') {
      if (!draft.scheduledAt) {
        e.scheduledAt = 'Informe a data e hora do agendamento.';
      } else if (new Date(draft.scheduledAt).getTime() <= Date.now()) {
        e.scheduledAt = 'O agendamento precisa ser em um horário futuro.';
      }
    }
    if (draft.mode === 'REPOST' && (!draft.intervalSeconds || draft.intervalSeconds < MIN_REPOST_INTERVAL_SECONDS)) {
      e.interval = 'O intervalo mínimo do Repost é 1 hora.';
    }
    if (platform) {
      const btnError = validateButtons(draft.buttons, platform);
      if (btnError) e.buttons = btnError;
    }
    return e;
  }, [draft, platform]);

  const valid = Object.keys(errors).length === 0;

  const patch = (partial: Partial<BroadcastDraft>) => setDraft((d) => ({ ...d, ...partial }));
  const reset = () => setDraft(EMPTY_DRAFT);

  return { draft, errors, valid, patch, reset };
}
