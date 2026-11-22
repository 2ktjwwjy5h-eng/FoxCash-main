// @ts-check
// E2E BROWSER do shell do portal (Spec 102):
//   - login → /dashboard (Layout com HeaderGlobal + Sidebar em seções)
//   - header: saudação "Boa…", data, faturamento + barra de meta, avatares
//     (carrossel de leads recentes — Spec 101)
//   - clique num avatar → Live Chat do CRM abre (Spec 84 via /crm?lead={id})
//   - menu lateral expandido em seções (NAV_GROUPS)
//   - atalho de notificações mostra badge (contador do shell)
//   - API sem token → 401
// Requer a app com FOXCASH_PORTAL_SERVE_FRONTEND=true + PostgreSQL dev.
const { test, expect } = require('@playwright/test');
const { Client } = require('pg');
const nodeCrypto = require('crypto');

function dbClient() {
  return new Client({
    host: process.env.DB_HOST || 'localhost',
    port: Number(process.env.DB_PORT || 5432),
    user: process.env.DB_USERNAME || 'nyxcash',
    password: process.env.DB_PASSWORD || 'nyxcash',
    database: process.env.DB_NAME || 'nyxcash',
  });
}

/** Replica o CryptoConverter do backend (AES-256/GCM, formato `v1:<base64>`). */
function encryptAtRest(plain) {
  const secret = process.env.FOXCASH_CRYPTO_SECRET || 'dev-only-secret-change-me-32bytes';
  const key = nodeCrypto.createHash('sha256').update(secret).digest();
  const iv = nodeCrypto.randomBytes(12);
  const cipher = nodeCrypto.createCipheriv('aes-256-gcm', key, iv);
  const ct = Buffer.concat([cipher.update(plain, 'utf8'), cipher.final()]);
  return 'v1:' + Buffer.concat([iv, ct, cipher.getAuthTag()]).toString('base64');
}

const sha256 = (s) => nodeCrypto.createHash('sha256').update(s).digest('hex');

