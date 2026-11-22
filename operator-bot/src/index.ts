import 'dotenv/config'; // carrega .env local (cp .env.example .env)
import { Client, Events, GatewayIntentBits, type Message } from 'discord.js';
import { createPool, queries, type DbQueries } from './db.js';
import { railwayClient } from './railway.js';
import { cloudflareClient } from './cloudflare.js';
import { commands, fetchWithRetry, help, type CommandCtx } from './commands.js';
import { startSchedulers } from './scheduled.js';
import {
  COMMAND_CATEGORY,
  createWebhookRouter,
  loadWebhooks,
  type WebhookCategory,
  type WebhookRouter,
} from './webhooks.js';

const token = process.env.OPERATOR_DISCORD_TOKEN ?? '';
if (!token) {
  console.error('Falta OPERATOR_DISCORD_TOKEN (crie o bot no portal.developers.discord.com e set a env).');
  process.exit(1);
}

const databaseUrl = process.env.DATABASE_URL;
const pool = databaseUrl ? createPool(databaseUrl) : null;
const db: DbQueries = pool ? queries(pool) : null as unknown as DbQueries;

const ctx: CommandCtx = {
  db,
  railway: railwayClient(),
  cloudflare: process.env.CF_API_TOKEN
    ? cloudflareClient(process.env.CF_API_TOKEN, process.env.CF_ZONE_ID ?? '')
    : undefined,
  baseUrl: process.env.FOXCASH_BASE_URL ?? 'https://api.foxcash.example.com',
  adminApiKey: process.env.FOXCASH_ADMIN_API_KEY,
  config: { anomalyThreshold: Number(process.env.ANOMALY_THRESHOLD ?? 0.5) },
};

const client = new Client({ intents: [GatewayIntentBits.Guilds, GatewayIntentBits.GuildMessages, GatewayIntentBits.MessageContent] });
// Allowlist de canais onde o bot responde: OPERATOR_CHANNELS (IDs separados por
// vírgula) ou OPERATOR_CHANNEL_ID (um canal). Sem isso, só responde por DM.
const allowedChannels = (process.env.OPERATOR_CHANNELS ?? process.env.OPERATOR_CHANNEL_ID ?? '')
  .split(',').map((s) => s.trim()).filter(Boolean);

// Instância do roteador de webhooks — criada vazia e preenchida no ClientReady
// (os WebhookClient precisam das URLs das env; degradação graciosa sem elas).
let webhooks: WebhookRouter = createWebhookRouter();

const truncate = (s: string) => (s.length > 1900 ? s.slice(0, 1900) + '…' : s);

/** Responde um comando: webhook da categoria quando configurado, senão reply. */
async function respond(message: Message, cmd: string, out: string): Promise<void> {
  const category = COMMAND_CATEGORY[cmd];
  const targetUrl = category ? webhooks.url(category) : undefined;
  if (!targetUrl) {
    await message.reply(truncate(out)).catch(() => undefined);
    return;
  }
  const delivered = await webhooks.send(category, out);
  if (delivered) {
    // Resposta enviada ao canal da categoria (webhook) — sem reply no canal do
    // comando (o ack "✅ respondido..." era ruído — pedido do usuário 2026-08-10).
    return;
  }
  // Webhook falhou → degradação graciosa: responde no canal mesmo assim.
  await message.reply(truncate(out)).catch(() => undefined);
}

async function handle(message: Message): Promise<void> {
  const content = message.content.trim();
  if (!content.startsWith('!')) return;
  // ACL: DM direto ao bot OU canal da allowlist (ex.: saúde-sistema, ops, ...).
  const isDm = message.channel.isDMBased();
  if (!isDm && !allowedChannels.includes(message.channelId)) return;

  const [rawCmd, ...rest] = content.slice(1).split(/\s+/);
  const cmd = rawCmd.toLowerCase();
  const args = rest.join(' ');

  try {
    if (cmd === 'ajuda' || cmd === 'help') {
      await respond(message, cmd, help);
      return;
    }
    const handler = commands[cmd];
    if (!handler) {
      await message.reply(`Comando desconhecido. ${help.split('\n')[0]}`).catch(() => undefined);
      return;
    }
    const out = await handler(args, ctx);
    await respond(message, cmd, out);
  } catch (e) {
    const msg = (e as Error).message;
    await message.reply(`❌ Erro: ${msg.slice(0, 300)}`).catch(() => undefined);
  }
}

client.once(Events.ClientReady, async (c) => {
  console.log(`Operator Bot online: ${c.user.tag}`);
  webhooks = createWebhookRouter(loadWebhooks());
  // Self-check do backend no boot (não-bloqueante): loga a acessibilidade da API
  // — útil p/ detectar FOXCASH_BASE_URL errada ou rede privada indisponível.
  fetchWithRetry(`${ctx.baseUrl}/actuator/health`)
    .then((r) => r.json() as Promise<{ status?: string }>)
    .then((h) => console.log(`[api] baseUrl=${ctx.baseUrl} health=${h.status ?? '?'}`))
    .catch((e) => console.error(`[api] baseUrl=${ctx.baseUrl} INACESSÍVEL: ${e instanceof Error ? e.message : e}`));
  // Agendados saem pelo webhook da categoria. A **anomalia é estrita**: só a
  // pulsação financeira (channelFallback: false) — sem webhook, vira log, NUNCA
  // cai em outro canal de texto. Demais resumos: sem webhook → canal da
  // allowlist (comportamento original); sem canal, viram log.
  const send = async (text: string, category: WebhookCategory = 'summaries', opts?: { channelFallback?: boolean }) => {
    if (webhooks.url(category)) {
      await webhooks.send(category, text);
      return;
    }
    if (opts?.channelFallback === false) {
      console.log(`[scheduled:${category}] ${text.replace(/\n/g, ' | ')}`);
      return;
    }
    const fallbackChannel = allowedChannels[0];
    const target = fallbackChannel ? await c.channels.fetch(fallbackChannel) : undefined;
    if (target && target.isTextBased() && 'send' in target) {
      await (target as { send(t: string): Promise<unknown> }).send(text);
    } else {
      console.log(`[scheduled:${category}] ${text.replace(/\n/g, ' | ')}`);
    }
  };
  startSchedulers(ctx, { send });
});

client.on(Events.MessageCreate, (m) => {
  if (m.author.bot) return;
  handle(m).catch((e) => console.error('handle:', e));
});

client.login(token).catch((e) => {
  console.error('login:', e);
  process.exit(1);
});

// Graceful shutdown
process.on('SIGINT', async () => {
  await pool?.end().catch(() => undefined);
  client.destroy();
  process.exit(0);
});
