// @ts-check
// CRM — central de atendimento estilo Telegram (Spec 84):
//   - cards de resumo (GET /api/v1/portal/crm/summary)
//   - sidebar de conversas com busca/filtros + badge de status + não lida
//   - abrir conversa → histórico persistido + marcar lida
//   - envio do painel (Telegram mockado) → mensagem ADMIN no histórico
//   - lead BLOQUEADO → envio desabilitado
const { test, expect } = require('@playwright/test');
const { Client } = require('pg');
const http = require('http');
const nodeCrypto = require('crypto');

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

test.describe.serial('Portal — CRM painel (Spec 84)', () => {
  const ts = Date.now();
  const state = { portalHeaders: {}, botId: '', leadNovo: '', leadBloqueado: '' };

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
          res.end(JSON.stringify({ ok: true, result: { id: 987, is_bot: true, first_name: 'Bot CRM Painel', username: 'botcrmpainel' } }));
        } else if (req.url.endsWith('/sendMessage')) {
          // Envio do painel: ok (spec 84 — envio com banner de sucesso).
          res.end(JSON.stringify({ ok: true, result: { message_id: 99 } }));
        } else {
          res.end(JSON.stringify({ ok: true, result: true }));
        }
      });
    });
    await new Promise((resolve) => telegramServer.listen(TELEGRAM_MOCK_PORT, resolve));

    const email = `crm.e2e.${ts}@example.com`;
    const reg = await request.post('/api/v1/merchants', {
      data: { name: 'Loja CRM E2E', email, password: 'Senha-CRM-123' },
    });
    expect(reg.status()).toBe(201);
    const merchant = await reg.json();

    const login = await request.post('/api/v1/merchant-auth/login', { data: { email, password: 'Senha-CRM-123' } });
    expect(login.status()).toBe(200);
    state.portalHeaders = { Authorization: `Bearer ${(await login.json()).token}` };

    const bot = await request.post('/api/v1/portal/bots', {
      headers: state.portalHeaders,
      data: { platform: 'telegram', token: '123456:ABC-CRM-token', displayName: 'Bot CRM' },
    });
    expect(bot.status()).toBe(201);
    state.botId = (await bot.json()).id;

    // Leads diretos via SQL (Spec 84 — seeds determinísticos):
    // NOVO com 1 mensagem CLIENT não lida; BLOQUEADO com envio recusado.
    const now = new Date().toISOString();
    const mk = async (platformUserId, name, status) => {
      const rows = await dbQuery(
        `INSERT INTO bot_leads (id, bot_id, platform_user_id, first_seen_at, created_at,
             name, username, status, handoff_status)
         VALUES ($1, $2, $3, $4, $4, $5, $6, $7, 'BOT') RETURNING id`,
        [nodeCrypto.randomUUID(), state.botId, platformUserId, now, name, `@${platformUserId}`, status],
      );
      return rows[0].id;
    };
    state.leadNovo = await mk('u-novo', 'Cliente Novo', 'NOVO');
    state.leadBloqueado = await mk('u-block', 'Cliente Bloqueado', 'BLOQUEADO');

    await dbQuery(
      `INSERT INTO chat_messages (id, lead_id, bot_id, sender_type, text_content, is_read, created_at)
       VALUES ($1, $2, $3, 'CLIENT', 'Preciso de ajuda', false, $4)`,
      [nodeCrypto.randomUUID(), state.leadNovo, state.botId, now],
    );
  });

  test.afterAll(async () => {
    await new Promise((resolve) => telegramServer && telegramServer.close(resolve));
  });

  async function openCrm(page) {
    await page.goto('/login');
    await page.getByLabel('E-mail').fill(`crm.e2e.${ts}@example.com`);
    await page.getByLabel('Senha').fill('Senha-CRM-123');
    await page.getByRole('button', { name: 'Entrar' }).click();
    await page.waitForURL('**/dashboard');
    await page.getByRole('link', { name: 'Clientes' }).click();
    await page.waitForURL('**/crm');
  }

  test('1. cards + lista de conversas com status, não lida e busca', async ({ page }) => {
    await openCrm(page);
    await expect(page.locator('h1')).toHaveText('Clientes');
    // Cards de resumo presentes (Total ≥ 2 leads seedados).
    await expect(page.locator('.summary-card')).toHaveCount(5);
    // Sidebar de conversas: leads seedados com badge de status + não lida (1).
    await expect(page.locator('.crm-chat-item').filter({ hasText: 'Cliente Novo' })).toContainText('Cliente Novo');
    await expect(page.locator('.crm-chat-item').filter({ hasText: 'Cliente Bloqueado' })).toContainText('Cliente Bloqueado');
    await expect(page.locator('.crm-chat-item').filter({ hasText: 'Cliente Bloqueado' })).toContainText('Bloqueado');
    await expect(page.locator('.crm-unread')).toHaveText('1');

    // Busca por nome filtra a lista de conversas.
    await page.getByLabel('Buscar clientes').fill('Cliente Novo');
    await expect(page.locator('.crm-chat-item').filter({ hasText: 'Cliente Novo' })).toContainText('Cliente Novo');
    await expect(page.locator('.crm-chat-item').filter({ hasText: 'Cliente Novo' })).not.toContainText('Cliente Bloqueado');
  });

  test('2. abrir conversa → histórico + marcar lida + envio ADMIN', async ({ page }) => {
    await openCrm(page);
    await page.locator('.crm-chat-item', { hasText: 'Cliente Novo' }).click();

    // Histórico persistido (mensagem CLIENT seedada) no painel direito.
    await expect(page.locator('.crm-inbox-chat')).toContainText('Preciso de ajuda');

    // Envio do painel (Telegram mock) → bolha ADMIN no histórico.
    await page.getByLabel('Mensagem').fill('Olá, como posso ajudar?');
    await page.getByRole('button', { name: 'Enviar' }).click();
    await expect(page.locator('.crm-inbox-chat')).toContainText('Olá, como posso ajudar?');

    // Marcar lida: a bolinha some ao abrir (POST /read no backend).
    await page.getByRole('button', { name: 'Fechar', exact: true }).click();
    await page.reload();
    await expect(page.locator('.crm-unread')).toHaveCount(0);
  });

  test('3. lead BLOQUEADO desabilita o envio (aviso)', async ({ page }) => {
    await openCrm(page);
    await page.locator('.crm-chat-item', { hasText: 'Cliente Bloqueado' }).click();

    await expect(page.locator('.crm-inbox-chat')).toContainText('envio desabilitado');
    await expect(page.getByRole('button', { name: 'Enviar' })).toHaveCount(0);
    await page.getByRole('button', { name: 'Fechar', exact: true }).click();
  });
});
