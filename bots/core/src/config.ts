import 'dotenv/config';
import { z } from 'zod';

/**
 * Configuração tipada por env (Spec 52) — segredos (FOXCASH_API_KEY, WEBHOOK_SECRET,
 * tokens) apenas por variável de ambiente; o bot não aceita configuração por
 * código. Validação falha no boot (fail-fast) com mensagem clara.
 */
const schema = z.object({
  FOXCASH_BASE_URL: z.string().url().default('http://localhost:8080'),
  FOXCASH_API_KEY: z.string().min(1, 'FOXCASH_API_KEY e obrigatoria (Spec 12/24)'),
  WEBHOOK_SECRET: z.string().min(1, 'WEBHOOK_SECRET e obrigatorio (Spec 35)'),
  /** Spec 52 — segredo do webhook do Discord (separado do Telegram por porta). */
  WEBHOOK_SECRET_DISCORD: z.string().optional().default(''),
  WEBHOOK_PORT: z.coerce.number().int().positive().default(3000),
  /** Spec 52 — porta do receiver do Discord (separada do Telegram p/ coexistir). */
  WEBHOOK_PORT_DISCORD: z.coerce.number().int().positive().default(3000),
  DELIVERY_POLL_MS: z.coerce.number().int().positive().default(15_000),
  ADMIN_DISCORD_IDS: z.string().optional().default(''),
  ADMIN_TELEGRAM_IDS: z.string().optional().default(''),
  DISCORD_TOKEN: z.string().optional().default(''),
  GUILD_ID: z.string().optional().default(''),
  TELEGRAM_BOT_TOKEN: z.string().optional().default(''),
  LOG_LEVEL: z.enum(['debug', 'info', 'warn', 'error']).default('info'),
  /** Spec 150 — id do store_bot (atribuição da venda ao bot; vazio = sem botId). */
  FOXCASH_BOT_ID: z.string().optional().default(''),
  /** Spec 150 — botId do bot Discord (separado do Telegram; fallback p/ FOXCASH_BOT_ID). */
  FOXCASH_BOT_ID_DISCORD: z.string().optional().default(''),
  /** Spec 150/D7 — gateway usado pelo bot (hoje apenas `foxcash`). */
  FOXCASH_GATEWAY_TYPE: z.enum(['foxcash']).default('foxcash'),
});

export type Config = z.infer<typeof schema>;

/** Lista de IDs de admin (Discord/Telegram) a partir do env CSV. */
export function adminIds(csv: string): string[] {
  return csv
    .split(',')
    .map((id) => id.trim())
    .filter(Boolean);
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  return schema.parse(env);
}
