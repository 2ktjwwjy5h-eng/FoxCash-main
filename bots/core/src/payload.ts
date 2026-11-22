import type {
  DeliveryAction,
  DeliveryActionType,
  MessageEvent,
  StoreButton,
  StoreMessages,
} from './types';

/**
 * Interpretação de payloads do storefront (Specs 49/50/51) — espelha as chaves
 * validadas no backend (`StoreButtonPayloadCodec`/`PlanActionPayloadCodec`).
 * Payloads chegam via API já decifrados (criptografados em repouso no backend);
 * aqui eles são **tipados por ação** para a plataforma renderizar/executar.
 * Nenhum valor de payload é logado.
 */

/** Variáveis de template nas mensagens (Spec 51): `{nome}`, `{plano}`. */
export interface MessageVars {
  nome?: string;
  plano?: string;
}

/** Substitui `{nome}`/`{plano}` no texto (variáveis ausentes → string vazia). */
export function renderTemplate(text: string, vars: MessageVars): string {
  return text
    .replace(/\{nome\}/g, vars.nome ?? '')
    .replace(/\{plano\}/g, vars.plano ?? '');
}

/** Mensagem da etapa renderizada (Spec 51) — undefined se a loja não definiu. */
export function renderMessage(
  messages: StoreMessages,
  event: MessageEvent,
  vars: MessageVars,
): string | undefined {
  const text = messages[event];
  return text ? renderTemplate(text, vars) : undefined;
}

// ---------------------------------------------------------------------------
// Botões do menu (Spec 50) — payload tipado por ação
// ---------------------------------------------------------------------------

export type ButtonView =
  | { action: 'BUY' | 'CLOSE'; payload: null }
  | { action: 'PREVIEW'; mediaUrls: string[] }
  | { action: 'FAQ'; text: string }
  | { action: 'SOCIAL' | 'LINK'; url: string; label?: string };

/**
 * Interpreta o payload do botão conforme a ação. Devolve null se o payload
 * não bater com o contrato (defensivo — o backend valida na escrita; o bot
 * nunca loga o valor). BUY/CLOSE não têm payload.
 */
export function interpretButton(button: StoreButton): ButtonView | null {
  switch (button.action) {
    case 'BUY':
    case 'CLOSE':
      return { action: button.action, payload: null };
    case 'PREVIEW': {
      const urls = asStringArray(button.payload?.['mediaUrls']);
      return urls && urls.length > 0 ? { action: 'PREVIEW', mediaUrls: urls } : null;
    }
    case 'FAQ': {
      const text = asString(button.payload?.['text']);
      return text ? { action: 'FAQ', text } : null;
    }
    case 'SOCIAL':
    case 'LINK': {
      const url = asString(button.payload?.['url']);
      if (!url) {
        return null;
      }
      const label = asString(button.payload?.['label']);
      return { action: button.action, url, ...(label ? { label } : {}) };
    }
  }
}

// ---------------------------------------------------------------------------
// Ações de entrega (Spec 49) — payload tipado por tipo
// ---------------------------------------------------------------------------

export type ActionPayloadView =
  | { actionType: 'GRANT_ROLE'; roleId: string }
  | { actionType: 'SEND_MESSAGE'; text: string }
  | { actionType: 'SEND_LINK'; url: string; label?: string }
  | { actionType: 'ADD_TO_GROUP'; groupId: string }
  | { actionType: 'SEND_FILE'; fileUrl: string };

/**
 * Interpreta o payload da ação de entrega conforme o tipo (chaves do
 * `PlanActionPayloadCodec`). Devolve null se faltar a chave obrigatória —
 * o executor trata como falha `invalid_payload` (nunca loga o payload).
 */
export function interpretAction(action: DeliveryAction): ActionPayloadView | null {
  const p = action.payload ?? {};
  switch (action.actionType) {
    case 'GRANT_ROLE': {
      const roleId = asString(p['roleId']);
      return roleId ? { actionType: 'GRANT_ROLE', roleId } : null;
    }
    case 'SEND_MESSAGE': {
      const text = asString(p['text']);
      return text ? { actionType: 'SEND_MESSAGE', text } : null;
    }
    case 'SEND_LINK': {
      const url = asString(p['url']);
      if (!url) {
        return null;
      }
      const label = asString(p['label']);
      return { actionType: 'SEND_LINK', url, ...(label ? { label } : {}) };
    }
    case 'ADD_TO_GROUP': {
      const groupId = asString(p['groupId']);
      return groupId ? { actionType: 'ADD_TO_GROUP', groupId } : null;
    }
    case 'SEND_FILE': {
      const fileUrl = asString(p['fileUrl']);
      return fileUrl ? { actionType: 'SEND_FILE', fileUrl } : null;
    }
  }
}

/** Ação de entrega suportada por uma plataforma (Design §3: telegram não tem grant_role etc.). */
export type SupportedActionType = Extract<
  DeliveryActionType,
  'GRANT_ROLE' | 'SEND_MESSAGE' | 'SEND_LINK' | 'ADD_TO_GROUP' | 'SEND_FILE'
>;

// ---------------------------------------------------------------------------
// helpers (valores do JSON decifrado — sempre string quando presentes)
// ---------------------------------------------------------------------------

function asString(value: unknown): string | undefined {
  if (typeof value === 'string' && value.length > 0) {
    return value;
  }
  return undefined;
}

function asStringArray(value: unknown): string[] | undefined {
  if (!Array.isArray(value)) {
    return undefined;
  }
  const strings = value.map((v) => (typeof v === 'string' ? v : undefined));
  return strings.every((v): v is string => v !== undefined) ? strings : undefined;
}
