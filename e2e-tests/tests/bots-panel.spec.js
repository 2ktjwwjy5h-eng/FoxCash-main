// @ts-check
const { test, expect } = require('@playwright/test');
const { Client } = require('pg');
const http = require('http');

/**
 * Painel de bots (Spec 76) — E2E BROWSER das duas telas (SPAs servidas pelo
 * Spring Boot; app com FOXCASH_PORTAL_SERVE_FRONTEND/FOXCASH_CONSOLE_SERVE_FRONTEND
 * + FOXCASH_TELEGRAM_API_BASE_URL=http://localhost:<mockPort>):
 *
 * PORTAL ("Meus Bots", /bots):
 *   1. Empty state antes de qualquer bot.
 *   2. Conectar bot (modal → integração real via mock Telegram getMe/setWebhook)
 *      → card ONLINE + banner de sucesso.
 *   3. Pausar → OFFLINE + banner; Reativar → ONLINE + banner.
 *   4. Editar (display_name) → banner + card atualizado.
 *   5. Filtro por plataforma (Telegram vs Discord).
 *   6. Excluir (ConfirmDialog danger) → banner + volta ao empty state.
 *
 * CONSOLE ("Bots", /console/bots):
 *   7. Espelho read-only: tabela com o bot do lojista + nome do lojista e
 *      métricas; filtros; NENHUMA ação de escrita (nenhum botão de mutação).
 */
