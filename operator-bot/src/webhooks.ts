import { WebhookClient } from 'discord.js';

/**
 * Roteamento por webhook (Spec 67) — o usuário mantém integrações no servidor
 * Discord, uma por categoria. A **anomalia** sai em `finPulse` (pulsação
 * financeira); os comandos de negócio/vendedores saem em `vendedores` (canal
 * Vendedores) — canais separados por pedido do usuário (2026-08-10).
 */
export type WebhookCategory =
  | 'cmd'          // comando e controle
  | 'ops'          // OPS
  | 'finPulse'     // pulsação financeira (anomalia)
  | 'summaries'    // resumos periódicos
  | 'health'       // saúde e sistema
  | 'security'     // segurança e auditoria
  | 'vendedores';  // informações dos vendedores (canal Vendedores)

/** Env var de cada categoria (integrations do servidor → Settings → Integrations). */
export const WEBHOOK_ENV: Record<WebhookCategory, string> = {
  cmd: 'WEBHOOK_CMD',
  ops: 'WEBHOOK_OPS',
  finPulse: 'WEBHOOK_FIN_PULSE',
  summaries: 'WEBHOOK_SUMMARIES',
  health: 'WEBHOOK_HEALTH',
  security: 'WEBHOOK_SECURITY',
  vendedores: 'WEBHOOK_VENDEDORES',
};

export const WEBHOOK_LABELS: Record<WebhookCategory, string> = {
  cmd: 'comando e controle',
  ops: 'OPS',
  finPulse: 'pulsação financeira',
  summaries: 'resumos periódicos',
  health: 'saúde e sistema',
  security: 'segurança e auditoria',
  vendedores: 'vendedores',
};

/**
 * Categoria de webhook de cada comando interativo (requirements §6 + ajuste do
 * usuário 2026-08-10: negócio/vendedores → canal Vendedores; `finPulse` fica
 * só p/ a anomalia). O dispatcher roteia a resposta para o webhook da categoria
 * quando configurado; sem webhook → reply no canal (fallback).
 */
export const COMMAND_CATEGORY: Record<string, WebhookCategory> = {
  // OPS
  deploy: 'ops',
  bloquear: 'ops',
  ratelimit: 'ops',
  log: 'ops',
  // vendedores (informações dos vendedores — canal Vendedores)
  vendas: 'vendedores',
  chargebacks: 'vendedores',
  lojistas: 'vendedores',
  receita: 'vendedores',
  top5: 'vendedores',
  ip: 'vendedores',
  vigiar: 'vendedores',
  // saúde e sistema
  saude: 'health',
  conexoes: 'health',
  outbox: 'health',
  jobs: 'health',
  latencia: 'health',
  // segurança e auditoria
  alertas: 'security',
  bloqueios: 'security',
  reconciliacao: 'security',
  // comando e controle
  ajuda: 'cmd',
  help: 'cmd',
};

/** URLs por categoria (apenas as configuradas). */
export type WebhookConfig = Partial<Record<WebhookCategory, string>>;

/**
 * Normaliza o domínio legado `discordapp.com` → `discord.com` (mesmo endpoint;
 * o regex do discord.js só aceita `discord.com`).
 */
export function normalizeWebhookUrl(url: string): string {
  return url.replace(/^https?:\/\/discordapp\.com\//i, 'https://discord.com/');
}

/** Carrega as URLs de webhook do ambiente — só as categorias preenchidas. */
export function loadWebhooks(env: NodeJS.ProcessEnv = process.env): WebhookConfig {
  const cfg: WebhookConfig = {};
  for (const [cat, key] of Object.entries(WEBHOOK_ENV) as [WebhookCategory, string][]) {
    const url = normalizeWebhookUrl(env[key]?.trim() ?? '');
    if (url) cfg[cat] = url;
  }
  return cfg;
}

export interface WebhookRouter {
  /** URL configurada da categoria (undefined = não configurada → fallback). */
  url(category: WebhookCategory): string | undefined;
  /** Envia para a categoria; true se entregue, false se não configurada/falhou. */
  send(category: WebhookCategory, content: string): Promise<boolean>;
}

export function createWebhookRouter(config: WebhookConfig = {}): WebhookRouter {
  const clients = new Map<WebhookCategory, WebhookClient>();
  for (const [cat, url] of Object.entries(config) as [WebhookCategory, string][]) {
    if (!url) continue;
    try {
      clients.set(cat, new WebhookClient({ url }));
    } catch (e) {
      // URL inválida não pode derrubar o bot (degradação graciosa).
      console.error(`webhook:${cat}: URL inválida ignorada:`, e instanceof Error ? e.message : e);
    }
  }
  return {
    url: (cat) => config[cat],
    async send(cat, content) {
      const c = clients.get(cat);
      if (!c) return false;
      try {
        const truncated = content.length > 1900 ? content.slice(0, 1900) + '…' : content;
        await c.send({ content: truncated });
        return true;
      } catch (e) {
        console.error(`webhook:${cat}:`, e instanceof Error ? e.message : e);
        return false;
      }
    },
  };
}
