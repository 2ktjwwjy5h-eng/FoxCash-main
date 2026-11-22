// @ts-check
// Handoff humano no CRM (Spec 90):
//   - webhook de lead em BOT → TELEGRAM_UPDATE publicado (outbox)
//   - pausar bot (PUT handoff HUMAN) → nova mensagem via webhook → persistida
//     como CLIENT no chat_messages + SEM novo TELEGRAM_UPDATE (funil abortado)
//   - auditoria LEAD_HANDOFF (before/after) no platform_activity_log
//   - retomar bot (BOT) → mensagem volta a publicar
const { test, expect } = require('@playwright/test');
const { Client } = require('pg');
const http = require('http');
const nodeCrypto = require('crypto');

const BASE = process.env.API_BASE_URL || 'http://localhost:8080';
const TELEGRAM_MOCK_PORT = Number(process.env.FOXCASH_TELEGRAM_MOCK_PORT || 9001);

function dbClient() {
  return new Client({
    host: process.env.DB_HOST || 'localhost',
    port: Number(process.env.DB_PORT || 5432),
    user: process.env.DB_USERNAME || 'nyxcash',
    password: process.env.DB_PASSWORD || 'nyxcash',
    database: process.env.DB_NAME || 'nyxcash',
  });
}

async function dbQuery(text, params) {
  const client = dbClient();
  await client.connect();
  try {
    const { rows } = await client.query(text, params);
    return rows;
  } finally {
    await client.end();
  }
}

