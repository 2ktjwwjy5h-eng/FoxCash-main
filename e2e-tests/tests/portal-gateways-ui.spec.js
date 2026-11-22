// @ts-check
// E2E BROWSER da página Gateways do portal (Spec 108):
//   - login → /gateways
//   - fila de fallback: Principal (Simulador) → Fallback 1 (Suitpay) — ordem da
//     policy da Spec 91 (no CI o routing é ligado com mock,suitpay)
//   - cards dos adquirentes com extrato do período (volume + contagem)
//   - catálogo de gateways com seções PIX/CARTÃO/CRIPTO + badges
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

test.describe.serial('Portal — Gateways UI (Spec 108)', () => {
  const ts = Date.now();
  const email = `gateways-${ts}@example.com`;
  const password = 'Senha-Gateways-1';

  test.beforeAll(async ({ request }) => {
    const reg = await request.post('/api/v1/merchants', {
      data: { name: 'Loja Gateways E2E', email, password },
    });
    expect(reg.status()).toBe(201);
    const merchant = await reg.json();

    const login = await request.post('/api/v1/merchant-auth/login', {
      data: { email, password },
    });
    expect(login.status()).toBe(200);

    // Seed de transação APPROVED no mês corrente com provider 'mock' — extrato
    // do card Simulador (routing do CI: mock,suitpay). pix_key criptografado.
    const client = dbClient();
    await client.connect();
    try {
      const txId = nodeCrypto.randomUUID();
      await client.query(
        `INSERT INTO pix_transactions (id, trace_id, merchant_id, amount, description, pix_key, end_to_end_id, status, created_at, updated_at, paid_at, buyer_id_hash, external_reference, provider)
         VALUES ($1, $1, $2, 123.45, 'Venda gateway E2E', $3, NULL, 'APPROVED', $4, $4, $4, 'buyerhashgw', 'ext-ref-gw', 'mock')`,
        [txId, merchant.id, encryptAtRest('chave-pix-gw'), new Date().toISOString()],
      );
    } finally {
      await client.end();
    }
  });

  test('gateways: fallback chain + cards com extrato + catálogo', async ({ page }) => {
    // --- Login (rota pública) ----------------------------------------------
    await page.goto('/login');
    await page.getByLabel('E-mail').fill(email);
    await page.getByLabel('Senha').fill(password);
    await page.getByRole('button', { name: 'Entrar' }).click();
    await page.waitForURL('**/dashboard');

    // --- Navega pelo menu lateral ------------------------------------------
    await page.getByRole('link', { name: 'Gateways' }).click();
    await page.waitForURL('**/gateways');
    await expect(page.locator('h1')).toHaveText('Gateways');

    // --- Fila de fallback (ordem da policy da Spec 91) ---------------------
    const chain = page.locator('.fallback-chain');
    await expect(chain).toBeVisible();
    // Principal = Simulador (mock), Fallback 1 = Suitpay (routing do CI).
    await expect(page.locator('.fallback-node').nth(0)).toContainText('Simulador');
    await expect(page.locator('.fallback-node').nth(0)).toContainText('Principal');
    await expect(page.locator('.fallback-node').nth(1)).toContainText('Suitpay');
    await expect(page.locator('.fallback-node').nth(1)).toContainText('Fallback 1');
    await expect(page.getByText('Rotação automática em falha')).toBeVisible();

    // --- Cards dos adquirentes com extrato do mês ---------------------------
    const cards = page.locator('.gateway-card');
    await expect(cards).toHaveCount(2);
    await expect(cards.nth(0)).toContainText('Simulador');
    await expect(cards.nth(0)).toContainText('R$ 123,45');
    await expect(cards.nth(0)).toContainText('1 pagamento no mês');

    // --- Catálogo com seções Pix/Cartão/Cripto -----------------------------
    await expect(page.locator('.gateway-catalog-title')).toHaveText(['PIX', 'CARTÃO', 'CRIPTO']);
    await expect(page.locator('.gateway-catalog-item', { hasText: 'Pagar.me' }).first()).toBeVisible();
    await expect(page.locator('.gateway-catalog-item', { hasText: 'Simulador' }).first())
      .toContainText('Conectado');
    await expect(page.locator('.gateway-catalog-item', { hasText: 'Cielo' }).first())
      .toContainText('Em breve');
    await expect(page.locator('.gateway-catalog-item', { hasText: 'BitPay' }).first())
      .toContainText('Em breve');
  });

  test('gateways: API sem token responde 401', async ({ request }) => {
    const res = await request.get('/api/v1/portal/gateways');
    expect(res.status()).toBe(401);
  });
});
