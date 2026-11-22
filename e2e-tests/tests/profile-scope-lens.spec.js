// @ts-check
// Perfil de operação — lente de contexto + escopo (Spec 139):
//   - login → lente default Bot → criar perfil BOT_ONLY (modal limpo)
//   - trocar lente Gateway → perfis filtrados (BOT_ONLY some; o ativo cai para o 1º visível)
//   - criar perfil GATEWAY_ONLY → painel da lente Gateway mostra a rota amigável
//   - trocar perfil → banner transitório (nome + escopo)
//   - reload mantém lente + perfil (localStorage `foxcash.portal.lens`)
//
// Requer a app com `FOXCASH_PORTAL_SERVE_FRONTEND=true` (mesmas condições do
// job e2e do CI) + mock do Telegram para o registro do bot.
const { test, expect } = require('@playwright/test');
const http = require('http');

const TELEGRAM_MOCK_PORT = Number(process.env.FOXCASH_TELEGRAM_MOCK_PORT || 9001);

test.describe.serial('Portal — lente de contexto + escopo do perfil (Spec 139)', () => {
  const ts = Date.now();
  const email = `lens-e2e-${ts}@example.com`;
  const password = 'Senha-Lens-123';

  /** @type {import('http').Server} */
  let telegramServer;

  test.beforeAll(async ({ request }) => {
    telegramServer = http.createServer((req, res) => {
      let body = '';
      req.on('data', (c) => (body += c));
      req.on('end', () => {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        if (req.url.endsWith('/getMe')) {
          res.end(JSON.stringify({ ok: true, result: { id: 222, is_bot: true, first_name: 'Bot Lente', username: 'botlente' } }));
        } else {
          res.end(JSON.stringify({ ok: true, result: true }));
        }
      });
    });
    await new Promise((resolve) => telegramServer.listen(TELEGRAM_MOCK_PORT, resolve));

    const reg = await request.post('/api/v1/merchants', {
      data: { name: 'Loja Lente E2E', email, password },
    });
    expect(reg.status()).toBe(201);

    // Bot para o modal "gerenciar bots" (frota) — mock Telegram (Spec 74).
    const login = await request.post('/api/v1/merchant-auth/login', {
      data: { email, password },
    });
    expect(login.status()).toBe(200);
    const headers = { Authorization: `Bearer ${(await login.json()).token}` };
    const bot = await request.post('/api/v1/portal/bots', {
      headers,
      data: { platform: 'telegram', token: '333333:CCC-lens-token', displayName: 'Bot Lente' },
    });
    expect(bot.status()).toBe(201);
  });

  test.afterAll(async () => {
    await new Promise((resolve) => telegramServer && telegramServer.close(resolve));
  });

  /** Login do lojista (contexto/browser novo por teste — isolar sessão). */
  async function login(page) {
    await page.goto('/login');
    await page.getByLabel('E-mail').fill(email);
    await page.getByLabel('Senha').fill(password);
    await page.getByRole('button', { name: 'Entrar' }).click();
    await page.waitForURL('**/dashboard');
  }

  /** Abre o dialog "Criar Novo Perfil" e preenche (modal limpo — nome + Tipo de Operação). */
  async function createProfile(page, name, scopeRadio) {
    await page.getByRole('button', { name: '+ criar perfil' }).click();
    const dialog = page.locator('[role="dialog"]', { hasText: 'Criar Novo Perfil' });
    await expect(dialog).toBeVisible();
    await dialog.getByLabel('Nome do novo perfil').fill(name);
    await dialog.getByRole('radio', { name: scopeRadio }).check();
    await dialog.getByRole('button', { name: 'Criar' }).click();
    await expect(dialog).not.toBeVisible();
  }

  test('lente Bot: criar BOT_ONLY → lente Gateway filtra → criar GATEWAY_ONLY → banner → reload mantém', async ({
    page,
  }) => {
    await login(page);

    // --- Lente default = Bot (primeira superfície de compra) ------------------
    const lensBot = page.getByRole('button', { name: '🤖 Bot' });
    const lensGateway = page.getByRole('button', { name: '🌐 Gateway' });
    await expect(lensBot).toHaveAttribute('aria-pressed', 'true');
    await expect(lensGateway).toHaveAttribute('aria-pressed', 'false');

    // --- Criar perfil BOT_ONLY (modal limpo: nome + Tipo de Operação) ---------
    await createProfile(page, 'Bot Padrão', /Somente Bot/);
    // Na lente Bot, o novo perfil aparece no menu + o banner de sucesso some.
    await expect(page.locator('[role="status"]')).toContainText('Perfil criado: Bot Padrão');

    // --- Trocar lente Gateway → BOT_ONLY some; o ativo cai para o 1º visível --
    await lensGateway.click();
    await expect(lensGateway).toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('[role="status"]')).toContainText('Contexto: Gateway');
    // Dropdown (lente Gateway): só GENERAL + GATEWAY_ONLY — "Bot Padrão" (BOT_ONLY) não aparece.
    await page.locator('.profile-menu-trigger').click();
    await expect(page.locator('.profile-menu-dropdown')).toContainText('Padrão BR');
    await expect(page.locator('.profile-menu-dropdown')).not.toContainText('Bot Padrão');
    await page.keyboard.press('Escape');

    // --- Criar perfil GATEWAY_ONLY → painel da lente Gateway mostra a rota ----
    await createProfile(page, 'Loja BR', /Somente Gateway/);
    // Painel do contexto (lente Gateway): rota amigável do perfil ativo (PADRAO → por risco).
    await expect(page.locator('.profile-route')).toContainText('Padrão (por risco)');

    // --- Trocar perfil → banner transitório com nome + escopo ----------------
    await page.locator('.profile-menu-trigger').click();
    await page.locator('.profile-option', { hasText: 'Padrão BR' }).click();
    await expect(page.locator('[role="status"]')).toContainText('Perfil ativo: Padrão BR');

    // --- Reload mantém lente (Gateway) + perfil ativo (localStorage) ----------
    await page.reload();
    await expect(lensGateway).toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('.profile-menu-trigger')).toContainText('Padrão BR');

    // --- Modal "gerenciar bots" abre com o bot do lojista (frota da lente Bot) -
    await lensBot.click();
    await page.locator('.profile-fleet').getByRole('button', { name: 'gerenciar bots' }).click();
    const botsDialog = page.locator('[role="dialog"]', { hasText: 'Frota do perfil' });
    await expect(botsDialog).toBeVisible();
    await expect(botsDialog).toContainText('Bot Lente');
  });
});