test.describe.serial('Portal — Shell (Specs 101/102)', () => {
  const ts = Date.now();
  const email = `shell-${ts}@example.com`;
  const password = 'Senha-Shell-1';
  const leadId1 = nodeCrypto.randomUUID();
  const leadId2 = nodeCrypto.randomUUID();

  test.beforeAll(async ({ request }) => {
    const reg = await request.post('/api/v1/merchants', {
      data: { name: 'Loja Shell E2E', email, password },
    });
    expect(reg.status()).toBe(201);
    const merchant = await reg.json();

    const login = await request.post('/api/v1/merchant-auth/login', {
      data: { email, password },
    });
    expect(login.status()).toBe(200);

    // Seed: 1 bot + 2 leads (carrossel) + 2 eventos MERCHANT (notificações).
    const client = dbClient();
    await client.connect();
    try {
      const botId = nodeCrypto.randomUUID();
      const token = 'bot-token-shell-e2e';
      await client.query(
        `INSERT INTO store_bots (id, merchant_id, platform, token_encrypted, token_hash,
             webhook_secret_hash, display_name, username, status, created_at, updated_at)
         VALUES ($1, $2, 'TELEGRAM', $3, $4, $5, 'Bot Shell E2E', '@botshell', 'ACTIVE', now(), now())`,
        [botId, merchant.id, encryptAtRest(token), sha256(token), sha256('wh-secret-shell')],
      );
      const nowIso = new Date().toISOString();
      await client.query(
        `INSERT INTO bot_leads (id, bot_id, platform_user_id, first_seen_at, created_at,
             name, username, status, handoff_status, updated_at)
         VALUES ($1, $2, 'u-shell-1', $3, $3, 'Joao Silva', '@joaosilva', 'NOVO', 'BOT', $3),
                ($4, $2, 'u-shell-2', $3, $3, 'Maria Oliveira', '@mariaol', 'PAGO', 'BOT', $3)`,
        [leadId1, botId, nowIso, leadId2],
      );
      await client.query(
        `INSERT INTO platform_activity_log (id, actor_type, actor_id, action, entity_type,
             entity_id, merchant_id, details, created_at)
         VALUES ($1, 'MERCHANT', $2, 'SALE_PAID', 'TRANSACTION', $3, $2, '{}', $4),
                ($5, 'MERCHANT', $2, 'WITHDRAWAL_COMPLETED', 'WITHDRAWAL', $3, $2, '{}', $4)`,
        [nodeCrypto.randomUUID(), merchant.id, nodeCrypto.randomUUID(), nowIso, nodeCrypto.randomUUID()],
      );
    } finally {
      await client.end();
    }
  });

  test('shell: header (saudação/billing/barra/avatares) + menu em seções + chat + badge', async ({ page }) => {
    // --- Login (rota pública) ----------------------------------------------
    await page.goto('/login');
    await page.getByLabel('E-mail').fill(email);
    await page.getByLabel('Senha').fill(password);
    await page.getByRole('button', { name: 'Entrar' }).click();
    await page.waitForURL('**/dashboard');

    // --- Header global (Spec 102 C1) ---------------------------------------
    const header = page.locator('.header-global');
    await expect(header).toBeVisible();

    // Saudação com a hora do dia + nome do lojista + data.
    await expect(page.locator('.header-greeting-title')).toContainText('Boa');
    await expect(page.locator('.header-greeting-title')).toContainText('Loja Shell E2E');
    await expect(page.locator('.header-greeting-date')).toBeVisible();

    // Faturamento + barra de meta (sem vendas → R$ 0,00 e meta do mês).
    await expect(page.locator('.header-billing-value')).toContainText('R$ 0,00');
    await expect(page.locator('.header-billing-goal')).toContainText('Meta do mês');
    await expect(page.locator('.header-progress')).toBeVisible();

    // Carrossel de leads recentes (2 avatares com iniciais).
    const avatars = page.locator('.lead-avatar');
    await expect(avatars).toHaveCount(2);
    await expect(avatars.nth(0)).toHaveText('JS');
    await expect(avatars.nth(1)).toHaveText('MO');

    // --- Clique no avatar → Live Chat do CRM abre (Spec 102 C2) ------------
    await avatars.nth(0).click();
    await page.waitForURL('**/crm**');
    const chatPanel = page.locator('.crm-inbox-chat');
    await expect(chatPanel).toBeVisible();
    await expect(chatPanel).toContainText('Joao Silva');
    await page.getByRole('button', { name: 'Fechar', exact: true }).click();

    // --- Menu lateral em seções (Spec 102 C3) ------------------------------
    const sections = page.locator('.nav-group-title');
    await expect(sections).toContainText(['Visão Geral', 'Financeiro', 'Clientes', 'Automações', 'Ferramentas', 'Mais']);
    // Itens reais navegam; itens futuros aparecem desabilitados "em breve".
    await expect(page.locator('.nav-link-soon').first()).toBeVisible();
    await expect(page.locator('.nav-link-soon').first()).toContainText('em breve');

    // --- Atalho de notificações com badge (Spec 102 C4) --------------------
    // O badge = nº de eventos recentes do lojista (inclui login/cadastro do
    // próprio fluxo E2E) — valida presença + as mensagens seedadas no dropdown.
    const notifTrigger = page.getByRole('button', { name: /Notificações/ });
    await expect(page.locator('.notifications-badge')).toHaveText(/\d+/);
    await notifTrigger.click();
    await expect(page.locator('.notifications-dropdown')).toBeVisible();
    await expect(page.locator('.notifications-item', { hasText: 'Venda aprovada' })).toBeVisible();
    await expect(page.locator('.notifications-item', { hasText: 'Saque concluído' })).toBeVisible();
    await page.keyboard.press('Escape');
  });

  test('shell: API sem token responde 401', async ({ request }) => {
    const res = await request.get('/api/v1/portal/shell');
    expect(res.status()).toBe(401);
  });
});
