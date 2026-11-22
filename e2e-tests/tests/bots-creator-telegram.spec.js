// @ts-check
const { test, expect } = require('@playwright/test');
const { Client } = require('pg');
const http = require('http');

/**
 * Criador de Bot Telegram (Spec 77) — E2E BROWSER do wizard no portal (SPA
 * servida pelo Spring Boot; app com FOXCASH_PORTAL_SERVE_FRONTEND +
 * FOXCASH_TELEGRAM_API_BASE_URL=http://localhost:<mockPort>):
 *
 *   1. Wizard completo: empty state → "Criar bot Telegram" → intro → BotFather
 *      (validação de formato do token sem rede) → validar (getMe → nome/@) →
 *      setup opcional (setMyDescription) → register (POST /portal/bots 1×) →
 *      done → "Meus Bots" com o card ONLINE.
 *   2. Re-tentar registrar o MESMO token → 409 bot_already_exists (o bot não
 *      é duplicado no banco — idempotência; "voltar" não recria registro).
 */
test.describe.serial('Criador de Bot Telegram (Spec 77)', () => {
  const TELEGRAM_MOCK_PORT = Number(process.env.FOXCASH_TELEGRAM_MOCK_PORT || 9001);
  const ts = Date.now();
  const email = `bots-creator-${ts}@example.com`;
  const password = 'Senha-Wizard-123';
  const MERCHANT_NAME = 'Loja Wizard E2E';

  // Token com ≥30 chars após ':' (regex do BotFatherStep) — identidade no mock.
  const TOKEN = '123456:WizardBotTokenABCDEFGHIJKLMNOPQRSTUVWXYZ';

  /** @type {Array<{method: string, url: string, body: string}>} */
  const telegramRequests = [];
  /** @type {import('http').Server | null} */
  let telegramServer = null;

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

  async function storeBotCount() {
    const rows = await dbQuery(
      `SELECT COUNT(*)::int AS count FROM store_bots sb
       JOIN merchants m ON m.id = sb.merchant_id
       WHERE m.email = $1 AND sb.deleted_at IS NULL`,
      [email],
    );
    return rows[0].count;
  }

  test.beforeAll(async () => {
    // Mock de contrato da Bot API do Telegram: getMe devolve a identidade do
    // wizard; qualquer outro método (setWebhook/setMyDescription/...) → ok:true.
    telegramServer = http.createServer((req, res) => {
      let body = '';
      req.on('data', (c) => (body += c));
      req.on('end', () => {
        telegramRequests.push({ method: req.method, url: req.url, body });
        res.writeHead(200, { 'Content-Type': 'application/json' });
        if (req.url.endsWith('/getMe')) {
          const tokenMatch = req.url.match(/\/bot([^/]+)\/getMe$/);
          const token = tokenMatch ? decodeURIComponent(tokenMatch[1]) : '';
          const identity = token === TOKEN
            ? { name: 'Bot Wizard', username: 'botwizard' }
            : { name: 'Bot Desconhecido', username: 'botdesconhecido' };
          res.end(JSON.stringify({
            ok: true,
            result: { id: 456, is_bot: true, first_name: identity.name, username: identity.username, can_join_groups: true },
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

  test('1. Wizard completo: empty → criar → validar → setup → registrar → Meus Bots', async ({ page }) => {
    // --- Login ----------------------------------------------------------------
    await page.goto('/login');
    await page.getByLabel('E-mail').fill(email);
    await page.getByLabel('Senha').fill(password);
    await page.getByRole('button', { name: 'Entrar' }).click();
    await page.waitForURL('**/dashboard');

    // --- Meus Bots: empty state com CTA "Criar bot Telegram" ------------------
    await page.getByRole('link', { name: 'Meus Robôs' }).click();
    await page.waitForURL('**/bots');
    await expect(page.locator('text=Nenhum bot conectado ainda.')).toBeVisible();
    await page.getByRole('link', { name: 'Criar bot Telegram' }).first().click();
    await page.waitForURL('**/bots/creator/telegram');

    // --- Intro ----------------------------------------------------------------
    await expect(page.locator('h1')).toHaveText('Criar bot Telegram');
    await expect(page.locator('text=O que é um bot de vendas?')).toBeVisible();
    await page.getByRole('button', { name: 'Começar' }).click();

    // --- BotFather: validação de formato do token (regex, sem rede) ----------
    await expect(page.locator('text=Criar o bot no BotFather')).toBeVisible();
    await page.getByLabel('Token do bot (do BotFather)').fill('token-invalido');
    await expect(page.locator('text=Formato inválido')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Validar token' })).toBeDisabled();
    await page.getByLabel('Token do bot (do BotFather)').fill(TOKEN);

    // --- Validar (getMe → nome/@ em tempo real) ------------------------------
    await page.getByRole('button', { name: 'Validar token' }).click();
    await expect(page.locator('text=Bot encontrado!')).toBeVisible();
    await expect(page.locator('.bot-identity-preview')).toContainText('Bot Wizard');
    await expect(page.locator('.bot-identity-preview')).toContainText('@botwizard');
    await page.getByRole('button', { name: 'Continuar' }).click();

    // --- Setup opcional (setMyDescription via mock) --------------------------
    await expect(page.locator('text=Configuração inicial (opcional)')).toBeVisible();
    await page.getByLabel('Descrição do bot').fill('Venda de planos pelo Telegram');
    await page.getByRole('button', { name: 'Aplicar e continuar' }).click();
    await expect(page.locator('[role="status"]')).toContainText('Configuração aplicada');

    // --- Register (POST /portal/bots 1×) → done -------------------------------
    await expect(page.locator('text=Registrar no FoxCash')).toBeVisible();
    await page.getByRole('button', { name: 'Registrar bot' }).click();
    await expect(page.locator('text=Bot no ar!')).toBeVisible();
    await expect(page.locator('.bot-identity-preview')).toContainText('Bot Wizard');
    await expect(page.locator('.tag-success')).toHaveText('ONLINE');

    // O mock recebeu a integração real da Spec 74 + o setMyDescription.
    expect(telegramRequests.filter((r) => r.url.endsWith('/getMe')).length).toBeGreaterThanOrEqual(2);
    expect(telegramRequests.some((r) => r.url.endsWith('/setWebhook'))).toBe(true);
    expect(telegramRequests.some((r) => r.url.endsWith('/setMyDescription'))).toBe(true);

    // --- Conclusão → Meus Bots com o card ONLINE ------------------------------
    await page.getByRole('link', { name: 'Ver Meus Bots' }).click();
    await page.waitForURL('**/bots');
    await expect(page.locator('.bot-card')).toContainText('Bot Wizard');
    await expect(page.locator('.bot-card .tag')).toHaveText('ONLINE');

    // Exatamente 1 registro no banco (POST /portal/bots executado 1×).
    expect(await storeBotCount()).toBe(1);
  });

  test('2. Re-registrar o mesmo token → 409 e NENHUMA duplicação', async ({ page }) => {
    // Login (context novo por teste — o login do teste 1 não persiste).
    await page.goto('/login');
    await page.getByLabel('E-mail').fill(email);
    await page.getByLabel('Senha').fill(password);
    await page.getByRole('button', { name: 'Entrar' }).click();
    await page.waitForURL('**/dashboard');

    // Wizard novo (mesmo lojista, mesmo token já registrado).
    await page.goto('/bots/creator/telegram');
    await page.getByRole('button', { name: 'Começar' }).click();
    await page.getByLabel('Token do bot (do BotFather)').fill(TOKEN);
    await page.getByRole('button', { name: 'Validar token' }).click();
    await expect(page.locator('text=Bot encontrado!')).toBeVisible();
    await page.getByRole('button', { name: 'Continuar' }).click();
    await page.getByRole('button', { name: 'Pular etapa' }).click();

    // Registrar de novo → 409 bot_already_exists (mensagem do backend no banner).
    await page.getByRole('button', { name: 'Registrar bot' }).click();
    await expect(page.locator('[role="alert"]')).toContainText('Ja existe um bot de venda com este token');

    // O registro NÃO foi duplicado (continua 1).
    expect(await storeBotCount()).toBe(1);
  });
});