test.describe.serial('Painel de bots (Spec 76)', () => {
  const TELEGRAM_MOCK_PORT = Number(process.env.FOXCASH_TELEGRAM_MOCK_PORT || 9001);
  const ts = Date.now();
  const email = `bots-panel-${ts}@example.com`;
  const password = 'Senha-Painel-123';
  const MERCHANT_NAME = 'Loja Bots Painel E2E';

  /** @type {Array<{method: string, url: string, body: string}>} */
  const telegramRequests = [];
  /** @type {import('http').Server | null} */
  let telegramServer = null;

  /**
   * Identidade Telegram por token (contrato do mock de getMe). O backend deriva o
   * display_name final do bot do getMe (integração sobrescreve o displayName do
   * registro) — então cada token mapeia para o nome que os testes esperam ver.
   * Fallback determinístico para tokens futuros.
   */
  const TELEGRAM_IDENTITIES = {
    '123456:ABC-Painel-token': { name: 'Bot Painel', username: 'botpainel' },
    '123456:ABC-Console-token': { name: 'Bot Console Espelho', username: 'botconsole' },
  };

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

  test.beforeAll(async () => {
    // Mock de contrato da Bot API do Telegram (getMe → identidade; setWebhook → ok).
    telegramServer = http.createServer((req, res) => {
      let body = '';
      req.on('data', (c) => (body += c));
      req.on('end', () => {
        telegramRequests.push({ method: req.method, url: req.url, body });
        res.writeHead(200, { 'Content-Type': 'application/json' });
        if (req.url.endsWith('/getMe')) {
          // O token vem no path (/bot<token>/getMe) — deriva a identidade. O
          // Spring URL-encoda o ':' do token (%3A), então decodifica antes.
          const tokenMatch = req.url.match(/\/bot([^/]+)\/getMe$/);
          const token = tokenMatch ? decodeURIComponent(tokenMatch[1]) : '';
          const identity = TELEGRAM_IDENTITIES[token] || {
            name: `Bot ${token.split(':')[1] || token}`,
            username: `bot${(token.split(':')[1] || token).replace(/[^a-zA-Z0-9]/g, '').toLowerCase()}`,
          };
          res.end(JSON.stringify({
            ok: true,
            result: { id: 123, is_bot: true, first_name: identity.name, username: identity.username, can_join_groups: true },
          }));
        } else {
          res.end(JSON.stringify({ ok: true, result: true }));
        }
      });
    });
    await new Promise((resolve) => telegramServer.listen(TELEGRAM_MOCK_PORT, resolve));

    // Lojista para o portal (cadastro público — API; o login é no browser).
    const register = await fetch(`${process.env.API_BASE_URL || 'http://localhost:8080'}/api/v1/merchants`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: MERCHANT_NAME, email, password }),
    });
    expect(register.status).toBe(201);
  });

  test.afterAll(async () => {
    await new Promise((resolve) => telegramServer && telegramServer.close(resolve));
  });

  test('1. Portal — login → Meus Bots → empty state → conectar → pausar → reativar → editar → filtrar → excluir', async ({
    page,
  }) => {
    // --- Login --------------------------------------------------------------
    await page.goto('/login');
    await page.getByLabel('E-mail').fill(email);
    await page.getByLabel('Senha').fill(password);
    await page.getByRole('button', { name: 'Entrar' }).click();
    await page.waitForURL('**/dashboard');

    // --- Meus Bots: empty state --------------------------------------------
    await page.getByRole('link', { name: 'Meus Robôs' }).click();
    await page.waitForURL('**/bots');
    await expect(page.locator('h1')).toHaveText('Meus Bots');
    await expect(page.locator('text=Nenhum bot conectado ainda.')).toBeVisible();

    // --- Conectar bot (modal → integração real via mock) --------------------
    await page.getByRole('button', { name: 'Conectar bot' }).first().click();
    await expect(page.getByRole('dialog').getByRole('heading', { name: 'Conectar bot' })).toBeVisible();
    await page.getByLabel('Token do bot (BotFather)').fill('123456:ABC-Painel-token');
    await page.getByLabel('Nome de exibição (opcional)').fill('Bot Painel');
    await page.getByRole('button', { name: 'Validar e conectar' }).click();

    // Banner de sucesso + card ONLINE com métricas.
    await expect(page.locator('[role="status"]')).toContainText('Bot do Telegram conectado!');
    await expect(page.locator('.bot-card')).toContainText('Bot Painel');
    await expect(page.locator('.bot-card')).toContainText('@botpainel');
    await expect(page.locator('.bot-card .tag')).toHaveText('ONLINE');
    // O mock recebeu getMe + setWebhook (integração real da Spec 74).
    expect(telegramRequests.some((r) => r.url.endsWith('/getMe'))).toBe(true);
    expect(telegramRequests.some((r) => r.url.endsWith('/setWebhook'))).toBe(true);

    // --- Pausar → OFFLINE + banner ------------------------------------------
    await page.getByRole('button', { name: 'Pausar' }).click();
    await expect(page.locator('[role="status"]')).toContainText('pausado');
    await expect(page.locator('.bot-card .tag')).toHaveText('OFFLINE');

    // --- Reativar → ONLINE + banner -----------------------------------------
    await page.getByRole('button', { name: 'Reativar' }).click();
    await expect(page.locator('[role="status"]')).toContainText('reativado');
    await expect(page.locator('.bot-card .tag')).toHaveText('ONLINE');

    // --- Editar (display_name) → banner + card atualizado -------------------
    await page.getByRole('button', { name: 'Editar' }).click();
    await expect(page.locator('text=Editar bot')).toBeVisible();
    await page.getByLabel('Nome de exibição').fill('Bot Painel Renomeado');
    await page.getByRole('button', { name: 'Salvar' }).click();
    await expect(page.locator('[role="status"]')).toContainText('atualizado');
    await expect(page.locator('.bot-card')).toContainText('Bot Painel Renomeado');

    // --- Filtro por plataforma: Telegram mostra, Discord vazio --------------
    await page.getByRole('button', { name: 'Discord' }).click();
    await expect(page.locator('text=Nenhum bot de Discord conectado.')).toBeVisible();
    await page.getByRole('button', { name: 'Telegram' }).click();
    await expect(page.locator('.bot-card')).toContainText('Bot Painel Renomeado');
    await page.getByRole('button', { name: 'Todas' }).click();
    await expect(page.locator('.bot-card')).toHaveCount(1);

    // --- Excluir (ConfirmDialog danger) → banner + empty state --------------
    await page.getByRole('button', { name: 'Excluir' }).click();
    await expect(page.getByRole('dialog').getByRole('heading', { name: 'Excluir bot' })).toBeVisible();
    await page.getByRole('dialog').getByRole('button', { name: 'Excluir' }).click();
    await expect(page.locator('[role="status"]')).toContainText('removido');
    await expect(page.locator('text=Nenhum bot conectado ainda.')).toBeVisible();
  });

  test('2. Console — espelho read-only com o bot do lojista (RBAC, sem ações de escrita)', async ({
    page,
  }) => {
    // Master OPERATOR via SQL (padrão master-console-ui/auth).
    const nodeCrypto = require('crypto');
    const masterUsername = `bots-console-${ts}`;
    await dbQuery(
      `INSERT INTO masters (id, username, password_hash, name, active, can_manage_masters, created_at)
       VALUES ($1, $2, $3, $4, true, false, $5)`,
      [nodeCrypto.randomUUID(), masterUsername,
        '$2a$10$15Pv6f0BRyiTgp.DrVUjwenfdnTaAh5uY1EGRfn0oso2M3GK9IIv6',
        'Master Painel E2E', new Date().toISOString()],
    );

    // --- Login no Master Console --------------------------------------------
    await page.goto('/console/login');
    await expect(page.locator('h1.auth-title')).toHaveText('FoxCash');
    await page.getByLabel('Usuário').fill(masterUsername);
    await page.getByLabel('Senha').fill('Senha-E2E-123');
    await page.getByRole('button', { name: 'Entrar' }).click();
    await page.waitForURL('**/console/dashboard');

    // --- Tela "Bots" ---------------------------------------------------------
    await page.getByRole('link', { name: 'Bots' }).click();
    await page.waitForURL('**/console/bots');
    await expect(page.locator('h1')).toHaveText('Bots');

    // O bot do lojista aparece com nome do lojista + status ONLINE (o teste 1
    // recriou o bot? não — o teste 1 EXCLUIU o bot; então o registro precisa
    // existir. Para o espelho, recriamos um bot via API com o mesmo lojista).
    // --- Setup: reconecta um bot via API (o teste 1 removeu o dele) ---------
    // Usa o mock Telegram (ainda vivo neste worker) via POST /portal/bots.
    const token = '123456:ABC-Console-token';
    const login = await (await fetch(`${process.env.API_BASE_URL || 'http://localhost:8080'}/api/v1/merchant-auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    })).json();
    const created = await fetch(`${process.env.API_BASE_URL || 'http://localhost:8080'}/api/v1/portal/bots`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${login.token}` },
      body: JSON.stringify({ platform: 'TELEGRAM', token, displayName: 'Bot Console Espelho' }),
    });
    expect(created.status).toBe(201);

    // Recarrega a página do console para ver o bot novo.
    await page.reload();
    await expect(page.locator('table')).toContainText('Bot Console Espelho');
    await expect(page.locator('table')).toContainText(MERCHANT_NAME);
    await expect(page.locator('table')).toContainText('ONLINE');

    // --- Filtros (lojista) ---------------------------------------------------
    await page.getByPlaceholder('Filtrar por lojista…').fill('loja inexistente xyz');
    await expect(page.locator('table')).toContainText('Nenhum bot encontrado.');
    await page.getByPlaceholder('Filtrar por lojista…').fill(MERCHANT_NAME);
    await expect(page.locator('table')).toContainText('Bot Console Espelho');

    // --- SEM ações de escrita (critério 5 — espelho read-only) --------------
    await expect(page.getByRole('button', { name: /Pausar|Reativar|Editar|Excluir/ })).toHaveCount(0);
  });
});