test.describe.serial('CRM — Handoff humano (Spec 90)', () => {
  const ts = Date.now();
  const state = {
    merchantId: '', portalHeaders: {}, botId: '', webhookSecret: '', webhookToken: '',
    leadId: '', platformUserId: '7770001',
  };

  /** @type {import('http').Server} */
  let telegramServer;
  const telegramRequests = [];

  test.beforeAll(async ({ request }) => {
    telegramServer = http.createServer((req, res) => {
      let body = '';
      req.on('data', (c) => (body += c));
      req.on('end', () => {
        telegramRequests.push({ method: req.method, url: req.url, body });
        res.writeHead(200, { 'Content-Type': 'application/json' });
        if (req.url.endsWith('/getMe')) {
          res.end(JSON.stringify({ ok: true, result: { id: 321, is_bot: true, first_name: 'Bot CRM', username: 'botcrm' } }));
        } else {
          res.end(JSON.stringify({ ok: true, result: true }));
        }
      });
    });
    await new Promise((resolve) => telegramServer.listen(TELEGRAM_MOCK_PORT, resolve));

    // Lojista + login + bot Telegram ACTIVE (mock na 9001 — padrão bot-funnel).
    const email = `handoff.e2e.${ts}@example.com`;
    const reg = await request.post('/api/v1/merchants', {
      data: { name: 'Loja Handoff E2E', email, password: 'Senha-Handoff-123' },
    });
    expect(reg.status()).toBe(201);
    state.merchantId = (await reg.json()).id;

    const login = await request.post('/api/v1/merchant-auth/login', { data: { email, password: 'Senha-Handoff-123' } });
    expect(login.status()).toBe(200);
    state.portalHeaders = { Authorization: `Bearer ${(await login.json()).token}` };

    const bot = await request.post('/api/v1/portal/bots', {
      headers: state.portalHeaders,
      data: { platform: 'telegram', token: '123456:ABC-Handoff-token', displayName: 'Bot CRM' },
    });
    expect(bot.status()).toBe(201);
    state.botId = (await bot.json()).id;

    const setWebhook = telegramRequests.find((r) => r.url.endsWith('/setWebhook'));
    const urlMatch = setWebhook.body.match(/\/webhooks\/telegram\/([^"\/]+)\/([^"]+)/);
    state.webhookSecret = urlMatch[2];
    // Auditoria 2026-08-18: o header usa o secret_token INDEPENDENTE (corpo do
    // setWebhook), não o segredo da URL.
    state.webhookToken = JSON.parse(setWebhook.body).secret_token;

    // Lead conhecido (BOT) via webhook real (o caminho de produção — Spec 90 D4).
    const first = await request.post(`/webhooks/telegram/${state.botId}/${state.webhookSecret}`, {
      headers: { 'X-Telegram-Bot-Api-Secret-Token': state.webhookToken },
      data: {
        update_id: 1,
        message: { message_id: 1, from: { id: 7770001 }, chat: { id: 7770001, type: 'private' }, text: '/start' },
      },
    });
    expect(first.status()).toBe(204);
    const lead = await dbQuery('SELECT id FROM bot_leads WHERE bot_id = $1 AND platform_user_id = $2', [state.botId, state.platformUserId]);
    expect(lead.length).toBe(1);
    state.leadId = lead[0].id;
  });

  test.afterAll(async () => {
    await new Promise((resolve) => telegramServer && telegramServer.close(resolve));
  });

  const sendUpdate = (request, text) =>
    request.post(`/webhooks/telegram/${state.botId}/${state.webhookSecret}`, {
      headers: { 'X-Telegram-Bot-Api-Secret-Token': state.webhookToken },
      data: {
        update_id: nodeCrypto.randomInt(1000000),
        message: { message_id: nodeCrypto.randomInt(1000000), from: { id: 7770001 }, chat: { id: 7770001, type: 'private' }, text },
      },
    });

  const outboxCount = async () => {
    const rows = await dbQuery(
      "SELECT COUNT(*)::int AS n FROM notification_outbox WHERE payload LIKE '%TELEGRAM_UPDATE%' AND payload LIKE '%' || $1 || '%'",
      [state.botId],
    );
    return rows[0].n;
  };

  test('1. webhook de lead BOT → TELEGRAM_UPDATE publicado (funil ativo)', async ({ request }) => {
    const before = await outboxCount();

    const res = await sendUpdate(request, 'oi bot');
    expect(res.status()).toBe(204);

    expect(await outboxCount()).toBeGreaterThan(before);
  });

  test('2. pausar bot (HUMAN) → mensagem CLIENT no chat + SEM TELEGRAM_UPDATE + auditoria', async ({ request }) => {
    const pause = await request.put(`/api/v1/portal/crm/leads/${state.leadId}/handoff`, {
      headers: state.portalHeaders,
      data: { mode: 'HUMAN' },
    });
    expect(pause.status()).toBe(200);
    expect((await pause.json()).handoffStatus).toBe('HUMAN');

    const before = await outboxCount();
    const res = await sendUpdate(request, 'preciso de ajuda');
    expect(res.status()).toBe(204);

    // SEM novo TELEGRAM_UPDATE (funil abortado).
    expect(await outboxCount()).toBe(before);

    // Mensagem persistida como CLIENT.
    const msg = await dbQuery(
      'SELECT sender_type, text_content FROM chat_messages WHERE lead_id = $1 AND sender_type = \'CLIENT\' ORDER BY created_at DESC LIMIT 1',
      [state.leadId],
    );
    expect(msg.length).toBe(1);
    expect(msg[0].text_content).toBe('preciso de ajuda');

    // Auditoria LEAD_HANDOFF com before/after.
    const audit = await dbQuery(
      "SELECT action, details FROM platform_activity_log WHERE action = 'LEAD_HANDOFF' AND merchant_id = $1",
      [state.merchantId],
    );
    expect(audit.length).toBe(1);
    expect(audit[0].details).toContain('BOT');
    expect(audit[0].details).toContain('HUMAN');
  });

  test('3. retomar bot (BOT) → mensagem volta a publicar', async ({ request }) => {
    const resume = await request.put(`/api/v1/portal/crm/leads/${state.leadId}/handoff`, {
      headers: state.portalHeaders,
      data: { mode: 'BOT' },
    });
    expect(resume.status()).toBe(200);
    expect((await resume.json()).handoffStatus).toBe('BOT');

    const before = await outboxCount();
    const res = await sendUpdate(request, 'voltei');
    expect(res.status()).toBe(204);

    expect(await outboxCount()).toBeGreaterThan(before);
  });
});
