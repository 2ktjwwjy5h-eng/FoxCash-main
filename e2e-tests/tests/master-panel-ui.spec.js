// @ts-check
const { test, expect } = require('@playwright/test');
const { Client } = require('pg');
const nodeCrypto = require('crypto');

/**
 * E2E BROWSER do Master Console — Painel Geral de Usuários (Spec 99):
 * tabela rica (MED%/conversão/gateway/taxas), perfil com KPIs/dados da conta/
 * taxas e limites, aba Transações (comprador via lead do CRM, produto via
 * plano, líquido), aba Saques (taxa + chave mascarada), Ver Shops (modal) e
 * empty state. Requer a app com FOXCASH_CONSOLE_SERVE_FRONTEND=true (+ portal)
 * e o PostgreSQL dev — mesmas condições do job e2e do CI.
 *
 * Seed: master OPERATOR via SQL (padrão master-console-ui) + lojistas via API
 * pública + stores/plans/bots/leads/transações/saque via SQL (dados ricos do
 * painel da Spec 97).
 */

const BASE = process.env.API_BASE_URL || 'http://localhost:8080';

/** Senha conhecida cujo hash BCrypt está em PASSWORD_BCRYPT (cost 10). */
const PASSWORD = 'Senha-E2E-123';
const PASSWORD_BCRYPT = '$2a$10$15Pv6f0BRyiTgp.DrVUjwenfdnTaAh5uY1EGRfn0oso2M3GK9IIv6';

function dbClient() {
  return new Client({
    host: process.env.DB_HOST || 'localhost',
    port: Number(process.env.DB_PORT || 5432),
    user: process.env.DB_USERNAME || 'nyxcash',
    password: process.env.DB_PASSWORD || 'nyxcash',
    database: process.env.DB_NAME || 'nyxcash',
  });
}

const sha256 = (s) => nodeCrypto.createHash('sha256').update(s).digest('hex');

/**
 * Replica o CryptoConverter do backend (AES-256/GCM, formato `v1:<base64(iv +
 * ct + tag)>`, chave = SHA-256 de foxcash.crypto.secret). Necessário para o
 * seed SQL de pix_transactions/withdrawals: pix_key, platform_user_id e
 * destination_key_snapshot são criptografados em repouso — texto claro quebra
 * a leitura da entidade (aba Transações/Saques). O CI e o dev usam o default
 * do application-dev.yml (FOXCASH_CRYPTO_SECRET não é setado no job e2e).
 */
function encryptAtRest(plain) {
  const secret = process.env.FOXCASH_CRYPTO_SECRET || 'dev-only-secret-change-me-32bytes';
  const key = nodeCrypto.createHash('sha256').update(secret).digest();
  const iv = nodeCrypto.randomBytes(12);
  const cipher = nodeCrypto.createCipheriv('aes-256-gcm', key, iv);
  const ct = Buffer.concat([cipher.update(plain, 'utf8'), cipher.final()]);
  return 'v1:' + Buffer.concat([iv, ct, cipher.getAuthTag()]).toString('base64');
}

