// @ts-check
// Auditoria de Broadcasts no Master Console (Spec 89):
//   - login admin (OPERATOR) → /console/broadcasts → lista cross-merchant
//   - drawer mostra conteúdo + métricas de envio (contagens + logs)
//   - "Cancelar Campanha" só no PENDING → POST force-cancel → FAILED no banco
//     + trilha master_audit_log BROADCAST_FORCE_CANCELLED com o operador
//   - botão ausente em PROCESSING/COMPLETED
const { test, expect } = require('@playwright/test');
const { Client } = require('pg');
const nodeCrypto = require('crypto');
const http = require('http');

const BASE = process.env.API_BASE_URL || 'http://localhost:8080';
// Hash bcrypt de 'Senha-E2E-123' (padrão dos demais specs master).
const PASSWORD = 'Senha-E2E-123';
const PASSWORD_BCRYPT = '$2a$10$15Pv6f0BRyiTgp.DrVUjwenfdnTaAh5uY1EGRfn0oso2M3GK9IIv6';
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

test.describe.serial('Master Console — Auditoria de Broadcasts (Spec 89)', () => {
  const ts = Date.now();
  const username = `bcast-${ts}`;
  const state = { merchantId: '', botId: '', broadcastPending: '', broadcastProcessing: '', broadcastCompleted: '' };

  /** @type {import('http').Server} */
  let telegramServer;

  test.beforeAll(async ({ request }) => {
    // Mock Telegram (getMe/setWebhook — registro do bot, padrão tracking.spec.js).
    telegramServer = http.createServer((req, res) => {
      let body = '';
      req.on('data', (c) => (body += c));
      req.on('end', () => {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        if (req.url.endsWith('/getMe')) {
          res.end(JSON.stringify({ ok: true, result: { id: 789, is_bot: true, first_name: 'Bot Broadcast', username: 'botbcast' } }));
        } else {
          res.end(JSON.stringify({ ok: true, result: true }));
        }
      });
    });
    await new Promise((resolve) => telegramServer.listen(TELEGRAM_MOCK_PORT, resolve));

    // Seed do master OPERATOR via SQL (padrão master-console-ui.spec.js).
    const client = dbClient();
    await client.connect();
    await client.query(
      `INSERT INTO masters (id, username, password_hash, name, active, can_manage_masters, created_at)
       VALUES ($1, $2, $3, $4, true, false, $5)`,
      [nodeCrypto.randomUUID(), username, PASSWORD_BCRYPT, 'Broadcast E2E', new Date().toISOString()],
    );

    // Lojista (cadastro público) + login → token para registrar o bot.
    const email = `bcast-${ts}@example.com`;
    const reg = await request.post('/api/v1/merchants', {
      data: { name: 'Broadcast Loja E2E', email, password: 'Senha-Loja-1' },
    });
    expect(reg.status()).toBe(201);
    state.merchantId = (await reg.json()).id;

    const login = await request.post('/api/v1/merchant-auth/login', {
      data: { email, password: 'Senha-Loja-1' },
    });
    expect(login.status()).toBe(200);
    const portalToken = (await login.json()).token;

    const bot = await request.post('/api/v1/portal/bots', {
      headers: { Authorization: `Bearer ${portalToken}` },
      data: { platform: 'telegram', token: '123456:Broadcast-E2E-token', displayName: 'Bot Broadcast' },
    });
    expect(bot.status()).toBe(201);
    state.botId = (await bot.json()).id;

    // Broadcasts em statuses diferentes (seed direto — status/erro controlados).
    const now = new Date().toISOString();
    const mk = (mode, status, errorMessage) =>
      client.query(
        `INSERT INTO broadcasts (id, store_bot_id, mode, target_chat_id, content, status, error_message, created_at, updated_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $8) RETURNING id`,
        [nodeCrypto.randomUUID(), state.botId, mode, '-100abc', 'Campanha de teste — conteúdo auditável', status, errorMessage, now],
      );
    const pending = await mk('NOW', 'PENDING', null);
    state.broadcastPending = pending.rows[0].id;
    state.broadcastProcessing = (await mk('SCHEDULED', 'PROCESSING', null)).rows[0].id;
    state.broadcastCompleted = (await mk('NOW', 'COMPLETED', null)).rows[0].id;

    // Logs do broadcast PENDING (contagens SUCCESS/BLOCKED/FAILED).
    await client.query(
      `INSERT INTO broadcast_logs (broadcast_id, recipient_id, status, sent_at)
       VALUES ($1, 'u-1', 'SUCCESS', $2), ($1, 'u-2', 'BLOCKED', $2), ($1, 'u-3', 'FAILED', $2)`,
      [state.broadcastPending, now],
    );
    await client.end();
  });

  test.afterAll(async () => {
    await new Promise((resolve) => telegramServer && telegramServer.close(resolve));
  });

  /** Login no browser + navega até /console/broadcasts (cada teste tem contexto limpo). */
  async function loginAsOperator(page) {
    await page.goto('/console/login');
    await page.getByLabel('Usuário').fill(username);
    await page.getByLabel('Senha').fill(PASSWORD);
    await page.getByRole('button', { name: 'Entrar' }).click();
    await page.waitForURL('**/console/dashboard');
    await page.getByRole('link', { name: 'Broadcasts' }).click();
    await page.waitForURL('**/console/broadcasts');
  }

  test('1. login admin → lista cross-merchant → drawer (conteúdo + logs)', async ({ page }) => {
    await loginAsOperator(page);
    await expect(page.locator('h1')).toHaveText('Broadcasts');
    await expect(page.locator('table')).toContainText('Broadcast Loja E2E');
    await expect(page.locator('table')).toContainText('Bot Broadcast');

    // Drawer de detalhe: conteúdo + métricas (1 ok / 1 bloqueado / 1 falha).
    await page.getByRole('button', { name: 'Inspecionar' }).first().click();
    await expect(page.locator('.modal')).toContainText('Campanha de teste');
    await expect(page.locator('.modal')).toContainText('1 ok');
    await expect(page.locator('.modal')).toContainText('1 bloqueados');
    await expect(page.locator('.modal')).toContainText('1 falhas');
    await expect(page.locator('.modal')).toContainText('SUCCESS');
    await page.getByRole('button', { name: 'Fechar' }).click();
  });

  test('2. Cancelar Campanha (PENDING) → FAILED no banco + trilha do operador', async ({ page, request }) => {
    await loginAsOperator(page);

    // Abre o detalhe do PENDING e cancela com confirmação (danger).
    const pendingRow = page.locator('tr', { hasText: 'PENDING' }).first();
    await pendingRow.getByRole('button', { name: 'Inspecionar' }).click();
    await expect(page.locator('.modal')).toContainText('Cancelar campanha');
    await page.getByRole('button', { name: 'Cancelar campanha' }).click();
    // ConfirmDialog (nome "Cancelar campanha") — o drawer de detalhe também é
    // role=dialog (strict mode), escopa por accessible name.
    await expect(page.getByRole('dialog', { name: 'Cancelar campanha' })).toBeVisible();
    // Botão de confirmar DENTRO do ConfirmDialog (o drawer também tem "Cancelar campanha").
    await page.getByRole('dialog', { name: 'Cancelar campanha' }).getByRole('button', { name: 'Cancelar campanha' }).click();
    await expect(page.locator('.alert-success')).toContainText('cancelada');
    await expect(page.locator('.modal')).toContainText('FAILED');

    // Status FAILED + error_message no banco.
    const client = dbClient();
    await client.connect();
    const r = await client.query(
      `SELECT status, error_message FROM broadcasts WHERE id = $1`,
      [state.broadcastPending],
    );
    expect(r.rows[0].status).toBe('FAILED');
    expect(r.rows[0].error_message).toBe('Cancelled by Master Operator');

    // Trilha master_audit_log com BROADCAST_FORCE_CANCELLED e o operador.
    const audit = await client.query(
      `SELECT action, actor_id, reference_type, reference_id FROM master_audit_log
       WHERE action = 'BROADCAST_FORCE_CANCELLED' AND reference_id = $1`,
      [state.broadcastPending],
    );
    expect(audit.rows.length).toBe(1);
    expect(audit.rows[0].reference_type).toBe('BROADCAST');
    expect(audit.rows[0].actor_id).toBeTruthy();
    await client.end();

    // O botão desaparece após FAILED (reload da lista).
    await page.getByRole('button', { name: 'Fechar' }).click();
    await page.reload();
    await expect(page.locator('table')).toContainText('FAILED');
  });

  test('3. botão ausente em PROCESSING/COMPLETED + 409 no force-cancel', async ({ page, request }) => {
    await loginAsOperator(page);

    // PROCESSING não pode ser cancelado: o drawer NÃO oferece o botão.
    const processingRow = page.locator('tr', { hasText: 'PROCESSING' }).first();
    await processingRow.getByRole('button', { name: 'Inspecionar' }).click();
    await expect(page.locator('.modal')).toContainText('PROCESSING');
    await expect(page.locator('.modal').getByRole('button', { name: 'Cancelar campanha' })).toHaveCount(0);
    await page.getByRole('button', { name: 'Fechar' }).click();

    // COMPLETED idem.
    const completedRow = page.locator('tr', { hasText: 'COMPLETED' }).first();
    await completedRow.getByRole('button', { name: 'Inspecionar' }).click();
    await expect(page.locator('.modal')).toContainText('COMPLETED');
    await expect(page.locator('.modal').getByRole('button', { name: 'Cancelar campanha' })).toHaveCount(0);
    await page.getByRole('button', { name: 'Fechar' }).click();

    // 409 no force-cancel direto pela API (PROCESSING não é PENDING).
    const login = await request.post('/api/v1/auth/master/login', {
      data: { username, password: PASSWORD },
    });
    expect(login.status()).toBe(200);
    const headers = { Authorization: `Bearer ${(await login.json()).token}` };
    const cancel = await request.post(
      `/api/v1/master/broadcasts/${state.broadcastProcessing}/force-cancel`,
      { headers },
    );
    expect(cancel.status()).toBe(409);
    const body = await cancel.json();
    expect(body.code).toBe('invalid_broadcast_state');
  });
});
