// @ts-check
// E2E BROWSER das melhorias leves do portal (Spec 119):
//   - CRM enriquecido: busca por telefone + colunas Plano/Última interação
//   - Webhooks melhorado: drawer "Ver payload" (sample sem PII) + aviso de segurança
//   - Comunidade: lista de posts (seed "Bem-vindo" no boot) + detalhe
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

test.describe.serial('Portal — Leves (Spec 119)', () => {
  const ts = Date.now();
  const email = `leves-${ts}@example.com`;
  const password = 'Senha-Leves-1';
  const leadId = nodeCrypto.randomUUID();
  const planId = nodeCrypto.randomUUID();

  test.beforeAll(async ({ request }) => {
    const reg = await request.post('/api/v1/merchants', {
      data: { name: 'Loja Leves E2E', email, password },
    });
    expect(reg.status()).toBe(201);
    const merchant = await reg.json();

    const login = await request.post('/api/v1/merchant-auth/login', {
      data: { email, password },
    });
    expect(login.status()).toBe(200);

    const client = dbClient();
    await client.connect();
    try {
      const botId = nodeCrypto.randomUUID();
      const token = 'bot-token-leves-e2e';
      await client.query(
        `INSERT INTO store_bots (id, merchant_id, platform, token_encrypted, token_hash,
             webhook_secret_hash, display_name, username, status, created_at, updated_at)
         VALUES ($1, $2, 'TELEGRAM', $3, $4, $5, 'Bot Leves E2E', '@botleves', 'ACTIVE', now(), now())`,
        [botId, merchant.id, encryptAtRest(token), sha256(token), sha256('wh-secret-leves')],
      );

      // CRM enriquecido: lead com telefone + plano comprado (APPROVED) →
      // coluna "Plano" preenchida e busca por telefone encontra o lead.
      const nowIso = new Date().toISOString();
      await client.query(
        `INSERT INTO bot_leads (id, bot_id, platform_user_id, first_seen_at, created_at,
             name, username, email, phone, status, handoff_status, updated_at)
         VALUES ($1, $2, 'u-leves-1', $3, $3, 'Cliente Leves', '@clienteleves',
             'cliente@leves.com', '+5511987654321', 'PAGO', 'BOT', $3)`,
        [leadId, botId, nowIso],
      );

      // Plano + transação APPROVED com plan_id (vínculo da compra). O plano
      // pertence a uma store (FK store_id) — cria a store 1:1 do lojista.
      const storeId = nodeCrypto.randomUUID();
      await client.query(
        `INSERT INTO stores (id, merchant_id, name, logo_url, banner_url, description,
             social_links, color, emoji, title, footer, created_at, updated_at)
         VALUES ($1, $2, 'Loja Leves E2E', NULL, NULL, 'Loja de teste', '[]', '#000000',
             '🛍️', 'Loja Leves E2E', 'Loja de teste', $3, $3)`,
        [storeId, merchant.id, nowIso],
      );
      await client.query(
        `INSERT INTO plans (id, store_id, name, description, price, image_url, embed_color,
             featured, active, created_at, updated_at)
         VALUES ($1, $2, 'PLANO PREMIUM', 'Plano E2E', 99.90, NULL, '#000000', false, true, $3, $3)`,
        [planId, storeId, nowIso],
      );
      const txId = nodeCrypto.randomUUID();
      await client.query(
        `INSERT INTO pix_transactions (id, trace_id, merchant_id, amount, description, pix_key,
             end_to_end_id, status, created_at, updated_at, paid_at, buyer_id_hash,
             external_reference, provider, plan_id, bot_id, platform_user_id)
         VALUES ($1, $1, $2, 99.90, 'Venda plano E2E', $3, NULL, 'APPROVED', $4, $4, $4,
             'buyerhashleves', 'ext-ref-leves', 'mock', $5, $6, 'u-leves-1')`,
        [txId, merchant.id, encryptAtRest('chave-pix-leves'), nowIso, planId, botId],
      );
      // Funil PAID: vínculo lead→transação (a coluna platform_user_id da
      // transação é criptografada — a ponte real é o bot_funnel_events).
      await client.query(
        `INSERT INTO bot_funnel_events (id, bot_id, platform_user_id, event, transaction_id, occurred_at)
         VALUES ($1, $2, 'u-leves-1', 'PAID', $3, $4)`,
        [nodeCrypto.randomUUID(), botId, txId, nowIso],
      );

      // Webhook cadastrado (para o drawer "Ver payload").
      await client.query(
        `INSERT INTO merchant_webhooks (id, merchant_id, url, secret, events, status, created_at, updated_at)
         VALUES ($1, $2, 'https://example.com/hook-leves', $3, 'SALE_PAID,SALE_REFUNDED', 'ACTIVE', $4, $4)`,
        [nodeCrypto.randomUUID(), merchant.id, encryptAtRest('wh-secret-leves'), nowIso],
      );

      // Comunidade: post com autor master (o seed do boot roda só na subida da
      // app — o global-setup trunca depois; aqui criamos o post via SQL).
      const masterId = nodeCrypto.randomUUID();
      const PASSWORD_BCRYPT =
        '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy'; // "password"
      await client.query(
        `INSERT INTO masters (id, username, password_hash, name, active, can_manage_masters, created_at)
         VALUES ($1, 'master-leves', $2, 'Master Leves E2E', true, false, $3)`,
        [masterId, PASSWORD_BCRYPT, nowIso],
      );
      await client.query(
        `INSERT INTO community_posts (id, title, content, published_by, pinned, published_at, created_at)
         VALUES ($1, 'Bem-vindo à Comunidade FoxCash', 'Acompanhe aqui os avisos e novidades da plataforma.', $2, true, $3, $3),
                ($4, 'Novidade: dashboard rico', 'O dashboard ganhou novos KPIs.', $2, false, $3, $3)`,
        [nodeCrypto.randomUUID(), masterId, nowIso, nodeCrypto.randomUUID()],
      );
    } finally {
      await client.end();
    }
  });

  test('leves: CRM com telefone + colunas plano/última interação', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('E-mail').fill(email);
    await page.getByLabel('Senha').fill(password);
    await page.getByRole('button', { name: 'Entrar' }).click();
    await page.waitForURL('**/dashboard');

    await page.getByRole('link', { name: 'Clientes' }).click();
    await page.waitForURL('**/crm');

    // Busca por telefone (Spec 119 — o q busca também em phone) → filtra a
    // lista de conversas da central estilo Telegram.
    await page.getByLabel('Buscar clientes').fill('+5511987654321');
    await expect(page.locator('.crm-chat-item')).toHaveCount(1);
    await expect(page.locator('.crm-chat-item').first()).toContainText('Cliente Leves');
    // Abre a conversa: Plano (da última compra APPROVED) + Última interação
    // (hora relativa pt-BR — "há N segundos") aparecem no cabeçalho (Spec 119).
    await page.locator('.crm-chat-item').first().click();
    await expect(page.locator('.crm-inbox-chat')).toContainText('PLANO PREMIUM');
    await expect(page.locator('.crm-inbox-chat')).toContainText(/há \d+ segundo/);
  });

  test('leves: webhook com drawer de payload (sem PII) + aviso de segurança', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('E-mail').fill(email);
    await page.getByLabel('Senha').fill(password);
    await page.getByRole('button', { name: 'Entrar' }).click();
    await page.waitForURL('**/dashboard');

    await page.getByRole('link', { name: 'Webhooks' }).click();
    await page.waitForURL('**/webhooks');

    // Aviso de segurança (Spec 119 D7.2 — texto informativo).
    await expect(page.getByText('HMAC-SHA256')).toBeVisible();
    await expect(page.getByText(/5 falhas/)).toBeVisible();

    // Drawer "Ver payload" → JSON do contrato SEM PII (buyerIdHash mascarado).
    await page.getByRole('button', { name: 'Ver payload' }).first().click();
    await expect(page.getByRole('dialog', { name: 'Payload do evento' })).toBeVisible();
    await expect(page.locator('.payload-pre')).toContainText('"type"');
    await expect(page.locator('.payload-pre')).toContainText('"buyerIdHash"');
    await expect(page.locator('.payload-pre')).toContainText('******');
    // Sem PII: nenhum e-mail/telefone/nome de cliente no sample.
    await expect(page.locator('.payload-pre')).not.toContainText('cliente@leves.com');
    await expect(page.locator('.payload-pre')).not.toContainText('+5511987654321');
    await page.getByRole('button', { name: 'Fechar' }).click();
  });

  test('leves: comunidade com post seed + detalhe', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('E-mail').fill(email);
    await page.getByLabel('Senha').fill(password);
    await page.getByRole('button', { name: 'Entrar' }).click();
    await page.waitForURL('**/dashboard');

    await page.getByRole('link', { name: 'Comunidade' }).click();
    await page.waitForURL('**/community');

    // Seed "Bem-vindo" criado no boot (Spec 119 D5 — idempotente).
    await expect(page.locator('.community-post').first()).toContainText('Bem-vindo');
    await expect(page.locator('.community-post').first()).toContainText('PINADO');

    // Detalhe (modal simples).
    await page.locator('.community-post').first().click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByRole('dialog')).toContainText('Bem-vindo');
    await page.getByRole('button', { name: 'Fechar' }).click();
  });
});