test.describe.serial('Master Console Painel Geral (Spec 99)', () => {
  const ts = Date.now();
  const username = `panel-${ts}`;
  let merchantId; // lojista rico (store/plano/bot/lead/transações/saque)
  let emptyMerchantId; // lojista sem dados (empty states)

  test.beforeAll(async ({ request }) => {
    const cleanup = dbClient();
    await cleanup.connect();
    // Padrão stepup: limpa masters/sessões/MFA deixados por specs anteriores.
    await cleanup.query('DELETE FROM master_mfa_methods');
    await cleanup.query('DELETE FROM master_sessions');
    await cleanup.query('DELETE FROM master_audit_log');
    await cleanup.query('DELETE FROM masters');
    await cleanup.end();

    // Master OPERATOR (can_manage_masters=false).
    const client = dbClient();
    await client.connect();
    await client.query(
      `INSERT INTO masters (id, username, password_hash, name, active, can_manage_masters, created_at)
       VALUES ($1, $2, $3, $4, true, false, $5)`,
      [nodeCrypto.randomUUID(), username, PASSWORD_BCRYPT, 'Panel E2E', new Date().toISOString()],
    );

    // Lojista rico (A).
    const reg = await request.post('/api/v1/merchants', {
      data: { name: 'Panel Loja E2E', email: `panel-${ts}@example.com`, password: 'Senha-Loja-1' },
    });
    expect(reg.status()).toBe(201);
    merchantId = (await reg.json()).id;

    // Lojista vazio (B) — empty states.
    const regB = await request.post('/api/v1/merchants', {
      data: { name: 'Panel Vazia E2E', email: `panel-empty-${ts}@example.com`, password: 'Senha-Loja-1' },
    });
    expect(regB.status()).toBe(201);
    emptyMerchantId = (await regB.json()).id;

    const now = new Date().toISOString();
    const hourAgo = new Date(Date.now() - 3600_000).toISOString();
    const twoHoursAgo = new Date(Date.now() - 7200_000).toISOString();

    // Dados ricos da Spec 97 (via SQL — o painel lê merchants/pix_transactions/
    // bot_leads/plans/stores/withdrawals diretamente).
    const storeId = nodeCrypto.randomUUID();
    const planId = nodeCrypto.randomUUID();
    const botId = nodeCrypto.randomUUID();
    await client.query(
      `INSERT INTO stores (id, merchant_id, name, logo_url, banner_url, description, social_links, color, emoji, title, footer, created_at, updated_at)
       VALUES ($1, $2, 'Loja Teste', NULL, NULL, 'Loja de teste do painel', NULL, '#7c3aed', '🛒', 'Bem-vindo', NULL, $3, $3)`,
      [storeId, merchantId, now],
    );
    await client.query(
      `INSERT INTO plans (id, store_id, name, description, price, image_url, embed_color, featured, active, created_at, updated_at)
       VALUES ($1, $2, 'VIP Semanal', 'Plano de teste', 50.00, NULL, '#7c3aed', FALSE, TRUE, $3, $3)`,
      [planId, storeId, now],
    );
    await client.query(
      `INSERT INTO store_bots (id, merchant_id, platform, token_encrypted, token_hash, webhook_secret_hash, display_name, username, application_id, status, error_message, last_health_check_at, deleted_at, created_at, updated_at)
       VALUES ($1, $2, 'TELEGRAM', $3, $4, $5, 'Bot Teste', 'bot_teste', NULL, 'ACTIVE', NULL, NULL, NULL, $6, $6)`,
      // token_encrypted com ciphertext VÁLIDO (encryptAtRest) e hashes únicos
      // por run: linhas inválidas/estáticas quebram /master/bots (converter
      // decripta) e colidem com a unicidade de token_hash em re-execuções.
      [botId, merchantId, encryptAtRest('fake-token'), sha256(`token-panel-${ts}`), sha256(`secret-panel-${ts}`), now],
    );
    await client.query(
      `INSERT INTO bot_leads (id, bot_id, platform_user_id, first_seen_at, created_at, name, username, email, phone, status, updated_at, handoff_status)
       VALUES ($1, $2, 'user-123', $3, $3, 'Cliente Teste', 'cliente_teste', NULL, NULL, 'PAGO', $3, 'BOT')`,
      [nodeCrypto.randomUUID(), botId, now],
    );

    // Transações: APPROVED (mais recente, com bot/plano → comprador+produto),
    // PENDING (sem bot/plano → buyerIdHash/“—”) e REFUNDED (→ MED% 100%).
    const tx1 = nodeCrypto.randomUUID();
    const tx2 = nodeCrypto.randomUUID();
    const tx3 = nodeCrypto.randomUUID();
    await client.query(
      `INSERT INTO pix_transactions (id, trace_id, merchant_id, amount, description, pix_key, end_to_end_id, status, created_at, updated_at, paid_at, buyer_id_hash, plan_id, platform, platform_user_id, bot_id, external_reference, provider)
       VALUES
        ($1, $1, $2, 100.00, 'Venda VIP', $10, NULL, 'APPROVED', $3, $3, $3, 'buyerhash111', $4, 'TELEGRAM', $11, $5, 'ext-ref-1', 'MOCK'),
        ($6, $6, $2, 50.00, 'Venda pendente', $12, NULL, 'PENDING', $7, $7, NULL, NULL, NULL, NULL, NULL, NULL, 'ext-ref-2', 'MOCK'),
        ($8, $8, $2, 30.00, 'Venda estornada', $13, NULL, 'REFUNDED', $9, $9, NULL, 'buyerhash333', NULL, NULL, NULL, NULL, 'ext-ref-3', 'MOCK')`,
      // pix_key e platform_user_id são criptografados em repouso (CryptoConverter) —
      // o seed precisa de ciphertext válido ou a leitura da entidade falha.
      [tx1, merchantId, now, planId, botId, tx2, hourAgo, tx3, twoHoursAgo,
        encryptAtRest('chave-pix-1'), encryptAtRest('user-123'),
        encryptAtRest('chave-pix-2'), encryptAtRest('chave-pix-3')],
    );
    // Saldo do wallet para o KPI (o painel lê wallet_balance diretamente).
    await client.query('UPDATE merchants SET wallet_balance = 150.00 WHERE id = $1', [merchantId]);
    // Saque COMPLETED com taxa (Spec 88) + chave Pix mascarada (Spec 32).
    await client.query(
      `INSERT INTO withdrawals (id, merchant_id, amount, status, created_at, completed_at, rejected_at, destination_id, type, reason, destination_key_snapshot, external_reference, payout_provider, failure_reason, failed_at, fee_amount)
       VALUES ($1, $2, 10.00, 'COMPLETED', $3, $3, NULL, NULL, 'MERCHANT', NULL, $4, 'ext-w-1', 'MOCK', NULL, NULL, 2.50)`,
      [nodeCrypto.randomUUID(), merchantId, now, encryptAtRest('PIX_CPF:52998224725')],
    );
    await client.end();
  });

  async function loginAs(page) {
    await page.goto('/console/login');
    await page.getByLabel('Usuário').fill(username);
    await page.getByLabel('Senha').fill(PASSWORD);
    await page.getByRole('button', { name: 'Entrar' }).click();
    await page.waitForURL('**/console/dashboard');
  }

  test('1. tabela rica: MED% com contagem, conversão, Pix pagos, gateway, taxas + menu de ações', async ({ page }) => {
    await loginAs(page);
    await page.getByRole('link', { name: 'Lojistas' }).click();
    await page.waitForURL('**/console/merchants');

    const row = page.locator('tbody tr', { hasText: 'Panel Loja E2E' }).first();
    await expect(row).toContainText('Aprovado'); // badge de status ACTIVE
    // MED% = 1 estornado ÷ 1 pago = 100% (vermelho) com contagem.
    await expect(row).toContainText('100,00%');
    await expect(row).toContainText('/ 1');
    // Conversão = 1 pago ÷ 3 criadas = 33,33%.
    await expect(row).toContainText('33,33%');
    await expect(row).toContainText('1'); // Pix pagos
    await expect(row).toContainText('MOCK'); // gateway da última cobrança
    // Taxas (Pix/Fixa/Saque).
    await expect(row).toContainText('R$ 0,50');
    await expect(row).toContainText('R$ 2,50');

    // Menu de ações abre com Ver detalhes / Ver shops / Config / Sacar.
    await row.getByRole('button', { name: /Ações/ }).click();
    await expect(page.getByRole('menu')).toContainText('Ver detalhes');
    await expect(page.getByRole('menu')).toContainText('Ver shops');
    await expect(page.getByRole('menu')).toContainText('Config');
    await expect(page.getByRole('menu')).toContainText('Sacar');
  });

  test('2. Ver Shops: modal com a loja + empty state no lojista sem dados', async ({ page }) => {
    await loginAs(page);
    await page.getByRole('link', { name: 'Lojistas' }).click();
    await page.waitForURL('**/console/merchants');

    // Lojista rico → modal com a store.
    await page.locator('tbody tr', { hasText: 'Panel Loja E2E' }).first().getByRole('button', { name: /Ações/ }).click();
    await page.getByRole('menu').getByRole('button', { name: 'Ver shops' }).click();
    const modal = page.getByRole('dialog');
    await expect(modal).toContainText('Shops de Panel Loja E2E');
    await expect(modal).toContainText('Loja Teste');
    await expect(modal).toContainText('Bem-vindo');
    await page.getByRole('button', { name: 'Fechar' }).click();
    await expect(modal).toBeHidden();

    // Lojista sem loja → empty state.
    await page.locator('tbody tr', { hasText: 'Panel Vazia E2E' }).first().getByRole('button', { name: /Ações/ }).click();
    await page.getByRole('menu').getByRole('button', { name: 'Ver shops' }).click();
    await expect(page.getByRole('dialog')).toContainText('Nenhuma loja cadastrada.');
  });

  test('3. perfil: KPIs + dados da conta + taxas/limites + aba Transações + aba Saques', async ({ page }) => {
    await loginAs(page);
    await page.getByRole('link', { name: 'Lojistas' }).click();
    await page.waitForURL('**/console/merchants');
    await page.locator('tbody tr', { hasText: 'Panel Loja E2E' }).first().getByRole('button', { name: /Ações/ }).click();
    await page.getByRole('menu').getByRole('link', { name: 'Ver detalhes' }).click();
    await page.waitForURL('**/console/merchants/*');
    await expect(page.locator('h1')).toContainText('Panel Loja E2E');

    // KPIs de topo (Spec 97): Saldo/Faturamento/Conversão/Shops.
    const saldoCard = page.locator('.card', { hasText: 'Saldo' }).filter({ hasNotText: 'Saldo do wallet' }).first();
    await expect(saldoCard).toContainText('R$ 150,00');
    const fatCard = page.locator('.card', { hasText: 'Faturamento' }).first();
    await expect(fatCard).toContainText('R$ 100,00');
    await expect(fatCard).toContainText('1 Pix pagos');
    await expect(page.locator('.card', { hasText: 'Conversão' }).first()).toContainText('33,33%');
    await expect(page.locator('.card', { hasText: 'Shops' }).first()).toContainText('1');

    // Dados da conta.
    const contaCard = page.locator('.card', { hasText: 'Dados da conta' }).first();
    await expect(contaCard).toContainText(`panel-${ts}@example.com`);
    await expect(contaCard).toContainText('—'); // CPF/CNPJ (cadastro sem documento)
    await expect(contaCard).toContainText('MOCK');

    // Taxas e limites.
    const taxasCard = page.locator('.card', { hasText: 'Taxas e limites' }).first();
    await expect(taxasCard).toContainText('R$ 0,50');
    await expect(taxasCard).toContainText('R$ 2,50');
    await expect(taxasCard).toContainText('100,00% / 1');

    // Aba Transações: comprador (lead), produto (plano), líquido (bruto − taxa).
    // Escopo em #main-content: 'Transações' também existe na nav lateral.
    const main = page.locator('#main-content');
    await main.getByRole('link', { name: 'Transações', exact: true }).click();
    await expect(page.locator('table')).toContainText('Cliente Teste');
    await expect(page.locator('table')).toContainText('VIP Semanal');
    await expect(page.locator('table')).toContainText('ext-ref-1');
    await expect(page.locator('table')).toContainText('R$ 99,50'); // 100 − 0,50
    await expect(page.locator('table')).toContainText('PENDENTE'); // tx sem lead

    // Aba Saques: taxa + chave mascarada + rótulo CONCLUIDO.
    await main.getByRole('link', { name: 'Saques', exact: true }).click();
    await expect(page.locator('table')).toContainText('CONCLUIDO');
    await expect(page.locator('table')).toContainText('529.***.***-25');
    await expect(page.locator('table')).toContainText('R$ 2,50');
  });

  test('4. 401: logout → rota privada redireciona; API master sem token → 401', async ({ page, request }) => {
    await loginAs(page);
    await page.getByRole('button', { name: 'Sair' }).click();
    await page.waitForURL('**/console/login');

    await page.goto('/console/dashboard');
    await page.waitForURL('**/console/login');

    const noAuth = await request.get('/api/v1/master/me');
    expect(noAuth.status()).toBe(401);
  });
});
