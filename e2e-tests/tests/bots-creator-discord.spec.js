// @ts-check
const { test, expect } = require('@playwright/test');
const { Client } = require('pg');
const http = require('http');

/**
 * Criador de Bot Discord (Spec 78) — E2E BROWSER do wizard no portal (SPA
 * servida pelo Spring Boot; app com FOXCASH_PORTAL_SERVE_FRONTEND +
 * FOXCASH_DISCORD_API_BASE_URL=http://localhost:<mockPort>):
 *
 *   1. Wizard completo: empty state → "Criar bot Discord" → intro → Developer
 *      Portal → credenciais (token + Application ID, validação de formato sem
 *      rede) → validar (getMe → username) → setup opcional (PATCH /users/@me)
 *      → register (POST /portal/bots 1×) → done com link de convite OAuth2 →
 *      "Meus Bots" com o card ONLINE.
 *   2. Re-tentar registrar o MESMO token → 409 bot_already_exists (o bot não
 *      é duplicado no banco — idempotência; "voltar" não recria registro).
 */
test.describe.serial('Criador de Bot Discord (Spec 78)', () => {
  const DISCORD_MOCK_PORT = Number(process.env.FOXCASH_DISCORD_MOCK_PORT || 9002);
  const ts = Date.now();
  const email = `bots-creator-discord-${ts}@example.com`;
  const password = 'Senha-Wizard-123';
  const MERCHANT_NAME = 'Loja Wizard Discord E2E';

  // Token com 3 partes separadas por ponto (regex do CredentialsStep) —
  // identidade no mock. Application ID snowflake (17–20 dígitos).
  const TOKEN = 'abc123def456.ghi789jkl012.mno345pqr678stu901vwx234yz567';
  const APP_ID = '123456789012345678';

  /** @type {Array<{method: string, url: string, body: string}>} */
  const discordRequests = [];
  /** @type {import('http').Server | null} */
  let discordServer = null;

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
    // Mock de contrato da API REST do Discord v10: GET /users/@me devolve a
    // identidade do wizard; PATCH /users/@me e PUT /applications/{id}/commands
    // → 200 vazio.
    discordServer = http.createServer((req, res) => {
      let body = '';
      req.on('data', (c) => (body += c));
      req.on('end', () => {
        discordRequests.push({ method: req.method, url: req.url, body });
        res.writeHead(200, { 'Content-Type': 'application/json' });
        if (req.method === 'GET' && req.url === '/users/@me') {
          res.end(JSON.stringify({
            id: '456',
            username: 'botdiscord',
            global_name: 'Bot Discord',
          }));
        } else {
          // PATCH /users/@me e PUT /applications/{id}/commands → ok.
          res.end(JSON.stringify({}));
        }
      });
    });
    await new Promise((resolve) => discordServer.listen(DISCORD_MOCK_PORT, resolve));

    // Lojista para o portal (cadastro público — API; o login é no browser).
    const register = await fetch(`${process.env.API_BASE_URL || 'http://localhost:8080'}/api/v1/merchants`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: MERCHANT_NAME, email, password }),
    });
    expect(register.status).toBe(201);
  });

  test.afterAll(async () => {
    await new Promise((resolve) => discordServer && discordServer.close(resolve));
  });

  test('1. Wizard completo: empty → criar → validar → setup → registrar → Meus Bots', async ({ page }) => {
    // --- Login ----------------------------------------------------------------
    await page.goto('/login');
    await page.getByLabel('E-mail').fill(email);
    await page.getByLabel('Senha').fill(password);
    await page.getByRole('button', { name: 'Entrar' }).click();
    await page.waitForURL('**/dashboard');

    // --- Meus Bots: empty state com CTA "Criar bot Discord" ------------------
    await page.getByRole('link', { name: 'Meus Robôs' }).click();
    await page.waitForURL('**/bots');
    await expect(page.locator('text=Nenhum bot conectado ainda.')).toBeVisible();
    await page.getByRole('link', { name: 'Criar bot Discord' }).first().click();
    await page.waitForURL('**/bots/creator/discord');

    // --- Intro ----------------------------------------------------------------
    await expect(page.locator('h1')).toHaveText('Criar bot Discord');
    await expect(page.locator('text=O que é um bot de vendas?')).toBeVisible();
    await page.getByRole('button', { name: 'Começar' }).click();

    // --- Developer Portal: instruções + link ---------------------------------
    await expect(page.locator('text=Criar a aplicação no Developer Portal')).toBeVisible();
    await page.getByRole('button', { name: 'Já copiei o token e o ID' }).click();

    // --- Credenciais: validação de formato (regex, sem rede) -----------------
    await expect(page.locator('text=Credenciais do bot')).toBeVisible();
    await page.getByLabel('Token do bot (do Developer Portal)').fill('token-invalido');
    await expect(page.locator('text=Formato inválido')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Validar conexão' })).toBeDisabled();
    await page.getByLabel('Token do bot (do Developer Portal)').fill(TOKEN);
    await page.getByLabel('Application ID').fill('abc');
    await expect(page.locator('text=Application ID inválido')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Validar conexão' })).toBeDisabled();
    await page.getByLabel('Application ID').fill(APP_ID);

    // --- Validar (getMe → username em tempo real) ----------------------------
    await page.getByRole('button', { name: 'Validar conexão' }).click();
    await expect(page.locator('text=Bot encontrado!')).toBeVisible();
    await expect(page.locator('.bot-identity-preview')).toContainText('Bot Discord');
    await expect(page.locator('.bot-identity-preview')).toContainText('@botdiscord');
    await page.getByRole('button', { name: 'Continuar' }).click();

    // --- Setup opcional (PATCH /users/@me via mock) --------------------------
    await expect(page.locator('text=Configuração inicial (opcional)')).toBeVisible();
    await page.getByLabel('Descrição ("Sobre mim")').fill('Venda de planos pelo Discord');
    await page.getByRole('button', { name: 'Aplicar e continuar' }).click();
    await expect(page.locator('[role="status"]')).toContainText('Configuração aplicada');

    // --- Register (POST /portal/bots 1×) → done -------------------------------
    await expect(page.locator('text=Registrar no FoxCash')).toBeVisible();
    await page.getByRole('button', { name: 'Registrar bot' }).click();
    await expect(page.locator('text=Bot no ar!')).toBeVisible();
    await expect(page.locator('.bot-identity-preview')).toContainText('Bot Discord');
    await expect(page.locator('.tag-success')).toHaveText('ONLINE');

    // Link de convite OAuth2 com o application_id do registro.
    await expect(page.getByRole('link', { name: 'Convidar bot para o servidor' })).toHaveAttribute(
      'href',
      `https://discord.com/api/oauth2/authorize?client_id=${APP_ID}&scope=bot&permissions=18432`,
    );

    // O mock recebeu a integração real da Spec 74 + o PATCH de perfil.
    expect(discordRequests.filter((r) => r.method === 'GET' && r.url === '/users/@me').length).toBeGreaterThanOrEqual(1);
    expect(discordRequests.some((r) => r.method === 'PATCH' && r.url === '/users/@me')).toBe(true);
    expect(discordRequests.some((r) => r.url.startsWith('/applications/') && r.url.endsWith('/commands'))).toBe(true);

    // --- Conclusão → Meus Bots com o card ONLINE ------------------------------
    await page.getByRole('link', { name: 'Ver Meus Bots' }).click();
    await page.waitForURL('**/bots');
    await expect(page.locator('.bot-card')).toContainText('Bot Discord');
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
    await page.goto('/bots/creator/discord');
    await page.getByRole('button', { name: 'Começar' }).click();
    await page.getByRole('button', { name: 'Já copiei o token e o ID' }).click();
    await page.getByLabel('Token do bot (do Developer Portal)').fill(TOKEN);
    await page.getByLabel('Application ID').fill(APP_ID);
    await page.getByRole('button', { name: 'Validar conexão' }).click();
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
